package com.pulse.runtime.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * PKT-FINAL-5 / BUG-39: Runtime bindings are deployment-global, not
 * per-tenant. The {@code tenant_id} column was dropped in V147 (Option A1)
 * because the active runtime persona governs the entire PULSE deployment
 * — every tenant under one deployment shares the same bindings.
 */
@Entity
@Table(name = "runtime_bindings")
public class RuntimeBinding extends BaseEntity {

    @Column(name = "environment", nullable = false)
    private String environment;

    @Column(name = "binding_kind", nullable = false)
    private String bindingKind;

    @Column(name = "settings_role", nullable = false)
    private String settingsRole = "PRIMARY";

    @Column(name = "record_state", nullable = false)
    private String recordState = "ACTIVE";

    @Column(name = "validation_status", nullable = false)
    private String validationStatus = "PENDING";

    /**
     * PKT-0014: Kind of validation last performed.
     * STUB = static/local check (cannot claim live-GCP readiness).
     * LIVE_GCP = live GCS bucket probe. LIVE_HDFS = live HDFS probe.
     */
    @Column(name = "validation_kind", nullable = false)
    private String validationKind = "STUB";

    @Column(name = "storage_root_files")
    private String storageRootFiles;

    @Column(name = "storage_root_lake")
    private String storageRootLake;

    @Column(name = "storage_root_ops")
    private String storageRootOps;

    @Column(name = "diagnostic_reason")
    private String diagnosticReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diagnostic_details", columnDefinition = "jsonb")
    private Map<String, Object> diagnosticDetails;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(name = "validation_error")
    private String validationError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_evidence", columnDefinition = "jsonb")
    private Map<String, Object> sourceEvidence;

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getBindingKind() { return bindingKind; }
    public void setBindingKind(String bindingKind) { this.bindingKind = bindingKind; }

    public String getSettingsRole() { return settingsRole; }
    public void setSettingsRole(String settingsRole) { this.settingsRole = settingsRole; }

    public String getRecordState() { return recordState; }
    public void setRecordState(String recordState) { this.recordState = recordState; }

    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }

    public String getValidationKind() { return validationKind; }
    public void setValidationKind(String validationKind) { this.validationKind = validationKind; }

    public String getStorageRootFiles() { return storageRootFiles; }
    public void setStorageRootFiles(String storageRootFiles) { this.storageRootFiles = storageRootFiles; }

    public String getStorageRootLake() { return storageRootLake; }
    public void setStorageRootLake(String storageRootLake) { this.storageRootLake = storageRootLake; }

    public String getStorageRootOps() { return storageRootOps; }
    public void setStorageRootOps(String storageRootOps) { this.storageRootOps = storageRootOps; }

    public String getDiagnosticReason() { return diagnosticReason; }
    public void setDiagnosticReason(String diagnosticReason) { this.diagnosticReason = diagnosticReason; }

    public Map<String, Object> getDiagnosticDetails() { return diagnosticDetails; }
    public void setDiagnosticDetails(Map<String, Object> diagnosticDetails) { this.diagnosticDetails = diagnosticDetails; }

    public Instant getValidatedAt() { return validatedAt; }
    public void setValidatedAt(Instant validatedAt) { this.validatedAt = validatedAt; }

    public String getValidationError() { return validationError; }
    public void setValidationError(String validationError) { this.validationError = validationError; }

    public Map<String, Object> getSourceEvidence() { return sourceEvidence; }
    public void setSourceEvidence(Map<String, Object> sourceEvidence) { this.sourceEvidence = sourceEvidence; }

    public boolean isPrimary() { return "PRIMARY".equals(settingsRole); }
    public boolean isDiagnostic() { return "DIAGNOSTIC".equals(settingsRole); }
    public boolean isActive() { return "ACTIVE".equals(recordState); }
    public boolean isValidated() { return "VALIDATED".equals(validationStatus); }

    public boolean hasCompleteRoots() {
        return storageRootFiles != null && !storageRootFiles.isBlank()
                && storageRootLake != null && !storageRootLake.isBlank()
                && storageRootOps != null && !storageRootOps.isBlank();
    }
}
