package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** schedule-and-triggers (SPEC #1 §B.1 rule 29) — portless control op, no schema effect. */
class ScheduleAndTriggersOpTest {

    private final ScheduleAndTriggersOp op = new ScheduleAndTriggersOp();

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.SCHEDULE_AND_TRIGGERS, op.opName());
    }

    @Test
    void returnsEmptySchema() {
        Schema out = op.apply(Schema.of(ColumnModel.simple("a", "string")), null,
                ResolvedConfig.empty());
        assertEquals(0, out.size());
    }
}
