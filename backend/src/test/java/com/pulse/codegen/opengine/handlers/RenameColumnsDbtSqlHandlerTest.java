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

class RenameColumnsDbtSqlHandlerTest {

    private final RenameColumnsDbtSqlHandler handler = new RenameColumnsDbtSqlHandler();

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.RENAME_COLUMNS);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.DBT_SQL);
    }

    @Test
    void aliasesRenamedColumnsAndPassesThroughRest() {
        Map<String, String> renames = new LinkedHashMap<>();
        renames.put("cust_id", "customer_id");
        renames.put("nm", "name");

        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of("rename_map", renames)))
                .inputSchema(Schema.of(
                        ColumnModel.simple("cust_id", "long"),
                        ColumnModel.simple("nm", "string"),
                        ColumnModel.simple("amount", "double")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);

        assertThat(sql).contains("cust_id AS customer_id");
        assertThat(sql).contains("nm AS name");
        assertThat(sql).contains("    amount");
        assertThat(sql).contains("FROM ref('up')");
    }

    @Test
    void passthroughWhenNoRenameMap() {
        EmitContext ctx = EmitContext.builder()
                .inputSchema(Schema.of(ColumnModel.simple("a", "string")))
                .upstreamRef("ref('up')")
                .build();

        assertThat(handler.emit(ctx)).isEqualTo("SELECT *\nFROM ref('up')");
    }
}
