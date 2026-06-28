package com.pulse.broker.mirror;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "remote_target_runtime_mirror")
public class RemoteTargetRuntimeMirror extends BaseEntity {
    @Column(name = "trust_binding_id", nullable = false)
    private String trustBindingId;
    @Column(name = "local_tenant_id", nullable = false)
    private String localTenantId;
    @Column(name = "federated_tenant_key", nullable = false)
    private String federatedTenantKey;
    @Column(name = "remote_target_ref", nullable = false)
    private String remoteTargetRef;
    @Column(nullable = false)
    private String environment;
    @Column(name = "peer_logical_dag_id", nullable = false)
    private String peerLogicalDagId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_schema")
    private Map<String, Object> payloadSchema = Map.of();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_payload_keys")
    private List<String> allowedPayloadKeys = List.of();
    @Column(name = "completion_event")
    private String completionEvent;
    @Column(name = "deployment_status")
    private String deploymentStatus;
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
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getPeerLogicalDagId() { return peerLogicalDagId; }
    public void setPeerLogicalDagId(String peerLogicalDagId) { this.peerLogicalDagId = peerLogicalDagId; }
    public Map<String, Object> getPayloadSchema() { return payloadSchema; }
    public void setPayloadSchema(Map<String, Object> payloadSchema) { this.payloadSchema = payloadSchema; }
    public List<String> getAllowedPayloadKeys() { return allowedPayloadKeys; }
    public void setAllowedPayloadKeys(List<String> allowedPayloadKeys) { this.allowedPayloadKeys = allowedPayloadKeys; }
    public String getCompletionEvent() { return completionEvent; }
    public void setCompletionEvent(String completionEvent) { this.completionEvent = completionEvent; }
    public String getDeploymentStatus() { return deploymentStatus; }
    public void setDeploymentStatus(String deploymentStatus) { this.deploymentStatus = deploymentStatus; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public String getSignedResponseJwsSha256() { return signedResponseJwsSha256; }
    public void setSignedResponseJwsSha256(String signedResponseJwsSha256) { this.signedResponseJwsSha256 = signedResponseJwsSha256; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
