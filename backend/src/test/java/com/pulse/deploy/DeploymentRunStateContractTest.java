package com.pulse.deploy;

import com.pulse.deploy.run.DeploymentRunState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 contract — deployment run state machine. Pins allowed and
 * disallowed transitions so a future "make this state-machine more
 * permissive" change shows up as an explicit test diff.
 */
class DeploymentRunStateContractTest {

    @Test
    @DisplayName("PENDING permits PREFLIGHT_RUNNING/PASSED/FAILED + CANCEL_REQUESTED + FAILED")
    void pendingTransitions() {
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.PENDING, DeploymentRunState.PREFLIGHT_RUNNING));
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.PENDING, DeploymentRunState.PREFLIGHT_PASSED));
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.PENDING, DeploymentRunState.PREFLIGHT_FAILED));
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.PENDING, DeploymentRunState.CANCEL_REQUESTED));
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.PENDING, DeploymentRunState.FAILED));
        // PENDING cannot skip preflight straight into MATERIALIZING.
        assertFalse(DeploymentRunState.canTransition(DeploymentRunState.PENDING, DeploymentRunState.MATERIALIZING));
        assertFalse(DeploymentRunState.canTransition(DeploymentRunState.PENDING, DeploymentRunState.SUCCEEDED));
    }

    @Test
    @DisplayName("PREFLIGHT_PASSED can move forward to MATERIALIZING; PREFLIGHT_FAILED is terminal")
    void preflightTransitions() {
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.PREFLIGHT_PASSED, DeploymentRunState.MATERIALIZING));
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.PREFLIGHT_PASSED, DeploymentRunState.CANCEL_REQUESTED));
        assertTrue(DeploymentRunState.PREFLIGHT_FAILED.isTerminal());
        for (DeploymentRunState s : DeploymentRunState.values()) {
            assertFalse(DeploymentRunState.canTransition(DeploymentRunState.PREFLIGHT_FAILED, s),
                    "PREFLIGHT_FAILED is terminal — " + s + " must not be reachable");
        }
    }

    @Test
    @DisplayName("Terminal states have zero outbound transitions")
    void terminalStatesHaveNoTransitions() {
        for (DeploymentRunState s : DeploymentRunState.values()) {
            if (!s.isTerminal()) continue;
            for (DeploymentRunState target : DeploymentRunState.values()) {
                assertFalse(DeploymentRunState.canTransition(s, target),
                        "terminal " + s + " must not transition to " + target);
            }
        }
    }

    @Test
    @DisplayName("CANCEL_REQUESTED can drop into CANCELLED, FAILED, or TIMED_OUT only")
    void cancelRequestedTransitions() {
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.CANCEL_REQUESTED, DeploymentRunState.CANCELLED));
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.CANCEL_REQUESTED, DeploymentRunState.FAILED));
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.CANCEL_REQUESTED, DeploymentRunState.TIMED_OUT));
        assertFalse(DeploymentRunState.canTransition(DeploymentRunState.CANCEL_REQUESTED, DeploymentRunState.SUCCEEDED));
    }

    @Test
    @DisplayName("Self-transitions are never legal (no PENDING -> PENDING)")
    void selfTransitionsRejected() {
        for (DeploymentRunState s : DeploymentRunState.values()) {
            assertFalse(DeploymentRunState.canTransition(s, s),
                    "self-transition " + s + " -> " + s + " must be rejected");
        }
    }

    @Test
    @DisplayName("RUNNING can finish in SUCCEEDED, FAILED, CANCEL_REQUESTED, or TIMED_OUT")
    void runningTransitions() {
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.RUNNING, DeploymentRunState.SUCCEEDED));
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.RUNNING, DeploymentRunState.FAILED));
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.RUNNING, DeploymentRunState.TIMED_OUT));
        assertTrue(DeploymentRunState.canTransition(DeploymentRunState.RUNNING, DeploymentRunState.CANCEL_REQUESTED));
        assertFalse(DeploymentRunState.canTransition(DeploymentRunState.RUNNING, DeploymentRunState.MATERIALIZING));
    }
}
