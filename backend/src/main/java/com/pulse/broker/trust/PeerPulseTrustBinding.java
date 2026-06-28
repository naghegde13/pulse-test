package com.pulse.broker.trust;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "remote_airflow_trust_bindings")
public class PeerPulseTrustBinding extends BaseEntity {

    @Column(name = "local_tenant_id", nullable = false)
    private String localTenantId;
    @Column(nullable = false)
    private String environment;
    @Column(name = "invoker_persona", nullable = false)
    private String invokerPersona;
    @Column(name = "target_owner_persona", nullable = false)
    private String targetOwnerPersona;
    @Column(name = "federated_tenant_key", nullable = false)
    private String federatedTenantKey;
    @Column(name = "airflow_base_url", nullable = false)
    private String airflowBaseUrl;
    @Column(nullable = false)
    private String issuer;
    @Column(nullable = false)
    private String audience;
    @Column(name = "jwks_uri")
    private String jwksUri;
    @Column(name = "inbound_shared_secret_ref")
    private String inboundSharedSecretRef;
    @Column(name = "outbound_secret_ref")
    private String outboundSecretRef;
    @Column(nullable = false)
    private String status = "UNVALIDATED";
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capability_snapshot")
    private Map<String, Object> capabilitySnapshot = Map.of();
    @Column(name = "validated_at")
    private Instant validatedAt;
    @Column(name = "validation_error")
    private String validationError;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata = Map.of();

    public String getLocalTenantId() { return localTenantId; }
    public void setLocalTenantId(String localTenantId) { this.localTenantId = localTenantId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getInvokerPersona() { return invokerPersona; }
    public void setInvokerPersona(String invokerPersona) { this.invokerPersona = invokerPersona; }
    public String getTargetOwnerPersona() { return targetOwnerPersona; }
    public void setTargetOwnerPersona(String targetOwnerPersona) { this.targetOwnerPersona = targetOwnerPersona; }
    public String getFederatedTenantKey() { return federatedTenantKey; }
    public void setFederatedTenantKey(String federatedTenantKey) { this.federatedTenantKey = federatedTenantKey; }
    public String getAirflowBaseUrl() { return airflowBaseUrl; }
    public void setAirflowBaseUrl(String airflowBaseUrl) { this.airflowBaseUrl = airflowBaseUrl; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getJwksUri() { return jwksUri; }
    public void setJwksUri(String jwksUri) { this.jwksUri = jwksUri; }
    public String getInboundSharedSecretRef() { return inboundSharedSecretRef; }
    public void setInboundSharedSecretRef(String inboundSharedSecretRef) {
        this.inboundSharedSecretRef = inboundSharedSecretRef;
    }
    public String getOutboundSecretRef() { return outboundSecretRef; }
    public void setOutboundSecretRef(String outboundSecretRef) { this.outboundSecretRef = outboundSecretRef; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<String, Object> getCapabilitySnapshot() { return capabilitySnapshot; }
    public void setCapabilitySnapshot(Map<String, Object> capabilitySnapshot) {
        this.capabilitySnapshot = capabilitySnapshot;
    }
    public Instant getValidatedAt() { return validatedAt; }
    public void setValidatedAt(Instant validatedAt) { this.validatedAt = validatedAt; }
    public String getValidationError() { return validationError; }
    public void setValidationError(String validationError) { this.validationError = validationError; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
