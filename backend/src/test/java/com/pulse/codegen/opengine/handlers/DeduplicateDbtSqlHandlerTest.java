package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeduplicateDbtSqlHandlerTest {

    private final DeduplicateDbtSqlHandler handler = new DeduplicateDbtSqlHandler();

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.DEDUPLICATE);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.DBT_SQL);
    }

    @Test
    void emitsQualifiedRowNumberWithExplicitOrderBy() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "dedup_key", List.of("customer_id"),
                        "order_by", "updated_at DESC")))
                .inputSchema(Schema.of(
                        ColumnModel.simple("customer_id", "long"),
                        ColumnModel.simple("updated_at", "timestamp")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);

        assertThat(sql).contains("QUALIFY row_number() OVER (PARTITION BY customer_id ORDER BY updated_at DESC) = 1");
        assertThat(sql).contains("FROM ref('up')");
    }

    @Test
    void hasDeterministicOrderByFallbackToDedupKey() {
        // ADR 0009: a dedup MUST have a stable ORDER BY even with no order_by config.
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of("dedup_key", List.of("a", "b"))))
                .inputSchema(Schema.of(
                        ColumnModel.simple("a", "string"),
                        ColumnModel.simple("b", "string")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);

        assertThat(sql).contains("PARTITION BY a, b");
        // deterministic tiebreaker: ORDER BY the partition columns themselves
        assertThat(sql).contains("ORDER BY a, b");
        assertThat(sql).contains("= 1");
    }

    @Test
    void distinctStarWhenNoDedupKey() {
        EmitContext ctx = EmitContext.builder()
                .inputSchema(Schema.of(ColumnModel.simple("a", "string")))
                .upstreamRef("ref('up')")
                .build();

        assertThat(handler.emit(ctx)).isEqualTo("SELECT DISTINCT *\nFROM ref('up')");
    }
}
