package com.pulse.deploy.controller;

import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.repository.GenerationRunRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Deployment;
import com.pulse.deploy.model.ApprovalRequest;
import com.pulse.deploy.repository.ApprovalRequestRepository;
import com.pulse.deploy.repository.DeploymentRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeployControllerTest {

    @Mock private PackageRepository packageRepo;
    @Mock private DeploymentRepository deployRepo;
    @Mock private DeploymentTargetRepository targetRepo;
    @Mock private ApprovalRequestRepository approvalRepo;
    @Mock private GenerationRunRepository generationRunRepository;
    @Mock private GeneratedArtifactRepository generatedArtifactRepository;
    @Mock private com.pulse.storage.StorageBackendDeployGate deployGate;
    // Phase 2: PackageService is concrete (no interface) so Mockito mocks
    // it explicitly rather than trying to construct a real instance via
    // @InjectMocks. The deeper provenance behavior is exercised by
    // PackageProvenanceContractTest with a real LocalGitService + JGit.
    @Mock private com.pulse.deploy.service.PackageService packageService;
    // Phase 3: real policy + actor resolver — the dev-stub default in
    // ActorResolverService allows everything (PLATFORM_ADMIN), which
    // preserves the pre-Phase-3 mocked behavior. Dedicated denial tests
    // live in DeployControllerAuthorizationEnforcementTest.
    @org.mockito.Spy private com.pulse.auth.policy.AuthorizationPolicyService authPolicy =
            new com.pulse.auth.policy.AuthorizationPolicyService();
    @org.mockito.Spy private com.pulse.auth.policy.ActorResolverService actorResolver =
            new com.pulse.auth.policy.ActorResolverService();
    // Phase 4 wiring — benign defaults stubbed in @BeforeEach.
    @Mock private com.pulse.deploy.preflight.DeploymentPreflightService preflightService;
    @Mock private com.pulse.deploy.repository.DeploymentRunRepository deploymentRunRepository;
    @Mock private com.pulse.deploy.evidence.DeploymentEvidenceService evidenceService;
    @Mock private com.pulse.deploy.orchestrator.DeploymentRunOrchestrator orchestrator;
    @Mock private com.pulse.runtime.service.RuntimeAuthorityService runtimeAuthorityService;

    private final AtomicReference<com.pulse.deploy.model.DeploymentRun> lastSavedRun = new AtomicReference<>();

    @InjectMocks
    private DeployController controller;

    @BeforeEach
    void stubPackageServiceWithCleanProvenance() {
        // Default benign stubs so existing buildPackage tests don't NPE on
        // the new PackageService delegation. Tests that need a different
        // provenance path override these stubs locally.
        com.pulse.deploy.service.PackageService.PackageProvenance clean =
                new com.pulse.deploy.service.PackageService.PackageProvenance(
                        "deployment-git-provenance.v1",
                        "git-repo-stub", "tenant-stub", "main",
                        "0".repeat(40), "1".repeat(40),
                        "clean", 0, "2026-05-04T00:00:00Z");
        when(packageService.captureProvenance(any(), any())).thenReturn(clean);
        when(packageService.diagnostics(any())).thenReturn(java.util.Map.of(
                "workingTreeStatus", "clean",
                "dirtyFileCount", 0,
                "complete", true));
        when(packageService.buildStaticAssessmentSeed(any())).thenReturn(java.util.List.of());
        when(packageService.buildManifest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(java.util.Map.of("schemaVersion", "deployment-package-manifest.v1"));
        when(packageService.computeManifestHash(any())).thenReturn("manifest-hash-stub");
        // Phase 4 default stubs: preflight passes, run row gets an id,
        // evidenceService is benign. Tests that want preflight failure
        // override locally.
        when(preflightService.check(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            String pkgId = inv.getArgument(0);
            String tgtId = inv.getArgument(1);
            return com.pulse.deploy.preflight.PreflightCheckResult.of(
                    pkgId, "tenant-stub", "dev", tgtId,
                    java.util.List.of(com.pulse.deploy.preflight.PreflightCheckResult.CheckOutcome
                            .pass(com.pulse.deploy.preflight.PreflightCheckCode.PACKAGE_COMPLETED)),
                    java.time.Instant.parse("2026-05-04T00:00:00Z"));
        });
        when(deploymentRunRepository.save(any(com.pulse.deploy.model.DeploymentRun.class)))
                .thenAnswer(inv -> {
                    com.pulse.deploy.model.DeploymentRun r = inv.getArgument(0);
                    if (r.getId() == null) r.setId("run-stub");
                    lastSavedRun.set(r);
                    return r;
                });
        when(deploymentRunRepository.findById(any())).thenAnswer(inv -> {
            com.pulse.deploy.model.DeploymentRun run = lastSavedRun.get();
            if (run != null && run.getId().equals(inv.getArgument(0))) {
                return Optional.of(run);
            }
            return Optional.empty();
        });
        when(orchestrator.runToTerminal(any(), any()))
                .thenAnswer(inv -> {
                    com.pulse.deploy.model.DeploymentRun run = lastSavedRun.get();
                    if (run != null) {
                        Map<String, Object> meta = run.getMetadata() == null
                                ? new java.util.LinkedHashMap<>()
                                : new java.util.LinkedHashMap<>(run.getMetadata());
                        meta.putIfAbsent("activationProviderRunId", "composer-sync-" + run.getId());
                        meta.putIfAbsent("validationStatus", Boolean.TRUE.equals(meta.get("validationRequested"))
                                ? "SUCCEEDED" : "NOT_REQUESTED");
                        run.setMetadata(meta);
                        run.setStatus(com.pulse.deploy.run.DeploymentRunState.SUCCEEDED.name());
                    }
                    return com.pulse.deploy.run.DeploymentRunState.SUCCEEDED;
                });
        when(orchestrator.runThroughSubmit(any(), any()))
                .thenAnswer(inv -> {
                    com.pulse.deploy.model.DeploymentRun run = lastSavedRun.get();
                    if (run != null) {
                        Map<String, Object> meta = run.getMetadata() == null
                                ? new java.util.LinkedHashMap<>()
                                : new java.util.LinkedHashMap<>(run.getMetadata());
                        meta.put("activationProviderRunId", "composer-sync-" + run.getId());
                        meta.put("validationDagRunId", "validation-" + run.getId());
                        meta.put("validationStatus", "TRIGGERED");
                        run.setMetadata(meta);
                        run.setStatus(com.pulse.deploy.run.DeploymentRunState.RUNNING.name());
                    }
                    return com.pulse.deploy.adapter.AdapterExecution.success(
                            "SUBMIT",
                            com.pulse.deploy.run.DeploymentRunState.RUNNING,
                            "composer-sync-run-stub",
                            Map.of(
                                    "activationProviderRunId", "composer-sync-run-stub",
                                    "validationDagRunId", "validation-run-stub",
                                    "validationStatus", "TRIGGERED"
                            ));
                });
        when(evidenceService.sha256Json(any())).thenReturn("body-hash-stub");
    }

    @Test
    void buildPackage_usesLatestGenerationArtifactsForMetadata() {
        GenerationRun run = new GenerationRun();
        run.setId("run-1");
        run.setVersionId("version-1");
        run.setMetadata(Map.of("compile_namespace", "domains/hr/pipelines/employees"));

        GeneratedArtifact dag = artifact("domains/hr/pipelines/employees/dags/hr.py", "AIRFLOW_DAG", "hash-dag");
        dag.setContent("from airflow import DAG\n");
        GeneratedArtifact requirements = artifact("domains/hr/pipelines/employees/requirements.txt", "REQUIREMENTS_TXT", "hash-req");
        requirements.setContent("""
                apache-airflow>=2.8.0
                apache-airflow-providers-apache-spark>=4.7.0
                apache-airflow-providers-amazon>=8.0.0
                apache-airflow-providers-sftp>=5.0.0
                apache-airflow-providers-common-sql>=1.14.0
                dbt-core>=1.7.0
                dbt-spark>=1.7.0
                """);
        GeneratedArtifact config = artifact("domains/hr/pipelines/employees/config/hr.yml", "CONFIG_YAML", "hash-config");
        config.setContent("pipeline: hr\n");
        GeneratedArtifact selector = artifact("domains/hr/pipelines/employees/dbt/selectors/gold.yml", "DBT_SELECTOR", "hash-selector");
        GeneratedArtifact manifest = artifact("domains/hr/pipelines/employees/manifests/compile-plan.json", "COMPILE_PLAN", "hash-compile");
        GeneratedArtifact publish = artifact("domains/hr/pipelines/employees/manifests/gold-publish-contract.json", "GOLD_PUBLISH_CONTRACT", "hash-publish");

        when(generationRunRepository.findTopByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(Optional.of(run));
        when(generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc("run-1"))
                .thenReturn(List.of(dag, requirements, config, selector, manifest, publish));
        when(packageRepo.save(any(Package.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new DeployController.BuildRequest("pipeline-1", "tenant-1", "user-1", null);
        ResponseEntity<Package> response = controller.buildPackage("version-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("ARTIFACT_BUNDLE", response.getBody().getPackageType());
        assertTrue(response.getBody().getArtifactUrl().contains("domains/hr/pipelines/employees/version-1.tar.gz"));
        assertNotNull(response.getBody().getArtifactHash());
        assertEquals("run-1", response.getBody().getMetadata().get("generationRunId"));
        assertEquals(true, response.getBody().getMetadata().get("hasGoldPublishBoundary"));
        @SuppressWarnings("unchecked")
        Map<String, Object> assessment = (Map<String, Object>) response.getBody().getMetadata().get("staticRuntimeAssessment");
        assertNotNull(assessment);
        assertEquals("LIKELY_DEPLOYABLE", assessment.get("verdict"));
    }

    @Test
    void buildPackage_withoutGenerationRun_throwsNotFound() {
        when(generationRunRepository.findTopByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(Optional.empty());

        var request = new DeployController.BuildRequest("pipeline-1", "tenant-1", "user-1", null);
        assertThrows(ResourceNotFoundException.class, () -> controller.buildPackage("version-1", request));
    }

    @Test
    void buildPackage_reportsWarningsWhenArtifactsContainTodos() {
        GenerationRun run = new GenerationRun();
        run.setId("run-1");
        run.setVersionId("version-1");
        run.setMetadata(Map.of("compile_namespace", "domains/hr/pipelines/employees"));

        GeneratedArtifact dag = artifact("domains/hr/pipelines/employees/dags/hr.py", "AIRFLOW_DAG", "hash-dag");
        dag.setContent("from airflow import DAG\n# TODO add retries\n");
        GeneratedArtifact requirements = artifact("domains/hr/pipelines/employees/requirements.txt", "REQUIREMENTS_TXT", "hash-req");
        requirements.setContent("""
                apache-airflow>=2.8.0
                apache-airflow-providers-apache-spark>=4.7.0
                dbt-core>=1.7.0
                dbt-spark>=1.7.0
                """);
        GeneratedArtifact config = artifact("domains/hr/pipelines/employees/config/hr.yml", "CONFIG_YAML", "hash-config");
        GeneratedArtifact compilePlan = artifact("domains/hr/pipelines/employees/manifests/compile-plan.json", "COMPILE_PLAN", "hash-plan");

        when(generationRunRepository.findTopByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(Optional.of(run));
        when(generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc("run-1"))
                .thenReturn(List.of(dag, requirements, config, compilePlan));
        when(packageRepo.save(any(Package.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new DeployController.BuildRequest("pipeline-1", "tenant-1", "user-1", null);
        ResponseEntity<Package> response = controller.buildPackage("version-1", request);

        @SuppressWarnings("unchecked")
        Map<String, Object> assessment = (Map<String, Object>) response.getBody().getMetadata().get("staticRuntimeAssessment");
        assertEquals("DEPLOYABLE_WITH_WARNINGS", assessment.get("verdict"));
        assertEquals(1, assessment.get("todoCount"));
    }

    @Test
    void createTarget_normalizesLegacyEnvironmentValuesToCanonicalLowercase() {
        when(targetRepo.save(any(DeploymentTarget.class))).thenAnswer(inv -> inv.getArgument(0));

        // Phase 1: legacy uppercase inputs (PROD, INT, PRODUCTION, ...) must
        // normalize to the canonical lowercase keys at the API boundary so
        // every persisted deployment_targets.environment row uses one of
        // local|dev|integration|uat|prod.
        var prodReq = new DeployController.CreateTargetReq(
                "Prod Airflow", "PROD", "AIRFLOW",
                "https://airflow.example.com", null);
        assertEquals("prod",
                controller.createTarget("tenant-1", prodReq).getBody().getEnvironment());

        var productionReq = new DeployController.CreateTargetReq(
                "Prod Airflow", "PRODUCTION", "AIRFLOW", null, null);
        assertEquals("prod",
                controller.createTarget("tenant-1", productionReq).getBody().getEnvironment());

        var intReq = new DeployController.CreateTargetReq(
                "Int Cluster", "INT", "AIRFLOW", null, null);
        assertEquals("integration",
                controller.createTarget("tenant-1", intReq).getBody().getEnvironment());

        var integrationReq = new DeployController.CreateTargetReq(
                "Int Cluster", "INTEGRATION", "AIRFLOW", null, null);
        assertEquals("integration",
                controller.createTarget("tenant-1", integrationReq).getBody().getEnvironment());

        var uatReq = new DeployController.CreateTargetReq(
                "UAT Airflow", "UAT", "AIRFLOW", null, null);
        assertEquals("uat",
                controller.createTarget("tenant-1", uatReq).getBody().getEnvironment());

        var devReq = new DeployController.CreateTargetReq(
                "Dev Cluster", "DEV", "AIRFLOW", null, null);
        assertEquals("dev",
                controller.createTarget("tenant-1", devReq).getBody().getEnvironment());

        // Already-canonical inputs must round-trip unchanged.
        var canonicalProd = new DeployController.CreateTargetReq(
                "Prod Airflow", "prod", "AIRFLOW", null, null);
        assertEquals("prod",
                controller.createTarget("tenant-1", canonicalProd).getBody().getEnvironment());

        var localReq = new DeployController.CreateTargetReq(
                "Local Dev", "local", "AIRFLOW", null, null);
        assertEquals("local",
                controller.createTarget("tenant-1", localReq).getBody().getEnvironment());

        // Null/blank inputs default to canonical 'dev' (preserves historical
        // controller behavior).
        var blankReq = new DeployController.CreateTargetReq(
                "Default", "", "AIRFLOW", null, null);
        assertEquals("dev",
                controller.createTarget("tenant-1", blankReq).getBody().getEnvironment());
    }

    @Test
    void createTarget_rejectsUnknownEnvironment() {
        // Unknown env strings must surface as IllegalArgumentException at the
        // controller boundary so they never reach persistence.
        var badReq = new DeployController.CreateTargetReq(
                "Bad", "STAGING", "AIRFLOW", null, null);
        var ex = assertThrows(IllegalArgumentException.class,
                () -> controller.createTarget("tenant-1", badReq));
        assertTrue(ex.getMessage().contains("Unknown deployment environment"),
                "Expected unknown-env error, got: " + ex.getMessage());
    }

    @Test
    void deploy_usesAuthoritativePackageAndTargetMetadata() {
        // Post-PKT-FINAL-2: PULSE deploys to dev only. The original test
        // used "PRODUCTION" target env to also exercise canonicalization,
        // but non-dev targets now short-circuit before metadata is written.
        // Use legacy-uppercase "DEV" so both intents are preserved: the
        // package-vs-request-body authority check AND the case-insensitive
        // canonicalization to "dev".
        Package pkg = new Package();
        pkg.setId("pkg-1");
        pkg.setPipelineId("pipeline-1");
        pkg.setVersionId("version-1");
        pkg.setTenantId("tenant-real");

        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-1");
        target.setTenantId("tenant-real");
        target.setName("Dev Airflow");
        target.setEnvironment("DEV");           // legacy uppercase
        target.setTargetType("AIRFLOW");

        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(target));
        when(deployRepo.save(any(Deployment.class))).thenAnswer(inv -> inv.getArgument(0));
        // #30 P10 deploy gate: assume validated for this test (storage-gate
        // behavior is covered by StorageBackendDeployGateTest).
        when(deployGate.check(any(), any()))
                .thenReturn(new com.pulse.storage.StorageBackendDeployGate.Result(true, List.of()));

        var request = new DeployController.DeployRequest("target-1", "tenant-spoofed", "user-1");
        ResponseEntity<Deployment> response = controller.deploy("pkg-1", request, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("tenant-real", response.getBody().getTenantId());
        assertEquals("dev", response.getBody().getMetadata().get("targetEnvironment"));
        assertEquals("NONE", response.getBody().getMetadata().get("validationMode"));
        assertEquals(false, response.getBody().getMetadata().get("validationRequested"));
        assertEquals("NOT_REQUESTED", response.getBody().getMetadata().get("validationStatus"));
        assertTrue(response.getBody().getDeployLog().contains("Dev Airflow"));
        assertTrue(response.getBody().getDeployLog().contains("dev"),
                "Deploy log should reference the canonical env, not the legacy uppercase form");
    }

    @Test
    void deploy_persistsCanonicalEnvironmentInDeploymentMetadata() {
        // Post-PKT-FINAL-2: PULSE deploys to dev only. The pre-merge version
        // of this test asserted "INT" -> "integration" canonicalization, but
        // non-dev targets are now categorically forbidden (403 dev-only).
        // We preserve the canonicalization-coverage intent by asserting
        // legacy uppercase "DEV" -> canonical "dev" instead. Non-dev
        // canonicalization is unreachable through this controller; the
        // pure normalizeEnvironment() helper still covers those cases
        // separately if needed.
        Package pkg = new Package();
        pkg.setId("pkg-2");
        pkg.setPipelineId("pipeline-2");
        pkg.setVersionId("version-2");
        pkg.setTenantId("tenant-real");

        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-2");
        target.setTenantId("tenant-real");
        target.setName("Dev Airflow");
        target.setEnvironment("DEV");          // legacy uppercase
        target.setTargetType("AIRFLOW");

        when(packageRepo.findById("pkg-2")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-2")).thenReturn(Optional.of(target));
        when(deployRepo.save(any(Deployment.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Deployment> response = controller.deploy(
                "pkg-2",
                new DeployController.DeployRequest("target-2", "tenant-real", "user-1"),
                null, null);
        assertEquals("dev",
                response.getBody().getMetadata().get("targetEnvironment"),
                "Deploy controller must canonicalize 'DEV' -> 'dev' before persistence");
    }

    @Test
    void deploy_rejectsValidationConfWithoutSmokeMode() {
        Package pkg = new Package();
        pkg.setId("pkg-3");
        pkg.setPipelineId("pipeline-3");
        pkg.setVersionId("version-3");
        pkg.setTenantId("tenant-real");

        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-3");
        target.setTenantId("tenant-real");
        target.setName("Dev Airflow");
        target.setEnvironment("dev");
        target.setTargetType("AIRFLOW");

        when(packageRepo.findById("pkg-3")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-3")).thenReturn(Optional.of(target));

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.deploy(
                        "pkg-3",
                        new DeployController.DeployRequest(
                                "target-3",
                                "tenant-real",
                                "user-1",
                                DeployController.ValidationMode.NONE,
                                false,
                                Map.of("sample", "value")),
                        null,
                        null));
        assertEquals(400, ex.getStatusCode().value());
        assertEquals("validationConf requires validationMode=SMOKE", ex.getReason());
    }

    @Test
    void deploy_asyncSmokeValidationPersistsExplicitMetadata() {
        Package pkg = new Package();
        pkg.setId("pkg-4");
        pkg.setPipelineId("pipeline-4");
        pkg.setVersionId("version-4");
        pkg.setTenantId("tenant-real");

        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-4");
        target.setTenantId("tenant-real");
        target.setName("Dev Airflow");
        target.setEnvironment("dev");
        target.setTargetType("AIRFLOW");

        when(packageRepo.findById("pkg-4")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-4")).thenReturn(Optional.of(target));
        when(deployRepo.save(any(Deployment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceService.sha256Json(Map.of("sample", "value"))).thenReturn("validation-conf-hash");

        ResponseEntity<Deployment> response = controller.deploy(
                "pkg-4",
                new DeployController.DeployRequest(
                        "target-4",
                        "tenant-real",
                        "user-1",
                        DeployController.ValidationMode.SMOKE,
                        false,
                        Map.of("sample", "value")),
                null,
                null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("ACTIVE", response.getBody().getStatus());
        assertEquals("SMOKE", response.getBody().getMetadata().get("validationMode"));
        assertEquals(true, response.getBody().getMetadata().get("validationRequested"));
        assertEquals(false, response.getBody().getMetadata().get("awaitValidation"));
        assertEquals("TRIGGERED", response.getBody().getMetadata().get("validationStatus"));
        assertEquals("validation-conf-hash", response.getBody().getMetadata().get("validationConfHash"));
        assertEquals("validation-run-stub", response.getBody().getMetadata().get("validationDagRunId"));
    }

    @Test
    void requestApproval_usesAuthoritativeDeploymentTenant() {
        Deployment deployment = new Deployment();
        deployment.setId("dep-1");
        deployment.setTenantId("tenant-real");

        when(deployRepo.findById("dep-1")).thenReturn(Optional.of(deployment));
        when(approvalRepo.save(any(ApprovalRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new DeployController.ApprovalReq("tenant-spoofed", "approver-1");
        ResponseEntity<ApprovalRequest> response = controller.requestApproval("dep-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("tenant-real", response.getBody().getTenantId());
        // Phase 3 closeout: requestedBy comes from the resolved
        // CallerContext, which in this no-HTTP-context test is the
        // ActorResolverService dev stub user. The body's "approver-1"
        // claim is ignored.
        assertEquals(com.pulse.auth.policy.ActorResolverService.DEV_STUB_USER_ID,
                response.getBody().getRequestedBy());
        assertNotNull(response.getBody().getExpiresAt());
    }

    @Test
    void listApprovals_returnsDeploymentApprovals() {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId("approval-1");
        approval.setDeploymentId("dep-1");
        approval.setStatus("PENDING");
        when(approvalRepo.findByDeploymentIdOrderByCreatedAtDesc("dep-1"))
                .thenReturn(List.of(approval));

        ResponseEntity<List<ApprovalRequest>> response = controller.listApprovals("dep-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("approval-1", response.getBody().get(0).getId());
    }

    @Test
    void decide_updatesApprovalRequest() {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId("approval-1");
        approval.setStatus("PENDING");
        when(approvalRepo.findById("approval-1")).thenReturn(Optional.of(approval));
        when(approvalRepo.save(any(ApprovalRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<ApprovalRequest> response = controller.decide("approval-1", Map.of(
                "status", "APPROVED",
                "decidedBy", "reviewer-1",
                "reason", "Looks good"
        ));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("APPROVED", response.getBody().getStatus());
        // Phase 3 closeout: approvedBy comes from the resolved
        // CallerContext, not body.decidedBy. In this no-HTTP-context
        // test the resolver returns the dev stub user.
        assertEquals(com.pulse.auth.policy.ActorResolverService.DEV_STUB_USER_ID,
                response.getBody().getApprovedBy());
        assertEquals("Looks good", response.getBody().getReason());
        assertNotNull(response.getBody().getDecidedAt());
    }

    private GeneratedArtifact artifact(String path, String type, String hash) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setFilePath(path);
        artifact.setFileType(type);
        artifact.setContentHash(hash);
        artifact.setContent("{}");
        return artifact;
    }
}
