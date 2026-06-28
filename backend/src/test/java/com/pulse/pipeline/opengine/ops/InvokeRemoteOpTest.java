package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** invoke-remote (SPEC #1 §B.1 rule 32) — portless control op, no schema effect. */
class InvokeRemoteOpTest {

    private final InvokeRemoteOp op = new InvokeRemoteOp();

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.INVOKE_REMOTE, op.opName());
    }

    @Test
    void returnsEmptySchema() {
        Schema out = op.apply(Schema.of(ColumnModel.simple("a", "string")), null,
                ResolvedConfig.empty());
        assertEquals(0, out.size());
    }
}
