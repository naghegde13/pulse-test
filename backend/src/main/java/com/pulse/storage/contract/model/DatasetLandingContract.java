package com.pulse.storage.contract.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "dataset_landing_contracts")
public class DatasetLandingContract extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "domain_id", nullable = false)
    private String domainId;

    @Column(name = "domain_slug", nullable = false)
    private String domainSlug;

    @Column(name = "sor_id", nullable = false)
    private String sorId;

    @Column(name = "sor_slug", nullable = false)
    private String sorSlug;

    @Column(name = "dataset_id", nullable = false)
    private String datasetId;

    @Column(name = "dataset_slug", nullable = false)
    private String datasetSlug;

    @Column(name = "contract_version", nullable = false)
    private int contractVersion = 1;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "root_kind", nullable = false)
    private String rootKind = "files";

    @Column(name = "relative_landing_path", nullable = false)
    private String relativeLandingPath;

    @Column(name = "arrival_partition_template")
    private String arrivalPartitionTemplate;

    @Column(name = "rejected_relative_path")
    private String rejectedRelativePath;

    @Column(name = "archive_relative_path")
    private String archiveRelativePath;

    @Column(name = "outgoing_relative_path")
    private String outgoingRelativePath;

    @Column(name = "first_arrival_at")
    private Instant firstArrivalAt;

    @Column(name = "first_arrival_event_id")
    private String firstArrivalEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provenance", columnDefinition = "jsonb")
    private Map<String, Object> provenance;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDomainId() { return domainId; }
    public void setDomainId(String domainId) { this.domainId = domainId; }

    public String getDomainSlug() { return domainSlug; }
    public void setDomainSlug(String domainSlug) { this.domainSlug = domainSlug; }

    public String getSorId() { return sorId; }
    public void setSorId(String sorId) { this.sorId = sorId; }

    public String getSorSlug() { return sorSlug; }
    public void setSorSlug(String sorSlug) { this.sorSlug = sorSlug; }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }

    public String getDatasetSlug() { return datasetSlug; }
    public void setDatasetSlug(String datasetSlug) { this.datasetSlug = datasetSlug; }

    public int getContractVersion() { return contractVersion; }
    public void setContractVersion(int contractVersion) { this.contractVersion = contractVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRootKind() { return rootKind; }
    public void setRootKind(String rootKind) { this.rootKind = rootKind; }

    public String getRelativeLandingPath() { return relativeLandingPath; }
    public void setRelativeLandingPath(String relativeLandingPath) { this.relativeLandingPath = relativeLandingPath; }

    public String getArrivalPartitionTemplate() { return arrivalPartitionTemplate; }
    public void setArrivalPartitionTemplate(String arrivalPartitionTemplate) { this.arrivalPartitionTemplate = arrivalPartitionTemplate; }

    public String getRejectedRelativePath() { return rejectedRelativePath; }
    public void setRejectedRelativePath(String rejectedRelativePath) { this.rejectedRelativePath = rejectedRelativePath; }

    public String getArchiveRelativePath() { return archiveRelativePath; }
    public void setArchiveRelativePath(String archiveRelativePath) { this.archiveRelativePath = archiveRelativePath; }

    public String getOutgoingRelativePath() { return outgoingRelativePath; }
    public void setOutgoingRelativePath(String outgoingRelativePath) { this.outgoingRelativePath = outgoingRelativePath; }

    public Instant getFirstArrivalAt() { return firstArrivalAt; }
    public void setFirstArrivalAt(Instant firstArrivalAt) { this.firstArrivalAt = firstArrivalAt; }

    public String getFirstArrivalEventId() { return firstArrivalEventId; }
    public void setFirstArrivalEventId(String firstArrivalEventId) { this.firstArrivalEventId = firstArrivalEventId; }

    public Map<String, Object> getProvenance() { return provenance; }
    public void setProvenance(Map<String, Object> provenance) { this.provenance = provenance; }
}
