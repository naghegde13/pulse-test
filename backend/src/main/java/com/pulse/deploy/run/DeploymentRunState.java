package com.pulse.deploy.run;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Phase 4 — deployment run state machine.
 *
 * <p>Mirrors the table in
 * {@code docs/architecture/deployment-productization-plan.md}
 * "Deployment Run, Cancel, And Rollback Contract". Phase 4 lands at
 * {@code PENDING}/{@code PREFLIGHT_*}; Phase 5 drives transitions
 * through materialization; Phase 7 adds adapter-driven runtime
 * states.
 */
public enum DeploymentRunState {
    PENDING(false),
    PREFLIGHT_RUNNING(false),
    PREFLIGHT_FAILED(true),
    PREFLIGHT_PASSED(false),
    MATERIALIZING(false),
    MATERIALIZED(false),
    SUBMITTING(false),
    RUNNING(false),
    SUCCEEDED(true),
    FAILED(true),
    CANCEL_REQUESTED(false),
    CANCELLED(true),
    TIMED_OUT(true);

    /** Allowed forward transitions from each state. CANCEL_REQUESTED can interrupt non-terminal states from outside. */
    static final Map<DeploymentRunState, Set<DeploymentRunState>> TRANSITIONS;
    static {
        Map<DeploymentRunState, Set<DeploymentRunState>> t = new EnumMap<>(DeploymentRunState.class);
        t.put(PENDING, EnumSet.of(PREFLIGHT_RUNNING, PREFLIGHT_FAILED, PREFLIGHT_PASSED, CANCEL_REQUESTED, FAILED));
        t.put(PREFLIGHT_RUNNING, EnumSet.of(PREFLIGHT_FAILED, PREFLIGHT_PASSED, CANCEL_REQUESTED, FAILED));
        t.put(PREFLIGHT_FAILED, EnumSet.noneOf(DeploymentRunState.class));
        t.put(PREFLIGHT_PASSED, EnumSet.of(MATERIALIZING, CANCEL_REQUESTED, FAILED));
        t.put(MATERIALIZING, EnumSet.of(MATERIALIZED, FAILED, CANCEL_REQUESTED, TIMED_OUT));
        t.put(MATERIALIZED, EnumSet.of(SUBMITTING, FAILED, CANCEL_REQUESTED));
        t.put(SUBMITTING, EnumSet.of(RUNNING, FAILED, CANCEL_REQUESTED, TIMED_OUT));
        t.put(RUNNING, EnumSet.of(SUCCEEDED, FAILED, CANCEL_REQUESTED, TIMED_OUT));
        t.put(SUCCEEDED, EnumSet.noneOf(DeploymentRunState.class));
        t.put(FAILED, EnumSet.noneOf(DeploymentRunState.class));
        t.put(CANCEL_REQUESTED, EnumSet.of(CANCELLED, FAILED, TIMED_OUT));
        t.put(CANCELLED, EnumSet.noneOf(DeploymentRunState.class));
        t.put(TIMED_OUT, EnumSet.noneOf(DeploymentRunState.class));
        TRANSITIONS = Map.copyOf(t);
    }

    private final boolean terminal;

    DeploymentRunState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** True iff a state machine in {@code from} can legally move to {@code to}. */
    public static boolean canTransition(DeploymentRunState from, DeploymentRunState to) {
        if (from == null || to == null) return false;
        if (from == to) return false;
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(DeploymentRunState.class)).contains(to);
    }

    public static DeploymentRunState parse(String raw) {
        if (raw == null) return null;
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(raw))
                .findFirst()
                .orElse(null);
    }
}
