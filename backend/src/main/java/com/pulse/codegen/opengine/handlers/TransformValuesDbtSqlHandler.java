package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;
import java.util.Map;

/**
 * dbt-SQL emission for {@code transform-values} (SPEC #2 §C.1).
 *
 * <p>Wraps named columns in a per-column SQL expression (e.g. {@code trim},
 * {@code coalesce}) while passing through the rest unchanged. The projection is
 * built by iterating the INPUT columns in schema order (deterministic, ADR 0009):
 * for each input column emit {@code <expr> AS <col>} if it is mapped in
 * {@code value_expressions}, else the bare {@code <col>}. With no config it
 * degrades to {@code SELECT * FROM <ref>} (SPEC #1 §A.2 passthrough).
 */
public final class TransformValuesDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.TRANSFORM_VALUES;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        Map<String, String> exprs = config.getStringMap("value_expressions");
        List<String> cols = ctx.inputColumnNames();
        if (exprs.isEmpty() || cols.isEmpty()) {
            return "SELECT *\nFROM " + ref;
        }
        StringBuilder sql = new StringBuilder("SELECT\n");
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            String mapped = exprs.get(col);
            String projection = mapped != null ? mapped + " AS " + col : col;
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
