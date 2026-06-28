package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SortDbtSqlHandlerTest {

    private final SortDbtSqlHandler handler = new SortDbtSqlHandler();

    @Test
    void emitsOrderByFromSortColumns() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "sort_columns", List.of("created_at DESC", "id"))))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);
        assertEquals("SELECT * FROM ref('up')\nORDER BY created_at DESC, id", sql);
    }

    @Test
    void emitsOrderByFromOrderByString() {
        EmitContext ctx = EmitContext.builder()
                .config(new ResolvedConfig(Map.of("order_by", "amount ASC")))
                .upstreamRef("ref('up')")
                .build();
        assertEquals("SELECT * FROM ref('up')\nORDER BY amount ASC", handler.emit(ctx));
    }

    @Test
    void noOrderDegradesToPassthrough() {
        EmitContext ctx = EmitContext.builder()
                .config(ResolvedConfig.empty())
                .upstreamRef("ref('up')")
                .build();
        assertEquals("SELECT * FROM ref('up')", handler.emit(ctx));
    }
}
