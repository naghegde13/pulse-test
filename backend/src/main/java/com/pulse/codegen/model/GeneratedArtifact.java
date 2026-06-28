package com.pulse.codegen.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "generated_artifacts")
public class GeneratedArtifact extends BaseEntity {

    @Column(name = "generation_run_id", nullable = false)
    private String generationRunId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_type", nullable = false)
    private String fileType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "template_name")
    private String templateName;

    @Column(name = "source_blueprint_id")
    private String sourceBlueprintId;

    @Column(name = "manually_modified", nullable = false)
    private boolean manuallyModified = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public String getGenerationRunId() { return generationRunId; }
    public void setGenerationRunId(String generationRunId) { this.generationRunId = generationRunId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getSourceBlueprintId() { return sourceBlueprintId; }
    public void setSourceBlueprintId(String sourceBlueprintId) { this.sourceBlueprintId = sourceBlueprintId; }
    public boolean isManuallyModified() { return manuallyModified; }
    public void setManuallyModified(boolean manuallyModified) { this.manuallyModified = manuallyModified; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
