package com.pulse.git.workspace;

import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.PulseAction;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.identity.UserGitIdentityService;
import com.pulse.git.model.GitRepo;
import com.pulse.git.model.PullRequest;
import com.pulse.git.provider.GitProviderAdapter;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.repository.PullRequestRepository;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.VersionAcceptance;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.VersionAcceptanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class WorkspacePullRequestService {

    private final DeveloperWorkspaceRepository workspaceRepository;
    private final GitRepoRepository gitRepoRepository;
    private final PullRequestRepository pullRequestRepository;
    private final PackageRepository packageRepository;
    private final VersionAcceptanceRepository acceptanceRepository;
    private final PipelineVersionRepository versionRepository;
    private final GitProviderAdapter gitProvider;
    private final UserGitIdentityService identityService;
    private final WorkspaceAuthorizationService authorizationService;

    public WorkspacePullRequestService(DeveloperWorkspaceRepository workspaceRepository,
                                       GitRepoRepository gitRepoRepository,
                                       PullRequestRepository pullRequestRepository,
                                       PackageRepository packageRepository,
                                       VersionAcceptanceRepository acceptanceRepository,
                                       PipelineVersionRepository versionRepository,
                                       GitProviderAdapter gitProvider,
                                       UserGitIdentityService identityService,
                                       WorkspaceAuthorizationService authorizationService) {
        this.workspaceRepository = workspaceRepository;
        this.gitRepoRepository = gitRepoRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.packageRepository = packageRepository;
        this.acceptanceRepository = acceptanceRepository;
        this.versionRepository = versionRepository;
        this.gitProvider = gitProvider;
        this.identityService = identityService;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public PullRequest createPullRequest(String workspaceId, WorkspaceDtos.CreatePullRequestRequest request) {
        DeveloperWorkspace workspace = workspace(workspaceId);
        GitRepo repo = repo(workspace);
        CallerContext caller = authorizationService.enforce(workspace.getTenantId(), PulseAction.COMMIT);
        UserGitIdentity identity = identityService.requireValidIdentity(caller);
        String targetBranch = request == null || request.targetBranch() == null || request.targetBranch().isBlank()
                ? workspace.getBaseBranch()
                : request.targetBranch().trim();
        GitProviderAdapter.PullRequestInfo info = gitProvider.createPullRequest(new GitProviderAdapter.CreatePullRequest(
                repo.getRepoUrl(),
                request == null || request.title() == null || request.title().isBlank()
                        ? "PULSE workspace " + workspace.getBranchName()
                        : request.title(),
                request == null || request.body() == null ? "" : request.body(),
                workspace.getBranchName(),
                targetBranch,
                identity.getCredentialReference(),
                List.of()));
        PullRequest pr = new PullRequest();
        pr.setGitRepoId(repo.getId());
        pr.setVersionId(workspace.getVersionId());
        pr.setPrNumber(info.number());
        pr.setTitle(info.title() == null ? "PULSE workspace " + workspace.getBranchName() : info.title());
        pr.setSourceBranch(workspace.getBranchName());
        pr.setTargetBranch(targetBranch);
        pr.setStatus(info.state() == null ? "OPEN" : info.state());
        pr.setPrUrl(info.url());
        pr.setMergeCommitSha(info.mergeCommitSha());
        pr.setHeadSha(workspace.getHeadSha());
        pr.setHeadTreeSha(workspace.getHeadTreeSha());
        pr.setBaseSha(workspace.getBaseSha());
        Package pkg = packageForPullRequest(workspace, request == null ? null : request.packageId());
        if (pkg != null) pr.setPackageArtifactSha256(pkg.getPackageArtifactSha256());
        pr.setProviderSyncedAt(Instant.now());
        pr.setMetadata(new LinkedHashMap<>(Map.of("workspaceId", workspace.getId())));
        pr = pullRequestRepository.save(pr);
        workspace.setPullRequestId(pr.getId());
        workspace.setPrStatus(pr.getStatus().toLowerCase());
        workspaceRepository.save(workspace);
        return pr;
    }

    @Transactional
    public PullRequest syncPullRequest(String prId) {
        PullRequest pr = pullRequestRepository.findById(prId)
                .orElseThrow(() -> new ResourceNotFoundException("PullRequest", prId));
        String gitRepoId = pr.getGitRepoId();
        GitRepo repo = gitRepoRepository.findById(gitRepoId)
                .orElseThrow(() -> new ResourceNotFoundException("GitRepo", gitRepoId));
        String tenantId = repo.getTenantId();
        CallerContext caller = authorizationService.enforce(tenantId, PulseAction.APPROVE);
        UserGitIdentity identity = identityService.requireValidIdentity(caller);
        GitProviderAdapter.PullRequestInfo info = gitProvider.syncState(
                repo.getRepoUrl(), pr.getPrNumber(), identity.getCredentialReference());
        pr.setStatus(info.state() == null ? pr.getStatus() : info.state());
        pr.setPrUrl(info.url() == null ? pr.getPrUrl() : info.url());
        pr.setMergeCommitSha(info.mergeCommitSha());
        pr.setMergedAt(info.mergedAt());
        pr.setClosedAt(info.closedAt());
        pr.setProviderSyncedAt(Instant.now());
        pr = pullRequestRepository.save(pr);
        if ("MERGED".equalsIgnoreCase(pr.getStatus())) {
            acceptExactHead(pr, caller);
        }
        return pr;
    }

    @Transactional(readOnly = true)
    public VersionAcceptance getAcceptance(String versionId) {
        return acceptanceRepository
                .findFirstByVersionIdAndAcceptanceStatusOrderByCreatedAtDesc(versionId, "ACTIVE")
                .orElseThrow(() -> new ResourceNotFoundException("VersionAcceptance for version", versionId));
    }

    private void acceptExactHead(PullRequest pr, CallerContext caller) {
        Package pkg = selectPackage(pr);
        if (pkg == null) throw new ResponseStatusException(CONFLICT, "PR_ACCEPTANCE_PACKAGE_MISSING");
        if (!pkg.isPromotable() || !"GIT_COMMIT".equals(pkg.getSourceKind())) {
            throw new ResponseStatusException(CONFLICT, "PR_ACCEPTANCE_PACKAGE_NOT_PROMOTABLE");
        }
        if (!equals(pkg.getCommitSha(), pr.getHeadSha()) || !equals(pkg.getTreeSha(), pr.getHeadTreeSha())) {
            throw new ResponseStatusException(CONFLICT, "PR_HEAD_SHA_MISMATCH");
        }
        List<VersionAcceptance> activeAcceptances =
                acceptanceRepository.findByVersionIdAndAcceptanceStatus(pr.getVersionId(), "ACTIVE");
        for (VersionAcceptance active : activeAcceptances) {
            if (equals(active.getAcceptedPackageId(), pkg.getId())
                    && equals(active.getAcceptedCommitSha(), pkg.getCommitSha())
                    && equals(active.getAcceptedTreeSha(), pkg.getTreeSha())) {
                return;
            }
            Instant activeAcceptedAt = active.getAcceptedAt();
            Instant candidateMergedAt = pr.getMergedAt();
            if (activeAcceptedAt != null && candidateMergedAt != null
                    && !candidateMergedAt.isAfter(activeAcceptedAt)) {
                throw new ResponseStatusException(CONFLICT, "PR_ACCEPTANCE_STALE_MERGE");
            }
        }
        for (VersionAcceptance active : activeAcceptances) {
            active.setAcceptanceStatus("SUPERSEDED");
            acceptanceRepository.save(active);
        }
        PipelineVersion version = versionRepository.findById(pr.getVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("PipelineVersion", pr.getVersionId()));
        VersionAcceptance acceptance = new VersionAcceptance();
        acceptance.setTenantId(pkg.getTenantId());
        acceptance.setPipelineId(pkg.getPipelineId());
        acceptance.setVersionId(pkg.getVersionId());
        acceptance.setWorkspaceId(pkg.getWorkspaceId());
        acceptance.setPullRequestId(pr.getId());
        acceptance.setAcceptedPackageId(pkg.getId());
        acceptance.setAcceptedCommitSha(pkg.getCommitSha());
        acceptance.setAcceptedTreeSha(pkg.getTreeSha());
        acceptance.setAcceptedAt(Instant.now());
        acceptance.setAcceptedBy(caller.userId());
        acceptance.setAcceptanceEvidence(Map.of(
                "pullRequestId", pr.getId(),
                "packageId", pkg.getId(),
                "packageArtifactSha256", pkg.getPackageArtifactSha256()));
        acceptanceRepository.save(acceptance);
        version.setCommitHash(pkg.getCommitSha());
        versionRepository.save(version);
    }

    private Package selectPackage(PullRequest pr) {
        List<Package> packages = packageRepository.findByVersionIdAndPromotableTrueOrderByCreatedAtDesc(pr.getVersionId());
        if (pr.getPackageArtifactSha256() != null && !pr.getPackageArtifactSha256().isBlank()) {
            return packages.stream()
                    .filter(pkg -> pr.getPackageArtifactSha256().equals(pkg.getPackageArtifactSha256()))
                    .findFirst()
                    .orElse(null);
        }
        return packages.stream()
                .filter(pkg -> equals(pkg.getCommitSha(), pr.getHeadSha()))
                .filter(pkg -> equals(pkg.getTreeSha(), pr.getHeadTreeSha()))
                .findFirst()
                .orElse(null);
    }

    private Package packageForPullRequest(DeveloperWorkspace workspace, String packageId) {
        if (packageId != null && !packageId.isBlank()) {
            Package pkg = packageRepository.findById(packageId).orElse(null);
            if (pkg == null) return null;
            if (!isPromotableHeadPackage(pkg, workspace)) {
                throw new ResponseStatusException(CONFLICT, "PR_PACKAGE_NOT_PROMOTABLE_FOR_HEAD");
            }
            return pkg;
        }
        return packageRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()).stream()
                .filter(pkg -> isPromotableHeadPackage(pkg, workspace))
                .findFirst()
                .orElse(null);
    }

    private static boolean isPromotableHeadPackage(Package pkg, DeveloperWorkspace workspace) {
        return pkg.isPromotable()
                && "GIT_COMMIT".equals(pkg.getSourceKind())
                && equals(pkg.getCommitSha(), workspace.getHeadSha())
                && equals(pkg.getTreeSha(), workspace.getHeadTreeSha());
    }

    private DeveloperWorkspace workspace(String workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("DeveloperWorkspace", workspaceId));
    }

    private GitRepo repo(DeveloperWorkspace workspace) {
        return gitRepoRepository.findById(workspace.getGitRepoId())
                .orElseThrow(() -> new ResourceNotFoundException("GitRepo", workspace.getGitRepoId()));
    }

    private static boolean equals(String left, String right) {
        return left != null && left.equals(right);
    }
}
