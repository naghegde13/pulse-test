package com.pulse.deploy.promotion;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * PKT-0008 — promotion proof-state machine.
 *
 * <p>Defines the seven canonical proof states a pipeline version
 * traverses from initial workspace creation through promotion
 * completion. Each state represents a distinct evidence tier:
 *
 * <ol>
 *   <li>{@link #DRAFT_WORKSPACE} — workspace exists, code may be dirty</li>
 *   <li>{@link #ACCEPTED_ARTIFACT} — PR merged, version acceptance recorded</li>
 *   <li>{@link #STATIC_PACKAGE_PROOF} — committed package built, static assessment present</li>
 *   <li>{@link #DEPLOY_REQUESTED} — deployment initiated against a target</li>
 *   <li>{@link #RUNTIME_PROVED} — deployment run completed with SUCCEEDED state</li>
 *   <li>{@link #PROMOTION_READY} — runtime proof + approval gates satisfied</li>
 *   <li>{@link #PROMOTION_COMPLETE} — package deployed and active in the promoted environment</li>
 * </ol>
 *
 * <p>Critical semantic: states are <b>not interchangeable</b>.
 * Static package proof does not imply runtime proof.
 * A deploy request does not imply runtime success.
 * Only a SUCCEEDED deployment run produces {@code RUNTIME_PROVED}.
 * Only approval-gate satisfaction on top of runtime proof produces
 * {@code PROMOTION_READY}.
 */
public enum PromotionProofState {
    DRAFT_WORKSPACE(false),
    ACCEPTED_ARTIFACT(false),
    STATIC_PACKAGE_PROOF(false),
    DEPLOY_REQUESTED(false),
    RUNTIME_PROVED(false),
    PROMOTION_READY(false),
    PROMOTION_COMPLETE(true);

    static final Map<PromotionProofState, Set<PromotionProofState>> TRANSITIONS;
    static {
        Map<PromotionProofState, Set<PromotionProofState>> t = new EnumMap<>(PromotionProofState.class);
        t.put(DRAFT_WORKSPACE,       EnumSet.of(ACCEPTED_ARTIFACT));
        t.put(ACCEPTED_ARTIFACT,     EnumSet.of(STATIC_PACKAGE_PROOF));
        t.put(STATIC_PACKAGE_PROOF,  EnumSet.of(DEPLOY_REQUESTED));
        t.put(DEPLOY_REQUESTED,      EnumSet.of(RUNTIME_PROVED));
        t.put(RUNTIME_PROVED,        EnumSet.of(PROMOTION_READY));
        t.put(PROMOTION_READY,       EnumSet.of(PROMOTION_COMPLETE));
        t.put(PROMOTION_COMPLETE,    EnumSet.noneOf(PromotionProofState.class));
        TRANSITIONS = Map.copyOf(t);
    }

    private final boolean terminal;

    PromotionProofState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** True iff a version in {@code from} can legally advance to {@code to}. */
    public static boolean canTransition(PromotionProofState from, PromotionProofState to) {
        if (from == null || to == null) return false;
        if (from == to) return false;
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(PromotionProofState.class)).contains(to);
    }

    /**
     * Ordinal-based ordering: returns true if {@code candidate} is at
     * least as advanced as {@code required}. Useful for gate checks
     * ("must be at least STATIC_PACKAGE_PROOF to deploy").
     */
    public static boolean isAtLeast(PromotionProofState candidate, PromotionProofState required) {
        if (candidate == null || required == null) return false;
        return candidate.ordinal() >= required.ordinal();
    }

    /**
     * Returns true if {@code state} represents evidence of runtime
     * execution (deployment run SUCCEEDED). Only {@code RUNTIME_PROVED},
     * {@code PROMOTION_READY}, and {@code PROMOTION_COMPLETE} qualify.
     */
    public static boolean hasRuntimeEvidence(PromotionProofState state) {
        if (state == null) return false;
        return state.ordinal() >= RUNTIME_PROVED.ordinal();
    }
}
