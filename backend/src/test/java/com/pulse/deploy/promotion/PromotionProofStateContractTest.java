package com.pulse.deploy.promotion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PKT-0008 — promotion proof-state machine contract tests.
 *
 * <p>Pins the exact transition table and negative constraints so that
 * any loosening of proof-state semantics shows up as an explicit test
 * failure. Critical contract: static package proof, deploy requested,
 * runtime proved, promotion ready, and promotion complete are never
 * substitutable for each other.
 */
class PromotionProofStateContractTest {

    // ── Allowed transitions ─────────────────────────────────────

    @Test
    @DisplayName("DRAFT_WORKSPACE -> ACCEPTED_ARTIFACT is the only allowed transition from draft")
    void draftWorkspaceTransitions() {
        assertTrue(PromotionProofState.canTransition(
                PromotionProofState.DRAFT_WORKSPACE, PromotionProofState.ACCEPTED_ARTIFACT));
        // No other targets from DRAFT_WORKSPACE
        for (PromotionProofState s : PromotionProofState.values()) {
            if (s == PromotionProofState.ACCEPTED_ARTIFACT) continue;
            assertFalse(PromotionProofState.canTransition(PromotionProofState.DRAFT_WORKSPACE, s),
                    "DRAFT_WORKSPACE must not transition to " + s);
        }
    }

    @Test
    @DisplayName("ACCEPTED_ARTIFACT -> STATIC_PACKAGE_PROOF is the only forward transition")
    void acceptedArtifactTransitions() {
        assertTrue(PromotionProofState.canTransition(
                PromotionProofState.ACCEPTED_ARTIFACT, PromotionProofState.STATIC_PACKAGE_PROOF));
        for (PromotionProofState s : PromotionProofState.values()) {
            if (s == PromotionProofState.STATIC_PACKAGE_PROOF) continue;
            assertFalse(PromotionProofState.canTransition(PromotionProofState.ACCEPTED_ARTIFACT, s),
                    "ACCEPTED_ARTIFACT must not transition to " + s);
        }
    }

    @Test
    @DisplayName("STATIC_PACKAGE_PROOF -> DEPLOY_REQUESTED is the only forward transition")
    void staticPackageProofTransitions() {
        assertTrue(PromotionProofState.canTransition(
                PromotionProofState.STATIC_PACKAGE_PROOF, PromotionProofState.DEPLOY_REQUESTED));
        for (PromotionProofState s : PromotionProofState.values()) {
            if (s == PromotionProofState.DEPLOY_REQUESTED) continue;
            assertFalse(PromotionProofState.canTransition(PromotionProofState.STATIC_PACKAGE_PROOF, s),
                    "STATIC_PACKAGE_PROOF must not transition to " + s);
        }
    }

    @Test
    @DisplayName("DEPLOY_REQUESTED -> RUNTIME_PROVED is the only forward transition")
    void deployRequestedTransitions() {
        assertTrue(PromotionProofState.canTransition(
                PromotionProofState.DEPLOY_REQUESTED, PromotionProofState.RUNTIME_PROVED));
        for (PromotionProofState s : PromotionProofState.values()) {
            if (s == PromotionProofState.RUNTIME_PROVED) continue;
            assertFalse(PromotionProofState.canTransition(PromotionProofState.DEPLOY_REQUESTED, s),
                    "DEPLOY_REQUESTED must not transition to " + s);
        }
    }

    @Test
    @DisplayName("RUNTIME_PROVED -> PROMOTION_READY is the only forward transition")
    void runtimeProvedTransitions() {
        assertTrue(PromotionProofState.canTransition(
                PromotionProofState.RUNTIME_PROVED, PromotionProofState.PROMOTION_READY));
        for (PromotionProofState s : PromotionProofState.values()) {
            if (s == PromotionProofState.PROMOTION_READY) continue;
            assertFalse(PromotionProofState.canTransition(PromotionProofState.RUNTIME_PROVED, s),
                    "RUNTIME_PROVED must not transition to " + s);
        }
    }

    @Test
    @DisplayName("PROMOTION_READY -> PROMOTION_COMPLETE is the only forward transition")
    void promotionReadyTransitions() {
        assertTrue(PromotionProofState.canTransition(
                PromotionProofState.PROMOTION_READY, PromotionProofState.PROMOTION_COMPLETE));
        for (PromotionProofState s : PromotionProofState.values()) {
            if (s == PromotionProofState.PROMOTION_COMPLETE) continue;
            assertFalse(PromotionProofState.canTransition(PromotionProofState.PROMOTION_READY, s),
                    "PROMOTION_READY must not transition to " + s);
        }
    }

    @Test
    @DisplayName("PROMOTION_COMPLETE is terminal — no outbound transitions")
    void promotionCompleteIsTerminal() {
        assertTrue(PromotionProofState.PROMOTION_COMPLETE.isTerminal());
        for (PromotionProofState s : PromotionProofState.values()) {
            assertFalse(PromotionProofState.canTransition(PromotionProofState.PROMOTION_COMPLETE, s),
                    "PROMOTION_COMPLETE must not transition to " + s);
        }
    }

    // ── Self-transitions ────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(PromotionProofState.class)
    @DisplayName("Self-transitions are never legal")
    void selfTransitionsRejected(PromotionProofState state) {
        assertFalse(PromotionProofState.canTransition(state, state),
                "self-transition " + state + " -> " + state + " must be rejected");
    }

    // ── Null safety ─────────────────────────────────────────────

    @Test
    @DisplayName("Null inputs are handled safely")
    void nullInputsSafe() {
        assertFalse(PromotionProofState.canTransition(null, PromotionProofState.DRAFT_WORKSPACE));
        assertFalse(PromotionProofState.canTransition(PromotionProofState.DRAFT_WORKSPACE, null));
        assertFalse(PromotionProofState.canTransition(null, null));
    }

    // ── Negative evidence: states are not interchangeable ───────

    @Test
    @DisplayName("NEGATIVE: static package proof cannot be rendered as runtime proved")
    void staticCannotBeRenderedAsRuntimeProved() {
        assertFalse(PromotionProofState.canTransition(
                PromotionProofState.STATIC_PACKAGE_PROOF, PromotionProofState.RUNTIME_PROVED),
                "static package proof must not skip to runtime proved");
        assertFalse(PromotionProofState.hasRuntimeEvidence(PromotionProofState.STATIC_PACKAGE_PROOF),
                "static package proof does not constitute runtime evidence");
    }

    @Test
    @DisplayName("NEGATIVE: static package proof cannot be rendered as promotion complete")
    void staticCannotBeRenderedAsPromotionComplete() {
        assertFalse(PromotionProofState.canTransition(
                PromotionProofState.STATIC_PACKAGE_PROOF, PromotionProofState.PROMOTION_COMPLETE),
                "static package proof must not skip to promotion complete");
    }

    @Test
    @DisplayName("NEGATIVE: deploy requested cannot be rendered as runtime proved without Stage 14 evidence")
    void deployRequestedCannotBeRenderedAsRuntimeWithoutEvidence() {
        // The state machine enforces that DEPLOY_REQUESTED -> RUNTIME_PROVED
        // requires evidence; the canTransition only validates the graph edge.
        // But crucially, DEPLOY_REQUESTED itself does not constitute runtime proof.
        assertFalse(PromotionProofState.hasRuntimeEvidence(PromotionProofState.DEPLOY_REQUESTED),
                "deploy requested does not constitute runtime evidence");
    }

    @Test
    @DisplayName("NEGATIVE: deploy requested cannot be rendered as promotion complete")
    void deployRequestedCannotBeRenderedAsPromotionComplete() {
        assertFalse(PromotionProofState.canTransition(
                PromotionProofState.DEPLOY_REQUESTED, PromotionProofState.PROMOTION_COMPLETE),
                "deploy requested must not skip to promotion complete");
    }

    @Test
    @DisplayName("NEGATIVE: accepted artifact cannot be rendered as deploy requested or runtime proved")
    void acceptedCannotSkipToDeployOrRuntime() {
        assertFalse(PromotionProofState.canTransition(
                PromotionProofState.ACCEPTED_ARTIFACT, PromotionProofState.DEPLOY_REQUESTED));
        assertFalse(PromotionProofState.canTransition(
                PromotionProofState.ACCEPTED_ARTIFACT, PromotionProofState.RUNTIME_PROVED));
        assertFalse(PromotionProofState.hasRuntimeEvidence(PromotionProofState.ACCEPTED_ARTIFACT));
    }

    @Test
    @DisplayName("NEGATIVE: draft workspace has no runtime evidence")
    void draftHasNoRuntimeEvidence() {
        assertFalse(PromotionProofState.hasRuntimeEvidence(PromotionProofState.DRAFT_WORKSPACE));
    }

    // ── isAtLeast ordering ──────────────────────────────────────

    @Test
    @DisplayName("isAtLeast correctly orders proof tiers")
    void isAtLeastOrdering() {
        assertTrue(PromotionProofState.isAtLeast(
                PromotionProofState.RUNTIME_PROVED, PromotionProofState.STATIC_PACKAGE_PROOF));
        assertTrue(PromotionProofState.isAtLeast(
                PromotionProofState.PROMOTION_COMPLETE, PromotionProofState.DRAFT_WORKSPACE));
        assertFalse(PromotionProofState.isAtLeast(
                PromotionProofState.STATIC_PACKAGE_PROOF, PromotionProofState.RUNTIME_PROVED));
        assertFalse(PromotionProofState.isAtLeast(
                PromotionProofState.DEPLOY_REQUESTED, PromotionProofState.PROMOTION_READY));
    }

    @Test
    @DisplayName("isAtLeast with null returns false")
    void isAtLeastNullSafe() {
        assertFalse(PromotionProofState.isAtLeast(null, PromotionProofState.DRAFT_WORKSPACE));
        assertFalse(PromotionProofState.isAtLeast(PromotionProofState.DRAFT_WORKSPACE, null));
    }

    // ── hasRuntimeEvidence boundary ─────────────────────────────

    @Test
    @DisplayName("hasRuntimeEvidence is true only for RUNTIME_PROVED and above")
    void hasRuntimeEvidenceBoundary() {
        assertFalse(PromotionProofState.hasRuntimeEvidence(PromotionProofState.DRAFT_WORKSPACE));
        assertFalse(PromotionProofState.hasRuntimeEvidence(PromotionProofState.ACCEPTED_ARTIFACT));
        assertFalse(PromotionProofState.hasRuntimeEvidence(PromotionProofState.STATIC_PACKAGE_PROOF));
        assertFalse(PromotionProofState.hasRuntimeEvidence(PromotionProofState.DEPLOY_REQUESTED));
        assertTrue(PromotionProofState.hasRuntimeEvidence(PromotionProofState.RUNTIME_PROVED));
        assertTrue(PromotionProofState.hasRuntimeEvidence(PromotionProofState.PROMOTION_READY));
        assertTrue(PromotionProofState.hasRuntimeEvidence(PromotionProofState.PROMOTION_COMPLETE));
    }

    @Test
    @DisplayName("hasRuntimeEvidence with null returns false")
    void hasRuntimeEvidenceNull() {
        assertFalse(PromotionProofState.hasRuntimeEvidence(null));
    }

    // ── Enum completeness ───────────────────────────────────────

    @Test
    @DisplayName("Exactly 7 proof states exist")
    void exactlySevenStates() {
        assertEquals(7, PromotionProofState.values().length,
                "PKT-0008 requires exactly 7 proof states");
    }

    @Test
    @DisplayName("Only PROMOTION_COMPLETE is terminal")
    void onlyPromotionCompleteIsTerminal() {
        for (PromotionProofState s : PromotionProofState.values()) {
            if (s == PromotionProofState.PROMOTION_COMPLETE) {
                assertTrue(s.isTerminal(), s + " must be terminal");
            } else {
                assertFalse(s.isTerminal(), s + " must not be terminal");
            }
        }
    }

    // ── Linear-chain enforcement ────────────────────────────────

    @Test
    @DisplayName("Every non-terminal state has exactly one allowed successor")
    void linearChain() {
        for (PromotionProofState s : PromotionProofState.values()) {
            if (s.isTerminal()) continue;
            int outbound = 0;
            for (PromotionProofState target : PromotionProofState.values()) {
                if (PromotionProofState.canTransition(s, target)) outbound++;
            }
            assertEquals(1, outbound,
                    s + " must have exactly one allowed successor (linear chain)");
        }
    }
}
