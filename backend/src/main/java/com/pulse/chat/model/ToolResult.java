package com.pulse.chat.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured envelope for chat tool execution (ARCH-009).
 *
 * <p>SSE {@code tool_result} events carry the JSON serialization of this
 * record. Frontend refresh and toast behavior must key off
 * {@code mutationApplied} and {@code refreshHints}, never the tool name.</p>
 *
 * @param toolName the chat tool that produced this result.
 * @param status   {@code ok | error | planCreated | rejected}.
 * @param planCreated true when the tool persisted a PREVIEW plan.
 * @param mutationApplied true when the tool applied an approved plan or
 *                        otherwise mutated product state.
 * @param planId the plan id involved, or null.
 * @param commandIds command ids executed by an apply, or empty.
 * @param affectedEntities list of {type, id, action} maps describing what
 *                         changed. Empty when no mutation occurred.
 * @param declaredDraftRefs plan-preview draft refs declared by a plan-producing tool.
 * @param previewCommands plan-preview command summaries safe to display before apply.
 * @param uiIntents post-apply UI intents with real product ids only.
 * @param refreshHints stable refresh hint keys for the frontend
 *                     ({@code pipeline}, {@code composition}, {@code plan},
 *                     {@code blueprint}, {@code dataset}, {@code connector},
 *                     {@code domain}, {@code commands}, ...).
 * @param message human-readable summary; safe to surface in toasts.
 */
public record ToolResult(
        String toolName,
        String status,
        boolean planCreated,
        boolean mutationApplied,
        String planId,
        List<String> commandIds,
        List<Map<String, Object>> affectedEntities,
        List<String> declaredDraftRefs,
        List<Map<String, Object>> previewCommands,
        List<Map<String, Object>> uiIntents,
        List<String> refreshHints,
        String message) {

    public ToolResult {
        commandIds = commandIds == null ? List.of() : List.copyOf(commandIds);
        affectedEntities = affectedEntities == null ? List.of() : List.copyOf(affectedEntities);
        declaredDraftRefs = declaredDraftRefs == null ? List.of() : List.copyOf(declaredDraftRefs);
        previewCommands = previewCommands == null ? List.of() : List.copyOf(previewCommands);
        uiIntents = uiIntents == null ? List.of() : List.copyOf(uiIntents);
        refreshHints = refreshHints == null ? List.of() : List.copyOf(refreshHints);
    }

    public static Builder builder(String toolName) {
        return new Builder(toolName);
    }

    /** Convenience: success result with no mutation. */
    public static ToolResult ok(String toolName, String message) {
        return builder(toolName).status("ok").message(message).build();
    }

    /** Convenience: tool created a PREVIEW plan but didn't apply it. */
    public static ToolResult planCreated(String toolName, String planId, String message) {
        return builder(toolName).status("planCreated").planCreated(true)
                .planId(planId).refreshHints(List.of("plan", "commands"))
                .message(message).build();
    }

    /** Convenience: apply_plan executed successfully. */
    public static ToolResult applied(String toolName, String planId,
                                     List<String> commandIds,
                                     List<Map<String, Object>> affected,
                                     List<String> refreshHints,
                                     String message) {
        return builder(toolName).status("ok").mutationApplied(true)
                .planId(planId).commandIds(commandIds)
                .affectedEntities(affected).refreshHints(refreshHints)
                .message(message).build();
    }

    /** Convenience: tool rejected (e.g. deprecated blueprint, validation). */
    public static ToolResult rejected(String toolName, String message) {
        return builder(toolName).status("rejected").message(message).build();
    }

    public static ToolResult error(String toolName, String message) {
        return builder(toolName).status("error").message(message).build();
    }

    public String toJson(ObjectMapper mapper) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("toolName", toolName);
            map.put("status", status);
            map.put("planCreated", planCreated);
            map.put("mutationApplied", mutationApplied);
            map.put("planId", planId);
            map.put("commandIds", commandIds);
            map.put("affectedEntities", affectedEntities);
            map.put("declaredDraftRefs", declaredDraftRefs);
            map.put("previewCommands", previewCommands);
            map.put("uiIntents", uiIntents);
            map.put("refreshHints", refreshHints);
            map.put("message", message);
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"toolName\":\"" + toolName + "\",\"status\":\"error\","
                    + "\"message\":\"Failed to serialize ToolResult: " + e.getMessage() + "\"}";
        }
    }

    public static final class Builder {
        private final String toolName;
        private String status = "ok";
        private boolean planCreated;
        private boolean mutationApplied;
        private String planId;
        private List<String> commandIds = new ArrayList<>();
        private List<Map<String, Object>> affectedEntities = new ArrayList<>();
        private List<String> declaredDraftRefs = new ArrayList<>();
        private List<Map<String, Object>> previewCommands = new ArrayList<>();
        private List<Map<String, Object>> uiIntents = new ArrayList<>();
        private List<String> refreshHints = new ArrayList<>();
        private String message;

        Builder(String toolName) { this.toolName = toolName; }

        public Builder status(String s) { this.status = s; return this; }
        public Builder planCreated(boolean v) { this.planCreated = v; return this; }
        public Builder mutationApplied(boolean v) { this.mutationApplied = v; return this; }
        public Builder planId(String id) { this.planId = id; return this; }
        public Builder commandIds(List<String> ids) {
            this.commandIds = ids == null ? new ArrayList<>() : new ArrayList<>(ids); return this;
        }
        public Builder affectedEntities(List<Map<String, Object>> entities) {
            this.affectedEntities = entities == null ? new ArrayList<>() : new ArrayList<>(entities);
            return this;
        }
        public Builder declaredDraftRefs(List<String> refs) {
            this.declaredDraftRefs = refs == null ? new ArrayList<>() : new ArrayList<>(refs);
            return this;
        }
        public Builder previewCommands(List<Map<String, Object>> commands) {
            this.previewCommands = commands == null ? new ArrayList<>() : new ArrayList<>(commands);
            return this;
        }
        public Builder uiIntents(List<Map<String, Object>> intents) {
            this.uiIntents = intents == null ? new ArrayList<>() : new ArrayList<>(intents);
            return this;
        }
        public Builder refreshHints(List<String> hints) {
            this.refreshHints = hints == null ? new ArrayList<>() : new ArrayList<>(hints);
            return this;
        }
        public Builder message(String m) { this.message = m; return this; }

        public ToolResult build() {
            return new ToolResult(toolName, status, planCreated, mutationApplied,
                    planId, commandIds, affectedEntities, declaredDraftRefs,
                    previewCommands, uiIntents, refreshHints, message);
        }
    }
}
