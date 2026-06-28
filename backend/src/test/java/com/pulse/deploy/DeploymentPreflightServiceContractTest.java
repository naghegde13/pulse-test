package com.pulse.deploy;

import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.preflight.DeploymentPreflightService;
import com.pulse.deploy.preflight.PreflightCheckCode;
import com.pulse.deploy.preflight.PreflightCheckResult;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.secret.service.CredentialReadinessService;
import com.pulse.storage.StorageBackendDeployGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4 contract — every closed {@link PreflightCheckCode} has a
 * deterministic pass and fail case, and the aggregate result obeys
 * the documented {@code deployment-preflight-result.v1} envelope.
 */
class DeploymentPreflightServiceContractTest {

    private PackageRepository packageRepo;
    private DeploymentTargetRepository targetRepo;
    private StorageBackendDeployGate storageGate;
    private CredentialReadinessService credentialReadinessService;
    private DeploymentPreflightService service;
    private com.pulse.git.repository.PullRequestRepository pullRequestRepository;
    private com.pulse.deploy.projection.service.RuntimeProjectionService runtimeProjectionService;

    @BeforeEach
    void setUp() {
        packageRepo = mock(PackageRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        storageGate = mock(StorageBackendDeployGate.class);
        credentialReadinessService = mock(CredentialReadinessService.class);
        pullRequestRepository = mock(com.pulse.git.repository.PullRequestRepository.class);
        runtimeProjectionService = mock(com.pulse.deploy.projection.service.RuntimeProjectionService.class);
        when(pullRequestRepository.findByVersionIdOrderByCreatedAtDesc(any()))
                .thenReturn(java.util.List.of());
        when(runtimeProjectionService.getActiveProjection(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        service = new DeploymentPreflightService(packageRepo, targetRepo,
                storageGate, credentialReadinessService,
                new com.pulse.git.policy.BranchAllowlistPolicy(),
                pullRequestRepository,
                mock(com.pulse.pipeline.repository.VersionAcceptanceRepository.class),
                new com.pulse.deploy.capability.RuntimeCapabilityMatrix(),
                com.pulse.runtime.TestRuntimeAuthorityFactory.gcpPulse(),
                mock(com.pulse.storage.contract.service.TableContractService.class),
                runtimeProjectionService,
                mock(com.pulse.deploy.runtime.AirflowRuntimeClient.class),
                mock(com.pulse.pipeline.service.OrchestrationNamespaceService.class));
    }

    @Test
    @DisplayName("A package with valid provenance + clean tree + happy storage/credentials passes preflight")
    void happyPathPasses() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        DeploymentTarget target = enabledTarget("target-1", "dev");
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(target));
        when(storageGate.check(any(), any())).thenReturn(
                new StorageBackendDeployGate.Result(true, List.of()));
        when(credentialReadinessService.compute(anyString(), anyString())).thenReturn(
                Map.of("ready", true, "connections", List.of()));

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.parse("2026-05-04T00:00:00Z"),
                callerForTest(),
                "corr-1");
        assertEquals(PreflightCheckResult.PASS, result.status());
        assertTrue(result.blockers().isEmpty(), () -> "expected no blockers, got: " + result.blockers());
        // Every closed blocker code appears exactly once.
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (var check : result.checks()) seen.add(check.code());
        for (PreflightCheckCode code : PreflightCheckCode.values()) {
            assertTrue(seen.contains(code.name()), "missing check: " + code);
        }
    }

    @Test
    @DisplayName("Closed blocker matrix contains exactly the callback-policy-expanded code set")
    void closedBlockerMatrixIsLocked() {
        Set<String> expected = Set.of(
                "PACKAGE_COMPLETED",
                "PACKAGE_PROVENANCE_PRESENT",
                "PACKAGE_CLEAN_FOR_ENV",
                "STATIC_DEPLOYABILITY",
                "AIRFLOW_CALLBACK_POLICY_VALID",
                "TARGET_EXISTS",
                "TARGET_ENABLED",
                "TARGET_SCHEMA_VALID",
                "STORAGE_BACKEND_VALIDATED",
                "CREDENTIAL_READINESS",
                "SECRET_REFERENCES_ONLY",
                "APPROVAL_POLICY",
                "GIT_BRANCH_ALLOWED",
                "PR_POLICY",
                "RUNTIME_CAPABILITY",
                // Phase 7 — actual matrix consult, not just a "profile recorded" check.
                "RUNTIME_CAPABILITY_OK",
                // ARCH-004 — persona/target legality.
                "RUNTIME_AUTHORITY_PERSONA_MATCH",
                "TARGET_TYPE_PERSONA_LEGAL",
                // ARCH-006 — table contract and projection readiness.
                "TABLE_CONTRACTS_PRESENT",
                "RUNTIME_PROJECTION_VALID",
                // ARCH-007 — active-run safety guard.
                "ACTIVE_RUN_SAFE",
                "AGENT_AUDIT_CONTEXT");
        Set<String> actual = new java.util.LinkedHashSet<>();
        for (PreflightCheckCode code : PreflightCheckCode.values()) actual.add(code.name());
        assertEquals(expected, actual,
                "PreflightCheckCode must contain exactly the callback-policy-expanded set");
    }

    @Test
    @DisplayName("Unknown targetType → TARGET_SCHEMA_VALID blocker")
    void unknownTargetTypeBlocks() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        DeploymentTarget target = enabledTarget("target-1", "dev");
        target.setTargetType("UNICORN-RUNTIME");
        wireHappyPath(pkg, target);

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.now(), callerForTest(), "corr-1");
        assertTrue(result.blockers().contains(PreflightCheckCode.TARGET_SCHEMA_VALID.name()));
    }

    @Test
    @DisplayName("Plaintext-looking secret in metadata → SECRET_REFERENCES_ONLY blocker")
    void plaintextSecretInMetadataBlocks() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        Map<String, Object> meta = new LinkedHashMap<>(pkg.getMetadata());
        meta.put("debugConfig", Map.of(
                "host", "db.example.com",
                "password", "plaintext-leak"));
        pkg.setMetadata(meta);
        DeploymentTarget target = enabledTarget("target-1", "dev");
        wireHappyPath(pkg, target);

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.now(), callerForTest(), "corr-1");
        assertTrue(result.blockers().contains(PreflightCheckCode.SECRET_REFERENCES_ONLY.name()),
                "Plaintext password in metadata must block; got blockers: " + result.blockers());
    }

    @Test
    @DisplayName("gcp-sm:// reference values do NOT trip SECRET_REFERENCES_ONLY")
    void gcpSmReferencesPass() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        Map<String, Object> meta = new LinkedHashMap<>(pkg.getMetadata());
        meta.put("debugConfig", Map.of(
                "host", "db.example.com",
                "password", "gcp-sm://projects/pulse-dev/secrets/x/versions/latest"));
        pkg.setMetadata(meta);
        DeploymentTarget target = enabledTarget("target-1", "dev");
        wireHappyPath(pkg, target);

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.now(), callerForTest(), "corr-1");
        assertFalse(result.blockers().contains(PreflightCheckCode.SECRET_REFERENCES_ONLY.name()));
    }

    @Test
    @DisplayName("Missing branch → GIT_BRANCH_ALLOWED blocker")
    void missingBranchBlocks() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> git = new LinkedHashMap<>((Map<String, Object>) pkg.getMetadata().get("git"));
        git.remove("branch");
        Map<String, Object> meta = new LinkedHashMap<>(pkg.getMetadata());
        meta.put("git", git);
        pkg.setMetadata(meta);
        DeploymentTarget target = enabledTarget("target-1", "dev");
        wireHappyPath(pkg, target);

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.now(), callerForTest(), "corr-1");
        assertTrue(result.blockers().contains(PreflightCheckCode.GIT_BRANCH_ALLOWED.name()));
    }

    @Test
    @DisplayName("Multi-blocker preflight produces a failure_reason that exceeds 64 chars (TEXT-required)")
    void multiBlockerPreflightExceedsLegacyVarcharLimit() {
        // Phase 4 closeout: deployment_runs.failure_reason was originally
        // VARCHAR(64). When preflight fails on multiple checks the
        // controller persists "preflight_failed: A,B,C,D,..." as the
        // failure reason — easy to overflow 64 chars with even three
        // codes. Migrating to TEXT is correct; this test pins the
        // intent so a future "shrink the column" change visibly
        // breaks here.
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        // Force five distinct blockers.
        pkg.setBuildStatus("FAILED");                        // PACKAGE_COMPLETED
        Map<String, Object> meta = new LinkedHashMap<>(pkg.getMetadata());
        meta.remove("git");                                  // PACKAGE_PROVENANCE_PRESENT + GIT_BRANCH_ALLOWED
        meta.put("staticRuntimeAssessment", Map.of(
                "verdict", "NOT_READY",
                "blockers", List.of("Missing Airflow DAG"),
                "warnings", List.of()));                     // STATIC_DEPLOYABILITY
        meta.put("debugConfig", Map.of("password", "plaintext-leak"));   // SECRET_REFERENCES_ONLY
        pkg.setMetadata(meta);
        DeploymentTarget target = enabledTarget("target-1", "prod");
        wireHappyPath(pkg, target);

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.now(), callerForTest(), "corr-1");
        assertTrue(result.blockers().size() >= 5,
                "Expected at least five distinct blockers, got: " + result.blockers());
        String failureReason = "preflight_failed: " + String.join(",", result.blockers());
        assertTrue(failureReason.length() > 64,
                "Failure reason must exceed 64 chars to validate the V104 column-width fix; got "
                        + failureReason.length() + " chars: " + failureReason);
    }

    @Test
    @DisplayName("Missing caller / correlation id → AGENT_AUDIT_CONTEXT blocker")
    void missingAuditContextBlocks() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        DeploymentTarget target = enabledTarget("target-1", "dev");
        wireHappyPath(pkg, target);

        // Caller resolver returns null → audit context fails.
        PreflightCheckResult resultNoCaller = service.check("pkg-1", "target-1",
                Instant.now(), null, "corr-1");
        assertTrue(resultNoCaller.blockers().contains(PreflightCheckCode.AGENT_AUDIT_CONTEXT.name()));

        // Caller present but correlation id blank → still fails.
        PreflightCheckResult resultNoCorr = service.check("pkg-1", "target-1",
                Instant.now(), callerForTest(), "");
        assertTrue(resultNoCorr.blockers().contains(PreflightCheckCode.AGENT_AUDIT_CONTEXT.name()));
    }

    @Test
    @DisplayName("Failed package build → PACKAGE_COMPLETED blocker")
    void packageNotCompleted_blocks() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        pkg.setBuildStatus("FAILED");
        DeploymentTarget target = enabledTarget("target-1", "dev");
        wireHappyPath(pkg, target);

        PreflightCheckResult result = service.check("pkg-1", "target-1", Instant.now());
        assertTrue(result.blockers().contains(PreflightCheckCode.PACKAGE_COMPLETED.name()));
    }

    @Test
    @DisplayName("Missing git block → PACKAGE_PROVENANCE_PRESENT blocker")
    void missingProvenance_blocks() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        // Strip the git block.
        Map<String, Object> meta = new LinkedHashMap<>(pkg.getMetadata());
        meta.remove("git");
        pkg.setMetadata(meta);
        DeploymentTarget target = enabledTarget("target-1", "dev");
        wireHappyPath(pkg, target);

        PreflightCheckResult result = service.check("pkg-1", "target-1", Instant.now());
        assertTrue(result.blockers().contains(PreflightCheckCode.PACKAGE_PROVENANCE_PRESENT.name()));
    }

    @Test
    @DisplayName("Dirty working tree blocks for prod, allows for dev")
    void dirtyTree_envScoped() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        Map<String, Object> meta = new LinkedHashMap<>(pkg.getMetadata());
        meta.put("workingTreeStatus", "dirty");
        Map<String, Object> git = new LinkedHashMap<>((Map<String, Object>) meta.get("git"));
        git.put("workingTreeStatus", "dirty");
        meta.put("git", git);
        pkg.setMetadata(meta);

        DeploymentTarget devTarget = enabledTarget("target-1", "dev");
        wireHappyPath(pkg, devTarget);
        PreflightCheckResult devResult = service.check("pkg-1", "target-1", Instant.now());
        assertFalse(devResult.blockers().contains(PreflightCheckCode.PACKAGE_CLEAN_FOR_ENV.name()),
                "dev tolerates dirty");

        DeploymentTarget prodTarget = enabledTarget("target-1", "prod");
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(prodTarget));
        PreflightCheckResult prodResult = service.check("pkg-1", "target-1", Instant.now());
        assertTrue(prodResult.blockers().contains(PreflightCheckCode.PACKAGE_CLEAN_FOR_ENV.name()),
                "prod must hard-block dirty");
    }

    @Test
    @DisplayName("Static-deployability blockers surface as STATIC_DEPLOYABILITY")
    void staticDeployability_blockers() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        Map<String, Object> meta = new LinkedHashMap<>(pkg.getMetadata());
        meta.put("staticRuntimeAssessment", Map.of(
                "verdict", "NOT_READY",
                "blockers", List.of("Missing Airflow DAG"),
                "warnings", List.of()));
        pkg.setMetadata(meta);
        DeploymentTarget target = enabledTarget("target-1", "dev");
        wireHappyPath(pkg, target);

        PreflightCheckResult result = service.check("pkg-1", "target-1", Instant.now());
        assertTrue(result.blockers().contains(PreflightCheckCode.STATIC_DEPLOYABILITY.name()));
    }

    @Test
    @DisplayName("Manifest callback diagnostics violations surface as AIRFLOW_CALLBACK_POLICY_VALID")
    void callbackPolicy_manifestViolationsBlock() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = new LinkedHashMap<>((Map<String, Object>) pkg.getMetadata().get("packageManifest"));
        manifest.put("callbackPolicyDiagnostics", Map.of(
                "promotedArtifactReady", false,
                "violations", List.of("dags/example.py contains PULSE_AIRFLOW_CALLBACK_URL")));
        Map<String, Object> meta = new LinkedHashMap<>(pkg.getMetadata());
        meta.put("packageManifest", manifest);
        pkg.setMetadata(meta);
        DeploymentTarget target = enabledTarget("target-1", "dev");
        wireHappyPath(pkg, target);

        PreflightCheckResult result = service.check("pkg-1", "target-1", Instant.now());
        assertTrue(result.blockers().contains(PreflightCheckCode.AIRFLOW_CALLBACK_POLICY_VALID.name()));
    }

    @Test
    @DisplayName("OPTIONAL callback policy blocks in promoted environments")
    void callbackPolicy_optionalBlocksInPromotedEnv() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = new LinkedHashMap<>((Map<String, Object>) pkg.getMetadata().get("packageManifest"));
        manifest.put("capabilityProfile", Map.of(
                "runtimeTargetTypes", List.of("LOCAL_MATERIALIZATION"),
                "controlPlaneDependency", "NONE",
                "airflowCallbackPolicy", "OPTIONAL"));
        Map<String, Object> meta = new LinkedHashMap<>(pkg.getMetadata());
        meta.put("packageManifest", manifest);
        pkg.setMetadata(meta);
        DeploymentTarget target = enabledTarget("target-1", "prod");
        wireHappyPath(pkg, target);

        PreflightCheckResult result = service.check("pkg-1", "target-1", Instant.now());
        assertTrue(result.blockers().contains(PreflightCheckCode.AIRFLOW_CALLBACK_POLICY_VALID.name()));
    }

    @Test
    @DisplayName("Missing target → TARGET_EXISTS + TARGET_ENABLED blockers")
    void missingTarget_blocks() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-missing")).thenReturn(Optional.empty());

        PreflightCheckResult result = service.check("pkg-1", "target-missing", Instant.now());
        assertTrue(result.blockers().contains(PreflightCheckCode.TARGET_EXISTS.name()));
        assertTrue(result.blockers().contains(PreflightCheckCode.TARGET_ENABLED.name()));
    }

    @Test
    @DisplayName("Disabled target → TARGET_ENABLED blocker only")
    void disabledTarget_blocks() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        DeploymentTarget target = enabledTarget("target-1", "dev");
        target.setEnabled(false);
        wireHappyPath(pkg, target);

        PreflightCheckResult result = service.check("pkg-1", "target-1", Instant.now());
        assertTrue(result.blockers().contains(PreflightCheckCode.TARGET_ENABLED.name()));
        assertFalse(result.blockers().contains(PreflightCheckCode.TARGET_EXISTS.name()));
    }

    @Test
    @DisplayName("Storage gate failure → STORAGE_BACKEND_VALIDATED blocker")
    void storageGateFailure_blocks() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        DeploymentTarget target = enabledTarget("target-1", "dev");
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(target));
        when(storageGate.check(any(), any())).thenReturn(
                new StorageBackendDeployGate.Result(false, List.of(
                        new StorageBackendDeployGate.Blocker(
                                "GCP", "dev", "pending", "Storage backend GCP for dev is pending."))));
        when(credentialReadinessService.compute(anyString(), anyString())).thenReturn(
                Map.of("ready", true, "connections", List.of()));

        PreflightCheckResult result = service.check("pkg-1", "target-1", Instant.now());
        assertTrue(result.blockers().contains(PreflightCheckCode.STORAGE_BACKEND_VALIDATED.name()));
    }

    @Test
    @DisplayName("Credential readiness ready=false → CREDENTIAL_READINESS blocker")
    void credentialReadinessFail_blocks() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        DeploymentTarget target = enabledTarget("target-1", "dev");
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(target));
        when(storageGate.check(any(), any())).thenReturn(
                new StorageBackendDeployGate.Result(true, List.of()));
        when(credentialReadinessService.compute(anyString(), anyString())).thenReturn(
                Map.of("ready", false, "connections", List.of()));

        PreflightCheckResult result = service.check("pkg-1", "target-1", Instant.now());
        assertTrue(result.blockers().contains(PreflightCheckCode.CREDENTIAL_READINESS.name()));
    }

    @Test
    @DisplayName("Result envelope matches the documented v1 schema")
    void resultEnvelopeUsesV1Schema() {
        Package pkg = cleanPackage("pkg-1", "tenant-A", "pipeline-1");
        DeploymentTarget target = enabledTarget("target-1", "dev");
        wireHappyPath(pkg, target);
        when(storageGate.check(any(), any())).thenReturn(
                new StorageBackendDeployGate.Result(true, List.of()));
        when(credentialReadinessService.compute(anyString(), anyString())).thenReturn(
                Map.of("ready", true, "connections", List.of()));

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.parse("2026-05-04T00:00:00Z"), callerForTest(), "corr-1");
        Map<String, Object> json = result.toCanonicalJson();
        assertEquals("deployment-preflight-result.v1", json.get("schemaVersion"));
        assertEquals("pkg-1", json.get("packageId"));
        assertEquals("dev", json.get("environment"));
        assertEquals("target-1", json.get("targetId"));
        assertEquals("PASS", json.get("status"));
        assertTrue(((List<?>) json.get("checks")).size() == PreflightCheckCode.values().length,
                "every closed blocker code must appear in the result");
    }

    // ------------------------------------------------------------------
    //  helpers
    // ------------------------------------------------------------------

    private void wireHappyPath(Package pkg, DeploymentTarget target) {
        when(packageRepo.findById(pkg.getId())).thenReturn(Optional.of(pkg));
        when(targetRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(storageGate.check(any(), any())).thenReturn(
                new StorageBackendDeployGate.Result(true, List.of()));
        when(credentialReadinessService.compute(anyString(), anyString())).thenReturn(
                Map.of("ready", true, "connections", List.of()));
    }

    private Package cleanPackage(String id, String tenantId, String pipelineId) {
        Package pkg = new Package();
        pkg.setId(id);
        pkg.setTenantId(tenantId);
        pkg.setPipelineId(pipelineId);
        pkg.setVersionId("v-1");
        pkg.setBuildStatus("COMPLETED");
        Map<String, Object> git = new LinkedHashMap<>();
        git.put("repoId", "repo-1");
        git.put("branch", "main");
        git.put("commitSha", "0".repeat(40));
        git.put("treeSha", "1".repeat(40));
        git.put("workingTreeStatus", "clean");
        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("verdict", "LIKELY_DEPLOYABLE");
        assessment.put("blockers", List.of());
        assessment.put("warnings", List.of());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", "deployment-package-manifest.v1");
        manifest.put("capabilityProfile", Map.of(
                "runtimeTargetTypes", List.of("LOCAL_MATERIALIZATION"),
                "controlPlaneDependency", "NONE",
                "airflowCallbackPolicy", "DISABLED"));
        manifest.put("callbackPolicyDiagnostics", Map.of(
                "promotedArtifactReady", true,
                "violations", List.of()));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("git", git);
        meta.put("workingTreeStatus", "clean");
        meta.put("staticRuntimeAssessment", assessment);
        meta.put("packageManifest", manifest);
        pkg.setMetadata(meta);
        return pkg;
    }

    private static com.pulse.auth.policy.CallerContext callerForTest() {
        return new com.pulse.auth.policy.CallerContext(
                "user-test", "tenant-A",
                java.util.Set.of(com.pulse.auth.policy.PulseRole.DEPLOYMENT_OPERATOR),
                com.pulse.auth.policy.CallerSurface.UI);
    }

    private DeploymentTarget enabledTarget(String id, String env) {
        DeploymentTarget t = new DeploymentTarget();
        t.setId(id);
        t.setTenantId("tenant-A");
        t.setName("Test " + env);
        t.setEnvironment(env);
        // Phase 7 — use canonical adapter key so the new
        // RUNTIME_CAPABILITY_OK check resolves cleanly. AIRFLOW (legacy)
        // is still accepted by TARGET_SCHEMA_VALID for backward compat,
        // but the capability matrix rejects unknown targetTypes.
        t.setTargetType("LOCAL_MATERIALIZATION");
        t.setEnabled(true);
        return t;
    }
}
