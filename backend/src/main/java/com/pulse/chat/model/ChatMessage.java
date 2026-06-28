package com.pulse.chat.model;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @Column(length = 26, updatable = false)
    private String id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    private Map<String, Object> toolCalls;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_results", columnDefinition = "jsonb")
    private Map<String, Object> toolResults;

    @Column(name = "plan_id")
    private String planId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) this.id = UlidCreator.getMonotonicUlid().toString();
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Map<String, Object> getToolCalls() { return toolCalls; }
    public void setToolCalls(Map<String, Object> toolCalls) { this.toolCalls = toolCalls; }
    public Map<String, Object> getToolResults() { return toolResults; }
    public void setToolResults(Map<String, Object> toolResults) { this.toolResults = toolResults; }
    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public Instant getCreatedAt() { return createdAt; }
}
