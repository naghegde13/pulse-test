package com.pulse.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.chat.model.ChatMessage;
import com.pulse.chat.prompt.StagePromptAssembler;
import com.pulse.chat.repository.ChatMessageRepository;
import com.pulse.chat.service.ChatService;
import com.pulse.chat.service.ChatService.StreamResult;
import com.pulse.chat.service.ChatToolExecutor;
import com.pulse.command.service.PlanService;
import com.pulse.command.service.PlanService.PlannedCommand;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.pipeline.service.CompositionService.CompositionView;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The orchestration spine (IMPL-ui-composition Phase 1/3/4) — the
 * {@link CompositionGraph.StageRunner} implementation that drives the 7-stage
 * StateGraph for a chat turn. It replaces {@code ChatService.handleLLMMode}'s
 * inline {@code while}-loop (behind the {@code pulse.chat.orchestration=graph}
 * flag, default graph).
 *
 * <p>Per turn (held in a {@link ThreadLocal} {@link TurnContext} because the
 * graph runs synchronously on one executor thread):</p>
 * <ol>
 *   <li><b>router</b> classifies the turn and routes to composer.</li>
 *   <li><b>composer</b> streams the reasoning-tier LLM, executes tool calls:
 *       read tools → {@link ChatToolExecutor} (against the STAGING view);
 *       mutation tools → {@link MutationToolService} → enqueue ONE op.
 *       Bounded by {@code MAX_TOOL_ROUNDS=40} (graceful halt, no throw).</li>
 *   <li><b>process_operations</b> drains the op-queue as one immutable fold onto
 *       the STAGING graph, resets the queue, emits one {@code candidate_graph}.</li>
 *   <li>If mutations staged → a PREVIEW {@link com.pulse.command.model.Plan} is
 *       created + a {@code plan} event emitted (the Plan-Preview gate); apply is
 *       deferred to the decision endpoint (P4).</li>
 *   <li><b>responder</b> closes the turn (final assistant reply already streamed).</li>
 * </ol>
 *
 * <p>The SSE emitter plumbing is RETAINED via {@link ChatService} (chunk /
 * tool_call / tool_result / navigate / candidate_graph / plan / done / error),
 * so the frontend reads the SAME events.</p>
 */
@Component
public class GraphDriver implements CompositionGraph.StageRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphDriver.class);

    // Phase 5 SSE event names (underscore, per WORKLIST-RESOLUTIONS §1). These
    // join the existing chunk/tool_call/tool_result/navigate/candidate_graph/
    // plan/done events the frontend already reads.
    public static final String EVENT_TOOL_PROGRESS = "tool_progress";
    public static final String EVENT_QUESTIONS = "questions";
    // Emitted ONLY by the compaction path (Phase 7, a separate concern). The
    // name constant lives here so the frontend contract knows the string; it is
    // intentionally not triggered from this driver.
    public static final String EVENT_MESSAGES_COMPACTED = "messages_compacted";

    private final NodeLlmAdapter llmAdapter;
    private final ChatService chatService;
    private final MutationToolService mutationToolService;
    private final ChatToolExecutor toolExecutor;
    private final CompositionService compositionService;
    private final PlanService planService;
    private final PipelineRepository pipelineRepo;
    private final ChatMessageRepository messageRepo;
    private final BaseCheckpointSaver checkpointSaver;
    private final ObjectMapper objectMapper;
    private final StagePromptAssembler stagePromptAssembler;
    private final com.pulse.chat.service.HistoryCompactor historyCompactor;

    /**
     * Per-turn mutable context, keyed by sessionId. Keyed (not ThreadLocal)
     * because langgraph4j may run {@code node_async} bodies on a pool thread, so
     * a node looks its context up via the {@code SESSION_ID} channel — available
     * to every node — rather than relying on thread affinity.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, TurnContext> turns =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public GraphDriver(NodeLlmAdapter llmAdapter,
                       ChatService chatService,
                       MutationToolService mutationToolService,
                       ChatToolExecutor toolExecutor,
                       CompositionService compositionService,
                       PlanService planService,
                       PipelineRepository pipelineRepo,
                       ChatMessageRepository messageRepo,
                       BaseCheckpointSaver checkpointSaver,
                       ObjectMapper objectMapper,
                       StagePromptAssembler stagePromptAssembler,
                       com.pulse.chat.service.HistoryCompactor historyCompactor) {
        this.llmAdapter = llmAdapter;
        this.chatService = chatService;
        this.mutationToolService = mutationToolService;
        this.toolExecutor = toolExecutor;
        this.compositionService = compositionService;
        this.planService = planService;
        this.pipelineRepo = pipelineRepo;
        this.messageRepo = messageRepo;
        this.checkpointSaver = checkpointSaver;
        this.objectMapper = objectMapper;
        this.stagePromptAssembler = stagePromptAssembler;
        this.historyCompactor = historyCompactor;
    }

    /** The per-turn carrier the stage bodies share (NOT in AgentState — non-serializable). */
    static final class TurnContext {
        final String sessionId;
        final String tenantId;
        final String pipelineId;
        final String versionId;
        final SseEmitter emitter;
        final AtomicBoolean emitterDead;
        final List<Map<String, Object>> messages;
        // The canonical snapshot at turn start (the revert point) + the per-turn
        // staging graph (clone of canonical + applied ops).
        StagingGraph canonicalSnapshot;
        StagingGraph staging;
        // pendingOps: ops queued but not yet drained this superstep.
        final List<PlanOperation> pendingOps = new ArrayList<>();
        // stagedOps: the full set of ops drained into staging this turn (the
        // Plan-Preview content), preserved across the process_operations reset.
        final List<PlanOperation> stagedOps = new ArrayList<>();
        boolean mutationsStaged = false;
        String createdPlanId;

        List<PlanOperation> stagedOpsSnapshot() { return new ArrayList<>(stagedOps); }

        TurnContext(String sessionId, String tenantId, String pipelineId, String versionId,
                    SseEmitter emitter, AtomicBoolean emitterDead, List<Map<String, Object>> messages) {
            this.sessionId = sessionId;
            this.tenantId = tenantId;
            this.pipelineId = pipelineId;
            this.versionId = versionId;
            this.emitter = emitter;
            this.emitterDead = emitterDead;
            this.messages = messages;
        }
    }

    /**
     * Drive one chat turn through the compiled graph. Called by
     * {@code ChatService.handleGraphMode}. Builds the initial AgentState inputs,
     * compiles the graph with the checkpointer, and invokes keyed by
     * threadId=sessionId.
     */
    public void handleTurn(String sessionId, String tenantId, String pipelineId,
                           SseEmitter emitter, AtomicBoolean emitterDead) throws Exception {
        String versionId = resolveActiveVersionId(pipelineId);
        List<Map<String, Object>> messages = chatService.rebuildMessagesForSession(sessionId, tenantId, pipelineId);

        // Phase 7: token-based history auto-compaction. When the rebuilt history
        // crosses the configured fraction of the context budget, fold the older
        // turns into a conversation-summary block and emit `messages_compacted`
        // so the frontend can reflect the trim. The deterministic summary keeps
        // this hermetic; reasoningCall is available but off by default.
        var compaction = historyCompactor.compact(messages);
        if (compaction.compacted()) {
            messages = compaction.messages();
            emitMessagesCompacted(emitter, emitterDead, compaction);
        }

        TurnContext ctx = new TurnContext(sessionId, tenantId, pipelineId, versionId,
                emitter, emitterDead, messages);
        ctx.canonicalSnapshot = loadCanonical(pipelineId, versionId);
        ctx.staging = ctx.canonicalSnapshot.copy();
        turns.put(sessionId, ctx);
        try {
            CompositionGraph graph = new CompositionGraph(this, ChatService.MAX_TOOL_ROUNDS);
            CompiledGraph<AgentState> compiled = graph.compile(checkpointSaver);

            Map<String, Object> inputs = new HashMap<>();
            inputs.put(AgentState.PHASE, CompositionGraph.ROUTER);
            inputs.put(AgentState.TENANT_ID, tenantId);
            inputs.put(AgentState.SESSION_ID, sessionId);
            if (pipelineId != null) inputs.put(AgentState.PIPELINE_ID, pipelineId);
            if (versionId != null) inputs.put(AgentState.VERSION_ID, versionId);
            // Phase 7: the per-turn snapshot lives on AgentState so the
            // checkpointer (the snapshot STORE, ADR 0025 §3) persists it. Both
            // are SERIALIZABLE projections (plain Map/List/String) — the
            // ObjectStreamStateSerializer round-trips them; the live
            // StagingGraph/CompositionView value objects are NOT Serializable and
            // stay in the (non-checkpointed) TurnContext. COMPOSITION_VIEW = the
            // canonical revert point captured at turn start; STAGING_GRAPH = its
            // clone (mutated as ops drain).
            inputs.put(AgentState.COMPOSITION_VIEW, toGraphPayload(ctx.canonicalSnapshot));
            inputs.put(AgentState.STAGING_GRAPH, toGraphPayload(ctx.staging));

            RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
            compiled.invoke(inputs, config);

            // Plan-Preview gate: if the turn staged composition mutations, create
            // the PREVIEW plan + emit the `plan` event (Apply is deferred to the
            // decision endpoint, P4). TurnContext is still set here.
            finalizeStagedPlan(sessionId, tenantId, pipelineId, versionId, emitter, emitterDead);

            chatService.emitEvent(emitter, emitterDead, "done", "");
            if (!emitterDead.get()) {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        } finally {
            turns.remove(sessionId);
        }
    }

    /** Look up the active turn context for the session driving this node. */
    private TurnContext ctxFor(AgentState state) {
        return state.sessionId().map(turns::get).orElse(null);
    }

    // ==================================================================
    // StageRunner — stage bodies
    // ==================================================================

    @Override
    public Map<String, Object> runRouter(AgentState state) {
        // Single-pass turn: the composer path owns build/compose. The router
        // node sets the working phase; deterministic routing (CompositionGraph)
        // does the rest. (Per-intent fan-out to discovery/provision is a Phase 8
        // prompt concern; here the composer is the spine.)
        return Map.of(AgentState.PHASE, CompositionGraph.COMPOSER);
    }

    @Override
    public Map<String, Object> runDiscovery(AgentState state) throws Exception {
        return runLlmStage(state, NodeLlmAdapter.Stage.DISCOVERY, CompositionGraph.ROUTE_NEXT_PHASE);
    }

    @Override
    public Map<String, Object> runComposer(AgentState state) throws Exception {
        return runLlmStage(state, NodeLlmAdapter.Stage.COMPOSER, CompositionGraph.RESPONDER);
    }

    @Override
    public Map<String, Object> runConfigure(AgentState state) throws Exception {
        return runLlmStage(state, NodeLlmAdapter.Stage.CONFIGURE, CompositionGraph.RESPONDER);
    }

    @Override
    public Map<String, Object> runProvision(AgentState state) throws Exception {
        return runLlmStage(state, NodeLlmAdapter.Stage.PROVISION, CompositionGraph.ROUTE_NEXT_PHASE);
    }

    @Override
    public Map<String, Object> runPlanner(AgentState state) throws Exception {
        return runLlmStage(state, NodeLlmAdapter.Stage.PLANNER, CompositionGraph.APPLY);
    }

    @Override
    public Map<String, Object> runResponder(AgentState state) {
        // The user-facing reply is streamed token-by-token during the LLM stage
        // and persisted there; nothing further to emit here. Settle the turn.
        return Map.of(AgentState.NEXT_PHASE, CompositionGraph.RESPONDER);
    }

    /**
     * Drain the op-queue into the STAGING graph (Phase 3). Reads the pending ops
     * accumulated this turn (held in the TurnContext, mirrored from the
     * AgentState op-queue), applies them as one immutable fold onto a clone of
     * the canonical snapshot, resets the queue, and emits ONE candidate_graph.
     */
    @Override
    public Map<String, Object> processOperations(AgentState state) {
        TurnContext ctx = ctxFor(state);
        if (ctx == null) {
            // Test contexts may run the graph with no TurnContext.
            Map<String, Object> reset = new HashMap<>();
            reset.put(AgentState.OP_QUEUE, null);
            return reset;
        }
        List<PlanOperation> ops = new ArrayList<>(ctx.pendingOps);
        // The AgentState op-queue may also carry ops (reducer-appended); include them.
        ops.addAll(state.opQueue());
        if (!ops.isEmpty()) {
            ctx.stagedOps.addAll(ops);
            // Re-fold ALL staged ops onto the canonical snapshot so a multi-drain
            // turn stays consistent (immutable fold, never mutates canonical).
            ctx.staging = StagingGraph.applyOps(ctx.canonicalSnapshot, ctx.stagedOps);
            ctx.mutationsStaged = true;
            emitCandidateGraph(ctx);
        }
        ctx.pendingOps.clear();
        // Reset the AgentState op-queue (reducer treats null as reset) and write
        // the updated STAGING projection back to the channel so the checkpoint
        // reflects the latest staging (the canonical COMPOSITION_VIEW snapshot is
        // immutable for the turn; only STAGING_GRAPH advances as ops drain).
        Map<String, Object> out = new HashMap<>();
        out.put(AgentState.OP_QUEUE, null);
        out.put(AgentState.STAGING_GRAPH, toGraphPayload(ctx.staging));
        return out;
    }

    /**
     * The sole canonical writer (Phase 4) — only reached after the Plan-Preview
     * interrupt resolves to approve. In the single-pass + decision-endpoint
     * model, the canonical write happens in {@link #applyApprovedPlan(String)}
     * (driven by the decision endpoint), so this node is a no-op route node.
     */
    @Override
    public Map<String, Object> apply(AgentState state) {
        return Map.of(AgentState.NEXT_PHASE, CompositionGraph.RESPONDER);
    }

    // ==================================================================
    // LLM stage body: stream + execute tools (read | mutation)
    // ==================================================================

    private Map<String, Object> runLlmStage(AgentState state, NodeLlmAdapter.Stage stage, String nextPhase) throws Exception {
        TurnContext ctx = ctxFor(state);
        if (ctx == null) {
            return Map.of(AgentState.NEXT_PHASE, nextPhase);
        }
        // Phase 8: swap in this stage's OWN per-stage system prompt (the 7 per-stage
        // assemblies + dumped catalog + category guides + ACTIVE MODE), replacing
        // the single buildSystemPrompt the message list was seeded with. The legacy
        // single-loop path (handleLLMMode) is untouched.
        applyStageSystemPrompt(ctx, stage);
        int toolRounds = 0;
        while (toolRounds < ChatService.MAX_TOOL_ROUNDS) {
            StreamResult streamResult = llmAdapter.stream(stage, ctx.messages, ctx.emitter, ctx.emitterDead);

            if (streamResult.toolCalls() == null || streamResult.toolCalls().isEmpty()) {
                // Terminal text reply — persist + finish this stage.
                if (streamResult.content() != null && !streamResult.content().isEmpty()) {
                    persistAssistant(ctx.sessionId, streamResult.content());
                    // Phase 5: questions — when a discovery/router-style stage's
                    // terminal reply is asking the Customer something, surface a
                    // minimal `questions` event so the frontend can render an
                    // answer affordance. Detection is the conservative "the
                    // assistant message contains a question" heuristic documented
                    // in the Phase 5 worklist (no clean structured signal exists
                    // pre-Phase-8 prompts); empty-safe by construction.
                    emitQuestionsIfAsking(ctx, stage, streamResult.content());
                }
                break;
            }

            // Assistant message with structured tool calls (replay-preserving).
            List<Map<String, Object>> tcList = new ArrayList<>();
            for (var tc : streamResult.toolCalls()) {
                tcList.add(ChatService.toOutboundToolCall(tc));
            }
            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", streamResult.content() != null ? streamResult.content() : "");
            assistantMsg.put("tool_calls", tcList);
            ctx.messages.add(assistantMsg);
            persistAssistantWithToolCalls(ctx.sessionId, streamResult.content(), tcList);

            for (var tc : streamResult.toolCalls()) {
                Map<String, Object> args = parseArgs(tc.arguments());
                args.putIfAbsent("_session_id", ctx.sessionId);
                args.putIfAbsent("_pipeline_id", ctx.pipelineId);

                chatService.emitEvent(ctx.emitter, ctx.emitterDead, "tool_call", tc.name());
                // Phase 5: tool_progress — a distinct "started" signal per tool so
                // the frontend can show an in-flight indicator while a (possibly
                // long) tool executes, before the tool_result lands.
                emitToolProgress(ctx, tc.name(), "started");

                String result = executeTool(ctx, tc.name(), args);

                var envelope = chatService.buildToolResultEnvelope(tc.name(), args, result);
                chatService.emitEvent(ctx.emitter, ctx.emitterDead, "tool_result",
                        envelope.toJson(objectMapper));

                if ("navigate_ui".equals(tc.name())) {
                    String navPath = chatService.resolveNavigationPathForUiIntent(
                            (String) args.get("page"), (String) args.get("resource_id"));
                    if (navPath != null) {
                        chatService.emitEvent(ctx.emitter, ctx.emitterDead, "navigate", navPath);
                    }
                } else {
                    String navPath = toolExecutor.getNavigationPath(tc.name(), args, result);
                    if (navPath != null) {
                        chatService.emitEvent(ctx.emitter, ctx.emitterDead, "navigate", navPath);
                    }
                }

                ctx.messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", tc.id(),
                        "content", result));
                persistToolResult(ctx, tc.id(), tc.name(), args, result);
            }
            toolRounds++;
        }
        if (toolRounds >= ChatService.MAX_TOOL_ROUNDS) {
            // Graceful halt (NOT a throw): append an assistant note.
            String halt = "I have reached the maximum number of tool steps for this turn. "
                    + "Let me know how you would like to proceed.";
            chatService.emitEvent(ctx.emitter, ctx.emitterDead, "chunk", halt);
            persistAssistant(ctx.sessionId, halt);
        }

        // Ops queued by mutation tools this stage are drained by the graph's
        // COMPOSER -> PROCESS_OPERATIONS edge (process_operations), which emits
        // the candidate_graph; the PREVIEW plan is created after the turn settles
        // (finalizeStagedPlan). Route to the next phase deterministically.
        Map<String, Object> out = new HashMap<>();
        out.put(AgentState.NEXT_PHASE, nextPhase);
        return out;
    }

    /**
     * Replace the message list's system prompt (index 0) with this stage's OWN
     * per-stage assembly ({@link StagePromptAssembler}). Each LLM stage runs under
     * its own role + the cross-cutting blocks it needs; the per-turn tenant /
     * composition / dataset context is reused (the assembler appends
     * {@code buildSystemPrompt}). Defensive: if the list somehow has no leading
     * system message, prepend one.
     */
    private void applyStageSystemPrompt(TurnContext ctx, NodeLlmAdapter.Stage stage) {
        String systemPrompt = stagePromptAssembler.assemble(
                stage, ctx.tenantId, ctx.pipelineId, ctx.sessionId);
        Map<String, Object> systemMsg = Map.of("role", "system", "content", systemPrompt);
        if (!ctx.messages.isEmpty()
                && "system".equals(ctx.messages.get(0).get("role"))) {
            ctx.messages.set(0, systemMsg);
        } else {
            ctx.messages.add(0, systemMsg);
        }
    }

    /**
     * Execute one tool call. Read/other tools go to {@link ChatToolExecutor}
     * (which sees the STAGING view via {@code executeWithStaging}). Mutation
     * tools are validated by {@link MutationToolService} and enqueued as ONE op
     * (never written to canonical / Command Log).
     */
    private String executeTool(TurnContext ctx, String toolName, Map<String, Object> args) {
        if (MutationToolService.isMutationTool(toolName)) {
            try {
                // The effective staging = canonical snapshot + ops queued so far.
                StagingGraph effective = StagingGraph.applyOps(ctx.canonicalSnapshot, ctx.pendingOps);
                PlanOperation op = mutationToolService.toOperation(toolName, args, effective);
                ctx.pendingOps.add(op);
                return "Staged " + op.op() + " into the candidate graph (not yet applied). "
                        + "Call apply_plan after the user approves the Plan Preview.";
            } catch (MutationToolService.MutationValidationException ex) {
                return "Error: " + ex.getMessage();
            }
        }
        // Read tools mid-turn must see the STAGING view once ops are queued.
        StagingGraph effective = StagingGraph.applyOps(ctx.canonicalSnapshot, ctx.pendingOps);
        return toolExecutor.executeWithStaging(toolName, args, ctx.tenantId,
                ctx.pendingOps.isEmpty() ? null : effective);
    }

    // ==================================================================
    // Plan creation (the Plan-Preview gate) + Apply (P4)
    // ==================================================================

    /**
     * After a turn that staged mutations, create the PREVIEW Plan (the staged
     * ops as composition.* commands) and emit the {@code plan} event. Returns
     * the planId, or null if nothing was staged. Invoked by
     * {@code ChatService.handleGraphMode} after the graph turn settles.
     */
    public String finalizeStagedPlan(String sessionId, String tenantId, String pipelineId, String versionId,
                                     SseEmitter emitter, AtomicBoolean emitterDead) {
        TurnContext ctx = turns.get(sessionId);
        if (ctx == null || !ctx.mutationsStaged || ctx.stagedOpsSnapshot().isEmpty()) {
            return null;
        }
        List<PlannedCommand> commands = OpToCommandMapper.toCommands(
                ctx.stagedOpsSnapshot(), pipelineId, versionId);
        if (commands.isEmpty()) {
            return null;
        }
        var plan = planService.createForSession(tenantId, pipelineId, sessionId,
                resolveActorId(sessionId), "Composition changes staged from chat", commands);
        ctx.createdPlanId = plan.getId();
        emitPlanEvent(ctx, plan.getId(), commands);
        return plan.getId();
    }

    private void emitPlanEvent(TurnContext ctx, String planId, List<PlannedCommand> commands) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("planId", planId);
            payload.put("status", "PREVIEW");
            payload.put("summary", "Review " + commands.size() + " staged composition "
                    + (commands.size() == 1 ? "change" : "changes") + " before Apply.");
            payload.put("commandCount", commands.size());
            payload.put("steps", planSteps(commands));
            // Phase 5: carry the full compareGraphs diff (changeCount +
            // per-instance / per-wire status) so the Plan Preview can render the
            // change summary without recomputing it client-side.
            payload.put("diff", diffPayload(ctx));
            payload.put("changeCount", com.pulse.chat.diff.CompareGraphs
                    .compare(ctx.canonicalSnapshot, ctx.staging).changeCount());
            chatService.emitEvent(ctx.emitter, ctx.emitterDead, "plan",
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to emit plan event: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> planSteps(List<PlannedCommand> commands) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlannedCommand command : commands) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("op", command.commandType());
            step.put("aggregateType", command.aggregateType());
            step.put("aggregateId", command.aggregateId());
            step.put("reasoning", command.description());
            Map<String, Object> payload = command.payload() == null ? Map.of() : command.payload();
            putIfPresent(step, "instanceRef", payload.get("instanceRef"));
            putIfPresent(step, "blueprintKey", payload.get("blueprintKey"));
            putIfPresent(step, "sourceRef", payload.get("sourceRef"));
            putIfPresent(step, "targetRef", payload.get("targetRef"));
            out.add(step);
        }
        return out;
    }

    private void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private void emitCandidateGraph(TurnContext ctx) {
        try {
            Map<String, Object> payload = toGraphPayload(ctx.staging);
            // Phase 5: carry the canonical-vs-staging diff so the frontend can
            // render the "Review N changes" badge + per-instance status directly
            // off the candidate_graph snapshot. canonical = the turn-start clone.
            payload.put("diff", diffPayload(ctx));
            chatService.emitEvent(ctx.emitter, ctx.emitterDead, "candidate_graph",
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to emit candidate_graph: {}", e.getMessage());
        }
    }

    /**
     * The compareGraphs diff payload (Phase 5): change count + per-instance and
     * per-wire status. canonical is projected from the turn-start snapshot;
     * staging is the current candidate graph.
     */
    private Map<String, Object> diffPayload(TurnContext ctx) {
        com.pulse.chat.diff.CompareGraphs.GraphDiff diff =
                com.pulse.chat.diff.CompareGraphs.compare(ctx.canonicalSnapshot, ctx.staging);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changeCount", diff.changeCount());
        Map<String, String> instanceStatus = new LinkedHashMap<>();
        diff.instances().forEach((ref, d) -> instanceStatus.put(ref, d.status().name()));
        out.put("instanceStatus", instanceStatus);
        List<Map<String, Object>> wireStatus = new ArrayList<>();
        for (var wd : diff.wirings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", wd.status().name());
            m.put("sourceRef", wd.wire().sourceRef());
            m.put("sourcePort", wd.wire().sourcePort());
            m.put("targetRef", wd.wire().targetRef());
            m.put("targetPort", wd.wire().targetPort());
            wireStatus.add(m);
        }
        out.put("wireStatus", wireStatus);
        return out;
    }

    /** Phase 5: tool_progress — a per-tool in-flight signal distinct from tool_call. */
    private void emitToolProgress(TurnContext ctx, String toolName, String status) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", toolName);
            payload.put("status", status);
            chatService.emitEvent(ctx.emitter, ctx.emitterDead, EVENT_TOOL_PROGRESS,
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to emit tool_progress: {}", e.getMessage());
        }
    }

    /**
     * Phase 5: questions — emit a minimal `questions` event when a
     * discovery/router-style stage's terminal reply is asking the Customer
     * something. Conservative heuristic (the reply contains a '?'); empty-safe.
     */
    private void emitQuestionsIfAsking(TurnContext ctx, NodeLlmAdapter.Stage stage, String content) {
        if (content == null || content.indexOf('?') < 0) return;
        // Only the elicitation-style stages ask; the composer/responder produce
        // statements, not clarifying questions.
        boolean elicits = stage == NodeLlmAdapter.Stage.DISCOVERY
                || stage == NodeLlmAdapter.Stage.PROVISION
                || stage == NodeLlmAdapter.Stage.CONFIGURE;
        if (!elicits) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stage", stage.name());
            payload.put("hasQuestions", true);
            chatService.emitEvent(ctx.emitter, ctx.emitterDead, EVENT_QUESTIONS,
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to emit questions: {}", e.getMessage());
        }
    }

    /**
     * Phase 7: messages_compacted — signal that the session history was folded
     * into a summary block this turn (token-budget compaction). The frontend
     * event name already exists in the contract; this is the only emitter.
     */
    private void emitMessagesCompacted(SseEmitter emitter, AtomicBoolean emitterDead,
                                       com.pulse.chat.service.HistoryCompactor.CompactionResult r) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summarizedMessageCount", r.summarizedMessageCount());
            payload.put("estimatedTokensBefore", r.estimatedTokensBefore());
            payload.put("estimatedTokensAfter", r.estimatedTokensAfter());
            chatService.emitEvent(emitter, emitterDead, EVENT_MESSAGES_COMPACTED,
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to emit messages_compacted: {}", e.getMessage());
        }
    }

    private Map<String, Object> toGraphPayload(StagingGraph g) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", g.name());
        List<Map<String, Object>> instances = new ArrayList<>();
        for (var i : g.instances()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", i.ref());
            m.put("blueprintKey", i.blueprintKey());
            m.put("params", i.params());
            m.put("storageBackend", i.storageBackend());
            m.put("lakeLayer", i.lakeLayer());
            m.put("lakeFormat", i.lakeFormat());
            instances.add(m);
        }
        payload.put("instances", instances);
        List<Map<String, Object>> wirings = new ArrayList<>();
        for (var w : g.wirings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sourceRef", w.sourceRef());
            m.put("sourcePort", w.sourcePort());
            m.put("targetRef", w.targetRef());
            m.put("targetPort", w.targetPort());
            wirings.add(m);
        }
        payload.put("wirings", wirings);
        return payload;
    }

    /**
     * Apply an APPROVED plan (the canonical write, P4) — invoked by the decision
     * endpoint after approve. Delegates to {@link PlanService#apply(String)},
     * which executes the composition.* commands under one shared planId in one
     * transaction (one Command-Log row per op). Returns the applied Plan.
     */
    public com.pulse.command.model.Plan applyApprovedPlan(String planId) {
        return planService.apply(planId);
    }

    // ==================================================================
    // Snapshot / revert (Phase 7)
    // ==================================================================

    /**
     * The current canonical composition for a pipeline, as a serializable graph
     * payload — the "re-render from the snapshot" content for a pre-Apply revert
     * (reject / cancel-mid-turn / Restore drops the staging clone; the canonical
     * is untouched, so the snapshot IS the current canonical). Returns an empty
     * graph payload when nothing resolves.
     */
    public Map<String, Object> canonicalGraphPayload(String pipelineId) {
        String versionId = resolveActiveVersionId(pipelineId);
        return toGraphPayload(loadCanonical(pipelineId, versionId));
    }

    /**
     * Restore an ALREADY-APPLIED turn's composition (Phase 7 §2.2): reconstruct
     * the canonical graph to match {@code snapshot} by reconciling — remove every
     * current canonical instance, then re-add the snapshot's instances and re-wire
     * them. This IS "restore the checkpoint snapshot" (deterministic
     * reconstruction of the captured state), NOT an inverse-plan derivation
     * (ADR 0025 §3 / SPEC §7.16 #13). Returns the restored graph payload.
     *
     * <p>The {@code snapshot} is the serializable {@code COMPOSITION_VIEW}
     * projection the checkpointer round-trips ({@link #toGraphPayload}); it is the
     * pre-turn revert point captured at the restored turn's start.</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> restoreCompositionFromSnapshot(String pipelineId, Map<String, Object> snapshot) {
        String versionId = resolveActiveVersionId(pipelineId);
        if (versionId == null || snapshot == null) {
            return snapshot == null ? Map.of() : snapshot;
        }
        // 1) Drop every current canonical instance (wirings cascade with their
        //    incident instances on remove).
        CompositionView current = compositionService.getComposition(versionId);
        for (var inst : current.instances()) {
            try {
                compositionService.removeInstance(versionId, inst.getId());
            } catch (Exception e) {
                log.debug("restore: could not remove instance {} ({})", inst.getId(), e.getMessage());
            }
        }
        // 2) Re-add the snapshot's instances.
        List<Map<String, Object>> instances = (List<Map<String, Object>>) snapshot.getOrDefault("instances", List.of());
        for (Map<String, Object> i : instances) {
            String ref = (String) i.get("ref");
            String blueprintKey = (String) i.get("blueprintKey");
            if (ref == null || blueprintKey == null) continue;
            Map<String, Object> params = (Map<String, Object>) i.getOrDefault("params", Map.of());
            try {
                compositionService.addInstance(pipelineId, versionId, blueprintKey, ref,
                        new HashMap<>(params),
                        (String) i.get("storageBackend"),
                        (String) i.get("lakeLayer"),
                        (String) i.get("lakeFormat"));
            } catch (Exception e) {
                log.debug("restore: could not re-add instance {} ({})", ref, e.getMessage());
            }
        }
        // 3) Re-wire the snapshot's wirings (resolve refs -> ids by name).
        List<Map<String, Object>> wirings = (List<Map<String, Object>>) snapshot.getOrDefault("wirings", List.of());
        for (Map<String, Object> w : wirings) {
            try {
                String srcId = compositionService.resolveInstanceIdByName(versionId, (String) w.get("sourceRef"));
                String tgtId = compositionService.resolveInstanceIdByName(versionId, (String) w.get("targetRef"));
                if (srcId == null || tgtId == null) continue;
                compositionService.wirePort(versionId, srcId, (String) w.get("sourcePort"),
                        tgtId, (String) w.get("targetPort"));
            } catch (Exception e) {
                log.debug("restore: could not re-wire {} ({})", w, e.getMessage());
            }
        }
        return toGraphPayload(loadCanonical(pipelineId, versionId));
    }

    /**
     * Read the {@code COMPOSITION_VIEW} (the turn-start canonical snapshot) from
     * the latest checkpoint for the session thread — the checkpointer IS the
     * snapshot store (ADR 0025 §3). Returns {@code null} when no checkpoint state
     * exists (e.g. the MemorySaver default has nothing for this thread, or the
     * graph has not run). The durable Postgres round-trip is asserted in the
     * postgres-lane {@code ChatCheckpointerRoundTripIT}.
     */
    public Map<String, Object> latestCheckpointCompositionView(String sessionId) {
        if (sessionId == null) return null;
        try {
            CompositionGraph graph = new CompositionGraph(this, ChatService.MAX_TOOL_ROUNDS);
            CompiledGraph<AgentState> compiled = graph.compile(checkpointSaver);
            RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
            var snapshot = compiled.getState(config);
            if (snapshot == null) return null;
            AgentState state = snapshot.state();
            if (state == null) return null;
            Object view = state.value(AgentState.COMPOSITION_VIEW).orElse(null);
            if (view instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                return typed;
            }
            return null;
        } catch (Exception e) {
            log.debug("latestCheckpointCompositionView({}) -> none ({})", sessionId, e.getMessage());
            return null;
        }
    }

    // ==================================================================
    // Persistence + resolution helpers
    // ==================================================================

    private void persistAssistant(String sessionId, String content) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setRole("ASSISTANT");
        m.setContent(content);
        messageRepo.save(m);
    }

    private void persistAssistantWithToolCalls(String sessionId, String content, List<Map<String, Object>> tcList) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setRole("ASSISTANT");
        m.setContent(content != null ? content : "");
        m.setToolCalls(Map.of("calls", tcList));
        messageRepo.save(m);
    }

    private void persistToolResult(TurnContext ctx, String toolCallId, String toolName,
                                   Map<String, Object> args, String result) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(ctx.sessionId);
        m.setRole("TOOL");
        m.setContent(result);
        m.setToolCalls(Map.of("tool_call_id", toolCallId));
        Map<String, Object> payload = chatService.buildToolResultPayload(
                ctx.sessionId, ctx.tenantId, toolName, args, result);
        if (!payload.isEmpty()) {
            m.setToolResults(payload);
        }
        messageRepo.save(m);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String arguments) {
        try {
            if (arguments == null || arguments.isBlank()) return new HashMap<>();
            return objectMapper.readValue(arguments, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String resolveActiveVersionId(String pipelineId) {
        if (pipelineId == null) return null;
        return pipelineRepo.findById(pipelineId).map(Pipeline::getActiveVersionId).orElse(null);
    }

    private StagingGraph loadCanonical(String pipelineId, String versionId) {
        if (versionId == null) return StagingGraph.empty();
        try {
            CompositionView view = compositionService.getComposition(versionId);
            String name = pipelineId == null ? "" :
                    pipelineRepo.findById(pipelineId).map(Pipeline::getName).orElse("");
            return StagingGraph.fromCanonical(view, name);
        } catch (Exception e) {
            log.debug("Could not load canonical composition for version {}: {}", versionId, e.getMessage());
            return StagingGraph.empty();
        }
    }

    private String resolveActorId(String sessionId) {
        // The chat plan actor is the session's user; default to the stub user id
        // when unavailable (auth disabled in dev returns a stub user).
        return "01JUSER00000000000000000";
    }
}
