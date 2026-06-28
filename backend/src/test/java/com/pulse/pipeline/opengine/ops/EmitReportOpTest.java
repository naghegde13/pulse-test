package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** emit-report (SPEC #1 §B.1 rule 23) — main-path passthrough (report is side-output). */
class EmitReportOpTest {

    private final EmitReportOp op = new EmitReportOp();

    private static Schema in() {
        return Schema.of(ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("amount", "double"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.EMIT_REPORT, op.opName());
    }

    @Test
    void mainPathPassesThrough() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("loan_id", "amount"), out.names());
    }

    @Test
    void reportConfigDoesNotAlterMainSchema() {
        ResolvedConfig cfg = new ResolvedConfig(java.util.Map.of(
                "report_table", "dq_summary"));
        Schema out = op.apply(in(), null, cfg);
        assertEquals(in().names(), out.names());
    }
}
