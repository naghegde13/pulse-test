package com.pulse.chat.orchestration;

import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-turn shared-state carrier — the PULSE analogue of n8n's
 * {@code ParentGraphState} (07-orchestration-revert-layout.md §1.3; ADR 0025 §1).
 * One typed state object is threaded through every node of the
 * {@link CompositionGraph}.
 *
 * <p>Channels (n8n → PULSE):</p>
 * <ul>
 *   <li>{@code compositionView} → the canonical {@code CompositionView} snapshot
 *       of the active version, taken at turn start (the revert point).</li>
 *   <li>{@code stagingGraph} → the STAGING graph the turn builds (clone of
 *       canonical + applied ops); never persisted until Apply.</li>
 *   <li>{@code opQueue} → the {@link PlanOperation} queue with the
 *       {@link OpQueue} append/clear reducer.</li>
 *   <li>{@code phase}/{@code nextPhase} → the orchestration phase route channels.</li>
 *   <li>{@code planOutput}/{@code mode}/{@code planDecision}/{@code planFeedback}/
 *       {@code planPrevious} → the Plan-Preview lifecycle channels.</li>
 *   <li>{@code messages} → the rebuilt LLM message list (append reducer).</li>
 *   <li>{@code coordinationLog} → turn-progress entries that drive deterministic
 *       routing (append reducer).</li>
 * </ul>
 */
public class AgentState extends org.bsc.langgraph4j.state.AgentState {

    // Channel keys.
    public static final String COMPOSITION_VIEW = "compositionView";
    public static final String STAGING_GRAPH = "stagingGraph";
    public static final String OP_QUEUE = "opQueue";
    public static final String PHASE = "phase";
    public static final String NEXT_PHASE = "nextPhase";
    public static final String PLAN_OUTPUT = "planOutput";
    public static final String MODE = "mode";
    public static final String PLAN_DECISION = "planDecision";
    public static final String PLAN_FEEDBACK = "planFeedback";
    public static final String PLAN_PREVIOUS = "planPrevious";
    public static final String MESSAGES = "messages";
    public static final String COORDINATION_LOG = "coordinationLog";
    public static final String TENANT_ID = "tenantId";
    public static final String PIPELINE_ID = "pipelineId";
    public static final String SESSION_ID = "sessionId";
    public static final String VERSION_ID = "versionId";

    /**
     * The state schema. The op-queue uses a custom append/clear reducer
     * ({@link OpQueue#reduce}); messages + coordinationLog append; the rest are
     * last-write-wins (the default Channel behavior when no reducer is set).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            OP_QUEUE, Channels.base(
                    (oldValue, newValue) -> OpQueue.reduce(
                            (List<PlanOperation>) oldValue, (List<PlanOperation>) newValue),
                    (java.util.function.Supplier) ArrayList::new),
            MESSAGES, Channels.appender(ArrayList::new),
            COORDINATION_LOG, Channels.appender(ArrayList::new)
    );

    public AgentState(Map<String, Object> initData) {
        super(initData);
    }

    // ------------------------------------------------------------------
    // Typed accessors.
    // ------------------------------------------------------------------

    public Optional<StagingGraph> stagingGraph() {
        return this.<StagingGraph>value(STAGING_GRAPH);
    }

    @SuppressWarnings("unchecked")
    public List<PlanOperation> opQueue() {
        return this.<List<PlanOperation>>value(OP_QUEUE).orElseGet(ArrayList::new);
    }

    public String phase() {
        return this.<String>value(PHASE).orElse("router");
    }

    public Optional<String> nextPhase() {
        return this.<String>value(NEXT_PHASE);
    }

    public String mode() {
        return this.<String>value(MODE).orElse("build");
    }

    public Optional<String> planDecision() {
        return this.<String>value(PLAN_DECISION);
    }

    public Optional<Object> planOutput() {
        return this.value(PLAN_OUTPUT);
    }

    @SuppressWarnings("unchecked")
    public List<Object> coordinationLog() {
        return this.<List<Object>>value(COORDINATION_LOG).orElseGet(ArrayList::new);
    }

    public Optional<String> tenantId() {
        return this.<String>value(TENANT_ID);
    }

    public Optional<String> sessionId() {
        return this.<String>value(SESSION_ID);
    }

    public Optional<String> pipelineId() {
        return this.<String>value(PIPELINE_ID);
    }

    public Optional<String> versionId() {
        return this.<String>value(VERSION_ID);
    }
}
