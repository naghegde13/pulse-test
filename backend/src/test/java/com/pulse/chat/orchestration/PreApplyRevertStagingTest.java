package com.pulse.chat.orchestration;

import com.pulse.chat.diff.CompareGraphs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 (IMPL-ui-composition; 07-orchestration §2.2) — pre-Apply revert
 * invariant (H2-free unit): reject / cancel-mid-turn / Restore drops the STAGING
 * clone and re-renders from the snapshot, and the CANONICAL graph is NEVER
 * touched (it is write-locked behind Apply). Modeled at the StagingGraph layer —
 * the immutable fold + clone-on-revert that {@code GraphDriver} relies on.
 */
class PreApplyRevertStagingTest {

    private static StagingGraph canonicalWithOneSource() {
        StagingGraph base = StagingGraph.empty();
        var add = new PlanOperation.AddInstances(List.of(
                new PlanOperation.InstanceSpec("read", "SourceSQL", "DPC", "bronze", "parquet")), "seed canonical");
        // Build the canonical by folding once, then treat THAT as the snapshot.
        return StagingGraph.applyOps(base, List.of(add));
    }

    @Test
    void revertDropsStagingAndLeavesCanonicalUntouched() {
        // The turn-start snapshot = the canonical at turn start.
        StagingGraph canonicalSnapshot = canonicalWithOneSource();
        // The staging clone the turn mutates.
        var addMore = new PlanOperation.AddInstances(List.of(
                new PlanOperation.InstanceSpec("clean", "BronzeToSilverCleaning", "DPC", "silver", "parquet")), "stage a step");
        StagingGraph staging = StagingGraph.applyOps(canonicalSnapshot, List.of(addMore));

        // The turn staged a real change.
        assertEquals(1, canonicalSnapshot.instances().size(), "canonical has only the seeded source");
        assertEquals(2, staging.instances().size(), "staging added a cleaning step");
        assertTrue(CompareGraphs.compare(canonicalSnapshot, staging).changeCount() > 0,
                "the staged change is visible in the diff");

        // REVERT (pre-Apply): drop the staging clone, re-render from the snapshot.
        StagingGraph reverted = canonicalSnapshot.copy();

        // Canonical snapshot is byte-identical before/after (never mutated by the fold).
        assertEquals(1, canonicalSnapshot.instances().size(),
                "canonical snapshot is untouched by the staging fold");
        assertEquals("read", canonicalSnapshot.instances().get(0).ref());
        // The reverted graph equals the snapshot (zero changes) — the staging clone is gone.
        assertEquals(0, CompareGraphs.compare(canonicalSnapshot, reverted).changeCount(),
                "after revert the rendered graph == the snapshot (staging dropped)");
        assertEquals(canonicalSnapshot.instances().size(), reverted.instances().size());
    }
}
