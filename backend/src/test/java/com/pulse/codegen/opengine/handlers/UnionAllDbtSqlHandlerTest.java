package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnionAllDbtSqlHandlerTest {

    private final UnionAllDbtSqlHandler handler = new UnionAllDbtSqlHandler();

    @Test
    void emitsUnionAll() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of()))
                .upstreamRef("ref('up')")
                .secondaryRef("ref('right')")
                .build();

        String sql = handler.emit(ctx);
        assertEquals(
                "SELECT * FROM ref('up')\nUNION ALL\nSELECT * FROM ref('right')",
                sql);
        assertTrue(sql.contains("UNION ALL"), sql);
    }
}
