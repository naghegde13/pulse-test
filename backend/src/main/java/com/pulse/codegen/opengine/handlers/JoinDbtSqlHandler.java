package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * dbt-SQL emission for {@code join} (SPEC #2 §C.1).
 *
 * <p>Emits {@code SELECT <cols> FROM <upstreamRef> l <TYPE> JOIN <secondaryRef> r
 * ON <on>}. The SELECT list is built explicitly from the input schemas (rather
 * than {@code l.*, r.*}) so the output is byte-stable (ADR 0009).
 *
 * <p><b>FIX #5 (collision keep-both):</b> every left column is projected as
 * {@code l.<c>}; every right column as {@code r.<c>}, but when a right column name
 * collides with a left name it is aliased {@code r.<c> AS right_<c>} so BOTH sides
 * survive the join (no silent column loss). Column order is left-schema order then
 * right-schema order.
 *
 * <p>The ON clause comes from {@code join_condition} (verbatim) or from
 * {@code join_keys} ({@code l.<k> = r.<k> AND ...}); join type from
 * {@code join_type} (default {@code inner}).
 */
public final class JoinDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.JOIN;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String leftRef = ctx.upstreamRef();
        String rightRef = ctx.secondaryRef();

        String joinType = config.getString("join_type");
        if (joinType == null || joinType.isBlank()) {
            joinType = "inner";
        }
        String joinKeyword = joinType.trim().toUpperCase().replace('_', ' ');

        List<String> leftCols = ctx.inputColumnNames();
        List<String> rightCols = ctx.secondarySchema() == null
                ? List.of() : ctx.secondarySchema().names();
        Set<String> leftNames = new LinkedHashSet<>(leftCols);

        StringBuilder select = new StringBuilder();
        boolean first = true;
        for (String c : leftCols) {
            if (!first) {
                select.append(",\n");
            }
            select.append("    l.").append(c);
            first = false;
        }
        for (String c : rightCols) {
            if (!first) {
                select.append(",\n");
            }
            // FIX #5: keep BOTH on a same-name collision; alias the right side.
            if (leftNames.contains(c)) {
                select.append("    r.").append(c).append(" AS right_").append(c);
            } else {
                select.append("    r.").append(c);
            }
            first = false;
        }
        if (first) {
            // No schema columns available — fall back to keep-both wildcard.
            select.append("    l.*,\n    r.*");
        }

        StringBuilder sql = new StringBuilder("SELECT\n");
        sql.append(select);
        sql.append("\nFROM ").append(leftRef).append(" l\n");
        sql.append(joinKeyword).append(" JOIN ").append(rightRef).append(" r\n");
        sql.append("    ON ").append(buildOnClause(config));
        return sql.toString();
    }

    private String buildOnClause(ResolvedConfig config) {
        String condition = config.getString("join_condition");
        if (condition != null && !condition.isBlank()) {
            return condition.trim();
        }
        List<String> keys = config.getStringList("join_keys");
        if (keys.isEmpty()) {
            keys = config.getStringList("dimension_keys");
        }
        if (keys.isEmpty()) {
            keys = config.getStringList("entity_key");
        }
        if (!keys.isEmpty()) {
            StringBuilder on = new StringBuilder();
            boolean first = true;
            for (String k : keys) {
                if (!first) {
                    on.append(" AND ");
                }
                on.append("l.").append(k).append(" = r.").append(k);
                first = false;
            }
            return on.toString();
        }
        return "l.id = r.id";
    }
}
