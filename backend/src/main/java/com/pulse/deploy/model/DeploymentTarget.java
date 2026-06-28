package com.pulse.deploy.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "deployment_targets")
public class DeploymentTarget extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String environment;

    /**
     * Phase 7 — canonical adapter key. Default is
     * {@code LOCAL_MATERIALIZATION} (replaces the legacy
     * {@code KUBERNETES} default). Migration {@code V106} rewrites
     * any pre-existing {@code KUBERNETES} rows.
     */
    @Column(name = "target_type", nullable = false)
    private String targetType = "LOCAL_MATERIALIZATION";

    @Column(name = "endpoint_url")
    private String endpointUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(nullable = false)
    private boolean enabled = true;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getEndpointUrl() { return endpointUrl; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * Phase 7 — boundary normalizer for {@code target_type}. Accepts
     * the canonical Phase 7 adapter keys plus legacy {@code KUBERNETES}
     * (alias for {@code LOCAL_MATERIALIZATION}, mirroring the V106
     * migration). Unknown values are returned unchanged so the V106
     * check constraint surfaces them on insert/update.
     */
    public static String normalizeTargetType(String raw) {
        if (raw == null || raw.isBlank()) return "LOCAL_MATERIALIZATION";
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (upper) {
            case "KUBERNETES" -> "LOCAL_MATERIALIZATION";
            default -> upper;
        };
    }
}
