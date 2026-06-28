package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** rollback (SPEC #1 §B.1 rule 30) — portless control op, no schema effect. */
class RollbackOpTest {

    private final RollbackOp op = new RollbackOp();

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.ROLLBACK, op.opName());
    }

    @Test
    void returnsEmptySchema() {
        Schema out = op.apply(Schema.of(ColumnModel.simple("a", "string")), null,
                ResolvedConfig.empty());
        assertEquals(0, out.size());
    }
}
