package com.pulse.storage.contract.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

@Entity
@Table(name = "table_contracts")
public class TableContract extends BaseEntity {

    @Column(name = "pipeline_id", nullable = false)
    private String pipelineId;

    @Column(name = "version_id", nullable = false)
    private String versionId;

    @Column(name = "producing_instance_id", nullable = false)
    private String producingInstanceId;

    @Column(name = "output_port_name", nullable = false)
    private String outputPortName = "main_output";

    @Column(name = "domain_id", nullable = false)
    private String domainId;

    @Column(name = "domain_slug", nullable = false)
    private String domainSlug;

    @Column(name = "layer", nullable = false)
    private String layer;

    @Column(name = "table_role", nullable = false)
    private String tableRole;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "table_slug", nullable = false)
    private String tableSlug;

    @Column(name = "logical_table_id", nullable = false)
    private String logicalTableId;

    @Column(name = "source_sor_id")
    private String sourceSorId;

    @Column(name = "source_sor_slug")
    private String sourceSorSlug;

    @Column(name = "source_dataset_id")
    private String sourceDatasetId;

    @Column(name = "source_dataset_slug")
    private String sourceDatasetSlug;

    @Column(name = "table_format", nullable = false)
    private String tableFormat;

    @Column(name = "catalog_kind", nullable = false)
    private String catalogKind;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    @Column(name = "catalog_table_name", nullable = false)
    private String catalogTableName;

    @Column(name = "relative_storage_path", nullable = false)
    private String relativeStoragePath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "partition_spec", columnDefinition = "jsonb")
    private Map<String, Object> partitionSpec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout_spec", columnDefinition = "jsonb")
    private Map<String, Object> layoutSpec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "primary_key_columns", columnDefinition = "jsonb")
    private List<String> primaryKeyColumns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_date_columns", columnDefinition = "jsonb")
    private List<String> businessDateColumns;

    @Column(name = "write_mode", nullable = false)
    private String writeMode;

    @Column(name = "ddl_strategy", nullable = false)
    private String ddlStrategy;

    @Column(name = "writer_owner", nullable = false)
    private String writerOwner;

    @Column(name = "contract_version", nullable = false)
    private int contractVersion = 1;

    @Column(name = "status", nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provenance", columnDefinition = "jsonb")
    private Map<String, Object> provenance;

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }

    public String getProducingInstanceId() { return producingInstanceId; }
    public void setProducingInstanceId(String producingInstanceId) { this.producingInstanceId = producingInstanceId; }

    public String getOutputPortName() { return outputPortName; }
    public void setOutputPortName(String outputPortName) { this.outputPortName = outputPortName; }

    public String getDomainId() { return domainId; }
    public void setDomainId(String domainId) { this.domainId = domainId; }

    public String getDomainSlug() { return domainSlug; }
    public void setDomainSlug(String domainSlug) { this.domainSlug = domainSlug; }

    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }

    public String getTableRole() { return tableRole; }
    public void setTableRole(String tableRole) { this.tableRole = tableRole; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getTableSlug() { return tableSlug; }
    public void setTableSlug(String tableSlug) { this.tableSlug = tableSlug; }

    public String getLogicalTableId() { return logicalTableId; }
    public void setLogicalTableId(String logicalTableId) { this.logicalTableId = logicalTableId; }

    public String getSourceSorId() { return sourceSorId; }
    public void setSourceSorId(String sourceSorId) { this.sourceSorId = sourceSorId; }

    public String getSourceSorSlug() { return sourceSorSlug; }
    public void setSourceSorSlug(String sourceSorSlug) { this.sourceSorSlug = sourceSorSlug; }

    public String getSourceDatasetId() { return sourceDatasetId; }
    public void setSourceDatasetId(String sourceDatasetId) { this.sourceDatasetId = sourceDatasetId; }

    public String getSourceDatasetSlug() { return sourceDatasetSlug; }
    public void setSourceDatasetSlug(String sourceDatasetSlug) { this.sourceDatasetSlug = sourceDatasetSlug; }

    public String getTableFormat() { return tableFormat; }
    public void setTableFormat(String tableFormat) { this.tableFormat = tableFormat; }

    public String getCatalogKind() { return catalogKind; }
    public void setCatalogKind(String catalogKind) { this.catalogKind = catalogKind; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public String getCatalogTableName() { return catalogTableName; }
    public void setCatalogTableName(String catalogTableName) { this.catalogTableName = catalogTableName; }

    public String getRelativeStoragePath() { return relativeStoragePath; }
    public void setRelativeStoragePath(String relativeStoragePath) { this.relativeStoragePath = relativeStoragePath; }

    public Map<String, Object> getPartitionSpec() { return partitionSpec; }
    public void setPartitionSpec(Map<String, Object> partitionSpec) { this.partitionSpec = partitionSpec; }

    public Map<String, Object> getLayoutSpec() { return layoutSpec; }
    public void setLayoutSpec(Map<String, Object> layoutSpec) { this.layoutSpec = layoutSpec; }

    public List<String> getPrimaryKeyColumns() { return primaryKeyColumns; }
    public void setPrimaryKeyColumns(List<String> primaryKeyColumns) { this.primaryKeyColumns = primaryKeyColumns; }

    public List<String> getBusinessDateColumns() { return businessDateColumns; }
    public void setBusinessDateColumns(List<String> businessDateColumns) { this.businessDateColumns = businessDateColumns; }

    public String getWriteMode() { return writeMode; }
    public void setWriteMode(String writeMode) { this.writeMode = writeMode; }

    public String getDdlStrategy() { return ddlStrategy; }
    public void setDdlStrategy(String ddlStrategy) { this.ddlStrategy = ddlStrategy; }

    public String getWriterOwner() { return writerOwner; }
    public void setWriterOwner(String writerOwner) { this.writerOwner = writerOwner; }

    public int getContractVersion() { return contractVersion; }
    public void setContractVersion(int contractVersion) { this.contractVersion = contractVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getProvenance() { return provenance; }
    public void setProvenance(Map<String, Object> provenance) { this.provenance = provenance; }
}
