package com.pulse.git.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "tenant_repo_scaffold_items")
public class TenantRepoScaffoldItem extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, length = 26)
    private String tenantId;

    @Column(name = "git_repo_id", nullable = false, length = 26)
    private String gitRepoId;

    @Column(name = "branch_name", nullable = false)
    private String branchName;

    @Column(name = "item_type", nullable = false, length = 32)
    private String itemType;

    @Column(name = "domain_id", length = 26)
    private String domainId;

    @Column(name = "domain_slug")
    private String domainSlug;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "last_scaffolded_at")
    private Instant lastScaffoldedAt;

    @Column(name = "last_scaffolded_by_user_id")
    private String lastScaffoldedByUserId;

    @Column(name = "last_commit_sha")
    private String lastCommitSha;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getGitRepoId() { return gitRepoId; }
    public void setGitRepoId(String gitRepoId) { this.gitRepoId = gitRepoId; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public String getDomainId() { return domainId; }
    public void setDomainId(String domainId) { this.domainId = domainId; }
    public String getDomainSlug() { return domainSlug; }
    public void setDomainSlug(String domainSlug) { this.domainSlug = domainSlug; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getLastScaffoldedAt() { return lastScaffoldedAt; }
    public void setLastScaffoldedAt(Instant lastScaffoldedAt) { this.lastScaffoldedAt = lastScaffoldedAt; }
    public String getLastScaffoldedByUserId() { return lastScaffoldedByUserId; }
    public void setLastScaffoldedByUserId(String lastScaffoldedByUserId) { this.lastScaffoldedByUserId = lastScaffoldedByUserId; }
    public String getLastCommitSha() { return lastCommitSha; }
    public void setLastCommitSha(String lastCommitSha) { this.lastCommitSha = lastCommitSha; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
