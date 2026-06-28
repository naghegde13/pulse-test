package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;

/**
 * dbt-SQL emission for {@code union-all} (SPEC #2 §C.1).
 *
 * <p>Emits {@code SELECT * FROM <upstreamRef> UNION ALL SELECT * FROM
 * <secondaryRef>} — a row-preserving (non-deduping) vertical concatenation of the
 * two compatible inputs.
 */
public final class UnionAllDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.UNION_ALL;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        return "SELECT * FROM " + ctx.upstreamRef()
                + "\nUNION ALL\n"
                + "SELECT * FROM " + ctx.secondaryRef();
    }
}
