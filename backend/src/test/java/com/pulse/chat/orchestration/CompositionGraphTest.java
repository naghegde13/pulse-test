package com.pulse.chat.orchestration;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 graph-wiring + routing tests (H2 fast lane, mock stage runner — no LLM).
 * Asserts the StateGraph compiles (no orphan nodes/bad edges), the recursion
 * bound is exposed, and the deterministic routing helpers honor the phase
 * channels (route_next_phase is NOT an LLM re-ask).
 */
class CompositionGraphTest {

    /** A stage runner that records which stages ran and drives a fixed route. */
    static class RecordingRunner implements CompositionGraph.StageRunner {
        final AtomicInteger composerRuns = new AtomicInteger();
        final AtomicInteger drainRuns = new AtomicInteger();
        final AtomicInteger responderRuns = new AtomicInteger();

        public Map<String, Object> runRouter(AgentState s) {
            // Route straight to composer for this test.
            return Map.of(AgentState.PHASE, CompositionGraph.COMPOSER);
        }
        public Map<String, Object> runDiscovery(AgentState s) { return Map.of(); }
        public Map<String, Object> runComposer(AgentState s) {
            composerRuns.incrementAndGet();
            // After composing, signal the turn should settle at responder.
            return Map.of(AgentState.NEXT_PHASE, CompositionGraph.RESPONDER);
        }
        public Map<String, Object> runConfigure(AgentState s) { return Map.of(); }
        public Map<String, Object> runProvision(AgentState s) { return Map.of(); }
        public Map<String, Object> runPlanner(AgentState s) { return Map.of(); }
        public Map<String, Object> runResponder(AgentState s) {
            responderRuns.incrementAndGet();
            return Map.of();
        }
        public Map<String, Object> processOperations(AgentState s) {
            drainRuns.incrementAndGet();
            return Map.of();
        }
        public Map<String, Object> apply(AgentState s) { return Map.of(); }
    }

    @Test
    void graphCompilesAndExposesRecursionBound() throws Exception {
        CompositionGraph graph = new CompositionGraph(new RecordingRunner(), 40);
        assertEquals(40, graph.recursionLimit());
        CompiledGraph<AgentState> compiled = graph.compile(new MemorySaver());
        assertNotNull(compiled, "StateGraph must compile with the MemorySaver checkpointer");
    }

    @Test
    void routerToComposerToDrainToResponderRunsEachOnce() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        CompositionGraph graph = new CompositionGraph(runner, 40);
        CompiledGraph<AgentState> compiled = graph.compile(new MemorySaver());

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(AgentState.PHASE, CompositionGraph.ROUTER);

        var result = compiled.invoke(inputs);
        assertTrue(result.isPresent(), "the graph turn must produce a final state");
        // composer ran, the op-queue drain ran after it, the responder closed the turn.
        assertEquals(1, runner.composerRuns.get(), "composer runs once");
        assertEquals(1, runner.drainRuns.get(), "process_operations drains once after composer");
        assertEquals(1, runner.responderRuns.get(), "responder closes the turn once");
    }

    @Test
    void routeNextPhaseIsDeterministicNotLlmReask() {
        // route_next_phase honors the nextPhase channel, never re-asks an LLM.
        AgentState s = new AgentState(Map.of(AgentState.NEXT_PHASE, CompositionGraph.PLANNER));
        Map<String, Object> out = CompositionGraph.routeNextPhase(s);
        assertEquals(CompositionGraph.PLANNER, out.get(AgentState.NEXT_PHASE));

        // An unknown phase normalizes to responder (graceful halt, not a throw).
        AgentState bogus = new AgentState(Map.of(AgentState.NEXT_PHASE, "totally-bogus"));
        assertEquals(CompositionGraph.RESPONDER, CompositionGraph.routeNextPhase(bogus).get(AgentState.NEXT_PHASE));
    }
}
