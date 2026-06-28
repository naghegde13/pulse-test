package com.pulse.expression.service;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates SQL expressions used in pipeline blueprints — derived-column
 * expressions, filter / router predicates, join conditions, DQ custom SQL.
 *
 * <p>Phase 1 capability:
 * <ul>
 *   <li>Parse the expression text with Calcite (lenient/Babel conformance to
 *       accept dbt/Spark idioms like backticks and `LIKE`).</li>
 *   <li>Walk the AST collecting {@link SqlIdentifier} references to columns.</li>
 *   <li>Diagnose unknown columns against the supplied input-port schemas
 *       and surface their qualified-name shape (port.column or column).</li>
 *   <li>Return a stable JSON-friendly result the front end can render
 *       inline as the user types.</li>
 * </ul>
 *
 * <p>Type inference (taking the validated rowtype's first column type) is a
 * Phase 2 enhancement and requires a full {@code Frameworks.getPlanner(...)}
 * setup with virtual tables per port. The current implementation returns
 * {@code outputType="unknown"} but reports parse/column diagnostics that are
 * already enough to make the configure-dialog feel responsive.
 */
@Service
public class ExpressionValidationService {

    public ValidationResult validate(ValidationRequest request) {
        String expression = request.expression() == null ? "" : request.expression().trim();
        if (expression.isEmpty()) {
            return new ValidationResult(false, "unknown", List.of(),
                    List.of(new Diagnostic("error", "EMPTY_EXPRESSION",
                            "Expression is empty.", null, null)));
        }

        // Pre-escape qualified references like LEFT.account_id where the port
        // name collides with a SQL reserved word. The standard PULSE join port
        // convention is "left" / "right" (see SchemaPropagationService) and
        // even Calcite's Babel parser refuses to accept those bare. We rewrite
        // them as `left`.account_id transparently before parsing — the user
        // never sees the backticks.
        String preprocessed = escapeReservedPortPrefixes(expression);

        // Parse the expression in isolation — boolean predicates are valid SQL
        // expressions, so parseExpression covers both kinds.
        SqlNode parsed;
        try {
            parsed = parserFor(preprocessed).parseExpression();
        } catch (SqlParseException e) {
            return new ValidationResult(false, "unknown", List.of(),
                    List.of(parseExceptionToDiagnostic(e)));
        }

        List<ReferencedColumn> refs = collectReferences(parsed);
        List<Diagnostic> diags = checkUnknownColumns(refs, request.inputSchemas());
        boolean valid = diags.stream().noneMatch(d -> "error".equals(d.severity()));
        return new ValidationResult(valid, "unknown", refs, diags);
    }

    private static final Pattern RESERVED_PORT_PREFIX = Pattern.compile(
            "(?<![A-Za-z0-9_])(left|right|inner|outer|full|cross|natural|order|group|having)\\s*\\.",
            Pattern.CASE_INSENSITIVE);

    /**
     * Backtick-quotes any reserved-word port name immediately followed by '.'
     * — only the port-prefix segment, not the column. {@code LEFT.account_id}
     * becomes {@code `LEFT`.account_id} which the Babel parser accepts.
     * No-op for non-reserved port names.
     */
    private static String escapeReservedPortPrefixes(String expression) {
        Matcher m = RESERVED_PORT_PREFIX.matcher(expression);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, "`" + Matcher.quoteReplacement(m.group(1)) + "`.");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static SqlParser parserFor(String text) {
        // Babel parser config is shared verbatim with CalciteSqlModelValidator
        // (SPEC #6 §A.2) via SqlParserConfigFactory. Behavior is byte-identical
        // to the prior inline config (factory holds the same chained calls); we
        // pre-escape reserved-word port prefixes like LEFT.account_id with
        // backticks before parsing here.
        return SqlParserConfigFactory.parserFor(text);
    }

    private static Diagnostic parseExceptionToDiagnostic(SqlParseException e) {
        SqlParserPos pos = e.getPos();
        Integer line = pos != null ? pos.getLineNum() : null;
        Integer col = pos != null ? pos.getColumnNum() : null;
        String msg = e.getMessage() == null ? "Parse error" : e.getMessage();
        // Calcite messages tend to be verbose; keep the first line which is the
        // human-readable summary.
        int newline = msg.indexOf('\n');
        if (newline > 0) msg = msg.substring(0, newline);
        return new Diagnostic("error", "PARSE_ERROR", msg, line, col);
    }

    /**
     * Walks the parsed {@link SqlNode} collecting {@link SqlIdentifier} column
     * references. Function names that Calcite models as identifiers (e.g. bare
     * {@code current_date}) are filtered downstream by {@link #isLikelyFunctionOrKeyword}.
     */
    private static List<ReferencedColumn> collectReferences(SqlNode root) {
        Set<String> seen = new LinkedHashSet<>();
        List<ReferencedColumn> out = new ArrayList<>();
        walk(root, seen, out);
        return out;
    }

    private static void walk(SqlNode node, Set<String> seen, List<ReferencedColumn> out) {
        if (node == null) return;
        if (node instanceof SqlNodeList list) {
            for (SqlNode child : list) walk(child, seen, out);
            return;
        }
        if (node instanceof SqlIdentifier id) {
            if (id.names == null || id.names.isEmpty()) return;
            String first = id.names.get(0);
            String port = null;
            String column = first;
            if (id.names.size() >= 2) {
                port = first;
                column = id.names.get(1);
            }
            String key = (port == null ? "" : port + ".") + column;
            if (seen.add(key.toLowerCase())) {
                out.add(new ReferencedColumn(port, column));
            }
            return;
        }
        if (node instanceof SqlCall call) {
            for (int i = 0; i < call.operandCount(); i++) {
                walk(call.operand(i), seen, out);
            }
            return;
        }
        if (node instanceof SqlLiteral) return;
    }

    /**
     * Compares each referenced column against the supplied input-port schemas.
     * Resolution rules:
     *   <ul>
     *     <li>Qualified ({@code port.column}) — must match the port and a column on it.</li>
     *     <li>Unqualified ({@code column}) — must match a column on at least one port.</li>
     *   </ul>
     * Both matches are case-insensitive.
     */
    private static List<Diagnostic> checkUnknownColumns(List<ReferencedColumn> refs,
                                                        List<InputSchema> schemas) {
        if (refs.isEmpty()) return List.of();
        Map<String, Set<String>> columnsByPort = new LinkedHashMap<>();
        Set<String> allColumns = new LinkedHashSet<>();
        if (schemas != null) {
            for (InputSchema s : schemas) {
                Set<String> cols = new LinkedHashSet<>();
                if (s.columns() != null) {
                    for (SchemaColumn c : s.columns()) {
                        if (c != null && c.name() != null) {
                            String lower = c.name().toLowerCase();
                            cols.add(lower);
                            allColumns.add(lower);
                        }
                    }
                }
                columnsByPort.put(s.portName().toLowerCase(), cols);
            }
        }
        List<Diagnostic> diags = new ArrayList<>();
        for (ReferencedColumn ref : refs) {
            String column = ref.column().toLowerCase();
            if (ref.port() != null) {
                Set<String> portCols = columnsByPort.get(ref.port().toLowerCase());
                if (portCols == null) {
                    diags.add(new Diagnostic("error", "UNKNOWN_PORT",
                            "Unknown port '" + ref.port() + "'.", null, null));
                } else if (!portCols.contains(column)) {
                    diags.add(new Diagnostic("error", "UNKNOWN_COLUMN",
                            "Column '" + ref.column() + "' is not on port '" + ref.port() + "'.",
                            null, null));
                }
            } else {
                if (!allColumns.contains(column) && !isLikelyFunctionOrKeyword(ref.column())) {
                    diags.add(new Diagnostic("warning", "UNKNOWN_COLUMN",
                            "Column '" + ref.column() + "' was not found on any input port.",
                            null, null));
                }
            }
        }
        return diags;
    }

    /**
     * Calcite's parser sometimes models bare function names ({@code current_date},
     * {@code current_timestamp}, {@code true}, {@code null}) as SqlIdentifier
     * rather than SqlCall when used without parentheses. Don't flag these as
     * unknown columns.
     */
    private static boolean isLikelyFunctionOrKeyword(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return KEYWORD_LIKE.contains(n);
    }

    private static final Set<String> KEYWORD_LIKE = Set.of(
            "current_date", "current_timestamp", "current_time", "current_user",
            "session_user", "system_user", "true", "false", "null", "unknown",
            "localtimestamp", "localtime");

    // ------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------

    public record ValidationRequest(
            String expression,
            String kind, // "value" | "predicate"
            List<InputSchema> inputSchemas,
            String expectedType // optional — caller's expected output type for value expressions
    ) {}

    public record InputSchema(String portName, List<SchemaColumn> columns) {}

    public record SchemaColumn(String name, String type) {}

    public record ValidationResult(
            boolean valid,
            String outputType,
            List<ReferencedColumn> referencedColumns,
            List<Diagnostic> diagnostics
    ) {}

    public record ReferencedColumn(String port, String column) {}

    public record Diagnostic(
            String severity, // "error" | "warning"
            String code,
            String message,
            Integer line,
            Integer column
    ) {}
}
