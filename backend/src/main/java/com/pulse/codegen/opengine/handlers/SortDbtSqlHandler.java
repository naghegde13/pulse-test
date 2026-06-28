package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;

/**
 * dbt-SQL emission for {@code sort} (SPEC #2 §C.1).
 *
 * <p>Emits {@code SELECT * FROM <ref> ORDER BY <order_by>}. The order columns come
 * from {@code sort_columns} (a list, joined comma-separated, preserving any
 * embedded {@code ASC/DESC}) or, if absent, the verbatim {@code order_by} string.
 * With neither configured the op degrades to {@code SELECT * FROM <ref>}
 * (SPEC #1 §A.2 do-nothing default).
 */
public final class SortDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.SORT;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        List<String> sortColumns = config.getStringList("sort_columns");
        String orderBy;
        if (!sortColumns.isEmpty()) {
            orderBy = String.join(", ", sortColumns);
        } else {
            String raw = config.getString("order_by");
            orderBy = (raw == null || raw.isBlank()) ? null : raw.trim();
        }
        if (orderBy == null) {
            return "SELECT * FROM " + ref;
        }
        return "SELECT * FROM " + ref + "\nORDER BY " + orderBy;
    }
}
