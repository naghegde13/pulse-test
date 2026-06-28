package com.pulse.git.workspace;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "workspace_file_manifests")
public class WorkspaceFileManifest extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String path;

    @Column(name = "source_artifact_id")
    private String sourceArtifactId;

    @Column(name = "last_materialized_sha256")
    private String lastMaterializedSha256;

    @Column(name = "current_workspace_sha256")
    private String currentWorkspaceSha256;

    @Column(name = "last_committed_sha256")
    private String lastCommittedSha256;

    @Column(name = "managed_by_pulse", nullable = false)
    private boolean managedByPulse = true;

    @Column(name = "path_scope", nullable = false)
    private String pathScope = "PIPELINE";

    @Column(name = "ownership_key")
    private String ownershipKey;

    @Column(name = "last_materialized_at")
    private Instant lastMaterializedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getSourceArtifactId() { return sourceArtifactId; }
    public void setSourceArtifactId(String sourceArtifactId) { this.sourceArtifactId = sourceArtifactId; }
    public String getLastMaterializedSha256() { return lastMaterializedSha256; }
    public void setLastMaterializedSha256(String lastMaterializedSha256) { this.lastMaterializedSha256 = lastMaterializedSha256; }
    public String getCurrentWorkspaceSha256() { return currentWorkspaceSha256; }
    public void setCurrentWorkspaceSha256(String currentWorkspaceSha256) { this.currentWorkspaceSha256 = currentWorkspaceSha256; }
    public String getLastCommittedSha256() { return lastCommittedSha256; }
    public void setLastCommittedSha256(String lastCommittedSha256) { this.lastCommittedSha256 = lastCommittedSha256; }
    public boolean isManagedByPulse() { return managedByPulse; }
    public void setManagedByPulse(boolean managedByPulse) { this.managedByPulse = managedByPulse; }
    public String getPathScope() { return pathScope; }
    public void setPathScope(String pathScope) { this.pathScope = pathScope; }
    public String getOwnershipKey() { return ownershipKey; }
    public void setOwnershipKey(String ownershipKey) { this.ownershipKey = ownershipKey; }
    public Instant getLastMaterializedAt() { return lastMaterializedAt; }
    public void setLastMaterializedAt(Instant lastMaterializedAt) { this.lastMaterializedAt = lastMaterializedAt; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
