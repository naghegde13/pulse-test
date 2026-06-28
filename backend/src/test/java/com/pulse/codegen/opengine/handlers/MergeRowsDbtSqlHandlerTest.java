package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MergeRowsDbtSqlHandlerTest {

    private final MergeRowsDbtSqlHandler handler = new MergeRowsDbtSqlHandler();

    @Test
    void emitsIncrementalMergeConfigWithUniqueKey() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "merge_keys", List.of("loan_id", "version"))))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);
        assertTrue(sql.contains("materialized='incremental'"), sql);
        assertTrue(sql.contains("incremental_strategy='merge'"), sql);
        assertTrue(sql.contains("unique_key=['loan_id', 'version']"), sql);
        assertTrue(sql.contains("SELECT * FROM ref('up')"), sql);
    }

    @Test
    void emitsIncrementalGuardWhenIncrementalFieldPresent() {
        EmitContext ctx = EmitContext.builder()
                .config(new ResolvedConfig(Map.of(
                        "merge_keys", List.of("id"),
                        "incremental_field", "updated_at")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);
        assertTrue(sql.contains("{% if is_incremental() %}"), sql);
        assertTrue(sql.contains("WHERE updated_at > (SELECT MAX(updated_at) FROM {{ this }})"), sql);
        assertTrue(sql.contains("{% endif %}"), sql);
    }
}
