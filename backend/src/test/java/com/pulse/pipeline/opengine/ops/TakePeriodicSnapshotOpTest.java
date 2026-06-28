package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** {@code take-periodic-snapshot} (rule 21) — FIX #3: pulse snapshot columns (NOT dbt_valid_*). */
class TakePeriodicSnapshotOpTest {

    private final TakePeriodicSnapshotOp op = new TakePeriodicSnapshotOp();

    @Test
    void opName() {
        assertEquals(OpVocabulary.TAKE_PERIODIC_SNAPSHOT, op.opName());
    }

    @Test
    void appendsExactlyThePulseSnapshotColumnsInOrderWithTypes() {
        Schema in = Schema.of(
                ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("balance", "decimal"));

        Schema out = op.apply(in, null, ResolvedConfig.empty());

        assertEquals(List.of(
                "loan_id", "balance",
                "ds", "_pulse_processing_ts", "_pulse_run_id", "_pulse_snapshot_model"),
                out.names());

        // FIX #3: correct types (legacy was transposed to dbt_valid_*).
        assertEquals("date", out.find("ds").type());
        assertEquals("timestamp", out.find("_pulse_processing_ts").type());
        assertEquals("string", out.find("_pulse_run_id").type());
        assertEquals("string", out.find("_pulse_snapshot_model").type());
    }

    @Test
    void doesNotEmitLegacyDbtValidColumns() {
        Schema in = Schema.of(ColumnModel.simple("loan_id", "string"));

        Schema out = op.apply(in, null, ResolvedConfig.empty());

        // The legacy (wrong) SCD-2 columns must NOT appear.
        assertFalse(out.hasColumn("dbt_valid_from"));
        assertFalse(out.hasColumn("dbt_valid_to"));
        assertFalse(out.hasColumn("dbt_scd_id"));
        assertFalse(out.hasColumn("dbt_updated_at"));
    }

    @Test
    void inputColumnsArePreservedFirst() {
        Schema in = Schema.of(
                ColumnModel.simple("a", "integer"),
                ColumnModel.simple("b", "string"),
                ColumnModel.simple("c", "double"));

        Schema out = op.apply(in, null, ResolvedConfig.empty());

        assertEquals(List.of("a", "b", "c"), out.names().subList(0, 3));
        assertEquals(7, out.size());
    }
}
