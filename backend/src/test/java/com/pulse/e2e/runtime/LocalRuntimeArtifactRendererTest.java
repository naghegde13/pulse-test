package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRuntimeArtifactRendererTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void render_rewritesRuntimePlaceholders_andEmitsEvidence() throws Exception {
        Path runtimeRoot = tempDir.resolve("runtime-root");
        Path evidenceRoot = tempDir.resolve("evidence");

        Path ingestionJob = runtimeRoot.resolve("jobs/ingestion/ingest_loan_master_ingest.py");
        Files.createDirectories(ingestionJob.getParent());
        Files.writeString(ingestionJob, """
                output_path = '${OUTPUT_BASE}/loan_master_runtime/ingest_loan_master'
                """);

        Path sinkJob = runtimeRoot.resolve("jobs/sink/write_loan_master_sink.py");
        Files.createDirectories(sinkJob.getParent());
        Files.writeString(sinkJob, """
                input_path = '${OUTPUT_BASE}/loan_master_runtime/${UPSTREAM_TASK}'
                """);

        Path gxScript = runtimeRoot.resolve("gx/checkpoints/filter_current_loans_checkpoint.py");
        Files.createDirectories(gxScript.getParent());
        Files.writeString(gxScript, """
                df = spark.read.format('delta').load(os.environ.get('OUTPUT_BASE', '/tmp') + '/silver/filter_current_loans')
                """);

        Path dag = runtimeRoot.resolve("dags/loan_master_runtime_dag.py");
        Files.createDirectories(dag.getParent());
        Files.writeString(dag, """
                from runtime.pulse_secret_resolver import cleanup_runtime_secret_files, resolve_runtime_secret_env
                    'retries': 3,
                    schedule='@daily',
                    catchup=True,
                ingest = SparkSubmitOperator(application='jobs/ingestion/ingest_loan_master_ingest.py')
                gate = PythonOperator(python_callable=lambda **ctx: [__import__('subprocess').run(['python', 'gx/checkpoints/filter_current_loans_checkpoint.py'], check=True)])
                bash_command='cd /opt/dbt && dbt build --select tag:loan_master_runtime,tag:filter_current_loans',
                """);

        LocalRuntimeArtifactRenderer renderer = new LocalRuntimeArtifactRenderer(objectMapper);
        LocalRuntimeArtifactRenderer.RenderResult result = renderer.render(
                new LocalRuntimeArtifactRenderer.RenderRequest(
                        "loan-master-live-runtime",
                        "servicing/pipelines/loan_master_runtime",
                        runtimeRoot,
                        evidenceRoot,
                        "s3a://pulse-dpc-home-lending-dev-lake/home-lending/servicing/loan-master-runtime",
                        "/opt/pulse/repo/home-lending/dbt_project",
                        Map.of("write_loan_master", "filter_current_loans")
                )
        );

        assertTrue(Files.readString(ingestionJob).contains(
                "s3a://pulse-dpc-home-lending-dev-lake/home-lending/servicing/loan-master-runtime/loan_master_runtime/ingest_loan_master"));
        assertTrue(Files.readString(sinkJob).contains(
                "s3a://pulse-dpc-home-lending-dev-lake/home-lending/servicing/loan-master-runtime/loan_master_runtime/filter_current_loans"));
        assertTrue(Files.readString(gxScript).contains(
                "'s3a://pulse-dpc-home-lending-dev-lake/home-lending/servicing/loan-master-runtime' + '/silver/filter_current_loans'"));
        assertTrue(Files.readString(dag).contains(
                "cd /opt/pulse/repo/home-lending/dbt_project && /home/airflow/.local/bin/dbt deps --project-dir /opt/pulse/repo/home-lending/dbt_project --profiles-dir /opt/pulse/repo/home-lending/dbt_project && /home/airflow/.local/bin/dbt build --project-dir /opt/pulse/repo/home-lending/dbt_project --profiles-dir /opt/pulse/repo/home-lending/dbt_project --select tag:loan_master_runtime,tag:filter_current_loans"));
        assertTrue(Files.readString(dag).contains(
                "sys.path.append(str(Path(__file__).resolve().parents[1]))"));
        assertTrue(Files.readString(dag).contains(
                "application='/opt/pulse/repo/home-lending/servicing/pipelines/loan_master_runtime/jobs/ingestion/ingest_loan_master_ingest.py'"));
        assertTrue(Files.readString(dag).contains(
                "['python', '/opt/pulse/repo/home-lending/servicing/pipelines/loan_master_runtime/gx/checkpoints/filter_current_loans_checkpoint.py']"));
        assertTrue(Files.readString(dag).contains("'retries': 0,"));
        assertTrue(Files.readString(dag).contains("schedule=None,"));
        assertTrue(Files.readString(dag).contains("catchup=False,"));

        assertEquals(4, result.patchedFiles().size());
        Path packet = evidenceRoot.resolve("runtime-render.json");
        assertTrue(Files.exists(packet));

        JsonNode json = objectMapper.readTree(packet.toFile());
        assertEquals("loan-master-live-runtime", json.get("scenarioId").asText());
        assertEquals(4, json.get("patchedFiles").size());
        assertEquals("filter_current_loans", json.get("upstreamTaskBySinkSlug").get("write_loan_master").asText());
        assertEquals("RUNTIME_RENDER_PACKET", result.evidenceBundle().artifacts().getFirst().type());
    }
}
