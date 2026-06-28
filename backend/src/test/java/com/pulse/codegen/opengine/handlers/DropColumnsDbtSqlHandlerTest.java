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

class DropColumnsDbtSqlHandlerTest {

    private final DropColumnsDbtSqlHandler handler = new DropColumnsDbtSqlHandler();

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.DROP_COLUMNS);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.DBT_SQL);
    }

    @Test
    void projectsKeptColumnsInSchemaOrder() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of("drop_columns", List.of("ssn", "dob"))))
                .inputSchema(Schema.of(
                        ColumnModel.simple("id", "long"),
                        ColumnModel.simple("ssn", "string"),
                        ColumnModel.simple("name", "string"),
                        ColumnModel.simple("dob", "date")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);

        assertThat(sql).contains("    id");
        assertThat(sql).contains("    name");
        assertThat(sql).doesNotContain("ssn");
        assertThat(sql).doesNotContain("dob");
        assertThat(sql).contains("FROM ref('up')");
        assertThat(sql.indexOf("id")).isLessThan(sql.indexOf("name"));
    }

    @Test
    void passthroughWhenNothingToDrop() {
        EmitContext ctx = EmitContext.builder()
                .inputSchema(Schema.of(ColumnModel.simple("a", "string")))
                .upstreamRef("ref('up')")
                .build();

        assertThat(handler.emit(ctx)).isEqualTo("SELECT *\nFROM ref('up')");
    }
}
