package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;

/**
 * dbt-SQL emission for {@code filter-rows} (SPEC #2 §C.1).
 *
 * <p>Emits {@code SELECT * FROM <ref> WHERE <predicate>}. The predicate is sourced
 * in priority order:
 * <ol>
 *   <li>{@code raw_sql} — a complete boolean expression used verbatim.</li>
 *   <li>{@code drop_when_null} — [names] composed as
 *       {@code <c1> IS NOT NULL AND <c2> IS NOT NULL ...} (columns in config order,
 *       deterministic per ADR 0009).</li>
 *   <li>{@code condition} — a single boolean expression.</li>
 * </ol>
 * With no predicate config the op degrades to a {@code SELECT * FROM <ref>}
 * passthrough (SPEC #1 §A.2).
 */
public final class FilterRowsDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.FILTER_ROWS;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        String predicate = predicateFor(config);
        if (predicate == null || predicate.isBlank()) {
            return "SELECT *\nFROM " + ref;
        }
        return "SELECT *\nFROM " + ref + "\nWHERE " + predicate;
    }

    private String predicateFor(ResolvedConfig config) {
        String rawSql = config.getString("raw_sql");
        if (rawSql != null && !rawSql.isBlank()) {
            return rawSql;
        }
        List<String> dropWhenNull = config.getStringList("drop_when_null");
        if (!dropWhenNull.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dropWhenNull.size(); i++) {
                if (i > 0) {
                    sb.append(" AND ");
                }
                sb.append(dropWhenNull.get(i)).append(" IS NOT NULL");
            }
            return sb.toString();
        }
        return config.getString("condition");
    }
}
