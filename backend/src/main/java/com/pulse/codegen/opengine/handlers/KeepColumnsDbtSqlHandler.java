package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;

/**
 * dbt-SQL emission for {@code keep-columns} (SPEC #2 §C.1).
 *
 * <p>Emits {@code SELECT <keep_columns> FROM <ref>}, projecting exactly the
 * columns named in {@code keep_columns}, in the CONFIG order (the keep-list is the
 * authoritative output order, so we honour it verbatim — deterministic, ADR 0009).
 * With no {@code keep_columns} it degrades to {@code SELECT * FROM <ref>}.
 */
public final class KeepColumnsDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.KEEP_COLUMNS;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        List<String> keep = config.getStringList("keep_columns");
        if (keep.isEmpty()) {
            keep = config.getStringList("measures");
        }
        if (keep.isEmpty()) {
            keep = config.getStringList("columns");
        }
        if (keep.isEmpty()) {
            return "SELECT *\nFROM " + ref;
        }
        StringBuilder sql = new StringBuilder("SELECT\n");
        for (int i = 0; i < keep.size(); i++) {
            sql.append("    ").append(keep.get(i));
            if (i < keep.size() - 1) {
                sql.append(",");
            }
            sql.append("\n");
        }
        sql.append("FROM ").append(ref);
        return sql.toString();
    }
}
