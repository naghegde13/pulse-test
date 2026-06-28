package com.pulse.blueprint.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

@Entity
@Table(name = "blueprints")
public class Blueprint extends BaseEntity {

    @Column(name = "blueprint_key", nullable = false, unique = true)
    private String blueprintKey;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BlueprintCategory category;

    @Column(nullable = false)
    private String version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_schema", columnDefinition = "jsonb")
    private List<Map<String, Object>> paramsSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_ports", columnDefinition = "jsonb")
    private List<Map<String, Object>> inputPorts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_ports", columnDefinition = "jsonb")
    private List<Map<String, Object>> outputPorts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "runtime_requirements", columnDefinition = "jsonb")
    private Map<String, Object> runtimeRequirements;

    @Column
    private String subcategory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "artifact_types", columnDefinition = "jsonb")
    private List<String> artifactTypes;

    @Column(name = "compute_backend")
    private String computeBackend;

    @Column(name = "composition_role")
    private String compositionRole;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "valid_layers", columnDefinition = "jsonb")
    private List<String> validLayers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "usage_guidance", columnDefinition = "jsonb")
    private Map<String, Object> usageGuidance;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_params_schema", columnDefinition = "jsonb")
    private Map<String, Object> requiredParamsSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "optional_params_schema", columnDefinition = "jsonb")
    private Map<String, Object> optionalParamsSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ui_schema", columnDefinition = "jsonb")
    private Map<String, Object> uiSchema;

    @Column(name = "emit_strategy")
    private String emitStrategy;

    @Column(name = "supports_reuse", nullable = false)
    private boolean supportsReuse;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_behavior", columnDefinition = "jsonb")
    private Map<String, Object> schemaBehavior;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "codegen_hints", columnDefinition = "jsonb")
    private Map<String, Object> codegenHints;

    @Column(nullable = false)
    private String status = "active";

    @Column(name = "replacement_blueprint_key")
    private String replacementBlueprintKey;

    @Column(nullable = false)
    private boolean deferred;

    @Column(name = "pipeline_config", nullable = false)
    private boolean pipelineConfig;

    /**
     * Add surface (ARCH-011). One of: composition, orchestration_policy, none.
     * Drives which generic mutation paths may instantiate or configure the
     * blueprint; deprecated/deferred rows are pinned to {@code none}.
     */
    @Column(name = "add_surface", nullable = false, length = 32)
    private String addSurface = "composition";

    public String getBlueprintKey() { return blueprintKey; }
    public void setBlueprintKey(String blueprintKey) { this.blueprintKey = blueprintKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BlueprintCategory getCategory() { return category; }
    public void setCategory(BlueprintCategory category) { this.category = category; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public List<Map<String, Object>> getParamsSchema() { return paramsSchema; }
    public void setParamsSchema(List<Map<String, Object>> paramsSchema) { this.paramsSchema = paramsSchema; }
    public List<Map<String, Object>> getInputPorts() { return inputPorts; }
    public void setInputPorts(List<Map<String, Object>> inputPorts) { this.inputPorts = inputPorts; }
    public List<Map<String, Object>> getOutputPorts() { return outputPorts; }
    public void setOutputPorts(List<Map<String, Object>> outputPorts) { this.outputPorts = outputPorts; }
    public Map<String, Object> getRuntimeRequirements() { return runtimeRequirements; }
    public void setRuntimeRequirements(Map<String, Object> runtimeRequirements) { this.runtimeRequirements = runtimeRequirements; }
    public String getSubcategory() { return subcategory; }
    public void setSubcategory(String subcategory) { this.subcategory = subcategory; }
    public List<String> getArtifactTypes() { return artifactTypes; }
    public void setArtifactTypes(List<String> artifactTypes) { this.artifactTypes = artifactTypes; }
    public String getComputeBackend() { return computeBackend; }
    public void setComputeBackend(String computeBackend) { this.computeBackend = computeBackend; }
    public String getCompositionRole() { return compositionRole; }
    public void setCompositionRole(String compositionRole) { this.compositionRole = compositionRole; }
    public List<String> getValidLayers() { return validLayers; }
    public void setValidLayers(List<String> validLayers) { this.validLayers = validLayers; }
    public Map<String, Object> getUsageGuidance() { return usageGuidance; }
    public void setUsageGuidance(Map<String, Object> usageGuidance) { this.usageGuidance = usageGuidance; }
    public Map<String, Object> getRequiredParamsSchema() { return requiredParamsSchema; }
    public void setRequiredParamsSchema(Map<String, Object> requiredParamsSchema) { this.requiredParamsSchema = requiredParamsSchema; }
    public Map<String, Object> getOptionalParamsSchema() { return optionalParamsSchema; }
    public void setOptionalParamsSchema(Map<String, Object> optionalParamsSchema) { this.optionalParamsSchema = optionalParamsSchema; }
    public Map<String, Object> getUiSchema() { return uiSchema; }
    public void setUiSchema(Map<String, Object> uiSchema) { this.uiSchema = uiSchema; }
    public String getEmitStrategy() { return emitStrategy; }
    public void setEmitStrategy(String emitStrategy) { this.emitStrategy = emitStrategy; }
    public boolean isSupportsReuse() { return supportsReuse; }
    public void setSupportsReuse(boolean supportsReuse) { this.supportsReuse = supportsReuse; }
    public Map<String, Object> getSchemaBehavior() { return schemaBehavior; }
    public void setSchemaBehavior(Map<String, Object> schemaBehavior) { this.schemaBehavior = schemaBehavior; }
    public Map<String, Object> getCodegenHints() { return codegenHints; }
    public void setCodegenHints(Map<String, Object> codegenHints) { this.codegenHints = codegenHints; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReplacementBlueprintKey() { return replacementBlueprintKey; }
    public void setReplacementBlueprintKey(String replacementBlueprintKey) { this.replacementBlueprintKey = replacementBlueprintKey; }
    public boolean isDeferred() { return deferred; }
    public void setDeferred(boolean deferred) { this.deferred = deferred; }
    public boolean isPipelineConfig() { return pipelineConfig; }
    public void setPipelineConfig(boolean pipelineConfig) { this.pipelineConfig = pipelineConfig; }
    public String getAddSurface() { return addSurface; }
    public void setAddSurface(String addSurface) { this.addSurface = addSurface; }
}
