package com.pulse.storage.contract.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * First-arrival ledger entry proving data has landed at a contracted path.
 *
 * <p>Once the first arrival event is recorded for a landing contract, the
 * contract's relative landing path becomes immutable — display-name changes
 * on the dataset or SOR do not mutate the path once data has landed.
 *
 * <p>The unique constraint (landing_contract_id, ingest_date, arrival_id)
 * prevents duplicate arrivals for the same partition.
 */
@Entity
@Table(name = "dataset_arrival_events")
public class DatasetArrivalEvent extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "dataset_id", nullable = false)
    private String datasetId;

    @Column(name = "landing_contract_id", nullable = false)
    private String landingContractId;

    @Column(name = "contract_version", nullable = false)
    private int contractVersion;

    @Column(name = "arrival_path", nullable = false, length = 1000)
    private String arrivalPath;

    @Column(name = "ingest_date", nullable = false)
    private String ingestDate;

    @Column(name = "arrival_id", nullable = false)
    private String arrivalId;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "total_bytes", nullable = false)
    private long totalBytes;

    @Column(name = "source_system")
    private String sourceSystem;

    @Column(name = "status", nullable = false)
    private String status = "recorded";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provenance", columnDefinition = "jsonb")
    private Map<String, Object> provenance;

    // --- getters and setters ---

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }

    public String getLandingContractId() { return landingContractId; }
    public void setLandingContractId(String landingContractId) { this.landingContractId = landingContractId; }

    public int getContractVersion() { return contractVersion; }
    public void setContractVersion(int contractVersion) { this.contractVersion = contractVersion; }

    public String getArrivalPath() { return arrivalPath; }
    public void setArrivalPath(String arrivalPath) { this.arrivalPath = arrivalPath; }

    public String getIngestDate() { return ingestDate; }
    public void setIngestDate(String ingestDate) { this.ingestDate = ingestDate; }

    public String getArrivalId() { return arrivalId; }
    public void setArrivalId(String arrivalId) { this.arrivalId = arrivalId; }

    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }

    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getProvenance() { return provenance; }
    public void setProvenance(Map<String, Object> provenance) { this.provenance = provenance; }
}
