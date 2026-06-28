package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcpDataprocExecutionPlannerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    void writeEvidence_buildsExistingClusterSubmitAndProbePlan() throws Exception {
        String namespace = "tenants/home-lending/pipelines/loan-master";
        Path runtimeRepoRoot = tempDir.resolve("runtime");
        Path namespacedRoot = runtimeRepoRoot.resolve(namespace);
        Files.createDirectories(namespacedRoot.resolve("jobs").resolve("ingestion"));
        Files.writeString(namespacedRoot.resolve("jobs/ingestion/loan_master_ingest.py"), "print('ok')");
        Files.createDirectories(namespacedRoot.resolve("runtime"));
        Files.writeString(namespacedRoot.resolve("runtime/pulse_secret_resolver.py"), "# helper");

        GcpDataprocExecutionPlanner planner = new GcpDataprocExecutionPlanner(objectMapper);
        Path packet = planner.writeEvidence(
                new GcpDataprocExecutionPlanner.ExecutionRequest(
                        "loan-master-gcp-runtime",
                        "run-123",
                        "pulse-dev",
                        "us-central1",
                        GcpDataprocExecutionPlanner.TargetMode.EXISTING_CLUSTER,
                        "spark-dev",
                        null,
                        null,
                        "pulse-staging",
                        "gs://pulse-output/loan-master/current",
                        namespace,
                        "jobs/ingestion/loan_master_ingest.py",
                        List.of("runtime/pulse_secret_resolver.py"),
                        List.of("--date=2026-04-26"),
                        runtimeRepoRoot
                ),
                tempDir.resolve("evidence")
        );

        assertTrue(Files.exists(packet));
        JsonNode json = objectMapper.readTree(packet.toFile());
        assertEquals("gs://pulse-staging/pulse-runtime/loan-master-gcp-runtime/run-123", json.get("stageRoot").asText());
        assertTrue(json.get("stageCommands").get(0).asText().contains("gcloud storage cp --recursive"));
        assertTrue(json.get("submitCommand").asText().contains("gcloud dataproc jobs submit pyspark"));
        assertTrue(json.get("submitCommand").asText().contains("--cluster=spark-dev"));
        assertTrue(json.get("submitCommand").asText().contains("--py-files=gs://pulse-staging/pulse-runtime/loan-master-gcp-runtime/run-123/" + namespace + "/runtime/pulse_secret_resolver.py"));
        assertTrue(json.get("statusCommands").get(0).asText().contains("gcloud dataproc jobs wait $JOB_ID"));
        assertTrue(json.get("outputProbeCommands").get(0).asText().contains("gcloud storage ls --recursive gs://pulse-output/loan-master/current"));
    }

    @Test
    void writeEvidence_buildsServerlessBatchSubmitAndDescribePlan() throws Exception {
        String namespace = "tenants/home-lending/pipelines/loan-master";
        Path runtimeRepoRoot = tempDir.resolve("runtime-serverless");
        Path namespacedRoot = runtimeRepoRoot.resolve(namespace);
        Files.createDirectories(namespacedRoot.resolve("jobs").resolve("ingestion"));
        Files.writeString(namespacedRoot.resolve("jobs/ingestion/loan_master_ingest.py"), "print('ok')");

        GcpDataprocExecutionPlanner planner = new GcpDataprocExecutionPlanner(objectMapper);
        Path packet = planner.writeEvidence(
                new GcpDataprocExecutionPlanner.ExecutionRequest(
                        "loan-master-gcp-runtime",
                        "run-456",
                        "pulse-dev",
                        "us-central1",
                        GcpDataprocExecutionPlanner.TargetMode.SERVERLESS_BATCH,
                        null,
                        "loan-master-batch",
                        "svc-pulse@pulse-dev.iam.gserviceaccount.com",
                        "gs://pulse-staging",
                        "gs://pulse-output/loan-master/current",
                        namespace,
                        "jobs/ingestion/loan_master_ingest.py",
                        List.of(),
                        List.of("--date=2026-04-26"),
                        runtimeRepoRoot
                ),
                tempDir.resolve("evidence-serverless")
        );

        JsonNode json = objectMapper.readTree(packet.toFile());
        assertTrue(json.get("submitCommand").asText().contains("gcloud dataproc batches submit pyspark"));
        assertTrue(json.get("submitCommand").asText().contains("--batch=loan-master-batch"));
        assertTrue(json.get("submitCommand").asText().contains("--service-account=svc-pulse@pulse-dev.iam.gserviceaccount.com"));
        assertTrue(json.get("statusCommands").get(0).asText().contains("gcloud dataproc batches describe loan-master-batch"));
    }
}
