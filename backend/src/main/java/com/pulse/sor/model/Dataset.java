package com.pulse.sor.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "datasets")
public class Dataset extends BaseEntity {

    @Column(name = "connector_instance_id")
    private String connectorInstanceId;

    @Column(name = "sor_id")
    private String sorId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "qualified_name", nullable = false, unique = true)
    private String qualifiedName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> schemaSnapshot;

    @Column(name = "schema_format")
    private String schemaFormat;

    @Column
    private String classification;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "freshness_sla", columnDefinition = "jsonb")
    private Map<String, Object> freshnessSla;

    @Column(name = "definition_type")
    private String definitionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition_config", columnDefinition = "jsonb")
    private Map<String, Object> definitionConfig;

    @Column(name = "custom_sql", columnDefinition = "TEXT")
    private String customSql;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_tables", columnDefinition = "jsonb")
    private java.util.List<String> sourceTables;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "api_spec", columnDefinition = "jsonb")
    private Map<String, Object> apiSpec;

    @Column
    private String description;

    @Column
    private String status;

    @Column(name = "time_grain")
    private String timeGrain;

    @Column(name = "file_naming_pattern")
    private String fileNamingPattern;

    /**
     * Source of the per-row processing_datetime audit value (V101).
     * Values: 'filename_segment' | 'file_arrival_time' | 'airflow_run_time'.
     * Default: 'airflow_run_time'.
     */
    @Column(name = "processing_datetime_source", nullable = false)
    private String processingDatetimeSource = "airflow_run_time";

    @Column(name = "arrival_cron")
    private String arrivalCron;

    @Column(name = "arrival_timezone")
    private String arrivalTimezone;

    @Column(name = "sensing_strategy")
    private String sensingStrategy;

    @Column(name = "readiness_query", columnDefinition = "TEXT")
    private String readinessQuery;

    @Column(name = "readiness_connection_id")
    private String readinessConnectionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sensor_config", columnDefinition = "jsonb")
    private Map<String, Object> sensorConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grain", columnDefinition = "jsonb")
    private Map<String, Object> grain;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "file_naming_metadata", columnDefinition = "jsonb")
    private Map<String, Object> fileNamingMetadata;

    @Column(name = "current_asof")
    private java.time.Instant currentAsof;

    @Column(name = "asof_timezone")
    private String asofTimezone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "time_grain_config", columnDefinition = "jsonb")
    private Map<String, Object> timeGrainConfig;

    @Column(name = "dataset_slug")
    private String datasetSlug;

    @Column(name = "domain_slug")
    private String domainSlug;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "partition_strategy", columnDefinition = "jsonb")
    private Map<String, Object> partitionStrategy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cluster_strategy", columnDefinition = "jsonb")
    private Map<String, Object> clusterStrategy;

    @Column(name = "write_mode")
    private String writeMode = "append";

    @Column(name = "table_format_hint")
    private String tableFormatHint;

    @Column(name = "physical_design_version")
    private int physicalDesignVersion = 0;

    @Column(name = "asof_column_name")
    private String asofColumnName;

    @Column(name = "discovery_method")
    private String discoveryMethod;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "discovery_proof", columnDefinition = "jsonb")
    private Map<String, Object> discoveryProof;

    public String getConnectorInstanceId() { return connectorInstanceId; }
    public void setConnectorInstanceId(String connectorInstanceId) { this.connectorInstanceId = connectorInstanceId; }
    public String getSorId() { return sorId; }
    public void setSorId(String sorId) { this.sorId = sorId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getQualifiedName() { return qualifiedName; }
    public void setQualifiedName(String qualifiedName) { this.qualifiedName = qualifiedName; }
    public Map<String, Object> getSchemaSnapshot() { return schemaSnapshot; }
    public void setSchemaSnapshot(Map<String, Object> schemaSnapshot) { this.schemaSnapshot = schemaSnapshot; }
    public String getSchemaFormat() { return schemaFormat; }
    public void setSchemaFormat(String schemaFormat) { this.schemaFormat = schemaFormat; }
    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }
    public Map<String, Object> getFreshnessSla() { return freshnessSla; }
    public void setFreshnessSla(Map<String, Object> freshnessSla) { this.freshnessSla = freshnessSla; }
    public String getDefinitionType() { return definitionType; }
    public void setDefinitionType(String definitionType) { this.definitionType = definitionType; }
    public Map<String, Object> getDefinitionConfig() { return definitionConfig; }
    public void setDefinitionConfig(Map<String, Object> definitionConfig) { this.definitionConfig = definitionConfig; }
    public String getCustomSql() { return customSql; }
    public void setCustomSql(String customSql) { this.customSql = customSql; }
    public java.util.List<String> getSourceTables() { return sourceTables; }
    public void setSourceTables(java.util.List<String> sourceTables) { this.sourceTables = sourceTables; }
    public Map<String, Object> getApiSpec() { return apiSpec; }
    public void setApiSpec(Map<String, Object> apiSpec) { this.apiSpec = apiSpec; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTimeGrain() { return timeGrain; }
    public void setTimeGrain(String timeGrain) { this.timeGrain = timeGrain; }
    public String getFileNamingPattern() { return fileNamingPattern; }
    public void setFileNamingPattern(String fileNamingPattern) { this.fileNamingPattern = fileNamingPattern; }
    public String getProcessingDatetimeSource() { return processingDatetimeSource; }
    public void setProcessingDatetimeSource(String processingDatetimeSource) { this.processingDatetimeSource = processingDatetimeSource; }
    public String getArrivalCron() { return arrivalCron; }
    public void setArrivalCron(String arrivalCron) { this.arrivalCron = arrivalCron; }
    public String getArrivalTimezone() { return arrivalTimezone; }
    public void setArrivalTimezone(String arrivalTimezone) { this.arrivalTimezone = arrivalTimezone; }
    public String getSensingStrategy() { return sensingStrategy; }
    public void setSensingStrategy(String sensingStrategy) { this.sensingStrategy = sensingStrategy; }
    public String getReadinessQuery() { return readinessQuery; }
    public void setReadinessQuery(String readinessQuery) { this.readinessQuery = readinessQuery; }
    public String getReadinessConnectionId() { return readinessConnectionId; }
    public void setReadinessConnectionId(String readinessConnectionId) { this.readinessConnectionId = readinessConnectionId; }
    public Map<String, Object> getSensorConfig() { return sensorConfig; }
    public void setSensorConfig(Map<String, Object> sensorConfig) { this.sensorConfig = sensorConfig; }
    public Map<String, Object> getGrain() { return grain; }
    public void setGrain(Map<String, Object> grain) { this.grain = grain; }
    public Map<String, Object> getFileNamingMetadata() { return fileNamingMetadata; }
    public void setFileNamingMetadata(Map<String, Object> fileNamingMetadata) { this.fileNamingMetadata = fileNamingMetadata; }
    public java.time.Instant getCurrentAsof() { return currentAsof; }
    public void setCurrentAsof(java.time.Instant currentAsof) { this.currentAsof = currentAsof; }
    public String getAsofTimezone() { return asofTimezone; }
    public void setAsofTimezone(String asofTimezone) { this.asofTimezone = asofTimezone; }
    public Map<String, Object> getTimeGrainConfig() { return timeGrainConfig; }
    public void setTimeGrainConfig(Map<String, Object> timeGrainConfig) { this.timeGrainConfig = timeGrainConfig; }
    public String getDatasetSlug() { return datasetSlug; }
    public void setDatasetSlug(String datasetSlug) { this.datasetSlug = datasetSlug; }
    public String getDomainSlug() { return domainSlug; }
    public void setDomainSlug(String domainSlug) { this.domainSlug = domainSlug; }
    public Map<String, Object> getPartitionStrategy() { return partitionStrategy; }
    public void setPartitionStrategy(Map<String, Object> partitionStrategy) { this.partitionStrategy = partitionStrategy; }
    public Map<String, Object> getClusterStrategy() { return clusterStrategy; }
    public void setClusterStrategy(Map<String, Object> clusterStrategy) { this.clusterStrategy = clusterStrategy; }
    public String getWriteMode() { return writeMode; }
    public void setWriteMode(String writeMode) { this.writeMode = writeMode; }
    public String getTableFormatHint() { return tableFormatHint; }
    public void setTableFormatHint(String tableFormatHint) { this.tableFormatHint = tableFormatHint; }
    public int getPhysicalDesignVersion() { return physicalDesignVersion; }
    public void setPhysicalDesignVersion(int physicalDesignVersion) { this.physicalDesignVersion = physicalDesignVersion; }
    public String getAsofColumnName() { return asofColumnName; }
    public void setAsofColumnName(String asofColumnName) { this.asofColumnName = asofColumnName; }
    public String getDiscoveryMethod() { return discoveryMethod; }
    public void setDiscoveryMethod(String discoveryMethod) { this.discoveryMethod = discoveryMethod; }
    public Map<String, Object> getDiscoveryProof() { return discoveryProof; }
    public void setDiscoveryProof(Map<String, Object> discoveryProof) { this.discoveryProof = discoveryProof; }
}
