package com.pulse.git.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "pull_requests")
public class PullRequest extends BaseEntity {

    @Column(name = "git_repo_id", nullable = false)
    private String gitRepoId;

    @Column(name = "generation_run_id")
    private String generationRunId;

    @Column(name = "version_id", nullable = false)
    private String versionId;

    @Column(name = "pr_number", nullable = false)
    private int prNumber;

    @Column(nullable = false)
    private String title;

    @Column(name = "source_branch", nullable = false)
    private String sourceBranch;

    @Column(name = "target_branch", nullable = false)
    private String targetBranch = "main";

    @Column(nullable = false)
    private String status = "OPEN";

    @Column(name = "pr_url")
    private String prUrl;

    @Column(name = "merge_commit_sha")
    private String mergeCommitSha;

    @Column(name = "provider_pr_id")
    private String providerPrId;

    @Column(name = "provider_node_id")
    private String providerNodeId;

    @Column(name = "provider_repository_id")
    private String providerRepositoryId;

    @Column(name = "head_sha")
    private String headSha;

    @Column(name = "head_tree_sha")
    private String headTreeSha;

    @Column(name = "base_sha")
    private String baseSha;

    @Column(name = "package_artifact_sha256")
    private String packageArtifactSha256;

    @Column(name = "provider_synced_at")
    private Instant providerSyncedAt;

    @Column(name = "merged_at")
    private Instant mergedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public String getGitRepoId() { return gitRepoId; }
    public void setGitRepoId(String gitRepoId) { this.gitRepoId = gitRepoId; }
    public String getGenerationRunId() { return generationRunId; }
    public void setGenerationRunId(String generationRunId) { this.generationRunId = generationRunId; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public int getPrNumber() { return prNumber; }
    public void setPrNumber(int prNumber) { this.prNumber = prNumber; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSourceBranch() { return sourceBranch; }
    public void setSourceBranch(String sourceBranch) { this.sourceBranch = sourceBranch; }
    public String getTargetBranch() { return targetBranch; }
    public void setTargetBranch(String targetBranch) { this.targetBranch = targetBranch; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
    public String getMergeCommitSha() { return mergeCommitSha; }
    public void setMergeCommitSha(String mergeCommitSha) { this.mergeCommitSha = mergeCommitSha; }
    public String getProviderPrId() { return providerPrId; }
    public void setProviderPrId(String providerPrId) { this.providerPrId = providerPrId; }
    public String getProviderNodeId() { return providerNodeId; }
    public void setProviderNodeId(String providerNodeId) { this.providerNodeId = providerNodeId; }
    public String getProviderRepositoryId() { return providerRepositoryId; }
    public void setProviderRepositoryId(String providerRepositoryId) { this.providerRepositoryId = providerRepositoryId; }
    public String getHeadSha() { return headSha; }
    public void setHeadSha(String headSha) { this.headSha = headSha; }
    public String getHeadTreeSha() { return headTreeSha; }
    public void setHeadTreeSha(String headTreeSha) { this.headTreeSha = headTreeSha; }
    public String getBaseSha() { return baseSha; }
    public void setBaseSha(String baseSha) { this.baseSha = baseSha; }
    public String getPackageArtifactSha256() { return packageArtifactSha256; }
    public void setPackageArtifactSha256(String packageArtifactSha256) { this.packageArtifactSha256 = packageArtifactSha256; }
    public Instant getProviderSyncedAt() { return providerSyncedAt; }
    public void setProviderSyncedAt(Instant providerSyncedAt) { this.providerSyncedAt = providerSyncedAt; }
    public Instant getMergedAt() { return mergedAt; }
    public void setMergedAt(Instant mergedAt) { this.mergedAt = mergedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
