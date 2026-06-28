package com.pulse.pipeline.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "pipeline_versions")
public class PipelineVersion extends BaseEntity {

    @Column(name = "pipeline_id", nullable = false)
    private String pipelineId;

    @Column(nullable = false)
    private int revision;

    @Column(name = "commit_hash")
    private String commitHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_stage", nullable = false)
    private PipelineStage lifecycleStage;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sla_config", columnDefinition = "jsonb")
    private Map<String, Object> slaConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "change_summary")
    private String changeSummary;

    @Column(name = "dq_readiness_score")
    private Integer dqReadinessScore;

    @Column(name = "schedule_cron")
    private String scheduleCron;

    @Column(name = "catchup_enabled")
    private Boolean catchupEnabled;

    @Column(name = "max_active_runs")
    private Integer maxActiveRuns;

    @Column(name = "depends_on_past")
    private Boolean dependsOnPast;

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public String getCommitHash() { return commitHash; }
    public void setCommitHash(String commitHash) { this.commitHash = commitHash; }
    public PipelineStage getLifecycleStage() { return lifecycleStage; }
    public void setLifecycleStage(PipelineStage lifecycleStage) { this.lifecycleStage = lifecycleStage; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Map<String, Object> getSlaConfig() { return slaConfig; }
    public void setSlaConfig(Map<String, Object> slaConfig) { this.slaConfig = slaConfig; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }
    public Integer getDqReadinessScore() { return dqReadinessScore; }
    public void setDqReadinessScore(Integer dqReadinessScore) { this.dqReadinessScore = dqReadinessScore; }
    public String getScheduleCron() { return scheduleCron; }
    public void setScheduleCron(String scheduleCron) { this.scheduleCron = scheduleCron; }
    public Boolean getCatchupEnabled() { return catchupEnabled; }
    public void setCatchupEnabled(Boolean catchupEnabled) { this.catchupEnabled = catchupEnabled; }
    public Integer getMaxActiveRuns() { return maxActiveRuns; }
    public void setMaxActiveRuns(Integer maxActiveRuns) { this.maxActiveRuns = maxActiveRuns; }
    public Boolean getDependsOnPast() { return dependsOnPast; }
    public void setDependsOnPast(Boolean dependsOnPast) { this.dependsOnPast = dependsOnPast; }
}
