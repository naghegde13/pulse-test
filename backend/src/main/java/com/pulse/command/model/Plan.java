package com.pulse.command.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "plans")
public class Plan extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "pipeline_id")
    private String pipelineId;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preview_data", columnDefinition = "jsonb")
    private Map<String, Object> previewData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "command_ids", columnDefinition = "jsonb")
    private List<String> commandIds;

    @Column(name = "applied_at")
    private Instant appliedAt;

    // -----------------------------------------------------------------------
    // ARCH-009 chat plan approval persistence.
    // -----------------------------------------------------------------------

    /** Chat session that authored the plan, or null for non-chat plans. */
    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** The USER chat message whose structured metadata referenced this planId. */
    @Column(name = "approved_by_message_id")
    private String approvedByMessageId;

    @Column(name = "approved_by_user_id")
    private String approvedByUserId;

    /**
     * Ordered list of full command objects: {@code type}, {@code aggregateType},
     * {@code aggregateId}, {@code description}, {@code payload}. Apply reads
     * commands only from this column; preview_data is for human display only.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "planned_commands", columnDefinition = "jsonb")
    private List<Map<String, Object>> plannedCommands;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_ref_declarations", columnDefinition = "jsonb")
    private List<Map<String, Object>> draftRefDeclarations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_ref_bindings", columnDefinition = "jsonb")
    private List<Map<String, Object>> draftRefBindings;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public PlanStatus getStatus() { return status; }
    public void setStatus(PlanStatus status) { this.status = status; }
    public Map<String, Object> getPreviewData() { return previewData; }
    public void setPreviewData(Map<String, Object> previewData) { this.previewData = previewData; }
    public List<String> getCommandIds() { return commandIds; }
    public void setCommandIds(List<String> commandIds) { this.commandIds = commandIds; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public String getApprovedByMessageId() { return approvedByMessageId; }
    public void setApprovedByMessageId(String approvedByMessageId) {
        this.approvedByMessageId = approvedByMessageId;
    }
    public String getApprovedByUserId() { return approvedByUserId; }
    public void setApprovedByUserId(String approvedByUserId) {
        this.approvedByUserId = approvedByUserId;
    }
    public List<Map<String, Object>> getPlannedCommands() { return plannedCommands; }
    public void setPlannedCommands(List<Map<String, Object>> plannedCommands) {
        this.plannedCommands = plannedCommands;
    }
    public List<Map<String, Object>> getDraftRefDeclarations() { return draftRefDeclarations; }
    public void setDraftRefDeclarations(List<Map<String, Object>> draftRefDeclarations) {
        this.draftRefDeclarations = draftRefDeclarations;
    }
    public List<Map<String, Object>> getDraftRefBindings() { return draftRefBindings; }
    public void setDraftRefBindings(List<Map<String, Object>> draftRefBindings) {
        this.draftRefBindings = draftRefBindings;
    }
}
