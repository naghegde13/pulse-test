package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code advance-time} (SPEC #1 §B.1 rule 31) — control op, PORTLESS, NO schema
 * effect.
 *
 * <p>Advancing the pipeline's logical time carries no data ports and therefore
 * no columns. {@code apply} returns an empty schema.
 */
public final class AdvanceTimeOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.ADVANCE_TIME;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        // Portless control op: no data ports, no columns.
        return Schema.empty();
    }
}
