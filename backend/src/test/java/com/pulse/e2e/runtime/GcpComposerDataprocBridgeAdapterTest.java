package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.contract.ScenarioDsl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcpComposerDataprocBridgeAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    void writeEvidence_rendersComposerDagWithDataprocServerlessOperatorAndSubmitRequest() throws Exception {
        String namespace = "tenants/home-lending/pipelines/loan-master";
        Path runtimeRepoRoot = tempDir.resolve("runtime");
        Path namespacedRoot = runtimeRepoRoot.resolve(namespace);
        Files.createDirectories(namespacedRoot.resolve("jobs/filter"));
        Files.writeString(namespacedRoot.resolve("jobs/filter/generic_filter.py"), "print('filter')");
        Files.createDirectories(namespacedRoot.resolve("runtime"));
        Files.writeString(namespacedRoot.resolve("runtime/pulse_secret_resolver.py"), "# helper");
        Files.createDirectories(namespacedRoot.resolve("dags"));

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(
                request(runtimeRepoRoot, namespace),
                tempDir.resolve("evidence")
        );

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        String renderedDag = Files.readString(written.renderedDag());
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("submit_dataproc_batch"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_generic_filter"));
        assertTrue(renderedDag.contains("batch_id=BATCH_ID"));
        assertFalse(renderedDag.contains("BashOperator"));
        assertFalse(renderedDag.toLowerCase().contains("minio"));

        JsonNode submit = objectMapper.readTree(written.dataprocSubmitRequest().toFile());
        assertEquals(ScenarioDsl.RuntimeAdapter.GCP_COMPOSER_DATAPROC_BRIDGE.name(), submit.get("runtimeAdapter").asText());
        assertEquals("pulse-proof-04261847", submit.get("projectId").asText());
        assertEquals("semantic-generic-filter-batch", submit.get("batchId").asText());
        assertEquals("GCLOUD_SUBPROCESS", submit.get("authMode").asText());
        assertEquals("GCLOUD_COMPOSER_RUN", submit.get("controlPlaneMode").asText());
        assertEquals(
                "gs://pulse-proof-stage/pulse-runtime/generic-filter-gcp-golden/run-0426/"
                        + namespace + "/jobs/filter/generic_filter.py",
                submit.at("/batch/pyspark_batch/main_python_file_uri").asText()
        );
        assertEquals(
                "semantic-runtime@pulse-proof-04261847.iam.gserviceaccount.com",
                submit.at("/batch/environment_config/execution_config/service_account").asText()
        );
        assertEquals("pulse-proof-stage", submit.at("/batch/environment_config/execution_config/staging_bucket").asText());
        assertTrue(submit.get("sourcePlanCommand").asText().contains("gcloud dataproc batches submit pyspark"));

        JsonNode adapterPacket = objectMapper.readTree(written.adapterPlan().toFile());
        assertEquals("runtime/dataproc-submit-request.json", adapterPacket.get("dataprocSubmitRequest").asText());
        assertEquals("runtime/composer-dataproc/generic_filter_gcp_dag.py", adapterPacket.get("renderedDag").asText());
        assertEquals("semantic-generic-filter-batch", adapterPacket.get("dataprocBatchId").asText());

        JsonNode composer = objectMapper.readTree(written.composerEvidence().toFile());
        assertTrue(composer.get("triggerCommand").asText().contains("dags trigger -- pulse_semantic_gcp_generic_filter"));

        JsonNode dataproc = objectMapper.readTree(written.dataprocEvidence().toFile());
        assertTrue(dataproc.get("submitCommand").asText().contains("--batch=semantic-generic-filter-batch"));
    }

    @Test
    void plan_keepsRenderedDagAndSubmitRequestInMemoryForSmokePreviews() throws Exception {
        String namespace = "tenants/home-lending/pipelines/loan-master";
        Path runtimeRepoRoot = tempDir.resolve("preview-runtime");
        Files.createDirectories(runtimeRepoRoot.resolve(namespace).resolve("jobs/filter"));
        Files.writeString(runtimeRepoRoot.resolve(namespace).resolve("jobs/filter/generic_filter.py"), "print('filter')");

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.AdapterPlan plan = adapter.plan(request(runtimeRepoRoot, namespace));

        assertEquals(ScenarioDsl.RuntimeAdapter.GCP_COMPOSER_DATAPROC_BRIDGE.name(), plan.runtimeAdapter());
        assertEquals("pulse_semantic_gcp_generic_filter", plan.composerPlan().dagId());
        assertEquals("semantic-generic-filter-batch", plan.dataprocPlan().batchId());
        assertTrue(plan.renderedDag().contains("from airflow.providers.google.cloud.operators.dataproc import DataprocCreateBatchOperator"));
        assertEquals("semantic-generic-filter-batch", plan.dataprocSubmitRequest().get("batchId"));
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request(Path runtimeRepoRoot, String namespace) {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "generic-filter-gcp-golden",
                "run-0426",
                "pulse-proof-04261847",
                "us-central1",
                "pulse-proof-composer",
                "gs://us-central1-pulse-proof-com-a7066110-bucket/dags",
                "us-central1",
                "semantic-runtime@pulse-proof-04261847.iam.gserviceaccount.com",
                "gs://pulse-proof-stage",
                "gs://pulse-proof-output/generic-filter/run-0426",
                namespace,
                "pulse_semantic_gcp_generic_filter",
                "generic_filter_gcp_dag.py",
                "pulse_semantic__run_0426",
                "2026-04-26T04:00:00+00:00",
                "semantic-generic-filter-batch",
                "jobs/filter/generic_filter.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--input", "gs://pulse-proof-fixtures/generic-filter/input.json",
                        "--output", "gs://pulse-proof-output/generic-filter/run-0426"
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                runtimeRepoRoot
        );
    }
}
