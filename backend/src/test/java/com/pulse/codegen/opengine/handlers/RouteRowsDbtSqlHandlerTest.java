package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouteRowsDbtSqlHandlerTest {

    private final RouteRowsDbtSqlHandler handler = new RouteRowsDbtSqlHandler();

    @Test
    void emitsBranchFilterFromBranchCondition_fix1() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "branch_condition", "status = 'APPROVED'")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);
        assertEquals("SELECT * FROM ref('up')\nWHERE status = 'APPROVED'", sql);
    }

    @Test
    void usesFirstBranchFromBranchesList() {
        EmitContext ctx = EmitContext.builder()
                .config(new ResolvedConfig(Map.of(
                        "branches", List.of(
                                Map.of("name", "approved", "condition", "status = 'APPROVED'"),
                                Map.of("name", "rejected", "condition", "status = 'REJECTED'")))))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);
        assertTrue(sql.contains("one model per branch"), sql);
        assertTrue(sql.contains("WHERE status = 'APPROVED'"), sql);
        assertFalse(sql.contains("REJECTED"), sql);
    }

    @Test
    void noConditionDegradesToPassthrough() {
        EmitContext ctx = EmitContext.builder()
                .config(ResolvedConfig.empty())
                .upstreamRef("ref('up')")
                .build();
        assertEquals("SELECT * FROM ref('up')", handler.emit(ctx));
    }
}
