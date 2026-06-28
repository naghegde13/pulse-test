# 06 — OPERATIONS-QUEUE + ATOMIC-APPLY + DIFF (contract fragment)

> Author #3 fragment for `SPEC-ui-composition.md` §7 (the Chat contract). This fragment specifies the
> three coupled mechanisms — the **typed-operation queue**, the **single atomic Apply**, and the
> **canonical-vs-staging DIFF** — at the contract level, **grounded in the RAW n8n implementation** of the
> same three mechanisms. n8n is source-available under the Sustainable Use License: this fragment **reads its
> source for shape and patterns, then re-expresses them in PULSE's stack** (Java/Spring, `@xyflow/react`,
> the op-composition / Blueprint / dataset-port / Command-Log model, Plan→Apply). It is **adapt-the-shape,
> NOT copy-the-code**.
>
> **`[read]`** tags a fact read verbatim from the cited n8n source path/URL **or** a LOCKED PULSE source
> (an ADR, `SPEC-ui-composition.md` §7, or the live codebase at a cited `file:line`). Everything PULSE-specific
> that this author had to invent rather than read is flagged **`> GUESS:`** — a concrete default for the
> building agent, NOT yet operator-agreed, to be resolved at the SPEC-GATE.
>
> EARS phrasing ("WHEN <trigger> THE SYSTEM SHALL <response>") is used for the binding/normative sentences.
>
> **Defined vocab (verbatim):** Customer · op · op-list · params · Blueprint · dataset · port · Command Log ·
> Plan Preview · Apply Plan · **CANONICAL graph** (the persisted composition: `sub_pipeline_instances` +
> `port_wirings` for the active version) · **STAGING graph** (the candidate composition a turn builds, never
> persisted until Apply).

---

## A. The n8n source that grounds this fragment (READ FIRST)

All paths are under `github.com/n8n-io/n8n`, package `packages/@n8n/ai-workflow-builder.ee/` unless noted as the
shared `packages/workflow/` package. Read these BEFORE writing the PULSE analogue.

| n8n mechanism | n8n source path | URL `[read]` |
|---|---|---|
| `WorkflowOperation` union + `SimpleWorkflow` | `…/ai-workflow-builder.ee/src/types/workflow.ts` | `[read]` https://github.com/n8n-io/n8n/blob/master/packages/%40n8n/ai-workflow-builder.ee/src/types/workflow.ts |
| Operations **queue** + `operationsReducer` (LangGraph state) | `…/ai-workflow-builder.ee/src/workflow-state.ts` | `[read]` https://github.com/n8n-io/n8n/blob/master/packages/%40n8n/ai-workflow-builder.ee/src/workflow-state.ts |
| **Atomic apply** — `applyOperations` + `processOperations` (queue drain) | `…/ai-workflow-builder.ee/src/utils/operations-processor.ts` | `[read]` https://github.com/n8n-io/n8n/blob/master/packages/%40n8n/ai-workflow-builder.ee/src/utils/operations-processor.ts |
| Tools emit ops, never mutate; read-tool sees pending ops (`getEffectiveWorkflow`) | `…/ai-workflow-builder.ee/src/tools/helpers/state.ts` | `[read]` https://github.com/n8n-io/n8n/blob/master/packages/%40n8n/ai-workflow-builder.ee/src/tools/helpers/state.ts |
| The `process_operations` graph node wired into the build loop | `…/ai-workflow-builder.ee/src/subgraphs/builder.subgraph.ts` | `[read]` https://github.com/n8n-io/n8n/blob/master/packages/%40n8n/ai-workflow-builder.ee/src/subgraphs/builder.subgraph.ts |
| **`compareWorkflowsNodes`** diff + `NodeDiffStatus` + `DiffableNode` + superset rules | `packages/workflow/src/workflow-diff.ts` (shared `n8n-workflow` pkg) | `[read]` https://github.com/n8n-io/n8n/blob/master/packages/workflow/src/workflow-diff.ts |
| Connection-level diff | `packages/workflow/src/connections-diff.ts` | `[read]` https://github.com/n8n-io/n8n/blob/master/packages/workflow/src/connections-diff.ts |
| Builder "Review N changes" using the diff (frontend) | `…/editor-ui/src/…/nodeChanges` (commit f7c3684) | `[read]` https://github.com/n8n-io/n8n/commit/f7c36840fb112039ae54423c40f1976dc6cb1f19 |
| Design narrative: "operations queue ⇒ atomic state updates" | PR #17423 "AI Workflow Builder core" | `[read]` https://github.com/n8n-io/n8n/pull/17423 |

Cross-reference: the same facts were catalogued in the prior n8n summary doc (`N8N-AI-BUILDER-ARCHITECTURE.md`,
since removed): §2.2 (the
union), §2.3 (tool→op mechanism + reducer), §4.2 (`compareWorkflowsNodes`), §4.3 (presenting + applying), §5.1
(in-flight state), §5.2 (client reconcile), §8.2 (the timing inversion). This fragment quotes the **raw source**
those sections summarize.

---

## B. RAW n8n — the operations queue (verbatim)

### B.1 The typed-operation union `[read types/workflow.ts]`

n8n tools never touch the graph — they return a typed `WorkflowOperation`. The **complete** discriminated union,
quoted verbatim:

```ts
// packages/@n8n/ai-workflow-builder.ee/src/types/workflow.ts  [read]
export type SimpleWorkflow = Pick<IWorkflowBase, 'name' | 'nodes' | 'connections'>;

export type WorkflowOperation =
  | { type: 'clear' }
  | { type: 'removeNode'; nodeIds: string[] }
  | { type: 'addNodes'; nodes: INode[] }
  | { type: 'updateNode'; nodeId: string; updates: Partial<INode> }
  | { type: 'setConnections'; connections: IConnections }
  | { type: 'mergeConnections'; connections: IConnections }
  | { type: 'removeConnection';
      sourceNode: string; targetNode: string; connectionType: string;
      sourceOutputIndex: number; targetInputIndex: number }
  | { type: 'setName'; name: string }
  | { type: 'renameNode'; nodeId: string; oldName: string; newName: string };
```

Nine variants. Note `mergeConnections` (additive, dedup'd) vs `setConnections` (replace the whole set) — both real.
Connections are keyed **by source node *name***, not id (`connections[sourceName][connectionType][outputIndex] =
[{ node: targetName, type, index }]`) `[read types/workflow.ts; operations-processor.ts connection-by-name handling]`.

### B.2 The queue + reducer `[read workflow-state.ts]`

The queue is a **LangGraph state annotation** with a custom **append/clear reducer**, quoted verbatim:

```ts
// packages/@n8n/ai-workflow-builder.ee/src/workflow-state.ts  [read]
function operationsReducer(
  current: WorkflowOperation[] | null,
  update: WorkflowOperation[] | null | undefined,
): WorkflowOperation[] {
  if (update === null) return [];                       // null => reset/clear the queue
  if (!update || update.length === 0) return current ?? [];
  if (update.some((op) => op.type === 'clear')) {       // a 'clear' op wipes everything
    return update.filter((op) => op.type === 'clear').slice(-1); // keep only the last clear
  }
  if (!current && !update) return [];
  return [...(current ?? []), ...update];               // otherwise APPEND
}

// ...
workflowJSON: Annotation<SimpleWorkflow>({               // the graph blob; updated ONLY via ops
  reducer: (x, y) => y ?? x,
  default: () => ({ nodes: [], connections: {}, name: '' }),
}),
workflowOperations: Annotation<WorkflowOperation[] | null>({   // the QUEUE
  reducer: operationsReducer,
  default: () => [],
}),
workflowValidation: Annotation<ProgrammaticEvaluationResult | null>({  // derived; invalidated on apply
  reducer: (x, y) => (y === undefined ? x : y),
  default: () => null,
}),
```

The comment in the source is load-bearing: *"Now a simple field without custom reducer — all updates go through
operations"* `[read workflow-state.ts]`. The graph blob is never mutated directly; every change is an op.

### B.3 A read-tool mid-turn sees PENDING ops `[read tools/helpers/state.ts]`

Because ops accumulate but apply only at the end of the superstep, n8n gives read tools a view that *includes*
the queued-but-unapplied ops, so a multi-tool turn stays self-consistent:

```ts
// packages/@n8n/ai-workflow-builder.ee/src/tools/helpers/state.ts  [read]
/**
 * Within a single agent turn, tools queue operations that are applied after all tools complete.
 * This means read tools would see stale state if they just read workflowJSON directly.
 * This function applies any pending operations to return the "effective" current state,
 * so read tools see the result of writes made earlier in the same turn.
 */
export function getEffectiveWorkflow(): SimpleWorkflow {
  const state = getWorkflowState();
  const pending = state.workflowOperations;
  if (!pending || pending.length === 0) return state.workflowJSON;
  return applyOperations(structuredClone(state.workflowJSON), pending);  // clone, never mutate
}
```

And the state-update helpers a mutation tool returns are just ops — e.g. `updateWorkflowConnections` returns
`{ workflowOperations: [{ type: 'mergeConnections', connections }] }`; `updateNodeInWorkflow` returns
`{ workflowOperations: [{ type: 'updateNode', nodeId, updates }] }` `[read tools/helpers/state.ts]`.

---

## C. RAW n8n — the atomic apply (verbatim)

`applyOperations` is a pure fold over the queue; `processOperations` is the LangGraph node that drains the queue
and clears it. Quoted verbatim `[read operations-processor.ts]`:

```ts
// packages/@n8n/ai-workflow-builder.ee/src/utils/operations-processor.ts  [read]
type OperationHandler = (workflow: SimpleWorkflow, operation: WorkflowOperation) => SimpleWorkflow;

const operationHandlers: Record<WorkflowOperation['type'], OperationHandler> = {
  clear: applyClearOperation,
  removeNode: applyRemoveNodeOperation,
  addNodes: applyAddNodesOperation,
  updateNode: applyUpdateNodeOperation,
  setConnections: applySetConnectionsOperation,
  mergeConnections: applyMergeConnectionsOperation,
  removeConnection: applyRemoveConnectionOperation,
  setName: applySetNameOperation,
  renameNode: applyRenameNodeOperation,
};

export function applyOperations(
  workflow: SimpleWorkflow,
  operations: WorkflowOperation[],
): SimpleWorkflow {
  let result: SimpleWorkflow = {                  // start from a COPY (never mutate the input)
    nodes: [...workflow.nodes],
    connections: { ...workflow.connections },
    name: workflow.name || '',
  };
  for (const operation of operations) {           // apply each op IN SEQUENCE
    const handler = operationHandlers[operation.type];
    result = handler(result, operation);
  }
  return result;
}

export function processOperations(state: {
  workflowJSON: SimpleWorkflow;
  workflowOperations?: WorkflowOperation[] | null;
}) {
  const { workflowJSON, workflowOperations } = state;
  if (!workflowOperations || workflowOperations.length === 0) return {};   // nothing queued
  const newWorkflow = applyOperations(workflowJSON, workflowOperations);
  return {
    workflowJSON: newWorkflow,        // the new graph
    workflowOperations: null,         // CLEAR the queue (reducer treats null as reset)
    workflowValidation: null,         // INVALIDATE stale validation
  };
}
```

The **atomic semantics that matter** (all `[read]` from the verbatim source above): (1) the queue is applied as a
**single fold** in op order — all-or-nothing per superstep; (2) `applyOperations` works on a **copy**, never the
live blob; (3) on success the queue is **reset to `null`** and the derived `workflowValidation` is **invalidated**.
The handlers themselves are immutable spreads — e.g. `applyAddNodesOperation` rebuilds a node map and returns
`{ ...workflow, nodes }`; `applyClearOperation` returns `{ nodes: [], connections: {}, name: '' }`;
`applyRenameNodeOperation` constructs a throwaway `Workflow` instance, calls `renameNode`, and returns the rebuilt
nodes/connections `[read operations-processor.ts]`.

This node is wired into the build loop as `process_operations`, **between** `tools` and the next `agent` step
(`tools → process_operations → agent`, looping back) `[read builder.subgraph.ts: .addNode('process_operations',
processOperations).addEdge('tools','process_operations').addEdge('process_operations','agent')]`. PR #17423's own
words: *"Tools return operations instead of directly mutating state · Operations accumulate from parallel tool
execution · Processing applies operations atomically in correct order · Enables future undo/redo capabilities"*
`[read PR #17423]`.

> Divergence noted up front: n8n's `processOperations` writes the **live** `workflowJSON` after every superstep
> (its graph IS the working copy). PULSE inverts this — `process_operations` writes the **STAGING** graph only;
> the **canonical** graph is write-locked behind `apply_plan` (§E, and SPEC §7.9/§7.10). This is the deliberate
> PULSE strengthening of n8n's pattern, dispositioned in SPEC §8.2.

---

## D. RAW n8n — the `compareWorkflowsNodes` DIFF (verbatim)

The diff lives in the **shared** `n8n-workflow` package, not the builder. It is the engine behind both the
"Review N changes" badge and the canvas diff highlighting. Quoted verbatim `[read packages/workflow/src/workflow-diff.ts]`:

```ts
// packages/workflow/src/workflow-diff.ts  [read]
export type DiffableNode = Pick<INode, 'id' | 'parameters' | 'name'>;

export const enum NodeDiffStatus {
  Eq       = 'equal',
  Modified = 'modified',
  Added    = 'added',
  Deleted  = 'deleted',
}
export type NodeDiff<T>     = { status: NodeDiffStatus; node: T };
export type WorkflowDiff<T> = Map<string, NodeDiff<T>>;   // keyed by node id

// content-based equality over a FIXED field projection:
export function compareNodes<T extends DiffableNode>(
  base: T | undefined,
  target: T | undefined,
): boolean {
  const propsToCompare = ['name', 'type', 'typeVersion', 'webhookId', 'credentials', 'parameters'];
  const baseNode   = pick(base, propsToCompare);
  const targetNode = pick(target, propsToCompare);
  return isEqual(baseNode, targetNode);     // lodash deep-equal of the picked projection
}

export function compareWorkflowsNodes<T extends DiffableNode>(
  base: T[],
  target: T[],
  nodesEqual: (base: T | undefined, target: T | undefined) => boolean = compareNodes,
): WorkflowDiff<T> {
  const baseNodes   = base.reduce((acc, n) => acc.set(n.id, n), new Map<string, T>());
  const targetNodes = target.reduce((acc, n) => acc.set(n.id, n), new Map<string, T>());

  const diff: WorkflowDiff<T> = new Map();
  for (const [id, node] of baseNodes.entries()) {
    if (!targetNodes.has(id)) {
      diff.set(id, { status: NodeDiffStatus.Deleted, node });            // in base, not in target
    } else if (!nodesEqual(baseNodes.get(id), targetNodes.get(id))) {
      diff.set(id, { status: NodeDiffStatus.Modified, node });          // in both, content differs
    } else {
      diff.set(id, { status: NodeDiffStatus.Eq, node });                // identical
    }
  }
  for (const [id, node] of targetNodes.entries()) {
    if (!baseNodes.has(id)) {
      diff.set(id, { status: NodeDiffStatus.Added, node });             // in target, not in base
    }
  }
  return diff;
}
```

**The diff result SHAPE** (the thing the building agent must reproduce): a `Map<nodeId, { status, node }>` where
`status ∈ {equal, modified, added, deleted}`. Equality is **content-based** over the fixed projection
`['name','type','typeVersion','webhookId','credentials','parameters']` — NOT positions, NOT ids beyond the keying.
The `nodesEqual` comparator is **injectable** (default `compareNodes`); n8n swaps in a superset-aware comparator
for additive-merge detection.

**Additive-change rules** (used to decide a "modification" is loss-free) `[read workflow-diff.ts]`:
`stringContainsParts(s, substr)` (all chars of `substr` appear in order within `s`); `parametersAreSuperset(prev,
next)` (recursive — strings must be *contained*, arrays/objects must match key-for-key and recurse);
`nodeIsSuperset` (non-param props equal via `compareNodes` with `parameters:{}` stripped, then params checked by
`parametersAreSuperset`); `mergeAdditiveChanges` (merge allowed iff no node deleted, every modified node remains a
superset, no connection removed).

**Connection diff is separate** `[read packages/workflow/src/connections-diff.ts]`: `compareConnections(prev, next)
→ { added, removed }` — a per-source-node, per-input, per-source-index set-diff (JSON-stringified connections into
maps, then diff). Node diff and connection diff are computed independently and combined by `WorkflowChangeSet`.

**Real call-site** (frontend "Review N changes"), verbatim shape `[read commit f7c3684]`:
```ts
const normalized = resolveNodeDefaults(cachedVersionNodes.value);     // the snapshot (base)
const diff = compareWorkflowsNodes(normalized, currentNodes);         // current (target)
return [...diff.values()].filter((d) => d.status !== NodeDiffStatus.Eq).length;   // the "N" badge
```

**Timing (the crux):** n8n runs `compareWorkflowsNodes(snapshot, current)` **POST-mutation** — the diff is an
*audit* of changes already applied to the live graph (SPEC §8.2 / the removed n8n summary doc §4.2, §8.2; the underlying source is `compareWorkflowsNodes` in the raw n8n tree). PULSE
**inverts** the timing: the diff is computed **PRE-commit**, canonical-vs-staging, and IS the Plan Preview (§F).

---

## E. PULSE CONTRACT — operations queue → plannedCommands

PULSE's analogue of `WorkflowOperation` is the **`PlanOperation`** union (already specified in SPEC §7.4 — this
fragment grounds it against the raw n8n source and does not redefine it). The mapping is one-to-one in *shape*,
re-expressed in PULSE field names (instances + typed-port wiring, not n8n nodes/connections):

| n8n op `[read]` | PULSE `PlanOperation.op` (SPEC §7.4) | Note |
|---|---|---|
| `addNodes` | `addInstances` | add Blueprint instance(s); carries `reasoning` (the n8n analogue is `initialParametersReasoning`) |
| `removeNode` | `removeInstance` | delete step + its wirings |
| `updateNode` | `updateInstance` | params / lakeLayer / lakeFormat / storageBackend |
| `setConnections` | `setWiring` | replace the whole wiring set (Modify-rebuild, pipeline-create) |
| `mergeConnections` | `mergeWiring` | **additive, dedup'd** — the default for `wire_ports` |
| `removeConnection` | `removeWiring` | one edge |
| `setName` | `setName` | rename the pipeline |
| `renameNode` | `rename` | rename a step + fix references |
| `clear` | `clear` | reducer control only |
| *(n8n has none)* | `setPipelineSetting` | **PULSE-specific** — portless orchestration-policy ops (ADR 0020/0021) |

> GUESS: PULSE wiring keys ops by **`instanceRef` = instance NAME** (n8n keys connections by node *name* too,
> `[read types/workflow.ts]`); at apply, refs resolve to ids (the n8n draft-ref pattern; live
> `Plan.draftRefDeclarations`/`draftRefBindings`, `Plan.java:75-80`). SPEC §7.4 GUESS owns the final
> refs-by-name-vs-ids decision — repeated here only as the grounding anchor.

**The queue + reducer + single atomic apply (PULSE).**

WHEN a mutation tool runs THE SYSTEM SHALL append its single typed `PlanOperation` to a **per-turn op-queue** —
a state field with an **append/clear reducer** mirroring n8n's `operationsReducer` `[read workflow-state.ts]`: an
incoming op is appended; `{op:'clear'}` empties the queue; a `null`/empty update is a no-op.

WHEN several mutation tools run inside one composer superstep THE SYSTEM SHALL **accumulate** their ops in the
queue (n8n's parallel-tool accumulation, `[read PR #17423]`) and SHALL NOT apply any op until the superstep settles.

WHEN a read tool runs MID-TURN after ops are queued THE SYSTEM SHALL return `applyOps(clone(canonical),
pendingOps)` — the STAGING view including queued-but-unapplied ops — and SHALL NOT read the canonical graph once
the queue is non-empty (the n8n `getEffectiveWorkflow` invariant, `[read tools/helpers/state.ts]`).

WHEN a composer superstep settles THE SYSTEM SHALL run a single **`process_operations`** step that drains the
queue and applies ALL ops as **one immutable fold** (n8n's `applyOperations`, `[read operations-processor.ts]`)
to the **STAGING graph** — never the canonical graph — then reset the queue to empty and **invalidate** any cached
`validate_plan` result (n8n invalidates `workflowValidation`, `[read operations-processor.ts]`). It SHALL then emit
ONE `candidate_graph` event carrying the full STAGING composition (SPEC §7.5).

A mutation tool SHALL NEVER write `sub_pipeline_instances` / `port_wirings` directly and SHALL NEVER write the
Command Log — it only enqueues. This is the n8n "tool emits, never mutates" rule `[read tools/helpers/state.ts;
PR #17423]` on PULSE's ARCH-009 contract.

> GUESS: PULSE applies ops to a **clone of the CANONICAL snapshot** (STAGING = clone of canonical at turn start,
> §SPEC 7.8), whereas n8n's `applyOperations` folds onto the live `workflowJSON`. The fold *algorithm* is the same;
> the *base graph* differs by design (staging clone vs live). The op-handler bodies (immutable spreads, additive
> `mergeWiring` with set-dedup keyed on `{source,sourcePort,target,targetPort}`, the `clear` reset) are PULSE
> reimplementations of the n8n handlers `[read operations-processor.ts]` in Java/Spring — operator/SPEC-GATE to
> confirm the dedup key for `mergeWiring` matches n8n's `{node,type,index}` analogue.

---

## F. PULSE CONTRACT — the single atomic Apply (one turn = one Command-Log transaction)

PULSE's atomic apply is **`apply_plan(plan_id)`** — the sole generic mutator (ARCH-009; SPEC §7.0, §7.9). It is
n8n's `processOperations` **moved behind an approval gate** and extended to write the Command Log atomically.

WHEN the Customer clicks **Apply Plan** THE SYSTEM SHALL run `apply_plan(plan_id)` as a **single transaction**:
(a) re-validate the plan is `APPROVED` and same-session (`[read] ChatTools.java:368-376`); (b) run `validate_plan`
(SPEC §7.13 layer 3 — the deterministic Builder pre-flight); if it does not compile, REJECT with the blockers and
mutate NOTHING; (c) resolve `instanceRef`s → real instance ids (draft-ref binding); (d) **in one DB transaction**
write the canonical graph (`sub_pipeline_instances` / `port_wirings`) AND one Command-Log row per applied op
(SPEC §7.4 mapping table); (e) emit a `tool_result` with `mutationApplied=true`, `planId`, `commandIds`,
`refreshHints`; (f) promote the STAGING ghosts to real canvas nodes.

WHEN `apply_plan` commits THE SYSTEM SHALL set, on every Command-Log row, the **same `planId`** (so one chat turn =
one Command-Log entry-group = one undo unit, SPEC §7.11) and `idempotencyKey = tenantId + ":" + commandType + ":" +
aggregateId + ":" + UUID` (`[read] CommandService.java:34`). Re-applying the SAME approved plan SHALL be a no-op on
already-executed commands (idempotent).

WHEN `apply_plan` is invoked for a non-`APPROVED` plan, a plan from a different session, or a plan whose
`validate_plan` fails THE SYSTEM SHALL reject **without side effects** (`[read] ChatTools.java:368-376`).

n8n→PULSE divergence (deliberate): n8n's atomic unit is **the queue-apply to the live blob** (every superstep is a
durable write); PULSE's atomic unit is **the Apply transaction** (only the gated Apply is durable; supersteps write
only the ephemeral STAGING graph). n8n gets "future undo/redo" `[read PR #17423]`; PULSE gets it *for free* because
one turn = one `planId` Command-Log group, and "undo" = **snapshot-restore** (restore the per-turn checkpoint; NOT an
inverse plan — §7.16 #13 / ADR 0025 §3, via the `langgraph4j-postgres-saver` checkpointer; SPEC §7.8, §7.11).

> GUESS: the `composition.*` command types (`composition.addInstance` / `removeInstance` / `updateInstance` /
> `wirePorts` / `removeWiring` / `renameInstance`) do NOT exist today — composition mutations currently bypass the
> Command Log (`[read]` SPEC §7.0 grounding ledger; live `ChatToolExecutor` ~`:2230-2260` calls `CompositionService`
> directly). This fragment REQUIRES registering the six `composition.*` handlers on `CommandService` so the atomic
> Apply writes them as first-class, idempotent, Command-Logged commands. Owner of the final strings: SPEC §7.4 GUESS.

---

## G. PULSE CONTRACT — the canonical-vs-staging DIFF (the `compareWorkflowsNodes` analogue)

PULSE's diff is the engine behind both the live ghost rendering and the Plan Preview banner (SPEC §7.6, §7.7). It
is n8n's `compareWorkflowsNodes` `[read workflow-diff.ts]` **re-expressed for the PULSE graph and run PRE-commit**.

```ts
// PULSE compareGraphs — the compareWorkflowsNodes analogue. Keyed by instanceRef (the stable
// pre-apply handle, since staged instances have no id yet — n8n keys its diff by node id, but
// connections by name; PULSE uses the ref end-to-end for the diff).
compareGraphs(canonical: CompositionView, staging: CompositionView):
  { instances: Map<instanceRef, { status: 'equal'|'modified'|'added'|'deleted'; instance }>,
    wirings:   Array<{ status: 'added'|'deleted'|'equal'; wire }> }
```

WHEN the client (or backend) holds a STAGING snapshot THE SYSTEM SHALL compute `compareGraphs(canonical, staging)`
with the SAME four-status logic as n8n `[read workflow-diff.ts]`: an instanceRef in canonical but not staging =
`deleted`; in both but content-differs = `modified`; identical = `equal`; in staging but not canonical = `added`.

**Instance equality is content-based** over a fixed projection — the PULSE analogue of n8n's
`['name','type','typeVersion','webhookId','credentials','parameters']` `[read workflow-diff.ts]`:

> GUESS: PULSE's projection = `{ name, blueprintKey, blueprintVersion, params, lakeLayer, lakeFormat,
> storageBackend }` (the n8n `name`/`type`/`typeVersion`/`parameters` analogues; PULSE has no `credentials`/
> `webhookId` — secrets are SecretRefs, ADR 0023, so they are NOT in the diff projection). **Open (SPEC §7.7 GUESS
> owns this):** do `dqExpectations` and `executionOrder` count toward `modified`, or are they excluded to avoid
> noisy diffs? Additive-param detection MAY reuse the n8n `parametersAreSuperset` rule `[read workflow-diff.ts]` so
> a param-add reads as a loss-free modification.

WHEN the diff is computed THE SYSTEM SHALL (mirroring n8n's frontend `[read commit f7c3684]`):
1. render each non-`equal` instance with a **status-colored ghost border** (added / modified / removed) on
   `@xyflow/react` — n8n's diff-class approach on its `@vue-flow` canvas;
2. drive the **"Review N changes" banner**, where `N = count(added) + count(modified) + count(deleted) +
   changedWires` (n8n's `[...diff.values()].filter(d => d.status !== Eq).length`, `[read commit f7c3684]`);
3. make that banner **the Plan Preview** — clicking it opens the before/after view and surfaces the per-op
   `reasoning` (SPEC §7.3 B, §7.7).

**Wiring diff is a parallel set-diff** over `{sourceRef, sourcePort, targetRef, targetPort}` (the PULSE analogue of
n8n's separate `compareConnections` `[read connections-diff.ts]`). Node diff and wiring diff are computed
independently and combined for the banner count — exactly n8n's `WorkflowChangeSet` split `[read workflow-diff.ts]`.

**The timing inversion (the load-bearing divergence).** WHEN the diff is computed THE SYSTEM SHALL compute it
**PRE-commit** — the diff IS the plan, shown BEFORE Apply — inverting n8n's POST-mutation audit (SPEC §8.2;
the removed n8n summary doc §4.2, §8.2 — grounded in the raw n8n source). The diff *machinery* (snapshot + 4-status content-diff + ghost
highlight + the "N changes" count) is adopted verbatim in shape; only the *timing* moves from after-the-write to
before-the-write, which is what makes the diff a **gate** rather than an **audit**.

---

## H. n8n → PULSE mapping (and where PULSE diverges)

| Concern | n8n (raw source) `[read]` | PULSE contract | Same shape? |
|---|---|---|---|
| Mutation unit | typed `WorkflowOperation` union (9 variants) | typed `PlanOperation` union (§E) | **Same** — 1:1 + `setPipelineSetting` added |
| Queue | `workflowOperations` state field + `operationsReducer` (append / null-resets / `clear`) `[read workflow-state.ts]` | per-turn op-queue, same append/clear reducer (§E) | **Same** |
| Mid-turn read | `getEffectiveWorkflow` = `applyOperations(clone(json), pending)` `[read state.ts]` | read tools see `applyOps(clone(canonical), pending)` (§E) | **Same** |
| Atomic apply | `processOperations` folds queue → live `workflowJSON`, clears queue, invalidates validation `[read operations-processor.ts]` | `process_operations` folds → **STAGING**; `apply_plan` folds → **canonical + Command Log** in one txn (§F) | **Divergent timing** — split into staging-apply + gated canonical-apply |
| Durable write boundary | every superstep writes the live graph | **only `apply_plan` writes canonical** (write-lock behind approval) | **Divergent** — PULSE adds the Plan→Apply gate (ARCH-009) |
| Diff | `compareWorkflowsNodes(base,target) → Map<id,{status,node}>`, 4 statuses, content-projection equality `[read workflow-diff.ts]` | `compareGraphs(canonical,staging)`, same 4 statuses, content-projection equality (§G) | **Same machinery** |
| Diff timing | **POST-mutation audit** ("Review N changes" after writes) | **PRE-commit gate** (the diff IS the Plan Preview) | **Divergent timing** — inverted (SPEC §8.2) |
| Connection diff | separate `compareConnections → {added,removed}` `[read connections-diff.ts]` | separate wiring set-diff on `{sourceRef,sourcePort,targetRef,targetPort}` (§G) | **Same** |
| Undo | "enables future undo/redo" via op log `[read PR #17423]` | one turn = one `planId` Command-Log group = one undo unit (SPEC §7.11) | **Same intent**, PULSE realizes it via the Command Log |
| Secrets in diff/ops | `credentials` is a diffed field `[read workflow-diff.ts]` | **no secrets** — SecretRefs only, excluded from the diff projection (ADR 0023) | **Divergent** — by design |

**Net:** PULSE adopts n8n's three mechanisms **verbatim in shape** — the typed-op union, the append/clear queue +
single-fold apply, and the 4-status content-diff — and makes exactly **two deliberate inversions**: (1) the apply
fold targets the STAGING graph, with a second gated fold (`apply_plan`) being the only writer of the canonical
graph + Command Log; (2) the diff is computed PRE-commit so it serves as the Plan Preview gate rather than n8n's
post-mutation audit. Both inversions are the same decision — **move the durable write behind the Plan→Apply gate** —
and are dispositioned in SPEC §8.2 / §7.9.
