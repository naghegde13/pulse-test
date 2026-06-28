package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AddColumnDbtSqlHandlerTest {

    private final AddColumnDbtSqlHandler handler = new AddColumnDbtSqlHandler();

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.ADD_COLUMN);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.DBT_SQL);
    }

    @Test
    void emitsExprAsNewColumn() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "name", "full_name",
                        "expression", "concat(first, ' ', last)")))
                .inputSchema(Schema.of(
                        ColumnModel.simple("first", "string"),
                        ColumnModel.simple("last", "string")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);

        assertThat(sql).contains("SELECT *, concat(first, ' ', last) AS full_name");
        assertThat(sql).contains("FROM ref('up')");
    }

    @Test
    void passthroughWhenNoName() {
        EmitContext ctx = EmitContext.builder()
                .config(new ResolvedConfig(Map.of("expression", "1")))
                .upstreamRef("ref('up')")
                .build();

        assertThat(handler.emit(ctx)).isEqualTo("SELECT *\nFROM ref('up')");
    }
}
