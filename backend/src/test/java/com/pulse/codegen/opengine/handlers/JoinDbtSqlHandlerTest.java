package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JoinDbtSqlHandlerTest {

    private final JoinDbtSqlHandler handler = new JoinDbtSqlHandler();

    @Test
    void collidingColumnKeepsBothWithRightAlias_fix5() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "join_type", "left",
                        "join_keys", List.of("loan_id"))))
                .inputSchema(Schema.of(
                        ColumnModel.simple("loan_id", "string"),
                        ColumnModel.simple("amount", "double")))
                .secondarySchema(Schema.of(
                        ColumnModel.simple("loan_id", "string"),
                        ColumnModel.simple("status", "string")))
                .upstreamRef("ref('up')")
                .secondaryRef("ref('right')")
                .build();

        String sql = handler.emit(ctx);

        // FIX #5: colliding loan_id keeps BOTH; right side aliased right_loan_id.
        assertTrue(sql.contains("l.loan_id"), sql);
        assertTrue(sql.contains("r.loan_id AS right_loan_id"), sql);
        // non-colliding right col stays as-is
        assertTrue(sql.contains("r.status"), sql);
        assertFalse(sql.contains("r.status AS"), sql);
        // join type + keys
        assertTrue(sql.contains("LEFT JOIN ref('right') r"), sql);
        assertTrue(sql.contains("ON l.loan_id = r.loan_id"), sql);
        assertTrue(sql.contains("FROM ref('up') l"), sql);
    }

    @Test
    void usesJoinConditionVerbatimAndDefaultsInnerJoin() {
        EmitContext ctx = EmitContext.builder()
                .config(new ResolvedConfig(Map.of(
                        "join_condition", "l.a = r.b AND l.c > 0")))
                .inputSchema(Schema.of(ColumnModel.simple("a", "string")))
                .secondarySchema(Schema.of(ColumnModel.simple("b", "string")))
                .upstreamRef("ref('up')")
                .secondaryRef("ref('right')")
                .build();

        String sql = handler.emit(ctx);
        assertTrue(sql.contains("INNER JOIN ref('right') r"), sql);
        assertTrue(sql.contains("ON l.a = r.b AND l.c > 0"), sql);
    }
}
