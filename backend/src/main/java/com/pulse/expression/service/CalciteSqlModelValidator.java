package com.pulse.expression.service;

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorCatalogReader;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.tools.Frameworks;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The deterministic, schema-DERIVING Calcite validator for the {@code sql-model}
 * op (SPEC #6 §A). It is the named "Phase 2" of {@link ExpressionValidationService}
 * (that service's javadoc :36-42): the parse-only Babel validator grown into a
 * schema-deriving one — it adds a {@link SqlValidator} + virtual catalog tables
 * per relation + {@link RelDataType} derivation from the terminal {@code SELECT}.
 *
 * <p><b>Scope (LOCKED 2026-06-16):</b> {@code sql-model} ONLY. {@code SourceSQL}
 * validates via its bound JDBC source (SPEC #6 §C.2), not Calcite — so this
 * validator carries a <b>single</b> catalog context (the {@code input} schema +
 * accumulated prior-step schemas), never a discovered-source catalog.
 *
 * <p><b>Determinism (SPEC #6 §A.7, ADR 0009):</b> a pure function of
 * {@code (orderedSql, catalog, registry, typeMap)} — same inputs yield the same
 * schema, byte-for-byte. <b>No LLM, no network, no clock</b>. It never executes
 * SQL and never resolves a date value (the {@code [[ … ]]} token is typed as a
 * {@code DATE} placeholder for parse/derivation only, SPEC #6 §D.3, via
 * {@link DateMnemonicTokenScanner}).
 *
 * <p><b>ADR 0011:</b> there is no silent pass and no AI fallback. The only
 * non-schema outcomes are a structured {@link BuildFailure} (single-statement
 * validation) or a {@link BuildFailureException} (the chain resolver, after the
 * optional {@code declared_output_schema} fallback, SPEC #6 §A.6). This service
 * NEVER calls {@code schemaInferenceService} or any LLM path.
 */
@Service
public class CalciteSqlModelValidator {

    /** The reserved relation name a {@code sql-model} step uses for its op input (SPEC #6 §B.1). */
    public static final String INPUT_RELATION = "input";

    /** Shared type factory (no clock / network state). */
    private final RelDataTypeFactory typeFactory =
            new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);

    /** The extensible Spark function set + Calcite std built-ins (SPEC #6 §A.2). */
    private final SqlOperatorTable operatorTable = SparkSqlFunctionRegistry.operatorTable();

    // ============================================================
    // A. Single-statement validation (SPEC #6 §A.1 / §A.4 / §A.5)
    // ============================================================

    /**
     * Validates ONE SQL statement against the supplied catalog and derives its
     * output schema, OR returns a structured {@link BuildFailure} on any error
     * (parse / unknown column / unknown relation / unresolved type / unregistered
     * function / unaliased expression / duplicate output name / unmappable type).
     *
     * <p>The {@code catalog} maps relation name → PULSE column list. {@code [[ … ]]}
     * tokens in {@code sql} are typed as {@code DATE} placeholders (never resolved).
     *
     * @return the derived PULSE column-descriptor list (left), or the failure (right).
     */
    public SqlModelResult validateStatement(String sql, Map<String, List<Map<String, Object>>> catalog) {
        // §D.3: type each [[ … ]] token as a DATE placeholder for parse/derivation.
        String prepared = DateMnemonicTokenScanner.substituteDatePlaceholders(sql);

        SchemaPlus rootSchema = buildRootSchema(catalog);
        SqlValidator validator = newValidator(rootSchema);

        SqlNode parsed;
        try {
            parsed = SqlParserConfigFactory.parserFor(prepared).parseStmt();
        } catch (SqlParseException e) {
            return SqlModelResult.failure(parseFailure(e));
        }

        // §A.4 author-error: an output column that is an expression with NO explicit
        // alias is a loud fail (no synthesized EXPR$n). Checked on the AST before
        // validation so the message names the offending position deterministically.
        BuildFailure unaliased = checkUnaliasedExpression(parsed);
        if (unaliased != null) {
            return SqlModelResult.failure(unaliased);
        }

        SqlNode validated;
        RelDataType rowType;
        try {
            validated = validator.validate(parsed);
            rowType = validator.getValidatedNodeType(validated);
        } catch (CalciteContextException e) {
            return SqlModelResult.failure(contextFailure(e));
        } catch (PulseCalciteTypeMap.UnmappableTypeException e) {
            return SqlModelResult.failure(BuildFailure.of(
                    BuildFailure.Cause.UNMAPPABLE_TYPE, e.getMessage()));
        } catch (RuntimeException e) {
            // Any other validation error — surface as an unresolved-type loud fail
            // (ADR 0011: never swallow, never guess).
            return SqlModelResult.failure(BuildFailure.of(
                    BuildFailure.Cause.UNRESOLVED_TYPE, firstLine(e.getMessage())));
        }

        // §A.4: derive output columns from the terminal SELECT's validated row type;
        // reverse-map each Calcite field type to a PULSE column (nested-recursive).
        List<Map<String, Object>> columns;
        try {
            columns = PulseCalciteTypeMap.rowTypeToColumns(rowType);
        } catch (PulseCalciteTypeMap.UnmappableTypeException e) {
            return SqlModelResult.failure(BuildFailure.of(
                    BuildFailure.Cause.UNMAPPABLE_TYPE, e.getMessage()));
        }

        // §A.4: duplicate output column name is a loud fail.
        BuildFailure dup = checkDuplicateOutputNames(columns);
        if (dup != null) {
            return SqlModelResult.failure(dup);
        }

        return SqlModelResult.schema(columns);
    }

    // ============================================================
    // B. The sql-model chain walker (SPEC #6 §B.2)
    // ============================================================

    /**
     * Walks a {@code sql-model} chain's {@code steps[]} in order against an
     * accumulating catalog (the {@code input} schema + every prior step's derived
     * schema), registers each step under its {@code name}, and returns the LAST
     * step's derived schema (SPEC #6 §B.2). {@code materialize} is
     * schema-irrelevant and is ignored here (SPEC #6 §B.3).
     *
     * <p>This is the EITHER/OR derivation primary (SPEC #6 §A.6): on a Calcite
     * parse/validation error it returns the {@link BuildFailure}; the
     * declared-schema fallback is applied by {@link #resolveSqlModelSchema}.
     *
     * @param inputSchema the upstream {@code input} relation's PULSE column list.
     * @param steps       the ordered chain steps.
     * @return the last step's derived schema (left) or the first failure (right).
     */
    public SqlModelResult validateChain(List<Map<String, Object>> inputSchema, List<SqlModelStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return SqlModelResult.failure(BuildFailure.of(
                    BuildFailure.Cause.PARSE_ERROR, "sql-model chain has no steps"));
        }

        // The accumulating catalog: input first, then each step's derived schema.
        Map<String, List<Map<String, Object>>> catalog = new LinkedHashMap<>();
        catalog.put(INPUT_RELATION, inputSchema == null ? List.of() : inputSchema);

        Set<String> seenNames = new LinkedHashSet<>();
        List<Map<String, Object>> lastSchema = null;

        for (SqlModelStep step : steps) {
            String name = step.name();
            if (name == null || name.isBlank()) {
                return SqlModelResult.failure(BuildFailure.of(
                        BuildFailure.Cause.PARSE_ERROR, "sql-model step has no name"));
            }
            // §B.1: unique step names within the chain.
            if (!seenNames.add(name.toLowerCase())) {
                return SqlModelResult.failure(BuildFailure.of(
                        BuildFailure.Cause.STEP_NAME_COLLISION,
                        "duplicate sql-model step name: '" + name + "'"));
            }
            // A step name must not shadow the reserved input relation.
            if (INPUT_RELATION.equalsIgnoreCase(name)) {
                return SqlModelResult.failure(BuildFailure.of(
                        BuildFailure.Cause.STEP_NAME_COLLISION,
                        "sql-model step name collides with reserved relation '" + INPUT_RELATION + "'"));
            }

            SqlModelResult stepResult = validateStatement(step.sql(), catalog);
            if (stepResult.isFailure()) {
                return stepResult; // first failure short-circuits the chain (§A.5).
            }
            lastSchema = stepResult.schema();
            // Register this step's derived schema under its name for later steps.
            catalog.put(name, lastSchema);
        }

        return SqlModelResult.schema(lastSchema);
    }

    // ============================================================
    // A.6 Declare-schema EITHER/OR fallback (SPEC #6 §A.6)
    // ============================================================

    /**
     * Resolves the OUT schema of a {@code sql-model} chain with the SPEC #6 §A.6
     * EITHER/OR fallback: derivation (Calcite) is <b>primary and wins</b>; on a
     * Calcite parse/validation <b>error</b> it falls back to the optional
     * {@code declared_output_schema} (an explicit Customer/DE declaration —
     * ADR-0011-legal, not a guess), else it fails the build <b>loudly</b>.
     *
     * <p>There is NO cross-check of declared-vs-derived and NO fail-on-mismatch:
     * derivation either succeeds (and wins) or errors (and the declared schema,
     * if any, takes over). This method NEVER calls an LLM / schema-inference path.
     *
     * @param inputSchema      the upstream {@code input} schema.
     * @param steps            the chain steps.
     * @param declaredSchema   the optional {@code declared_output_schema} (may be {@code null}).
     * @return the resolved PULSE column list (derived, else declared).
     * @throws BuildFailureException if derivation errors AND no declared schema is present.
     */
    public List<Map<String, Object>> resolveSqlModelSchema(
            List<Map<String, Object>> inputSchema,
            List<SqlModelStep> steps,
            List<Map<String, Object>> declaredSchema) {

        SqlModelResult result = validateChain(inputSchema, steps);
        if (!result.isFailure()) {
            return result.schema(); // derivation wins (§A.6).
        }
        // Calcite error: EITHER/OR fallback to the declared schema if present.
        if (declaredSchema != null) {
            // SPEC #6 §A.6: "use the declared schema and log a warning".
            org.slf4j.LoggerFactory.getLogger(CalciteSqlModelValidator.class).warn(
                    "sql-model schema derivation failed ({}); falling back to declared_output_schema. cause={}",
                    result.failure().message(), result.failure().cause());
            return declaredSchema;
        }
        // No declared schema -> loud build-fail (ADR 0011: never a guess, never an LLM).
        throw new BuildFailureException(result.failure());
    }

    // ============================================================
    // Catalog / validator wiring
    // ============================================================

    /** Builds a Calcite root schema with one virtual table per catalog relation. */
    private SchemaPlus buildRootSchema(Map<String, List<Map<String, Object>>> catalog) {
        SchemaPlus rootSchema = Frameworks.createRootSchema(true);
        if (catalog != null) {
            for (Map.Entry<String, List<Map<String, Object>>> e : catalog.entrySet()) {
                rootSchema.add(e.getKey(), new PulseVirtualTable(e.getValue()));
            }
        }
        return rootSchema;
    }

    /**
     * Builds a {@link SqlValidator} over the catalog schema with the Babel
     * conformance + the Spark function table. Case-insensitive name matching to
     * mirror {@link SqlParserConfigFactory#babelConfig()}'s {@code withCaseSensitive(false)}.
     */
    private SqlValidator newValidator(SchemaPlus rootSchema) {
        CalciteConnectionConfig connectionConfig = new CalciteConnectionConfigImpl(new java.util.Properties())
                .set(CalciteConnectionProperty.CASE_SENSITIVE, "false");

        SqlValidatorCatalogReader catalogReader = new org.apache.calcite.prepare.CalciteCatalogReader(
                org.apache.calcite.jdbc.CalciteSchema.from(rootSchema),
                List.of(), // default (empty) schema path: tables resolve by simple name
                javaTypeFactory(),
                connectionConfig);

        return SqlValidatorUtil.newValidator(
                operatorTable,
                catalogReader,
                javaTypeFactory(),
                org.apache.calcite.sql.validate.SqlValidator.Config.DEFAULT
                        .withConformance(org.apache.calcite.sql.validate.SqlConformanceEnum.BABEL)
                        .withIdentifierExpansion(true));
    }

    /**
     * A {@link org.apache.calcite.jdbc.JavaTypeFactory} for the catalog reader.
     * The reverse map in {@link PulseCalciteTypeMap} works off {@link RelDataType}
     * produced by this same factory family, so types round-trip.
     */
    private org.apache.calcite.adapter.java.JavaTypeFactory javaTypeFactory() {
        return new org.apache.calcite.jdbc.JavaTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    }

    /** A Calcite virtual table whose row type is a PULSE column list (§A, §A.3 forward map). */
    private final class PulseVirtualTable extends AbstractTable {
        private final List<Map<String, Object>> columns;

        PulseVirtualTable(List<Map<String, Object>> columns) {
            this.columns = columns == null ? List.of() : columns;
        }

        @Override
        public RelDataType getRowType(RelDataTypeFactory factory) {
            return PulseCalciteTypeMap.columnsToRowType(columns, factory);
        }

        @Override
        public Statistic getStatistic() {
            return Statistics.UNKNOWN;
        }
    }

    // ============================================================
    // §A.4 author-error checks (on the AST)
    // ============================================================

    /**
     * Detects an output column that is an EXPRESSION (not a bare column reference)
     * with NO explicit alias on the terminal/outer SELECT (SPEC #6 §A.4). Such a
     * column would otherwise get Calcite's synthesized {@code EXPR$n} name; the
     * spec requires the author to supply an explicit alias instead.
     */
    private BuildFailure checkUnaliasedExpression(SqlNode stmt) {
        SqlSelect select = terminalSelect(stmt);
        if (select == null) {
            return null; // not a SELECT we introspect (e.g. VALUES) — leave to validation.
        }
        SqlNodeList selectList = select.getSelectList();
        if (selectList == null) {
            return null;
        }
        for (SqlNode item : selectList) {
            if (item == null) {
                continue;
            }
            // `expr AS alias` -> explicit alias present, fine.
            if (item.getKind() == SqlKind.AS) {
                continue;
            }
            // A bare column reference (a.b or b) is allowed — its name is the column.
            if (item instanceof SqlIdentifier) {
                continue;
            }
            // SELECT * / SELECT a.* expand to source columns — allowed.
            if (item instanceof SqlIdentifier == false && isStar(item)) {
                continue;
            }
            // Anything else is an expression without an explicit alias -> loud fail.
            SqlParserPos pos = item.getParserPosition();
            BuildFailure.SqlPosition sp = (pos == null) ? null
                    : new BuildFailure.SqlPosition(pos.getLineNum(), pos.getColumnNum());
            return BuildFailure.of(BuildFailure.Cause.UNALIASED_EXPRESSION, sp,
                    "SELECT output expression has no explicit alias; add 'AS <name>' "
                            + "(deterministic output naming, no synthesized EXPR$n)");
        }
        return null;
    }

    private static boolean isStar(SqlNode node) {
        if (node instanceof SqlIdentifier id) {
            return id.isStar();
        }
        if (node instanceof SqlBasicCall call) {
            return call.getKind() == SqlKind.IDENTIFIER && call.getOperandList().stream()
                    .anyMatch(CalciteSqlModelValidator::isStar);
        }
        return false;
    }

    /**
     * Unwraps a statement to its terminal/outer {@link SqlSelect} for alias
     * inspection: {@code SqlOrderBy} wraps a query, {@code SqlWith} wraps the
     * final body. Set operations (UNION, etc.) are left to Calcite's validation.
     */
    private static SqlSelect terminalSelect(SqlNode stmt) {
        SqlNode node = stmt;
        if (node instanceof SqlOrderBy orderBy) {
            node = orderBy.query;
        }
        if (node instanceof SqlWith with) {
            node = with.body;
        }
        if (node instanceof SqlOrderBy orderBy) {
            node = orderBy.query;
        }
        if (node instanceof SqlSelect select) {
            return select;
        }
        return null;
    }

    /** SPEC #6 §A.4: two output columns resolving to the same name is a loud fail. */
    private static BuildFailure checkDuplicateOutputNames(List<Map<String, Object>> columns) {
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> col : columns) {
            String name = String.valueOf(col.get(PulseCalciteTypeMap.K_NAME));
            if (!seen.add(name.toLowerCase())) {
                return BuildFailure.of(BuildFailure.Cause.DUPLICATE_OUTPUT_NAME,
                        "duplicate output column name: '" + name + "'");
            }
        }
        return null;
    }

    // ============================================================
    // Failure classification (SPEC #6 §A.5, honestly documented in BuildFailure)
    // ============================================================

    private static BuildFailure parseFailure(SqlParseException e) {
        SqlParserPos pos = e.getPos();
        BuildFailure.SqlPosition sp = (pos == null) ? null
                : new BuildFailure.SqlPosition(pos.getLineNum(), pos.getColumnNum());
        return BuildFailure.of(BuildFailure.Cause.PARSE_ERROR, sp, firstLine(e.getMessage()));
    }

    /**
     * Classifies a {@link CalciteContextException} (the validator's unified
     * "object not found" family) into {@link BuildFailure.Cause#UNKNOWN_RELATION},
     * {@link BuildFailure.Cause#UNKNOWN_COLUMN}, or
     * {@link BuildFailure.Cause#UNREGISTERED_FUNCTION}. Calcite collapses
     * column-not-found and table-not-found into the same exception family with
     * messages that are not reliably machine-distinguishable across versions
     * (documented honestly in {@link BuildFailure}); we key on the message shape.
     */
    private static BuildFailure contextFailure(CalciteContextException e) {
        Integer line = e.getPosLine() > 0 ? e.getPosLine() : null;
        Integer col = e.getPosColumn() > 0 ? e.getPosColumn() : null;
        BuildFailure.SqlPosition sp = (line == null && col == null) ? null
                : new BuildFailure.SqlPosition(line, col);

        String msg = e.getMessage() == null ? "" : e.getMessage();
        // Calcite nests the real cause in the context exception's cause message.
        String detail = (e.getCause() != null && e.getCause().getMessage() != null)
                ? e.getCause().getMessage() : msg;
        String lower = detail.toLowerCase();

        // Classification order matters: Calcite phrases an unknown COLUMN as
        // "Column 'x' not found in any table", which also contains the word
        // "table" — so the column-shaped checks MUST precede the relation check
        // (BuildFailure honesty note: ambiguous identifier-misses default to
        // UNKNOWN_COLUMN). Function-signature misses are most specific, first.
        BuildFailure.Cause cause;
        if (lower.contains("no match found for function signature")
                || (lower.contains("function") && lower.contains("not found"))
                || lower.contains("unknown function")) {
            cause = BuildFailure.Cause.UNREGISTERED_FUNCTION;
        } else if (lower.contains("column") && lower.contains("not found")) {
            cause = BuildFailure.Cause.UNKNOWN_COLUMN;
        } else if ((lower.contains("object") && lower.contains("not found"))
                || (lower.contains("table") && lower.contains("not found"))) {
            cause = BuildFailure.Cause.UNKNOWN_RELATION;
        } else if (lower.contains("cannot apply") || lower.contains("cast")
                || lower.contains("incompatible")) {
            cause = BuildFailure.Cause.UNRESOLVED_TYPE;
        } else {
            // Ambiguous identifier-not-found cases default to UNKNOWN_COLUMN
            // (documented in BuildFailure — a real Calcite limitation, not faked).
            cause = BuildFailure.Cause.UNKNOWN_COLUMN;
        }
        return BuildFailure.of(cause, sp, firstLine(detail));
    }

    private static String firstLine(String msg) {
        if (msg == null) {
            return "validation error";
        }
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, nl) : msg;
    }

    // ============================================================
    // DTOs
    // ============================================================

    /** One step in a {@code sql-model} chain (SPEC #6 §B.1). */
    public record SqlModelStep(String name, String sql, boolean materialize) {
        /** Convenience constructor; {@code materialize} defaults to {@code false} (§B.3). */
        public static SqlModelStep of(String name, String sql) {
            return new SqlModelStep(name, sql, false);
        }
    }

    /**
     * The single-statement / chain validation outcome: EITHER a derived PULSE
     * column list OR a structured {@link BuildFailure} (SPEC #6 §A.1). Never both.
     */
    public record SqlModelResult(List<Map<String, Object>> schema, BuildFailure failure) {
        public static SqlModelResult schema(List<Map<String, Object>> schema) {
            return new SqlModelResult(schema, null);
        }

        public static SqlModelResult failure(BuildFailure failure) {
            return new SqlModelResult(null, failure);
        }

        public boolean isFailure() {
            return failure != null;
        }

        public Optional<List<Map<String, Object>>> schemaOpt() {
            return Optional.ofNullable(schema);
        }
    }
}
