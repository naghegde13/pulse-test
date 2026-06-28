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

class KeepColumnsDbtSqlHandlerTest {

    private final KeepColumnsDbtSqlHandler handler = new KeepColumnsDbtSqlHandler();

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.KEEP_COLUMNS);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.DBT_SQL);
    }

    @Test
    void projectsKeepColumnsInConfigOrder() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                // config order (name, id) intentionally differs from schema order (id, name, extra)
                .config(new ResolvedConfig(Map.of("keep_columns", List.of("name", "id"))))
                .inputSchema(Schema.of(
                        ColumnModel.simple("id", "long"),
                        ColumnModel.simple("name", "string"),
                        ColumnModel.simple("extra", "string")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);

        assertThat(sql).contains("    name");
        assertThat(sql).contains("    id");
        assertThat(sql).doesNotContain("extra");
        // config order is authoritative: name before id
        assertThat(sql.indexOf("name")).isLessThan(sql.indexOf("    id"));
        assertThat(sql).contains("FROM ref('up')");
    }

    @Test
    void passthroughWhenNoKeepColumns() {
        EmitContext ctx = EmitContext.builder()
                .inputSchema(Schema.of(ColumnModel.simple("a", "string")))
                .upstreamRef("ref('up')")
                .build();

        assertThat(handler.emit(ctx)).isEqualTo("SELECT *\nFROM ref('up')");
    }
}
