package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code schedule-and-triggers} (SPEC #1 §B.1 rule 29) — control op, PORTLESS,
 * NO schema effect.
 *
 * <p>Scheduling / trigger wiring carries no data ports and therefore no columns.
 * {@code apply} returns an empty schema.
 */
public final class ScheduleAndTriggersOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.SCHEDULE_AND_TRIGGERS;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        // Portless control op: no data ports, no columns.
        return Schema.empty();
    }
}
