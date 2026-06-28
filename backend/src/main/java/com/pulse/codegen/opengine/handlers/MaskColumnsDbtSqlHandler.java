package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dbt-SQL emission for {@code mask-columns} (SPEC #2 §C.1).
 *
 * <p>Replaces PII columns with a deterministic masking expression while passing
 * through the rest. Two config shapes are merged into one column&rarr;strategy map
 * (insertion-order preserved, deterministic per ADR 0009):
 * <ul>
 *   <li>{@code pii_columns}: [names] — defaults to the {@code hash} strategy.</li>
 *   <li>{@code mask_specs}: [{column, strategy}] — explicit per-column strategy
 *       (overrides any {@code pii_columns} default for the same column).</li>
 * </ul>
 *
 * <p>Strategies: {@code hash} &rarr; {@code sha2(CAST(<col> AS STRING), 256) AS <col>};
 * {@code redact} / {@code redact_left} / anything else &rarr; the deterministic
 * literal redaction {@code '***' AS <col>}. The projection iterates the INPUT
 * columns in schema order; an unmasked column passes through as the bare {@code <col>}.
 */
public final class MaskColumnsDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.MASK_COLUMNS;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        List<String> cols = ctx.inputColumnNames();

        // Build a deterministic col -> strategy map: pii_columns first (default
        // "hash"), then mask_specs overrides, both in insertion order.
        Map<String, String> strategies = new LinkedHashMap<>();
        for (String pii : config.getStringList("pii_columns")) {
            strategies.put(pii, "hash");
        }
        for (Map<String, Object> spec : config.getMapList("mask_specs")) {
            Object column = spec.get("column");
            if (column == null) {
                continue;
            }
            Object strategy = spec.get("strategy");
            strategies.put(column.toString(), strategy == null ? "hash" : strategy.toString());
        }

        if (strategies.isEmpty() || cols.isEmpty()) {
            return "SELECT *\nFROM " + ref;
        }

        StringBuilder sql = new StringBuilder("SELECT\n");
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            String strategy = strategies.get(col);
            String projection = strategy != null ? maskExpr(col, strategy) : col;
            sql.append("    ").append(projection);
            if (i < cols.size() - 1) {
                sql.append(",");
            }
            sql.append("\n");
        }
        sql.append("FROM ").append(ref);
        return sql.toString();
    }

    private String maskExpr(String col, String strategy) {
        if ("hash".equalsIgnoreCase(strategy)) {
            return "sha2(CAST(" + col + " AS STRING), 256) AS " + col;
        }
        // redact / redact_left / any other strategy -> deterministic literal redaction.
        return "'***' AS " + col;
    }
}
