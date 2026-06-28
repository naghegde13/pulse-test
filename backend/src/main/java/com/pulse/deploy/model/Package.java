package com.pulse.deploy.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "packages")
public class Package extends BaseEntity {

    @Column(name = "pipeline_id", nullable = false)
    private String pipelineId;

    @Column(name = "version_id", nullable = false)
    private String versionId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "package_type", nullable = false)
    private String packageType = "DOCKER";

    @Column(name = "artifact_url")
    private String artifactUrl;

    @Column(name = "artifact_hash")
    private String artifactHash;

    @Column(name = "build_status", nullable = false)
    private String buildStatus = "PENDING";

    @Column(name = "built_by", nullable = false)
    private String builtBy;

    @Column(name = "build_log", columnDefinition = "TEXT")
    private String buildLog;

    @Column(name = "built_at")
    private Instant builtAt;

    @Column(name = "source_kind")
    private String sourceKind;

    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(name = "commit_sha")
    private String commitSha;

    @Column(name = "tree_sha")
    private String treeSha;

    @Column(name = "package_artifact_uri")
    private String packageArtifactUri;

    @Column(name = "package_artifact_sha256")
    private String packageArtifactSha256;

    @Column(name = "package_manifest_hash")
    private String packageManifestHash;

    @Column(name = "promotable", nullable = false)
    private boolean promotable = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPackageType() { return packageType; }
    public void setPackageType(String packageType) { this.packageType = packageType; }
    public String getArtifactUrl() { return artifactUrl; }
    public void setArtifactUrl(String artifactUrl) { this.artifactUrl = artifactUrl; }
    public String getArtifactHash() { return artifactHash; }
    public void setArtifactHash(String artifactHash) { this.artifactHash = artifactHash; }
    public String getBuildStatus() { return buildStatus; }
    public void setBuildStatus(String buildStatus) { this.buildStatus = buildStatus; }
    public String getBuiltBy() { return builtBy; }
    public void setBuiltBy(String builtBy) { this.builtBy = builtBy; }
    public String getBuildLog() { return buildLog; }
    public void setBuildLog(String buildLog) { this.buildLog = buildLog; }
    public Instant getBuiltAt() { return builtAt; }
    public void setBuiltAt(Instant builtAt) { this.builtAt = builtAt; }
    public String getSourceKind() { return sourceKind; }
    public void setSourceKind(String sourceKind) { this.sourceKind = sourceKind; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public String getTreeSha() { return treeSha; }
    public void setTreeSha(String treeSha) { this.treeSha = treeSha; }
    public String getPackageArtifactUri() { return packageArtifactUri; }
    public void setPackageArtifactUri(String packageArtifactUri) { this.packageArtifactUri = packageArtifactUri; }
    public String getPackageArtifactSha256() { return packageArtifactSha256; }
    public void setPackageArtifactSha256(String packageArtifactSha256) { this.packageArtifactSha256 = packageArtifactSha256; }
    public String getPackageManifestHash() { return packageManifestHash; }
    public void setPackageManifestHash(String packageManifestHash) { this.packageManifestHash = packageManifestHash; }
    public boolean isPromotable() { return promotable; }
    public void setPromotable(boolean promotable) { this.promotable = promotable; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
