package com.pulse.cobol.model;

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
@Table(name = "cobol_discovery_runs")
public class CobolDiscoveryRun extends BaseEntity {

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "run_type", nullable = false)
    private String runType;

    @Column(nullable = false)
    private String status = "QUEUED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> configSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profiling_summary", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> profilingSummary;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "anomaly_summary", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> anomalySummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sample_policy", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> samplePolicy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_schema_snapshot", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> resultSchemaSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preview_rows", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> previewRows;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mapping_spec", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> mappingSpec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_log", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> eventLog;

    @Column(name = "cleanup_status", nullable = false)
    private String cleanupStatus = "ACTIVE";

    @Column(name = "expires_at")
    private Instant expiresAt;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getRunType() { return runType; }
    public void setRunType(String runType) { this.runType = runType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getConfigSnapshot() { return configSnapshot; }
    public void setConfigSnapshot(Map<String, Object> configSnapshot) { this.configSnapshot = configSnapshot; }

    public Map<String, Object> getProfilingSummary() { return profilingSummary; }
    public void setProfilingSummary(Map<String, Object> profilingSummary) { this.profilingSummary = profilingSummary; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public Map<String, Object> getAnomalySummary() { return anomalySummary; }
    public void setAnomalySummary(Map<String, Object> anomalySummary) { this.anomalySummary = anomalySummary; }

    public Map<String, Object> getSamplePolicy() { return samplePolicy; }
    public void setSamplePolicy(Map<String, Object> samplePolicy) { this.samplePolicy = samplePolicy; }

    public Map<String, Object> getResultSchemaSnapshot() { return resultSchemaSnapshot; }
    public void setResultSchemaSnapshot(Map<String, Object> resultSchemaSnapshot) { this.resultSchemaSnapshot = resultSchemaSnapshot; }

    public List<Map<String, Object>> getPreviewRows() { return previewRows; }
    public void setPreviewRows(List<Map<String, Object>> previewRows) { this.previewRows = previewRows; }

    public List<Map<String, Object>> getMappingSpec() { return mappingSpec; }
    public void setMappingSpec(List<Map<String, Object>> mappingSpec) { this.mappingSpec = mappingSpec; }

    public List<Map<String, Object>> getEventLog() { return eventLog; }
    public void setEventLog(List<Map<String, Object>> eventLog) { this.eventLog = eventLog; }

    public String getCleanupStatus() { return cleanupStatus; }
    public void setCleanupStatus(String cleanupStatus) { this.cleanupStatus = cleanupStatus; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
