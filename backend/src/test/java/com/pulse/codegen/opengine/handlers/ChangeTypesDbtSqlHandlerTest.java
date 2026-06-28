package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeTypesDbtSqlHandlerTest {

    private final ChangeTypesDbtSqlHandler handler = new ChangeTypesDbtSqlHandler();

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.CHANGE_TYPES);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.DBT_SQL);
    }

    @Test
    void castsCoercedColumnsAndPassesThroughRest() {
        Map<String, String> coercions = new LinkedHashMap<>();
        coercions.put("amount", "DECIMAL(18,2)");
        coercions.put("ts", "TIMESTAMP");

        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of("type_coercions", coercions)))
                .inputSchema(Schema.of(
                        ColumnModel.simple("id", "long"),
                        ColumnModel.simple("amount", "string"),
                        ColumnModel.simple("ts", "string")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);

        assertThat(sql).contains("CAST(amount AS DECIMAL(18,2)) AS amount");
        assertThat(sql).contains("CAST(ts AS TIMESTAMP) AS ts");
        assertThat(sql).contains("    id");
        assertThat(sql).contains("FROM ref('up')");
    }

    @Test
    void passthroughWhenNoCoercions() {
        EmitContext ctx = EmitContext.builder()
                .inputSchema(Schema.of(ColumnModel.simple("a", "string")))
                .upstreamRef("ref('up')")
                .build();

        assertThat(handler.emit(ctx)).isEqualTo("SELECT *\nFROM ref('up')");
    }
}
