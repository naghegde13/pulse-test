package com.pulse.pipeline.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

@Entity
@Table(name = "sub_pipeline_instances")
public class SubPipelineInstance extends BaseEntity {

    @Column(name = "pipeline_id", nullable = false)
    private String pipelineId;

    @Column(name = "version_id")
    private String versionId;

    @Column(name = "blueprint_id", nullable = false)
    private String blueprintId;

    @Column(name = "blueprint_key")
    private String blueprintKey;

    @Column(name = "blueprint_version", nullable = false)
    private String blueprintVersion;

    @Column(nullable = false)
    private String name;

    @Column(name = "execution_order", nullable = false)
    private int executionOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_datasets", columnDefinition = "jsonb")
    private List<Map<String, Object>> inputDatasets;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_datasets", columnDefinition = "jsonb")
    private List<Map<String, Object>> outputDatasets;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_schema", columnDefinition = "jsonb")
    private Map<String, Object> outputSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dq_expectations", columnDefinition = "jsonb")
    private List<Map<String, Object>> dqExpectations;

    @Column(name = "schema_status", nullable = false)
    private String schemaStatus = "unknown";

    /** #30 storage convention: 'DPC' | 'GCP'. Resolved via storage_backends
     * (tenant_id, environment, backend) lookup at codegen / deploy time. */
    @Column(name = "storage_backend", nullable = false)
    private String storageBackend = "DPC";

    /** #30: 'bronze' | 'silver' | 'gold' | null for non-table blueprints. */
    @Column(name = "lake_layer")
    private String lakeLayer;

    /** #30: 'delta' | 'iceberg_external' | 'iceberg_bq_managed' | 'bq_native' | 'parquet'.
     * DB-enforced gold-on-GCP rule lives on this column via the
     * chk_sub_pipeline_instances_gold_gcp_must_be_bq_native CHECK from V96. */
    @Column(name = "lake_format")
    private String lakeFormat;

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getBlueprintId() { return blueprintId; }
    public void setBlueprintId(String blueprintId) { this.blueprintId = blueprintId; }
    public String getBlueprintKey() { return blueprintKey; }
    public void setBlueprintKey(String blueprintKey) { this.blueprintKey = blueprintKey; }
    public String getBlueprintVersion() { return blueprintVersion; }
    public void setBlueprintVersion(String blueprintVersion) { this.blueprintVersion = blueprintVersion; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getExecutionOrder() { return executionOrder; }
    public void setExecutionOrder(int executionOrder) { this.executionOrder = executionOrder; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public List<Map<String, Object>> getInputDatasets() { return inputDatasets; }
    public void setInputDatasets(List<Map<String, Object>> inputDatasets) { this.inputDatasets = inputDatasets; }
    public List<Map<String, Object>> getOutputDatasets() { return outputDatasets; }
    public void setOutputDatasets(List<Map<String, Object>> outputDatasets) { this.outputDatasets = outputDatasets; }
    public Map<String, Object> getOutputSchema() { return outputSchema; }
    public void setOutputSchema(Map<String, Object> outputSchema) { this.outputSchema = outputSchema; }
    public List<Map<String, Object>> getDqExpectations() { return dqExpectations; }
    public void setDqExpectations(List<Map<String, Object>> dqExpectations) { this.dqExpectations = dqExpectations; }
    public String getSchemaStatus() { return schemaStatus; }
    public void setSchemaStatus(String schemaStatus) { this.schemaStatus = schemaStatus; }

    public String getStorageBackend() { return storageBackend; }
    public void setStorageBackend(String storageBackend) { this.storageBackend = storageBackend; }

    public String getLakeLayer() { return lakeLayer; }
    public void setLakeLayer(String lakeLayer) { this.lakeLayer = lakeLayer; }

    public String getLakeFormat() { return lakeFormat; }
    public void setLakeFormat(String lakeFormat) { this.lakeFormat = lakeFormat; }
}
