package com.pulse.chat.orchestration;

import com.pulse.chat.diff.CompareGraphs;
import com.pulse.chat.orchestration.StagingGraph.StagingInstance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 op-queue + staging fold + P5 diff units (H2 fast lane, in-memory).
 */
class OpQueueStagingTest {

    // ---- OpQueue reducer ----

    @Test
    void reducerAppends() {
        var a = new PlanOperation.AddInstances(
                List.of(new PlanOperation.InstanceSpec("read", "SourceSQL", null, null, null)), "r1");
        var b = new PlanOperation.AddInstances(
                List.of(new PlanOperation.InstanceSpec("clean", "BronzeToSilverCleaning", null, null, null)), "r2");
        var q = OpQueue.reduce(List.of(a), List.of(b));
        assertEquals(2, q.size());
    }

    @Test
    void reducerNullResets() {
        var a = new PlanOperation.AddInstances(List.of(), "r1");
        assertTrue(OpQueue.reduce(List.of(a), null).isEmpty(), "null update resets the queue");
    }

    @Test
    void reducerClearWipes() {
        var a = new PlanOperation.AddInstances(List.of(), "r1");
        var clear = new PlanOperation.Clear();
        var q = OpQueue.reduce(List.of(a, a), List.of(clear));
        assertEquals(1, q.size());
        assertTrue(q.get(0) instanceof PlanOperation.Clear);
    }

    @Test
    void reducerEmptyUpdateKeepsCurrent() {
        var a = new PlanOperation.AddInstances(List.of(), "r1");
        assertEquals(1, OpQueue.reduce(List.of(a), List.of()).size());
    }

    // ---- StagingGraph fold ----

    @Test
    void applyOpsBuildsStagingAndNeverMutatesBase() {
        StagingGraph base = StagingGraph.empty();
        var add = new PlanOperation.AddInstances(List.of(
                new PlanOperation.InstanceSpec("read", "SourceSQL", null, null, null),
                new PlanOperation.InstanceSpec("clean", "BronzeToSilverCleaning", null, null, null)), "add two");
        var wire = new PlanOperation.MergeWiring(List.of(
                new PlanOperation.WireSpec("read", "source_output", "clean", "input")), "wire them");

        StagingGraph staged = StagingGraph.applyOps(base, List.of(add, wire));

        assertEquals(0, base.instances().size(), "base is never mutated (immutable fold)");
        assertEquals(2, staged.instances().size());
        assertEquals(1, staged.wirings().size());
        assertEquals("SourceSQL", staged.instance("read").blueprintKey());
    }

    @Test
    void mergeWiringDedupsOnFullTuple() {
        StagingGraph base = StagingGraph.applyOps(StagingGraph.empty(), List.of(
                new PlanOperation.AddInstances(List.of(
                        new PlanOperation.InstanceSpec("a", "X", null, null, null),
                        new PlanOperation.InstanceSpec("b", "Y", null, null, null)), "add")));
        var w = new PlanOperation.WireSpec("a", "out", "b", "in");
        StagingGraph staged = StagingGraph.applyOps(base, List.of(
                new PlanOperation.MergeWiring(List.of(w), "1"),
                new PlanOperation.MergeWiring(List.of(w), "2")));
        assertEquals(1, staged.wirings().size(), "duplicate wire deduped on the 4-tuple");
    }

    @Test
    void updateInstanceMergesParamsStructured() {
        StagingGraph base = StagingGraph.applyOps(StagingGraph.empty(), List.of(
                new PlanOperation.AddInstances(List.of(
                        new PlanOperation.InstanceSpec("clean", "BronzeToSilverCleaning", null, null, null)), "add")));
        StagingGraph staged = StagingGraph.applyOps(base, List.of(
                new PlanOperation.UpdateInstance("clean", Map.of("k1", "v1"), null, null, null, "set k1"),
                new PlanOperation.UpdateInstance("clean", Map.of("k2", "v2"), null, "silver", null, "set k2")));
        StagingInstance i = staged.instance("clean");
        assertEquals("v1", i.params().get("k1"));
        assertEquals("v2", i.params().get("k2"));
        assertEquals("silver", i.lakeLayer());
    }

    @Test
    void removeInstanceDropsIncidentWirings() {
        StagingGraph base = StagingGraph.applyOps(StagingGraph.empty(), List.of(
                new PlanOperation.AddInstances(List.of(
                        new PlanOperation.InstanceSpec("a", "X", null, null, null),
                        new PlanOperation.InstanceSpec("b", "Y", null, null, null)), "add"),
                new PlanOperation.MergeWiring(List.of(
                        new PlanOperation.WireSpec("a", "out", "b", "in")), "wire")));
        StagingGraph staged = StagingGraph.applyOps(base, List.of(
                new PlanOperation.RemoveInstance("b", "drop b")));
        assertNull(staged.instance("b"));
        assertTrue(staged.wirings().isEmpty(), "incident wirings dropped with the instance");
    }

    @Test
    void clearResetsGraph() {
        StagingGraph base = StagingGraph.applyOps(StagingGraph.empty(), List.of(
                new PlanOperation.AddInstances(List.of(
                        new PlanOperation.InstanceSpec("a", "X", null, null, null)), "add")));
        StagingGraph staged = StagingGraph.applyOps(base, List.of(new PlanOperation.Clear()));
        assertTrue(staged.instances().isEmpty());
    }

    @Test
    void renameFixesWiringReferences() {
        StagingGraph base = StagingGraph.applyOps(StagingGraph.empty(), List.of(
                new PlanOperation.AddInstances(List.of(
                        new PlanOperation.InstanceSpec("a", "X", null, null, null),
                        new PlanOperation.InstanceSpec("b", "Y", null, null, null)), "add"),
                new PlanOperation.MergeWiring(List.of(
                        new PlanOperation.WireSpec("a", "out", "b", "in")), "wire")));
        StagingGraph staged = StagingGraph.applyOps(base, List.of(
                new PlanOperation.Rename("a", "source", "rename a")));
        assertNull(staged.instance("a"));
        assertEquals("X", staged.instance("source").blueprintKey());
        assertEquals("source", staged.wirings().get(0).sourceRef());
    }

    // ---- CompareGraphs diff ----

    @Test
    void diffFourStatuses() {
        StagingGraph canonical = StagingGraph.applyOps(StagingGraph.empty(), List.of(
                new PlanOperation.AddInstances(List.of(
                        new PlanOperation.InstanceSpec("keep", "X", null, null, null),
                        new PlanOperation.InstanceSpec("drop", "Y", null, null, null),
                        new PlanOperation.InstanceSpec("mod", "Z", null, null, null)), "seed")));
        StagingGraph staging = StagingGraph.applyOps(canonical, List.of(
                new PlanOperation.RemoveInstance("drop", "rm"),
                new PlanOperation.UpdateInstance("mod", Map.of("p", "1"), null, null, null, "mod"),
                new PlanOperation.AddInstances(List.of(
                        new PlanOperation.InstanceSpec("new", "W", null, null, null)), "add")));

        CompareGraphs.GraphDiff diff = CompareGraphs.compare(canonical, staging);
        assertEquals(CompareGraphs.Status.EQUAL, diff.instances().get("keep").status());
        assertEquals(CompareGraphs.Status.DELETED, diff.instances().get("drop").status());
        assertEquals(CompareGraphs.Status.MODIFIED, diff.instances().get("mod").status());
        assertEquals(CompareGraphs.Status.ADDED, diff.instances().get("new").status());
        // "Review N changes": deleted + modified + added = 3.
        assertEquals(3, diff.changeCount());
    }

    @Test
    void wiringDiffSetDelta() {
        StagingGraph canonical = StagingGraph.applyOps(StagingGraph.empty(), List.of(
                new PlanOperation.AddInstances(List.of(
                        new PlanOperation.InstanceSpec("a", "X", null, null, null),
                        new PlanOperation.InstanceSpec("b", "Y", null, null, null)), "seed"),
                new PlanOperation.MergeWiring(List.of(
                        new PlanOperation.WireSpec("a", "out", "b", "in")), "wire")));
        StagingGraph staging = StagingGraph.applyOps(canonical, List.of(
                new PlanOperation.RemoveWiring(new PlanOperation.WireSpec("a", "out", "b", "in"), "unwire")));
        CompareGraphs.GraphDiff diff = CompareGraphs.compare(canonical, staging);
        assertFalse(diff.wirings().isEmpty());
        assertEquals(CompareGraphs.Status.DELETED, diff.wirings().get(0).status());
        assertEquals(1, diff.changeCount());
    }
}
