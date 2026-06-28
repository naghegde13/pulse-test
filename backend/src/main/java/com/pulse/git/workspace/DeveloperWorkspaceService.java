package com.pulse.git.workspace;

import com.github.f4b6a3.ulid.UlidCreator;
import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.repository.GenerationRunRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.VersionAcceptance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.VersionAcceptanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class DeveloperWorkspaceService {

    private static final String ACTIVE = "ACTIVE";

    private final DeveloperWorkspaceRepository workspaceRepository;
    private final GitRepoRepository gitRepoRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineVersionRepository versionRepository;
    private final VersionAcceptanceRepository acceptanceRepository;
    private final GenerationRunRepository generationRunRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final WorkspaceGitStorageService gitStorageService;
    private final WorkspaceFileService workspaceFileService;
    private final ActorResolverService actorResolver;

    public DeveloperWorkspaceService(DeveloperWorkspaceRepository workspaceRepository,
                                     GitRepoRepository gitRepoRepository,
                                     PipelineRepository pipelineRepository,
                                     PipelineVersionRepository versionRepository,
                                     VersionAcceptanceRepository acceptanceRepository,
                                     GenerationRunRepository generationRunRepository,
                                     GeneratedArtifactRepository artifactRepository,
                                     WorkspaceGitStorageService gitStorageService,
                                     WorkspaceFileService workspaceFileService,
                                     ActorResolverService actorResolver) {
        this.workspaceRepository = workspaceRepository;
        this.gitRepoRepository = gitRepoRepository;
        this.pipelineRepository = pipelineRepository;
        this.versionRepository = versionRepository;
        this.acceptanceRepository = acceptanceRepository;
        this.generationRunRepository = generationRunRepository;
        this.artifactRepository = artifactRepository;
        this.gitStorageService = gitStorageService;
        this.workspaceFileService = workspaceFileService;
        this.actorResolver = actorResolver;
    }

    @Transactional
    public WorkspaceDtos.WorkspaceStatusDto startWorkspace(
            String versionId,
            WorkspaceDtos.StartWorkspaceRequest request) {
        PipelineVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineVersion", versionId));
        Pipeline pipeline = pipelineRepository.findById(version.getPipelineId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", version.getPipelineId()));
        CallerContext caller = actorResolver.resolve(CallerSurface.UI, pipeline.getTenantId());

        DeveloperWorkspace workspace = workspaceRepository
                .findFirstByTenantIdAndVersionIdAndActorUserIdAndLifecycleStatusOrderByCreatedAtDesc(
                        pipeline.getTenantId(), versionId, caller.userId(), ACTIVE)
                .orElse(null);
        if (workspace != null) {
            enforceLease(workspace, request);
            gitStorageService.refreshStatus(workspace);
            return WorkspaceDtos.WorkspaceStatusDto.from(workspaceRepository.save(workspace));
        }

        GitRepo repo = resolveRepo(pipeline);
        workspace = newWorkspace(pipeline, version, repo, caller, request);
        applyBootstrapEvidence(workspace, version);
        gitStorageService.prepareCheckout(workspace);
        workspace = workspaceRepository.save(workspace);
        materializeLatestArtifacts(workspace, versionId);
        gitStorageService.refreshStatus(workspace);
        return WorkspaceDtos.WorkspaceStatusDto.from(workspaceRepository.save(workspace));
    }

    @Transactional(readOnly = true)
    public WorkspaceDtos.WorkspaceStatusDto getWorkspace(String workspaceId) {
        DeveloperWorkspace workspace = get(workspaceId);
        return WorkspaceDtos.WorkspaceStatusDto.from(workspace);
    }

    @Transactional(readOnly = true)
    public WorkspaceDtos.WorkspaceContextDto getWorkspaceContext(String versionId) {
        PipelineVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineVersion", versionId));
        Pipeline pipeline = pipelineRepository.findById(version.getPipelineId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", version.getPipelineId()));
        CallerContext caller = actorResolver.resolve(CallerSurface.UI, pipeline.getTenantId());
        DeveloperWorkspace workspace = workspaceRepository
                .findFirstByTenantIdAndVersionIdAndActorUserIdAndLifecycleStatusOrderByCreatedAtDesc(
                        pipeline.getTenantId(), versionId, caller.userId(), ACTIVE)
                .orElse(null);
        GitRepo repo = resolveRepoOptional(pipeline).orElse(null);
        return new WorkspaceDtos.WorkspaceContextDto(
                repo,
                workspace == null ? null : WorkspaceDtos.WorkspaceStatusDto.from(workspace));
    }

    @Transactional
    public WorkspaceDtos.WorkspaceDiffDto getDiff(String workspaceId) {
        DeveloperWorkspace workspace = get(workspaceId);
        gitStorageService.refreshStatus(workspace);
        workspaceRepository.save(workspace);
        return new WorkspaceDtos.WorkspaceDiffDto(
                workspace.getId(),
                workspace.getWorkingTreeStatus(),
                workspace.getDirtyFileCount(),
                gitStorageService.changedPaths(workspace));
    }

    @Transactional
    public WorkspaceDtos.WorkspaceStatusDto archive(String workspaceId) {
        DeveloperWorkspace workspace = get(workspaceId);
        workspace.setLifecycleStatus("ARCHIVED");
        workspace.setLeaseOwner(null);
        workspace.setLeaseExpiresAt(null);
        return WorkspaceDtos.WorkspaceStatusDto.from(workspaceRepository.save(workspace));
    }

    DeveloperWorkspace get(String workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("DeveloperWorkspace", workspaceId));
    }

    private DeveloperWorkspace newWorkspace(Pipeline pipeline,
                                            PipelineVersion version,
                                            GitRepo repo,
                                            CallerContext caller,
                                            WorkspaceDtos.StartWorkspaceRequest request) {
        DeveloperWorkspace workspace = new DeveloperWorkspace();
        workspace.setId(UlidCreator.getMonotonicUlid().toString());
        workspace.setTenantId(pipeline.getTenantId());
        workspace.setPipelineId(pipeline.getId());
        workspace.setVersionId(version.getId());
        workspace.setGitRepoId(repo.getId());
        workspace.setActorUserId(caller.userId());
        workspace.setBaseBranch(blankToDefault(repo.getDefaultBranch(), "main"));
        workspace.setCheckoutPath(gitStorageService.checkoutPath(workspace.getId()).toString());
        String branch = gitStorageService.branchName(pipeline.getName(), version.getRevision(), caller.userId());
        if (workspaceRepository.findFirstByGitRepoIdAndBranchNameAndLifecycleStatus(
                repo.getId(), branch, ACTIVE).isPresent()) {
            branch = gitStorageService.branchNameWithWorkspaceSuffix(
                    pipeline.getName(), version.getRevision(), caller.userId(), workspace.getId());
        }
        workspace.setBranchName(branch);
        workspace.setRemoteSyncStatus("not_pushed");
        workspace.setPrStatus("none");
        applyLease(workspace, request);
        return workspace;
    }

    private GitRepo resolveRepo(Pipeline pipeline) {
        return resolveRepoOptional(pipeline)
                .orElseThrow(() -> new ResourceNotFoundException("GitRepo for tenant", pipeline.getTenantId()));
    }

    private Optional<GitRepo> resolveRepoOptional(Pipeline pipeline) {
        if (pipeline.getDomainId() != null && !pipeline.getDomainId().isBlank()) {
            GitRepo repo = gitRepoRepository.findFirstByDomainIdOrderByCreatedAtDesc(pipeline.getDomainId()).orElse(null);
            if (repo != null) {
                return Optional.of(repo);
            }
        }
        return gitRepoRepository.findByTenantIdAndScope(pipeline.getTenantId(), "TENANT")
                .or(() -> gitRepoRepository.findByTenantIdAndScope(pipeline.getTenantId(), "LEGACY"));
    }

    private void applyBootstrapEvidence(DeveloperWorkspace workspace, PipelineVersion version) {
        VersionAcceptance acceptance = acceptanceRepository
                .findFirstByVersionIdAndAcceptanceStatusOrderByCreatedAtDesc(version.getId(), ACTIVE)
                .orElse(null);
        if (acceptance != null) {
            workspace.setBaseSha(acceptance.getAcceptedCommitSha());
            workspace.setHeadSha(acceptance.getAcceptedCommitSha());
            workspace.setHeadTreeSha(acceptance.getAcceptedTreeSha());
            workspace.setMetadata(Map.of("bootstrapSource", "ACTIVE_ACCEPTANCE"));
            return;
        }
        if (version.getCommitHash() != null && !version.getCommitHash().isBlank()) {
            workspace.setBaseSha(version.getCommitHash());
            workspace.setMetadata(Map.of("bootstrapSource", "PIPELINE_VERSION_COMMIT"));
            return;
        }
        boolean hasArtifacts = generationRunRepository.findTopByVersionIdOrderByCreatedAtDesc(version.getId())
                .map(run -> !artifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId()).isEmpty())
                .orElse(false);
        if (hasArtifacts) {
            workspace.setLegacySeed(true);
            workspace.setMetadata(Map.of("bootstrapSource", "LEGACY_GENERATED_ARTIFACTS"));
        } else {
            workspace.setMetadata(Map.of("bootstrapSource", "EMPTY_WORKSPACE"));
        }
    }

    private void materializeLatestArtifacts(DeveloperWorkspace workspace, String versionId) {
        generationRunRepository.findTopByVersionIdOrderByCreatedAtDesc(versionId)
                .ifPresent(run -> artifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId())
                        .forEach(artifact -> workspaceFileService.materializeArtifact(workspace, artifact)));
    }

    private void enforceLease(DeveloperWorkspace workspace, WorkspaceDtos.StartWorkspaceRequest request) {
        Instant now = Instant.now();
        String requestedOwner = request == null ? null : request.leaseOwner();
        if (workspace.getLeaseExpiresAt() != null && workspace.getLeaseExpiresAt().isAfter(now)
                && requestedOwner != null && workspace.getLeaseOwner() != null
                && !workspace.getLeaseOwner().equals(requestedOwner)) {
            throw new ResponseStatusException(CONFLICT, "WORKSPACE_LEASE_HELD");
        }
        applyLease(workspace, request);
    }

    private void applyLease(DeveloperWorkspace workspace, WorkspaceDtos.StartWorkspaceRequest request) {
        if (request == null || request.leaseOwner() == null || request.leaseOwner().isBlank()) {
            return;
        }
        int minutes = request.leaseMinutes() == null ? 60 : Math.max(1, Math.min(request.leaseMinutes(), 480));
        workspace.setLeaseOwner(request.leaseOwner().trim());
        workspace.setLeaseExpiresAt(Instant.now().plus(Duration.ofMinutes(minutes)));
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
