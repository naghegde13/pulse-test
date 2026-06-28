package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcpPhase0PassBuildEvidenceTest {

    @Test
    void writesAcceptedPhaseZeroArtifactsUnderBuildDirectory() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Path root = Path.of("build/e2e-semantic-hardening");

        GcpPhase0PassEvidenceWriter.PassArtifacts artifacts = new GcpPhase0PassEvidenceWriter(objectMapper).write(
                root,
                new GcpPhase0PassEvidenceWriter.Phase0PassSnapshot(
                        "pulse-semantic-smoke-20260506-0115",
                        "pulse-proof-04261847",
                        "us-central1",
                        "pulse-proof-composer",
                        "user:aamer@aamer.net",
                        "serviceAccount:composer-runtime@pulse-proof-04261847.iam.gserviceaccount.com",
                        "serviceAccount:semantic-runtime@pulse-proof-04261847.iam.gserviceaccount.com",
                        "user:aamer@aamer.net",
                        "pulse_semantic__run_20260506_0115",
                        "pulse-semantic-batch-20260506-0115",
                        "AIRFLOW",
                        "PASS",
                        "PASS",
                        "PASS",
                        24,
                        2,
                        2,
                        5.0,
                        18,
                        2,
                        2,
                        9,
                        7,
                        3.75
                ),
                new GcpPhase0PassEvidenceWriter.ProgressSummary(
                        72,
                        0,
                        List.of(),
                        4.0,
                        "2026-05-06T01:35:00Z",
                        "2026-05-06T03:00:00Z",
                        "none"
                )
        );

        assertTrue(Files.exists(artifacts.readinessReport()));
        assertTrue(Files.exists(artifacts.costControl()));
        assertTrue(Files.exists(artifacts.cleanupReport()));
        assertTrue(Files.exists(artifacts.composerIdlePolicy()));
        assertTrue(Files.exists(artifacts.stagingCleanup()));
        assertTrue(Files.exists(artifacts.smokeVerdict()));
        assertTrue(Files.exists(artifacts.laneLedger()));
        assertTrue(Files.exists(artifacts.progressSummary()));

        JsonNode smokeVerdict = objectMapper.readTree(artifacts.smokeVerdict().toFile());
        assertEquals("PASS", smokeVerdict.get("verdict").asText());
        assertEquals("PASS", smokeVerdict.get("composer").asText());
        assertEquals("PASS", smokeVerdict.get("composerDataproc").asText());
        assertEquals("PASS", smokeVerdict.get("generatedAdapterPrereq").asText());
        assertEquals("PASS", smokeVerdict.get("promotionPrivilegeStatus").asText());
        assertTrue(smokeVerdict.get("blockedProofShapes").isEmpty());

        JsonNode costControl = objectMapper.readTree(artifacts.costControl().toFile());
        assertEquals("PASS", costControl.get("verdict").asText());
        assertEquals(1, costControl.at("/planned/maxConcurrentCloudJobs").asInt());
        assertEquals(1, costControl.at("/observed/maxConcurrentCloudJobs").asInt());
        assertFalse(costControl.at("/concurrencyOverride/approved").asBoolean());
        assertFalse(costControl.at("/resources/0/expiresAt").asText().isBlank());
        assertFalse(costControl.at("/resources/1/expiresAt").asText().isBlank());

        JsonNode cleanup = objectMapper.readTree(artifacts.cleanupReport().toFile());
        assertEquals("PASS", cleanup.get("verdict").asText());
        assertTrue(cleanup.get("staleResources").isEmpty());

        JsonNode composerIdlePolicy = objectMapper.readTree(artifacts.composerIdlePolicy().toFile());
        assertEquals("PASS", composerIdlePolicy.get("verdict").asText());
        assertTrue(composerIdlePolicy.at("/idlePolicy/mustNotRemainRunningWithoutExplicitDecision").asBoolean());

        JsonNode ledger = objectMapper.readTree(artifacts.laneLedger().toFile());
        JsonNode readinessLane = StreamSupport.stream(ledger.get("lanes").spliterator(), false)
                .filter(lane -> "gcp-readiness-smoke".equals(lane.get("lane").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals("PASS", readinessLane.get("laneState").asText());
        assertEquals("PASS", readinessLane.get("cleanupStatus").asText());
        assertEquals("PASS", readinessLane.get("promotionPrivilegeStatus").asText());
        assertTrue(textArray(readinessLane.get("blockedProofShapes")).isEmpty());

        JsonNode progress = objectMapper.readTree(artifacts.progressSummary().toFile());
        assertEquals("PASS", progress.get("phase0GateStatus").asText());
        assertEquals("PREPARING", progress.get("gcpGoldenStatus").asText());
        assertEquals("PENDING_RUNTIME_EXECUTION", progress.get("idempotencyEvidenceStatus").asText());
        assertEquals("verdict/rerun-idempotency-coverage.json", progress.get("requiredIdempotencyArtifact").asText());
        assertEquals("gcp-environment/cleanup/composer-idle-policy.json", progress.at("/currentGateEvidence/cleanupPolicy").asText());
        assertEquals("gcp-environment/cleanup/staging-cleanup.json", progress.at("/currentGateEvidence/stagingCleanup").asText());
    }

    private List<String> textArray(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toList());
    }
}
