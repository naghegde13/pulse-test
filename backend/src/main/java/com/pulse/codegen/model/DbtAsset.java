package com.pulse.codegen.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

@Entity
@Table(name = "dbt_assets")
public class DbtAsset extends BaseEntity {

    @Column(name = "domain_id", nullable = false)
    private String domainId;

    @Column(name = "project_name", nullable = false)
    private String projectName;

    @Column(name = "asset_name", nullable = false)
    private String assetName;

    @Column(name = "asset_type", nullable = false)
    private String assetType;

    @Column(nullable = false)
    private String path;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> tags;

    @Column(name = "group_name")
    private String groupName;

    @Column(name = "access_level")
    private String accessLevel;

    private String grain;

    @Column(name = "business_concept")
    private String businessConcept;

    @Column(name = "schema_signature")
    private String schemaSignature;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "git_sha")
    private String gitSha;

    @Column(nullable = false)
    private String branch = "main";

    public String getDomainId() { return domainId; }
    public void setDomainId(String domainId) { this.domainId = domainId; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getAssetName() { return assetName; }
    public void setAssetName(String assetName) { this.assetName = assetName; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getAccessLevel() { return accessLevel; }
    public void setAccessLevel(String accessLevel) { this.accessLevel = accessLevel; }
    public String getGrain() { return grain; }
    public void setGrain(String grain) { this.grain = grain; }
    public String getBusinessConcept() { return businessConcept; }
    public void setBusinessConcept(String businessConcept) { this.businessConcept = businessConcept; }
    public String getSchemaSignature() { return schemaSignature; }
    public void setSchemaSignature(String schemaSignature) { this.schemaSignature = schemaSignature; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public String getGitSha() { return gitSha; }
    public void setGitSha(String gitSha) { this.gitSha = gitSha; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
}
