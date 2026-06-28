package com.pulse.chat.service;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.chat.model.ChatMessage;
import com.pulse.chat.repository.ChatMessageRepository;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.pipeline.service.CompositionService.CompositionView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic conversation-phase detection. Runs rule-based matching over the
 * last five chat messages + the active composition's blueprint keys. No LLM
 * call. Given the same inputs, returns the same {@link ConversationPhase}.
 */
@Component
public class PhaseDetector {

    private static final int LOOKBACK_MESSAGES = 5;

    private final ChatMessageRepository messageRepo;
    private final PipelineRepository pipelineRepo;
    private final BlueprintRepository blueprintRepo;
    private final CompositionService compositionService;

    public PhaseDetector(ChatMessageRepository messageRepo,
                         PipelineRepository pipelineRepo,
                         BlueprintRepository blueprintRepo,
                         CompositionService compositionService) {
        this.messageRepo = messageRepo;
        this.pipelineRepo = pipelineRepo;
        this.blueprintRepo = blueprintRepo;
        this.compositionService = compositionService;
    }

    public ConversationPhase detect(String sessionId, String pipelineId, String tenantId) {
        List<ChatMessage> tail = recentMessages(sessionId);
        List<Blueprint> compositionBlueprints = compositionBlueprints(pipelineId);
        return detectFromInputs(tail, compositionBlueprints);
    }

    /**
     * Pure function: takes the already-loaded tail + blueprint list and returns a phase.
     * Exposed for testability.
     */
    ConversationPhase detectFromInputs(List<ChatMessage> tail, List<Blueprint> compositionBlueprints) {
        String lastUserText = lastUserContent(tail).toLowerCase();
        String lastToolName = lastToolCallName(tail);

        boolean hasApproval = containsAny(lastUserText,
                "looks good", "build it", "approve", "apply plan");
        boolean priorAddInstance = tail.stream()
                .anyMatch(m -> m != null && "tool_call".equals(m.getRole())
                        && (m.getContent() != null && m.getContent().contains("propose_add_instance")));
        if (hasApproval || ("propose_create_pipeline".equals(lastToolName) && priorAddInstance)) {
            return ConversationPhase.REVIEW_BUILD;
        }

        if (containsAny(lastUserText, "schedule", "cron", "sensor", "backfill", "airflow", "orchestrate")
                || compositionContainsCompositionRole(compositionBlueprints, "orchestration_policy")
                || compositionContainsLayer(compositionBlueprints, "control_plane")) {
            return ConversationPhase.ORCHESTRATION;
        }

        if (containsAny(lastUserText, "quality", "expectation", "validate", "dq", "great expectations")
                || compositionContainsArtifactType(compositionBlueprints, "gx_checkpoint")) {
            return ConversationPhase.DQ;
        }

        boolean messageSaysSilver = containsAny(lastUserText, "silver");
        if (containsAny(lastUserText, "gold", "mart", "fact", "dimension", "scd2", "aggregate")
                || (compositionContainsLayer(compositionBlueprints, "gold") && !messageSaysSilver)) {
            return ConversationPhase.GOLD;
        }

        if (containsAny(lastUserText, "silver", "clean", "standardize", "conform", "join", "pii")
                || compositionContainsCompositionRole(compositionBlueprints, "cleaning")
                || compositionContainsLayer(compositionBlueprints, "silver")) {
            return ConversationPhase.SILVER;
        }

        boolean ingestionKeyword = containsAny(lastUserText,
                "ingest", "bronze", "pull", "load raw", "cdc", "stream");
        boolean ingestionAddInstance = lastAddInstanceBlueprintKey(tail)
                .map(key -> key.endsWith("Ingestion")).orElse(false);
        if (ingestionKeyword || ingestionAddInstance) {
            return ConversationPhase.INGESTION;
        }

        if (lastToolName != null && List.of(
                "create_data_source", "create_connector", "create_dataset", "create_domain",
                "list_data_sources", "list_connectors").contains(lastToolName)) {
            return ConversationPhase.SOURCE_SETUP;
        }
        if (containsAny(lastUserText, "source", "connector", "schema", "dataset", "sor")) {
            return ConversationPhase.SOURCE_SETUP;
        }

        return ConversationPhase.DISCOVERY;
    }

    private List<ChatMessage> recentMessages(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        List<ChatMessage> all = messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int from = Math.max(0, all.size() - LOOKBACK_MESSAGES);
        return all.subList(from, all.size());
    }

    private List<Blueprint> compositionBlueprints(String pipelineId) {
        if (pipelineId == null || pipelineId.isBlank()) return List.of();
        Pipeline pipeline = pipelineRepo.findById(pipelineId).orElse(null);
        if (pipeline == null || pipeline.getActiveVersionId() == null) return List.of();
        CompositionView view = compositionService.getComposition(pipeline.getActiveVersionId());
        List<Blueprint> out = new ArrayList<>();
        for (SubPipelineInstance inst : view.instances()) {
            if (inst.getBlueprintKey() == null) continue;
            blueprintRepo.findByBlueprintKey(inst.getBlueprintKey()).ifPresent(out::add);
        }
        return out;
    }

    private String lastUserContent(List<ChatMessage> tail) {
        for (int i = tail.size() - 1; i >= 0; i--) {
            ChatMessage msg = tail.get(i);
            if (msg == null) continue;
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                return msg.getContent();
            }
        }
        return "";
    }

    private String lastToolCallName(List<ChatMessage> tail) {
        for (int i = tail.size() - 1; i >= 0; i--) {
            ChatMessage msg = tail.get(i);
            if (msg == null) continue;
            if ("tool_call".equals(msg.getRole()) || "tool".equals(msg.getRole())) {
                if (msg.getToolResults() != null && msg.getToolResults().get("tool_name") != null) {
                    return msg.getToolResults().get("tool_name").toString();
                }
                if (msg.getContent() != null && msg.getContent().contains(":")) {
                    String first = msg.getContent().split("[:\\s]", 2)[0];
                    if (!first.isBlank()) return first;
                }
            }
        }
        return null;
    }

    private java.util.Optional<String> lastAddInstanceBlueprintKey(List<ChatMessage> tail) {
        for (int i = tail.size() - 1; i >= 0; i--) {
            ChatMessage msg = tail.get(i);
            if (msg == null) continue;
            if (msg.getContent() != null && msg.getContent().contains("propose_add_instance")) {
                if (msg.getToolResults() != null && msg.getToolResults().get("blueprint_key") != null) {
                    return java.util.Optional.of(msg.getToolResults().get("blueprint_key").toString());
                }
            }
        }
        return java.util.Optional.empty();
    }

    private boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isEmpty()) return false;
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    private boolean compositionContainsLayer(List<Blueprint> bps, String layer) {
        for (Blueprint bp : bps) {
            List<String> valid = bp.getValidLayers();
            if (valid != null && valid.contains(layer)) return true;
        }
        return false;
    }

    private boolean compositionContainsCompositionRole(List<Blueprint> bps, String role) {
        for (Blueprint bp : bps) {
            if (role.equals(bp.getCompositionRole())) return true;
        }
        return false;
    }

    private boolean compositionContainsArtifactType(List<Blueprint> bps, String artifactType) {
        for (Blueprint bp : bps) {
            List<String> types = bp.getArtifactTypes();
            if (types != null && types.contains(artifactType)) return true;
        }
        return false;
    }
}
