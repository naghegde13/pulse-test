package com.pulse.chat.orchestration;

import java.util.Locale;

/**
 * The HITL Plan-Preview approval gate (ADR 0025 §1; 07-orchestration §1.4) —
 * realized as a LangGraph4j {@code interruptBefore} on the {@code apply} node
 * (wired in {@link CompositionGraph#compile}). The turn PAUSES before the sole
 * canonical writer; the Customer's decision (approve / modify / reject) resumes
 * it via {@code POST /api/v1/chat/sessions/{sessionId}/plans/{planId}/decision}.
 *
 * <p>This type encapsulates the three decision outcomes + the modify-iteration
 * cap (the n8n {@code MAX_PLAN_MODIFY_ITERATIONS} analogue), keeping the gate
 * semantics in one place rather than scattered across the driver.</p>
 */
public final class PlanGate {

    /** The n8n MAX_PLAN_MODIFY_ITERATIONS analogue (07-orchestration §1.4 / discovery.subgraph). */
    public static final int MAX_PLAN_MODIFY_ITERATIONS = 5;

    public enum Decision {
        /** Apply Plan — resume, run apply (the canonical + Command-Log write). */
        APPROVE,
        /** Rebuild the staging graph at composer from the prior plan + feedback (capped). */
        MODIFY,
        /** Discard staging + restore snapshot; no canonical/Command-Log write. */
        REJECT;

        public static Decision parse(String raw) {
            if (raw == null) throw new IllegalArgumentException("decision is required");
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "approve", "apply", "approved" -> APPROVE;
                case "modify", "revise", "change" -> MODIFY;
                case "reject", "cancel", "discard" -> REJECT;
                default -> throw new IllegalArgumentException(
                        "Unknown plan decision '" + raw + "' (expected approve|modify|reject)");
            };
        }
    }

    private PlanGate() {}

    /**
     * The channel updates a resumed turn applies after the interrupt, mirroring
     * n8n's planner branches (07 §1.4): approve sets {@code mode=build} +
     * {@code planDecision=approve}; modify carries the prior plan + feedback
     * forward to a composer rebuild; reject clears the plan output.
     */
    public static java.util.Map<String, Object> resumeChannels(Decision decision,
                                                               Object plan,
                                                               String feedback) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        switch (decision) {
            case APPROVE -> {
                out.put(AgentState.PLAN_DECISION, "approve");
                out.put(AgentState.MODE, "build");
                out.put(AgentState.PLAN_OUTPUT, plan);
                out.put(AgentState.PLAN_FEEDBACK, null);
                out.put(AgentState.PLAN_PREVIOUS, null);
                out.put(AgentState.NEXT_PHASE, CompositionGraph.APPLY);
            }
            case MODIFY -> {
                out.put(AgentState.PLAN_DECISION, "modify");
                out.put(AgentState.PLAN_OUTPUT, null);
                out.put(AgentState.PLAN_PREVIOUS, plan);
                out.put(AgentState.PLAN_FEEDBACK, feedback);
                out.put(AgentState.NEXT_PHASE, CompositionGraph.COMPOSER);
            }
            case REJECT -> {
                out.put(AgentState.PLAN_DECISION, "reject");
                out.put(AgentState.PLAN_OUTPUT, null);
                out.put(AgentState.PLAN_FEEDBACK, null);
                out.put(AgentState.PLAN_PREVIOUS, null);
                out.put(AgentState.NEXT_PHASE, CompositionGraph.RESPONDER);
            }
        }
        return out;
    }
}
