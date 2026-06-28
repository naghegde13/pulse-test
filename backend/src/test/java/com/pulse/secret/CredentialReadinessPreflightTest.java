package com.pulse.secret;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4 — credential readiness contributes to deploy preflight.
 *
 * <p>Pins:
 * <ul>
 *   <li>{@link CredentialReadinessService} is consulted with the
 *       canonical env at preflight time.</li>
 *   <li>{@code ready=true} → {@link PreflightCheckCode#CREDENTIAL_READINESS}
 *       passes.</li>
 *   <li>{@code ready=false} → that check is the only credential-related
 *       blocker.</li>
 *   <li>Service-thrown exceptions surface as the same blocker, never
 *       crash preflight.</li>
 * </ul>
 */
class CredentialReadinessPreflightTest {

    private PackageRepository packageRepo;
    private DeploymentTargetRepository targetRepo;
    private StorageBackendDeployGate storageGate;
    private CredentialReadinessService credentialReadinessService;
    private DeploymentPreflightService service;

    @BeforeEach
    void setUp() {
        packageRepo = mock(PackageRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        storageGate = mock(StorageBackendDeployGate.class);
        credentialReadinessService = mock(CredentialReadinessService.class);
        com.pulse.git.repository.PullRequestRepository prRepo =
                mock(com.pulse.git.repository.PullRequestRepository.class);
        when(prRepo.findByVersionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        service = new DeploymentPreflightService(packageRepo, targetRepo,
                storageGate, credentialReadinessService,
                new com.pulse.git.policy.BranchAllowlistPolicy(), prRepo,
                mock(com.pulse.pipeline.repository.VersionAcceptanceRepository.class),
                new com.pulse.deploy.capability.RuntimeCapabilityMatrix(),
                com.pulse.runtime.TestRuntimeAuthorityFactory.gcpPulse(),
                mock(com.pulse.storage.contract.service.TableContractService.class),
                mock(com.pulse.deploy.projection.service.RuntimeProjectionService.class),
                mock(com.pulse.deploy.runtime.AirflowRuntimeClient.class),
                mock(com.pulse.pipeline.service.OrchestrationNamespaceService.class));
        // Default: storage gate happy.
        when(storageGate.check(any(), any())).thenReturn(
                new StorageBackendDeployGate.Result(true, List.of()));
    }

    @Test
    @DisplayName("Preflight asks CredentialReadinessService for the canonical env")
    void preflightRequestsCanonicalEnv() {
        wireDevPackage();
        when(credentialReadinessService.compute(eq("pipeline-1"), eq("dev"))).thenReturn(
                Map.of("ready", true, "connections", List.of()));

        PreflightCheckResult result = service.check("pkg-1", "target-1",
                Instant.now(), callerForTest(), "corr-1");
        assertEquals(PreflightCheckResult.PASS, result.status());
        verify(credentialReadinessService).compute("pipeline-1", "dev");
    }

    private static com.pulse.auth.policy.CallerContext callerForTest() {
        return new com.pulse.auth.policy.CallerContext(
                "user-test", "tenant-A",
                java.util.Set.of(com.pulse.auth.policy.PulseRole.DEPLOYMENT_OPERATOR),
                com.pulse.auth.policy.CallerSurface.UI);
    }

    @Test
    @DisplayName("ready=true → CREDENTIAL_READINESS passes")
    void readyTruePasses() {
        wireDevPackage();
        when(credentialReadinessService.compute(anyString(), anyString())).thenReturn(
                Map.of("ready", true, "connections", List.of()));

        PreflightCheckResult result = service.check("pkg-1", "target-1", Instant.now());
        assertFalse(result.blockers().contains(PreflightCheckCode.CREDENTIAL_READINESS.name()));
    }

    @Test
    @DisplayName("ready=false → CREDENTIAL_READINESS blocker with stable code")
    void readyFalseBlocks() {
        wireDevPackage();
        when(credentialReadinessService.compute(anyString(), anyString())).thenReturn(
                Map.of("ready", false, "connections", List.of()));

        PreflightCheckResult result = service.check("pkg-1", "target-1", Instant.now());
        assertTrue(result.blockers().contains(PreflightCheckCode.CREDENTIAL_READINESS.name()));
        // No other credential-related blocker leaked in.
        long credBlockers = result.blockers().stream()
                .filter(b -> b.contains("CREDENTIAL"))
                .count();
        assertEquals(1, credBlockers);
    }

    @Test
    @DisplayName("Service exception surfaces as CREDENTIAL_READINESS blocker, not a preflight crash")
    void serviceExceptionBecomesBlocker() {
        wireDevPackage();
        when(credentialReadinessService.compute(anyString(), anyString()))
                .thenThrow(new RuntimeException("readiness probe down"));

        PreflightCheckResult result = service.check("pkg-1", "target-1", Instant.now());
        assertTrue(result.blockers().contains(PreflightCheckCode.CREDENTIAL_READINESS.name()),
                "Service exception must surface as CREDENTIAL_READINESS blocker, got blockers: "
                        + result.blockers());
    }

    private void wireDevPackage() {
        Package pkg = new Package();
        pkg.setId("pkg-1");
        pkg.setTenantId("tenant-A");
        pkg.setPipelineId("pipeline-1");
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
        pkg.setMetadata(meta);
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));

        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-1");
        target.setTenantId("tenant-A");
        target.setName("Dev");
        target.setEnvironment("dev");
        target.setTargetType("LOCAL_MATERIALIZATION");
        target.setEnabled(true);
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(target));
    }
}
