package com.pulse.chat.orchestration;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * The Chat orchestration {@code StateGraph} (ADR 0025 §1) — the 7 LOCKED stages
 * as real graph nodes, mapped ~1:1 onto n8n's
 * {@code multi-agent-workflow-subgraphs.ts} (07-orchestration §1.1):
 * {@code router · discovery · composer · configure · provision · planner ·
 * responder}, plus {@code process_operations} (the op-queue drain, Phase 3) and
 * {@code route_next_phase} (deterministic next-phase from {@code coordinationLog},
 * NOT an LLM re-ask).
 *
 * <p>The Plan-Preview approval gate is an {@code interruptBefore} on the
 * {@code apply} node ({@link PlanGate}); the {@code langgraph4j-postgres-saver}
 * checkpointer ({@link CheckpointerConfig}) is the snapshot/undo store.</p>
 *
 * <p>The node BODIES are supplied by a {@link StageRunner} (the driver), so the
 * stage/tool/prompt logic stays OUT of langgraph4j types where cheap (RISK #1
 * mitigation): this class owns only the wiring, the deterministic routing, the
 * recursion bound, and the interrupt gate.</p>
 */
public class CompositionGraph {

    // Node ids (== route strings; lowercase, no hyphens per WORKLIST §1).
    public static final String ROUTER = "router";
    public static final String DISCOVERY = "discovery";
    public static final String COMPOSER = "composer";
    public static final String CONFIGURE = "configure";
    public static final String PROVISION = "provision";
    public static final String PLANNER = "planner";
    public static final String RESPONDER = "responder";
    public static final String PROCESS_OPERATIONS = "process_operations";
    public static final String ROUTE_NEXT_PHASE = "route_next_phase";
    public static final String APPLY = "apply";

    /**
     * The driver supplies each stage's body. A stage returns a map of
     * {@link AgentState} channel updates (e.g. {@code nextPhase},
     * {@code opQueue}, {@code coordinationLog}). The graph wiring + routing is
     * fixed; only the bodies vary.
     */
    public interface StageRunner {
        Map<String, Object> runRouter(AgentState state) throws Exception;
        Map<String, Object> runDiscovery(AgentState state) throws Exception;
        Map<String, Object> runComposer(AgentState state) throws Exception;
        Map<String, Object> runConfigure(AgentState state) throws Exception;
        Map<String, Object> runProvision(AgentState state) throws Exception;
        Map<String, Object> runPlanner(AgentState state) throws Exception;
        Map<String, Object> runResponder(AgentState state) throws Exception;
        /** Drain the op-queue into the STAGING graph (Phase 3). */
        Map<String, Object> processOperations(AgentState state) throws Exception;
        /** The sole canonical writer (Phase 4); runs AFTER the interrupt resolves approve. */
        Map<String, Object> apply(AgentState state) throws Exception;
    }

    private final StageRunner runner;
    private final int recursionLimit;

    public CompositionGraph(StageRunner runner, int recursionLimit) {
        this.runner = runner;
        this.recursionLimit = recursionLimit;
    }

    /** Build the StateGraph wiring (uncompiled). Visible for routing/structure tests. */
    public StateGraph<AgentState> build() throws GraphStateException {
        StateGraph<AgentState> g = new StateGraph<>(AgentState.SCHEMA, AgentState::new);

        g.addNode(ROUTER, node_async(wrap(runner::runRouter)));
        g.addNode(DISCOVERY, node_async(wrap(runner::runDiscovery)));
        g.addNode(COMPOSER, node_async(wrap(runner::runComposer)));
        g.addNode(CONFIGURE, node_async(wrap(runner::runConfigure)));
        g.addNode(PROVISION, node_async(wrap(runner::runProvision)));
        g.addNode(PLANNER, node_async(wrap(runner::runPlanner)));
        g.addNode(RESPONDER, node_async(wrap(runner::runResponder)));
        g.addNode(PROCESS_OPERATIONS, node_async(wrap(runner::processOperations)));
        g.addNode(ROUTE_NEXT_PHASE, node_async(CompositionGraph::routeNextPhase));
        g.addNode(APPLY, node_async(wrap(runner::apply)));

        // Entry: the router classifies the turn.
        g.addEdge(START, ROUTER);
        // The router picks the first working phase deterministically from its output.
        g.addConditionalEdges(ROUTER, edge_async(CompositionGraph::routeFromPhaseChannel),
                Map.of(
                        DISCOVERY, DISCOVERY,
                        COMPOSER, COMPOSER,
                        CONFIGURE, CONFIGURE,
                        PROVISION, PROVISION,
                        PLANNER, PLANNER,
                        RESPONDER, RESPONDER));

        // composer/configure mutate the op-queue → drain to STAGING, then re-route.
        g.addEdge(COMPOSER, PROCESS_OPERATIONS);
        g.addEdge(CONFIGURE, PROCESS_OPERATIONS);
        g.addEdge(PROCESS_OPERATIONS, ROUTE_NEXT_PHASE);

        // provision/discovery feed forward to deterministic re-routing.
        g.addEdge(DISCOVERY, ROUTE_NEXT_PHASE);
        g.addEdge(PROVISION, ROUTE_NEXT_PHASE);

        // route_next_phase loops back to the next working stage (or terminates via responder/planner).
        g.addConditionalEdges(ROUTE_NEXT_PHASE, edge_async(CompositionGraph::routeFromNextPhaseChannel),
                Map.of(
                        DISCOVERY, DISCOVERY,
                        COMPOSER, COMPOSER,
                        CONFIGURE, CONFIGURE,
                        PROVISION, PROVISION,
                        PLANNER, PLANNER,
                        RESPONDER, RESPONDER));

        // The planner emits the Plan Preview, then the graph pauses at the apply
        // gate (interruptBefore APPLY). Resume = decision; approve → apply.
        g.addEdge(PLANNER, APPLY);
        g.addEdge(APPLY, RESPONDER);

        // The responder synthesizes the user-facing reply and ends the turn.
        g.addEdge(RESPONDER, END);

        return g;
    }

    /** The recursion bound enforced by the driver loop (MAX_TOOL_ROUNDS, ADR 0025 §1; 3-03 = 40). */
    public int recursionLimit() {
        return recursionLimit;
    }

    /** Compile with the checkpointer + the interruptBefore plan gate (ADR 0025 §1/§3). */
    public CompiledGraph<AgentState> compile(BaseCheckpointSaver checkpointer) throws GraphStateException {
        // NOTE: the beta5 CompileConfig.Builder has no recursionLimit(int) (added
        // on a later train); the bound is enforced by the driver's tool-round
        // counter (graceful-halt, not a throw — 07 §1.2). The interruptBefore on
        // APPLY is the Plan-Preview HITL gate.
        CompileConfig config = CompileConfig.builder()
                .checkpointSaver(checkpointer)
                .interruptBefore(APPLY)   // PlanGate: pause before the sole canonical writer
                .releaseThread(false)
                .build();
        return build().compile(config);
    }

    // ------------------------------------------------------------------
    // Deterministic routing (NOT an LLM re-ask).
    // ------------------------------------------------------------------

    /** Route off the {@code phase} channel the router set. */
    static String routeFromPhaseChannel(AgentState state) {
        return normalizePhase(state.<String>value(AgentState.PHASE).orElse(RESPONDER));
    }

    /** Route off the {@code nextPhase} channel route_next_phase set. */
    static String routeFromNextPhaseChannel(AgentState state) {
        return normalizePhase(state.nextPhase().orElse(RESPONDER));
    }

    /**
     * The {@code route_next_phase} node: deterministically pick the next phase
     * from the turn's progress ({@code coordinationLog}) — the n8n
     * {@code getNextPhaseFromLog} analogue. If the driver already set
     * {@code nextPhase}, honor it; otherwise default to responder (turn settles).
     */
    static Map<String, Object> routeNextPhase(AgentState state) {
        String next = state.nextPhase().orElse(RESPONDER);
        return Map.of(AgentState.NEXT_PHASE, normalizePhase(next));
    }

    private static String normalizePhase(String phase) {
        if (phase == null) return RESPONDER;
        return switch (phase) {
            case ROUTER, DISCOVERY, COMPOSER, CONFIGURE, PROVISION, PLANNER, RESPONDER -> phase;
            default -> RESPONDER;
        };
    }

    /** Adapt a checked-throwing stage body to a {@link NodeAction}. */
    private static NodeAction<AgentState> wrap(ThrowingStage body) {
        return state -> {
            try {
                Map<String, Object> out = body.run(state);
                return out == null ? Map.of() : out;
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingStage {
        Map<String, Object> run(AgentState state) throws Exception;
    }
}
