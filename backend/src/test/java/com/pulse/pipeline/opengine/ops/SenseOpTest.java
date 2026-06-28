package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** sense (SPEC #1 §B.1 rule 28) — portless control op, no schema effect. */
class SenseOpTest {

    private final SenseOp op = new SenseOp();

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.SENSE, op.opName());
    }

    @Test
    void returnsEmptySchema() {
        Schema out = op.apply(Schema.of(ColumnModel.simple("a", "string")), null,
                ResolvedConfig.empty());
        assertEquals(0, out.size());
    }
}
