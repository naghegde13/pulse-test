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

class MaskColumnsDbtSqlHandlerTest {

    private final MaskColumnsDbtSqlHandler handler = new MaskColumnsDbtSqlHandler();

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.MASK_COLUMNS);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.DBT_SQL);
    }

    @Test
    void hashesPiiColumnsByDefault() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of("pii_columns", List.of("ssn"))))
                .inputSchema(Schema.of(
                        ColumnModel.simple("id", "long"),
                        ColumnModel.simple("ssn", "string")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);

        assertThat(sql).contains("sha2(CAST(ssn AS STRING), 256) AS ssn");
        assertThat(sql).contains("    id");
        assertThat(sql).contains("FROM ref('up')");
    }

    @Test
    void redactsViaMaskSpecStrategy() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "mask_specs", List.of(
                                Map.of("column", "email", "strategy", "redact"),
                                Map.of("column", "phone", "strategy", "hash")))))
                .inputSchema(Schema.of(
                        ColumnModel.simple("email", "string"),
                        ColumnModel.simple("phone", "string")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);

        assertThat(sql).contains("'***' AS email");
        assertThat(sql).contains("sha2(CAST(phone AS STRING), 256) AS phone");
    }

    @Test
    void passthroughWhenNoMaskConfig() {
        EmitContext ctx = EmitContext.builder()
                .inputSchema(Schema.of(ColumnModel.simple("a", "string")))
                .upstreamRef("ref('up')")
                .build();

        assertThat(handler.emit(ctx)).isEqualTo("SELECT *\nFROM ref('up')");
    }
}
