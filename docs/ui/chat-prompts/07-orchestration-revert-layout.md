# PULSE Chat — Agent orchestration, snapshot/revert, and auto-layout

> **DRAFT — operator decision pending (staged-vs-single-loop).**
>
> This fragment (Author #3) authors PULSE's **agent-orchestration**, **snapshot/revert**, and **auto-layout** contracts
> for the composition workspace, **grounded in the ACTUAL n8n source** — not in any prior n8n summary doc
> (the earlier `N8N-AI-BUILDER-ARCHITECTURE.md` summary has since been removed; this fragment stands on the raw source).
> Every n8n claim below is `[read]` from a real file pulled from
> `github.com/n8n-io/n8n` **master** (raw URLs cited inline, fetched 2026-06-16). PULSE inventions with no read source
> are flagged `> GUESS:`.
>
> This pays off three threads in `docs/ui/SPEC-ui-composition.md`: the **§7.0/§7.1 staged-vs-single-loop GUESS**
> (`SPEC-ui-composition.md:171-177, 198-203`), the **§7.8 snapshot/revert** contract (`:537-547`), and the **§7.6
> Dagre auto-layout** step (`:500-509`). Where this fragment and §7 agree, §7 stands; where the real n8n source diverges
> from the summary the SPEC was built on, the divergence is logged in the table at §6 and the corrected reading is used.
>
> **Defined vocab (verbatim, per SPEC §7):** Customer · op · op-list · params · Blueprint · dataset · port · Command Log ·
> Plan Preview · Apply Plan · **CANONICAL graph** (persisted `sub_pipeline_instances` + `port_wirings`) · **STAGING graph**
> (the candidate composition a turn builds, never persisted until Apply).

---

## 0. Source-grounding ledger — the real n8n files read

`[read]` — pulled verbatim from `github.com/n8n-io/n8n` **master** on 2026-06-16 (raw URLs):

| # | n8n artifact | What it grounds | Raw URL read |
|---|---|---|---|
| A | `ai-workflow-builder.ee/src/multi-agent-workflow-subgraphs.ts` | the supervisor `StateGraph` wiring, nodes, conditional edges, `routeToNode`, `.compile({ checkpointer })` | `https://raw.githubusercontent.com/n8n-io/n8n/master/packages/@n8n/ai-workflow-builder.ee/src/multi-agent-workflow-subgraphs.ts` |
| B | `ai-workflow-builder.ee/src/parent-graph-state.ts` | the shared STATE object (`ParentGraphState` = `Annotation.Root({…})`, ~20 channels + reducers) | `…/packages/@n8n/ai-workflow-builder.ee/src/parent-graph-state.ts` |
| C | `ai-workflow-builder.ee/src/workflow-state.ts` | `WorkflowState` (`workflowJSON`, `workflowOperations` + `operationsReducer`) | `…/src/workflow-state.ts` |
| D | `ai-workflow-builder.ee/src/types/workflow.ts` | `WorkflowOperation` union + `SimpleWorkflow` | `…/src/types/workflow.ts` |
| E | `ai-workflow-builder.ee/src/subgraphs/discovery.subgraph.ts` | the planner-inside-discovery wiring, `shouldPlan` / `shouldLoopPlanner` edges, `MAX_PLAN_MODIFY_ITERATIONS` | `…/src/subgraphs/discovery.subgraph.ts` |
| F | `ai-workflow-builder.ee/src/agents/planner.agent.ts` | the `interrupt({ type: 'plan', plan })` HITL + approve/reject/modify branches | `…/src/agents/planner.agent.ts` |
| G | `ai-workflow-builder.ee/src/tools/helpers/state.ts` | `getEffectiveWorkflow()` (read-tools-see-pending-ops invariant) | `…/src/tools/helpers/state.ts` |
| H | `ai-workflow-builder.ee/src/workflow-builder-agent.ts` | `getState()`, the `Command({ resume, update })` resume, `ChatPayload.versionId` | `…/src/workflow-builder-agent.ts` |
| I | `ai-workflow-builder.ee/src/ai-workflow-builder-agent.service.ts` | `truncateMessagesAfter(messageId)`, `saveSessionSafe` | `…/src/ai-workflow-builder-agent.service.ts` |
| J | `workflow-sdk/src/workflow-builder/layout-utils.ts` | `calculateNodePositionsDagre` (Dagre), `import dagre from '@dagrejs/dagre'`, `toJSON({ tidyUp: true })` | `…/packages/@n8n/workflow-sdk/src/workflow-builder/layout-utils.ts` |
| K | `frontend/editor-ui/src/app/composables/useWorkflowUpdate.ts` | FE reconcile + `tidyUpNodes()` (full re-layout only on structural change) | `…/packages/frontend/editor-ui/src/app/composables/useWorkflowUpdate.ts` |

Non-source corroboration (n8n PRs/issues, dated): `[ref]` PR **#17423** (LangGraph state-machine core), PR **#794a8d6 / #18737 / #25498** (planning-mode + `interrupt`), issue **#23060** (ai-builder versioning/restore), issue **#25903** (restore→build-mode reset), issue **#25905 / #29850 / #30455** (auto-layout on structural change, position preservation, grid snap).

---

## 1. AGENT ORCHESTRATION

### 1.1 What n8n ACTUALLY wires (the real graph) — `[read A]`

n8n's builder is a **LangGraph supervisor graph**, `createMultiAgentWorkflowWithSubgraphs(config)`, built as
`new StateGraph(ParentGraphState)` and finished with `.compile({ checkpointer })` `[read A]`. The agents are NOT five
flat peer nodes; the current master shape is a **supervisor + one composite Discovery subgraph (which CONTAINS the
planner) + a Responder + an Assistant subgraph**, with the old standalone **Builder removed**. The graph header verbatim:

```ts
// [read A] multi-agent-workflow-subgraphs.ts
import { StateGraph, END, START, type MemorySaver, isGraphInterrupt, getWriter } from '@langchain/langgraph';
import { ParentGraphState } from './parent-graph-state';
import { DiscoverySubgraph } from './subgraphs/discovery.subgraph';

// Maps routing decisions to graph node names. Used by both supervisor (LLM) and route_next_phase (deterministic).
function routeToNode(next: string): string {
  const nodeMapping: Record<string, string> = {
    responder: 'responder',
    discovery: 'discovery_subgraph',
    assistant: 'assistant_subgraph',
  };
  return nodeMapping[next] ?? 'responder';
}
```

The node/edge wiring (verbatim `[read A]`, abridged to the structure):

```ts
new StateGraph(ParentGraphState)
  .addNode('supervisor', async (state, config) => { /* LLM routing → { nextPhase } */ })
  .addNode('responder',  async (state, config) => { /* synthesizes the user-facing reply → { messages:[response] } */ })
  .addNode('route_next_phase', (state) => { /* deterministic re-route from coordinationLog + planDecision */ })
  .addNode('check_state', (state) => { /* preprocessing: clear/compact/cleanup OR continue */ })
  .addNode('cleanup_dangling', …).addNode('compact_messages', …).addNode('delete_messages', …)
  .addNode('clear_error_state', …).addNode('create_workflow_name', …)
  .addNode('discovery_subgraph', createSubgraphNodeHandler('discovery',
      createCompiledSubgraphExecutor(discoverySubgraph, compiledDiscovery, MAX_DISCOVERY_ITERATIONS), logger))
  .addNode('assistant_subgraph', createSubgraphNodeHandler('assistant', /* SDK help/debug handler */, logger))
  .addEdge('discovery_subgraph', 'route_next_phase')
  .addEdge('assistant_subgraph', 'route_next_phase')
  .addEdge(START, 'check_state')
  .addConditionalEdges('check_state', (state) => routes[state.nextPhase] ?? 'supervisor')
  .addEdge('cleanup_dangling', 'check_state')
  .addEdge('delete_messages', 'responder')
  .addEdge('clear_error_state', 'check_state')
  .addConditionalEdges('create_workflow_name', (state) => routeToNode(state.nextPhase))
  .addConditionalEdges('compact_messages', (state) => state.messages.length > 0 ? 'check_state' : 'responder')
  .addConditionalEdges('supervisor', (state) =>
      state.nextPhase === 'discovery' ? 'create_workflow_name' : routeToNode(state.nextPhase))
  .addConditionalEdges('route_next_phase', (state) => routeToNode(state.nextPhase))
  .addEdge('responder', END)
  .compile({ checkpointer });
```

The "builder removed" fact is explicit in the source `[read A]`:

```ts
// route_next_phase node, verbatim [read A]:
// After discovery in plan mode, getNextPhaseFromLog returns 'builder'.
// With builder removed, redirect back to discovery to retry planning.
if (next === 'builder' && state.mode === 'plan' && !state.planOutput) {
  return { nextPhase: 'discovery', planDecision: null };
}
```

**The Planner is a node INSIDE the Discovery subgraph, not a peer agent** `[read A, E]`. `DiscoverySubgraph.create({ … plannerLLM: stageLLMs.planner … })` compiles a child `StateGraph(DiscoverySubgraphState)` with nodes `discovery_agent · tools · format_output · reprompt · planner` and these edges `[read E]`:

```
START → discovery_agent
discovery_agent → (conditional) tools | format_output | reprompt | END
tools → discovery_agent ;  reprompt → discovery_agent
format_output → (shouldPlan)  planner | END        // 'planner' only if mode==='plan' && !planOutput
planner → (shouldLoopPlanner) discovery_agent | END // 'discovery_agent' on planDecision==='modify' (capped)
static readonly MAX_PLAN_MODIFY_ITERATIONS = 5;     // [read E]
```

So the canonical "Supervisor / Discovery / **Builder** / Planner / Responder" five-agent decomposition (from PR #17423 +
the planning PRs) is the **historical** shape; on **current master** the standalone code-builder node was lifted OUT of
the graph and runs as a post-approval path (`runCodeWorkflowBuilder`, reached after the plan `interrupt` resolves
`approve` `[read H]`), and the **planner lives inside Discovery**. The agents are LLM-config'd per stage via
`stageLLMs: { supervisor, discovery, planner, responder }` `[read A]`.

> The operator's **LOCKED stage model** (`D1-FEEDBACK-CHANGELIST.md` §F; SPEC §7.1 stage table) is **seven logical
> stages** — router/discovery/composer/**configure**/**provision**/planner/responder. PULSE adds an explicit **Configure**
> stage (STRUCTURED `set_params` on an EXISTING step, no new structure) and a **Provision** stage (SOR/Domain/Connector/
> Dataset entity build) that have **no n8n analogue inside the builder graph** (n8n's `configurator` is the loose
> Provision analogue, fragment 01 §5). The n8n *documented* shape is five agents; the *running* master code is **four
> graph nodes + a planner-inside-discovery**, with builder externalized. This fragment treats the **LOCKED seven stages**
> as the TARGET decomposition and records the n8n divergence at §6.1 — the operator should know the architecture they are
> "matching the shape of" already collapsed Builder into a post-approval path.

### 1.2 PULSE's orchestration contract (shaped like n8n's)

> GUESS — the whole of §1.2 is PULSE-side design. The live PULSE Chat is a **single OpenRouter `/chat/completions`
> tool-loop** bounded by `MAX_TOOL_ROUNDS = 30` (`ChatService.java:36, 546-738`), NOT a LangGraph. There is **no
> `StateGraph`, no supervisor, no `ParentGraphState`, no `interrupt()`** in PULSE today. Everything below is the staged
> TARGET re-expressed in PULSE's stack — adapt-the-shape, not copy-the-code.

**THE LOCKED-DECISION-AREA FORK — RESOLVED (operator 2026-06-16) to Option A (§7.0/§7.16 #1; ADR 0025).** Two ways to
realize the seven stages were considered; **Option A is the decision**:

| Option | What it is | Cost / risk | Maps to n8n |
|---|---|---|---|
| **A — Build-staged-now as a LangGraph4j `StateGraph` (DECISION — ADR 0025)** | Each stage = a real node in a LangGraph4j `StateGraph`, with a supervisor/router node, the shared `AgentState` carrier (§1.3), conditional edges, and an `interruptBefore` at the plan gate (§1.4); the Postgres checkpointer (`langgraph4j-postgres-saver`) is the snapshot store (§2.2). | New orchestration layer, built INCREMENTALLY behind the "first pipeline composed + built end-to-end" milestone (ADR 0025 §5); LangGraph4j ships the StateGraph/checkpointer/interrupt machinery PULSE would otherwise hand-roll, so this maps the design ~1:1 onto n8n's `multi-agent-workflow-subgraphs.ts` and LOWERS risk. | n8n master `[read A]` (`ParentGraphState`→`AgentState`, `interrupt`→`interruptBefore`, `MemorySaver`→`PostgresSaver`) |
| **B — Phase-gated prompt-assembly (FALLBACK)** | Keep the ONE OpenRouter/Vertex tool-loop; the seven "stages" are **prompt-assembly + tool-gating MODES** over that one loop, selected by a `ConversationPhase`. Today's `PulseSystemPrompt` already concatenates `IDENTITY`/`PLANNER_PACKET`/`GENERATION_PACKET` by `ConversationPhase` (`ChatService.java:1038-1061`). | Smallest delta, but forgoes durable/resumable session state + time-travel and makes per-stage models messier (swap mid-loop). **Retained as the named fallback** if LangGraph4j is later deemed unfit (ADR 0025 "Considered"). | n8n's *stage roles* + `stageLLMs`, minus the graph nodes |

**This fragment specifies the contract so EITHER option satisfies it**; the **decision is Option A** (the LangGraph4j
`StateGraph`), with Option B retained as the named fallback. The normative sentences below are written stage-agnostic;
under Option A each stage is a graph *node*.

EARS contract:

- WHEN a Customer turn begins THE SYSTEM SHALL select an **orchestration phase** ∈ `{router, discovery, composer,
  configure, provision, planner, responder}` (the LOCKED seven-stage model, `D1-FEEDBACK-CHANGELIST.md` §F; SPEC §7.1
  stage table) and run that phase's **prompt-assembly mode** + **tool allow-list** + **LLM model choice** (the PULSE
  analogue of n8n's per-stage `stageLLMs` `[read A]`).
- WHEN the phase is `composer` or `configure` THE SYSTEM SHALL allow only the **mutation tier** (emit-op tools, §7.3 B of
  the SPEC — `configure` is restricted to the STRUCTURED `set_params` subset on an EXISTING step, no new structure) and
  SHALL NOT write the CANONICAL graph; WHEN the phase is `provision` THE SYSTEM SHALL allow only the **entity-provisioning
  tier** (the SOR/Domain/Connector/Dataset tools, fragment 02 §1.2 — also Plan-gated, never a direct canonical write);
  WHEN the phase is `discovery`/`planner`/`responder` THE SYSTEM SHALL allow only the **read-only tier** (+ `submit_plan`
  in `planner`).
- WHEN a phase completes THE SYSTEM SHALL route to the next phase deterministically from the turn's progress (the PULSE
  analogue of n8n's `route_next_phase` reading the `coordinationLog` `[read A]`), NOT by re-asking the LLM to route.
- WHEN the loop reaches its recursion bound THE SYSTEM SHALL halt, append an assistant message ("I stopped after N build
  steps — here's what I staged so far; tell me to continue"), and NOT throw (the SPEC §7.1 graceful-halt; the live bound
  is `MAX_TOOL_ROUNDS = 30`, `ChatService.java:36`).

> RESOLVED (operator 2026-06-16): recursion bound = **40** (§7.16 #3). n8n caps subgraph recursion via
> `MAX_DISCOVERY_ITERATIONS` (`[read A]`); PULSE's analogue `MAX_TOOL_ROUNDS` is raised from the live 30 to **40** because
> a 10-step pipeline ≈ 30 ops ≈ 30 tool calls — 30 is too tight; 40 is a one-constant change with no downside but a
> latency tail.

> RESOLVED (operator 2026-06-16): per-stage model matrix (ADR 0025 §2; SPEC §7.16 #2). Each LangGraph4j node declares its
> own model (the n8n `stageLLMs` analogue): **cheap tier** (`pulse.llm.cheap-model` → new `LlmSurface.CHAT_CHEAP`, Gemini
> Flash) on **router / discovery / configure / provision / responder**; **reasoning tier** (`pulse.llm.model`, Gemini Pro)
> on **composer and planner**. A node MAY escalate to the reasoning tier on a flagged hard case. The cheap tier is a NEW
> chat key/surface — NOT `pulse.schema-inference.model`/`SCHEMA_INFERENCE`, which ADR 0011 left dead (schema inference is
> zero-LLM). Models are reached through PULSE's `LlmEndpointService` Vertex adapter (ADR 0025 §4), not the model client
> inside LangGraph4j.

### 1.3 The shared STATE object (the carrier) — `[read B, C]`

n8n threads ONE typed state object through every node: `ParentGraphState = Annotation.Root({ … })`, ~20 channels, each
with a **reducer** + **default** `[read B]`. The channels that matter for orchestration + staging + planning, verbatim
names `[read B]`:

```ts
// [read B] parent-graph-state.ts (channel names + reducer semantics, verbatim)
ParentGraphState = Annotation.Root({
  messages,            // messagesStateReducer (append/merge BaseMessage[])
  workflowJSON,        // (x, y) => y ?? x   — last-write-wins; the built graph
  workflowContext,     // last-write-wins
  nextPhase,           // last-write-wins, default '' — the route channel
  discoveryContext,    // last-write-wins, default null
  workflowOperations,  // operationsReducer — the OP-QUEUE (append; {type:'clear'} empties) [read C]
  coordinationLog,     // append — subgraph completion tracking (drives deterministic routing)
  previousSummary,     // last-write-wins — compaction carry
  templateIds,         // appendArrayReducer
  cachedTemplates,     // cachedTemplatesReducer — shared across subgraphs
  planOutput,          // undefined-safe last-write-wins, default null
  mode,                // last-write-wins, default 'build'   — 'build' | 'plan'
  planDecision,        // undefined-safe — 'approve' | 'reject' | 'modify' after interrupt resume
  planFeedback,        // undefined-safe, default null
  planPrevious,        // undefined-safe, default null — the prior plan for a modify revision
  introspectionEvents, // append
  sdkSessionId, approvedDomains, webFetchCount, allDomainsApproved // assistant/web-fetch HITL plumbing
});
```

And the op-queue mechanics `[read C, G]`:

```ts
// [read C] workflow-state.ts
workflowJSON:       { reducer: (x, y) => y ?? x, default: () => ({ nodes: [], connections: {}, name: '' }) },
workflowOperations: { reducer: operationsReducer, default: () => [] },  // ops accumulate, applied by a separate node

// [read G] tools/helpers/state.ts — the "read tools see pending ops" invariant
export function getEffectiveWorkflow(): SimpleWorkflow {
  const state = getWorkflowState();
  const pending = state.workflowOperations;
  if (!pending || pending.length === 0) return state.workflowJSON;
  return applyOperations(structuredClone(state.workflowJSON), pending);  // staging = canonical + pending ops
}
```

**PULSE's shared-state contract (shaped like n8n's):**

- WHEN a turn runs THE SYSTEM SHALL carry ONE per-turn state object with these PULSE channels (n8n channel → PULSE
  channel): `workflowJSON` → **`CompositionView` of the active version** (`{instances[], wirings[]}`,
  `CompositionService.java:372-375`); `workflowOperations` → the **op-queue of `PlanOperation`** (SPEC §7.4); `nextPhase`
  → the **orchestration phase**; `planOutput`/`mode`/`planDecision`/`planFeedback`/`planPrevious` → the **Plan-Preview
  lifecycle** channels (SPEC §7.2); `messages` → the rebuilt LLM message list (`ChatService.java:547-618`);
  `coordinationLog` → the **turn-progress log** that drives deterministic routing.
- WHEN a read tool runs MID-TURN after ops are enqueued THE SYSTEM SHALL return `applyOps(clone(canonical), pendingOps)` —
  the **STAGING graph**, not the canonical graph — exactly the n8n `getEffectiveWorkflow()` invariant `[read G]` (SPEC
  §7.1 "read tools see PENDING staging state").

> RESOLVED (operator 2026-06-16, ADR 0025 §1): the per-turn shared-state carrier is the LangGraph4j **`AgentState`** (the
> `ParentGraphState`/`Annotation.Root` analogue) — PULSE has no such object today (the live loop reads/writes Java service
> state directly), so this channel set is the carrier the `StateGraph` threads. The channels map 1:1 onto SPEC §7.10's
> three state layers; the op-queue channel uses an append/clear reducer like n8n's `operationsReducer`.

### 1.4 The HITL interrupt = PULSE's Plan Preview approval — `[read F, H]`

n8n's plan approval is a LangGraph **`interrupt()`** inside the planner node `[read F]`:

```ts
// [read F] agents/planner.agent.ts — invokePlannerNode(...)
const decisionValue: unknown = interrupt({ type: 'plan', plan });   // pause; surface { type:'plan', plan } to the caller
const decision = parsePlanDecision(decisionValue);                  // resume value comes back here

if (decision.action === 'approve')
  return { planDecision: 'approve', planOutput: plan, mode: 'build', planFeedback: null, planPrevious: null };
if (decision.action === 'reject')
  return { planDecision: 'reject', planOutput: null,  planFeedback: null, planPrevious: null };
// modify:
return { planDecision: 'modify', planOutput: null, planFeedback: feedback, planPrevious: plan, messages: [feedbackMessage] };
```

Resume is a LangGraph **`Command`** carried on the next request `[read H]`:

```ts
// [read H] workflow-builder-agent.ts
await agent.stream(new Command({ resume: payload.resumeData, update: { /* mode, etc. */ } }), streamConfig);
// payload.resumeInterrupt?.type === 'plan' → parsePlanDecision(payload.resumeData) → approve routes to CodeWorkflowBuilder
```

The three outcomes match PULSE's already-LOCKED HITL exactly (SPEC §6, §7.2): **approve = Apply Plan**, **reject = discard
staging + restore snapshot**, **modify = re-enter the loop at composer; rebuild (not append) the staging graph**
(`planPrevious := the prior plan` is n8n's "rebuild from the old plan + feedback" — the SPEC §7.2 Modify semantics).

**PULSE HITL contract:**

- WHEN the `planner` phase produces a Plan Preview THE SYSTEM SHALL **pause the turn at the approval gate** (PULSE's
  analogue of `interrupt({ type: 'plan', plan })` `[read F]`) and surface the structured Plan Preview
  `{summary, trigger, steps[], additionalSpecs}` (SPEC §7.2) to the Customer — WITHOUT writing the CANONICAL graph or the
  Command Log.
- WHEN the Customer **approves** (clicks **Apply Plan**) THE SYSTEM SHALL resume the turn with the decision, set
  `planOutput := the approved plan`, `mode := build`, and run the single atomic `apply_plan(plan_id)` step (SPEC §7.9) —
  the only writer of the CANONICAL graph + Command Log.
- WHEN the Customer **rejects** THE SYSTEM SHALL discard the STAGING graph and restore the turn snapshot (§2 below);
  `planOutput := null`; no canonical/Command-Log write.
- WHEN the Customer **modifies** THE SYSTEM SHALL set `planPrevious := the prior plan`, `planFeedback := the correction`,
  re-enter at `composer`, and **rebuild** the STAGING graph (not append), capped at a modify bound (n8n
  `MAX_PLAN_MODIFY_ITERATIONS = 5` `[read E]`).

> RESOLVED (operator 2026-06-16, ADR 0025 §1): the HITL plan gate is a LangGraph4j **`interruptBefore` on the apply node**
> (the n8n `interrupt({type:'plan'})` analogue) — PULSE has no `interrupt()`/`Command.resume` today (approval rides in the
> next chat message's `Plan.approvedByMessageId` metadata, `Plan.java:59`; the mutator is `apply_plan(plan_id)`,
> `ChatTools.java:368-376`, ARCH-009). Resume is the first-class **`POST /api/v1/chat/sessions/{sessionId}/plans/{planId}/decision`**
> (approve/modify/reject) endpoint (SPEC §7.16 #10) — the PULSE analogue of n8n's `Command({resume})`. The plan-decision
> channels (`planOutput`/`mode`/`planDecision`/`planFeedback`/`planPrevious`) live on the `AgentState` and map onto PULSE's
> `Plan` lifecycle (`Plan.java`: `status` DRAFT→PREVIEW→APPROVED→APPLIED).

---

## 2. SNAPSHOT / REVERT

### 2.1 What n8n ACTUALLY does — `[read H, I]` + `[ref #23060]`

n8n does **NOT** snapshot the workflow JSON inside the agent before edits. Two mechanisms cover "undo an AI edit":

1. **Checkpoint state** — the graph is `.compile({ checkpointer })` with a `MemorySaver` `[read A, H]`; per-thread state is
   read back via `agent.getState({ configurable: { thread_id } })` `[read H]`, and persisted via
   `saveSessionSafe → sessionManager.saveSessionFromCheckpointer(threadId, previousSummary)` `[read I]`. The checkpointer
   restores the *conversation + op state*, not a product-snapshot.
2. **Workflow versioning + message-anchored restore** `[ref #23060]` — every Customer message carries a `versionId` in
   `additional_kwargs` (`ChatPayload.versionId` "Version ID to store in message metadata for restore functionality"
   `[read H]`). The **front end saves the workflow → gets a `versionId` → sends it with the message**; restore =
   `workflow-history` restores that version, then the backend **`truncateMessagesAfter(messageId)`** removes that message
   AND all messages after it `[read I]`, and the FE truncates its local chat to match `[ref #23060]`.

```ts
// [read I] ai-workflow-builder-agent.service.ts
// "Truncate all messages including and after the message with the specified messageId.
//  Used when restoring to a previous version"
async truncateMessagesAfter(workflowId, user, messageId, versionCardId?) {
  return await this.sessionManager.truncateMessagesAfter(workflowId, user.id, messageId, 'code-builder', versionCardId);
}
```

So n8n's "snapshot" is the **saved workflow version** taken *before the turn on the FE*, and "revert" is **restore-version
+ truncate-chat-to-that-message** `[ref #23060]`. (Restore also force-resets builder mode back to `'build'`, else an empty
canvas auto-switches to plan mode `[ref #25903]`.)

### 2.2 PULSE's snapshot/revert contract = composition revert

PULSE is **strictly safer** than n8n here: because PULSE's CANONICAL graph is **write-locked behind Apply** (SPEC §7.9,
§7.10), the staging clone IS the snapshot semantics for the common case — "revert" is just "drop the staging clone," not
"undo applied writes." The contract folds n8n's two mechanisms onto PULSE's model:

- WHEN a Customer turn begins THE SYSTEM SHALL take a **canonical snapshot** — the full `CompositionView` of the active
  version — as the turn's revert point (SPEC §7.8), and SHALL initialize the STAGING graph as a deep clone of it; ops
  apply to the clone (the n8n `structuredClone` of `getEffectiveWorkflow()` `[read G]`, made durable).
- WHEN the Customer **rejects**, **cancels mid-turn**, or clicks **Restore** THE SYSTEM SHALL discard the STAGING graph and
  re-render from the snapshot; the CANONICAL graph is untouched in all three cases (PULSE's no-silent-write guarantee,
  SPEC §7.8).
- WHEN a turn HAS been applied and the Customer later clicks **Restore** THE SYSTEM SHALL (a) restore the version's
  composition from the snapshot, and (b) **truncate the chat back to the turn's anchor message** — the PULSE analogue of
  n8n's `truncateMessagesAfter(messageId)` `[read I]` — keyed by the turn's anchor message id.
- WHEN restore completes THE SYSTEM SHALL reset the orchestration phase to a fresh **build** baseline (the n8n
  restore→build-mode reset `[ref #25903]`), so the next message builds rather than mis-routing on an empty staging graph.

> RESOLVED (operator 2026-06-16, ADR 0025 §3): the snapshot STORE is the LangGraph4j **`langgraph4j-postgres-saver`**
> Postgres checkpointer — **no separate `chat_turn_snapshots` table** (SPEC §7.16 #4). The per-turn canonical snapshot and
> the staging clone are checkpoints in the checkpointer's own Postgres tables, keyed by thread (session) + checkpoint
> (turn); the message-anchored restore (n8n's `truncateMessagesAfter` `[ref #23060]`) maps onto truncating chat to the
> turn's anchor message. **Undo = restore the checkpoint snapshot (no new commands)**, NOT an inverse plan (SPEC §7.16
> #13): one turn = one checkpoint, and the canonical write is gated behind Apply, so snapshot-restore (the checkpointer's
> `getState`/restore/time-travel) is strictly cheaper/safer than deriving and replaying inverse commands.

---

## 3. AUTO-LAYOUT (Dagre tidy-up)

### 3.1 What n8n ACTUALLY does — `[read J, K]` + `[ref #25905/#29850/#30455]`

n8n's auto-layout is **Dagre**. The SDK has two strategies, and the AI path uses the Dagre one `[read J]`:

```ts
// [read J] workflow-sdk/src/workflow-builder/layout-utils.ts (file header, verbatim)
/**
 * Two layout strategies:
 * 1. BFS layout (calculateNodePositions) — simple left-to-right BFS, used by default toJSON()
 * 2. Dagre layout (calculateNodePositionsDagre) — mirrors the FE's useCanvasLayout algorithm,
 *    used by toJSON({ tidyUp: true })
 */
import dagre from '@dagrejs/dagre';

export function calculateNodePositionsDagre(
  nodes: ReadonlyMap<string, GraphNode>,
): Map<string, [number, number]> {
  const positions = new Map<string, [number, number]>();
  if (nodes.size === 0) return positions;
  // Classify nodes (AI parent/config), seed Dagre with explicit config.position, run dagre.layout, snap to GRID_SIZE…
}
```

Key behaviors of n8n's auto-layout `[read J] + [ref]`:

- The layout is invoked via **`builder.toJSON({ tidyUp: true })`** → `calculateNodePositionsDagre` `[read J, ref #29850]`.
- It **respects explicit `config.position`** (seeds Dagre with existing x,y, only computes positions for *unpositioned*
  nodes) so content-only edits don't reflow the whole canvas `[ref #29850]`.
- It **snaps final positions to the grid** (`Math.round(value / GRID_SIZE) * GRID_SIZE`, 16px) `[ref #30455]`.

The **front-end reconcile** that drives liveness is `useWorkflowUpdate.ts` `[read K]`:

```ts
// [read K] frontend/editor-ui/src/app/composables/useWorkflowUpdate.ts
function tidyUpNodes(nodeIdsFilter?: string[]): void {
  canvasEventBus.emit('tidyUp', { source: 'builder-update', nodeIdsFilter, trackEvents: false, trackHistory: true, trackBulk: false });
}
async function updateWorkflow(workflowData, options) {
  // removeStaleNodes → addNewNodes → updateExistingNodes (preserves manual positions)…
  const hasStructuralChanges = nodesToAdd.length > 0 || nodesToRemove.length > 0;
  if (hasStructuralChanges) { tidyUpNodes(); }   // FULL re-layout ONLY on add/remove [ref #25905]
}
```

`tidyUpNodes()` with **no filter = full re-layout** (`layout('all')`); the **critical fix `[ref #25905]`** was to run a
*full* re-layout on any structural change (add OR remove), because filtering to just-new nodes left existing nodes in
stale, overlapping positions on follow-up edits. Pure param edits do **not** trigger tidy-up `[read K]`.

### 3.2 PULSE's auto-layout contract = React Flow tidy-up

PULSE's canvas is `@xyflow/react` (React Flow), not n8n's Vue canvas (CLAUDE.md; SPEC §7.6). The Dagre contract maps 1:1:

- WHEN the client receives a `candidate_graph` staging snapshot (SPEC §7.5) THE SYSTEM SHALL **reconcile** against the
  rendered canvas: `categorize → {toAdd, toUpdate, toRemove}` by `instanceRef`; `updateExisting` (mutate params/labels
  **in place, preserving the Customer's manual node positions**); `removeStale`; `addNew` (new ghost nodes get
  auto-positions); reconcile wirings — the n8n `updateWorkflow` model `[read K]` (SPEC §7.6 steps 1-4).
- WHEN — and ONLY WHEN — the reconcile produced a **structural change** (an instance added OR removed, or a wire
  added/removed) THE SYSTEM SHALL run `autoLayout()` over the staging graph via **Dagre (`@dagrejs/dagre`)** — the n8n
  `calculateNodePositionsDagre` + `tidyUpNodes()`-without-filter behavior `[read J, K; ref #25905]`. WHEN the change is a
  **pure param edit** THE SYSTEM SHALL NOT re-layout (so the canvas does not jump during conversational tuning, SPEC §7.6
  step 5).
- WHEN auto-layout runs THE SYSTEM SHALL **respect explicit/manual node positions** (seed the layout with existing x,y;
  compute positions only for unpositioned ghost nodes) `[ref #29850]`, and SHALL **snap final positions to the canvas
  grid** `[ref #30455]`.

> GUESS — PULSE has no node-position store today: `SubPipelineInstance` carries no x/y (SPEC §7.6/§7.16 #11). This contract
> assumes a **client-side position map keyed by `instanceRef` during a turn, persisted to instance metadata at Apply**.
> PULSE must add `@dagrejs/dagre` to the frontend (n8n's exact dependency `[read J]`) and a React Flow `autoLayout()` that
> mirrors `calculateNodePositionsDagre`. Operator/SPEC-GATE to confirm the position store (SPEC §7.16 #11) and that Dagre
> (vs ELK or React Flow's own layouting) is the chosen engine.

---

## 4. n8n → PULSE mapping (one table)

| Concern | n8n (real source) | PULSE realization | EARS / SPEC anchor |
|---|---|---|---|
| Orchestration graph | `createMultiAgentWorkflowWithSubgraphs` = `new StateGraph(ParentGraphState).…compile({ checkpointer })` `[read A]` | **LangGraph4j `StateGraph` (Option A — ADR 0025)**; the staged graph is the decision (Option B phase-gated loop is the named fallback) | §1.2; SPEC §7.1/§7.16 #1 |
| Supervisor/router | `supervisor` node → `{ nextPhase }`; `routeToNode` `[read A]` | `router` node classifies the turn; deterministic next-phase routing | §1.2 |
| Stage agents | Supervisor / Discovery(+Planner) / Responder / Assistant; **Builder externalized** `[read A]` | router / discovery / composer / configure / provision / planner / responder NODES (LOCKED 7; configure + provision have no n8n graph analogue) | §1.1; **divergence §6.1** |
| Per-stage LLM | `stageLLMs.{supervisor,discovery,planner,responder}` `[read A]` | cheap tier (`pulse.llm.cheap-model` → new `CHAT_CHEAP`, Flash; NOT the dead `schema-inference` key) on router/discovery/configure/provision/responder; reasoning tier (`pulse.llm.model`, Pro) on composer + planner; per-node escalation allowed (ADR 0025 §2) | §1.2; SPEC §7.16 #2 |
| Shared state | `ParentGraphState = Annotation.Root({ … 20 channels … })` `[read B]` | LangGraph4j **`AgentState`**: `CompositionView` + op-queue + phase + plan channels (ADR 0025 §1) | §1.3; SPEC §7.10 |
| Op-queue | `workflowOperations: operationsReducer`; `getEffectiveWorkflow()` `[read C, G]` | `PlanOperation` op-queue (append/clear reducer on `AgentState`); read tools see `applyOps(clone(canonical), pending)` | §1.3; SPEC §7.4/§7.1 |
| HITL plan gate | `interrupt({ type:'plan', plan })` + `Command({ resume })` `[read F, H]` | LangGraph4j **`interruptBefore` on the apply node**; resume via the `…/plans/{planId}/decision` endpoint (ADR 0025 §1) | §1.4; SPEC §7.2/§7.9 |
| approve/reject/modify | planner branches `[read F]` | Apply / discard+restore / rebuild-staging (capped) | §1.4; SPEC §7.2 |
| Snapshot | FE saves workflow version + `versionId` in `additional_kwargs` `[read H; ref #23060]` | per-turn canonical `CompositionView` snapshot as a **LangGraph4j checkpoint** (`langgraph4j-postgres-saver`) + STAGING clone (ADR 0025 §3) | §2.2; SPEC §7.8 |
| Revert | restore version + `truncateMessagesAfter(messageId)` `[read I]` | drop staging clone (pre-Apply) OR **restore the checkpoint** + truncate chat to anchor msg (undo = restore snapshot, not inverse plan) | §2.2; SPEC §7.8/§7.11 |
| Auto-layout | `calculateNodePositionsDagre` (`@dagrejs/dagre`), `toJSON({ tidyUp:true })` `[read J]` | React Flow `autoLayout()` via Dagre, on staging reconcile | §3.2; SPEC §7.6 |
| Layout trigger | full `tidyUpNodes()` only on structural change `[read K; ref #25905]` | re-layout only on add/remove (not pure param edits); preserve manual positions; snap to grid | §3.2; SPEC §7.6 |

---

## 5. Resolved decisions (operator 2026-06-16 — keystone ADR 0025)

1. **Staged-vs-single-loop.** > RESOLVED: **Option A — build the staged graph now as a LangGraph4j `StateGraph`** (ADR
   0025). Option B (phase-gated single loop) is the named fallback. — §1.2; SPEC §7.16 #1.
2. **Recursion bound.** > RESOLVED: **`MAX_TOOL_ROUNDS = 40`** per turn (raised from 30). — §1.2; SPEC §7.16 #3.
3. **Per-stage model matrix.** > RESOLVED: cheap tier (Gemini Flash) on router/discovery/configure/provision/responder;
   reasoning tier (Gemini Pro) on composer + planner; per-node escalation allowed (ADR 0025 §2). — §1.2; SPEC §7.16 #2.
4. **Shared-state channels.** > RESOLVED: the carrier is the LangGraph4j **`AgentState`** (CompositionView + op-queue +
   phase + plan-lifecycle channels), ADR 0025 §1. — §1.3; SPEC §7.10.
5. **Plan-decision transport.** > RESOLVED: add the first-class session-scoped **`POST …/plans/{planId}/decision`**
   endpoint (the `interruptBefore` resume / `Command({resume})` analogue). — §1.4; SPEC §7.5/§7.16 #10.
6. **Snapshot store + undo semantics.** > RESOLVED: the snapshot store is the **`langgraph4j-postgres-saver`** Postgres
   checkpointer (no `chat_turn_snapshots` table); **undo = restore the checkpoint snapshot (no new commands)**, NOT an
   inverse plan (ADR 0025 §3). — §2.2; SPEC §7.16 #4, #13.
7. **Node-position store + layout engine.** > RESOLVED (DEFAULT): client-side position map keyed by `instanceRef`,
   persisted at Apply; **Dagre (`@dagrejs/dagre`)** as the engine, re-layout on structural change only. — §3.2; SPEC §7.16 #11.

---

## 6. Divergences logged (real n8n source vs the SPEC/summary it was built on)

### 6.1 The "5 separate agents" framing is HISTORICAL, not current-master.

The SPEC **brief** (the n8n summary doc) describes n8n as five agents **Supervisor / Discovery / Builder / Planner /
Responder** (a faithful read of PR #17423 + the planning PRs) — note this is n8n's *historical* decomposition, NOT PULSE's
stage model (PULSE's LOCKED model is the **seven** phases above; SPEC §7.1). **Current n8n master `[read A]` runs four
graph nodes + a planner-inside-Discovery, with the standalone Builder removed** ("With builder removed, redirect back to discovery to retry planning" `[read A]`); the code
builder runs as a **post-approval path** (`runCodeWorkflowBuilder`, reached after the plan `interrupt` resolves `approve`
`[read H]`). **Impact on PULSE:** none structurally — PULSE's **LOCKED seven logical phases** (router/discovery/composer/
configure/provision/planner/responder) are a clean decomposition regardless; but the operator should know that the "shape
to match" already collapsed Builder into a post-approval step and folded Planner into Discovery. PULSE's `composer` phase
is the analogue of n8n's externalized code-builder, PULSE's `planner` phase is the analogue of n8n's
planner-inside-discovery, and PULSE's `configure`/`provision` phases are PULSE-specific (no n8n builder-graph analogue;
`provision` loosely mirrors n8n's `configurator`).

### 6.2 n8n does NOT snapshot inside the agent; PULSE's plan-gate makes snapshot CHEAPER.

n8n's "revert" is FE-saved workflow versions + `truncateMessagesAfter` `[read H, I; ref #23060]`, and its canonical graph
IS mutated live (it only softens last-writer-wins). PULSE's canonical graph is **write-locked behind Apply** (SPEC §7.9),
so the common-case revert is "drop the staging clone" — strictly safer. The SPEC §7.8 framing ("made structurally
cheaper") is **confirmed correct** against the real source.

### 6.3 Auto-layout: PULSE must take a NEW frontend dependency (`@dagrejs/dagre`) + a structural-change gate.

n8n's auto-layout is Dagre, gated to **structural changes only**, preserving manual positions and snapping to grid
`[read J, K; ref #25905/#29850/#30455]`. PULSE's SPEC §7.6 already specifies "Dagre / `@dagrejs/dagre`… only on STRUCTURAL
change" — this fragment **confirms that against the real source** and adds the two behaviors the SPEC under-specified:
**seed-with-explicit-positions** (`[ref #29850]`) and **snap-to-grid** (`[ref #30455]`). No divergence; two additions.
