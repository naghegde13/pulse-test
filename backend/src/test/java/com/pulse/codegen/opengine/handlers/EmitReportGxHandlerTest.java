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

class EmitReportGxHandlerTest {

    private final EmitReportGxHandler handler = new EmitReportGxHandler();

    private ModeResolver resolver() {
        return new ModeResolver(mock(RuntimeAuthorityService.class));
    }

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.EMIT_REPORT);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.GX);
    }

    @Test
    void defaultReportModeIsAppendNotOverwrite_fix7() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .modeResolver(resolver())
                .config(ResolvedConfig.empty())
                .lakeLayer("silver")
                .build();

        String py = handler.emit(ctx);

        // FIX #7: default must be append, never the legacy overwrite.
        assertThat(py).contains("report_df.write.mode('append')");
        assertThat(py).doesNotContain(".mode('overwrite')");
        // And never delta (C-2).
        assertThat(py).doesNotContain("delta");
        assertThat(py).contains(".format('iceberg')");
    }

    @Test
    void explicitOverwriteIsHonored() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .modeResolver(resolver())
                .config(new ResolvedConfig(Map.of(
                        "report_mode", "overwrite",
                        "report_path", "gs://reports/dq")))
                .lakeLayer("silver")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("report_df.write.mode('overwrite')");
        assertThat(py).contains("os.environ.get('PULSE_REPORT_URI', 'gs://reports/dq')");
    }

    @Test
    void gcpGoldUsesBqNativeFormatAndStillNoDelta() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .modeResolver(resolver())
                .config(ResolvedConfig.empty())
                .lakeLayer("gold")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains(".format('bq_native')");
        assertThat(py).contains(".mode('append')");
        assertThat(py).doesNotContain("delta");
    }

    @Test
    void dpcUsesParquet() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.DPC_PULSE)
                .modeResolver(resolver())
                .config(ResolvedConfig.empty())
                .lakeLayer("bronze")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains(".format('parquet')");
        assertThat(py).contains(".mode('append')");
        assertThat(py).doesNotContain("delta");
    }
}
