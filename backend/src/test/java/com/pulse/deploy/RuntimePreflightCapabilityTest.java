package com.pulse.deploy;

import com.pulse.deploy.capability.RuntimeCapabilityMatrix;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.preflight.DeploymentPreflightService;
import com.pulse.deploy.preflight.PreflightCheckCode;
import com.pulse.deploy.preflight.PreflightCheckResult;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.git.policy.BranchAllowlistPolicy;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7 — RUNTIME_CAPABILITY_OK preflight blocker.
 *
 * <p>Pins the integration between {@link RuntimeCapabilityMatrix} and
 * {@link DeploymentPreflightService}: an unapproved or fallback-required
 * matrix outcome blocks deploy with the new {@code RUNTIME_CAPABILITY_OK}
 * code, while an approved matrix outcome lands as a PASS.
 */
class RuntimePreflightCapabilityTest {

    private PackageRepository packageRepo;
    private DeploymentTargetRepository targetRepo;
    private StorageBackendDeployGate storageGate;
    private CredentialReadinessService credentialReadinessService;
    private com.pulse.git.repository.PullRequestRepository pullRequestRepository;
    private DeploymentPreflightService service;

    @BeforeEach
    void setUp() {
        packageRepo = mock(PackageRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        storageGate = mock(StorageBackendDeployGate.class);
        credentialReadinessService = mock(CredentialReadinessService.class);
        pullRequestRepository = mock(com.pulse.git.repository.PullRequestRepository.class);
        when(pullRequestRepository.findByVersionIdOrderByCreatedAtDesc(any()))
                .thenReturn(List.of());
        when(storageGate.check(any(), any())).thenReturn(
                new StorageBackendDeployGate.Result(true, List.of()));
        when(credentialReadinessService.compute(anyString(), anyString())).thenReturn(
                Map.of("ready", true, "connections", List.of()));
        service = new DeploymentPreflightService(packageRepo, targetRepo,
                storageGate, credentialReadinessService,
                new BranchAllowlistPolicy(), pullRequestRepository,
                mock(com.pulse.pipeline.repository.VersionAcceptanceRepository.class),
                new RuntimeCapabilityMatrix(),
                com.pulse.runtime.TestRuntimeAuthorityFactory.gcpPulse(),
                mock(com.pulse.storage.contract.service.TableContractService.class),
                mock(com.pulse.deploy.projection.service.RuntimeProjectionService.class),
                mock(com.pulse.deploy.runtime.AirflowRuntimeClient.class),
                mock(com.pulse.pipeline.service.OrchestrationNamespaceService.class));
    }

    @Test
    @DisplayName("LOCAL target with PARQUET package: RUNTIME_CAPABILITY_OK passes")
    void localApproves() {
        wirePackage("pkg-1", "tenant-A", "pipeline-1", "PARQUET");
        wireTarget("target-1", "dev", RuntimeCapabilityMatrix.LOCAL, false);

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.now(), null, "corr");
        assertFalse(result.blockers().contains(PreflightCheckCode.RUNTIME_CAPABILITY_OK.name()));
    }

    @Test
    @DisplayName("DPC target with ICEBERG and dpcIcebergSupported=false: RUNTIME_CAPABILITY_OK blocks (fallback required)")
    void dpcIcebergWithoutHintBlocks() {
        wirePackage("pkg-1", "tenant-A", "pipeline-1", "ICEBERG");
        wireTarget("target-1", "prod", RuntimeCapabilityMatrix.DPC, false);

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.now(), null, "corr");
        assertTrue(result.blockers().contains(PreflightCheckCode.RUNTIME_CAPABILITY_OK.name()),
                "DPC + ICEBERG without platform hint must block; got blockers: " + result.blockers());
    }

    @Test
    @DisplayName("DPC target with ICEBERG and dpcIcebergSupported=true: RUNTIME_CAPABILITY_OK passes")
    void dpcIcebergWithHintPasses() {
        wirePackage("pkg-1", "tenant-A", "pipeline-1", "ICEBERG");
        wireTarget("target-1", "prod", RuntimeCapabilityMatrix.DPC, true);

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.now(), null, "corr");
        assertFalse(result.blockers().contains(PreflightCheckCode.RUNTIME_CAPABILITY_OK.name()),
                "DPC + ICEBERG with platform hint must pass; got blockers: " + result.blockers());
    }

    @Test
    @DisplayName("DPC target with DELTA: RUNTIME_CAPABILITY_OK blocks (rejected outright)")
    void dpcDeltaBlocks() {
        wirePackage("pkg-1", "tenant-A", "pipeline-1", "DELTA");
        wireTarget("target-1", "prod", RuntimeCapabilityMatrix.DPC, true);

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.now(), null, "corr");
        assertTrue(result.blockers().contains(PreflightCheckCode.RUNTIME_CAPABILITY_OK.name()));
    }

    @Test
    @DisplayName("GCP target with ICEBERG: RUNTIME_CAPABILITY_OK passes (matrix v1 trusts GCP)")
    void gcpIcebergPasses() {
        wirePackage("pkg-1", "tenant-A", "pipeline-1", "ICEBERG");
        wireTarget("target-1", "prod", RuntimeCapabilityMatrix.GCP, false);

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.now(), null, "corr");
        assertFalse(result.blockers().contains(PreflightCheckCode.RUNTIME_CAPABILITY_OK.name()));
    }

    @Test
    @DisplayName("Missing target: RUNTIME_CAPABILITY_OK fails (cannot consult matrix)")
    void missingTargetFails() {
        wirePackage("pkg-1", "tenant-A", "pipeline-1", "PARQUET");
        when(targetRepo.findById("target-missing")).thenReturn(Optional.empty());

        PreflightCheckResult result = service.check("pkg-1", "target-missing",
                Instant.now(), null, "corr");
        assertTrue(result.blockers().contains(PreflightCheckCode.RUNTIME_CAPABILITY_OK.name()));
    }

    // ------------------------------------------------------------------

    private void wirePackage(String packageId, String tenantId, String pipelineId, String tableFormat) {
        Package pkg = new Package();
        pkg.setId(packageId);
        pkg.setTenantId(tenantId);
        pkg.setPipelineId(pipelineId);
        pkg.setVersionId("v-1");
        pkg.setBuildStatus("COMPLETED");
        Map<String, Object> meta = new LinkedHashMap<>();
        Map<String, Object> git = new LinkedHashMap<>();
        git.put("repoId", "repo-1");
        git.put("branch", "main");
        git.put("commitSha", "0".repeat(40));
        git.put("treeSha", "1".repeat(40));
        git.put("workingTreeStatus", "clean");
        meta.put("git", git);
        meta.put("workingTreeStatus", "clean");
        meta.put("staticRuntimeAssessment", Map.of(
                "verdict", "LIKELY_DEPLOYABLE",
                "blockers", List.of(),
                "warnings", List.of()));
        meta.put("requestedTableFormat", tableFormat);
        pkg.setMetadata(meta);
        when(packageRepo.findById(packageId)).thenReturn(Optional.of(pkg));
    }

    private void wireTarget(String id, String env, String targetType, boolean dpcIcebergSupported) {
        DeploymentTarget t = new DeploymentTarget();
        t.setId(id);
        t.setTenantId("tenant-A");
        t.setName("Target " + env);
        t.setEnvironment(env);
        t.setTargetType(targetType);
        t.setEnabled(true);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dpcIcebergSupported", dpcIcebergSupported);
        t.setConfig(config);
        when(targetRepo.findById(id)).thenReturn(Optional.of(t));
    }
}
