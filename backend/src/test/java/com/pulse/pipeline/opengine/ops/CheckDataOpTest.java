package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** check-data (SPEC #1 §B.1 rule 22) — main-path passthrough. */
class CheckDataOpTest {

    private final CheckDataOp op = new CheckDataOp();

    private static Schema in() {
        return Schema.of(ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("amount", "double"),
                ColumnModel.simple("status", "string"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.CHECK_DATA, op.opName());
    }

    @Test
    void mainPathPassesThrough() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("loan_id", "amount", "status"), out.names());
    }

    @Test
    void configuredChecksStillPassThrough() {
        ResolvedConfig cfg = new ResolvedConfig(java.util.Map.of(
                "expectations", List.of("not_null:loan_id")));
        Schema out = op.apply(in(), null, cfg);
        assertEquals(in().names(), out.names());
    }
}
