package com.pulse.chat.orchestration;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-turn op-queue reducer — the PULSE analogue of n8n's
 * {@code operationsReducer} (06-ops-queue-apply-diff.md §B.2), used as the
 * reducer on the {@link AgentState} {@code opQueue} channel.
 *
 * <p>Semantics (verbatim from n8n's reducer):</p>
 * <ul>
 *   <li>{@code update == null} → reset/clear the queue (return empty).</li>
 *   <li>empty/no update → keep current.</li>
 *   <li>any incoming {@link PlanOperation.Clear} op → wipe everything (keep only
 *       the last clear).</li>
 *   <li>otherwise → APPEND the incoming ops to current.</li>
 * </ul>
 *
 * <p>This is a pure function so it is trivially unit-testable on H2 and reusable
 * both as a LangGraph4j channel reducer and directly by {@code process_operations}.</p>
 */
public final class OpQueue {

    private OpQueue() {}

    /**
     * The reduce step. {@code current} is the accumulated queue; {@code update}
     * is the batch a tool (or superstep) contributes.
     */
    public static List<PlanOperation> reduce(List<PlanOperation> current, List<PlanOperation> update) {
        if (update == null) {
            return new ArrayList<>(); // null => reset/clear
        }
        if (update.isEmpty()) {
            return current == null ? new ArrayList<>() : new ArrayList<>(current);
        }
        boolean hasClear = update.stream().anyMatch(op -> op instanceof PlanOperation.Clear);
        if (hasClear) {
            // Keep only the LAST clear, drop everything else (n8n semantics).
            List<PlanOperation> clears = update.stream()
                    .filter(op -> op instanceof PlanOperation.Clear)
                    .toList();
            List<PlanOperation> out = new ArrayList<>();
            out.add(clears.get(clears.size() - 1));
            return out;
        }
        List<PlanOperation> out = new ArrayList<>(current == null ? List.of() : current);
        out.addAll(update);
        return out;
    }
}
