package com.pulse.deploy;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.deploy.controller.DeployController;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.repository.ApprovalRequestRepository;
import com.pulse.deploy.repository.DeploymentRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.deploy.service.PackageService;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.repository.GenerationRunRepository;
import com.pulse.deploy.boundary.DeployBoundaryService;
import com.pulse.deploy.evidence.DeploymentEvidenceService;
import com.pulse.deploy.orchestrator.DeploymentRunOrchestrator;
import com.pulse.deploy.preflight.DeploymentPreflightService;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.runtime.TestRuntimeAuthorityFactory;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.runtime.service.RuntimeAuthorityService.RuntimeAuthorityViolationException;
import com.pulse.storage.StorageBackendDeployGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ARCH-004 Phase 8 — Runtime authority enforcement at the
 * {@link DeployController#createTarget} boundary.
 *
 * <p>Proves that persona-illegal target types are rejected before
 * persistence, that persona-legal target types succeed, and that
 * LOCAL_MATERIALIZATION is allowed for both personas.
 */
class DeployControllerRuntimeAuthorityTest {

    private DeploymentTargetRepository targetRepo;
    private DeployController gcpController;
    private DeployController dpcController;

    @BeforeEach
    void setUp() {
        PackageRepository packageRepo = mock(PackageRepository.class);
        DeploymentRepository deployRepo = mock(DeploymentRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        ApprovalRequestRepository approvalRepo = mock(ApprovalRequestRepository.class);
        GenerationRunRepository runRepo = mock(GenerationRunRepository.class);
        GeneratedArtifactRepository artifactRepo = mock(GeneratedArtifactRepository.class);
        StorageBackendDeployGate deployGate = mock(StorageBackendDeployGate.class);
        PackageService packageService = mock(PackageService.class);
        AuthorizationPolicyService policy = new AuthorizationPolicyService();
        ActorResolverService actorResolver = new ActorResolverService();
        DeploymentPreflightService preflightService = mock(DeploymentPreflightService.class);
        DeploymentRunRepository deploymentRunRepo = mock(DeploymentRunRepository.class);
        DeploymentEvidenceService evidenceService = mock(DeploymentEvidenceService.class);
        DeploymentRunOrchestrator orchestrator = mock(DeploymentRunOrchestrator.class);

        // Default stub: targetRepo.save returns its argument with an id.
        when(targetRepo.save(any(DeploymentTarget.class))).thenAnswer(inv -> {
            DeploymentTarget t = inv.getArgument(0);
            if (t.getId() == null) t.setId("target-stub");
            return t;
        });

        RuntimeAuthorityService gcpAuthority = TestRuntimeAuthorityFactory.gcpPulse();
        RuntimeAuthorityService dpcAuthority = TestRuntimeAuthorityFactory.dpcPulse();

        com.pulse.deploy.evidence.RuntimeEvidenceService runtimeEvidenceService =
                mock(com.pulse.deploy.evidence.RuntimeEvidenceService.class);

        gcpController = new DeployController(
                packageRepo, deployRepo, targetRepo, approvalRepo,
                runRepo, artifactRepo, deployGate, packageService,
                policy, actorResolver,
                preflightService, deploymentRunRepo, evidenceService,
                orchestrator, gcpAuthority, runtimeEvidenceService,
                mock(DeployBoundaryService.class));

        dpcController = new DeployController(
                packageRepo, deployRepo, targetRepo, approvalRepo,
                runRepo, artifactRepo, deployGate, packageService,
                policy, actorResolver,
                preflightService, deploymentRunRepo, evidenceService,
                orchestrator, dpcAuthority, runtimeEvidenceService,
                mock(DeployBoundaryService.class));
    }

    @Test
    @DisplayName("GCP_PULSE rejects DPC_AIRFLOW_OPENSHIFT_SPARK target type")
    void gcpRejectsDpcTarget() {
        assertThrows(RuntimeAuthorityViolationException.class,
                () -> gcpController.createTarget("tenant-A",
                        new DeployController.CreateTargetReq(
                                "DPC Cluster", "integration",
                                "DPC_AIRFLOW_OPENSHIFT_SPARK", null, null)));

        verify(targetRepo, never()).save(any(DeploymentTarget.class));
    }

    @Test
    @DisplayName("GCP_PULSE allows GCP_COMPOSER_DATAPROC target type")
    void gcpAllowsGcpTarget() {
        var response = gcpController.createTarget("tenant-A",
                new DeployController.CreateTargetReq(
                        "GCP Cluster", "integration",
                        "GCP_COMPOSER_DATAPROC", "https://composer.example.com", new HashMap<>()));

        assertNotNull(response.getBody());
        assertEquals("GCP_COMPOSER_DATAPROC", response.getBody().getTargetType());
        verify(targetRepo).save(any(DeploymentTarget.class));
    }

    @Test
    @DisplayName("DPC_PULSE rejects GCP_COMPOSER_DATAPROC target type")
    void dpcRejectsGcpTarget() {
        assertThrows(RuntimeAuthorityViolationException.class,
                () -> dpcController.createTarget("tenant-A",
                        new DeployController.CreateTargetReq(
                                "GCP Cluster", "integration",
                                "GCP_COMPOSER_DATAPROC", null, null)));

        verify(targetRepo, never()).save(any(DeploymentTarget.class));
    }

    @Test
    @DisplayName("DPC_PULSE allows DPC_AIRFLOW_OPENSHIFT_SPARK target type")
    void dpcAllowsDpcTarget() {
        var response = dpcController.createTarget("tenant-A",
                new DeployController.CreateTargetReq(
                        "DPC Cluster", "integration",
                        "DPC_AIRFLOW_OPENSHIFT_SPARK", "https://airflow.example.com", new HashMap<>()));

        assertNotNull(response.getBody());
        assertEquals("DPC_AIRFLOW_OPENSHIFT_SPARK", response.getBody().getTargetType());
        verify(targetRepo).save(any(DeploymentTarget.class));
    }

    @Test
    @DisplayName("LOCAL_MATERIALIZATION is allowed for GCP_PULSE persona")
    void localMaterializationAllowedForGcp() {
        var response = gcpController.createTarget("tenant-A",
                new DeployController.CreateTargetReq(
                        "Local Dev", "dev",
                        "LOCAL_MATERIALIZATION", null, null));

        assertNotNull(response.getBody());
        assertEquals("LOCAL_MATERIALIZATION", response.getBody().getTargetType());
    }

    @Test
    @DisplayName("LOCAL_MATERIALIZATION is allowed for DPC_PULSE persona")
    void localMaterializationAllowedForDpc() {
        var response = dpcController.createTarget("tenant-A",
                new DeployController.CreateTargetReq(
                        "Local Dev", "dev",
                        "LOCAL_MATERIALIZATION", null, null));

        assertNotNull(response.getBody());
        assertEquals("LOCAL_MATERIALIZATION", response.getBody().getTargetType());
    }
}
