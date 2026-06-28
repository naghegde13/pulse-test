package com.pulse.chat.orchestration;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 (IMPL-ui-composition; ADR 0025 §3) — the checkpointer round-trip IT.
 *
 * <p><b>Postgres lane only.</b> This IT runs in the {@code backendIntegrationTest}
 * (postgres-it) lane — NOT {@code fastPrTest}: it asserts the
 * {@code langgraph4j-postgres-saver} {@link PostgresSaver} (the snapshot/undo
 * STORE) actually round-trips a per-turn snapshot through a REAL Postgres, whose
 * schema cannot be faithfully exercised on H2. It is {@code @Tag("integration")}
 * and intentionally relies on the default boot config so {@link CheckpointerConfig}
 * builds the {@link PostgresSaver} against the postgres-it datasource.</p>
 *
 * <p>Asserts: a turn checkpoints (thread = sessionId, checkpoint = turn) and
 * {@code getState(config)} restores the per-turn snapshot — the canonical
 * {@code COMPOSITION_VIEW} captured at turn start — verbatim.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres-it")
@Tag("integration")
class ChatCheckpointerRoundTripIT {

    @Autowired
    private BaseCheckpointSaver checkpointSaver;

    @Test
    void postgresSaverIsWired() {
        assertThat(checkpointSaver)
                .as("default postgres-it boot must build the langgraph4j PostgresSaver")
                .isInstanceOf(PostgresSaver.class);
    }

    @Test
    void turnCheckpointsAndGetStateRestoresThePerTurnSnapshot() throws Exception {
        // A unique thread per run so re-runs against the same Postgres are isolated.
        String sessionId = "it-session-" + UUID.randomUUID();

        // A minimal StageRunner that settles the turn immediately at responder.
        // The point of this IT is the CHECKPOINTER round-trip, not the LLM path:
        // the per-turn snapshot (COMPOSITION_VIEW) is carried as graph input and
        // must survive the Postgres checkpoint + getState restore.
        CompositionGraph graph = new CompositionGraph(new SettleAtResponderRunner(), 40);
        CompiledGraph<AgentState> compiled = graph.compile(checkpointSaver);

        // The per-turn snapshot = a serializable graph payload (Map/List/String),
        // exactly what GraphDriver writes to COMPOSITION_VIEW at turn start.
        Map<String, Object> snapshot = Map.of(
                "name", "demo-pipeline",
                "instances", List.of(Map.of(
                        "ref", "read", "blueprintKey", "SourceSQL",
                        "params", Map.of("query", "select 1"),
                        "storageBackend", "DPC", "lakeLayer", "bronze", "lakeFormat", "parquet")),
                "wirings", List.of());

        Map<String, Object> inputs = new HashMap<>();
        inputs.put(AgentState.PHASE, CompositionGraph.RESPONDER);
        inputs.put(AgentState.SESSION_ID, sessionId);
        inputs.put(AgentState.COMPOSITION_VIEW, snapshot);
        inputs.put(AgentState.STAGING_GRAPH, snapshot);

        RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
        compiled.invoke(inputs, config);

        // getState on the same thread restores the checkpointed per-turn snapshot.
        var state = compiled.getState(config);
        assertThat(state).as("a checkpoint exists for the turn's thread").isNotNull();
        AgentState restored = state.state();
        assertThat(restored).isNotNull();

        Object view = restored.value(AgentState.COMPOSITION_VIEW).orElse(null);
        assertThat(view)
                .as("getState restores the per-turn canonical snapshot (COMPOSITION_VIEW)")
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> restoredView = (Map<String, Object>) view;
        assertThat(restoredView.get("name")).isEqualTo("demo-pipeline");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> restoredInstances =
                (List<Map<String, Object>>) restoredView.get("instances");
        assertThat(restoredInstances).hasSize(1);
        assertThat(restoredInstances.get(0).get("ref")).isEqualTo("read");
        assertThat(restoredInstances.get(0).get("blueprintKey")).isEqualTo("SourceSQL");
    }

    /** Settles every stage straight to responder/END — no LLM, no tools. */
    private static final class SettleAtResponderRunner implements CompositionGraph.StageRunner {
        @Override public Map<String, Object> runRouter(AgentState s) { return Map.of(AgentState.PHASE, CompositionGraph.RESPONDER); }
        @Override public Map<String, Object> runDiscovery(AgentState s) { return Map.of(AgentState.NEXT_PHASE, CompositionGraph.RESPONDER); }
        @Override public Map<String, Object> runComposer(AgentState s) { return Map.of(AgentState.NEXT_PHASE, CompositionGraph.RESPONDER); }
        @Override public Map<String, Object> runConfigure(AgentState s) { return Map.of(AgentState.NEXT_PHASE, CompositionGraph.RESPONDER); }
        @Override public Map<String, Object> runProvision(AgentState s) { return Map.of(AgentState.NEXT_PHASE, CompositionGraph.RESPONDER); }
        @Override public Map<String, Object> runPlanner(AgentState s) { return Map.of(AgentState.NEXT_PHASE, CompositionGraph.RESPONDER); }
        @Override public Map<String, Object> runResponder(AgentState s) { return Map.of(AgentState.NEXT_PHASE, CompositionGraph.RESPONDER); }
        @Override public Map<String, Object> processOperations(AgentState s) { return Map.of(AgentState.OP_QUEUE, null); }
        @Override public Map<String, Object> apply(AgentState s) { return Map.of(AgentState.NEXT_PHASE, CompositionGraph.RESPONDER); }
    }
}
