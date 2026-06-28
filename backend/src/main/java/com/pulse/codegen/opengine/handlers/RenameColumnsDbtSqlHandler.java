package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;
import java.util.Map;

/**
 * dbt-SQL emission for {@code rename-columns} (SPEC #2 §C.1).
 *
 * <p>Iterates the INPUT columns in schema order (deterministic, ADR 0009): for
 * each column emit {@code <col> AS <newname>} if it is in {@code rename_map},
 * else the bare {@code <col>}. With no map it degrades to
 * {@code SELECT * FROM <ref>}.
 */
public final class RenameColumnsDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.RENAME_COLUMNS;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        Map<String, String> renames = config.getStringMap("rename_map");
        List<String> cols = ctx.inputColumnNames();
        if (renames.isEmpty() || cols.isEmpty()) {
            return "SELECT *\nFROM " + ref;
        }
        StringBuilder sql = new StringBuilder("SELECT\n");
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            String newName = renames.get(col);
            String projection = newName != null ? col + " AS " + newName : col;
            sql.append("    ").append(projection);
            if (i < cols.size() - 1) {
                sql.append(",");
            }
            sql.append("\n");
        }
        sql.append("FROM ").append(ref);
        return sql.toString();
    }
}
