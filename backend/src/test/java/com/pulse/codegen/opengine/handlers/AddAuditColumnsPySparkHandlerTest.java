package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.audit.IngestionAuditColumns;
import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AddAuditColumnsPySparkHandlerTest {

    private final AddAuditColumnsPySparkHandler handler = new AddAuditColumnsPySparkHandler();

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.ADD_AUDIT_COLUMNS);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.PYSPARK);
    }

    @Test
    void emitsTheFullEightColumnAuditSetIncludingDagId() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "pipeline_slug", "loans_bronze",
                        "task_slug", "ingest")))
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        // The 8 locked audit columns (C-1) must all appear, delegated to the SoT.
        for (String name : IngestionAuditColumns.NAMES) {
            assertThat(py).as("audit column " + name).contains("'" + name + "'");
        }
        // Key guarantee: _pulse_dag_id present (8-col set).
        assertThat(py).contains("_pulse_dag_id");
        assertThat(py).contains("lit('loans_bronze')");
    }

    @Test
    void usesFileSourceContextForInputFileName() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of("source_context", "FILE")))
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("input_file_name()");
        assertThat(py).contains("_pulse_dag_id");
    }

    @Test
    void defaultsAreSafeWhenUnconfigured() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(ResolvedConfig.empty())
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("lit('pipeline')");
        // GENERIC source context -> PULSE_SOURCE_URI env fallback (not input_file_name).
        assertThat(py).contains("os.environ.get('PULSE_SOURCE_URI', '')");
        assertThat(py).contains("_pulse_dag_id");
    }
}
