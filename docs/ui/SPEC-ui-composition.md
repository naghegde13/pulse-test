# SPEC — Pipeline Composition Workspace (the flagship screen)

> Status: DRAFT — UI grill in progress (2026-06-15). The composition screen is a full REWRITE,
> designed to a world-class / "wow" bar. Operator: this is where Customers spend the most time.
> Research basis: the UI design-research passes (Pass 1 canvas/polish + Pass 2 schema/conflict) — the standalone
> `UI-DESIGN-RESEARCH.md` has since been removed; its conclusions are folded into this spec and the `docs/ui/chat-prompts/` fragments.
> Defined vocab: Customer / op / op-list / params / Blueprint; Designer/Builder/Packager/Deployer/Chat.

## Contents (this is the ONE doc to read; the rest is engineer reference)
1. Scope (operator-set)
2. Locked / in-scope so far — layout, schema-visibility model, bespoke panels, conflict overlay, Chat real-time
3. New scope added — to design (generate/package actions, git branching, Chat placement)
4. Open (grill next)
5. Research dispatched
6. Chat architecture — folded in (the blend, op-queue, two tool tiers, streaming, prompting, dump-all, data-eng voice)
7. [Detailed Chat spec (contract-level)](#7-detailed-chat-spec-contract-level) — the agent stages, planning,
   the two tool tiers, the typed PlanOperation union + op-queue, the streaming protocol, liveness + the diff,
   snapshot/revert, the present-then-apply blend, the three state layers, undo, concurrency, 3-layer validation,
   the prompts (system + best-practices + context-injection), and per-page behavior (pipeline-composition deep-dive + other pages)

> Engineer reference (NOT required reading for the operator):
> - The n8n agent-architecture study (layout/canvas/NDV/schema-flow, §7A–7D) — the standalone `N8N-AI-BUILDER-ARCHITECTURE.md`
>   summary has since been removed; the grounded artifacts are the `docs/ui/chat-prompts/` fragments (built directly on the raw n8n source) + ADR 0025.
> - The UI design-research passes — the 13 canvas/polish patterns + the schema-propagation/conflict patterns (standalone `UI-DESIGN-RESEARCH.md` removed; conclusions folded into this spec).

## Scope (operator-set)
This screen is the developer's WHOLE workspace, not just a canvas: design the pipeline → see the
schema flow → generate code (Builder) → package (Packager) → branch / commit / raise PR — with Chat
available throughout, editing the surface in real-time.

## Locked / in-scope so far
- LAYOUT — **LOCKED (operator, 2026-06-15):** far-left = the **app menu** (global nav, unchanged) · center =
  the **canvas** (the star) · right = **CHAT, a SYSTEM-WIDE drawer** (on-demand, consistent on EVERY page —
  Chat is app-wide: domains, SORs, every page) that **SHARES the right region with the step inspector** — the
  inspector takes over when you select a step, returns to Chat on close · **top action bar** = Generate /
  Package / Git (branch·commit·PR) / Save · **blueprint palette OPENS ON DEMAND** as an overlay (NOT a
  persistent left panel) · run logs = bottom, collapsible · + outline + minimap for big pipelines. The step
  inspector = the selected step's **bespoke** config (params + input/output schema + validation + observability,
  + that step's generated code / package / PR as tabs). Chat is NOT on the left (app menu is there).
- ALL 13 Pass-1 patterns incorporated (operator: "all items 1 through 13") — from the UI design-research Pass 1 (standalone doc since removed; patterns folded into this spec).
- BESPOKE node panels generated from the Blueprint construct library (purpose-built controls —
  expression builder, column picker, rename-mapper, condition-builder — never generic forms);
  Inspector tabs: Config · Ports/Schema · Validation · Observability. Adding a Blueprint = its
  metadata + its coded panel (operator-confirmed; easy enough).
- SCHEMA VISIBILITY (the base layer; the operator's priority): schema passes along the wires (each
  node's output = next node's input, computed live by Schema Propagation). Every node shows INPUT
  columns and OUTPUT columns (ADF "Inspect": added/renamed/retyped/dropped highlighted); compact
  "N in → M out" on the node, expandable; the wire represents the schema passing. [confirm pending]
- CONFLICT OVERLAY (Recce/SQLMesh model, on top of schema visibility): on edit, auto-classify
  non-breaking / partial / breaking (computed from how downstream consumes the column); Impact-Radius
  (downstream steps+columns light up, red traceable paths); guided one-click fix with preview;
  Apply gates on severity (non-breaking flows; breaking-with-consumers needs ack/fix) — fits Plan→Apply.
- CHAT REAL-TIME: Chat edits the graph and the surface reflects it LIVE (n8n model: stream mutations
  to canvas + chat + tool-status; "Review N changes" before/after diff; accept/reject) — on PULSE's
  Chat→Plan→Apply gate. Chat ↔ surface is the shared seam with the Chat-system grill.

## New scope added (operator, 2026-06-15) — to design
- GENERATE CODE (Builder) and PACKAGE CODE (Packager) actions live IN the experience (likely a top
  action bar): design → generate → package → deploy.
- GIT BRANCHING for the developer: a NEW BRANCH per revision (revision numbers track via branches);
  COMMIT; raise a PR with a DEVELOPER-SPECIFIED target branch. Ties to PULSE's Git model (PR-only;
  PR at GenerateArtifacts; merge → tag pipeline/<id>/vX.Y.Z).
- CHAT PLACEMENT in the layout — open question (instinct: a dockable panel, right or bottom).

## Open (grill next)
- CONFIRMED 2026-06-15: canvas-hero three-region (accepted) + the schema-visibility model (input AND
  output columns visible per node, flowing along the wires, conflict-detection as an overlay).
- LAYOUT lock — where Chat, the git actions, and generate/package sit. HELD pending the n8n layout study (running).
- ACTIVE: git-branching / revision model (independent of layout) — branch-per-revision, commit, PR to a
  developer-chosen target branch, version bump on merge.

## Research dispatched
- n8n layout + AI-builder precise study (for reimplementation; license = reimplement patterns, not copy code).
- n8n chat-architecture study → the standalone summary docs have since been removed; the grounded artifacts are the
  `docs/ui/chat-prompts/` fragments (built directly on the raw n8n source) + ADR 0025 (LangGraph4j orchestration).

## Chat architecture — folded in (UX + Chat grilled TOGETHER here, 2026-06-15)
Operator: UX and Chat are inseparable (Chat edits the surface in real-time), so they are specced TOGETHER here,
NOT in a separate session. Chat in PULSE is APP-WIDE (creating domains, SORs/producers, every page) — the bulk is
pipeline composition, but the spec must cover Chat as a global assistant. Reference: the `docs/ui/chat-prompts/` fragments (grounded in the raw n8n source) + ADR 0025.

Decisions / locked-ish:
- CORRECTION (operator): n8n PLANS *and* live-mutates — separate concerns; the study's TL;DR conflated them.
  PULSE answer = **plan-first with live ghost-staging** (n8n study §8.2): the agent streams typed ops into a
  STAGING graph rendered as ghost/candidate nodes (watch it build live); the DIFF is the Plan Preview; Apply
  commits to the canonical graph + Command Log; reject discards the staging graph (true no-silent-write).
  Resolves the earlier apply-live-vs-staged fork = the BLEND.
- Op-queue + atomic apply (like, §2.2-2.3): chat tools emit typed PlanOperations into a queue; one apply step
  drains it atomically; never mutate canonical directly (fits PULSE's Command Log; one turn = one command entry).
- Two tool tiers (like, §2.1): read-only/discovery (blueprint-search, blueprint-schema, validate, get-context)
  + mutation (`add_blueprint_instance`, `wire_ports`, `set_params`, `remove_instance`).
- Streaming full candidate_graph snapshots + client-side diff/reconcile (like, §3.4); positions preserved; auto-layout after.
- Review/diff machinery (like, §4.3): compareGraphs → added/modified/removed; ghost borders on canvas; click-to-focus;
  the count drives the Plan Preview banner.
- Prompting strategy (love, §6): system prompt + catalog grounding + best-practices injection + tool-descriptions-
  carry-the-contract + 3-layer validation (Zod/JSON-schema + semantic port-type compat + validate-plan runs the
  deterministic Builder's preflight) + errors-as-observations retry. BUT in DATA-ENGINEERING language (SCD2, medallion,
  dataset contracts), not n8n's general-automation language.
- SKIP n8n's multi-agent subgraph complexity (operator: "no subgraphs"); n8n itself trims it (PR #27925).
- CATALOG GROUNDING — **RESOLVED (operator agreed, 2026-06-15): DUMP ALL ~50 blueprints** into the **cached**
  system prompt (tight entries: name + intent + ports + key params). Prompt-caching keeps it cheap after turn one;
  full catalog awareness, no retrieval step, simpler agent. Fallback if tokens bloat: hybrid (always inject the
  50 summaries + fetch a blueprint's full schema on demand).
- TODO at spec-write: extract n8n's ACTUAL system prompts (study couldn't — file moved/404; may be in repo now).
- The layout-reference's PULSE-mapping recs were partly wrong (operator) — defer to the raw n8n source (the §8 material, now grounded in the `docs/ui/chat-prompts/` fragments) as authoritative.

---

## 7. Detailed Chat spec (contract-level)

> Status: contract-level draft for the SPEC-GATE (ADR 0010 zero-fuzziness bar). **Adapts** the n8n architecture
> (the standalone `N8N-AI-BUILDER-ARCHITECTURE.md` summary §8 has since been removed; the grounded source is the
> `docs/ui/chat-prompts/` fragments built on the raw n8n tree) into PULSE's model — op-composition,
> Blueprints, datasets, typed ports, the deterministic Builder, the Command Log, Plan→Apply, and the staging-graph
> blend. It does **not** copy n8n code. EARS phrasing ("WHEN <trigger> THE SYSTEM SHALL <response>") is used for the
> binding contract sentences. Sections 1–6 above are LOCKED inputs and are applied here, not re-decided.
>
> **IMPLEMENTATION SHAPE — read n8n's source, match its shape (operator directive 2026-06-15).** The building
> agent MUST first read **how n8n actually wrote their code** — the LangGraph 5-agent builder (Supervisor /
> Discovery / Builder / Planner / Responder), the operations-queue + atomic-apply, the chunked json-lines SSE
> streaming, the `compareWorkflowsNodes` diff, snapshot/revert, and the tool contracts — via
> the `docs/ui/chat-prompts/` fragments (which read and cite the raw n8n source directly; the prior standalone
> `N8N-AI-BUILDER-ARCHITECTURE.md` / `N8N-PROMPTS-REFERENCE.md` summaries have since been removed) and the n8n
> source paths cited therein. The PULSE implementation SHALL be **heavily influenced by that shape** — the same
> structural decomposition, control flow, and prompt/tool patterns — **re-expressed** in PULSE's stack (Java/Spring
> backend, React Flow `@xyflow/react`, the op-composition / Blueprint / dataset-port / Command-Log model, Plan→Apply,
> Vertex/OpenRouter). This is **adapt-the-shape, NOT copy-the-code**: n8n is source-available under the Sustainable
> Use License — read it for structure and patterns; write PULSE's own implementation in PULSE's voice and vocabulary.
> Where PULSE diverges by design (op-composition vs free node-graph, the deterministic Builder, schema-as-contract,
> SecretRefs), each divergence is a **deliberate, dispositioned** choice in the n8n-capability disposition table
> (run as part of the SPEC-GATE completeness pass), never a silent omission.
>
> **`> GUESS:` lines are the SPEC-GATE's findings to resolve** — each marks a PULSE-specific contract/name/shape this
> author had to invent rather than read from a LOCKED source or the live codebase. They are written (not omitted) so
> the building agent has a concrete default, but they are NOT yet operator-agreed and must be resolved before build.
>
> **Defined vocab (used verbatim throughout):** Customer · op · op-list · params · Blueprint · dataset · port ·
> Command Log · Plan Preview · Apply Plan. Two more terms are fixed here: **CANONICAL graph** = the persisted
> composition (`sub_pipeline_instances` + `port_wirings` for the active version); **STAGING graph** = the candidate
> composition an agent turn builds, never persisted until Apply.

> **AUTHORITATIVE DETAIL LIVES IN THE 7 CHAT-PROMPT FRAGMENTS — §7 is the contract spine, the fragments are the
> sub-specs.** The deep per-area detail of §7 was carried forward into seven fragments under
> `docs/ui/chat-prompts/`. Where a fragment and the §7 body disagree, **the fragment wins** (it is the later, more
> grounded pass); §7 here is reconciled to them and points at them rather than re-inlining their detail. The seven:
>
> | Fragment | Authoritative sub-spec for | Supersedes / grounds |
> |---|---|---|
> | `chat-prompts/01-system-prompts.md` | The per-stage prompts + the **LOCKED 7-stage** model (Router · Discovery · Build/Composer · Configure · Provision · Planner · Responder) — actual prompt TEXT | **Replaces §7.19** (the old 5-prompt draft); reconciles §7.1/§7.14 |
> | `chat-prompts/02-tools-and-context.md` | Tools (read + mutation tiers) + the per-turn context-tag injection wrappers | Grounds §7.3, §7.14 (context tags), §7.17 A |
> | `chat-prompts/03-best-practices.md` | Best-practice + technique guides (per Blueprint category + cross-cutting techniques) | Grounds §7.14 (category guides), §7.18 row 18 |
> | `chat-prompts/04-vertex-provider.md` | The Vertex/Gemini provider seam (per-stage model matrix, thought-signature, caching) | Grounds §7.1 model matrix; §7.14 cache markers |
> | `chat-prompts/05-streaming-canvas-protocol.md` | The streaming-to-canvas SSE protocol (events, liveness, candidate_graph) | Grounds §7.5, §7.6 |
> | `chat-prompts/06-ops-queue-apply-diff.md` | The op-queue, atomic Apply, `composition.*` command types, and the diff | Grounds §7.4, §7.7, §7.9 |
> | `chat-prompts/07-orchestration-revert-layout.md` | Turn orchestration, snapshot/revert, canvas layout/positions | Grounds §7.8, §7.10, §7.11 |
>
> (`docs/ui/chat-prompts/D1-FEEDBACK-CHANGELIST.md` is the revision driver behind the fragments — §F locks the 7-stage
> model; §E carries the final decisions reflected below.)

### 7.0 Grounding ledger (what is READ vs GUESSED)

[read] is used where a fact is read from a LOCKED section, an ADR, or the live codebase at a cited path. Everything
else is flagged `> GUESS:`.

- [read] Transport is **SSE** over `POST /api/v1/chat/sessions/{sessionId}/messages` (produces `text/event-stream`);
  current event names are `chunk`, `tool_call`, `tool_result`, `navigate`, `done`, `error`
  (`ChatController.java:76-83`, `ChatService.java:520-738`). The LOCKED text (§2 "Chat real-time", §6) calls this
  "PULSE's `/ws`"; the live code is SSE, not a raw WebSocket. This spec uses the **live SSE channel** and treats
  "`/ws`" in §6 as shorthand for the streaming transport.
- [read] The agent loop is a **single OpenRouter `/chat/completions` tool-loop**, bounded by `MAX_TOOL_ROUNDS = 30`
  (`ChatService.java:36, 546-738`). The LLM is config-driven: `pulse.llm.api-key` / `pulse.llm.base-url`
  (default `https://api.openai.com/v1`, set to OpenRouter in deploy) / `pulse.llm.model` (default `openai/gpt-5.2`) /
  `pulse.llm.reasoning-model` (default `o4-mini`) (`ChatService.java:301-311`).
- [read] Mutation is **Plan→Apply** (ARCH-009): `plan_*` tools persist a PREVIEW `Plan` with `plannedCommands` and write
  NO product state; `apply_plan(plan_id)` is the sole generic mutator and only for an `APPROVED` plan in the same
  session (`ChatTools.java:152-376`, `PulseSystemPrompt.java:140-162`, `Plan.java`, `ToolResult.java`).
- [read] A `plannedCommands` entry is `{type, aggregateType, aggregateId, description, payload, outputs?, uiIntent?}`
  (`PlanService.java:687-702`); a `CommandLog` row is `{planId, commandType, aggregateType, aggregateId, tenantId,
  actorId, idempotencyKey (unique), payload, resultPayload, status, executedAt}` (`CommandLog.java`); the
  `idempotencyKey = tenantId + ":" + commandType + ":" + aggregateId + ":" + UUID` (`CommandService.java:34`).
- [read] Pipeline command types: `pipeline.create` / `pipeline.update` / `pipeline.delete` / `pipeline.transition` /
  `pipeline.createRevision` (`PipelineCommandHandlers.java:19-23`); broker: `broker.remoteInvocation.configure`.
  **Gap [read]:** composition mutations (add-instance / wire_ports / set_params / remove) currently call
  `CompositionService` directly from `ChatToolExecutor` (~`:2230-2260`) and are **NOT** routed through `CommandService`
  / the Command Log as typed command types. This spec REQUIRES them to become first-class command types (§7.4) — see
  the GUESS there for the exact strings.
- [read] The CANONICAL composition shape is `CompositionView{ List<SubPipelineInstance> instances, List<PortWiring> wirings }`
  (`CompositionService.java:372-375`). `SubPipelineInstance` carries `id, pipelineId, versionId, blueprintKey,
  blueprintVersion, name, executionOrder, params, inputDatasets, outputDatasets, outputSchema, dqExpectations,
  schemaStatus, storageBackend, lakeLayer, lakeFormat` (`SubPipelineInstance.java:15-74`). `PortWiring` carries
  `versionId, sourceInstanceId, sourcePortName, targetInstanceId, targetPortName` (`PortWiring.java`).

> RESOLVED (operator 2026-06-16): the stage model is the **LOCKED 7 stages** — Router · Discovery · Build/Composer ·
> Configure · Provision · Planner · Responder (`chat-prompts/01-system-prompts.md` §F; `D1-FEEDBACK-CHANGELIST.md:64-80`).
> (An earlier draft of this spec named only five — router / discovery / composer / planner / responder; that is
> SUPERSEDED. PULSE split `builder` into **Build** [structure] vs **Configure** [values, structured set_params, no
> sub-LLM], added **Provision** [SOR/Domain/Connector/Dataset, the configurator analogue], and folds n8n's `assistant`
> into Responder's `explain`.) The live code is a SINGLE-loop agent (one system prompt, one tool-loop). **Build-shape =
> Option A: the 7 stages are realized as a LangGraph4j `StateGraph` (real graph nodes + supervisor/router + conditional
> edges + an interrupt-equivalent at the plan gate), NOT prompt-modes over the one loop.** This is the keystone
> decision in **ADR 0025**: Option A maps the #3 design ~1:1 onto the n8n reference source it was grounded in
> (`ParentGraphState`→`AgentState`, `interrupt`→`interruptBefore`, `MemorySaver`→`PostgresSaver`), lowering design risk,
> and unlocks durable/resumable session state + the per-stage cost lever (§7.16 #2). Rationale: LangGraph4j ships the
> `StateGraph`/checkpointer/interrupt machinery PULSE would otherwise hand-roll, and PULSE already runs Postgres. The
> graph is built incrementally behind the "first pipeline composed + built end-to-end" milestone (ADR 0025 §5). Option B
> (phase-gated single loop) is the **named fallback** if LangGraph4j is later deemed unfit (ADR 0025 "Considered").

### 7.1 Agent architecture — the stages, the loop, the memory (brief item 1)

**One Chat, app-wide.** WHEN the Customer opens Chat on ANY page (pipeline composition, domains, SORs/producers,
datasets, blueprints, commands) THE SYSTEM SHALL run the SAME agent framework and the SAME session/loop; only the
**active tool set** and the **page-context tags** (§7.14) differ by surface (§7.15). The stages below are shared across
pages.

**The LOCKED 7 stages (logical roles over the one loop).** Each stage = a prompt-assembly mode + a tool allow-list +
an LLM model choice. They run in sequence within a turn; a turn MAY revisit `composer`, `configure`, and `discovery`
repeatedly inside the bounded loop. The actual per-stage prompt TEXT is in `chat-prompts/01-system-prompts.md`
(authoritative); the table below is the contract spine. (An earlier draft named only five — router/discovery/composer/
planner/responder; that is SUPERSEDED by the LOCKED 7, `D1-FEEDBACK-CHANGELIST.md:64-80`. **Build** = structure;
**Configure** = values [structured set_params, no sub-LLM]; **Provision** = SOR/Domain/Connector/Dataset onboarding.)

| Stage | Job (one line) | Tool access | LLM (config key) |
|---|---|---|---|
| **router** | Classify the Customer turn → `{discover, build, configure, provision, explain, plan_decision}` and pick the surface | none (text only) | **cheap tier** `pulse.llm.cheap-model` (Gemini Flash) |
| **discovery** | Establish ground truth: read composition, datasets/SOR, blueprint details, upstream schema; SINGLE judicious-ask (one question only on material, plan-changing ambiguity — §7.1 below, `01` §2) | read-only tier (§7.3 A) | **cheap tier** `pulse.llm.cheap-model` (Gemini Flash) |
| **build / composer** | Sequence the build: emit `add_blueprint_instance` → `wire_ports` → `set_params` ops into the op-queue, each with a "why these params/ports" reasoning field | mutation tier (§7.3 B) — emits ops only, never writes canonical | **reasoning tier** `pulse.llm.model` (Gemini Pro) |
| **configure** | Change a VALUE / orchestration on an EXISTING step — STRUCTURED set_params object, validated deterministically (no parameter-updater sub-LLM; ADR 0013) | mutation tier (§7.3 B), set_params/orchestration only | **cheap tier** `pulse.llm.cheap-model` (Gemini Flash) |
| **provision** | Onboard the HELPER entities a pipeline consumes — Producer/SOR, ServiceInstance, Binding, Domain, Connector, Dataset (+ schema inference); retains `PulseSystemPrompt` onboarding coverage | provision tools (§7.15, §7.17 A) | **cheap tier** `pulse.llm.cheap-model` (Gemini Flash) |
| **planner** | Produce the Plan Preview `{summary, trigger, steps[], additionalSpecs}` (plain language, no params/secrets) emitted as markdown that `chat-dag` renders as the in-chat DAG (`01` §6), and request approval | read-only tier + `submit_plan` | **reasoning tier** `pulse.llm.model` (Gemini Pro) |
| **responder** | Terminal: report the ACTUAL staging/canonical composition; also answers `explain` (folds n8n's `assistant`); never claim "deployed"/"promoted"; no emojis | read-only tier | **cheap tier** `pulse.llm.cheap-model` (Gemini Flash) |

> RESOLVED (operator 2026-06-16): per-stage model matrix (ADR 0025 §2). **Cheap tier** (`pulse.llm.cheap-model`,
> Gemini Flash) on **Router, Discovery, Configure, Provision, Responder**; **reasoning tier** (`pulse.llm.model`, Gemini
> Pro) on **Build/Composer and Planner**. A node MAY escalate to the reasoning tier on a flagged hard case, but the
> defaults above are the cost-optimized baseline. Rationale: at thousands of engineers, putting the cheap model on
> routing/structured-params/reporting and the reasoning model only on composition + plan synthesis is a large recurring
> saving, and is cleaner per-node (each LangGraph4j node declares its own model — the n8n `stageLLMs` analogue) than
> swapping models mid-loop. Provider note: PULSE reaches every model through `LlmEndpointService`'s `LlmSurface` seam.
> The cheap chat tier is a **NEW** surface `CHAT_CHEAP` (`pulse.llm.cheap-model`), distinct from `CHAT`/`CHAT_REASONING`.
> It is **NOT** the `SCHEMA_INFERENCE` surface: ADR 0011 retired model-based schema inference (it is 100% deterministic,
> zero-LLM), so `pulse.schema-inference.model` / `pulse.llm.vertex.schema-model` are **dead keys**, not the chat cheap
> tier. Under the Vertex provider the cheap tier resolves to a new `pulse.llm.vertex.cheap-chat-model` (Gemini Flash, e.g.
> `gemini-2.5-flash`) and the reasoning tier to `pulse.llm.vertex.chat-model` (Gemini Pro). "Vertex later" is a provider
> impl behind that seam (fragment 04), not a bare config swap.

**The loop (ReAct tool-loop, bounded).** WHEN a Customer turn begins THE SYSTEM SHALL run: build the context-tagged
prompt (§7.14) → call the LLM with the active tool set → for each returned tool call, execute it (read tools return
observations; mutation tools enqueue ops — §7.4) and stream a `tool_result` envelope → feed observations back → repeat
until the LLM stops calling tools OR the loop hits the recursion bound.

WHEN the loop reaches the **per-turn recursion bound (`MAX_TOOL_ROUNDS = 40`)** without the LLM stopping THE SYSTEM
SHALL halt, append an assistant message "I stopped after 40 build steps — here's what I staged so far; tell me to
continue", and NOT throw.

> RESOLVED (operator 2026-06-16): recursion bound = **40** (raised from the live single-loop constant 30,
> `ChatService.java:36`). Rationale: a 10-step multi-Blueprint pipeline ≈ 30 ops ≈ 30 tool calls, so 30 is too tight;
> 40 is a one-constant change with no downside but a latency tail (the n8n analogue is `MAX_MULTI_AGENT_STREAM_ITERATIONS`).

**Read tools see PENDING staging state.** WHEN a read tool (e.g. `get_composition`, `get_step_schema`) runs MID-TURN
after ops have already been enqueued THE SYSTEM SHALL return the composition as `applyOps(clone(canonical), pendingOps)`
— i.e. the STAGING graph as-of the queued-but-not-applied ops — so a multi-tool turn stays self-consistent (the n8n
`getCurrentWorkflow` invariant, §1.3). It SHALL NOT read the canonical graph mid-turn once the op-queue is non-empty.

**Per-session checkpoint / memory.** [read] Session + message history persist in `chat_sessions` / `chat_messages`
(`ChatSession`, `ChatMessage`, rebuilt each turn at `ChatService.java:547-618`); a session is bound to at most one
`pipelineId` (`ChatController.java:47-49`). WHEN a turn starts THE SYSTEM SHALL rebuild the LLM message list from
persisted history, dropping any orphan `tool` message whose `tool_call_id` has no matching emitted `tool_calls[].id`
(`ChatService.java:552-614`, the BUG-2026-05-27 pairing guard).

> RESOLVED (operator 2026-06-16): the per-session graph checkpointer is the LangGraph4j **`langgraph4j-postgres-saver`**
> Postgres checkpointer (ADR 0025 §3) — PULSE already runs Postgres, so it is a drop-in for durable, resumable, per-turn
> session state. The per-turn canonical snapshot and the staging clone are **checkpoints** in the checkpointer's own
> Postgres tables; abort/restore (§7.8, §7.11) is a checkpoint restore (`getState`/restore/time-travel). **No separate
> `chat_turn_snapshots` table is required** — the §7.16 #4 snapshot-store resolves to the checkpointer's schema, keyed by
> the LangGraph4j thread (session) + checkpoint (turn). Rationale: it lets the #3 design reuse the same `MemorySaver`→
> `PostgresSaver` mapping the n8n reference uses (ADR 0025 §1).

### 7.2 Planning — implicit + explicit plan-mode (brief item 2)

PULSE plans at **two layers** (the n8n distinction, §1.5), both adapted to data engineering:

1. **Implicit planning** — inside the `composer` loop the LLM sequences `add_blueprint_instance` → `wire_ports` →
   `set_params` across loop steps; each op carries a reasoning field (§7.3 B). This is how a multi-Blueprint build is
   assembled op-by-op WITHOUT a separate plan document.
2. **Explicit plan-mode** — the `planner` stage emits a structured **Plan Preview** the Customer approves / modifies /
   rejects (HITL) BEFORE any op-list is committed. This is PULSE's already-locked **Plan→Apply** gate (LOCKED §6;
   ARCH-009), not a new concept.

**How a multi-Blueprint build is planned in BOTH modes.** WHEN the Customer asks for a pipeline THE SYSTEM SHALL:
(a) in `discovery`, resolve the datasets/SOR/connectors and the medallion shape; (b) in `composer`, enqueue the full
op-list into the op-queue, building the STAGING graph live (ghost steps, §7.9); (c) in `planner`, render the staging
graph's diff as the Plan Preview; (d) hold at the approval gate until the Customer clicks **Apply Plan**. The ops are
sequenced in `composer` (implicit), and the resulting plan is surfaced for approval (explicit) — the two modes are the
SAME build seen at two altitudes.

**Plan structure (adapted from n8n `{summary, trigger, steps, additionalSpecs}`, PROMPTS-REFERENCE §5).** WHEN `planner`
submits a plan THE SYSTEM SHALL emit:

```json
{
  "summary":  "1–2 plain-language sentences: what the pipeline does (datasets in → curated output).",
  "trigger":  "what starts it, plain language (e.g. 'runs after the daily loan_master file lands').",
  "steps": [
    { "ordinal": 1,
      "title": "Ingest loan_master",
      "blueprintKey": "FileIngestion",
      "description": "Pull the daily CSV from the MSP connector into bronze.",
      "medallionLayer": "bronze" }
    /* one per op-group; non-technical description; medallionLayer ∈ bronze|silver|gold|control */
  ],
  "additionalSpecs": [ "only non-obvious notes; NEVER secret values, NEVER deploy/promote instructions" ]
}
```

Differences from n8n (data-engineering, no credentials): there is **no `credentials`/auth** field (PULSE secrets are
SecretRefs resolved at build/package time, never in a plan — ADR 0023; `PulseSystemPrompt.java:140-162`); each step
carries a `medallionLayer` and a `blueprintKey` (internal, used to render the canvas ghost — the Customer-facing
`title`/`description` stay non-technical per PROMPTS-REFERENCE §5).

> RESOLVED (operator 2026-06-16, DEFAULT): the live `plan.previewData` (human display) + `plannedCommands` (executable)
> split (`Plan.java:37-72`) is the persistence; `previewData := {summary, trigger, steps, additionalSpecs}` (the
> structure above) and `plannedCommands := the typed ops (§7.4) serialized`. The exact `previewData` key names are not
> pinned in code — reconcile field names against `PlanService.serializeCommands` + the frontend Plan Preview renderer at
> build time (no new contract).

**HITL approve / modify / reject.** WHEN the Plan Preview is shown THE SYSTEM SHALL offer three actions:
**Apply Plan** (approve → §7.9 commit), **Modify** (the Customer's correction re-enters the loop at `composer`; the
staging graph is rebuilt, not appended), **Reject** (discard the staging graph, restore the snapshot — §7.8).
WHEN the Customer modifies or rejects THE SYSTEM SHALL NOT write the canonical graph or the Command Log.

### 7.3 Tools — two tiers, every tool spelled out (brief item 3)

Tools are partitioned into **read-only/discovery** (no product-state write, no op enqueued; the LLM MAY call any of
these at any point) and **mutation** (emits exactly one typed op into the op-queue — §7.4 — and NEVER touches the
canonical graph). This is the n8n two-tier model (§2.1) on PULSE's ARCH-009 contract. Field types are JSON-schema
types; every input is Zod/JSON-schema-validated at the tool boundary (§7.13 layer 1).

Most read-only and `plan_*` tools already exist (`ChatTools.java`). This spec **renames the composition mutation tools
to the op-emitting contract** and adds the discovery tools the LOCKED §6 names. Where a tool exists today, the live
name is cited; where this spec proposes a new/renamed tool, it is flagged.

#### 7.3 A. Read-only / discovery tier

Catalog awareness is **dump-all** (LOCKED §6): all active Blueprints are injected into the cached system prompt
(§7.14), so there is NO catalog *search* tool — awareness is in-context, and these tools are for **detail + dataset/SOR
discovery + schema + validation**, not retrieval.

| Tool · purpose | INPUT (field: type) | OUTPUT | Validation |
|---|---|---|---|
| `get_blueprint_detail` — full Blueprint contract (params, in/out ports, op-list, valid layers, emit strategy, schema behavior) `[read ChatTools:115]` | `blueprint_key: string` | Markdown/JSON: params table, ports table, op-list, layer constraints | `blueprint_key` must exist + be active |
| `list_blueprints` — category/surface-filtered catalog browse `[read ChatTools:96]` | `category?: enum(INGESTION,TRANSFORM,MODELING,DATA_QUALITY,ORCHESTRATION,DESTINATION)`, `surface?: enum(composition,orchestration_policy,none,all)=composition`, `include_deprecated?: bool=false` | catalog rows | — |
| `list_data_sources` / `list_connectors` / `list_datasets` / `list_domains` / `list_sink_targets` — SOR/dataset/domain/sink discovery `[read ChatTools:19,23,29,58,312]` | per `ChatTools` (e.g. `list_connectors{sor_name}`, `list_datasets{connector_instance_id?, sor_name?}`) | entity rows w/ schema/classification | tenant-scoped |
| `get_composition` — current CANONICAL composition (instances + wirings) `[read ChatTools:142]` | `pipeline_id: string` | `{instances[], wirings[]}` (CompositionView) | pipeline in tenant |
| `get_composition_overview` — compact "N steps, M wires, layers, open ports, unresolved schema" summary | `pipeline_id: string`, `version_id?: string` | `{stepCount, wireCount, layers[], danglingPorts[], schemaStatusCounts}` | pipeline in tenant | 
| `get_step_schema` (a.k.a. `get_upstream_schema`) — inferred output columns of a step, following wirings backward `[read ChatTools:251]` | `pipeline_id, version_id, instance_id: string` | `{columns:[{name,type,nullable,pii}], source: rule|declared|discovery}` | instance in version |
| `get_blueprint_op_list` — the Blueprint's declared op-list (ADR 0012) so the agent can reason about schema/row effects without guessing | `blueprint_key: string` | `{ops:[{op, schemaEffect, rowEffect, sideOutput}]}` | blueprint active + has declared `schema_behavior` |
| `validate_structure` — graph-level checks (no orphan steps, no cycles, every step reachable, ports satisfied) | `version_id: string` | `{ok: bool, issues:[{code,message,instanceId?,port?}]}` | version in tenant |
| `validate_configuration` — per-step param/port completeness vs the Blueprint contract + the runtime punch-list (`PulseSystemPrompt.java:185-248`) | `version_id: string`, `instance_id?: string` | `{ok, issues:[{code,message,field}]}` | — |
| `validate_plan` — runs the **deterministic Builder pre-flight** over the STAGING graph so a plan that won't compile cannot be applied (§7.13 layer 3) | `version_id: string` (staging) | `{compiles: bool, blockers:[{code,message,instanceId,emission}]}` | — |
| `validate_sql_expression` — Calcite-validate a derived-column expression or a `sql-model` body against its input schema (ADR 0013) | `version_id, instance_id: string`, `expression?: string`, `sql?: string`, `expectedType?: string` | `{valid: bool, outputColumns?:[{name,type}], error?}` | input schema resolvable |
| `find_dbt_reuse_candidate` / `list_dbt_assets` — registry-aware reuse lookup `[read ChatTools:121,128]` | per `ChatTools` | best asset + emit strategy + reasons | — |
| `evaluate_dq_readiness` / `suggest_dq_expectations` — DQ scoring + GX rule suggestions `[read ChatTools:259,266]` | per `ChatTools` | score / suggested expectations | — |
| `preview_*` family — landing/table-contract/runtime-projection/deploy-readiness reads `[read ChatTools:382-450]` | per `ChatTools` | read-only projections | side-effect-free |

> RESOLVED (operator 2026-06-16): discovery tool names are snake_case (live convention; 0 hyphens) —
> `get_composition_overview` (new), `get_step_schema` (**rename of the live `get_upstream_schema`**, `ChatTools:251`),
> `get_blueprint_op_list` (new, depends on ADR 0012), `validate_structure` / `validate_configuration` (new),
> `validate_plan` (new, depends on ADR 0011/0012/0013 Builder), `validate_sql_expression` (new, ADR 0013). Some checks
> exist today under other names (`check_table_contract_readiness`, `derive_contract_impact`) and are folded into these.

#### 7.3 B. Mutation tier — each emits exactly one typed op

WHEN a mutation tool is called THE SYSTEM SHALL: (1) Zod/JSON-schema-validate the input; (2) read the STAGING graph
(`applyOps(clone(canonical), pendingOps)`); (3) run the tool's semantic validation; (4) on success, **enqueue one typed
`PlanOperation` (§7.4) into the op-queue and return a `tool_result`**; on failure, return a typed error observation to
the LLM (§7.13). A mutation tool SHALL NEVER write `sub_pipeline_instances` / `port_wirings` directly and SHALL NEVER
write the Command Log — only `apply_plan` does that (§7.9).

| Tool · purpose | INPUT (field: type) | Emits op (§7.4) | Semantic validation |
|---|---|---|---|
| `add_blueprint_instance` — add one Blueprint instance (auto-name, auto-order) | `pipeline_id, blueprint_key, instance_name: string`, `reasoning: string (REQUIRED)` — **NO initial `params` (3-F4): adding an instance and setting its params are ALWAYS separate ops; a following `set_params` carries the values** | `addInstances` | blueprint active + `add_surface=composition` (reject orchestration-policy/deprecated, the `STEP_REQUIRES_PIPELINE_ORCHESTRATION` / `BLUEPRINT_COMPAT_READ_ONLY` codes, `ChatTools:167`) |
| `wire_ports` — connect an output port → input port | `pipeline_id, source_instance_name, source_port, target_instance_name, target_port: string`, `reasoning: string (REQUIRED)` | `setWiring` (additive `mergeWiring`) | both instances exist in version; **port-type + dataset-schema-CONTRACT compatibility** (§7.13 layer 2); exact port names, not generic `input`/`output` (`ChatTools:176`) |
| `set_params` — set/update an instance's params | `pipeline_id, instance_name: string`, `params: object`, `reasoning: string (REQUIRED — "why these params/ports")` | `updateInstance` | forbidden-key blockers: canonical storage/lake/table fields, orchestration-policy fields, DQ-expectation fields, empty payloads (`ChatTools:186`, ARCH-018) |
| `remove_instance` — delete a step + its wirings | `pipeline_id, instance_name: string` | `removeInstance` | instance exists; warn if it has downstream consumers (impact radius, §2 CONFLICT OVERLAY) |
| `remove_wire` — delete one specific edge | `pipeline_id, source_instance_name, source_port, target_instance_name, target_port: string` | `removeWiring` | the wire exists |
| `rename_instance` — rename a step + fix references | `pipeline_id, instance_name, new_name: string` | `rename` | new name unique in version |
| `set_pipeline_setting` — set a Pipeline Setting op (ScheduleAndTriggers / RollbackOnFailure — portless behavior, ADR 0020/0021) | `pipeline_id: string`, `setting: enum(schedule_and_triggers, rollback_on_failure, ...)`, `config: object`, `reasoning: string` | `setPipelineSetting` | setting is a valid orchestration-policy Blueprint; routes to `update_pipeline_orchestration` semantics (`ChatTools:283`) |

The `reasoning` field is the PULSE analogue of n8n's `initialParametersReasoning` (PROMPTS-REFERENCE §2): it forces the
LLM to justify param/port choices BEFORE emitting them. WHEN `add_blueprint_instance`, `wire_ports`, `set_params`, or
`set_pipeline_setting` is called THE SYSTEM SHALL require a non-empty `reasoning` and SHALL surface it in the
`tool_result` so the Plan Preview can show "why" per step.

`apply_plan(plan_id)` is NOT a mutation-tier op-emitter — it is the **single atomic apply step** (§7.9) and is the only
tool that drains the op-queue into the Command Log. `request_credential_attach` / `navigate_ui` are **UI-intent** tools:
they carry no secrets, emit no op, and mutate no product state (`PulseSystemPrompt.java:154-156`).

> RESOLVED (operator 2026-06-16): the live composition mutators are `plan_add_step`, `plan_wire_ports`,
> `plan_set_step_params` (which persist a Plan directly) plus direct `wire_ports` / `configure_step_params` /
> `remove_step` (which write `CompositionService` immediately — `ChatTools:274-310`). This spec re-times ALL of them to
> the **op-queue** model: a mutation tool emits an op (never writes), and a SINGLE `apply_plan` drains the queue. The
> op-names `add_blueprint_instance` / `wire_ports` / `set_params` / `remove_instance` / `remove_wire` / `rename_instance`
> / `set_pipeline_setting` are the snake_case contract names (live convention; 0 hyphens in `ChatTools.java`). The
> direct-write tools (`wire_ports`, `configure_step_params`, `remove_step`) are **route-to-queue, NOT deleted** (§7.16 #7):
> keeping the tool surface stable makes every mutation previewable and reversible. This is the single biggest divergence
> from today's code.

### 7.4 Typed operations — the PlanOperation union, queue, and Command-Log mapping (brief item 4)

A mutation tool emits a **`PlanOperation`** — a discriminated union keyed to PULSE's model (sub-pipeline instances +
typed-port wiring). This is the PULSE analogue of n8n's `WorkflowOperation` union (§2.2), with PULSE field names (NOT
n8n's). Reproduced as a SHAPE for reimplementation:

```ts
// PULSE PlanOperation — discriminated on `op`. instanceRef = the instance NAME within the version
// (stable, human-readable; resolved to a real instance id at apply). All ops carry the target `versionId`.
type PlanOperation =
  | { op: 'clear' }                                                            // reducer control only
  | { op: 'addInstances';    versionId: string;
      instances: Array<{ instanceRef: string; blueprintKey: string;
                         blueprintVersion?: string; executionOrder?: number;
                         reasoning: string }> }   // NO initial params (3-F4) — a separate updateInstance/set_params op carries values
  | { op: 'removeInstance';  versionId: string; instanceRefs: string[] }
  | { op: 'updateInstance';  versionId: string; instanceRef: string;
      updates: Partial<{ params: Record<string, unknown>; lakeLayer: string;
                         lakeFormat: string; storageBackend: string }>;
      reasoning: string }
  | { op: 'setWiring';       versionId: string;
      wires: Array<{ sourceRef: string; sourcePort: string;
                     targetRef: string; targetPort: string }> }              // replace the wiring set
  | { op: 'mergeWiring';     versionId: string;
      wires: Array<{ sourceRef: string; sourcePort: string;
                     targetRef: string; targetPort: string }> }              // additive, dedup'd
  | { op: 'removeWiring';    versionId: string;
      sourceRef: string; sourcePort: string; targetRef: string; targetPort: string }
  | { op: 'rename';          versionId: string; instanceRef: string; newName: string }
  | { op: 'setName';         versionId: string; pipelineName: string }       // rename the pipeline
  | { op: 'setPipelineSetting'; versionId: string;
      setting: 'schedule_and_triggers' | 'rollback_on_failure' | string;
      config: Record<string, unknown>; reasoning: string };
```

Mapping tool → op: `add_blueprint_instance → addInstances`, `wire_ports → mergeWiring` (additive),
`set_params → updateInstance`, `remove_instance → removeInstance`, `remove_wire → removeWiring`,
`rename_instance → rename`, `set_pipeline_setting → setPipelineSetting`. `setWiring`/`setName` exist for whole-set
replace and pipeline rename (used by Modify-rebuilds and pipeline-create).

> RESOLVED (operator 2026-06-16, DEFAULT): PULSE wiring is keyed by **instance id** in the persisted `PortWiring`
> (`sourceInstanceId`/`targetInstanceId`, `PortWiring.java`), but the ops above key by **`instanceRef` = instance NAME**
> with **apply-time id resolution** (not ids-only) because, during a turn, instances may be staged (no real id yet) —
> names are the only stable handle pre-apply (this matches n8n keying connections by node *name*, §2.2, and PULSE's own
> `plan_wire_ports{source_instance_name,...}`, `ChatTools:176-183`). At apply, refs resolve to ids via the live draft-ref
> mechanism (`Plan.draftRefDeclarations`/`draftRefBindings`, `Plan.java:75-80`).

**The queue + reducer + the single atomic apply.** WHEN a mutation tool runs THE SYSTEM SHALL append its op to a
per-turn **op-queue** (a state field with an append/clear reducer, mirroring n8n's `operationsReducer`, §2.3): an
incoming op is appended; `{op:'clear'}` empties the queue. SEVERAL ops accumulate across one turn's tool calls. After
the loop settles (or at each composer superstep, to drive liveness — §7.6), a single **`process_operations`** step
SHALL drain the queue and apply ALL ops atomically to the **STAGING graph** (never the canonical graph), then reset the
queue to empty. WHEN `process_operations` runs THE SYSTEM SHALL emit a `candidate_graph` event (§7.5) carrying the full
staging composition.

**Map to the Command Log (one applied op = one command).** WHEN `apply_plan` (§7.9) commits THE SYSTEM SHALL translate
each staged op into exactly one **Command Log** entry, idempotent, with a ULID id (the live ids are ULID via
`BaseEntity`). The mapping:

| PlanOperation `op` | CommandLog `commandType` (proposed) | `aggregateType` | `aggregateId` | `payload` |
|---|---|---|---|---|
| `addInstances` (one per instance) | `composition.addInstance` | `SubPipelineInstance` | resolved instance id | `{versionId, blueprintKey, name, executionOrder, params}` |
| `removeInstance` | `composition.removeInstance` | `SubPipelineInstance` | instance id | `{versionId}` |
| `updateInstance` | `composition.updateInstance` | `SubPipelineInstance` | instance id | `{versionId, updates}` |
| `mergeWiring`/`setWiring` (one per wire) | `composition.wirePorts` | `PortWiring` | wiring id | `{versionId, sourceInstanceId, sourcePort, targetInstanceId, targetPort}` |
| `removeWiring` | `composition.removeWiring` | `PortWiring` | wiring id | `{versionId, ...edge}` |
| `rename` | `composition.renameInstance` | `SubPipelineInstance` | instance id | `{versionId, newName}` |
| `setName` | `pipeline.update` `[read]` | `Pipeline` | pipeline id | `{name}` |
| `setPipelineSetting` | `pipeline.update` `[read]` (orchestration policy) | `Pipeline` | pipeline id | `{setting, config}` |

WHEN a command is written THE SYSTEM SHALL set `idempotencyKey = tenantId + ":" + commandType + ":" + aggregateId + ":"
+ UUID` (`CommandService.java:34`) and `planId = the applied plan id`, so the whole turn's commands share one `planId`
(one turn = one Command-Log transaction). Re-applying the SAME approved plan SHALL be a no-op on already-executed
commands (idempotent).

> RESOLVED (operator 2026-06-16): register the **six `composition.*` command types** (`composition.addInstance` /
> `removeInstance` / `updateInstance` / `wirePorts` / `removeWiring` / `renameInstance`) on `CommandService` — they do
> NOT exist today (composition mutations bypass the Command Log, grounding ledger §7.0). The six strings are already in
> the correct live `noun.verb` convention (camelCase compound verbs, mirroring `pipeline.createRevision`) — leave as-is.
> **`setName` and `setPipelineSetting` fold into `pipeline.update`** (they are pipeline-level settings, not
> composition-graph ops), NOT new `composition.*` types. The live pipeline types stay
> `pipeline.create/.update/.delete/.transition/.createRevision` (`PipelineCommandHandlers.java:19-23`). This closes the
> §7.0 gap (composition becomes first-class, idempotent, Command-Logged).

### 7.5 Streaming protocol to the canvas (brief item 5)

**Transport.** [read] The stream is **SSE** over `POST /api/v1/chat/sessions/{sessionId}/messages`
(`produces=text/event-stream`, `ChatController.java:76`); a 600s emitter (`ChatService.java:433`). The LOCKED §2/§6
"`/ws`" is shorthand for this stream. There are conceptually **two channels multiplexed over the one SSE stream**: a
**progress channel** (chat text + tool status) and a **graph-state channel** (the candidate composition). They are
distinguished by the SSE `event:` name.

**Event/chunk types and EXACT shapes.** This adapts the n8n chunk catalog (§3.3) onto PULSE's SSE events. Live events
that already exist are marked `[read]`; new ones are flagged.

| SSE `event:` | Shape (`data:` JSON) | Channel | Meaning |
|---|---|---|---|
| `chunk` `[read ChatService:847]` | raw text delta (string) | progress | streamed assistant chat text (responder/router prose) |
| `tool_call` `[read ChatService:667]` | tool name (string) | progress | the LLM is invoking a tool |
| `tool_progress` (new) | `{toolName, toolCallId, status: 'running'\|'completed'\|'error', displayTitle, updates: [{type:'input'\|'progress'\|'output', data}]}` | progress | live tool-status pill ("Adding loan_master ingestion…") |
| `tool_result` `[read ChatService:693]` | the `ToolResult` envelope JSON (`ToolResult.java`: `toolName, status, planCreated, mutationApplied, planId, commandIds, affectedEntities, declaredDraftRefs, previewCommands, uiIntents, refreshHints, message`) | progress | structured outcome; frontend keys off `mutationApplied` + `refreshHints`, never the tool name |
| `candidate_graph` (a.k.a. `plan_updated`, new) | `{versionId, instances:[...], wirings:[...], turnId}` — the FULL staging composition (CompositionView shape) | graph-state | **the canvas mutation payload**: render the staging graph as ghosts (§7.9) |
| `questions` (new) | `{questions:[{id, text}], introMessage?}` | progress | clarifying-questions HITL (one `?` per Absolute Rule #1) |
| `plan` (new) | `{plan: {summary, trigger, steps[], additionalSpecs}, planId}` | progress | the Plan Preview HITL card (§7.2) |
| `navigate` `[read ChatService:682]` | a route path (string) | progress | UI-Chat sync (`navigate_ui`) |
| `messages_compacted` (new) | `{}` | progress | history was auto-compacted; UI cleanup signal (§7.11) |
| `done` `[read ChatService:734]` | `""` | progress | turn complete |
| `error` `[read ChatService:495]` | `{code, message, cause, upstream?}` | progress | structured streaming error |

WHEN `process_operations` finishes a superstep THE SYSTEM SHALL emit ONE `candidate_graph` event carrying the **full**
staging composition (a coarse snapshot), NOT per-edge deltas (the n8n design note §3.4 — coarse wire + client-side
reconcile is more robust than fragile incremental edge events). The granularity contract: **one full candidate snapshot
per superstep**, liveness produced client-side (§7.6).

**Cancellation.** WHEN the Customer cancels (an `AbortController` on the client closes the SSE connection) THE SYSTEM
SHALL detect the dead emitter (`emitterDead`, `ChatService:434-437, 535-543`), stop the loop, discard the STAGING graph
for that turn (§7.8), append an "aborted" assistant message, and NOT throw (the n8n graceful-abort, §5.3). The canonical
graph is untouched (it was never written mid-turn).

> RESOLVED (operator 2026-06-16): the NEW SSE events are **underscore** — `tool_progress`, `candidate_graph`,
> `questions`, `plan`, `messages_compacted` — matching the live `chunk`/`tool_call`/`tool_result`/`navigate`/`done`/`error`
> snake_case convention (`ChatService.java`). (Earlier hyphenated drafts of these two event names are corrected to the
> underscore forms above; fragment 05 already uses the underscore forms.) The two-channel
> framing is logical (both ride the one SSE stream, separated by `event:` name) — there is no second physical transport.
> **Add the session-scoped `POST /api/v1/chat/sessions/{sessionId}/plans/{planId}/decision`** (approve/modify/reject)
> endpoint — the LangGraph4j `interruptBefore` resume / n8n `Command({resume})` analogue (ADR 0025 §1; §8.3 rec 8) — so
> the gate is a first-class transport event, not the inline `Plan.approvedByMessageId` metadata it rides on today
> (`Plan.java:59`).

### 7.6 Liveness — how steps appear live (brief item 6)

The liveness is **produced client-side from the diff**, not pushed as deltas. WHEN the client receives a
`candidate_graph` snapshot THE SYSTEM SHALL run a client-side **reconcile** against the current canvas (the n8n
`updateWorkflow` model, §5.2):

1. `categorize(candidate)` → `{toAdd, toUpdate, toRemove}` by `instanceRef` (added/updated/removed vs the rendered set).
2. `updateExisting(toUpdate)` — mutate params/labels in place; **preserve the Customer's manual node positions**.
3. `removeStale(toRemove)`; `addNew(toAdd)` — new ghost nodes get auto-positions.
4. reconcile wirings (remove-old + add-new edges).
5. `autoLayout()` (Dagre / `@dagrejs/dagre`, §7B) — **only on STRUCTURAL change** (add/remove/wire), NOT on pure param
   edits, so the canvas does not jump during conversational tuning.

Because `process_operations` emits after each composer superstep, the Customer watches the staging graph grow
step-by-step (ghost node appears → ports light → edge draws), exactly the LOCKED §2 "Chat edits the graph and the
surface reflects it LIVE" behavior — but on the STAGING graph, not the canonical one.

> RESOLVED (operator 2026-06-16, DEFAULT): positions persist per the LOCKED §2 layout via a **client-side position map
> keyed by `instanceRef`** during a turn, persisted to instance metadata at Apply (`SubPipelineInstance` has no x/y column
> today, so this is the contract). Dagre auto-layout runs on STRUCTURAL change only (§7.6 step 5).

### 7.7 The diff — `compareGraphs` (brief item 7)

The diff is the engine behind both the live ghost rendering (§7.6) and the Plan Preview banner. WHEN the client holds a
candidate snapshot THE SYSTEM SHALL compute:

```ts
// content-based equality; status drives ghost border color + the "Review N changes" count.
compareGraphs(canonical: CompositionView, candidate: CompositionView):
  { instances: Map<instanceRef, { status: 'equal'|'modified'|'added'|'deleted'; instance }>,
    wirings:   Array<{ status: 'added'|'deleted'|'equal'; wire }> }
```

Instance equality is **content-based** over a comparable projection `{name, blueprintKey, params, lakeLayer,
lakeFormat, storageBackend}` (the n8n `compareWorkflowsNodes` / `DiffableNode` pattern, §4.2; additive-param changes
detected via a superset check). Wiring diff is a parallel set-diff over `{sourceRef, sourcePort, targetRef, targetPort}`.

WHEN the diff is computed THE SYSTEM SHALL: (a) render each changed instance with a status-colored ghost border
(added / modified / removed — the n8n diff-class approach, §4.3, on `@xyflow/react`); (b) drive the **"Review N changes"
banner**, where `N = count(added) + count(modified) + count(deleted) + changed wires`; (c) make the banner **the Plan
Preview** — clicking it opens the before/after diff and surfaces the per-step `reasoning` (§7.3 B). The diff is computed
PRE-commit (PULSE inverts n8n's post-hoc timing, §8.2): the diff IS the plan, shown before Apply.

> RESOLVED (operator 2026-06-16, DEFAULT): content-equality projection = `{name, blueprintKey, blueprintVersion, params,
> lakeLayer, lakeFormat, storageBackend}` (secrets excluded — SecretRefs per ADR 0023). **`dqExpectations` and
> `executionOrder` are EXCLUDED** from "modified" (they are not structural and would produce noisy diffs).

### 7.8 Snapshot-before-edit + revert (brief item 8)

WHEN a Customer turn begins THE SYSTEM SHALL take a **canonical snapshot** — the full `CompositionView` of the active
version — as the turn's revert point, persisted as a **LangGraph4j checkpoint** in the `langgraph4j-postgres-saver`
Postgres checkpointer (keyed by thread = session, checkpoint = turn — ADR 0025 §3; §7.1 RESOLVED — no separate
`chat_turn_snapshots` table). The STAGING graph is initialized as a deep clone of this snapshot; ops apply to the clone.

WHEN the Customer **rejects** the plan, **cancels** mid-turn, or clicks **Restore** THE SYSTEM SHALL discard the staging
graph and re-render from the snapshot — the canonical graph is untouched in all three cases (it was never written until
Apply). This is the n8n revert pattern (§4.1) made structurally cheaper: because the canonical write is locked behind
Apply, "revert" is just "drop the staging clone," not "undo applied writes."

### 7.9 Present-then-apply — the BLEND (brief item 9)

This is the LOCKED §6 decision ("plan-first with live ghost-staging," n8n §8.2) at contract level:

1. WHEN `composer` emits ops THE SYSTEM SHALL apply them to the **STAGING graph** and stream `candidate_graph` snapshots
   (§7.5); the client renders **ghost/candidate steps** on the canvas (§7.6) — the Customer watches it build live.
2. The **diff IS the Plan Preview** (§7.7): the "Review N changes" banner + before/after view.
3. WHEN the Customer clicks **Apply Plan** THE SYSTEM SHALL run the single atomic `apply_plan(plan_id)` step:
   (a) re-validate the plan is `APPROVED` and same-session; (b) run `validate_plan` (§7.13 layer 3) — if it does not
   compile, REJECT the apply with the blockers and do not mutate; (c) resolve `instanceRef`s → real ids
   (draft-ref binding); (d) write the canonical graph (`sub_pipeline_instances` / `port_wirings`) AND the Command Log
   commands (§7.4) in one transaction; (e) emit a `tool_result` with `mutationApplied=true`, `planId`, `commandIds`,
   `refreshHints`; (f) promote the ghosts to real nodes.
4. WHEN the Customer **rejects** THE SYSTEM SHALL discard the staging graph (§7.8). The canonical graph is **write-locked
   behind Apply** — true no-silent-write, the hard guarantee n8n only softens (§8.4).

WHEN `apply_plan` is invoked for a non-`APPROVED` plan, a plan from a different session, or a plan whose `validate_plan`
fails THE SYSTEM SHALL reject without side effects (`ChatTools.java:368-376`, the live contract).

### 7.10 State — the three layers (brief item 10)

| Layer | What | Owner | Lifetime | Reconciles via |
|---|---|---|---|---|
| **Agent in-flight state** | the op-queue, pending ops, message list, the turn's reasoning | backend, per session+turn | one turn (checkpoint per `(sessionId, turnId)`) | drained by `process_operations` → staging graph |
| **STAGING graph** | the candidate composition (instances + wirings), positions | backend authoritative + client mirror | one turn, until Apply or discard | the `candidate_graph` event → client `reconcile` (§7.6) |
| **CANONICAL graph + Command Log** | persisted `sub_pipeline_instances` / `port_wirings` (active version) + append-only `command_log` | backend, durable | permanent | written ONLY by `apply_plan` (§7.9) |

WHEN any layer changes THE SYSTEM SHALL keep the rule: the agent writes ONLY the op-queue; `process_operations` writes
ONLY the staging graph; `apply_plan` is the ONLY writer of the canonical graph + Command Log. The client's canvas is a
pure render of (canonical ∪ staging-diff). This is the n8n state model (§5.1) with PULSE's write-lock added.

### 7.11 Undo / rollback (brief item 11)

- **One chat turn = one undo transaction = one Command-Log entry-group.** WHEN a turn is applied THE SYSTEM SHALL group
  all its commands under one `planId` (§7.4). **Undo of that turn = restore the turn's checkpoint snapshot** (the
  LangGraph4j `langgraph4j-postgres-saver` `getState`/restore/time-travel, ADR 0025 §3) — NOT an inverse plan. Because the
  canonical graph is write-locked behind Apply and each turn is one checkpoint, restoring the pre-turn snapshot is
  strictly cheaper and safer than deriving and replaying inverse commands. The whole AI edit is one undoable unit (the
  n8n undo-bracket, §5.3), realized as a checkpoint restore.
- **Abort = discard staging, append "aborted", no throw** (§7.5 cancellation; n8n §5.3). Already-staged ops are dropped;
  nothing reached the canonical graph, so there is no partial-write to roll back (PULSE is strictly safer than n8n's
  live-mutate, §8.4).
- **Restore-to-version backstop** (§7.8): even though PULSE is plan-gated, the turn checkpoint provides "Apply, then
  changed my mind" recovery — restore re-imports the snapshot and truncates chat back to that turn.
- **Token-based history auto-compaction.** WHEN the session message history exceeds a token threshold THE SYSTEM SHALL
  summarize older turns into a conversation-summary block (§7.14) and emit `messages_compacted` (the n8n
  `autoCompactThresholdTokens` pattern, §7). The live loop has no compaction yet — this is new.

> RESOLVED (operator 2026-06-16): **undo = restore the checkpoint snapshot (no new commands)** — NOT an inverse plan
> (§7.16 #13; ADR 0025 §3). One turn = one LangGraph4j checkpoint, and the canonical write is gated behind Apply, so
> snapshot-restore is the cheaper/safer mechanism than inverse-command derivation. The auto-compaction token threshold is
> a tunable default — set to ~50% of the context budget; it remains operator-adjustable but is not a design fork.

### 7.12 Concurrent user edits vs agent edits (brief item 12)

> RESOLVED (operator 2026-06-16): **concurrency while a plan is staged = BLOCKED** (§7.16 #14), NOT REBASED. **While a
> plan is staged (a turn is mid-build or a Plan Preview is open), manual canvas edits are BLOCKED.** WHEN a staging graph
> is active for a version THE SYSTEM SHALL disable direct canvas mutation (add/move-with-persist/wire/delete/param-edit on
> the canonical graph) and show a non-blocking notice ("A proposed change is staged — Apply or Reject it to edit
> directly"). This sidesteps n8n's last-writer-wins hazard (§5.4, §8.4) by construction: the canonical graph has exactly
> one writer-path (Apply) and no concurrent manual writer during staging. Cosmetic-only moves (repositioning a node
> without persisting) MAY remain allowed. Rationale: BLOCKED is the simplest correct behavior, single-user-per-pipeline is
> the common case, and REBASE (rebase manual edits onto the staging graph) reintroduces the merge problem the plan-gate
> exists to avoid — it is a v2 enhancement, not the default.

### 7.13 Validation — three layers (brief item 13)

The n8n three-layer model (§6, §8.3 rec 6), made stronger by PULSE's typed ports + deterministic Builder. WHEN a
mutation or apply runs THE SYSTEM SHALL enforce, in order:

1. **Syntactic (Zod/JSON-schema on every tool input).** WHEN a tool is called THE SYSTEM SHALL validate the input
   against its JSON schema; an invalid input returns a typed `VALIDATION_ERROR` observation to the LLM (not a throw), so
   the agent self-corrects on the next superstep (errors-as-observations, §6).
2. **Semantic (port-type + dataset-schema-CONTRACT compatibility at wire-time).** WHEN `wire_ports` runs THE SYSTEM SHALL
   check that the source output port's type AND its **dataset schema contract** are compatible with the target input
   port's required contract (PULSE's typed ports make this stronger than n8n's connection-type inference, §8.3 rec 6;
   §2 CONFLICT OVERLAY classifies the edit non-breaking/partial/breaking from how the target consumes the columns). An
   incompatible wire is rejected with `INVALID_WIRE` (or auto-oriented if reversed) and never enqueued.
3. **Deterministic Builder pre-flight (`validate_plan`).** WHEN `apply_plan` runs THE SYSTEM SHALL first run the
   deterministic Builder's pre-flight over the STAGING graph (ADR 0011/0012/0013: schema is deterministic; ops compose;
   the Builder compiles). A plan that won't compile (unknown blueprint with no declared `schema_behavior`, unsatisfiable
   port, schema-contract mismatch, illegal medallion transition) **CANNOT be applied** — `validate_plan.compiles=false`
   blocks Apply and returns the blockers to the agent for self-correction. This is the loud-fail of ADR 0011 (unknown →
   error, never an LLM fallback).

All validation errors are returned to the agent **as observations** (the n8n error-as-observation retry loop, §6/§7),
never thrown to the Customer mid-build.

> RESOLVED (operator 2026-06-16): `validate_plan` runs **interim checks now** (§7.16 #15) — `validate_structure` +
> `validate_configuration` + `check_table_contract_readiness` (`ChatTools:424`) — until the deterministic Builder (ADR
> 0012/0013) lands, at which point the full compile-preflight swaps in. This is a build-sequencing call (ship the
> workspace pre-Builder), not a permanent design fork; `validate_plan` does NOT block on the Builder.

### 7.14 Prompting (brief item 14)

Adapts n8n's prompt shape (the prior `N8N-PROMPTS-REFERENCE.md` summary is removed; the surviving grounded source is the `docs/ui/chat-prompts/` fragments) into PULSE's **data-engineering voice** (datasets, medallion bronze/silver/gold,
SCD2, partitions, dbt/Spark/Airflow/Great Expectations). The live `PulseSystemPrompt.java` already carries most of this
voice (IDENTITY "25-year veteran data engineering lead", ABSOLUTE_RULES, MEDALLION_RULES, PLANNER_PACKET) — this section
**reorganizes it into the LOCKED 7 per-stage prompts** + the dump-all catalog + the context-injection wrappers.

> **The actual per-stage prompt TEXT lives in `chat-prompts/01-system-prompts.md` (authoritative) and the context-tag
> wrappers + tool strings in `chat-prompts/02-tools-and-context.md`.** This section is the contract spine: the catalog
> dump-all rule, the per-CATEGORY best-practice guide shape, the context wrappers, and the binding guardrails. Where it
> previously sketched FIVE draft role lines, those are superseded — the fragments author the LOCKED **7** stages
> (Router · Discovery · Build/Composer · Configure · Provision · Planner · Responder), the single judicious-ask
> Discovery, and the global-Mode storage rule (below).

**Catalog grounding = DUMP all ~50 Blueprints into the CACHED system prompt** (LOCKED §6). WHEN the system prompt is
assembled THE SYSTEM SHALL inject ALL active Blueprints as **tight entries** (`name · intent · ports · key params`) into
a prompt block marked for provider prompt-caching, so it is cheap after turn one. There is NO catalog-search tool;
awareness is in-context. The live prompt already dumps the catalog summary (`ChatService.java:1023-1031`,
`PulseSystemPrompt.buildContextSection`). Tight-entry format (one line each):

```
- FileIngestion (INGESTION/bronze): pull a file from a connector into bronze. in: — · out: raw_output. params: connector_instance_id, dataset_ids, file_format.
- BronzeToSilverCleaning (TRANSFORM/silver): cast/trim/rename/drop into silver (dbt). in: data_input · out: cleaned_output. params: rename_map, drop_columns, type_coercions.
- SCD2Dimension (MODELING/silver|gold): track history (SCD2, dbt snapshot). in: data_input · out: dimension_output. params: business_key, change_columns.
- DQValidator (DATA_QUALITY/any): GX expectations. in: data_input · out: validated_output, quarantine(side). params: expectations[].
  ... (all active Blueprints) ...
```

> RESOLVED (operator 2026-06-16, DEFAULT — fallback named): tight-entry schema = the per-Blueprint
> `name · category · layer · ports · key-param list` shown above, dumped into the cached prompt (LOCKED §6 catalog-in-
> prompt). Today the dump is `blueprint_key + 80-char description` only (`ChatService.java:1030`), without ports/params, so
> this is the contract. The prompt-cache markers (`cache_control: ephemeral` for Vertex; the OpenRouter equivalent) remain
> a **forward build item** — the live request body carries no `cache_control` yet (`ChatService.java:649-662`, fragment 04
> §6). Budget-fit is a measurable fact to verify; the §6 hybrid (summaries + on-demand `get_blueprint_detail`) is the
> named fallback if the ~50 entries bloat the cache budget.

**The LOCKED 7 per-stage system prompts (PULSE vocabulary).** Each is a `prompt().section(...).build()`-style assembly
injected as a cached `SystemMessage`. **The actual prompt TEXT is authoritative in `chat-prompts/01-system-prompts.md`**
(`01` §1 Router · §2 Discovery · §3 Build/Composer · §4 Configure · §5 Provision · §6 Planner · §7 Responder, plus the
shared preamble §8). Do not re-inline it here. The contract facts §7 must agree on:

- **Router** (`01` §1) routes to `{discover, build, configure, provision, explain, plan_decision}` — the LOCKED 7-route
  set; resolves deixis against `<selected_step>`. (NOT the old 5-route `{build, configure, explain, discover,
  plan_decision}` — the old set lacked `provision`.)
- **Discovery** (`01` §2) is **SINGLE judicious-ask**, NOT dual-mode: ask the ONE most-important clarifying question
  ONLY on material, plan-changing ambiguity (a different change pattern / grain / source / PII presence); otherwise make
  the reasonable choice, note the assumption, and proceed — the human reviews at Plan Preview. There is NO headless
  "never-ask" variant and NO second agent (`D1-FEEDBACK-CHANGELIST.md:53-56`; supersedes the n8n dual `PROCESS_WITH_QUESTIONS`
  / `NEVER ask` split).
- **Build/Composer** (`01` §3) emits ops (add_blueprint_instance → wire_ports → set_params) in medallion order, each with
  a REQUIRED `reasoning`; never writes canonical; never writes codegen (ADR 0013).
- **Configure** (`01` §4) — STRUCTURED set_params on an existing step, validated deterministically, no parameter-updater
  sub-LLM.
- **Provision** (`01` §5) — onboards Producer/SOR/ServiceInstance/Binding/Domain/Connector/Dataset (+ schema inference);
  retains the live `PulseSystemPrompt` onboarding coverage.
- **Planner** (`01` §6) — plain-language Plan Preview emitted as a markdown table that the EXISTING `chat-dag` renderer
  draws as the in-chat DAG (no invented `graph` field; `parsePipelineSteps`/`parsePipelineTable`, `chat-panel.tsx:563`,
  `PulseSystemPrompt.java:35`).
- **Responder** (`01` §7) — reports the ACTUAL composition, also answers `explain` (folds n8n's `assistant`); never
  deploy/promote; no emojis.

**Tool descriptions carry the contract.** WHEN a mutation tool is registered THE SYSTEM SHALL include in its description
the `reasoning`-required contract (the n8n `initialParametersReasoning` analogue): e.g. `add_blueprint_instance` —
*"REQUIRED `reasoning`: explain why this Blueprint, which datasets/ports it consumes and produces, which medallion layer,
and the key param choices (e.g. SCD2 business key, partition column) before emitting them."* (§7.3 B.)

**Best-practices guides — one per Blueprint CATEGORY** (PROMPTS-REFERENCE §3 analogue; the live code already has
per-Blueprint cards, `ChatService.java:37-91`, and example packets `:93-285` — this spec consolidates to FIVE category
guides). WHEN `composer`/`discovery` selects Blueprints in a category THE SYSTEM SHALL inject that category's guide.
Per-guide shape: `# Best Practices: <Category>` → `## Pipeline Design (CRITICAL rules)` → `## Recommended Blueprints`
(each: Purpose / Use cases / Configuration / Best practice). The five guides, data-engineering voice:

| Category | Guide focus (one line) |
|---|---|
| **Ingestion** | bronze = raw only; deterministic schema reads; audit columns; sensing from the dataset's time grain (ADR 0022); PySpark. |
| **Transform** | silver = cast/trim/rename/dedup/mask (dbt, materialized=table); contract-stable column sets; partitioning for marts. |
| **Modeling** | SCD2 (business key vs change columns), snapshots (point-in-time only), facts (declare grain first), marts (BI contract); dbt. |
| **DQ** | Great Expectations only (never dbt tests); blocking vs warning; quarantine = managed table; expectations as params, not macros. |
| **Orchestration** | ScheduleAndTriggers (event vs schedule, ADR 0021); catchup is a separate explicit decision; max_active_runs from ordering sensitivity. |

**Context-injection wrappers (per-turn).** WHEN a turn's prompt is built THE SYSTEM SHALL concatenate labeled context
parts into the user message (the n8n `=== SECTION ===` / context-tag pattern, PROMPTS-REFERENCE §4), adapted:

- `<current_composition>{staging-or-canonical CompositionView JSON}</current_composition>` (+ "large param values may be
  trimmed; use `get_blueprint_detail` / `get_step_schema` for full detail") — the ground-truth graph, injected each turn.
- `<dataset_schemas>{per-dataset columns + types + PII flags}</dataset_schemas>` — the available datasets' schemas (live
  prompt already injects this, `ChatService.java:986-1001`).
- `<selected_step>{the instance the Customer has selected in the inspector}</selected_step>` — the deictic anchor ("this
  step", "it" → this instance).
- `<run_status>{success|error|no_run + per-step row counts}</run_status>` — last run facts (the n8n
  `<execution_status>`/`<data_flow>` analogue).
- conversation-summary — a plain-text header block (`Previous summary:` / `Original request:` / `Prior actions:` /
  `Current request:`), NOT an XML tag (PROMPTS-REFERENCE §4).

WHEN delivering streamed text to the Customer THE SYSTEM SHALL strip these context tags (the n8n stream-processor
tag-stripping, §3.2).

**ACTIVE MODE injection — storage is the GLOBAL Mode, NOT a per-pipeline default.** WHEN any prompt that can touch
storage is assembled (Build/Composer, Configure, Provision, Planner) THE SYSTEM SHALL inject the active **Mode** as a
per-deployment CONSTANT read from `RuntimeAuthorityService.getActivePersona()` (`GCP_PULSE` or `DPC_PULSE`,
`RuntimePersona.java`), with the layer→backend mapping from the live presets (`RuntimeAuthorityService.java`):
**GCP** → bronze + silver = BigQuery-managed Iceberg (`iceberg_bq_managed`), gold = BigQuery native (`bq_native`);
**DPC** → bronze + silver + gold = Hive + Parquet on S3A object storage. The stage SHALL NOT choose, ask for, or
`set_params` the storage backend / lake format / bucket / path — they derive from the Mode + medallion layer; it sets
only user-tier canonical fields. (This SUPERSEDES the old per-pipeline `storage_backend` default framing — see fragment
`chat-prompts/01-system-prompts.md` §8b "ACTIVE MODE injection" `[read D1-FEEDBACK-CHANGELIST.md:34-37]`. The live
`plan_create_pipeline` still carries a `default_storage_backend?` param (§7.17 #15) — that is the per-pipeline
*override seam* on top of the Mode default, not a free per-step choice.)

**Guardrails (binding).** THE SYSTEM SHALL, in every stage: (a) carry only **SecretRefs, never secret values** (ADR
0023; `PulseSystemPrompt.java:140-162`); (b) NEVER tell the Customer to deploy/promote to a higher environment — that is
gated (`PulseSystemPrompt.java:164-179`); (c) report the **ACTUAL** composition/params, not assumed config; (d) use NO
emojis; (e) PII is per-column, never per-dataset; (f) one `?` per message (Absolute Rule #1); (g) never claim "created"
after a `plan_*`/op-emitting call — only after `apply_plan` returns success (ARCH-009).

> RESOLVED (operator 2026-06-16) — SHAPE; voice review remains the named content pass (§7.16 #17). The prompt **shape** is
> LOCKED: split the one concatenated system prompt (`ChatService.buildSystemPrompt`, `:953-1072`, phase-gated by
> `ConversationPhase` `:1038-1061`) into the **7 per-stage assemblies** authored in `chat-prompts/01-system-prompts.md`,
> and consolidate the ~24 per-Blueprint cards into **5 category guides** (`chat-prompts/03-best-practices.md`). The
> verbatim **DE-voice review** of every best-practice / use-case / pitfall line is the remaining operator-voice content
> pass (the fragments carry their own `> GUESS:` voice worklist) — it is a content tune, not an architecture decision.

### 7.15 Per-page behavior — one Chat, many surfaces (brief item 15)

The SAME agent framework (§7.1), op-queue (§7.4), streaming (§7.5), and Plan→Apply gate (§7.9) run on EVERY page; only
the **active tool set**, the **page-context tags** (§7.14), and the **typed ops/commands** differ by surface. Chat is
app-wide (LOCKED §2). The pipeline-composition surface is the DEEP case (everything in §7.1–§7.14). The other surfaces
reuse the framework with page-specific tools.

**Pipeline composition (the deep case).** Tools: the full read tier (§7.3 A) + the composition mutation tier (§7.3 B);
ops: the `addInstances`/`mergeWiring`/`updateInstance`/… union (§7.4); context: `<current_composition>` +
`<dataset_schemas>` + `<selected_step>` + `<run_status>`. This is the entirety of §7.1–§7.14.

**Domains.** WHEN the Customer creates/edits a domain THE SYSTEM SHALL use `list_domains` / `create_domain`
(`ChatTools:58,62`) and SHALL **elicit the calendar/grain/fiscal** at creation (ADR 0023: a new domain otherwise has no
holiday calendar and silently falls back to `US-FED`). Required elicitation (one `?` each): `business_date_grain`
(DAILY/…); the holiday calendar id; the fiscal offset. The mutation is a `plan_*`→`apply_plan` flow if it changes
product state; today `create_domain` writes directly (`ChatTools:62`) — to honor the universal Plan→Apply gate this
should become a plan-producing tool.

**SORs / producers + connectors + datasets.** WHEN the Customer onboards an SOR/connector/dataset THE SYSTEM SHALL use
`list_data_sources` / `create_data_source` / `get_connector_type_schema` / `create_connector` /
`request_credential_attach` / `derive_dataset_schema` / `create_dataset` (`ChatTools:19-94, 330-501`), branching by
connector family (object-storage vs external-SOR, `PulseSystemPrompt.CONNECTOR_VOCABULARY`). For datasets THE SYSTEM
SHALL **elicit the sensing strategy** (ADR 0022): `sensingStrategy` (file vs sql_query), the `file_naming_pattern` (with
BOTH the business-date and processing-datetime segments, Phase 2g), and the `time_grain`. Secrets go through the
credential dialog (SecretRefs), never chat.

**Blueprints / commands pages.** Read-only/explanatory: `list_blueprints` / `get_blueprint_detail` /
`view_code_examples`, and the Command Log / Plan history (`/commands`). No composition ops here.

WHEN the Customer's topic shifts pages THE SYSTEM SHALL call `navigate_ui` so the screen mirrors the conversation
(Absolute Rule #5; `ChatService.java:669-684`), keeping one Chat consistent across all surfaces.

> RESOLVED (operator 2026-06-16): **universal Plan→Apply gate** (§7.16 #18). Creating domains / SORs / connectors /
> datasets (`create_domain` / `create_data_source` / `create_connector` / `create_dataset`) ALSO goes through Plan
> Preview → Apply — entity creation appears as explicit commands in the preview, and **nothing writes product state
> silently**. Today several of these write directly (`ChatTools:36-94`); they become plan-producing tools so the gate is
> truly universal, consistent with the chat→plan→command model (LOCKED §6, CLAUDE.md). Draft-saves remain the only
> non-gated writes; every state-changing create is in the preview.

### 7.16 GUESS index — resolution status for the SPEC-GATE / operator

Every `> GUESS:` above, collected. There were **17 inline `> GUESS:` markers** decomposed into **18 distinct decisions**.
The operator LOCKED the decisions below on **2026-06-16** (keystone: **ADR 0025** for #1/#2/#4/#13). Each item now carries
its resolution; the few remaining DEFAULT-grade confirmations are marked as such.

1. **§7.0/§7.1 — Stage architecture.** > RESOLVED (operator 2026-06-16): **Option A — build the routed 7-stage model now
   as a LangGraph4j `StateGraph`** (ADR 0025). Not the single-loop/phase-gated-prompt fallback (Option B, retained as the
   named fallback). Stages = graph nodes + supervisor/router + conditional edges; the plan gate = `interruptBefore` on the
   apply node (#10); the shared state carrier = LangGraph4j `AgentState` (#4); the snapshot store = the
   `langgraph4j-postgres-saver` checkpointer (#4). Rationale: ~1:1 map to the n8n reference, lowers risk, unblocks durable
   session state + the per-stage cost lever.
2. **§7.1 — Per-stage model matrix.** > RESOLVED (operator 2026-06-16, ADR 0025 §2): **cheap tier**
   (`pulse.llm.cheap-model`, Gemini Flash — a NEW dedicated chat-stage surface `CHAT_CHEAP`, NOT the retired
   `SCHEMA_INFERENCE`/`pulse.schema-inference.model` keys, which ADR 0011 left dead) on
   Router/Discovery/Configure/Provision/Responder; **reasoning tier** (`pulse.llm.model`, Gemini Pro) on Build/Composer
   and Planner; a node MAY escalate on a flagged hard case. Provider: each node resolves its model via
   `LlmEndpointService`'s `LlmSurface` seam (fragment 04), not a bare config swap.
3. **§7.1 — Recursion bound.** > RESOLVED (operator 2026-06-16): **`MAX_TOOL_ROUNDS = 40`** (raised from the live 30 — a
   multi-Blueprint build can exceed 30 tool calls; one-constant change, no downside but a latency tail).
4. **§7.1 — Per-session snapshot store.** > RESOLVED (operator 2026-06-16, ADR 0025 §3): the **`langgraph4j-postgres-saver`
   Postgres checkpointer** IS the snapshot store (PULSE already runs Postgres). **No separate `chat_turn_snapshots` table** —
   per-turn snapshots are checkpoints keyed by thread (session) + checkpoint (turn).
5. **§7.2 — Plan `previewData` shape.** DEFAULT (recommendation given): `{summary,trigger,steps,additionalSpecs}` maps
   onto the live `Plan.previewData` (human display) + `plannedCommands` (executable) split; reconcile field names against
   `PlanService.serializeCommands` + the frontend renderer at build time (no new contract).
6. **§7.3 A — Discovery tool names.** > RESOLVED (operator 2026-06-16): snake_case, zero hyphens — `get_composition_overview`
   (new) / `get_step_schema` (**rename of live `get_upstream_schema`**) / `get_blueprint_op_list` (new) /
   `validate_structure` / `validate_configuration` / `validate_plan` (new) / `validate_sql_expression` (new). Live
   convention: every tool in `ChatTools.java` is snake_case (0 hyphens).
7. **§7.3 B — Op-emitting mutation tools.** > RESOLVED (operator 2026-06-16): renamed to snake_case —
   `add_blueprint_instance` / `wire_ports` / `set_params` / `remove_instance` / `remove_wire` / `rename_instance` (and
   `set_pipeline_setting` **folds into `pipeline.update` via #9** — `setName`/`setPipelineSetting` are pipeline-level, not
   a 7th `composition.*` type). The direct-write `wire_ports` / `configure_step_params` / `remove_step` are **route-to-queue
   (NOT deleted)** — every mutation becomes previewable; the tool surface stays stable.
8. **§7.4 — `instanceRef` keying.** DEFAULT (recommendation given): refs-by-name + apply-time id resolution (the live
   `Plan.draftRefDeclarations`/`draftRefBindings` mechanism) — staged instances have no id pre-apply.
9. **§7.4 — `composition.*` command types.** > RESOLVED (operator 2026-06-16): register the **six** new Command-Log types
   `composition.addInstance`/`removeInstance`/`updateInstance`/`wirePorts`/`removeWiring`/`renameInstance` (already in the
   correct `noun.verb` convention — leave as-is); **`setName`/`setPipelineSetting` fold into `pipeline.update`** (they are
   pipeline-level settings, not composition-graph ops). Closes the §7.0 gap.
10. **§7.5 — New SSE events + decision endpoint.** > RESOLVED (operator 2026-06-16): event names are underscore —
    `tool_progress` / `candidate_graph` / `questions` / `plan` / `messages_compacted` (matching the live `tool_call` /
    `tool_result` convention; the hyphenated `candidate_graph`/`messages_compacted` written earlier in §7.5 are fixed).
    **Add the session-scoped `POST /api/v1/chat/sessions/{sessionId}/plans/{planId}/decision` endpoint** (the LangGraph4j
    `interruptBefore` resume / n8n `Command({resume})` analogue) — the plan gate is a first-class transport event.
11. **§7.6 — Node-position store.** DEFAULT (recommendation given): client-side position map keyed by `instanceRef` during
    a turn, persisted to instance metadata at Apply; Dagre auto-layout on STRUCTURAL change only (no x/y column today).
12. **§7.7 — Diff content-equality fields.** DEFAULT (recommendation given): projection = `{name, blueprintKey,
    blueprintVersion, params, lakeLayer, lakeFormat, storageBackend}` (secrets excluded, ADR 0023); **exclude
    `dqExpectations` and `executionOrder`** from "modified" to avoid noisy diffs.
13. **§7.11 — Undo + compaction.** > RESOLVED (operator 2026-06-16, ADR 0025 §3): **undo = restore the checkpoint snapshot
    (no new commands)**, NOT an inverse plan — one turn = one checkpoint, canonical is write-locked behind Apply. Compaction
    token threshold = a tunable default (~50% of the context budget).
14. **§7.12 — Concurrency rule.** > RESOLVED (operator 2026-06-16): **BLOCKED while a plan is staged** (manual canonical
    edits disabled until Apply or Reject) — the simplest correct behavior; REBASE is a v2 enhancement.
15. **§7.13 — `validate_plan` composition.** > RESOLVED (operator 2026-06-16): **interim checks now** (structure + config
    + contract-readiness) until the deterministic Builder (ADR 0012/0013) lands, then swap in the Builder pre-flight. A
    build-sequencing call, not a permanent fork.
16. **§7.14 — Catalog tight-entry schema + prompt-cache markers.** DEFAULT (fallback named): the per-Blueprint tight-entry
    list dumped into the cached prompt; prompt-cache markers stay a forward build item (no `cache_control` in the live
    request body, `ChatService.java:649-662`); budget-fit is a measurable fact with the §6 hybrid as the named fallback.
17. **§7.14 — The LOCKED 7 per-stage prompts + five category guides.** Prompt *shape* RESOLVED (LOCKED 7 stages + 5
    category guides, fragments `01`/`03`); the verbatim DE-**voice** review remains the operator-voice content pass (the
    fragments carry their own voice worklist).
18. **§7.15 — Universal Plan→Apply gate.** > RESOLVED (operator 2026-06-16): **universal gate** — domain/SOR/connector/
    dataset creation ALSO goes through Plan Preview → Apply (entity creation appears as explicit commands in the preview;
    nothing writes product state silently).

The keystone build-shape decisions (#1/#2/#4/#13) are recorded in **ADR 0025**; the naming decisions (#6/#7/#9/#10) follow
the live snake_case (tools) / `noun.verb` (commands) / underscore (SSE) conventions.

---

### 7.17 Tool surface + full backend-API enumeration (PIECE A — added 2026-06-16, PRODUCER pass)

> Status: NEW section, added by the PRODUCER pass that does NOT depend on the pending Vertex merge. This is the
> **explicit tool surface** (every LLM tool the agent exposes) and the **full backend API surface** (every REST/SSE
> endpoint the Chat / composition workspace requires, existing AND to-build). Two tables. Each row is tagged `[read]`
> (exists at the cited `file:line`) or `> GUESS:` (new, to build). The naming-and-renaming GUESSes in §7.3 already cover
> *which tools should be renamed*; THIS section is the flat, complete inventory the SPEC-GATE completeness pass needs —
> nothing the agent can call, and nothing the workspace can fetch, may be undeclared.
>
> **DEFERRED (needs Vertex merge / operator voice):** the per-tool *prompt text* (the `reasoning`-required description
> strings, the data-engineering voice of each tool description) and the per-stage agent prompts are NOT finalized here —
> they wait on the Vertex provider merge + the operator voice review (§7.14 GUESS). This section pins the tool's
> NAME · CONTRACT · ARGS · op/endpoint mapping only, not its final prompt wording.

#### 7.17 A — Tool surface (every LLM tool the agent exposes)

The live registry is `ChatTools.getToolDefinitions()` (`ChatTools.java:9-503`); the deprecated `propose_*` aliases route
to the same handlers (`ChatTools.java:210-247`) and are listed once, collapsed. Tier = the §7.3 partition
(R = read-only/discovery, M = mutation, A = apply, U = UI-intent, X = direct-write that §7.3 B requires re-timing to the
op-queue). Count is of **distinct live tools** + the **new tools this rebuild needs**.

| # | Tool · one-line contract | Args (name: type) | Tier | Status |
|---|---|---|---|---|
| 1 | `navigate_ui` — move the main UI to a page so screen + chat stay in sync | `page: enum(data_sources, data_source_detail, pipelines, pipeline_detail, blueprints, commands)`, `resource_id?: string` | U | `[read ChatTools:11]` |
| 2 | `list_data_sources` — list all SORs with connectors + dataset counts | — | R | `[read ChatTools:19]` |
| 3 | `list_connectors` — list a SOR's connectors (name/type/cred-status/dataset count) | `sor_name: string` | R | `[read ChatTools:23]` |
| 4 | `list_datasets` — list datasets for a connector / SOR / whole tenant | `connector_instance_id?: string`, `sor_name?: string` | R | `[read ChatTools:29]` |
| 5 | `create_data_source` — persist a new SOR (direct write) | `name: string`, `domain_name?: string`, `domain_id?: string`, `description?: string` | X | `[read ChatTools:36]` |
| 6 | `create_connector` — create a connector on a SOR (direct write) | `sor_name, connector_name: string`, `connector_type?, description?: string`, `config?, credential_refs?, credentials?: object`, `credentials_environment?: string` | X | `[read ChatTools:45]` |
| 7 | `list_domains` — list tenant data domains + business-date config | — | R | `[read ChatTools:58]` |
| 8 | `create_domain` — create a domain, optionally set business date (direct write) | `name: string`, `description?: string`, `current_business_date?: string`, `business_date_grain?: enum(DAILY, DAILY_BUSINESS_DAY, WEEKLY, MONTHLY)` | X | `[read ChatTools:62]` |
| 9 | `create_dataset` — create a dataset w/ schema/time-dim/file-naming (direct write) | `sor_name, name: string`, `description?, classification?: string`, `schema_snapshot?: object`, `time_grain?: enum`, `current_asof?, file_naming_pattern?: string`, `processing_datetime_source?: enum(filename_segment, file_arrival_time, airflow_run_time)`, `connector_instance_id?: string`, `partition_strategy?, cluster_strategy?: object`, `write_mode?: enum(append, overwrite, merge)`, `table_format_hint?: enum(PARQUET, ICEBERG, DELTA)` | X | `[read ChatTools:72]` |
| 10 | `list_blueprints` — category/surface-filtered catalog browse | `category?: enum(INGESTION, TRANSFORM, MODELING, DATA_QUALITY, ORCHESTRATION, DESTINATION)`, `surface?: enum(composition, orchestration_policy, none, all)=composition`, `include_deprecated?: bool=false` | R | `[read ChatTools:96]` |
| 11 | `get_blueprint_detail` — full Blueprint contract (params/ports/layers/emit/schema behavior) | `blueprint_key: string` | R | `[read ChatTools:115]` |
| 12 | `list_dbt_assets` — list indexed dbt assets for a domain (reuse lookup) | `domain_id?, domain_name?: string` | R | `[read ChatTools:121]` |
| 13 | `find_dbt_reuse_candidate` — best dbt reuse candidate + emit strategy + reasons | `domain_id?, domain_name?: string`, `business_concept: string`, `asset_type: enum(model, snapshot)`, `grain?, access_level?, schema_signature?: string`, `planning_context?: object`, `emit_strategy?: enum(generate, reuse_wrapper, reference_only)` | R | `[read ChatTools:128]` |
| 14 | `get_composition` — current CANONICAL composition (instances + wirings) | `pipeline_id: string` | R | `[read ChatTools:142]` |
| 15 | `plan_create_pipeline` — PREVIEW plan to create a pipeline (DOMAIN scope) | `scope?: enum(DOMAIN)=DOMAIN`, `name: string`, `domain?, domain_name?, domain_id?: string`, `description: string`, `default_storage_backend?: enum(DPC, GCP)` | M | `[read ChatTools:152]` |
| 16 | `plan_add_step` — PREVIEW plan to add a composition step (rejects orchestration-policy / deprecated) | `pipeline_id, blueprint_key, instance_name: string`, `params?: object` | M | `[read ChatTools:166]` |
| 17 | `plan_wire_ports` — PREVIEW plan to wire output→input by EXACT port names | `pipeline_id, source_instance_name, source_port, target_instance_name, target_port: string` | M | `[read ChatTools:175]` |
| 18 | `plan_set_step_params` — PREVIEW plan for a generic-params update (ARCH-018 forbidden-key blockers) | `pipeline_id, instance_name: string`, `params: object` | M | `[read ChatTools:185]` |
| 19 | `plan_configure_remote_pipeline_invocation` — PREVIEW plan for a RemotePipelineInvocation step (design-time only) | `pipeline_id: string`, `version_id?, instance_id?, instance_name?: string`, `federated_tenant_key, remote_target_ref, environment, airflow_connection_id: string`, `remote_dag_id?: string`, `poll_interval_seconds?, timeout_seconds?: int`, `payload_template?: object` | M | `[read ChatTools:193]` |
| 20 | `propose_create_pipeline` / `propose_add_instance` / `propose_wiring` / `propose_set_params` — DEPRECATED aliases of the four `plan_*` above | (per the aliased tool) | M | `[read ChatTools:212-247]` (deprecated) |
| 21 | `get_upstream_schema` — inferred upstream output schema, following wirings backward | `pipeline_id, version_id, instance_id: string` | R | `[read ChatTools:251]` |
| 22 | `evaluate_dq_readiness` — DQ readiness score 0-100 + recommendations | `pipeline_id, version_id: string` | R | `[read ChatTools:259]` |
| 23 | `suggest_dq_expectations` — AI-suggested GX expectations for a step | `pipeline_id, version_id, instance_id: string` | R | `[read ChatTools:266]` |
| 24 | `configure_step_params` — **directly applies** param changes to a step (NOT a plan) | `pipeline_id, version_id, instance_id: string`, `params: object` | X | `[read ChatTools:274]` |
| 25 | `update_pipeline_orchestration` — **directly** sets version-level orchestration policy (schedule/catchup/etc.) | `pipeline_id, version_id: string`, `schedule_cron?: string`, `catchup_enabled?: bool`, `max_active_runs?: int`, `depends_on_past?: bool`, `policy_configs?: object` | X | `[read ChatTools:283]` |
| 26 | `wire_ports` — **directly** wires two ports (writes `CompositionService` now) | `version_id, source_instance_id, source_port_name, target_instance_id, target_port_name: string` | X | `[read ChatTools:295]` |
| 27 | `remove_step` — **directly** removes a step + its wirings | `version_id, instance_id: string` | X | `[read ChatTools:305]` |
| 28 | `list_sink_targets` — list registered publish destinations | — | R | `[read ChatTools:312]` |
| 29 | `create_sink_target` — create a TARGET-registry SOR (direct write) | `name: string`, `description?: string`, `domain_id: string` | X | `[read ChatTools:316]` |
| 30 | `view_code_examples` — curated codegen examples for a Blueprint | `blueprint_key: string` | R | `[read ChatTools:324]` |
| 31 | `get_connector_type_schema` — canonical connection_spec for a connector type | `connector_type: string` | R | `[read ChatTools:330]` |
| 32 | `request_credential_attach` — open the credential dialog (UI-intent; no secrets in chat) | `connector_instance_id?, connector_name?: string`, `environment?: enum(DEV, INTEGRATION, UAT, PRODUCTION)=DEV` | U | `[read ChatTools:336]` |
| 33 | `get_storage_paths` — resolve bucket + SOR path prefix for object-storage connectors | `sor_id: string`, `environment?: enum(LOCAL, DEV, INTEGRATION, UAT, PRODUCTION)=DEV`, `backend: enum(DPC, GCP)`, `direction?: enum(source, sink)=source` | R | `[read ChatTools:344]` |
| 34 | `apply_dq_expectations` — **directly** persist GX rules to a DQValidator instance | `pipeline_id, version_id, instance_id: string`, `expectations: array` | X | `[read ChatTools:353]` |
| 35 | `apply_plan` — the SOLE generic mutator: apply an APPROVED plan by id (drains plannedCommands) | `plan_id: string` | A | `[read ChatTools:368]` |
| 36 | `preview_dataset_landing` — read-only landing-contract preview for a dataset | `dataset_id: string`, `environment?: string=dev` | R | `[read ChatTools:382]` |
| 37 | `preview_table_contract` — read-only table-contract preview for a producing instance | `instance_id, version_id: string`, `environment?: string=dev` | R | `[read ChatTools:393]` |
| 38 | `preview_runtime_projection` — read-only active runtime projection + drift check | `package_id, target_id: string`, `environment?: string=dev` | R | `[read ChatTools:406]` |
| 39 | `preview_runtime_authority` — read-only active runtime authority (persona/targets/backends) | — | R | `[read ChatTools:418]` |
| 40 | `check_table_contract_readiness` — read-only: are all table contracts present + active? | `version_id: string` | R | `[read ChatTools:424]` |
| 41 | `get_package_contract` — read-only canonical package manifest (v2) | `package_id: string` | R | `[read ChatTools:433]` |
| 42 | `check_deploy_readiness` — read-only 21-check deploy preflight composition | `package_id, target_id: string` | R | `[read ChatTools:442]` |
| 43 | `get_workspace_context` — read-only WORKSPACE-scope git repo + workspace status + scopeHint | `version_id: string` | R | `[read ChatTools:452]` |
| 44 | `derive_contract_impact` — read-only ARCH-018 contractImpact hint (NONE / SCHEMA_STALE / …) | `version_id: string`, `package_id?, target_id?: string`, `environment?: string=dev` | R | `[read ChatTools:460]` |
| 45 | `derive_dataset_schema` — discover schema from JDBC table / SQL query / sample upload | `connector_instance_id: string`, `source_type: enum(table, query, sample)`, `table_name?, query?, sample_data?: string`, `sample_format?: enum(CSV, JSON)`, `environment?: string` | R | `[read ChatTools:472]` |
| 46 | `create_dataset_from_discovery` — register a dataset from a discovery result (direct write) | `connector_instance_id, name: string`, `description?: string`, `fields: array`, `classification?, discovery_method?: string`, `discovery_proof?: object`, plus time-dim/asof/sql/source-table fields (`ChatTools:505-525`) | X | `[read ChatTools:496]` |
| — | **NEW (to build) — the op-emitting mutation tier (renames of #16-18, #24, #26-27 to the op-queue contract, §7.3 B)** | | | |
| N1 | `add_blueprint_instance` — add one Blueprint instance; emits `addInstances` op (never writes canonical) | `pipeline_id, blueprint_key, instance_name: string`, `reasoning: string (REQUIRED)` — **NO initial `params` (3-F4); a separate `set_params` follows** | M | `> GUESS:` re-times `plan_add_step` (§7.3 B) |
| N2 | `wire_ports` — connect output→input; emits `mergeWiring` op | `pipeline_id, source_instance_name, source_port, target_instance_name, target_port: string`, `reasoning: string (REQUIRED)` | M | `> GUESS:` re-times `plan_wire_ports` / replaces direct `wire_ports` |
| N3 | `set_params` — set/update params; emits `updateInstance` op | `pipeline_id, instance_name: string`, `params: object`, `reasoning: string (REQUIRED)` | M | `> GUESS:` re-times `plan_set_step_params` / replaces direct `configure_step_params` |
| N4 | `remove_instance` — delete a step + wirings; emits `removeInstance` op | `pipeline_id, instance_name: string` | M | `> GUESS:` replaces direct `remove_step` |
| N5 | `remove_wire` — delete one edge; emits `removeWiring` op | `pipeline_id, source_instance_name, source_port, target_instance_name, target_port: string` | M | `> GUESS:` new (§7.3 B) |
| N6 | `rename_instance` — rename a step + fix references; emits `rename` op | `pipeline_id, instance_name, new_name: string` | M | `> GUESS:` new (§7.3 B) |
| N7 | `set_pipeline_setting` — portless Pipeline Setting op (schedule/rollback); emits `setPipelineSetting` op | `pipeline_id: string`, `setting: enum(schedule_and_triggers, rollback_on_failure, …)`, `config: object`, `reasoning: string` | M | `> GUESS:` re-times `update_pipeline_orchestration` (§7.3 B) |
| N8 | `get_composition_overview` — compact "N steps, M wires, layers, open ports, unresolved schema" summary | `pipeline_id: string`, `version_id?: string` | R | `> GUESS:` new (§7.3 A) |
| N9 | `get_blueprint_op_list` — the Blueprint's declared op-list (schema/row effects, side-outputs) | `blueprint_key: string` | R | `> GUESS:` new; depends on ADR 0012 (§7.3 A) |
| N10 | `validate_structure` — graph-level checks (orphans, cycles, reachability, ports satisfied) | `version_id: string` | R | `> GUESS:` new (some checks exist under other names; §7.3 A) |
| N11 | `validate_configuration` — per-step param/port completeness vs Blueprint contract | `version_id: string`, `instance_id?: string` | R | `> GUESS:` new (§7.3 A) |
| N12 | `validate_plan` — runs the deterministic Builder pre-flight over the STAGING graph (blocks Apply if it won't compile) | `version_id: string` (staging) | R | `> GUESS:` new; depends on ADR 0011/0012/0013 Builder (§7.3 A, §7.13 layer 3) |
| N13 | `validate_sql_expression` — Calcite-validate a derived-column expression or `sql-model` body against input schema | `version_id, instance_id: string`, `expression?, sql?, expectedType?: string` | R | `> GUESS:` new; depends on ADR 0013 (§7.3 A) |

**TOOL COUNT.** **46 distinct live tools** (`ChatTools.java`; the 4 `propose_*` deprecated aliases counted as one row #20
but are 4 registrations — 49 registrations total, 46 distinct contracts) **+ 13 new tools to build** (N1-N13) =
**59 distinct tool contracts** in the target surface. Of the 46 live tools, **11 are direct-write `X`-tier**
(`create_data_source`, `create_connector`, `create_domain`, `create_dataset`, `configure_step_params`,
`update_pipeline_orchestration`, `wire_ports`, `remove_step`, `create_sink_target`, `apply_dq_expectations`,
`create_dataset_from_discovery`) that §7.3 B / §7.15 require re-timing to the op-queue or
Plan→Apply gate; **5 `plan_*` plan-producers**; **1 apply** (`apply_plan`); **2 UI-intent** (`navigate_ui`,
`request_credential_attach`); the rest (**27**) **read-only**.

> RESOLVED (operator 2026-06-16): the N1-N13 names are the snake_case op-emitting / discovery contract names (§7.3 A/B;
> live convention, 0 hyphens). The direct-write `X`-tier is **route-to-queue, NOT removed** (§7.16 #7); non-composition
> entity creation is **universally Plan→Apply-gated** (§7.16 #18).

#### 7.17 B — Backend API surface (every REST/SSE endpoint the workspace requires)

The endpoints the Chat / composition workspace consumes, existing AND to-build. Tagged `[read]` (exists at the cited
`file:line`) or `> GUESS:` (new). Composition mutation endpoints EXIST as REST endpoints but write `CompositionService`
**directly, bypassing the Command Log** (the §7.0 grounding-ledger gap) — they are `[read]` for *route shape* but the
**`composition.*` command-typed path is the to-build delta** (§7.4).

| # | Method · path | Handler · what it does | Status |
|---|---|---|---|
| 1 | `GET /api/v1/versions/{versionId}/composition` | `CompositionController.getComposition` — full CompositionView (instances + wirings) | `[read CompositionController.java:28]` |
| 2 | `POST /api/v1/versions/{versionId}/composition/instances` | `addInstance` — add a sub-pipeline instance (writes service directly) | `[read CompositionController.java:38]` |
| 3 | `PUT /api/v1/versions/{versionId}/composition/instances/{instanceId}` | `updateInstance` — canonical update (params + storageBackend/lakeLayer/lakeFormat) | `[read CompositionController.java:90]` |
| 4 | `PUT /api/v1/versions/{versionId}/composition/instances/{instanceId}/params` | `updateParams` — legacy params-only update (surfaces deprecated keys via header) | `[read CompositionController.java:70]` |
| 5 | `DELETE /api/v1/versions/{versionId}/composition/instances/{instanceId}` | `removeInstance` — remove a step | `[read CompositionController.java:49]` |
| 6 | `PUT /api/v1/versions/{versionId}/composition/instances/reorder` | `reorder` — reorder instances by execution order | `[read CompositionController.java:57]` |
| 7 | `POST /api/v1/versions/{versionId}/composition/wirings` | `wirePort` — create a port wiring | `[read CompositionController.java:101]` |
| 8 | `DELETE /api/v1/versions/{versionId}/composition/wirings/{wiringId}` | `unwire` — remove a port wiring | `[read CompositionController.java:111]` |
| 9 | `PUT /api/v1/versions/{versionId}/composition/instances/{instanceId}/schema` | `updateSchema` — override an instance's output schema | `[read CompositionController.java:119]` |
| 10 | `GET /api/v1/versions/{versionId}/composition/instances/{instanceId}/upstream-schema` | `getUpstreamSchema` — resolved upstream schema for a step | `[read CompositionController.java:127]` |
| 11 | `GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions` | `PipelineController.listVersions` — list versions | `[read PipelineController.java:67]` |
| 12 | `GET …/pipelines/{pipelineId}/versions/{versionId}` | `getVersion` — get a version | `[read PipelineController.java:75]` |
| 13 | `POST …/pipelines/{pipelineId}/versions` | `createRevision` — create a new revision (branch-per-revision, new scope §3 git) | `[read PipelineController.java:84]` |
| 14 | `POST …/pipelines/{pipelineId}/versions/{versionId}/transition` | `transitionStage` — transition a version's stage | `[read PipelineController.java:93]` |
| 15 | `PUT …/pipelines/{pipelineId}/versions/{versionId}/orchestration` | `updateOrchestration` — schedule/catchup/max-runs/policies | `[read PipelineController.java:104]` |
| 16 | `GET /api/v1/tenants/{tenantId}/plans` | `PlanController.listPlans` — list plans (optional `?pipelineId`) | `[read PlanController.java:24]` |
| 17 | `GET …/plans/{planId}` | `getPlan` — get a plan | `[read PlanController.java:34]` |
| 18 | `GET …/plans/{planId}/commands` | `getPlanCommands` — the plan's Command-Log rows | `[read PlanController.java:41]` |
| 19 | `POST …/plans/{planId}/approve` | `approvePlan` — approve (body refs the approving chat message + user) | `[read PlanController.java:52]` |
| 20 | `POST …/plans/{planId}/apply` | `applyPlan` — apply an APPROVED plan (executes planned_commands) | `[read PlanController.java:65]` |
| 21 | `POST …/plans/{planId}/cancel` | `cancelPlan` — cancel a plan (the closest live thing to "reject") | `[read PlanController.java:72]` |
| 22 | `GET /api/v1/tenants/{tenantId}/commands` | `CommandLogController.listCommands` — list commands (optional `?aggregateId`) | `[read CommandLogController.java:20]` |
| 23 | `GET /api/v1/tenants/{tenantId}/chat/sessions` | `ChatController.listSessions` | `[read ChatController.java:37]` |
| 24 | `POST /api/v1/tenants/{tenantId}/chat/sessions` | `createSession` (binds at most one `pipelineId`) | `[read ChatController.java:42]` |
| 25 | `GET /api/v1/tenants/{tenantId}/chat/sessions/latest` | `getLatestSession` (by `?userId`) | `[read ChatController.java:52]` |
| 26 | `GET /api/v1/chat/sessions/{sessionId}` | `getSession` | `[read ChatController.java:61]` |
| 27 | `GET /api/v1/chat/sessions/{sessionId}/messages` | `getMessages` — message history | `[read ChatController.java:66]` |
| 28 | `GET /api/v1/chat/sessions/{sessionId}/facts` | `getSessionFacts` — flattened facts from tool results | `[read ChatController.java:71]` |
| 29 | `POST /api/v1/chat/sessions/{sessionId}/messages` **(SSE, `text/event-stream`)** | `sendMessage` — the agent turn; streams the SSE events of §7.5 | `[read ChatController.java:76]` |
| 30 | `POST /api/v1/versions/{versionId}/schema/recompute` | `SchemaPropagationController.recompute` — propagate schema root→leaf | `[read SchemaPropagationController.java:33]` |
| 31 | `GET /api/v1/versions/{versionId}/schema-graph` | `schemaGraph` — schema dependency graph | `[read SchemaPropagationController.java:38]` |
| 32 | `GET /api/v1/versions/{versionId}/schema-conflicts` | `listConflicts` — the §2 CONFLICT OVERLAY feed | `[read SchemaPropagationController.java:43]` |
| 33 | `POST /api/v1/versions/{versionId}/schema-conflicts/{conflictId}/resolve` | `resolve` — resolve a schema conflict | `[read SchemaPropagationController.java:50]` |
| 34 | `PUT /api/v1/versions/{versionId}/schema/instances/{instanceId}/ports/{portName}/override` | `setOverride` — manual port-schema override | `[read SchemaPropagationController.java:58]` |
| 35 | `DELETE …/ports/{portName}/override` | `clearOverride` — clear an override | `[read SchemaPropagationController.java:67]` |
| 36 | `GET /api/v1/versions/{versionId}/table-contracts/preview` | `StorageContractController.getTableContractPreview` — table-contract preview (logical metadata, NOT data rows) | `[read StorageContractController.java]` |
| — | **NEW (to build) — the endpoints this rebuild requires** | | |
| B1 | `POST /api/v1/chat/sessions/{sessionId}/plans/{planId}/decision` (body `{decision: approve\|modify\|reject}`) | the **plan-decision endpoint** — first-class apply/reject as a transport event, not an inline chat-message hack (n8n `confirmAction`, §7.5 GUESS). Today approval rides as `Plan.approvedByMessageId` + the tenant-scoped `…/plans/{planId}/approve`+`/apply`+`/cancel` (#19-21); this is the **session-scoped, single-call** plan decision the workspace needs | `> GUESS:` new (§7.5, §7.16 #10) |
| B2 | `composition.addInstance` / `removeInstance` / `updateInstance` / `wirePorts` / `removeWiring` / `renameInstance` — the **six `composition.*` command types** registered on `CommandService` so composition mutations are first-class, idempotent, Command-Logged commands (NOT a new REST route — new command-bus handlers behind `apply_plan`) | closes the §7.0 / §7.4 grounding gap: today composition writes bypass the Command Log (#2-9 write `CompositionService` directly) | `> GUESS:` new (§7.4, §7.16 #9) |
| B3 | `POST /api/v1/versions/{versionId}/composition/preview` (body `{inputSample, steps:[instanceRef…1..N]}`) → `{rows: top-N, schema: [{name,type,nullable,pii}]}` | the **design-time per-step preview endpoint** — `(input sample, chain steps 1..N) → top-N rows + schema` that #4's rich sql-builder needs. **THIS DOES NOT EXIST**: the live `preview_*` tools (#36-44, `preview_dataset_landing` / `preview_table_contract` / `preview_runtime_projection` / `check_table_contract_readiness`) return **logical contract / storage / readiness metadata, NOT executed sample data rows**. No endpoint anywhere returns top-N rows flowing through chained composition steps | `> GUESS:` new — confirmed non-existent (§4 dependency) |

**ENDPOINT COUNT.** **36 existing endpoints** the workspace consumes (`[read]`, across CompositionController ×10,
PipelineController ×5, PlanController ×6, CommandLogController ×1, ChatController ×7 incl. the SSE turn,
SchemaPropagationController ×6, StorageContractController ×1) **+ 3 to-build** (B1 plan-decision endpoint; B2 the six
`composition.*` command types behind `apply_plan`; B3 the design-time per-step row-preview endpoint) = **39 in the target
surface**. The single most load-bearing **to-build** finding is **B3**: a design-time sample-row preview is required by
#4's sql-builder and **has no live analogue** — every existing `preview_*` surface is contract/metadata, not data rows.

> RESOLVED (operator 2026-06-16): **B1 = the session-scoped `POST …/plans/{planId}/decision` endpoint** (§7.16 #10 — the
> LangGraph4j `interruptBefore` resume / n8n `Command({resume})` analogue), NOT a reuse of the tenant-scoped
> `…/approve|cancel`. **B2 = the six `composition.*` command-type strings** (§7.4 / §7.16 #9). B3's request/response shape
> is the proposed design; B3 still needs the deterministic Builder + a sample-execution path (ADR 0011/0012/0013) to
> produce real rows — until then it returns *projected schema*, not *sampled rows* (a partial B3, consistent with the
> interim `validate_plan`, §7.16 #15).

---

### 7.18 n8n capability dispositions (PIECE B — added 2026-06-16, PRODUCER pass)

> Status: NEW section — the **n8n-as-yardstick disposition pass** the SPEC-GATE completeness pass requires. Yardstick =
> the n8n capability set as catalogued in the `docs/ui/chat-prompts/` fragments (the prior standalone
> `N8N-AI-BUILDER-ARCHITECTURE.md` + `N8N-PROMPTS-REFERENCE.md` summaries are removed; the fragments cite the raw source). EVERY n8n capability is
> dispositioned for PULSE: **Adopted** (cite where in §7) / **Deferred** (rationale + when) / **Ignored** (rationale why
> PULSE doesn't need it). An UNDISPOSITIONED capability is itself a SPEC-GATE finding, so this table covers them all.
>
> **DEFERRED (needs Vertex merge / operator voice):** the precise *agent prompt wording* (the **7-stage** prompts in
> `chat-prompts/01-system-prompts.md` + the category best-practice guides in `chat-prompts/03-best-practices.md`) waits on
> the Vertex merge + operator voice review (§7.14 GUESS), so the prompt-*shape* is Adopted but the verbatim-*text*
> voice-pass is Deferred. (The text is now DRAFTED in the fragments; "Deferred" here means the operator voice review, not
> "unwritten.")

| # | n8n capability (yardstick §) | Disposition | Where in §7 / rationale + when |
|---|---|---|---|
| 1 | **5-agent LangGraph staged decomposition** (n8n: supervisor/discovery/builder/planner/responder, §1.1-1.2) | **Adopted (shape, EXPANDED to 7, as a real graph — ADR 0025)** | §7.0-7.1 adopt staged decomposition as a **LangGraph4j `StateGraph` (Option A)** — real graph nodes + supervisor/router + conditional edges, NOT prompt-modes over the one loop (the phase-gated single loop is the named fallback). PULSE expands n8n's 5 to the **LOCKED 7** (adds Configure + Provision; `chat-prompts/01-system-prompts.md` §F). Resolved by ADR 0025 (§7.16 #1). |
| 2 | **Operations-queue + atomic apply** (typed ops accumulate; one `process_operations` drains atomically, §2.2-2.3) | **Adopted** | §7.4 (the `PlanOperation` union + per-turn op-queue + `process_operations` superstep) + §7.10 (state layers). The single biggest mechanic borrowed. |
| 3 | **Tool emits typed op, never writes graph directly** (§2.3) | **Adopted** | §7.3 B (every mutation tool emits exactly one typed op; only `apply_plan` writes canonical) + §7.17 A N1-N7. |
| 4 | **Read tools see PENDING staging state** (`getCurrentWorkflow` = applyOps(clone, pendingOps), §1.3) | **Adopted** | §7.1 ("Read tools see PENDING staging state" invariant). |
| 5 | **Chunked / SSE streaming of the build** (§3.1) | **Adopted** | §7.5 — PULSE already streams SSE (`ChatController.java:76`); n8n's *direction* (the newer `instance-ai` SSE + confirm endpoint) matches PULSE's existing transport. |
| 6 | **Full candidate_graph snapshot per superstep (coarse wire, client reconcile)** (§3.4) | **Adopted** | §7.5 (`candidate_graph` event = full staging composition per superstep) + §7.6 (client-side reconcile). |
| 7 | **`compareWorkflowsNodes` / content-based diff → added/modified/removed** (§4.2) | **Adopted** | §7.7 (`compareGraphs` content-based equality over a comparable projection; drives ghost borders + the "Review N changes" banner). |
| 8 | **Ghost/candidate node rendering + canvas highlight classes** (§4.3, §8.2) | **Adopted** | §7.6 + §7.9 (the BLEND — staging ghosts on `@xyflow/react`, status-colored borders). |
| 9 | **"Review N changes" banner = the diff** (§4.3) | **Adopted (timing inverted)** | §7.7 (c) + §7.9 — PULSE inverts n8n's *post-hoc* timing: the diff is computed PRE-commit and **IS the Plan Preview** (n8n's is a post-mutation audit). |
| 10 | **Snapshot-before-edit + Restore/revert** (§4.1, §5.3) | **Adopted** | §7.8 + §7.11 (per-turn canonical snapshot = a LangGraph4j checkpoint in the `langgraph4j-postgres-saver` Postgres checkpointer, ADR 0025 §3 — no separate `chat_turn_snapshots` table; revert = restore the checkpoint, structurally cheaper than n8n because canonical is write-locked behind Apply). |
| 11 | **Client reconcile preserves manual node positions; auto-layout after** (§5.2) | **Adopted** | §7.6 steps 2 + 5 (preserve Customer positions; `autoLayout` only on STRUCTURAL change). |
| 12 | **Dagre tidy-up / auto-layout (`@dagrejs/dagre`), structural-change-only** (§7B) | **Adopted** | §7.6 step 5 (Dagre, only on add/remove/wire, not on pure param edits). |
| 13 | **Plan-mode interrupt → approve / modify / reject (HITL)** (§1.5) | **Adopted** | §7.2 (explicit plan-mode = PULSE's already-LOCKED Plan→Apply gate; approve/modify/reject HITL) + B1 plan-decision endpoint (§7.17 B). |
| 14 | **Clarifying-`questions` interrupt (one HITL question)** (§3.3) | **Adopted** | §7.5 (`questions` SSE event) + §7.1 discovery stage (ask the ONE missing fact; Absolute Rule #1). |
| 15 | **Three-layer validation (Zod / semantic / programmatic), errors-as-observations** (§6) | **Adopted (stronger)** | §7.13 — PULSE's typed ports + deterministic Builder make layers 2-3 stronger than n8n's connection-type inference. |
| 16 | **Tool descriptions carry the contract (`initialParametersReasoning`)** (§6, PROMPTS §2) | **Adopted** | §7.3 B + §7.14 (the REQUIRED `reasoning` field on every mutation op — "why these params/ports"). |
| 17 | **Catalog grounding = RAG-as-tools (node-search / node-details)** (§1.4, §8.3 rec 5) | **Ignored (deliberate divergence)** | PULSE has ~50 Blueprints (not ~500 nodes), so LOCKED §6 chose **dump-all into the cached prompt** (§7.14) — no `blueprint-search` retrieval tool. This is a dispositioned divergence: catalog-in-prompt, not catalog-as-tools. Fallback to hybrid only if tokens bloat (§7.14 GUESS). |
| 18 | **Best-practices guides (18 per-technique guides via `get_documentation`)** (§3 PROMPTS, §6) | **Adopted (shape) / Deferred (text)** | §7.14 adopts the *shape* consolidated to **5 per-CATEGORY guides** (Ingestion/Transform/Modeling/DQ/Orchestration), data-engineering voice. The verbatim guide *text* is Deferred to the operator-voice pass (§7.16 #17). |
| 19 | **Per-stage system prompts (n8n 5-agent prompt split, `prompt().section().build()`)** (PROMPTS §1) | **Adopted (shape, 7 stages) / Deferred (voice)** | §7.14 + `chat-prompts/01-system-prompts.md` adopt the LOCKED **7** per-stage prompt assemblies (Router/Discovery/Build/Configure/Provision/Planner/Responder); the prompt text is DRAFTED in fragment `01`, with the verbatim DE-voice review Deferred to the Vertex merge + operator voice pass (§7.16 #17, this section's DEFERRED note). |
| 20 | **Context-tag injection (`<current_workflow_json>` etc., stripped before delivery)** (PROMPTS §4) | **Adopted** | §7.14 context-injection wrappers (`<current_composition>` / `<dataset_schemas>` / `<selected_step>` / `<run_status>` + the plain-text conversation-summary; stripped from streamed text). |
| 21 | **Token-based history auto-compaction (`messages_compacted`)** (§7) | **Deferred** | §7.11 specifies it as the target; the live loop has no compaction yet. **When:** after the core staged loop + op-queue land; the threshold is operator-set (§7.16 #13). |
| 22 | **Per-session graph checkpointer (LangGraph `MemorySaver`)** (§1.1) | **Adopted (LangGraph4j `PostgresSaver` — ADR 0025)** | §7.1 — PULSE keeps per-session message history (`chat_sessions`/`chat_messages`) and adopts the **`langgraph4j-postgres-saver`** Postgres checkpointer (the `MemorySaver`→`PostgresSaver` analogue) for per-turn snapshot/abort/restore; no separate `chat_turn_snapshots` table (§7.16 #4). |
| 23 | **Graceful abort/cancel (discard, append "aborted", no throw)** (§5.3, §7) | **Adopted** | §7.5 cancellation + §7.11 (abort discards staging, appends "aborted", never throws; canonical untouched). |
| 24 | **Undo bracket — one AI edit = one undoable unit** (§5.3) | **Adopted** | §7.11 (one chat turn = one undo transaction = one Command-Log entry-group under one `planId`; undo = **restore the checkpoint snapshot**, not inverse plan — ADR 0025 §3, §7.16 #13). |
| 25 | **Concurrent-edit handling (last-writer-wins; the cautionary tale)** (§5.4, §8.4) | **Adopted (PULSE's safer answer) — BLOCKED** | §7.12 — PULSE BLOCKS manual canvas edits while a plan is staged (one writer-path = Apply), sidestepping n8n's last-writer-wins hazard by construction. Resolved to BLOCKED (REBASE is a v2 enhancement, §7.16 #14). |
| 26 | **Live-mutate onto the canonical graph (review post-hoc)** (§0, §8.1) | **Ignored (deliberate divergence)** | §7.9 + §8.1 row 1 — PULSE **declines** live-mutate; the canonical graph is write-locked behind Apply (true no-silent-write). The single biggest n8n behavior PULSE rejects by design. |
| 27 | **Multi-agent subgraph generation (`createMultiAgentWorkflowWithSubgraphs`)** (§1.2) | **Ignored** | LOCKED §6 ("no subgraphs"); n8n itself trimmed it (PR #27925). PULSE's staged-modes-over-one-loop (row 1) makes subgraphs unnecessary. |
| 28 | **`web-fetch` tool + `web_fetch_approval` HITL** (§3.3) | **Ignored** | PULSE composition grounds in the Blueprint catalog + the tenant's datasets/SOR, not arbitrary web fetches; no general-web tool in the composition surface. Not needed for the data-engineering domain. |
| 29 | **Drag-to-map / expression-mode field toggle in node config (NDV)** (§7C-7D) | **Deferred** | This is the §4 rich sql-builder / bespoke-panel territory (`SPEC-construct-library.md`), out of THIS §7 Chat-contract scope. **When:** the bespoke-panel / sql-builder spec (#4), which also needs the B3 design-time row-preview endpoint (§7.17 B). |
| 30 | **Vertical sub-node / cross-cutting-attachment rendering (DQ/policy/obs)** (§7A, §8.5) | **Deferred** | Adopt the *vertical-attachment rendering grammar* but NOT n8n's sub-node *composition model* (PULSE DQ/policy/obs are Blueprint-declared obligations, not composable op-list members — §8.5 rec 11). **When:** the canvas-rendering spec; the data-contract (obligations-on-op) is already PULSE's model. |
| 31 | **Anti-substitution prompt rule ("use the model name EXACTLY")** (PROMPTS §1 Builder) | **Adopted (remapped)** | §7.14 — PULSE's analogue is the "report the ACTUAL composition/params, never assumed config" guardrail + SecretRefs-never-values; n8n's literal model-name rule maps to PULSE's report-actual discipline. |
| 32 | **LangSmith tracing (`code-workflow-builder` project)** (§1.1) | **Ignored** | PULSE has its own Command-Log + observability stack; LangSmith is n8n's vendor tracer, not a PULSE dependency. Provider-side tracing is a deploy-config concern, not a spec contract. |
| 33 | **Prompt-cache markers (`cache_control: ephemeral`)** (§1.1, PROMPTS §1) | **Adopted (provider-mapped) / Deferred (exact marker)** | §7.14 marks the dump-all catalog block for provider prompt-caching. The exact marker (`cache_control: ephemeral` for Anthropic/Vertex vs the OpenRouter equivalent) is Deferred to the Vertex merge (§7.14 GUESS, §7.16 #16). |

**DISPOSITION-TABLE COUNT.** **33 rows.** Breakdown: **Adopted 22** (rows 2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,20,22,23,
24,25,26-as-decline⟂,31 — counting the pure-Adopted + the "Adopted (stronger/remapped/inverted/as-a-table)" variants;
**18 are clean Adopted** and **4 are Adopted-with-a-twist**: 9 inverted-timing, 15 stronger, 22 as-a-table, 31 remapped) ·
**Adopted-shape/Deferred-text 4** (rows 1,18,19,33 — shape Adopted, the network-agents/guide-text/prompt-text/cache-marker
Deferred) · **Deferred 3** (rows 21,29,30) · **Ignored 5** (rows 17,26,27,28,32). Counting each row by its *primary*
disposition: **Adopted ≈ 23, Deferred ≈ 5 (incl. the shape/text splits' deferred half), Ignored = 5.** The two
deliberate-divergence **Ignored** rows that most define PULSE vs n8n: **#17** (dump-all catalog, not RAG-as-tools) and
**#26** (write-lock behind Apply, not live-mutate).

> GUESS: the Adopted/Deferred/Ignored *primary* labeling of the 4 "shape Adopted / text Deferred" hybrid rows
> (1,18,19,33) is a judgment call — they are counted as Adopted-with-deferred-text above. A SPEC-GATE reviewer may prefer
> to count them strictly as Deferred until the text lands; the row content is unambiguous either way.

---

### 7.19 The precise prompts — SUPERSEDED by `chat-prompts/01-system-prompts.md`

> **SUPERSEDED (2026-06-16).** This subsection was the killed agent's PIECE-C draft: the actual prompt TEXT for **five**
> per-stage system prompts (router/discovery/composer/planner/responder) authored from the n8n PROMPTS-REFERENCE
> **summary**, plus the op-tool description strings, the context-tag wrappers, and the plan-mode contract. It is
> **replaced in full by the fragment `docs/ui/chat-prompts/01-system-prompts.md`**, which (a) is authored from the REAL
> n8n source, not the summary, with a §0 divergence log; (b) carries the **LOCKED 7 stages** — Router · Discovery ·
> Build/Composer · Configure · Provision · Planner · Responder (this draft had only 5: it lacked **Configure** and
> **Provision**, and its Discovery was the summary-grounded "ask when underspecified" rather than the LOCKED **single
> judicious-ask**); and (c) grounds the Planner's in-chat DAG on the EXISTING `chat-dag` markdown renderer
> (`parsePipelineSteps`/`parsePipelineTable`, `chat-panel.tsx:563`, `PulseSystemPrompt.java:35`), not an invented `graph`
> field. The op-tool description strings (old §7.19 B), the context-tag wrappers (old §7.19 C), and the plan-mode output
> contract (old §7.19 D) now live, reconciled, in fragments `02-tools-and-context.md` and `06-ops-queue-apply-diff.md`.
>
> Read `chat-prompts/01-system-prompts.md` for the authoritative prompt text. The stale draft below is removed; only
> this pointer remains so links to "§7.19" resolve.

_(The detailed prompt text, op-tool description strings, and context-tag wrappers that stood here are removed; they now
live, authored from the real n8n source and reconciled to the LOCKED 7 stages, in `chat-prompts/01-system-prompts.md`
(prompts), `chat-prompts/02-tools-and-context.md` (tool strings + context tags), and `chat-prompts/06-ops-queue-apply-diff.md`
(op contracts). The Plan-mode output structure that closed the old §7.19 is below, retained because it pins the
Plan Preview → Apply Plan data contract.)_

#### 7.19 D — Plan-mode output structure (the Plan Preview → Apply Plan contract)

The planner stage (`chat-prompts/01-system-prompts.md` §6) emits this structure; it IS PULSE's Plan Preview → Apply Plan gate (§7.2, §6 LOCKED;
ARCH-009). Adapted from n8n's `{summary, trigger, steps, additionalSpecs}` `[read PROMPTS-REFERENCE §5]`, with PULSE's
medallion layer + blueprintKey on each step and NO credentials field (the key data-engineering divergence):

```json
{
  "summary":  "1-2 plain-language sentences: what data comes in and what curated output comes out.",
  "trigger":  "what starts it, plain language (e.g. 'runs after the daily loan_master file lands').",
  "steps": [
    { "ordinal": 1,
      "title": "Ingest loan_master",
      "blueprintKey": "FileIngestion",          // internal — renders the canvas ghost, NOT shown in user text
      "medallionLayer": "bronze",                // bronze | silver | gold | control
      "description": "Pull the daily loan_master file into the bronze layer.",   // non-technical
      "reasoning": "why this step / these params — surfaced from the op's reasoning field" }
  ],
  "additionalSpecs": [ "only non-obvious notes; NEVER secret values; NEVER deploy/promote instructions" ]
}
```

Then **interrupt for HITL** (n8n `interrupt()` `[read PROMPTS-REFERENCE §5]`): the user **approves** (Apply Plan →
§7.9 atomic commit, one Command-Log transaction), **modifies** (the correction re-enters the composer; the staging
graph is rebuilt, not appended), or **rejects** (discard the staging graph; restore the snapshot, §7.8). WHEN the user
modifies or rejects THE SYSTEM SHALL NOT write the canonical graph or the Command Log (§7.2). The `description`/`title`
text is user-facing and stays non-technical (no blueprintKey, no ids); `blueprintKey`/`medallionLayer` are internal,
used only to render the canvas ghost (§7.9). NO `credentials`/`auth` field exists — PULSE secrets are SecretRefs
resolved at build/package time, never in a plan `[read ADR 0023; PulseSystemPrompt:140-162]`.

> RESOLVED (operator 2026-06-16, DEFAULT): per-step `reasoning` surfaced into the Plan Preview (so the user sees "why"
> per step) is PULSE's addition — n8n's plan steps carry `suggestedNodes` but not a per-step reasoning string. It is wired
> from the REQUIRED `reasoning` field on each mutating op (§7.3 B; tool strings in `chat-prompts/02-tools-and-context.md`).
> The exact `previewData` key names map to `Plan.previewData` (§7.2 RESOLVED) — reconcile against the live Plan Preview
> renderer at build time (no new contract).

**§7.19 grounding tally — SUPERSEDED.** The prompt-text / tool-string / context-wrapper inventory that this tally
counted (the killed agent's **5**-prompt PIECE-C draft) is moved to the fragments and re-counted there against the
**LOCKED 7** stages: see `chat-prompts/01-system-prompts.md` (7 stage prompts + shared preamble), `02-tools-and-context.md`
(tool strings + context tags), `06-ops-queue-apply-diff.md` (op contracts). The fragments carry their own grounding
tallies and `> GUESS:` indexes. Only the **Plan-mode output contract** (§7.19 D above) is retained here — it pins the
Plan Preview → Apply Plan data shape and is referenced by §7.2/§7.9. Its one remaining open item is the narrow
`previewData` key mapping (the GUESS just above), not the prompt text.
