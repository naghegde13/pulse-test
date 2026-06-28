package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;
import java.util.Map;

/**
 * dbt-SQL emission for {@code group-and-aggregate} (SPEC #2 §C.1).
 *
 * <p>Emits {@code SELECT <group_by>, <agg fns> FROM <ref> GROUP BY <group_by>}.
 * Group-by columns come from {@code group_by_columns}; aggregations from
 * {@code aggregations} (each {@code {alias, function, column}}), in declared order
 * for byte-stability (ADR 0009).
 *
 * <p>Aggregation rules: {@code COUNT(*)} when the column is absent or {@code "*"};
 * {@code COUNT_DISTINCT} maps to {@code COUNT(DISTINCT <column>)}; otherwise
 * {@code <FUNCTION>(<column>)}. Each carries {@code AS <alias>}.
 */
public final class GroupAndAggregateDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.GROUP_AND_AGGREGATE;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        List<String> groupBy = config.getStringList("group_by_columns");
        if (groupBy.isEmpty()) {
            groupBy = config.getStringList("entity_key");
        }
        List<Map<String, Object>> aggs = config.getMapList("aggregations");
        if (aggs.isEmpty()) {
            aggs = config.getMapList("features");
        }
        if (aggs.isEmpty()) {
            aggs = config.getMapList("pre_aggregations");
        }

        if (groupBy.isEmpty() && aggs.isEmpty()) {
            return "SELECT *\nFROM " + ref;
        }

        StringBuilder sql = new StringBuilder("SELECT\n");
        boolean first = true;
        for (String g : groupBy) {
            if (!first) {
                sql.append(",\n");
            }
            sql.append("    ").append(g);
            first = false;
        }
        for (Map<String, Object> agg : aggs) {
            if (!first) {
                sql.append(",\n");
            }
            sql.append("    ").append(aggExpr(agg));
            first = false;
        }
        sql.append("\nFROM ").append(ref);
        if (!groupBy.isEmpty()) {
            sql.append("\nGROUP BY ").append(String.join(", ", groupBy));
        }
        return sql.toString();
    }

    private String aggExpr(Map<String, Object> agg) {
        Object fnObj = agg.get("function");
        String function = fnObj == null ? "COUNT" : fnObj.toString().toUpperCase();
        Object colObj = agg.get("column");
        String column = colObj == null ? null : colObj.toString();
        Object aliasObj = agg.get("alias");

        String body;
        if (column == null || column.isBlank() || "*".equals(column)) {
            body = "COUNT(*)";
        } else if ("COUNT_DISTINCT".equals(function)) {
            body = "COUNT(DISTINCT " + column + ")";
        } else {
            body = function + "(" + column + ")";
        }

        String alias = aliasObj == null ? defaultAlias(function, column) : aliasObj.toString();
        return body + " AS " + alias;
    }

    private String defaultAlias(String function, String column) {
        String base = (column == null || column.isBlank() || "*".equals(column)) ? "all" : column;
        return function.toLowerCase() + "_" + base;
    }
}
