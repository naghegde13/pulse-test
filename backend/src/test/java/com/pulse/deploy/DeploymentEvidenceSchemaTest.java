package com.pulse.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PulseRole;
import com.pulse.deploy.evidence.DeploymentEvidenceService;
import com.pulse.deploy.model.DeploymentEvent;
import com.pulse.deploy.model.DeploymentEvidence;
import com.pulse.deploy.preflight.PreflightCheckCode;
import com.pulse.deploy.preflight.PreflightCheckResult;
import com.pulse.deploy.repository.DeploymentEventRepository;
import com.pulse.deploy.repository.DeploymentEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4 contract — every evidence and event row uses the documented
 * {@code v1} envelope and matches the published JSON fixtures.
 */
class DeploymentEvidenceSchemaTest {

    private DeploymentEvidenceRepository evidenceRepo;
    private DeploymentEventRepository eventRepo;
    private DeploymentEvidenceService service;

    @BeforeEach
    void setUp() {
        evidenceRepo = mock(DeploymentEvidenceRepository.class);
        eventRepo = mock(DeploymentEventRepository.class);
        when(evidenceRepo.save(any(DeploymentEvidence.class))).thenAnswer(inv -> {
            DeploymentEvidence ev = inv.getArgument(0);
            if (ev.getId() == null) ev.setId("ev-stub");
            return ev;
        });
        when(eventRepo.save(any(DeploymentEvent.class))).thenAnswer(inv -> {
            DeploymentEvent ev = inv.getArgument(0);
            if (ev.getId() == null) ev.setId("evt-stub");
            return ev;
        });
        service = new DeploymentEvidenceService(evidenceRepo, eventRepo);
    }

    @Test
    @DisplayName("Preflight evidence carries deployment-evidence.v1 envelope + canonical body")
    void preflightEvidenceUsesV1Envelope() {
        PreflightCheckResult preflight = PreflightCheckResult.of(
                "pkg-1", "tenant-A", "dev", "target-1",
                List.of(PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.PACKAGE_COMPLETED)),
                Instant.parse("2026-05-04T00:00:00Z"));

        DeploymentEvidence saved = service.recordPreflightResult(
                "dep-1", "run-1", "pkg-1", preflight, "corr-1");

        assertEquals("deployment-evidence.v1", saved.getSchemaVersion());
        assertEquals("PREFLIGHT_RESULT", saved.getType());
        assertEquals("validation/preflight-result.json", saved.getPath());
        assertEquals("DeploymentPreflightService", saved.getProducedBy());
        assertEquals("corr-1", saved.getCorrelationId());
        assertNotNull(saved.getSha256());
        assertEquals(64, saved.getSha256().length(), "sha256 must be 64 hex chars");
        assertEquals("PASS", saved.getSummary().get("status"));
        assertEquals(0, saved.getSummary().get("blockerCount"));
        // Body is the canonical preflight result.
        assertEquals("deployment-preflight-result.v1",
                saved.getBody().get("schemaVersion"));
    }

    @Test
    @DisplayName("Run-state-changed event carries deployment-event.v1 envelope")
    void runStateChangedEventUsesV1Envelope() {
        CallerContext caller = new CallerContext("user-real", "tenant-A",
                Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.UI);
        DeploymentEvent saved = service.recordRunStateChange(
                "dep-1", "run-1", "PENDING", "PREFLIGHT_PASSED",
                caller, "corr-1", "body-hash-1",
                Map.of("preflightStatus", "PASS"));
        assertEquals("deployment-event.v1", saved.getSchemaVersion());
        assertEquals("RUN_STATE_CHANGED", saved.getEventType());
        assertEquals("PENDING", saved.getFromStatus());
        assertEquals("PREFLIGHT_PASSED", saved.getToStatus());
        assertEquals("UI", saved.getActorType());
        assertEquals("user-real", saved.getActorId());
        assertEquals("UI", saved.getSurface());
        assertEquals("corr-1", saved.getCorrelationId());
        assertEquals("body-hash-1", saved.getRequestBodySha256());
        assertEquals("PASS", saved.getDetails().get("preflightStatus"));
    }

    @Test
    @DisplayName("Preflight outcome event maps PASS/FAIL to PREFLIGHT_PASSED/FAILED event types")
    void preflightOutcomeEventTypeReflectsResult() {
        PreflightCheckResult passing = PreflightCheckResult.of(
                "pkg-1", "tenant-A", "dev", "target-1",
                List.of(PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.PACKAGE_COMPLETED)),
                Instant.parse("2026-05-04T00:00:00Z"));
        DeploymentEvent passEvent = service.recordPreflightOutcome("dep-1", "run-1", passing,
                null, "corr-1", "h");
        assertEquals("PREFLIGHT_PASSED", passEvent.getEventType());

        PreflightCheckResult failing = PreflightCheckResult.of(
                "pkg-1", "tenant-A", "prod", "target-1",
                List.of(
                        PreflightCheckResult.CheckOutcome.fail(PreflightCheckCode.PACKAGE_COMPLETED, "bad")),
                Instant.parse("2026-05-04T00:00:00Z"));
        DeploymentEvent failEvent = service.recordPreflightOutcome("dep-1", "run-1", failing,
                null, "corr-1", "h");
        assertEquals("PREFLIGHT_FAILED", failEvent.getEventType());
    }

    @Test
    @DisplayName("preflight-result.json fixture matches the runtime envelope shape")
    void preflightResultFixtureMatchesRuntimeShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/deploy/preflight-result.json")) {
            assertNotNull(in, "preflight-result.json missing from test resources");
            JsonNode root = mapper.readTree(in);
            assertEquals("deployment-preflight-result.v1", root.path("schemaVersion").asText());
            // Every closed PreflightCheckCode appears as a check entry.
            JsonNode checks = root.path("checks");
            Set<String> seenCodes = new java.util.HashSet<>();
            for (JsonNode c : checks) seenCodes.add(c.path("code").asText());
            for (PreflightCheckCode code : PreflightCheckCode.values()) {
                assertTrue(seenCodes.contains(code.name()),
                        "fixture is missing check code: " + code);
            }
            assertTrue(root.path("blockers").isArray());
        }
    }

    @Test
    @DisplayName("deployment-event.json fixture conforms to deployment-event.v1 envelope")
    void deploymentEventFixtureConformsToEnvelope() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/deploy/deployment-event.json")) {
            assertNotNull(in, "deployment-event.json missing from test resources");
            JsonNode root = mapper.readTree(in);
            assertEquals("deployment-event.v1", root.path("schemaVersion").asText());
            for (String required : List.of(
                    "eventId", "eventType", "actorType", "actorId", "createdAt")) {
                assertTrue(root.has(required), "fixture missing required field: " + required);
            }
        }
    }

    @Test
    @DisplayName("deployment-evidence.json fixture conforms to deployment-evidence.v1 envelope")
    void deploymentEvidenceFixtureConformsToEnvelope() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/deploy/deployment-evidence.json")) {
            assertNotNull(in, "deployment-evidence.json missing from test resources");
            JsonNode root = mapper.readTree(in);
            assertEquals("deployment-evidence.v1", root.path("schemaVersion").asText());
            for (String required : List.of(
                    "artifactId", "type", "sha256", "producedBy", "createdAt")) {
                assertTrue(root.has(required), "fixture missing required field: " + required);
            }
        }
    }
}
