package com.pulse.deploy.evidence;

/**
 * PKT-0005 — canonical proof levels for the runtime evidence contract.
 *
 * <p>Each level represents a distinct class of evidence that is
 * <b>not interchangeable</b> with any other level. The ordinal
 * ordering defines the proof ladder: lower levels can never satisfy
 * a gate that requires a higher level.
 *
 * <p>Critical invariants enforced by this enum and
 * {@link RuntimeEvidenceService}:
 * <ul>
 *   <li>{@code STATIC_PACKAGE} and {@code PREFLIGHT} cannot mark
 *       {@code runtimeProof=true} or {@code promotionReady=true}.</li>
 *   <li>{@code LOCAL_SYNTHETIC} produces evidence for dev-loop
 *       feedback but cannot satisfy runtime output proof.</li>
 *   <li>Only {@code LIVE_RUNTIME} evidence carries real
 *       Composer/Dataproc/BigQuery run identifiers.</li>
 *   <li>{@code ORACLE_VERDICT} requires prior {@code LIVE_RUNTIME}
 *       evidence with output probes.</li>
 *   <li>{@code PROMOTION_READINESS} is the terminal assessment and
 *       requires all prior tiers to be satisfied.</li>
 * </ul>
 */
public enum EvidenceProofLevel {

    /**
     * Package built with static assessment (blocker/warning analysis).
     * No runtime execution involved.
     */
    STATIC_PACKAGE(false, false),

    /**
     * Preflight checks passed (storage gate, policy, capability).
     * No runtime execution involved.
     */
    PREFLIGHT(false, false),

    /**
     * Local materialization completed. Package landed on disk and
     * the orchestrator drove a synthetic SUCCEEDED run. This is
     * dev-loop feedback only — no real data-plane execution occurred.
     */
    LOCAL_SYNTHETIC(false, false),

    /**
     * Real data-plane execution completed. Carries Composer DAG run
     * IDs, Dataproc batch IDs, BigQuery job IDs, and output
     * URI/table probes with row counts and checksums.
     */
    LIVE_RUNTIME(true, false),

    /**
     * Oracle comparison completed. Semantic comparison of expected
     * vs. actual output with a verdict. Requires prior
     * {@code LIVE_RUNTIME} evidence with output probes.
     */
    ORACLE_VERDICT(true, false),

    /**
     * Promotion readiness decision. All evidence tiers satisfied,
     * approval gates cleared, cleanup policy applied.
     */
    PROMOTION_READINESS(true, true);

    private final boolean runtimeProof;
    private final boolean promotionReady;

    EvidenceProofLevel(boolean runtimeProof, boolean promotionReady) {
        this.runtimeProof = runtimeProof;
        this.promotionReady = promotionReady;
    }

    /** True only for LIVE_RUNTIME, ORACLE_VERDICT, PROMOTION_READINESS. */
    public boolean isRuntimeProof() {
        return runtimeProof;
    }

    /** True only for PROMOTION_READINESS. */
    public boolean isPromotionReady() {
        return promotionReady;
    }

    /**
     * Returns true if {@code candidate} is at least as strong as
     * {@code required} on the proof ladder. Used for gate checks.
     */
    public static boolean isAtLeast(EvidenceProofLevel candidate, EvidenceProofLevel required) {
        if (candidate == null || required == null) return false;
        return candidate.ordinal() >= required.ordinal();
    }

    /**
     * Returns true if the given level represents evidence from a
     * real data-plane execution (not static, preflight, or local
     * synthetic). Only LIVE_RUNTIME, ORACLE_VERDICT, and
     * PROMOTION_READINESS qualify.
     */
    public static boolean hasRealRuntimeEvidence(EvidenceProofLevel level) {
        if (level == null) return false;
        return level.runtimeProof;
    }
}
