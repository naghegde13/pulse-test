package com.pulse.deploy;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.repository.GenerationRunRepository;
import com.pulse.deploy.controller.DeployController;
import com.pulse.deploy.boundary.DeployBoundaryService;
import com.pulse.deploy.evidence.DeploymentEvidenceService;
import com.pulse.deploy.model.Deployment;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.preflight.DeploymentPreflightService;
import com.pulse.deploy.preflight.PreflightCheckCode;
import com.pulse.deploy.preflight.PreflightCheckResult;
import com.pulse.deploy.repository.ApprovalRequestRepository;
import com.pulse.deploy.repository.DeploymentRepository;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.deploy.run.DeploymentRunState;
import com.pulse.deploy.service.PackageService;
import com.pulse.storage.StorageBackendDeployGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4 contract — idempotency.
 *
 * <p>{@code Idempotency-Key} header replays:
 * <ul>
 *   <li>same key + same body hash → reuse the prior {@link DeploymentRun};</li>
 *   <li>same key + different body hash → {@code 409 idempotency_body_mismatch};</li>
 *   <li>no key → always create a fresh run.</li>
 * </ul>
 */
class DeploymentWriteIdempotencyContractTest {

    private PackageRepository packageRepo;
    private DeploymentRepository deployRepo;
    private DeploymentTargetRepository targetRepo;
    private DeploymentRunRepository deploymentRunRepository;
    private DeploymentPreflightService preflightService;
    private DeploymentEvidenceService evidenceService;
    private DeployController controller;

    @BeforeEach
    void setUp() {
        packageRepo = mock(PackageRepository.class);
        deployRepo = mock(DeploymentRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        deploymentRunRepository = mock(DeploymentRunRepository.class);
        preflightService = mock(DeploymentPreflightService.class);
        evidenceService = mock(DeploymentEvidenceService.class);
        // Wire the same body-hash function the controller relies on so
        // the test can compute matching/mismatching hashes deterministically.
        when(evidenceService.sha256Json(any())).thenAnswer(inv ->
                new DeploymentEvidenceService(
                        mock(com.pulse.deploy.repository.DeploymentEvidenceRepository.class),
                        mock(com.pulse.deploy.repository.DeploymentEventRepository.class))
                        .sha256Json(inv.getArgument(0)));
        when(deploymentRunRepository.save(any(DeploymentRun.class))).thenAnswer(inv -> {
            DeploymentRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId("run-" + System.nanoTime());
            return r;
        });
        when(preflightService.check(any(), any(), any(), any(), any())).thenAnswer(inv ->
                PreflightCheckResult.of(inv.getArgument(0), "tenant-A", "dev", inv.getArgument(1),
                        List.of(PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.PACKAGE_COMPLETED)),
                        Instant.parse("2026-05-04T00:00:00Z")));
        controller = new DeployController(
                packageRepo, deployRepo, targetRepo,
                mock(ApprovalRequestRepository.class),
                mock(GenerationRunRepository.class),
                mock(GeneratedArtifactRepository.class),
                mock(StorageBackendDeployGate.class),
                mock(PackageService.class),
                new AuthorizationPolicyService(),
                new ActorResolverService(),
                preflightService, deploymentRunRepository, evidenceService,
                mock(com.pulse.deploy.orchestrator.DeploymentRunOrchestrator.class),
                com.pulse.runtime.TestRuntimeAuthorityFactory.gcpPulse(),
                mock(com.pulse.deploy.evidence.RuntimeEvidenceService.class),
                mock(DeployBoundaryService.class));
    }

    @Test
    @DisplayName("Same Idempotency-Key + same body hash returns the prior run")
    void sameKeySameBodyReturnsPriorRun() {
        wireDeployFixture("pkg-1", "target-1", "tenant-A", "dev");
        Deployment existingDeployment = new Deployment();
        existingDeployment.setId("dep-1");
        existingDeployment.setPackageId("pkg-1");
        existingDeployment.setTargetId("target-1");
        existingDeployment.setTenantId("tenant-A");
        existingDeployment.setStatus("RUNNING");
        when(deployRepo.findByPipelineIdOrderByCreatedAtDesc(any()))
                .thenReturn(List.of(existingDeployment));

        // Wire a prior run keyed by the test's idempotency key + matching body hash.
        DeploymentRun prior = new DeploymentRun();
        prior.setId("run-prior");
        prior.setDeploymentId("dep-1");
        prior.setIdempotencyKey("key-1");
        // Body hash for (packageId=pkg-1, targetId=target-1, environment=dev).
        String matchingBodyHash = computeDeployBodyHash("pkg-1", "target-1", "dev",
                "NONE", false, null);
        prior.setRequestBodySha256(matchingBodyHash);
        when(deploymentRunRepository.findByDeploymentIdAndIdempotencyKey("dep-1", "key-1"))
                .thenReturn(Optional.of(prior));

        var response = controller.deploy("pkg-1",
                new DeployController.DeployRequest("target-1", "tenant-A", "u"),
                "key-1", null);
        assertEquals(200, response.getStatusCode().value());
        // No new run created on replay.
        verify(deploymentRunRepository, never()).save(any(DeploymentRun.class));
        assertEquals("run-prior",
                response.getBody().getMetadata().get("deploymentRunId"),
                "Replay must surface the prior run id, not a fresh one");
        assertEquals(true, response.getBody().getMetadata().get("idempotentReplay"));
    }

    @Test
    @DisplayName("Same Idempotency-Key + different body hash → 409 idempotency_body_mismatch")
    void sameKeyDifferentBodyConflicts() {
        wireDeployFixture("pkg-1", "target-1", "tenant-A", "dev");
        Deployment existingDeployment = new Deployment();
        existingDeployment.setId("dep-1");
        existingDeployment.setPackageId("pkg-1");
        existingDeployment.setTargetId("target-1");
        existingDeployment.setTenantId("tenant-A");
        when(deployRepo.findByPipelineIdOrderByCreatedAtDesc(any()))
                .thenReturn(List.of(existingDeployment));

        DeploymentRun prior = new DeploymentRun();
        prior.setId("run-prior");
        prior.setDeploymentId("dep-1");
        prior.setIdempotencyKey("key-1");
        prior.setRequestBodySha256("DEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEF");
        when(deploymentRunRepository.findByDeploymentIdAndIdempotencyKey("dep-1", "key-1"))
                .thenReturn(Optional.of(prior));

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.deploy("pkg-1",
                        new DeployController.DeployRequest("target-1", "tenant-A", "u"),
                        "key-1", null));
        assertEquals(HttpStatus.CONFLICT, denied.getStatusCode());
        assertEquals("idempotency_body_mismatch", denied.getReason());
        verify(deploymentRunRepository, never()).save(any(DeploymentRun.class));
    }

    @Test
    @DisplayName("No Idempotency-Key → always creates a new run")
    void noKeyAlwaysCreatesNewRun() {
        wireDeployFixture("pkg-1", "target-1", "tenant-A", "dev");
        when(deployRepo.findByPipelineIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(deployRepo.save(any(Deployment.class))).thenAnswer(inv -> {
            Deployment d = inv.getArgument(0);
            if (d.getId() == null) d.setId("dep-fresh");
            return d;
        });

        var response = controller.deploy("pkg-1",
                new DeployController.DeployRequest("target-1", "tenant-A", "u"),
                null, null);
        assertEquals(200, response.getStatusCode().value());
        verify(deploymentRunRepository, times(1)).save(any(DeploymentRun.class));
        assertNotNull(response.getBody().getMetadata().get("deploymentRunId"));
    }

    @Test
    @DisplayName("Idempotency body hash is computed deterministically over (packageId, targetId, env)")
    void bodyHashIsDeterministic() {
        // Two calls with the same effective body produce the same hash;
        // a different env yields a different hash.
        String hashA = computeDeployBodyHash("pkg-1", "target-1", "dev",
                "NONE", false, null);
        String hashB = computeDeployBodyHash("pkg-1", "target-1", "dev",
                "NONE", false, null);
        String hashC = computeDeployBodyHash("pkg-1", "target-1", "prod",
                "NONE", false, null);
        assertEquals(hashA, hashB);
        assertTrue(!hashA.equals(hashC), "different env must produce a different body hash");
    }

    @Test
    @DisplayName("Validation intent participates in the idempotency body hash")
    void validationIntentChangesBodyHash() {
        String normal = computeDeployBodyHash("pkg-1", "target-1", "dev",
                "NONE", false, null);
        String smoke = computeDeployBodyHash("pkg-1", "target-1", "dev",
                "SMOKE", true, "validation-conf-hash");
        assertTrue(!normal.equals(smoke),
                "SMOKE validation intent must not collide with normal deploy idempotency");
    }

    // ----------------------------------------------------------------------

    private void wireDeployFixture(String packageId, String targetId, String tenantId, String env) {
        Package pkg = new Package();
        pkg.setId(packageId);
        pkg.setTenantId(tenantId);
        pkg.setPipelineId("pipeline-1");
        pkg.setVersionId("v-1");
        when(packageRepo.findById(packageId)).thenReturn(Optional.of(pkg));
        DeploymentTarget target = new DeploymentTarget();
        target.setId(targetId);
        target.setTenantId(tenantId);
        target.setName("Test " + env);
        target.setEnvironment(env);
        target.setTargetType("AIRFLOW");
        target.setEnabled(true);
        when(targetRepo.findById(targetId)).thenReturn(Optional.of(target));
    }

    private static String computeDeployBodyHash(String packageId,
                                                String targetId,
                                                String env,
                                                String validationMode,
                                                boolean awaitValidation,
                                                String validationConfHash) {
        // Mirrors the controller's own canonicalization logic exactly.
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("packageId", packageId);
        m.put("targetId", targetId);
        m.put("environment", env);
        m.put("validationMode", validationMode);
        m.put("awaitValidation", awaitValidation);
        m.put("validationConfHash", validationConfHash);
        return new DeploymentEvidenceService(
                mock(com.pulse.deploy.repository.DeploymentEvidenceRepository.class),
                mock(com.pulse.deploy.repository.DeploymentEventRepository.class))
                .sha256Json(m);
    }
}
