package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

/**
 * dbt-SQL emission for {@code add-column} (SPEC #2 §C.1).
 *
 * <p>Emits {@code SELECT *, <expr> AS <name> FROM <ref>}. The expression and new
 * column name come from the resolved config ({@code expression} / {@code name}).
 * With no {@code name}, the op degrades to a {@code SELECT * FROM <ref>}
 * passthrough (SPEC #1 §A.2 do-nothing default).
 */
public final class AddColumnDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.ADD_COLUMN;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        String name = config.getString("name");
        String expr = config.getString("expression");
        if (name == null || name.isBlank() || expr == null || expr.isBlank()) {
            return "SELECT *\nFROM " + ref;
        }
        return "SELECT *, " + expr + " AS " + name + "\nFROM " + ref;
    }
}
