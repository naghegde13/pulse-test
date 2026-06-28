package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SampleLimitDbtSqlHandlerTest {

    private final SampleLimitDbtSqlHandler handler = new SampleLimitDbtSqlHandler();

    @Test
    void emitsLimitFromConfig() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of("limit", "500")))
                .upstreamRef("ref('up')")
                .build();
        assertEquals("SELECT * FROM ref('up')\nLIMIT 500", handler.emit(ctx));
    }

    @Test
    void emitsDefaultLimitWhenNeitherConfigured() {
        EmitContext ctx = EmitContext.builder()
                .config(ResolvedConfig.empty())
                .upstreamRef("ref('up')")
                .build();
        assertEquals("SELECT * FROM ref('up')\nLIMIT 1000", handler.emit(ctx));
    }

    @Test
    void emitsTablesampleForFraction() {
        EmitContext ctx = EmitContext.builder()
                .config(new ResolvedConfig(Map.of("sample_fraction", "0.1")))
                .upstreamRef("ref('up')")
                .build();
        String sql = handler.emit(ctx);
        assertEquals("SELECT * FROM ref('up') TABLESAMPLE (10 PERCENT)", sql);
    }
}
