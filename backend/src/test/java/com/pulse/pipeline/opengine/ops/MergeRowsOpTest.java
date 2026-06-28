package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** merge-rows (SPEC #1 §B.1 rule 19) — schema passthrough (upsert/merge by key). */
class MergeRowsOpTest {

    private final MergeRowsOp op = new MergeRowsOp();

    private static Schema in() {
        return Schema.of(
                ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("status", "string"),
                ColumnModel.simple("amount", "decimal"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.MERGE_ROWS, op.opName());
    }

    @Test
    void schemaIsUnchanged() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("loan_id", "status", "amount"), out.names());
        assertEquals(in().names(), out.names());
    }

    @Test
    void emptyInputStaysEmpty() {
        Schema out = op.apply(Schema.empty(), null, ResolvedConfig.empty());
        assertTrue(out.isEmpty());
    }
}
