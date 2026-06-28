package com.pulse.pipeline.opengine.ops;

import com.pulse.codegen.audit.IngestionAuditColumns;
import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * add-audit-columns (SPEC #1 §B.1 rule 25) — OUT = IN + the 8 PULSE audit
 * columns, in {@link IngestionAuditColumns} order, after the IN columns.
 */
class AddAuditColumnsOpTest {

    private final AddAuditColumnsOp op = new AddAuditColumnsOp();

    /** The 8 audit names in canonical (source-of-truth) order. */
    private static final List<String> AUDIT_NAMES = List.of(
            "_pulse_ingested_at", "_pulse_processing_ts", "_pulse_pipeline", "_pulse_task",
            "_pulse_run_id", "_pulse_source_uri", "_pulse_business_date", "_pulse_dag_id");

    private static Schema in() {
        return Schema.of(
                ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("amount", "decimal"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.ADD_AUDIT_COLUMNS, op.opName());
    }

    @Test
    void outputSizeIsInputSizePlusEight() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(in().size() + 8, out.size());
    }

    @Test
    void allEightAuditNamesPresentInOrderAfterInputColumns() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());

        List<String> expected = new ArrayList<>();
        expected.add("loan_id");
        expected.add("amount");
        expected.addAll(AUDIT_NAMES);

        assertEquals(expected, out.names());
    }

    @Test
    void dagIdAuditColumnIsPresentAndLast() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertTrue(out.hasColumn("_pulse_dag_id"), "_pulse_dag_id must be present");
        assertEquals("_pulse_dag_id", out.names().get(out.size() - 1));
    }

    @Test
    void auditNamesMatchTheSourceOfTruth() {
        // Guard against drift: the test's expected list must equal the single source of truth.
        assertEquals(IngestionAuditColumns.NAMES, AUDIT_NAMES);
    }

    @Test
    void auditColumnTypesAreCarriedFromDescriptors() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals("timestamp", out.find("_pulse_ingested_at").type());
        assertEquals("timestamp", out.find("_pulse_processing_ts").type());
        assertEquals("string", out.find("_pulse_pipeline").type());
        assertEquals("date", out.find("_pulse_business_date").type());
    }

    @Test
    void emptyInputYieldsExactlyTheEightAuditColumns() {
        Schema out = op.apply(Schema.empty(), null, ResolvedConfig.empty());
        assertEquals(8, out.size());
        assertEquals(AUDIT_NAMES, out.names());
    }
}
