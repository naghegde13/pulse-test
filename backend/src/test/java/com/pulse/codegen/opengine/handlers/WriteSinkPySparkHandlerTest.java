package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.codegen.opengine.ModeResolver;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WriteSinkPySparkHandlerTest {

    private final WriteSinkPySparkHandler handler = new WriteSinkPySparkHandler();

    /**
     * A real ModeResolver over a Mockito-mock RuntimeAuthorityService. The handler
     * only calls the pure {@code fileFormatFor(mode, layer)} method, which never
     * touches the RuntimeAuthorityService, so the mock is never invoked.
     */
    private ModeResolver resolver() {
        return new ModeResolver(mock(RuntimeAuthorityService.class));
    }

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.WRITE_SINK);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.PYSPARK);
    }

    @Test
    void gcpBronzeEmitsIcebergAndNeverDelta() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .modeResolver(resolver())
                .config(new ResolvedConfig(Map.of("target_path", "gs://out/loans")))
                .lakeLayer("bronze")
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("df.write");
        assertThat(py).contains(".format('iceberg')");
        assertThat(py).contains(".mode('overwrite')");
        assertThat(py).doesNotContain("delta");
        assertThat(py).contains(".save(PULSE_TARGET_URI)");
    }

    @Test
    void gcpGoldEmitsBigQueryNativeWriteForm() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .modeResolver(resolver())
                .config(new ResolvedConfig(Map.of("write_mode", "append")))
                .lakeLayer("gold")
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("df.write.format('bigquery')");
        assertThat(py).contains(".option('table', PULSE_TARGET_URI)");
        assertThat(py).contains(".mode('append')");
        assertThat(py).doesNotContain("delta");
    }

    @Test
    void dpcEmitsParquet() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.DPC_PULSE)
                .modeResolver(resolver())
                .config(new ResolvedConfig(Map.of("target_path", "s3a://out/loans")))
                .lakeLayer("bronze")
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains(".format('parquet')");
        assertThat(py).doesNotContain("delta");
        assertThat(py).doesNotContain("iceberg");
    }

    @Test
    void defaultWriteModeIsOverwrite() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .modeResolver(resolver())
                .config(ResolvedConfig.empty())
                .lakeLayer("silver")
                .dfVar("df")
                .build();

        assertThat(handler.emit(ctx)).contains(".mode('overwrite')");
    }
}
