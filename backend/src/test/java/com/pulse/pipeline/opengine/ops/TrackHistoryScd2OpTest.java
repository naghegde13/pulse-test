package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** {@code track-history-scd2} (rule 20) — FIX #2: dbt-snapshot system columns (NOT is_current). */
class TrackHistoryScd2OpTest {

    private final TrackHistoryScd2Op op = new TrackHistoryScd2Op();

    @Test
    void opName() {
        assertEquals(OpVocabulary.TRACK_HISTORY_SCD2, op.opName());
    }

    @Test
    void appendsExactlyTheFourDbtColumnsInOrderWithTypes() {
        Schema in = Schema.of(
                ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("status", "string"));

        Schema out = op.apply(in, null, ResolvedConfig.empty());

        assertEquals(List.of(
                "loan_id", "status",
                "dbt_valid_from", "dbt_valid_to", "dbt_scd_id", "dbt_updated_at"),
                out.names());

        // FIX #2: correct types (legacy was transposed).
        assertEquals("timestamp", out.find("dbt_valid_from").type());
        assertEquals("timestamp", out.find("dbt_valid_to").type());
        assertEquals("string", out.find("dbt_scd_id").type());
        assertEquals("timestamp", out.find("dbt_updated_at").type());
    }

    @Test
    void doesNotEmitLegacyIsCurrentOrPlainValidColumns() {
        Schema in = Schema.of(ColumnModel.simple("loan_id", "string"));

        Schema out = op.apply(in, null, ResolvedConfig.empty());

        // The legacy (wrong) columns must NOT appear.
        assertFalse(out.hasColumn("is_current"));
        assertFalse(out.hasColumn("valid_from"));
        assertFalse(out.hasColumn("valid_to"));
    }

    @Test
    void inputColumnsArePreservedFirst() {
        Schema in = Schema.of(
                ColumnModel.simple("a", "integer"),
                ColumnModel.simple("b", "decimal"));

        Schema out = op.apply(in, null, ResolvedConfig.empty());

        assertEquals("a", out.names().get(0));
        assertEquals("b", out.names().get(1));
        assertEquals(6, out.size());
    }
}
