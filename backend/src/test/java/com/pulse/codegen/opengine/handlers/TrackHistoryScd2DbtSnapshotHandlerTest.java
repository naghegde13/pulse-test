package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrackHistoryScd2DbtSnapshotHandlerTest {

    private final TrackHistoryScd2DbtSnapshotHandler handler = new TrackHistoryScd2DbtSnapshotHandler();

    @Test
    void usesDbtSnapshotEngine() {
        assertEquals(EmissionEngine.DBT_SNAPSHOT, handler.engine());
    }

    @Test
    void emitsSnapshotBlockNoCustomEffectiveColumns_fix10() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "snapshot_name", "snp_loans",
                        "business_key", List.of("loan_id"),
                        "tracked_columns", List.of("status", "balance"))))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);
        // {% snapshot %} block
        assertTrue(sql.contains("{% snapshot snp_loans %}"), sql);
        assertTrue(sql.contains("{% endsnapshot %}"), sql);
        // check strategy with tracked columns
        assertTrue(sql.contains("strategy='check'"), sql);
        assertTrue(sql.contains("check_cols=['status', 'balance']"), sql);
        assertTrue(sql.contains("unique_key='loan_id'"), sql);
        assertTrue(sql.contains("SELECT * FROM ref('up')"), sql);
        // FIX #10: NO custom effective_from / effective_to columns
        assertFalse(sql.contains("effective_from"), sql);
        assertFalse(sql.contains("effective_to"), sql);
    }

    @Test
    void timestampStrategyWhenNoTrackedColumns() {
        EmitContext ctx = EmitContext.builder()
                .config(new ResolvedConfig(Map.of(
                        "business_key", List.of("id"),
                        "updated_at", "modified_ts")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);
        assertTrue(sql.contains("strategy='timestamp'"), sql);
        assertTrue(sql.contains("updated_at='modified_ts'"), sql);
    }
}
