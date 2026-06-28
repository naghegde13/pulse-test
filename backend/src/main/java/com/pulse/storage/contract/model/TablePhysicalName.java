package com.pulse.storage.contract.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Authoritative physical table name registry.
 *
 * <p>Enforces uniqueness per (tenant, catalog_kind, schema_name,
 * physical_table_name) so two table contracts cannot claim the same
 * physical table name within a catalog. The unique index filters on
 * {@code status = 'active'} so superseded/retired names can be reused.
 *
 * <p>An optional {@code shared_group} allows multiple contracts to
 * share a physical table name when they belong to the same logical
 * group (e.g. multi-output blueprints writing to a shared table).
 */
@Entity
@Table(name = "table_physical_names")
public class TablePhysicalName extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "table_contract_id", nullable = false)
    private String tableContractId;

    @Column(name = "catalog_kind", nullable = false)
    private String catalogKind;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    @Column(name = "physical_table_name", nullable = false)
    private String physicalTableName;

    @Column(name = "layer", nullable = false)
    private String layer;

    @Column(name = "domain_slug")
    private String domainSlug;

    @Column(name = "pipeline_id")
    private String pipelineId;

    @Column(name = "version_id")
    private String versionId;

    @Column(name = "shared_group")
    private String sharedGroup;

    @Column(name = "status", nullable = false)
    private String status = "active";

    // --- getters and setters ---

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getTableContractId() { return tableContractId; }
    public void setTableContractId(String tableContractId) { this.tableContractId = tableContractId; }

    public String getCatalogKind() { return catalogKind; }
    public void setCatalogKind(String catalogKind) { this.catalogKind = catalogKind; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public String getPhysicalTableName() { return physicalTableName; }
    public void setPhysicalTableName(String physicalTableName) { this.physicalTableName = physicalTableName; }

    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }

    public String getDomainSlug() { return domainSlug; }
    public void setDomainSlug(String domainSlug) { this.domainSlug = domainSlug; }

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }

    public String getSharedGroup() { return sharedGroup; }
    public void setSharedGroup(String sharedGroup) { this.sharedGroup = sharedGroup; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
