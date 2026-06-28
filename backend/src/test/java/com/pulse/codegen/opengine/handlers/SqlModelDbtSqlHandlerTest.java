package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlModelDbtSqlHandlerTest {

    private final SqlModelDbtSqlHandler handler = new SqlModelDbtSqlHandler();

    @Test
    void emitsUserSqlVerbatim() {
        String userSql = "SELECT a, b, a + b AS c\nFROM {{ ref('upstream') }}\nWHERE a > 0";
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of("sql", userSql)))
                .upstreamRef("ref('up')")
                .build();

        assertEquals(userSql, handler.emit(ctx));
    }

    @Test
    void emitsSeededStepChain() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of("steps", java.util.List.of(
                        Map.of("name", "Clean Loans", "sql", "SELECT * FROM {{ ref('raw_loans') }} WHERE status IS NOT NULL;"),
                        Map.of("name", "Final Model", "sql", "SELECT loan_id, status FROM clean_loans")
                ))))
                .upstreamRef("{{ ref('upstream') }}")
                .build();

        String sql = handler.emit(ctx);

        assertTrue(sql.contains("WITH clean_loans AS"), sql);
        assertTrue(sql.contains("final_model AS"), sql);
        assertTrue(sql.contains("SELECT loan_id, status FROM clean_loans"), sql);
        assertTrue(sql.endsWith("FROM final_model"), sql);
        assertFalse(sql.contains("status IS NOT NULL;"), sql);
    }

    @Test
    void noSqlDegradesToPassthrough() {
        EmitContext ctx = EmitContext.builder()
                .config(ResolvedConfig.empty())
                .upstreamRef("ref('up')")
                .build();
        String sql = handler.emit(ctx);
        assertTrue(sql.contains("SELECT * FROM ref('up')"), sql);
        assertTrue(sql.contains("no SQL supplied"), sql);
    }
}
