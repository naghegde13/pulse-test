package com.pulse.deploy;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PulseRole;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.repository.GenerationRunRepository;
import com.pulse.deploy.boundary.DeployBoundaryService;
import com.pulse.deploy.controller.DeployController;
import com.pulse.deploy.model.ApprovalRequest;
import com.pulse.deploy.model.Deployment;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.ApprovalRequestRepository;
import com.pulse.deploy.repository.DeploymentRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.deploy.service.PackageService;
import com.pulse.storage.StorageBackendDeployGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 enforcement contract — proves the deploy/package/approval/
 * target-config write paths actually invoke
 * {@link AuthorizationPolicyService} BEFORE any repository mutation,
 * and that denials surface as HTTP 403 with the policy reason as the
 * status reason.
 *
 * <p>Drives the {@link ActorResolverService} via a Mockito spy so each
 * test can replace the dev-stub PLATFORM_ADMIN context with a single
 * targeted role, without spinning up the full Spring context.
 */
class DeployControllerAuthorizationEnforcementTest {

    private PackageRepository packageRepo;
    private DeploymentRepository deployRepo;
    private DeploymentTargetRepository targetRepo;
    private ApprovalRequestRepository approvalRepo;
    private GenerationRunRepository runRepo;
    private GeneratedArtifactRepository artifactRepo;
    private StorageBackendDeployGate deployGate;
    private PackageService packageService;
    private AuthorizationPolicyService policy;
    private ActorResolverService actorResolver;
    // Phase 4 dependencies — mocked here because authorization-enforcement
    // tests deny BEFORE preflight runs; deeper Phase 4 contract coverage
    // lives in the dedicated Phase 4 tests.
    private com.pulse.deploy.preflight.DeploymentPreflightService preflightService;
    private com.pulse.deploy.repository.DeploymentRunRepository deploymentRunRepository;
    private com.pulse.deploy.evidence.DeploymentEvidenceService evidenceService;
    private com.pulse.deploy.evidence.RuntimeEvidenceService runtimeEvidenceService;
    private DeployController controller;

    @BeforeEach
    void setUp() {
        packageRepo = mock(PackageRepository.class);
        deployRepo = mock(DeploymentRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        approvalRepo = mock(ApprovalRequestRepository.class);
        runRepo = mock(GenerationRunRepository.class);
        artifactRepo = mock(GeneratedArtifactRepository.class);
        deployGate = mock(StorageBackendDeployGate.class);
        packageService = mock(PackageService.class);
        policy = new AuthorizationPolicyService();
        actorResolver = spy(new ActorResolverService());
        preflightService = mock(com.pulse.deploy.preflight.DeploymentPreflightService.class);
        deploymentRunRepository = mock(com.pulse.deploy.repository.DeploymentRunRepository.class);
        evidenceService = mock(com.pulse.deploy.evidence.DeploymentEvidenceService.class);
        // Default benign Phase 4 stubs so policy-allow paths reach
        // preflight without NPE.
        when(preflightService.check(any(), any(), any(), any(), any())).thenAnswer(inv ->
                com.pulse.deploy.preflight.PreflightCheckResult.of(
                        inv.getArgument(0), "tenant-A", "dev", inv.getArgument(1),
                        java.util.List.of(com.pulse.deploy.preflight.PreflightCheckResult.CheckOutcome
                                .pass(com.pulse.deploy.preflight.PreflightCheckCode.PACKAGE_COMPLETED)),
                        java.time.Instant.parse("2026-05-04T00:00:00Z")));
        when(deploymentRunRepository.save(any(com.pulse.deploy.model.DeploymentRun.class)))
                .thenAnswer(inv -> {
                    com.pulse.deploy.model.DeploymentRun r = inv.getArgument(0);
                    if (r.getId() == null) r.setId("run-stub");
                    return r;
                });
        when(evidenceService.sha256Json(any())).thenReturn("hash-stub");
        runtimeEvidenceService = mock(com.pulse.deploy.evidence.RuntimeEvidenceService.class);
        controller = new DeployController(
                packageRepo, deployRepo, targetRepo, approvalRepo,
                runRepo, artifactRepo, deployGate, packageService,
                policy, actorResolver,
                preflightService, deploymentRunRepository, evidenceService,
                mock(com.pulse.deploy.orchestrator.DeploymentRunOrchestrator.class),
                com.pulse.runtime.TestRuntimeAuthorityFactory.gcpPulse(),
                runtimeEvidenceService,
                mock(DeployBoundaryService.class));
    }

    private void asActor(Set<PulseRole> roles, String tenantId, CallerSurface surface) {
        when(actorResolver.resolve(surface, tenantId))
                .thenReturn(new CallerContext("user-test", tenantId, roles, surface));
    }

    @Test
    @DisplayName("PACKAGE_BUILD denied for TENANT_USER → 403 missing_role and no save")
    void packageBuildDeniedForTenantUser() {
        GenerationRun run = new GenerationRun();
        run.setId("run-1");
        run.setVersionId("version-1");
        run.setPipelineId("pipeline-1");
        run.setTenantId("tenant-A");
        when(runRepo.findTopByVersionIdOrderByCreatedAtDesc("version-1")).thenReturn(Optional.of(run));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-1"))
                .thenReturn(List.of(artifact("dags/x.py", "AIRFLOW_DAG", "{}", "h")));
        asActor(Set.of(PulseRole.TENANT_USER), "tenant-A", CallerSurface.UI);

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.buildPackage("version-1",
                        new DeployController.BuildRequest("pipeline-1", "tenant-A", "user-test", null)));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertTrue(denied.getReason().contains("missing_role"),
                "Expected missing_role reason, got: " + denied.getReason());
        verify(packageRepo, never()).save(any(Package.class));
    }

    @Test
    @DisplayName("PACKAGE_BUILD allowed for DEPLOYMENT_OPERATOR; ChainContext flows to PackageService")
    void packageBuildAllowedForDeploymentOperator() {
        GenerationRun run = new GenerationRun();
        run.setId("run-1");
        run.setVersionId("version-1");
        run.setPipelineId("pipeline-1");
        run.setTenantId("tenant-A");
        when(runRepo.findTopByVersionIdOrderByCreatedAtDesc("version-1")).thenReturn(Optional.of(run));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-1"))
                .thenReturn(List.of(artifact("dags/x.py", "AIRFLOW_DAG", "from airflow import DAG\n", "h"),
                                    artifact("requirements.txt", "REQUIREMENTS_TXT",
                                            "apache-airflow>=2.8.0\napache-airflow-providers-apache-spark>=4.7.0\ndbt-core>=1.7.0\ndbt-spark>=1.7.0\n", "h"),
                                    artifact("config/x.yml", "CONFIG_YAML", "p: x\n", "h"),
                                    artifact("manifests/compile-plan.json", "COMPILE_PLAN", "{}\n", "h")));
        when(packageRepo.save(any(Package.class))).thenAnswer(inv -> {
            Package p = inv.getArgument(0);
            if (p.getId() == null) p.setId("pkg-1");
            return p;
        });
        when(packageService.captureProvenance(any(), any())).thenReturn(
                new PackageService.PackageProvenance("v1", "repo", "tenant-A",
                        "main", "0".repeat(40), "1".repeat(40), "clean", 0, "now"));
        when(packageService.diagnostics(any())).thenReturn(Map.of());
        when(packageService.buildStaticAssessmentSeed(any())).thenReturn(List.of());
        when(packageService.buildManifest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of());
        when(packageService.computeManifestHash(any())).thenReturn("h");
        asActor(Set.of(PulseRole.DEPLOYMENT_OPERATOR), "tenant-A", CallerSurface.UI);

        controller.buildPackage("version-1",
                new DeployController.BuildRequest("pipeline-1", "tenant-A", "user-test", null));
        verify(packageRepo, times(2)).save(any(Package.class));
    }

    @Test
    @DisplayName("DEPLOY denied for PIPELINE_DEVELOPER → 403 missing_role and no deploy save")
    void deployDeniedForPipelineDeveloper() {
        // Post-PKT-FINAL-2: PULSE deploys to dev only. Use a dev target so the
        // dev-only gate passes and the test still exercises the role-based auth
        // gate (PIPELINE_DEVELOPER lacks DEPLOY:dev permission → missing_role).
        Package pkg = new Package();
        pkg.setId("pkg-1");
        pkg.setTenantId("tenant-A");
        pkg.setPipelineId("pipeline-1");
        pkg.setVersionId("version-1");
        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-1");
        target.setEnvironment("dev");
        target.setName("Dev Cluster");
        target.setTargetType("AIRFLOW");
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(target));
        asActor(Set.of(PulseRole.PIPELINE_DEVELOPER), "tenant-A", CallerSurface.UI);

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.deploy("pkg-1",
                        new DeployController.DeployRequest("target-1", "tenant-A", "user-test"), null, null));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertTrue(denied.getReason().contains("missing_role"));
        // Storage gate must NOT have been consulted before the policy denied.
        verify(deployGate, never()).check(any(), any());
        verify(deployRepo, never()).save(any(Deployment.class));
    }

    @Test
    @DisplayName("DEPLOY denied for PULL_REQUEST_APPROVER → 403 missing_role (case-insensitive env)")
    void deployEnvNotAllowedFor_NonOperatorRole() {
        // Post-PKT-FINAL-2: PULSE deploys to dev only — the env_not_allowed
        // distinction PKT-0003 originally encoded is no longer reachable
        // because non-dev targets short-circuit with the dev-only message
        // before the role gate sees them. We retain the role-gate proof by
        // using a dev target written in legacy uppercase, so we also retain
        // the case-insensitive env normalization coverage.
        Package pkg = new Package();
        pkg.setId("pkg-1");
        pkg.setTenantId("tenant-A");
        pkg.setPipelineId("pipeline-1");
        pkg.setVersionId("version-1");
        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-1");
        target.setEnvironment("DEV");  // legacy uppercase
        target.setName("Dev Cluster");
        target.setTargetType("AIRFLOW");
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(target));
        asActor(Set.of(PulseRole.PULL_REQUEST_APPROVER), "tenant-A", CallerSurface.UI);

        // PULL_REQUEST_APPROVER is not in the DEPLOY action's allowed roles.
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.deploy("pkg-1",
                        new DeployController.DeployRequest("target-1", "tenant-A", "user-test"), null, null));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("missing_role", denied.getReason());
    }

    @Test
    @DisplayName("APPROVE denied for DEPLOYMENT_OPERATOR → SoD between deploy and approve enforced")
    void approveDeniedForDeploymentOperator() {
        ApprovalRequest existing = new ApprovalRequest();
        existing.setId("approval-1");
        existing.setTenantId("tenant-A");
        existing.setStatus("PENDING");
        when(approvalRepo.findById("approval-1")).thenReturn(Optional.of(existing));
        asActor(Set.of(PulseRole.DEPLOYMENT_OPERATOR), "tenant-A", CallerSurface.UI);

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.decide("approval-1", Map.of(
                        "status", "APPROVED",
                        "decidedBy", "operator-1",
                        "reason", "self-approve attempt")));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("missing_role", denied.getReason());
        verify(approvalRepo, never()).save(any(ApprovalRequest.class));
    }

    @Test
    @DisplayName("TARGET_CONFIG denied for PIPELINE_DEVELOPER → 403 and no target save")
    void targetConfigDeniedForPipelineDeveloper() {
        asActor(Set.of(PulseRole.PIPELINE_DEVELOPER), "tenant-A", CallerSurface.UI);
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.createTarget("tenant-A",
                        new DeployController.CreateTargetReq("Dev Airflow", "dev", "AIRFLOW", null, null)));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("missing_role", denied.getReason());
        verify(targetRepo, never()).save(any(DeploymentTarget.class));
    }

    @Test
    @DisplayName("Cross-tenant deploy denied with tenant_membership reason")
    void crossTenantDeployDenied() {
        Package pkg = new Package();
        pkg.setId("pkg-1");
        pkg.setTenantId("tenant-A");
        pkg.setPipelineId("pipeline-1");
        pkg.setVersionId("version-1");
        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-1");
        target.setEnvironment("dev");
        target.setName("Dev Cluster");
        target.setTargetType("AIRFLOW");
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(target));
        // Caller scoped to tenant-B, package belongs to tenant-A — expect denial.
        when(actorResolver.resolve(CallerSurface.UI, "tenant-A"))
                .thenReturn(new CallerContext("user-test", "tenant-B",
                        Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.UI));

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.deploy("pkg-1",
                        new DeployController.DeployRequest("target-1", "tenant-A", "user-test"), null, null));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("tenant_membership", denied.getReason());
        verify(deployRepo, never()).save(any(Deployment.class));
    }

    // -------------------------------------------------------------------
    //  Phase 3 closeout — body actor fields are ignored; audit is
    //  attributed to the server-resolved CallerContext.userId().
    // -------------------------------------------------------------------

    @Test
    @DisplayName("buildPackage stamps Package.builtBy + manifest createdBy from resolved actor, not body")
    void packageBuildAuditAttributedToResolvedActor() {
        GenerationRun run = new GenerationRun();
        run.setId("run-1");
        run.setVersionId("version-1");
        run.setPipelineId("pipeline-1");
        run.setTenantId("tenant-A");
        when(runRepo.findTopByVersionIdOrderByCreatedAtDesc("version-1")).thenReturn(Optional.of(run));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-1"))
                .thenReturn(List.of(
                        artifact("dags/x.py", "AIRFLOW_DAG", "from airflow import DAG\n", "h"),
                        artifact("requirements.txt", "REQUIREMENTS_TXT",
                                "apache-airflow>=2.8.0\napache-airflow-providers-apache-spark>=4.7.0\ndbt-core>=1.7.0\ndbt-spark>=1.7.0\n", "h"),
                        artifact("config/x.yml", "CONFIG_YAML", "p: x\n", "h"),
                        artifact("manifests/compile-plan.json", "COMPILE_PLAN", "{}\n", "h")));
        when(packageRepo.save(any(Package.class))).thenAnswer(inv -> {
            Package p = inv.getArgument(0);
            if (p.getId() == null) p.setId("pkg-1");
            return p;
        });
        when(packageService.captureProvenance(any(), any())).thenReturn(
                new PackageService.PackageProvenance("v1", "repo", "tenant-A",
                        "main", "0".repeat(40), "1".repeat(40), "clean", 0, "now"));
        when(packageService.diagnostics(any())).thenReturn(Map.of());
        when(packageService.buildStaticAssessmentSeed(any())).thenReturn(List.of());
        when(packageService.computeManifestHash(any())).thenReturn("h");

        // Capture the createdBy that flows to the manifest builder.
        org.mockito.ArgumentCaptor<String> createdByCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(packageService.buildManifest(any(), any(), any(), any(), any(),
                createdByCaptor.capture(), any(), any(), any(), any()))
                .thenReturn(Map.of());

        // Resolved actor is "user-real"; request body claims "spoof-user".
        when(actorResolver.resolve(CallerSurface.UI, "tenant-A"))
                .thenReturn(new CallerContext("user-real", "tenant-A",
                        Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.UI));

        controller.buildPackage("version-1",
                new DeployController.BuildRequest("pipeline-1", "tenant-A", "spoof-user", null));

        org.mockito.ArgumentCaptor<Package> savedCaptor = org.mockito.ArgumentCaptor.forClass(Package.class);
        verify(packageRepo, times(2)).save(savedCaptor.capture());
        Package saved = savedCaptor.getAllValues().get(0);
        assertEquals("user-real", saved.getBuiltBy(),
                "Package.builtBy must come from CallerContext.userId(), not the request body");
        assertEquals("user-real", createdByCaptor.getValue(),
                "manifest createdBy must come from CallerContext.userId(), not the request body");
    }

    @Test
    @DisplayName("deploy stamps Deployment.deployedBy from resolved actor, not body")
    void deployAuditAttributedToResolvedActor() {
        Package pkg = new Package();
        pkg.setId("pkg-1");
        pkg.setTenantId("tenant-A");
        pkg.setPipelineId("pipeline-1");
        pkg.setVersionId("version-1");
        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-1");
        target.setEnvironment("dev");
        target.setName("Dev Cluster");
        target.setTargetType("AIRFLOW");
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(target));
        when(deployGate.check(any(), any())).thenReturn(
                new com.pulse.storage.StorageBackendDeployGate.Result(true, List.of()));
        when(deployRepo.save(any(Deployment.class))).thenAnswer(inv -> inv.getArgument(0));

        when(actorResolver.resolve(CallerSurface.UI, "tenant-A"))
                .thenReturn(new CallerContext("user-real", "tenant-A",
                        Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.UI));

        var response = controller.deploy("pkg-1",
                new DeployController.DeployRequest("target-1", "tenant-A", "spoof-user"), null, null);

        Deployment saved = response.getBody();
        assertEquals("user-real", saved.getDeployedBy(),
                "Deployment.deployedBy must come from CallerContext.userId(), not the request body");
    }

    @Test
    @DisplayName("decide stamps ApprovalRequest.approvedBy from resolved actor, not body decidedBy")
    void approveAuditAttributedToResolvedActor() {
        ApprovalRequest existing = new ApprovalRequest();
        existing.setId("approval-1");
        existing.setTenantId("tenant-A");
        existing.setStatus("PENDING");
        when(approvalRepo.findById("approval-1")).thenReturn(Optional.of(existing));
        when(approvalRepo.save(any(ApprovalRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(actorResolver.resolve(CallerSurface.UI, "tenant-A"))
                .thenReturn(new CallerContext("user-real", "tenant-A",
                        Set.of(PulseRole.PULL_REQUEST_APPROVER), CallerSurface.UI));

        var response = controller.decide("approval-1", Map.of(
                "status", "APPROVED",
                "decidedBy", "spoof-user",
                "reason", "looks good"));

        ApprovalRequest saved = response.getBody();
        assertEquals("user-real", saved.getApprovedBy(),
                "ApprovalRequest.approvedBy must come from CallerContext.userId(), not body.decidedBy");
        assertEquals("APPROVED", saved.getStatus());
        assertEquals("looks good", saved.getReason());
    }

    @Test
    @DisplayName("requestApproval stamps ApprovalRequest.requestedBy from resolved actor, not body")
    void requestApprovalAttributedToResolvedActor() {
        Deployment deployment = new Deployment();
        deployment.setId("dep-1");
        deployment.setTenantId("tenant-A");
        when(deployRepo.findById("dep-1")).thenReturn(Optional.of(deployment));
        when(approvalRepo.save(any(ApprovalRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(actorResolver.resolve(CallerSurface.UI, "tenant-A"))
                .thenReturn(new CallerContext("user-real", "tenant-A",
                        Set.of(PulseRole.PULL_REQUEST_APPROVER), CallerSurface.UI));

        var response = controller.requestApproval("dep-1",
                new DeployController.ApprovalReq("tenant-A", "spoof-user"));

        ApprovalRequest saved = response.getBody();
        assertEquals("user-real", saved.getRequestedBy(),
                "ApprovalRequest.requestedBy must come from CallerContext.userId(), not the request body");
    }

    // -------------------------------------------------------------------
    //  PKT-0005 — evidence readback authorization
    // -------------------------------------------------------------------

    private com.pulse.deploy.model.DeploymentRun wireEvidenceFixture(String tenantId, String env) {
        com.pulse.deploy.model.DeploymentRun run = new com.pulse.deploy.model.DeploymentRun();
        run.setId("run-evidence");
        run.setDeploymentId("dep-evidence");
        run.setTenantId(tenantId);
        run.setMetadata(Map.of("packageId", "pkg-1"));
        Deployment dep = new Deployment();
        dep.setId("dep-evidence");
        dep.setTargetId("target-evidence");
        dep.setTenantId(tenantId);
        DeploymentTarget tgt = new DeploymentTarget();
        tgt.setId("target-evidence");
        tgt.setEnvironment(env);
        tgt.setTargetType("AIRFLOW");
        when(deploymentRunRepository.findById("run-evidence")).thenReturn(Optional.of(run));
        when(deployRepo.findById("dep-evidence")).thenReturn(Optional.of(dep));
        when(targetRepo.findById("target-evidence")).thenReturn(Optional.of(tgt));
        // Stub the RuntimeEvidenceService to return a valid envelope
        when(runtimeEvidenceService.assembleForRun(any(), any(), any(), any(), any(), any()))
                .thenReturn(new com.pulse.deploy.evidence.RuntimeEvidenceEnvelope(
                        com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                        com.pulse.deploy.evidence.EvidenceProofLevel.PREFLIGHT,
                        com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.TYPE_PREFLIGHT,
                        "run-evidence", "pkg-1", tenantId, env, "AIRFLOW",
                        java.time.Instant.now(), "test", "corr-1",
                        false, false,
                        null, null, null, null, null, null, null,
                        java.util.List.of()));
        return run;
    }

    @Test
    @DisplayName("PKT-0005: evidence readback denied for TENANT_USER → 403 missing_role")
    void evidenceReadbackDeniedForTenantUser() {
        wireEvidenceFixture("tenant-A", "dev");
        asActor(Set.of(PulseRole.TENANT_USER), "tenant-A", CallerSurface.UI);

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.getRunEvidence("run-evidence"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("missing_role", denied.getReason());
    }

    @Test
    @DisplayName("PKT-0005: cross-tenant evidence readback denied → 403 tenant_membership")
    void crossTenantEvidenceReadbackDenied() {
        wireEvidenceFixture("tenant-A", "dev");
        // Caller belongs to tenant-B, run belongs to tenant-A.
        when(actorResolver.resolve(CallerSurface.UI, "tenant-A"))
                .thenReturn(new CallerContext("user-test", "tenant-B",
                        Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.UI));

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> controller.getRunEvidence("run-evidence"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("tenant_membership", denied.getReason());
    }

    @Test
    @DisplayName("PKT-0005: same-tenant DEPLOYMENT_OPERATOR can read evidence → 200")
    void sameTenantDeploymentOperatorCanReadEvidence() {
        wireEvidenceFixture("tenant-A", "dev");
        asActor(Set.of(PulseRole.DEPLOYMENT_OPERATOR), "tenant-A", CallerSurface.UI);

        var response = controller.getRunEvidence("run-evidence");
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        // Envelope must include proofLevel and runtimeProof fields
        assertTrue(body.containsKey("proofLevel"), "Envelope must have proofLevel");
        assertTrue(body.containsKey("runtimeProof"), "Envelope must have runtimeProof");
    }

    private static GeneratedArtifact artifact(String path, String type, String content, String hash) {
        GeneratedArtifact a = new GeneratedArtifact();
        a.setFilePath(path);
        a.setFileType(type);
        a.setContent(content);
        a.setContentHash(hash);
        return a;
    }
}
