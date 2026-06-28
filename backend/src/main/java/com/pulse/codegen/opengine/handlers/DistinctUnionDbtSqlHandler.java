package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;

/**
 * dbt-SQL emission for {@code distinct-union} (SPEC #2 §C.1).
 *
 * <p>Emits {@code SELECT * FROM <upstreamRef> UNION SELECT * FROM <secondaryRef>}.
 * Plain {@code UNION} (no {@code ALL}) deduplicates the combined rows.
 */
public final class DistinctUnionDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.DISTINCT_UNION;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        return "SELECT * FROM " + ctx.upstreamRef()
                + "\nUNION\n"
                + "SELECT * FROM " + ctx.secondaryRef();
    }
}
