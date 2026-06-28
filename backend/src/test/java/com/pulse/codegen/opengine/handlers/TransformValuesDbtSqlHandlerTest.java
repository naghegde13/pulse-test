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

class TransformValuesDbtSqlHandlerTest {

    private final TransformValuesDbtSqlHandler handler = new TransformValuesDbtSqlHandler();

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.TRANSFORM_VALUES);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.DBT_SQL);
    }

    @Test
    void wrapsMappedColumnsAndPassesThroughRest() {
        Map<String, String> exprs = new LinkedHashMap<>();
        exprs.put("name", "trim(name)");
        exprs.put("note", "coalesce(note, '')");

        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of("value_expressions", exprs)))
                .inputSchema(Schema.of(
                        ColumnModel.simple("id", "long"),
                        ColumnModel.simple("name", "string"),
                        ColumnModel.simple("note", "string")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);

        assertThat(sql).contains("trim(name) AS name");
        assertThat(sql).contains("coalesce(note, '') AS note");
        // unmapped column passes through unchanged
        assertThat(sql).contains("    id");
        assertThat(sql).contains("FROM ref('up')");
        // id (unmapped) emitted before the mapped columns (schema order preserved)
        assertThat(sql.indexOf("    id")).isLessThan(sql.indexOf("trim(name)"));
    }

    @Test
    void passthroughWhenNoConfig() {
        EmitContext ctx = EmitContext.builder()
                .inputSchema(Schema.of(ColumnModel.simple("a", "string")))
                .upstreamRef("ref('up')")
                .build();

        assertThat(handler.emit(ctx)).isEqualTo("SELECT *\nFROM ref('up')");
    }
}
