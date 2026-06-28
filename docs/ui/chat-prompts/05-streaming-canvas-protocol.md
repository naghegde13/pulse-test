# 05 — STREAM-TO-CANVAS protocol (Author #3)

> Fragment of the Chat-prompts spec set. This section authors PULSE's **stream-to-canvas
> protocol contract** — the wire envelope, the typed-op → staging-graph stream, the ghost-node
> rendering + canonical-vs-staging diff overlay, and the Apply/reject commit.
>
> **Grounding discipline.** `[read]` marks a fact read from a cited source — either the **RAW n8n
> implementation** (`github.com/n8n-io/n8n`, `master`, paths cited inline) or the **live PULSE code**
> (path:line cited inline). `> GUESS:` marks a PULSE-specific contract/name/shape this author had to
> invent rather than read. This is the n8n SHAPE re-expressed in PULSE terms — adapt-the-shape, NOT
> copy-the-code (n8n is Sustainable-Use-licensed). Normative sentences use EARS
> ("WHEN <trigger> THE SYSTEM SHALL <response>").
>
> **Defined vocab (verbatim):** Customer · op · op-list · params · Blueprint · dataset · port ·
> Command Log · Plan Preview · Apply Plan · **CANONICAL graph** (persisted composition) · **STAGING
> graph** (the candidate an agent turn builds, never persisted until Apply). See SPEC-ui-composition.md §7.

---

## A. What n8n actually does (read from the RAW source)

This is the n8n stream-to-canvas pipeline, read end-to-end from the implementation — **not** from a
summary. n8n's AI builder lives in `packages/@n8n/ai-workflow-builder.ee/` (the agent) and
`packages/frontend/editor-ui/` (the canvas consumer); the HTTP edge is in `packages/cli/`.

### A.1 The wire: chunked HTTP + newline-delimited JSON (jsonl), NOT SSE

`[read]` n8n does **not** use SSE. The builder endpoint streams **`Content-type: application/json-lines`**
over a single chunked HTTP `POST /rest/ai/build`, writing one JSON object per chunk, each terminated by
a separator. From `packages/cli/src/controllers/ai.controller.ts` (the `build` handler):

```ts
// packages/cli/src/controllers/ai.controller.ts  (master)
import { FREE_AI_CREDITS_CREDENTIAL_NAME, STREAM_SEPARATOR } from '@/constants';
// ...
const abortController = new AbortController();
const { signal } = abortController;
const handleClose = () => abortController.abort();
res.on('close', handleClose);                       // client disconnect → abort the agent

const aiResponse = await this.aiWorkflowBuilderService.chat(/* payload */, req.user, signal);

res.header('Content-type', 'application/json-lines').flush();
try {
  for await (const chunk of aiResponse) {           // chunk = StreamOutput { messages: StreamChunk[] }
    res.flush();
    res.write(JSON.stringify(chunk) + STREAM_SEPARATOR);   // one JSON object + '\n'
  }
} catch (streamError) {
  const errorChunk = { messages: [{ role: 'assistant', type: 'error', content: streamError.message }] };
  res.write(JSON.stringify(errorChunk) + STREAM_SEPARATOR);   // errors ride INSIDE the stream
} finally {
  res.off('close', handleClose);
}
res.end();
```

`[read]` The separator is a literal newline. `packages/frontend/editor-ui/src/features/ai/chatHub/chat.api.ts`:
`// Workflows stream data as newline separated JSON objects (jsonl)` → `const STREAM_SEPARATOR = '\n';`.
(There is a legacy magic separator `'⧉⇋⇋➽⌑⧉§§\n'` in git history — `packages/frontend/@n8n/rest-api-client/src/utils.ts` default — but the builder path uses `'\n'`.)

`[read]` The frontend parser buffers partial chunks and splits on the separator — from
`packages/frontend/@n8n/rest-api-client/src/utils.ts` (`streamRequest<T>`):

```ts
const reader = response.body.getReader();
const decoder = new TextDecoder('utf-8');
let buffer = '';
async function readStream() {
  const { done, value } = await reader.read();
  if (done) { response.ok ? onDone?.() : onErrorOnce?.(/* ResponseError */); return; }
  buffer += decoder.decode(value);
  const splitChunks = buffer.split(separator);
  buffer = '';
  for (const splitChunk of splitChunks) {
    if (!splitChunk) continue;
    let data: T;
    try { data = jsonParse<T>(splitChunk, { errorMessage: 'Invalid json' }); }
    catch { buffer += splitChunk; continue; }       // incomplete JSON → re-buffer until complete
    onChunk?.(data);
  }
  // ...recurse
}
```

So the framing contract is: **length-unprefixed, newline-delimited JSON objects; partial trailing
objects are re-buffered until the next read completes them.** Each object is a `StreamOutput`.

### A.2 The chunk catalog: the `StreamChunk` discriminated union

`[read]` `packages/@n8n/ai-workflow-builder.ee/src/types/streaming.ts` — the agent yields
`StreamOutput { messages: StreamChunk[]; interruptId?: string }`, where `StreamChunk` is discriminated
on `type`:

| `type` | shape (fields) | meaning |
|---|---|---|
| `message` | `{ role:'assistant', type:'message', text, codeSnippet? }` | streamed assistant chat text |
| `tool` | `{ type:'tool', toolName, status, [key]:unknown }` (`ToolProgressChunk`) | tool-status pill |
| **`workflow-updated`** | `{ role:'assistant', type:'workflow-updated', codeSnippet: <JSON-stringified workflow>, iterationCount?, sourceCode? }` (`WorkflowUpdateChunk`) | **the canvas mutation payload** |
| `execution-requested` | `{ role:'assistant', type:'execution-requested', reason }` | ask to run the workflow |
| `questions` | `{ role:'assistant', type:'questions', introMessage?, questions: PlannerQuestion[] }` | clarifying-questions HITL |
| `plan` | `{ role:'assistant', type:'plan', plan: PlanOutput }` | plan-mode preview |
| `session-messages` | `{ type:'session-messages', messages: unknown[] }` | persistence side-channel |
| `code-diff` | `{ role:'assistant', type:'code-diff', suggestionId, sdkSessionId, codeDiff?, ... }` | inline code suggestion |
| `messages-compacted` | `{ type:'messages-compacted' }` | history compacted; UI clears old msgs |
| `summary` | `{ role:'assistant', type:'summary', title, content }` | structured summary |
| `agent-suggestion` | `{ role:'assistant', type:'agent-suggestion', title, text, suggestionId? }` | suggestion card |
| `web_fetch_approval` | `{ role:'assistant', type:'web_fetch_approval', requestId, url, domain }` | HITL approval |

`[read]` `ToolProgressMessage` (the richer tool-status shape, `packages/@n8n/ai-workflow-builder.ee/src/types/tools.ts`):
`{ type:'tool', toolName, toolCallId?, status:'running'|'completed'|'error', updates: ProgressUpdate[],
displayTitle?, customDisplayTitle? }`, with `ProgressUpdateType = 'input'|'output'|'progress'|'error'`.
`displayTitle` is the generic UI label ("Adding nodes"); `customDisplayTitle` is the specific one
("Adding Gmail node").

**Key observation:** the canvas payload (`workflow-updated`) carries the **entire workflow** as a
JSON string (`codeSnippet`), not a delta. n8n streams **full coarse snapshots**, not per-edge events.

### A.3 The typed-op stream: `WorkflowOperation` + the reducer drain

`[read]` Inside the agent, tool calls do NOT mutate the workflow directly — they enqueue typed ops onto
a `workflowOperations[]` state field, drained by a dedicated node. `packages/@n8n/ai-workflow-builder.ee/src/types/workflow.ts`:

```ts
// types/workflow.ts  (master)  — discriminated on `type`
export type WorkflowOperation =
  | { type: 'clear' }
  | { type: 'removeNode'; nodeIds: string[] }
  | { type: 'addNodes'; nodes: INode[] }
  | { type: 'updateNode'; nodeId: string; updates: Partial<INode> }
  | { type: 'setConnections'; connections: IConnections }
  | { type: 'mergeConnections'; connections: IConnections }
  | { type: 'removeConnection'; sourceNode: string; targetNode: string;
      connectionType: string; sourceOutputIndex: number; targetInputIndex: number }
  | { type: 'setName'; name: string }
  | { type: 'renameNode'; nodeId: string; oldName: string; newName: string };
```

`[read]` `packages/@n8n/ai-workflow-builder.ee/src/utils/operations-processor.ts` — each op maps to a
pure handler `(SimpleWorkflow, WorkflowOperation) => SimpleWorkflow`; `applyOperations` threads the
state through every handler in sequence; `processOperations` is the **drain node**: it applies the
accumulated `workflowOperations` to `workflowJSON` and **returns the queue cleared** (operations are an
immutable queue that drains once):

```ts
const operationHandlers: Record<WorkflowOperation['type'], OperationHandler> = {
  clear, removeNode, addNodes, updateNode,
  setConnections, mergeConnections, removeConnection, setName, renameNode,
};
export function applyOperations(workflow, operations) {
  let result = { nodes: [...workflow.nodes], connections: { ...workflow.connections }, name: workflow.name || '' };
  for (const operation of operations) result = operationHandlers[operation.type](result, operation);
  return result;
}
// processOperations(state) → applyOperations(state.workflowJSON, state.workflowOperations)
//   then returns { workflowJSON: <new>, workflowOperations: [] }  (queue drained + reset)
```

Notable handler semantics `[read]`: `addNodes` dedups by node **id** via a Map; `mergeConnections` is
**additive** (stringified-signature dedup) vs `setConnections` which **replaces**; `removeNode` filters
the node and recursively scrubs connection references **by node name**; `renameNode` instantiates the
real `Workflow` class to atomically rewrite expressions + connection refs + the name. The agent runs on
LangGraph with `streamMode: ['updates', 'custom']` and subgraphs — ops accumulate across the turn, then
the `process_operations` superstep drains them and the agent emits a fresh `workflow-updated` snapshot.

### A.4 The canvas consumer: direct mutation, reconcile-by-diff, NO ghost layer

`[read]` The frontend store (`packages/frontend/editor-ui/src/features/ai/assistant/builder.store.ts`,
`useBuilderStore`) feeds each chunk to `processAssistantMessages`; a `workflow-updated` chunk routes to
`packages/frontend/editor-ui/src/app/composables/useWorkflowUpdate.ts`. There, `categorizeNodes` splits
incoming nodes into `nodesToUpdate / nodesToAdd / nodesToRemove` — **match by id first, fall back to
`type::name`** (because the SDK regenerates ids on re-parse). Then `updateWorkflow`:

1. apply default credentials to incoming nodes;
2. `updateExistingNodes(nodesToUpdate)` — mutate params/labels **in place, preserving existing positions**;
3. `canvasOperations.deleteNode(...)` the stale; `canvasOperations.addNodes(...)` the new;
4. reconcile connections (remove old / add new);
5. `tidyUpNodes()` → emits `'tidyUp'` on `canvasEventBus` (auto-layout).

`[read]` **The decisive fact: n8n mutates the REAL canvas directly — there is no ghost / candidate /
staging layer.** The candidate workflow *is* the live workflow store (`setNodes` / `setConnections`); no
overlay is rendered. The only protections are (a) the canvas goes **read-only while streaming**
(`packages/frontend/editor-ui/src/app/views/NodeView.vue`: `isCanvasReadOnly` includes
`builderStore.streaming && !builderStore.isHelpStreaming`), and (b) the whole AI edit is wrapped in a
single **undo bracket** (`useWorkflowUpdate` note: "Undo recording should be managed by the caller …
start when streaming begins, stop when it ends"). Review/accept-reject is a **chat-level** affordance
over already-applied state, **not** a pre-commit gate.

> This is exactly the seam PULSE inverts (SPEC-ui-composition.md §8.2 / §8.4): n8n's canvas IS the
> source of truth being live-mutated; PULSE interposes a STAGING graph so the canonical graph is
> write-locked behind Apply.

---

## B. The live PULSE transport (what exists today)

`[read]` PULSE's chat transport is **SSE**, not n8n's jsonl-over-chunked-HTTP. The endpoint is
`POST /api/v1/chat/sessions/{sessionId}/messages` with `produces = MediaType.TEXT_EVENT_STREAM_VALUE`,
returning a Spring `SseEmitter` (`ChatController.java:76-77`). Events are named via
`SseEmitter.event().name(...)` in `ChatService.java`; the names that exist **today**:

| live SSE `event:` | `[read]` site | `data:` |
|---|---|---|
| `chunk` | `ChatService.java:522, 847` | streamed assistant text delta (string) |
| `tool_call` | `ChatService.java:667` | tool name (string) |
| `tool_result` | `ChatService.java:694` | the `ToolResult` envelope JSON (below) |
| `navigate` | `ChatService.java:682, 699` | a route path (string) — UI-Chat sync |
| `done` | `ChatService.java:530, 734` | `""` (turn complete) |
| `error` | `ChatService.java:495` | error JSON (`MediaType.APPLICATION_JSON`) |

`[read]` The `tool_result` envelope is `ToolResult` (`ToolResult.java:35-47`):
`{ toolName, status (ok|error|planCreated|rejected), planCreated, mutationApplied, planId,
commandIds, affectedEntities[], declaredDraftRefs[], previewCommands[], uiIntents[], refreshHints[],
message }`. Its own Javadoc is binding: *"Frontend refresh and toast behavior must key off
`mutationApplied` and `refreshHints`, never the tool name."* (`ToolResult.java:13-15`).

**Mapping of transports.** n8n's jsonl framing and PULSE's SSE framing are isomorphic for our purposes:
one n8n `StreamOutput` ≈ one PULSE SSE event; n8n's `type` discriminant ≈ PULSE's SSE `event:` name.
This spec keeps **PULSE's SSE channel** (it exists and works) and re-expresses n8n's chunk catalog onto
SSE event names. n8n's `workflow-updated` snapshot chunk has **no PULSE equivalent yet** — that is the
core new event this protocol adds.

---

## C. The PULSE stream-to-canvas protocol (the CONTRACT)

PULSE's BLEND (SPEC-ui-composition.md §6, §7.9): the agent streams typed ops into a **STAGING graph**
rendered as **ghost/candidate nodes** via React Flow `@xyflow/react`; the **diff IS the Plan Preview**;
**Apply** commits to the canonical graph + Command Log; **reject** discards staging — true
no-silent-write. This section pins that as a wire contract shaped like n8n's, in PULSE terms.

### C.1 Transport + framing

WHEN a Customer sends a chat message THE SYSTEM SHALL stream the turn over the live SSE channel
`POST /api/v1/chat/sessions/{sessionId}/messages` (`text/event-stream`, `ChatController.java:76`) `[read]`,
emitting one SSE event per chunk, distinguished by the SSE `event:` name.

THE SYSTEM SHALL multiplex **two logical channels over the one SSE stream**: a **progress channel**
(chat text, tool status, questions, plan, errors) and a **graph-state channel** (the staging-graph
snapshot). They are separated by `event:` name only; there is no second physical transport.

WHEN the Customer cancels (the client `AbortController` closes the SSE connection) THE SYSTEM SHALL
detect the dead emitter (`emitterDead`, `ChatService.java:434-437, 535-543`) `[read]`, stop the loop,
discard the STAGING graph for the turn (§C.6), append an "aborted" assistant message, and NOT throw —
mirroring n8n's `res.on('close', () => abortController.abort())` graceful abort `[read ai.controller.ts]`.

> GUESS: n8n's transport is jsonl-over-chunked-HTTP; PULSE's is SSE. This spec does NOT adopt jsonl —
> PULSE already ships SSE. The two are equivalent framings; the only inherited n8n property is **errors
> ride inside the stream** (n8n writes an `error` chunk rather than failing the HTTP response once
> headers are sent — `ai.controller.ts` catch block), which PULSE already does via the `error` SSE
> event (`ChatService.java:495`). Operator/SPEC-GATE to confirm SSE is the final transport (vs adopting
> n8n's jsonl for symmetry with the n8n reference implementation).

### C.2 The SSE event schema (the chunk catalog, PULSE terms)

`[read]` events exist today; `(new)` events are added by this protocol (and are flagged GUESS below).

| SSE `event:` | `data:` JSON shape | channel | meaning |
|---|---|---|---|
| `chunk` `[read ChatService:522,847]` | text delta (string) | progress | streamed assistant prose (router/responder) |
| `tool_call` `[read ChatService:667]` | tool name (string) | progress | the LLM is invoking a tool |
| `tool_progress` (new) | `{ toolName, toolCallId, status:'running'\|'completed'\|'error', displayTitle, customDisplayTitle?, updates:[{type:'input'\|'output'\|'progress'\|'error', data}] }` | progress | live tool-status pill ("Adding loan_master ingestion…") — n8n `ToolProgressMessage` shape `[read tools.ts]`, PULSE labels |
| `tool_result` `[read ChatService:694, ToolResult.java]` | the `ToolResult` envelope (§B) | progress | structured outcome; frontend keys off `mutationApplied` + `refreshHints`, never the tool name |
| **`candidate_graph`** (new) | `{ versionId, turnId, instances:[…], wirings:[…] }` — the **full** STAGING `CompositionView` snapshot | graph-state | **the canvas mutation payload** — PULSE's analogue of n8n `workflow-updated` |
| `questions` (new) | `{ introMessage?, questions:[{ id, text }] }` | progress | clarifying-questions HITL (one `?` per Absolute Rule #1) — n8n `QuestionsChunk` shape `[read streaming.ts]` |
| `plan` (new) | `{ planId, plan:{ summary, trigger, steps[], additionalSpecs } }` | progress | the Plan Preview HITL card (§7.2) — n8n `PlanChunk` shape `[read streaming.ts]` |
| `navigate` `[read ChatService:682,699]` | a route path (string) | progress | UI-Chat sync (`navigate_ui`) |
| `messages_compacted` (new) | `{}` | progress | history auto-compacted; UI clears old msgs — n8n `MessagesCompactedChunk` `[read streaming.ts]` |
| `done` `[read ChatService:530,734]` | `""` | progress | turn complete |
| `error` `[read ChatService:495]` | `{ code, message, cause?, upstream? }` | progress | structured streaming error (rides inside the stream) |

WHEN the agent emits assistant prose, a tool invocation, a tool outcome, a navigation, a clarifying
question, a plan, a compaction signal, completion, or an error THE SYSTEM SHALL emit it on the
**progress channel** under the corresponding `event:` name above.

WHEN the staging graph changes (§C.4) THE SYSTEM SHALL emit it on the **graph-state channel** as a
`candidate_graph` event carrying the **full** staging composition (a coarse snapshot), NOT per-edge
deltas — inheriting n8n's coarse-snapshot design (`workflow-updated` carries the whole workflow string;
liveness is reconstructed client-side, §C.5).

> GUESS: `tool_progress`, `candidate_graph`, `questions`, `plan`, `messages_compacted` are NEW SSE
> events; only `chunk`/`tool_call`/`tool_result`/`navigate`/`done`/`error` exist today (`ChatService.java`).
> Event names (`candidate_graph` vs `plan_updated`; `messages_compacted` snake-case to match
> `tool_call`/`tool_result`) are this author's choice — Operator/SPEC-GATE to confirm the final names.

### C.3 The typed-op stream — `PlanOperation` → STAGING-graph mutation

`[read]` PULSE's op union (`PlanOperation`, SPEC-ui-composition.md §7.4) is the PULSE analogue of n8n's
`WorkflowOperation` (`types/workflow.ts`), with PULSE field names — keyed by **`instanceRef` = instance
NAME** (n8n keys connections by node *name*; pre-apply, staged instances have no real id yet). The op
union, the per-turn op-queue, and the single drain step mirror n8n's `WorkflowOperation` +
`operationsReducer` + `processOperations` exactly:

| n8n `WorkflowOperation` `[read]` | PULSE `PlanOperation` (op) `[read §7.4]` | mutation tool `[read §7.3B]` |
|---|---|---|
| `addNodes` (dedup by id) | `addInstances` (dedup by `instanceRef`) | `add_blueprint_instance` |
| `removeNode` | `removeInstance` | `remove_instance` |
| `updateNode` | `updateInstance` | `set_params` |
| `setConnections` (replace) | `setWiring` (replace) | (Modify-rebuild) |
| `mergeConnections` (additive) | `mergeWiring` (additive, dedup) | `wire_ports` |
| `removeConnection` | `removeWiring` | `remove_wire` |
| `renameNode` | `rename` | `rename_instance` |
| `setName` | `setName` | (pipeline rename) |
| — | `setPipelineSetting` | `set_pipeline_setting` |
| `clear` | `clear` (reducer control) | — |

WHEN a mutation tool runs THE SYSTEM SHALL: (1) Zod/JSON-schema-validate its input; (2) read the STAGING
graph as `applyOps(clone(canonical), pendingOps)`; (3) run semantic validation (port-type +
dataset-schema-contract, §7.13 layer 2); (4) on success, **append exactly one `PlanOperation` to the
per-turn op-queue** and return a `tool_result`; on failure, return a typed error observation to the LLM.
A mutation tool SHALL NEVER write `sub_pipeline_instances` / `port_wirings` and SHALL NEVER write the
Command Log — only `apply_plan` does (§C.7). This is n8n's "tools enqueue ops, a node drains" exactly.

WHEN the op-queue reducer receives an op THE SYSTEM SHALL append it; WHEN it receives `{op:'clear'}` THE
SYSTEM SHALL empty the queue — mirroring n8n's `operationsReducer` `[read operations-processor.ts]`.

WHEN the loop settles a composer superstep THE SYSTEM SHALL run a single **`process_operations`** drain
that applies ALL queued ops atomically to the STAGING graph (`applyOps`, n8n's `applyOperations`),
**resets the queue to empty** (n8n's `processOperations` returns `workflowOperations: []`), and emits ONE
`candidate_graph` event carrying the full staging composition. Ops apply to the STAGING graph **only** —
never the canonical graph.

> GUESS: today's PULSE composition mutators (`plan_add_step`/`plan_wire_ports`/`plan_set_step_params`,
> and direct `wire_ports`/`configure_step_params`/`remove_step`, `ChatTools.java:274-310`) do NOT use an
> op-queue — some write a Plan, some write `CompositionService` immediately. This protocol REQUIRES
> re-timing ALL of them to the op-queue model (n8n's shape). The `process_operations` drain node and the
> per-turn op-queue do not exist yet. This is the single biggest divergence from today's code
> (SPEC-ui-composition.md §7.3B GUESS).

### C.4 STAGING-graph snapshot payload (`candidate_graph`)

`[read]` PULSE's canonical composition shape is `CompositionView { instances: SubPipelineInstance[],
wirings: PortWiring[] }` (`CompositionService.java:372-375`). The staging snapshot reuses it.

WHEN `process_operations` emits a `candidate_graph` THE SYSTEM SHALL set `data:` to:

```json
{
  "versionId": "<active version id>",
  "turnId":    "<this turn's id>",
  "instances": [ /* SubPipelineInstance projection: id?, instanceRef(name), blueprintKey,
                    blueprintVersion, executionOrder, params, inputDatasets, outputDatasets,
                    outputSchema, schemaStatus, lakeLayer, lakeFormat, storageBackend */ ],
  "wirings":   [ { "sourceRef": "<name>", "sourcePort": "...", "targetRef": "<name>", "targetPort": "..." } ]
}
```

This is the **full** staging composition (coarse snapshot), directly analogous to n8n's
`WorkflowUpdateChunk.codeSnippet` (the whole workflow JSON-stringified) `[read streaming.ts]`. PULSE
sends structured JSON rather than a stringified blob, but the granularity contract is identical: **one
full candidate snapshot per superstep, not incremental edge events** (n8n's robustness choice — coarse
snapshot + client reconcile beats fragile deltas).

### C.5 Liveness + ghost-node rendering (client reconcile on `@xyflow/react`)

The liveness is **produced client-side from the snapshot**, exactly as n8n reconstructs it in
`useWorkflowUpdate.updateWorkflow` `[read]`. WHEN the client receives a `candidate_graph` snapshot THE
SYSTEM SHALL reconcile it against the current canvas:

1. `categorize(candidate)` → `{ toAdd, toUpdate, toRemove }` keyed by **`instanceRef`** (n8n keys by id
   then `type::name`; PULSE keys by `instanceRef` = name, the only stable pre-apply handle) `[read useWorkflowUpdate.ts]`.
2. `updateExisting(toUpdate)` — mutate params/labels **in place**; **preserve the Customer's manual node
   positions** (n8n's explicit position-preservation invariant `[read]`).
3. `removeStale(toRemove)`; `addNew(toAdd)` — new **ghost** nodes get auto-positions.
4. reconcile wirings (remove-old / add-new edges).
5. `autoLayout()` (Dagre, `@dagrejs/dagre`) **only on STRUCTURAL change** (add/remove/wire), NOT on pure
   param edits — so the canvas does not jump during conversational tuning (a deliberate refinement over
   n8n's unconditional `tidyUpNodes()` `[read]`, which auto-lays-out on every update).

WHEN a candidate instance/wire is rendered THE SYSTEM SHALL render it as a **ghost / candidate node** on
the STAGING layer (status-colored border per §C.6), **distinct** from canonical nodes — **this is PULSE's
deliberate divergence from n8n**, which has no ghost layer and mutates the real canvas in place (§A.4).

WHEN a turn is streaming THE SYSTEM SHALL keep the **canonical** canvas read-only for direct mutation
(n8n's `isCanvasReadOnly` during `streaming` `[read NodeView.vue]`), so the only writer to canonical
state is Apply (§C.7). The Customer watches ghosts appear step-by-step (ghost node → ports light → edge
draws) — the LOCKED "Chat edits the graph and the surface reflects it LIVE" behavior, but on STAGING.

> GUESS: positions persist per the LOCKED layout, but `SubPipelineInstance` has no x/y today
> (SPEC-ui-composition.md §7.6 GUESS). This protocol assumes a client-side position map keyed by
> `instanceRef` during a turn, persisted to instance metadata at Apply. Operator/SPEC-GATE to confirm
> the position store.

### C.6 The diff overlay — canonical-vs-staging (`compareGraphs`)

The diff is the engine behind both the ghost rendering (§C.5) and the Plan Preview banner. It is PULSE's
analogue of n8n's `compareWorkflowsNodes` / `DiffableNode` `[read — n8n diff util]`, but computed
**PRE-commit** (PULSE inverts n8n's post-hoc, already-applied diff timing).

WHEN the client holds a `candidate_graph` snapshot THE SYSTEM SHALL compute
`compareGraphs(canonical, candidate)`:

```ts
compareGraphs(canonical: CompositionView, candidate: CompositionView):
  { instances: Map<instanceRef, { status: 'equal'|'added'|'modified'|'deleted'; instance }>,
    wirings:   Array<{ status: 'added'|'deleted'|'equal'; wire }> }
```

Instance equality is **content-based** over `{ name, blueprintKey, params, lakeLayer, lakeFormat,
storageBackend }`; wiring diff is a set-diff over `{ sourceRef, sourcePort, targetRef, targetPort }`.

WHEN the diff is computed THE SYSTEM SHALL: (a) render each changed instance with a status-colored ghost
border (added / modified / deleted) on `@xyflow/react`; (b) drive the **"Review N changes"** banner,
`N = count(added)+count(modified)+count(deleted)+changed wires`; (c) make that banner **the Plan
Preview** — clicking it opens the before/after overlay and surfaces each op's `reasoning` (§7.3B). **The
diff IS the plan**, shown BEFORE Apply — the inversion of n8n, whose diff is a post-hoc review of an
already-live edit (§A.4).

> GUESS: the comparable-projection field set is chosen here; no LOCKED source pins which
> `SubPipelineInstance` fields participate in content-equality (e.g. whether `dqExpectations` /
> `executionOrder` count as "modified" or are ignored to avoid noisy diffs). Operator/SPEC-GATE to confirm.

### C.7 Apply / reject commit

WHEN the Customer clicks **Apply Plan** THE SYSTEM SHALL run the single atomic `apply_plan(plan_id)` step
(`ChatTools.java:368-376`) `[read]`: (a) re-validate the plan is `APPROVED` and same-session; (b) run
`validate_plan` (the deterministic Builder pre-flight, §7.13 layer 3) — if it does not compile, REJECT
the Apply with blockers and mutate nothing; (c) resolve `instanceRef`s → real instance ids (draft-ref
binding); (d) in ONE transaction, write the canonical graph (`sub_pipeline_instances` / `port_wirings`)
AND one Command-Log entry per staged op (§7.4); (e) emit a `tool_result` with `mutationApplied=true`,
`planId`, `commandIds`, `refreshHints`; (f) **promote the ghosts to real (canonical) nodes**, clearing
the STAGING layer.

WHEN the Customer **rejects** (or cancels mid-turn, or clicks **Restore**) THE SYSTEM SHALL **discard the
STAGING graph** and re-render from the turn's canonical snapshot (§7.8); the canonical graph is
untouched in all three cases (it was never written until Apply). Because the canonical write is
write-locked behind Apply, "reject" is just "drop the staging clone" — structurally cheaper than n8n's
revert, which must **undo already-applied** canvas writes via the streaming undo bracket (§A.4).

WHEN `apply_plan` is invoked for a non-`APPROVED` plan, a plan from a different session, or a plan whose
`validate_plan` fails THE SYSTEM SHALL reject without side effects (`ChatTools.java:368-376`) `[read]`.

> GUESS: the `composition.*` Command-Log command types that Apply writes
> (`composition.addInstance`/`removeInstance`/`updateInstance`/`wirePorts`/`removeWiring`/`renameInstance`)
> do NOT exist today — composition mutations currently bypass the Command Log (SPEC-ui-composition.md §7.0,
> §7.4 GUESS). This protocol REQUIRES registering them so Apply is a first-class, idempotent, Command-Logged
> commit. Operator/SPEC-GATE to confirm the command-type strings.

---

## D. n8n real shape → PULSE BLEND (convergence / divergence ledger)

| Concern | n8n real shape `[read]` | PULSE BLEND | verdict |
|---|---|---|---|
| Transport framing | chunked HTTP, `application/json-lines`, `res.write(JSON+'\n')`, partial-chunk re-buffer (`ai.controller.ts`, `utils.ts`, `STREAM_SEPARATOR='\n'`) | SSE `text/event-stream`, `SseEmitter.event().name(...)` (`ChatController.java:76`, `ChatService.java`) | **diverge** (both valid; PULSE keeps SSE) |
| Errors mid-stream | written as an `error` chunk inside the stream (headers already sent) | `error` SSE event inside the stream | **converge** |
| Chunk catalog | `StreamChunk` union on `type` (`streaming.ts`) | SSE events by `event:` name (§C.2) | **converge** (isomorphic) |
| Canvas payload | `workflow-updated`: the WHOLE workflow as a JSON string; coarse snapshot, no deltas | `candidate_graph`: the WHOLE staging `CompositionView`; coarse snapshot, no deltas | **converge** (same granularity) |
| Typed ops | `WorkflowOperation` union; tools enqueue; `processOperations` drains + clears (`workflow.ts`, `operations-processor.ts`) | `PlanOperation` union; tools enqueue; `process_operations` drains + clears (§C.3) | **converge** (PULSE field names) |
| Op keying | connections keyed by node **name**; nodes dedup by id | wiring keyed by `instanceRef` = instance **name** | **converge** |
| Connection merge | `mergeConnections` additive vs `setConnections` replace | `mergeWiring` additive vs `setWiring` replace | **converge** |
| Client reconcile | `categorizeNodes` (id → `type::name` fallback) + `updateWorkflow`; **preserve positions** | `categorize` by `instanceRef` + `reconcile`; **preserve positions** | **converge** |
| Auto-layout | unconditional `tidyUpNodes()` every update | Dagre `autoLayout()` **only on structural change** | **diverge** (PULSE refines — no jump on param tuning) |
| **Where the candidate lives** | **the REAL canvas store, mutated in place — NO ghost/staging layer** (`useWorkflowUpdate`, `builder.store`) | **a STAGING graph rendered as ghost/candidate nodes**, separate from canonical | **diverge — the core BLEND** |
| Concurrency guard | canvas **read-only while streaming** (`isCanvasReadOnly`) + single undo bracket | canonical canvas read-only during a turn; canonical write-locked behind Apply (§7.12) | **diverge** (PULSE strictly stronger) |
| Review / accept | **post-hoc**: review changes already applied to the live canvas; undo to revert | **pre-commit**: the diff IS the Plan Preview; Apply commits, reject discards staging | **diverge — the inversion** |
| Commit / log | mutates workflow store, then saves the workflow | `apply_plan` writes canonical + one Command-Log entry per op, atomically | **diverge** (PULSE adds the Command Log) |
| Abort | `res.on('close', () => abortController.abort())`; graceful | `emitterDead` → stop loop, discard staging, "aborted" msg, no throw | **converge** |
| History compaction | `messages-compacted` chunk; frontend clears old msgs | `messages_compacted` SSE event (new) | **converge** |

**The headline:** PULSE matches n8n's **shape** at the framing, chunk-catalog, typed-op, coarse-snapshot,
and client-reconcile layers (converge), and **deliberately diverges at exactly one seam** — n8n live-mutates
the real canvas with only a read-only lock + undo bracket; PULSE interposes a **STAGING graph** so the
diff becomes a **pre-commit Plan Preview** and the canonical graph is **write-locked behind Apply**
(true no-silent-write). Everything else is n8n's real implementation re-expressed in PULSE's vocabulary.
