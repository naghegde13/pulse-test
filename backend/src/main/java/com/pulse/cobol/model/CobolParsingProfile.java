package com.pulse.cobol.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "cobol_parsing_profiles")
public class CobolParsingProfile extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "copybook_content", nullable = false, columnDefinition = "TEXT")
    private String copybookContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cobrix_options", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> cobrixOptions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flatten_spec", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> flattenSpec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_schema_snapshot", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> outputSchemaSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_quality_summary", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> profileQualitySummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata;

    @Column(name = "created_by")
    private String createdBy;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCopybookContent() { return copybookContent; }
    public void setCopybookContent(String copybookContent) { this.copybookContent = copybookContent; }

    public Map<String, Object> getCobrixOptions() { return cobrixOptions; }
    public void setCobrixOptions(Map<String, Object> cobrixOptions) { this.cobrixOptions = cobrixOptions; }

    public Map<String, Object> getFlattenSpec() { return flattenSpec; }
    public void setFlattenSpec(Map<String, Object> flattenSpec) { this.flattenSpec = flattenSpec; }

    public Map<String, Object> getOutputSchemaSnapshot() { return outputSchemaSnapshot; }
    public void setOutputSchemaSnapshot(Map<String, Object> outputSchemaSnapshot) { this.outputSchemaSnapshot = outputSchemaSnapshot; }

    public Map<String, Object> getProfileQualitySummary() { return profileQualitySummary; }
    public void setProfileQualitySummary(Map<String, Object> profileQualitySummary) { this.profileQualitySummary = profileQualitySummary; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
