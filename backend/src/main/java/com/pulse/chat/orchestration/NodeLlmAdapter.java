package com.pulse.chat.orchestration;

import com.pulse.chat.service.ChatService;
import com.pulse.chat.service.ChatService.StreamResult;
import com.pulse.llm.LlmSurface;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The thin node→{@code LlmEndpointService} adapter (ADR 0025 §4): each
 * LangGraph4j graph node invokes PULSE's own Vertex/OpenRouter path through this
 * seam — NOT a model client inside the library. It maps a {@link Stage} to its
 * {@link LlmSurface} tier (the n8n {@code stageLLMs} analogue) and delegates to
 * {@link ChatService#streamStage} so the fragile replay path (structured
 * tool-call replay + Vertex thought-signature) is preserved unchanged.
 *
 * <p>Per-stage model matrix (ADR 0025 §2):</p>
 * <ul>
 *   <li><b>Cheap tier</b> ({@link LlmSurface#CHAT_CHEAP}, Flash): router,
 *       discovery, configure, provision, responder.</li>
 *   <li><b>Reasoning tier</b> ({@link LlmSurface#CHAT}, the primary chat model,
 *       Gemini Pro): composer, planner.</li>
 * </ul>
 * <p>A node MAY escalate to the reasoning tier on a flagged hard case via
 * {@link #stream(Stage, boolean, List, SseEmitter, AtomicBoolean)}.</p>
 */
@Component
public class NodeLlmAdapter {

    /** The seven LOCKED orchestration stages (D1-FEEDBACK-CHANGELIST §F). */
    public enum Stage {
        ROUTER, DISCOVERY, COMPOSER, CONFIGURE, PROVISION, PLANNER, RESPONDER
    }

    private final ChatService chatService;

    @Autowired
    public NodeLlmAdapter(ChatService chatService) {
        this.chatService = chatService;
    }

    /** The cost-optimized default surface for a stage (ADR 0025 §2). */
    public static LlmSurface surfaceFor(Stage stage) {
        return switch (stage) {
            case COMPOSER, PLANNER -> LlmSurface.CHAT;          // reasoning tier (Pro)
            case ROUTER, DISCOVERY, CONFIGURE, PROVISION, RESPONDER -> LlmSurface.CHAT_CHEAP; // cheap tier (Flash)
        };
    }

    /** Stream a stage turn on its default tier. */
    public StreamResult stream(Stage stage,
                               List<Map<String, Object>> messages,
                               SseEmitter emitter,
                               AtomicBoolean emitterDead) throws Exception {
        return stream(stage, false, messages, emitter, emitterDead);
    }

    /**
     * Stream a stage turn, optionally escalating a cheap-tier stage to the
     * reasoning tier for a flagged hard case (ADR 0025 §2).
     */
    public StreamResult stream(Stage stage,
                               boolean escalate,
                               List<Map<String, Object>> messages,
                               SseEmitter emitter,
                               AtomicBoolean emitterDead) throws Exception {
        LlmSurface surface = surfaceFor(stage);
        if (escalate && surface == LlmSurface.CHAT_CHEAP) {
            surface = LlmSurface.CHAT;
        }
        // If the resolved tier isn't configured (e.g. cheap key unset), fall
        // back to the primary CHAT surface so a stage never hard-fails on a
        // missing optional tier.
        if (!chatService.isStageConfigured(surface) && chatService.isStageConfigured(LlmSurface.CHAT)) {
            surface = LlmSurface.CHAT;
        }
        return chatService.streamStage(surface, messages, emitter, emitterDead);
    }
}
