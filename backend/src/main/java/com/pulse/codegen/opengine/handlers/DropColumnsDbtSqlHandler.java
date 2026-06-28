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
 * dbt-SQL emission for {@code drop-columns} (SPEC #2 §C.1).
 *
 * <p>Emits a {@code SELECT} projection of the INPUT columns (in schema order)
 * MINUS {@code drop_columns}. Projecting the kept columns explicitly (rather than
 * relying on a non-existent SQL EXCEPT) keeps the output byte-stable (ADR 0009).
 * With nothing to drop it degrades to {@code SELECT * FROM <ref>}.
 */
public final class DropColumnsDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.DROP_COLUMNS;
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
        Set<String> drop = new LinkedHashSet<>(config.getStringList("drop_columns"));
        if (drop.isEmpty() || cols.isEmpty()) {
            return "SELECT *\nFROM " + ref;
        }
        StringBuilder sql = new StringBuilder("SELECT\n");
        boolean first = true;
        for (String col : cols) {
            if (drop.contains(col)) {
                continue;
            }
            if (!first) {
                sql.append(",\n");
            }
            sql.append("    ").append(col);
            first = false;
        }
        sql.append("\nFROM ").append(ref);
        return sql.toString();
    }
}
