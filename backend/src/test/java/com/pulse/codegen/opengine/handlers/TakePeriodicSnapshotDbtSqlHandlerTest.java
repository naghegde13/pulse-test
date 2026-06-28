package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TakePeriodicSnapshotDbtSqlHandlerTest {

    private final TakePeriodicSnapshotDbtSqlHandler handler = new TakePeriodicSnapshotDbtSqlHandler();

    @Test
    void emitsSnapshotColumnSet_fix3() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "business_key", List.of("loan_id"))))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);
        // incremental config
        assertTrue(sql.contains("materialized='incremental'"), sql);
        // FIX #3: ds + _pulse_processing_ts + _pulse_run_id + _pulse_snapshot_model
        assertTrue(sql.contains("AS ds"), sql);
        assertTrue(sql.contains("_pulse_processing_ts"), sql);
        assertTrue(sql.contains("_pulse_run_id"), sql);
        assertTrue(sql.contains("_pulse_snapshot_model"), sql);
        assertTrue(sql.contains("FROM ref('up')"), sql);
        // unique_key includes the business key + partition col
        assertTrue(sql.contains("unique_key=['loan_id', 'ds']"), sql);
    }
}
