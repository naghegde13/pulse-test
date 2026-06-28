package com.pulse.git.workspace;

import com.pulse.git.model.GitRepo;
import com.pulse.deploy.model.Package;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class WorkspaceDtos {
    private WorkspaceDtos() {}

    public record StartWorkspaceRequest(
            String leaseOwner,
            Integer leaseMinutes
    ) {}

    public record WorkspaceContextDto(
            GitRepo gitRepo,
            WorkspaceStatusDto workspace
    ) {}

    public record WorkspaceStatusDto(
            String id,
            String tenantId,
            String pipelineId,
            String versionId,
            String gitRepoId,
            String actorUserId,
            String branchName,
            String baseBranch,
            String baseSha,
            String checkoutPath,
            boolean legacySeed,
            String lifecycleStatus,
            String workingTreeStatus,
            String remoteSyncStatus,
            String prStatus,
            String headSha,
            String headTreeSha,
            int dirtyFileCount,
            String lastPackageId,
            String lastDevDeploymentRunId,
            String lastCommitSha,
            String lastPushSha,
            String pullRequestId,
            int lockVersion,
            String leaseOwner,
            Instant leaseExpiresAt,
            Map<String, Object> metadata
    ) {
        public static WorkspaceStatusDto from(DeveloperWorkspace workspace) {
            return new WorkspaceStatusDto(
                    workspace.getId(),
                    workspace.getTenantId(),
                    workspace.getPipelineId(),
                    workspace.getVersionId(),
                    workspace.getGitRepoId(),
                    workspace.getActorUserId(),
                    workspace.getBranchName(),
                    workspace.getBaseBranch(),
                    workspace.getBaseSha(),
                    workspace.getCheckoutPath(),
                    workspace.isLegacySeed(),
                    workspace.getLifecycleStatus(),
                    workspace.getWorkingTreeStatus(),
                    workspace.getRemoteSyncStatus(),
                    workspace.getPrStatus(),
                    workspace.getHeadSha(),
                    workspace.getHeadTreeSha(),
                    workspace.getDirtyFileCount(),
                    workspace.getLastPackageId(),
                    workspace.getLastDevDeploymentRunId(),
                    workspace.getLastCommitSha(),
                    workspace.getLastPushSha(),
                    workspace.getPullRequestId(),
                    workspace.getLockVersion(),
                    workspace.getLeaseOwner(),
                    workspace.getLeaseExpiresAt(),
                    workspace.getMetadata()
            );
        }
    }

    public record WorkspaceDiffDto(
            String workspaceId,
            String workingTreeStatus,
            int dirtyFileCount,
            List<String> changedPaths
    ) {}

    public record WorkspaceFileDto(
            String id,
            String workspaceId,
            String path,
            String sourceArtifactId,
            String lastMaterializedSha256,
            String currentWorkspaceSha256,
            String lastCommittedSha256,
            boolean managedByPulse,
            String pathScope,
            String ownershipKey,
            Instant lastMaterializedAt
    ) {
        public static WorkspaceFileDto from(WorkspaceFileManifest manifest) {
            return new WorkspaceFileDto(
                    manifest.getId(),
                    manifest.getWorkspaceId(),
                    manifest.getPath(),
                    manifest.getSourceArtifactId(),
                    manifest.getLastMaterializedSha256(),
                    manifest.getCurrentWorkspaceSha256(),
                    manifest.getLastCommittedSha256(),
                    manifest.isManagedByPulse(),
                    manifest.getPathScope(),
                    manifest.getOwnershipKey(),
                    manifest.getLastMaterializedAt()
            );
        }
    }

    public record WorkspaceFileContentDto(
            WorkspaceFileDto manifest,
            String content
    ) {}

    public record WorkspaceFileUpdateRequest(
            String path,
            String content
    ) {}

    public record WorkspaceGenerationDto(
            String workspaceId,
            String generationRunId,
            int materializedFileCount,
            WorkspaceStatusDto workspace
    ) {}

    public record WorkspaceCommitRequest(String message) {}

    public record CreatePullRequestRequest(
            String title,
            String body,
            String targetBranch,
            String packageId
    ) {}

    public record WorkspacePackageDto(
            String id,
            String workspaceId,
            String versionId,
            String sourceKind,
            String commitSha,
            String treeSha,
            String packageArtifactUri,
            String packageArtifactSha256,
            String packageManifestHash,
            boolean promotable
    ) {
        public static WorkspacePackageDto from(Package pkg) {
            return new WorkspacePackageDto(
                    pkg.getId(),
                    pkg.getWorkspaceId(),
                    pkg.getVersionId(),
                    pkg.getSourceKind(),
                    pkg.getCommitSha(),
                    pkg.getTreeSha(),
                    pkg.getPackageArtifactUri(),
                    pkg.getPackageArtifactSha256(),
                    pkg.getPackageManifestHash(),
                    pkg.isPromotable());
        }
    }
}
