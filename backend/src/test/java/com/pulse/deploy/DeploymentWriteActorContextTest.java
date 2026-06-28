package com.pulse.deploy;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PulseRole;
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
import com.pulse.deploy.service.PackageService;
import com.pulse.storage.StorageBackendDeployGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4 contract — every deployment write derives actor identity
 * from {@link com.pulse.auth.policy.CallerContext}, never from request
 * bodies. Mirrors the Phase 3 spoof tests but specifically targets
 * the {@code DeploymentRun.initiated_by} + event {@code actor_id}
 * paths that Phase 4 introduced.
 */
class DeploymentWriteActorContextTest {

    private PackageRepository packageRepo;
    private DeploymentRepository deployRepo;
    private DeploymentTargetRepository targetRepo;
    private DeploymentRunRepository deploymentRunRepository;
    private DeploymentPreflightService preflightService;
    private DeploymentEvidenceService evidenceService;
    private ActorResolverService actorResolver;
    private DeployController controller;

    @BeforeEach
    void setUp() {
        packageRepo = mock(PackageRepository.class);
        deployRepo = mock(DeploymentRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        deploymentRunRepository = mock(DeploymentRunRepository.class);
        preflightService = mock(DeploymentPreflightService.class);
        evidenceService = mock(DeploymentEvidenceService.class);
        actorResolver = spy(new ActorResolverService());
        when(deploymentRunRepository.save(any(DeploymentRun.class))).thenAnswer(inv -> {
            DeploymentRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId("run-stub");
            return r;
        });
        when(deployRepo.save(any(Deployment.class))).thenAnswer(inv -> {
            Deployment d = inv.getArgument(0);
            if (d.getId() == null) d.setId("dep-stub");
            return d;
        });
        when(deployRepo.findByPipelineIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(preflightService.check(any(), any(), any(), any(), any())).thenAnswer(inv ->
                PreflightCheckResult.of(inv.getArgument(0), "tenant-A", "dev", inv.getArgument(1),
                        List.of(PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.PACKAGE_COMPLETED)),
                        Instant.parse("2026-05-04T00:00:00Z")));
        when(evidenceService.sha256Json(any())).thenReturn("body-hash-fixed");
        controller = new DeployController(
                packageRepo, deployRepo, targetRepo,
                mock(ApprovalRequestRepository.class),
                mock(GenerationRunRepository.class),
                mock(GeneratedArtifactRepository.class),
                mock(StorageBackendDeployGate.class),
                mock(PackageService.class),
                new AuthorizationPolicyService(),
                actorResolver,
                preflightService, deploymentRunRepository, evidenceService,
                mock(com.pulse.deploy.orchestrator.DeploymentRunOrchestrator.class),
                com.pulse.runtime.TestRuntimeAuthorityFactory.gcpPulse(),
                mock(com.pulse.deploy.evidence.RuntimeEvidenceService.class),
                mock(DeployBoundaryService.class));
    }

    @Test
    @DisplayName("DeploymentRun.initiatedBy is the resolved CallerContext.userId(), not the request body")
    void runInitiatedByComesFromResolvedActor() {
        wireFixture();
        when(actorResolver.resolve(CallerSurface.UI, "tenant-A")).thenReturn(
                new CallerContext("user-real", "tenant-A",
                        Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.UI));

        controller.deploy("pkg-1",
                new DeployController.DeployRequest("target-1", "tenant-A", "spoof-user"),
                null, null);

        org.mockito.ArgumentCaptor<DeploymentRun> runCaptor =
                org.mockito.ArgumentCaptor.forClass(DeploymentRun.class);
        verify(deploymentRunRepository).save(runCaptor.capture());
        assertEquals("user-real", runCaptor.getValue().getInitiatedBy(),
                "DeploymentRun.initiatedBy must come from the resolved actor, not the body");
    }

    @Test
    @DisplayName("Evidence + event payloads are stamped with the resolved CallerContext")
    void evidenceAndEventReceiveResolvedCallerContext() {
        wireFixture();
        CallerContext expected = new CallerContext("user-real", "tenant-A",
                Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.UI);
        when(actorResolver.resolve(CallerSurface.UI, "tenant-A")).thenReturn(expected);

        controller.deploy("pkg-1",
                new DeployController.DeployRequest("target-1", "tenant-A", "spoof-user"),
                null, null);

        org.mockito.ArgumentCaptor<CallerContext> ctxCaptor =
                org.mockito.ArgumentCaptor.forClass(CallerContext.class);
        verify(evidenceService).recordPreflightOutcome(any(), any(), any(),
                ctxCaptor.capture(), any(), any());
        assertEquals("user-real", ctxCaptor.getValue().userId());
        assertEquals(CallerSurface.UI, ctxCaptor.getValue().surface());
    }

    private void wireFixture() {
        Package pkg = new Package();
        pkg.setId("pkg-1");
        pkg.setTenantId("tenant-A");
        pkg.setPipelineId("pipeline-1");
        pkg.setVersionId("v-1");
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));
        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-1");
        target.setTenantId("tenant-A");
        target.setName("Dev");
        target.setEnvironment("dev");
        target.setTargetType("AIRFLOW");
        target.setEnabled(true);
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(target));
    }
}
