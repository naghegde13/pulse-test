package com.pulse.deploy;

import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.preflight.DeploymentPreflightService;
import com.pulse.deploy.preflight.PreflightCheckCode;
import com.pulse.deploy.preflight.PreflightCheckResult;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.git.model.PullRequest;
import com.pulse.git.policy.BranchAllowlistPolicy;
import com.pulse.git.repository.PullRequestRepository;
import com.pulse.pipeline.model.VersionAcceptance;
import com.pulse.pipeline.repository.VersionAcceptanceRepository;
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
 * Phase 6 — DeploymentPreflightService consumes Phase 6 PR + branch
 * policy state. Pins:
 *
 * <ul>
 *   <li>{@link PreflightCheckCode#GIT_BRANCH_ALLOWED} reads
 *       {@link BranchAllowlistPolicy} for the canonical env.</li>
 *   <li>{@link PreflightCheckCode#PR_POLICY} reads
 *       {@link PullRequestRepository} and gates on PR state per env.</li>
 * </ul>
 */
class PullRequestPreflightPolicyTest {

    private PackageRepository packageRepo;
    private DeploymentTargetRepository targetRepo;
    private StorageBackendDeployGate storageGate;
    private CredentialReadinessService credentialReadinessService;
    private PullRequestRepository pullRequestRepository;
    private VersionAcceptanceRepository acceptanceRepository;
    private DeploymentPreflightService service;

    @BeforeEach
    void setUp() {
        packageRepo = mock(PackageRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        storageGate = mock(StorageBackendDeployGate.class);
        credentialReadinessService = mock(CredentialReadinessService.class);
        pullRequestRepository = mock(PullRequestRepository.class);
        acceptanceRepository = mock(VersionAcceptanceRepository.class);
        when(storageGate.check(any(), any())).thenReturn(
                new StorageBackendDeployGate.Result(true, List.of()));
        when(credentialReadinessService.compute(anyString(), anyString())).thenReturn(
                Map.of("ready", true, "connections", List.of()));
        service = new DeploymentPreflightService(packageRepo, targetRepo,
                storageGate, credentialReadinessService,
                new BranchAllowlistPolicy(), pullRequestRepository,
                acceptanceRepository,
                new com.pulse.deploy.capability.RuntimeCapabilityMatrix(),
                com.pulse.runtime.TestRuntimeAuthorityFactory.gcpPulse(),
                mock(com.pulse.storage.contract.service.TableContractService.class),
                mock(com.pulse.deploy.projection.service.RuntimeProjectionService.class),
                mock(com.pulse.deploy.runtime.AirflowRuntimeClient.class),
                mock(com.pulse.pipeline.service.OrchestrationNamespaceService.class));
    }

    @Test
    @DisplayName("GIT_BRANCH_ALLOWED allows main for prod, blocks feature/x for prod")
    void branchAllowlistPerEnv() {
        wirePackage("pkg-1", "tenant-A", "pipeline-1", "version-1", "main");
        wireProdTarget();
        when(pullRequestRepository.findByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(List.of(merged("version-1")));
        when(acceptanceRepository.findFirstByVersionIdAndAcceptanceStatusOrderByCreatedAtDesc("version-1", "ACTIVE"))
                .thenReturn(Optional.of(accepted("pkg-1", "version-1")));
        var allowed = service.check("pkg-1", "target-1", Instant.now(), null, "corr");
        assertFalse(allowed.blockers().contains(PreflightCheckCode.GIT_BRANCH_ALLOWED.name()));

        wirePackage("pkg-2", "tenant-A", "pipeline-1", "version-2", "feature/x");
        // wirePackage already stubbed packageRepo.findById("pkg-2"); the
        // PR repo also needs a MERGED PR for version-2 so the only
        // remaining blocker is the branch policy.
        when(pullRequestRepository.findByVersionIdOrderByCreatedAtDesc("version-2"))
                .thenReturn(List.of(merged("version-2")));
        when(acceptanceRepository.findFirstByVersionIdAndAcceptanceStatusOrderByCreatedAtDesc("version-2", "ACTIVE"))
                .thenReturn(Optional.of(accepted("pkg-2", "version-2")));
        var blocked = service.check("pkg-2", "target-1", Instant.now(), null, "corr");
        assertTrue(blocked.blockers().contains(PreflightCheckCode.GIT_BRANCH_ALLOWED.name()),
                "feature/x must NOT be allowed for prod, got blockers: " + blocked.blockers());
    }

    @Test
    @DisplayName("PR_POLICY requires MERGED PR for prod; OPEN PR is not enough")
    void prPolicyProdRequiresMerged() {
        wirePackage("pkg-1", "tenant-A", "pipeline-1", "version-1", "main");
        wireProdTarget();
        when(pullRequestRepository.findByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(List.of(open("version-1")));
        var result = service.check("pkg-1", "target-1", Instant.now(), null, "corr");
        assertTrue(result.blockers().contains(PreflightCheckCode.PR_POLICY.name()),
                "OPEN PR alone must NOT satisfy prod policy");

        when(pullRequestRepository.findByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(List.of(merged("version-1")));
        when(acceptanceRepository.findFirstByVersionIdAndAcceptanceStatusOrderByCreatedAtDesc("version-1", "ACTIVE"))
                .thenReturn(Optional.of(accepted("pkg-1", "version-1")));
        var allowed = service.check("pkg-1", "target-1", Instant.now(), null, "corr");
        assertFalse(allowed.blockers().contains(PreflightCheckCode.PR_POLICY.name()),
                "MERGED PR must satisfy prod policy");
    }

    @Test
    @DisplayName("PR_POLICY requires OPEN or MERGED for integration, dev is exempt")
    void prPolicyIntegrationOpenOrMerged() {
        wirePackage("pkg-1", "tenant-A", "pipeline-1", "version-1", "feature/x");
        wireIntegrationTarget();
        when(pullRequestRepository.findByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(List.of());
        var blocked = service.check("pkg-1", "target-1", Instant.now(), null, "corr");
        assertTrue(blocked.blockers().contains(PreflightCheckCode.PR_POLICY.name()),
                "no PR must block integration");

        when(pullRequestRepository.findByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(List.of(open("version-1")));
        var openOk = service.check("pkg-1", "target-1", Instant.now(), null, "corr");
        assertFalse(openOk.blockers().contains(PreflightCheckCode.PR_POLICY.name()),
                "OPEN PR must satisfy integration");

        // dev never requires a PR.
        wireDevTarget();
        when(pullRequestRepository.findByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(List.of());
        var devOk = service.check("pkg-1", "target-1", Instant.now(), null, "corr");
        assertFalse(devOk.blockers().contains(PreflightCheckCode.PR_POLICY.name()),
                "dev must never require a PR");
    }

    // ------------------------------------------------------------------

    private void wirePackage(String packageId, String tenantId, String pipelineId,
                             String versionId, String branch) {
        Package pkg = new Package();
        pkg.setId(packageId);
        pkg.setTenantId(tenantId);
        pkg.setPipelineId(pipelineId);
        pkg.setVersionId(versionId);
        pkg.setBuildStatus("COMPLETED");
        pkg.setCommitSha("0".repeat(40));
        pkg.setTreeSha("1".repeat(40));
        Map<String, Object> meta = new LinkedHashMap<>();
        Map<String, Object> git = new LinkedHashMap<>();
        git.put("repoId", "repo-1");
        git.put("branch", branch);
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
        when(packageRepo.findById(packageId)).thenReturn(Optional.of(pkg));
    }

    private void wireProdTarget() { wireTarget("target-1", "prod"); }
    private void wireIntegrationTarget() { wireTarget("target-1", "integration"); }
    private void wireDevTarget() { wireTarget("target-1", "dev"); }

    private void wireTarget(String id, String env) {
        DeploymentTarget t = new DeploymentTarget();
        t.setId(id);
        t.setTenantId("tenant-A");
        t.setName("Target " + env);
        t.setEnvironment(env);
        t.setTargetType("AIRFLOW");
        t.setEnabled(true);
        when(targetRepo.findById(id)).thenReturn(Optional.of(t));
    }

    private static PullRequest open(String versionId) {
        PullRequest pr = new PullRequest();
        pr.setVersionId(versionId);
        pr.setStatus("OPEN");
        pr.setSourceBranch("feature/x");
        pr.setTargetBranch("main");
        pr.setTitle("test");
        pr.setPrNumber(1);
        pr.setGitRepoId("repo-1");
        return pr;
    }

    private static PullRequest merged(String versionId) {
        PullRequest pr = new PullRequest();
        pr.setVersionId(versionId);
        pr.setStatus("MERGED");
        pr.setSourceBranch("feature/x");
        pr.setTargetBranch("main");
        pr.setTitle("test");
        pr.setPrNumber(1);
        pr.setGitRepoId("repo-1");
        return pr;
    }

    private static VersionAcceptance accepted(String packageId, String versionId) {
        VersionAcceptance acceptance = new VersionAcceptance();
        acceptance.setAcceptedPackageId(packageId);
        acceptance.setVersionId(versionId);
        acceptance.setAcceptedCommitSha("0".repeat(40));
        acceptance.setAcceptedTreeSha("1".repeat(40));
        acceptance.setAcceptanceStatus("ACTIVE");
        return acceptance;
    }
}
