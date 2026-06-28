package com.pulse.git.workspace;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "developer_workspaces")
public class DeveloperWorkspace extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "pipeline_id", nullable = false)
    private String pipelineId;

    @Column(name = "version_id", nullable = false)
    private String versionId;

    @Column(name = "git_repo_id", nullable = false)
    private String gitRepoId;

    @Column(name = "actor_user_id", nullable = false)
    private String actorUserId;

    @Column(name = "branch_name", nullable = false)
    private String branchName;

    @Column(name = "base_branch", nullable = false)
    private String baseBranch = "main";

    @Column(name = "base_sha")
    private String baseSha;

    @Column(name = "checkout_path", nullable = false)
    private String checkoutPath;

    @Column(name = "legacy_seed", nullable = false)
    private boolean legacySeed = false;

    @Column(name = "lifecycle_status", nullable = false)
    private String lifecycleStatus = "ACTIVE";

    @Column(name = "working_tree_status", nullable = false)
    private String workingTreeStatus = "unknown";

    @Column(name = "remote_sync_status", nullable = false)
    private String remoteSyncStatus = "unknown";

    @Column(name = "pr_status", nullable = false)
    private String prStatus = "none";

    @Column(name = "head_sha")
    private String headSha;

    @Column(name = "head_tree_sha")
    private String headTreeSha;

    @Column(name = "dirty_file_count", nullable = false)
    private int dirtyFileCount = 0;

    @Column(name = "last_package_id")
    private String lastPackageId;

    @Column(name = "last_dev_deployment_run_id")
    private String lastDevDeploymentRunId;

    @Column(name = "last_commit_sha")
    private String lastCommitSha;

    @Column(name = "last_push_sha")
    private String lastPushSha;

    @Column(name = "pull_request_id")
    private String pullRequestId;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion = 0;

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getGitRepoId() { return gitRepoId; }
    public void setGitRepoId(String gitRepoId) { this.gitRepoId = gitRepoId; }
    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String actorUserId) { this.actorUserId = actorUserId; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public String getBaseBranch() { return baseBranch; }
    public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }
    public String getBaseSha() { return baseSha; }
    public void setBaseSha(String baseSha) { this.baseSha = baseSha; }
    public String getCheckoutPath() { return checkoutPath; }
    public void setCheckoutPath(String checkoutPath) { this.checkoutPath = checkoutPath; }
    public boolean isLegacySeed() { return legacySeed; }
    public void setLegacySeed(boolean legacySeed) { this.legacySeed = legacySeed; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public void setLifecycleStatus(String lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }
    public String getWorkingTreeStatus() { return workingTreeStatus; }
    public void setWorkingTreeStatus(String workingTreeStatus) { this.workingTreeStatus = workingTreeStatus; }
    public String getRemoteSyncStatus() { return remoteSyncStatus; }
    public void setRemoteSyncStatus(String remoteSyncStatus) { this.remoteSyncStatus = remoteSyncStatus; }
    public String getPrStatus() { return prStatus; }
    public void setPrStatus(String prStatus) { this.prStatus = prStatus; }
    public String getHeadSha() { return headSha; }
    public void setHeadSha(String headSha) { this.headSha = headSha; }
    public String getHeadTreeSha() { return headTreeSha; }
    public void setHeadTreeSha(String headTreeSha) { this.headTreeSha = headTreeSha; }
    public int getDirtyFileCount() { return dirtyFileCount; }
    public void setDirtyFileCount(int dirtyFileCount) { this.dirtyFileCount = dirtyFileCount; }
    public String getLastPackageId() { return lastPackageId; }
    public void setLastPackageId(String lastPackageId) { this.lastPackageId = lastPackageId; }
    public String getLastDevDeploymentRunId() { return lastDevDeploymentRunId; }
    public void setLastDevDeploymentRunId(String lastDevDeploymentRunId) { this.lastDevDeploymentRunId = lastDevDeploymentRunId; }
    public String getLastCommitSha() { return lastCommitSha; }
    public void setLastCommitSha(String lastCommitSha) { this.lastCommitSha = lastCommitSha; }
    public String getLastPushSha() { return lastPushSha; }
    public void setLastPushSha(String lastPushSha) { this.lastPushSha = lastPushSha; }
    public String getPullRequestId() { return pullRequestId; }
    public void setPullRequestId(String pullRequestId) { this.pullRequestId = pullRequestId; }
    public int getLockVersion() { return lockVersion; }
    public void setLockVersion(int lockVersion) { this.lockVersion = lockVersion; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
