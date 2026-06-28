package com.pulse.broker.mirror;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "remote_target_catalog_mirror")
public class RemoteTargetCatalogMirror extends BaseEntity {
    @Column(name = "trust_binding_id", nullable = false)
    private String trustBindingId;
    @Column(name = "local_tenant_id", nullable = false)
    private String localTenantId;
    @Column(name = "federated_tenant_key", nullable = false)
    private String federatedTenantKey;
    @Column(name = "remote_target_ref", nullable = false)
    private String remoteTargetRef;
    @Column(nullable = false)
    private String slug;
    @Column(name = "display_name", nullable = false)
    private String displayName;
    private String description;
    @Column(nullable = false)
    private String status;
    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;
    @Column(name = "signed_response_jws_sha256")
    private String signedResponseJwsSha256;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload = Map.of();

    public String getTrustBindingId() { return trustBindingId; }
    public void setTrustBindingId(String trustBindingId) { this.trustBindingId = trustBindingId; }
    public String getLocalTenantId() { return localTenantId; }
    public void setLocalTenantId(String localTenantId) { this.localTenantId = localTenantId; }
    public String getFederatedTenantKey() { return federatedTenantKey; }
    public void setFederatedTenantKey(String federatedTenantKey) { this.federatedTenantKey = federatedTenantKey; }
    public String getRemoteTargetRef() { return remoteTargetRef; }
    public void setRemoteTargetRef(String remoteTargetRef) { this.remoteTargetRef = remoteTargetRef; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public String getSignedResponseJwsSha256() { return signedResponseJwsSha256; }
    public void setSignedResponseJwsSha256(String signedResponseJwsSha256) {
        this.signedResponseJwsSha256 = signedResponseJwsSha256;
    }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
