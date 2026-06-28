package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadSourcePySparkHandlerTest {

    private final ReadSourcePySparkHandler handler = new ReadSourcePySparkHandler();

    @Test
    void declaresOpAndEngine() {
        assertThat(handler.opName()).isEqualTo(OpVocabulary.READ_SOURCE);
        assertThat(handler.engine()).isEqualTo(EmissionEngine.PYSPARK);
    }

    @Test
    void emitsBatchReadWithFormatAndUri() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "source_format", "parquet",
                        "source_uri", "gs://bucket/in/")))
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("df = (spark.read.format('parquet')");
        assertThat(py).contains(".option('header', 'true')");
        assertThat(py).contains(".load(os.environ.get('PULSE_SOURCE_URI', 'gs://bucket/in/')))");
        assertThat(py).doesNotContain("readStream");
    }

    @Test
    void defaultsToCsvAndEnvFallbackWhenUnconfigured() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(ResolvedConfig.empty())
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("spark.read.format('csv')");
        assertThat(py).contains("os.environ.get('PULSE_SOURCE_URI', '')");
    }

    @Test
    void emitsStreamingReadForKafkaTransport() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "transport", "kafka",
                        "source_uri", "cdc-topic")))
                .dfVar("df")
                .build();

        String py = handler.emit(ctx);

        assertThat(py).contains("spark.readStream.format('kafka')");
        assertThat(py).contains(".option('subscribe', os.environ.get('PULSE_KAFKA_TOPIC', 'cdc-topic'))");
        assertThat(py).doesNotContain("spark.read.format");
    }

    @Test
    void respectsCustomDfVar() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(ResolvedConfig.empty())
                .dfVar("raw")
                .build();

        assertThat(handler.emit(ctx)).contains("raw = (spark.read.format('csv')");
    }
}
