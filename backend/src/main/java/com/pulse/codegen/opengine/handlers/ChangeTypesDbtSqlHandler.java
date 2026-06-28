package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;
import java.util.Map;

/**
 * dbt-SQL emission for {@code change-types} (SPEC #2 §C.1).
 *
 * <p>Iterates the INPUT columns in schema order (deterministic, ADR 0009): for
 * each column emit {@code CAST(<col> AS <newtype>) AS <col>} if it appears in
 * {@code type_coercions}, else the bare {@code <col>}. With no coercions it
 * degrades to {@code SELECT * FROM <ref>}.
 */
public final class ChangeTypesDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.CHANGE_TYPES;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        Map<String, String> coercions = config.getStringMap("type_coercions");
        List<String> cols = ctx.inputColumnNames();
        if (coercions.isEmpty() || cols.isEmpty()) {
            return "SELECT *\nFROM " + ref;
        }
        StringBuilder sql = new StringBuilder("SELECT\n");
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            String newType = coercions.get(col);
            String projection = newType != null
                    ? "CAST(" + col + " AS " + newType + ") AS " + col
                    : col;
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
