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

class FlattenJsonDbtSqlHandlerTest {

    private final FlattenJsonDbtSqlHandler handler = new FlattenJsonDbtSqlHandler();

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.FLATTEN_JSON);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.DBT_SQL);
    }

    @Test
    void expandsStructFieldsViaDotAccess() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "columns", List.of("addr"),
                        "flatten_fields", List.of(
                                Map.of("column", "addr", "field", "city"),
                                Map.of("column", "addr", "field", "zip")))))
                .inputSchema(Schema.of(
                        ColumnModel.simple("id", "long"),
                        ColumnModel.struct("addr", List.of(
                                ColumnModel.simple("city", "string"),
                                ColumnModel.simple("zip", "string")), true)))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);

        assertThat(sql).contains("addr.city AS city");
        assertThat(sql).contains("addr.zip AS zip");
        // the flattened struct column itself is not passed through verbatim
        assertThat(sql).doesNotContain("    addr\n");
        // sibling scalar column passes through
        assertThat(sql).contains("    id");
        assertThat(sql).contains("FROM ref('up')");
    }

    @Test
    void passthroughWhenNoFlattenConfig() {
        EmitContext ctx = EmitContext.builder()
                .inputSchema(Schema.of(ColumnModel.simple("a", "string")))
                .upstreamRef("ref('up')")
                .build();

        assertThat(handler.emit(ctx)).isEqualTo("SELECT *\nFROM ref('up')");
    }
}
