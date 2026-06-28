package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcpComposerRuntimeBridgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    void writeEvidence_buildsComposerUploadTriggerAndStatusPlan() throws Exception {
        String namespace = "tenants/home-lending/pipelines/loan-master";
        Path runtimeRepoRoot = tempDir.resolve("runtime");
        Path namespacedRoot = runtimeRepoRoot.resolve(namespace);
        Files.createDirectories(namespacedRoot.resolve("dags"));
        Files.writeString(namespacedRoot.resolve("dags").resolve("loan_master_dag.py"), "# dag");
        Files.createDirectories(runtimeRepoRoot.resolve("dbt_project"));
        Files.writeString(runtimeRepoRoot.resolve("dbt_project").resolve("dbt_project.yml"), "name: pulse");

        GcpComposerRuntimeBridge bridge = new GcpComposerRuntimeBridge(objectMapper);
        Path packet = bridge.writeEvidence(
                new GcpComposerRuntimeBridge.BridgeRequest(
                        "loan-master-gcp-runtime",
                        "run-123",
                        "pulse-dev",
                        "us-central1",
                        "composer-dev",
                        "gs://composer-bucket/dags/",
                        namespace,
                        "pulse_loan_master_v1",
                        "loan_master_dag.py",
                        "pulse_e2e__run123",
                        "2026-04-26T04:00:00+00:00",
                        runtimeRepoRoot
                ),
                tempDir.resolve("evidence")
        );

        assertTrue(Files.exists(packet));
        JsonNode json = objectMapper.readTree(packet.toFile());
        assertEquals("gs://composer-bucket/dags", json.get("dagGcsPrefix").asText());
        assertEquals(
                "gs://composer-bucket/dags/" + namespace,
                json.get("namespacedDagGcsPrefix").asText()
        );
        assertTrue(json.get("uploadCommands").get(0).asText().contains(namespacedRoot.toString()));
        assertTrue(json.get("uploadCommands").get(0).asText().contains("gs://composer-bucket/dags/" + namespace));
        assertTrue(json.get("uploadCommands").get(1).asText().contains(runtimeRepoRoot.resolve("dbt_project").toString()));
        assertTrue(json.get("triggerCommand").asText().contains("gcloud composer environments run composer-dev --location=us-central1 --project=pulse-dev dags trigger -- pulse_loan_master_v1"));
        assertTrue(json.get("triggerCommand").asText().contains("--logical-date=2026-04-26T04:00:00+00:00"));
        assertTrue(json.get("statusCommands").get(0).asText().contains("dags state -- pulse_loan_master_v1 2026-04-26T04:00:00+00:00"));
        assertTrue(json.get("statusCommands").get(1).asText().contains("tasks states-for-dag-run -- pulse_loan_master_v1 2026-04-26T04:00:00+00:00"));
        assertTrue(json.get("notes").get("dbtProjectPresent").asBoolean());
    }
}
