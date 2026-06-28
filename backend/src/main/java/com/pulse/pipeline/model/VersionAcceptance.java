package com.pulse.pipeline.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "version_acceptances")
public class VersionAcceptance extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "pipeline_id", nullable = false)
    private String pipelineId;

    @Column(name = "version_id", nullable = false)
    private String versionId;

    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(name = "pull_request_id")
    private String pullRequestId;

    @Column(name = "accepted_package_id")
    private String acceptedPackageId;

    @Column(name = "accepted_commit_sha", nullable = false)
    private String acceptedCommitSha;

    @Column(name = "accepted_tree_sha", nullable = false)
    private String acceptedTreeSha;

    @Column(name = "acceptance_kind", nullable = false)
    private String acceptanceKind = "MERGED_PR_EXACT_HEAD";

    @Column(name = "acceptance_status", nullable = false)
    private String acceptanceStatus = "ACTIVE";

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by")
    private String acceptedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "acceptance_evidence", columnDefinition = "jsonb")
    private Map<String, Object> acceptanceEvidence;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getPullRequestId() { return pullRequestId; }
    public void setPullRequestId(String pullRequestId) { this.pullRequestId = pullRequestId; }
    public String getAcceptedPackageId() { return acceptedPackageId; }
    public void setAcceptedPackageId(String acceptedPackageId) { this.acceptedPackageId = acceptedPackageId; }
    public String getAcceptedCommitSha() { return acceptedCommitSha; }
    public void setAcceptedCommitSha(String acceptedCommitSha) { this.acceptedCommitSha = acceptedCommitSha; }
    public String getAcceptedTreeSha() { return acceptedTreeSha; }
    public void setAcceptedTreeSha(String acceptedTreeSha) { this.acceptedTreeSha = acceptedTreeSha; }
    public String getAcceptanceKind() { return acceptanceKind; }
    public void setAcceptanceKind(String acceptanceKind) { this.acceptanceKind = acceptanceKind; }
    public String getAcceptanceStatus() { return acceptanceStatus; }
    public void setAcceptanceStatus(String acceptanceStatus) { this.acceptanceStatus = acceptanceStatus; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }
    public String getAcceptedBy() { return acceptedBy; }
    public void setAcceptedBy(String acceptedBy) { this.acceptedBy = acceptedBy; }
    public Map<String, Object> getAcceptanceEvidence() { return acceptanceEvidence; }
    public void setAcceptanceEvidence(Map<String, Object> acceptanceEvidence) { this.acceptanceEvidence = acceptanceEvidence; }
}
