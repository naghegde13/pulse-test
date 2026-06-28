package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.codegen.opengine.ModeResolver;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CheckDataGxHandlerTest {

    private final CheckDataGxHandler handler = new CheckDataGxHandler();

    private ModeResolver resolver() {
        return new ModeResolver(mock(RuntimeAuthorityService.class));
    }

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.CHECK_DATA);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.GX);
    }

    @Test
    void onFailureBlockEmitsRaiseAndAssertThatFailsTheTask() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .modeResolver(resolver())
                .config(new ResolvedConfig(Map.of(
                        "on_failure", "block",
                        "expectations", List.of(
                                Map.of("type", "ExpectColumnValuesToNotBeNull",
                                        "kwargs", Map.of("column", "id"))))))
                .lakeLayer("silver")
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("gx.expectations.ExpectColumnValuesToNotBeNull(column='id')");
        assertThat(py).contains("if not checkpoint_result.success:");
        assertThat(py).contains("assert checkpoint_result.success");
        assertThat(py).contains("raise Exception");
    }

    @Test
    void defaultOnFailureIsBlockSoItRaises() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .modeResolver(resolver())
                .config(new ResolvedConfig(Map.of(
                        "expectations", List.of(
                                Map.of("type", "ExpectColumnToExist",
                                        "kwargs", Map.of("column", "amount"))))))
                .lakeLayer("silver")
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("assert checkpoint_result.success");
        assertThat(py).contains("raise Exception");
    }

    @Test
    void warnDoesNotRaise() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .modeResolver(resolver())
                .config(new ResolvedConfig(Map.of(
                        "on_failure", "warn",
                        "expectations", List.of(
                                Map.of("type", "ExpectColumnToExist",
                                        "kwargs", Map.of("column", "amount"))))))
                .lakeLayer("silver")
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("on_failure=warn");
        assertThat(py).doesNotContain("raise Exception");
        assertThat(py).doesNotContain("assert checkpoint_result.success");
    }

    @Test
    void quarantineRoutesFailingRowsAppendNotOverwrite() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .modeResolver(resolver())
                .config(new ResolvedConfig(Map.of(
                        "on_failure", "block",
                        "quarantine", true,
                        "expectations", List.of(
                                Map.of("type", "ExpectColumnToExist",
                                        "kwargs", Map.of("column", "amount"))))))
                .lakeLayer("silver")
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("df_quarantine = df.filter");
        assertThat(py).contains(".write.mode('append').format('iceberg')");
        // Quarantine plus block still raises after routing.
        assertThat(py).contains("raise Exception");
    }
}
