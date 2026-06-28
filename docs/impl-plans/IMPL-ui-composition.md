# IMPL-ui-composition — Implementation Plan: the Chat + composition-workspace layer (spec #3, as a LangGraph4j multi-stage graph)

> **Build plan, not code.** Turns spec #3 [`docs/ui/SPEC-ui-composition.md`](../ui/SPEC-ui-composition.md)
> (the §7 Chat contract spine + the 7 chat-prompt fragments `docs/ui/chat-prompts/01-…07-…`) into a build
> roadmap. The keystone build-shape decision is **ADR 0025** (`docs/adr/0025-chat-orchestration-langgraph4j-multistage-graph.md`):
> the 7 stages are realized as a **LangGraph4j `StateGraph`** (real graph nodes + supervisor/router + conditional
> edges + an `interruptBefore` plan gate + the `langgraph4j-postgres-saver` checkpointer-as-undo), NOT prompt-modes
> over the single tool-loop. **This is the plan, NOT the code.** It sequences the §7 worklist + the fragment
> sub-specs into dependency-ordered build phases.
>
> **Vocabulary (enforced, per §7's defined vocab):** Customer (never "user"), op / op-list (never "recipe"),
> params (never "settings"), Blueprint, dataset, port, Command Log, Plan Preview, Apply Plan,
> **CANONICAL graph** (persisted `sub_pipeline_instances` + `port_wirings` for the active version),
> **STAGING graph** (the candidate composition a turn builds, never persisted until Apply).
>
> **Evidence tags:** `[read]` = confirmed at the cited `file:line` in this repo (LIVE code/config) or a LOCKED
> ADR; `[spec]` = the owning §7 section / fragment; `[report]` = transcribed from a cross-plan IMPL or ADR.
> Every anchor below was opened, not assumed.
>
> **What this layer REPLACES (verified):** PULSE Chat today is a **single OpenRouter/Vertex `/chat/completions`
> tool-loop** — `[read]` `backend/src/main/java/com/pulse/chat/service/ChatService.java:39` (`MAX_TOOL_ROUNDS = 30`),
> `:649` (the `while (toolRounds < MAX_TOOL_ROUNDS)` loop), `:1089` (the `ConversationPhase`-gated prompt assembly),
> `:576` (`buildSystemPrompt`). There is **no `StateGraph`, no supervisor, no `AgentState`, no `interrupt`**. The
> live tool registry is `[read]` `ChatTools.java` and the executor switch `[read]` `ChatToolExecutor.java:246`
> (the `switch (toolName)`). This plan builds the graph **incrementally behind the "first pipeline composed +
> built end-to-end" milestone** (ADR 0025 §5) — NOT a big-bang orchestration rewrite.
>
> **Upstream dependencies this plan CONSUMES (does NOT build):** the deterministic Builder
> ([`IMPL-builder.md`](IMPL-builder.md), specs #1/#2 — `validate_plan`'s real pre-flight), the catalog + V153
> ([`IMPL-catalog-seed.md`](IMPL-catalog-seed.md) — the dump-all Blueprint awareness + `get_blueprint_op_list`),
> and Calcite ([`IMPL-calcite-sql.md`](IMPL-calcite-sql.md) — `validate_sql_expression`). Migration head is
> **V151** `[read]` `backend/src/main/resources/db/migration/` (V151 is the last file; no V152/V153 on this branch);
> **this plan authors no migration** — composition becomes Command-Logged via new command-bus handlers, not a schema
> change (§7.4; the checkpointer owns its own Postgres tables per ADR 0025 §3).

---

## 1. SCOPE — what gets built

The composition workspace + app-wide Chat is rebuilt as a **LangGraph4j multi-stage orchestration graph** with a
**plan-gated, op-queue staging** mechanic. Concretely, nine capability blocks:

1. **The LangGraph4j orchestration foundation** — add `langgraph4j-core` + `langgraph4j-postgres-saver` (Gradle);
   the `AgentState` carrier (the PULSE channels: `CompositionView` + op-queue + phase + plan-lifecycle, mapped from
   n8n's `ParentGraphState` `[spec]` fragment 07 §1.3); the `StateGraph` wiring of the **7 stages as nodes**
   (router · discovery · composer · configure · provision · planner · responder) + a deterministic
   `route_next_phase`; the `interruptBefore` plan-approval gate; the checkpointer-as-snapshot-store `[spec]` ADR 0025
   §1/§3, fragment 07 §1.1-1.4.
2. **The Vertex provider adapter** — the thin node→`LlmEndpointService` adapter so graph nodes call PULSE's Vertex
   path (preserving thought-signature + structured tool-replay); ADD `LlmSurface.CHAT_CHEAP` + the
   `pulse.llm.cheap-model` / `pulse.llm.vertex.cheap-chat-model` keys; wire the per-stage model matrix `[spec]`
   ADR 0025 §2/§4, fragment 04.
3. **The op-emitting mutation tier** — the snake_case tools (`add_blueprint_instance` [NO initial params, 3-F4] /
   `wire_ports` / `set_params` / `remove_instance` / `remove_wire` / `rename_instance` / `set_pipeline_setting`),
   the `PlanOperation` union, the per-turn op-queue + reducer, the single atomic `process_operations` drain to the
   STAGING graph, the read-tools-see-staging invariant `[spec]` §7.3 B/§7.4, fragments 05 §C.3 / 06 §E.
4. **First-class Command-Logged composition + the universal Plan→Apply gate** — register the six `composition.*`
   command types behind `apply_plan`; route the direct-write `X`-tier tools (composition AND entity-provisioning
   `create_*`) through Plan→Apply; the session-scoped `…/plans/{planId}/decision` endpoint; BLOCKED concurrency
   while staged `[spec]` §7.4/§7.9/§7.12/§7.15/§7.16 #9/#18, fragment 06 §F.
5. **Streaming-to-canvas + the diff** — the new SSE events (`tool_progress` / `candidate_graph` / `questions` /
   `plan` / `messages_compacted`, all underscore); the `compareGraphs` content-equality diff; the "Review N changes"
   banner = the Plan Preview `[spec]` §7.5/§7.7, fragments 05 §C / 06 §G.
6. **Client reconcile + Dagre auto-layout** — the React Flow ghost-node reconcile (preserve manual positions); add
   `@dagrejs/dagre` (frontend); structural-change-only re-layout + seed-with-positions + grid-snap `[spec]`
   §7.6, fragment 07 §3.
7. **Snapshot / revert** — per-turn canonical snapshot as a checkpoint; reject/cancel/restore = drop-staging or
   checkpoint-restore; truncate-chat-to-anchor; token-based history auto-compaction `[spec]` §7.8/§7.11,
   fragment 07 §2.
8. **The 7 per-stage prompt assemblies + dump-all catalog + 5 category guides** — split the one concatenated
   `buildSystemPrompt` into the 7 stage prompts (fragment 01), the dump-all cached catalog block, the 5
   per-category best-practice guides (fragment 03), and the per-turn context-tag wrappers (fragment 02) `[spec]`
   §7.14.
9. **The new discovery/validation read tools** — `get_composition_overview`, `get_step_schema` (rename of live
   `get_upstream_schema`), `get_blueprint_op_list`, `validate_structure` / `validate_configuration` /
   `validate_plan` / `validate_sql_expression` `[spec]` §7.3 A/§7.17 A N8-N13.

**Explicitly OUT of scope here** (owned by other specs/plans, this plan only consumes/forward-refs them):
- The deterministic Builder, the 32 op handlers, schema propagation → #1/#2 ([`IMPL-builder.md`](IMPL-builder.md)).
  `validate_plan` runs **interim checks** until that lands (§7.16 #15).
- The V153 op-list content + Blueprint catalog rows the dump-all reads → #5 ([`IMPL-catalog-seed.md`](IMPL-catalog-seed.md),
  the SINGLE owner of V153). `get_blueprint_op_list` depends on the declared `schema_behavior` it writes.
- The Calcite schema-deriving validator behind `validate_sql_expression` → #6 ([`IMPL-calcite-sql.md`](IMPL-calcite-sql.md),
  `CALCITE-PHASE-2`).
- The bespoke node panels / rich sql-builder / B3 design-time row-preview endpoint → #4 (`SPEC-construct-library.md`,
  `IMPL-construct-library.md`); §7.18 rows 29-30 defer these. The verbatim DE-**voice** review of the prompt text →
  the operator-voice content pass (§7.16 #17), NOT a build decision.

---

## 2. PREREQUISITES — resolve before / alongside the build

The §7.16 residual build-decisions, the cross-plan dependencies, and the live-vs-spec deltas this plan must honor.
**Count: 10.**

| # | Prerequisite | Owner / source | Blocking for | Status |
|---|---|---|---|---|
| P1 | **`langgraph4j-core` + `langgraph4j-postgres-saver` are NOT in the build** — neither dep is declared (`[read]` `backend/build.gradle.kts` has `calcite-core`/`calcite-babel:99-100` but **no `langgraph4j`** anywhere). The Postgres checkpointer needs its own tables (the checkpointer owns them; no PULSE migration) | this plan | the WHOLE orchestration foundation (Phase 1) | NOT in repo — confirm the `langgraph4j` version (ADR 0025 names v1.8.x) + that `langgraph4j-postgres-saver` matches the running Postgres 16 |
| P2 | **`LlmSurface.CHAT_CHEAP` + the cheap-tier config keys do NOT exist** — `[read]` `LlmSurface.java:3-10` is exactly `{CHAT, CHAT_REASONING, STORY_GENERATION, SCHEMA_INFERENCE, DQ_READINESS, COBOL_DISCOVERY}` (no `CHAT_CHEAP`); `[read]` `application.yml:88-115` has **no** `pulse.llm.cheap-model` / `vertex.cheap-chat-model`. The dead `schema-model`/`schema-inference.model` keys (`:108-113,119-126`) are explicitly **NOT** the cheap tier (ADR 0011 retired model schema-inference) | this plan + ADR 0025 §2 | the per-stage model matrix (Phase 2) | NOT in repo — `[read]` `application.yml:110-111` already names the exact to-add keys |
| P3 | **The six `composition.*` command types do NOT exist** — composition mutations bypass the Command Log: the direct-write tools call `CompositionService` from the executor (`[read]` `ChatToolExecutor.java:277` `configure_step_params`, `:279` `wire_ports`, `:280` `remove_step` → `compositionService` field `:57`). The live pipeline types are `pipeline.create/.update/.delete/.transition/.createRevision` `[read]` `PipelineCommandHandlers.java:19-23` (referenced by §7.0) | this plan | first-class Command-Logged Apply (Phase 4) | to-build; `setName`/`setPipelineSetting` fold into `pipeline.update` (§7.16 #9), so only **6** new types |
| P4 | **#1/#2 deterministic Builder (`validate_plan` real pre-flight)** — `[report]` [`IMPL-builder.md`](IMPL-builder.md) Phases 1-7; the op-engine + 32 handlers + anchor oracle | #1/#2 | `validate_plan`'s **full** compile pre-flight (§7.13 layer 3) + B3 real sample rows | NOT ready — interim checks (`validate_structure` + `validate_configuration` + `check_table_contract_readiness` `[read]` `ChatTools.java:424`) ship first (§7.16 #15) |
| P5 | **#5 catalog + V153 (dump-all awareness + declared op-lists)** — `[report]` [`IMPL-catalog-seed.md`](IMPL-catalog-seed.md) owns `V153__builder_op_lists_and_param_tiering.sql` (head is V151, no V152/V153 `[read]` migration dir) | #5 | the dump-all tight-entry catalog block (Phase 8) + `get_blueprint_op_list` (Phase 9, needs declared `schema_behavior`) | catalog GATE-CLEAN; V153 authored by catalog-seed — this plan CONSUMES it |
| P6 | **#6 Calcite (`CALCITE-PHASE-2`) for `validate_sql_expression`** — `[report]` [`IMPL-calcite-sql.md`](IMPL-calcite-sql.md); the parse-only validator (`ExpressionValidationService`) is grown into a schema-deriving one | #6 | the schema-returning branch of `validate_sql_expression` ONLY (not its parse-only check) | NOT ready — ship a parse-only / declared check until P6 lands |
| P7 | **Diff content-equality projection fields** — the `compareGraphs` "modified" projection (§7.7 GUESS, fragment 06 §G GUESS). DEFAULT: `{name, blueprintKey, blueprintVersion, params, lakeLayer, lakeFormat, storageBackend}`; **exclude `dqExpectations` + `executionOrder`** to avoid noisy diffs | §7.16 #12 (DEFAULT) | Phase 5 (the diff) | DEFAULT given; confirm against `SubPipelineInstance` fields `[read]` `SubPipelineInstance.java:15-74` (per §7.0) |
| P8 | **Node-position store** — `SubPipelineInstance` has **no x/y column** (§7.6/§7.16 #11). DEFAULT: client-side position map keyed by `instanceRef` during a turn, persisted to instance metadata at Apply; Dagre on structural change only | §7.16 #11 (DEFAULT) | Phase 6 (auto-layout) | DEFAULT given; no schema change — instance-metadata write at Apply |
| P9 | **AgentState channel names + the op-queue reducer semantics** — the exact PULSE channel set the `StateGraph` threads (n8n `ParentGraphState`→`AgentState`): `compositionView`, `opQueue` (append/clear reducer), `phase`/`nextPhase`, `planOutput`/`mode`/`planDecision`/`planFeedback`/`planPrevious`, `messages`, `coordinationLog` `[spec]` fragment 07 §1.3 | §7.16 #4, ADR 0025 §1 | Phase 1 (the carrier) | RESOLVED to LangGraph4j `AgentState`; pin the Java field names + reducers in Phase 1 |
| P10 | **Catalog tight-entry schema + prompt-cache markers** — the dump-all entry shape (`name · category · layer · ports · key-params`) vs today's `blueprint_key + 80-char description` only (`[read]` per §7.14, `ChatService.java:1081`); the `cache_control` marker (the live request body carries **none** — `[read]` `ChatService.java:649-662` per fragment 04 §6) | §7.16 #16 (DEFAULT) | Phase 8 (cached catalog) | DEFAULT + fallback named; prompt-cache marker stays a forward item (fragment 04 §6 confirms unimplemented on `cg-env-transition`) |

> **Dependency notes the plan honors:** `validate_plan`'s **real** compile pre-flight depends on **#1/#2** (P4) —
> interim checks ship first. The dump-all catalog + `get_blueprint_op_list` depend on **#5/V153** (P5). The
> schema-returning half of `validate_sql_expression` depends on **#6** (P6). **None of P4-P6 block the gating
> milestone** ("first pipeline composed + built end-to-end") — the workspace ships pre-Builder with interim
> validation (§7.16 #15), and the first pipeline is a closed-vocab composition that does not need `sql-model`.

---

## 3. BUILD PHASES (ordered, dependency-first)

> **Ordering principle:** the orchestration foundation (Phase 1) is the spine — every stage node, the op-queue, the
> plan gate, and the checkpointer hang off `AgentState`. The Vertex adapter + `CHAT_CHEAP` (Phase 2) is the model
> seam every node calls, so it lands right after the carrier. The op-emitting mutation tier (Phase 3) is the
> mechanic the composer node drives; the Command-Logged Apply + universal gate (Phase 4) is the *only* canonical
> writer and depends on the op union from Phase 3. Streaming + diff (Phase 5) renders what Phases 3-4 produce;
> client reconcile + Dagre (Phase 6) consumes the `candidate_graph` event; snapshot/revert (Phase 7) is the
> checkpointer behavior. The prompts (Phase 8) and the new read tools (Phase 9) are the largest parallel fan-out
> and can proceed alongside once the carrier + adapter exist.
>
> **Milestone-discipline constraint (binds ADR 0025 §5 into the phase order, not just prose).** The gating
> milestone is **"first pipeline composed + built end-to-end,"** built incrementally — NOT a big-bang rewrite.
> Concretely: a vertical slice of Phases 1→2→3→4→5→6 for the **composer** path (one Blueprint added → wired →
> params set → Plan Preview → Apply → canonical + Command Log → ghost-to-real → Builder builds it) is the FIRST
> deliverable; the other six stages, the universal entity-provisioning gate, compaction, and the prompt-voice
> polish layer ON TOP of that working slice. The graph grows behind the milestone (Phase order is also the
> incremental-slice order), so the rescue is protected (memory: never a big-bang).

### Phase 1 — The LangGraph4j orchestration foundation (FOUNDATIONAL, serial spine)

**Goal:** the 7 stages run as real `StateGraph` nodes with a shared `AgentState`, deterministic routing, the plan
gate as `interruptBefore`, and the Postgres checkpointer wired — replacing the single `while`-loop. Everything
downstream threads `AgentState`.

- **ADD (Gradle)** `langgraph4j-core` + `langgraph4j-postgres-saver` to `[read]` `backend/build.gradle.kts`
  (neither present today — `:99-100` is calcite only) `[spec]` ADR 0025 "Consequences", P1. Keep the build green.
- **CREATE** `.../chat/orchestration/AgentState.java` — the LangGraph4j `AgentState` carrier (the
  `ParentGraphState`/`Annotation.Root` analogue `[spec]` fragment 07 §1.3 `[read B]`). Channels (P9):
  `compositionView` (the `CompositionView{instances,wirings}` of the active version — `[read]`
  `CompositionService.java:372-375`), `opQueue` (the `PlanOperation[]` with an **append/clear reducer** mirroring
  n8n's `operationsReducer` `[spec]` fragment 06 §B.2), `nextPhase`, `planOutput`/`mode`/`planDecision`/
  `planFeedback`/`planPrevious` (the Plan-lifecycle channels mapping onto `PlanStatus`'s
  `PREVIEW→APPROVED→APPLYING→APPLIED` (`[read]` `PlanStatus.java:4-9` — there is NO `DRAFT` value)), `messages` (the rebuilt LLM list — reuse `[read]` `ChatService.java:574-647`
  history rebuild + the BUG-2026-05-27 orphan-`tool`-message guard `:580-640`), `coordinationLog` (turn-progress
  for deterministic routing) `[spec]` §7.10, fragment 07 §1.3.
- **CREATE** `.../chat/orchestration/CompositionGraph.java` — the `StateGraph(AgentState)` wiring `[spec]` ADR 0025
  §1, fragment 07 §1.1-1.2. Nodes: `router` · `discovery` · `composer` · `configure` · `provision` · `planner` ·
  `responder` + `process_operations` (the op-queue drain, Phase 3) + `route_next_phase` (deterministic next-phase
  from `coordinationLog`, NOT a re-ask of the LLM `[spec]` fragment 07 §1.2). Conditional edges per the stage
  allow-lists (composer/configure → mutation tier; provision → entity tier; discovery/planner/responder →
  read-only). The recursion bound = **40** (`MAX_TOOL_ROUNDS` raised from the live `[read]` `ChatService.java:39`
  =30; §7.16 #3) with the graceful-halt message, not a throw `[spec]` §7.1.
- **CREATE** `.../chat/orchestration/PlanGate.java` (or wire on the apply node) — the HITL approval as
  LangGraph4j **`interruptBefore` on the apply node** (the n8n `interrupt({type:'plan'})` analogue `[spec]`
  fragment 07 §1.4 `[read F]`); resume via the new `…/plans/{planId}/decision` endpoint (Phase 4). approve→Apply,
  reject→discard+restore, modify→rebuild-staging (capped at the n8n `MAX_PLAN_MODIFY_ITERATIONS=5` analogue).
- **WIRE** the `langgraph4j-postgres-saver` checkpointer as `.compile(checkpointer)` — keyed by thread (session) +
  checkpoint (turn). This IS the snapshot/undo store (Phase 7); **no separate `chat_turn_snapshots` table**
  `[spec]` ADR 0025 §3, §7.16 #4.
- **REWIRE** `[read]` `ChatService.java:574` `handleLLMMode` to drive the graph instead of the inline
  `while (toolRounds < MAX_TOOL_ROUNDS)` loop `[read]` `:649`. The SSE emitter plumbing (`emitterDead`
  `[read]` `:462-465`, `safeSend` `:691-750`) is RETAINED — the graph nodes emit through it.
- **DELIVERABLE:** the graph compiles and runs the 7 nodes for a turn (initially with the existing prompts, no
  behavior change to the mutation surface yet); a turn checkpoints to Postgres. **No new mutation contract yet.**

### Phase 2 — The Vertex adapter + `CHAT_CHEAP` + the per-stage model matrix (serial, right after the carrier)

**Goal:** each graph node resolves its own model through PULSE's Vertex seam, preserving thought-signature +
structured tool-replay; the cheap/reasoning tiers are wired.

- **MODIFY** `[read]` `LlmSurface.java:3-10` — **ADD `CHAT_CHEAP`** (the 7th surface; today exactly 6, no
  `CHAT_CHEAP`) `[spec]` ADR 0025 §2, fragment 04 §1.1, P2.
- **MODIFY** `[read]` `application.yml:88-115` — ADD `pulse.llm.cheap-model` and `pulse.llm.vertex.cheap-chat-model`
  (default Gemini Flash, e.g. `gemini-2.5-flash`); the file already documents these as the to-add keys at
  `[read]` `:110-111` ("a NEW pulse.llm.vertex.cheap-chat-model + LlmSurface.CHAT_CHEAP (ADR 0025)"). **Do NOT**
  reuse the dead `schema-model`/`schema-inference.model` `[read]` `:108-113,119-126` (ADR 0011 retired model
  schema-inference) `[spec]` ADR 0025 §2, fragment 04 §1.1.
- **MODIFY** `LlmEndpointService` (the seam `[spec]` fragment 04 §1 — already merged on `cg-env-transition`,
  consumed via `@Autowired(required=false)` at `ChatService.java:316-317` per fragment 04) — extend the
  enum→model resolution to map `CHAT_CHEAP` → `vertex.cheap-chat-model` / `cheap-model`.
- **CREATE** `.../chat/orchestration/NodeLlmAdapter.java` — the thin node→`LlmEndpointService` adapter so each
  LangGraph4j node invokes PULSE's Vertex path (NOT a model client inside the library) `[spec]` ADR 0025 §4,
  fragment 04 §1. It MUST preserve: **structured tool-call replay** (completed calls stay structured `function`
  entries, not flattened `[spec]` fragment 04 §3, the live `toOutboundToolCall` `[read]` `ChatService.java:670`)
  and the **Vertex thought-signature** (`extra_content.google.thought_signature` captured + replayed unchanged
  `[spec]` fragment 04 §4 — the live capture/emit code is provider-agnostic at `ChatService.java:813-816,907-911`).
- **WIRE the per-stage model matrix** on the nodes (the n8n `stageLLMs` analogue `[spec]` ADR 0025 §2, fragment 07
  §1.2): **cheap tier** (`CHAT_CHEAP`) on **router · discovery · configure · provision · responder**; **reasoning
  tier** (`LlmSurface.CHAT`, Gemini Pro) on **composer · planner**; a node MAY escalate on a flagged hard case.
- **TEST-GATE (binding):** do NOT modify the Vertex replay path without re-running `[read]`
  `VertexLlmConnectivityIT.java` — the opt-in `@Tag("live-vertex")` test `:20-21` asserting `thought_signature`
  present + a `String` `:94-99` and `assumeTrue(VERTEX_PROJECT_ID)` `:129-136` — and the hermetic SSE
  contract test `[spec]` fragment 04 §4/§5.
- **DELIVERABLE:** each stage node calls its tier through the adapter; OpenRouter stays the default + switchable
  (`provider` is `vertex` in this env `[read]` `application.yml:91`); default test lanes make no paid Vertex calls.

### Phase 3 — The op-emitting mutation tier + op-queue + atomic drain (serial, on Phases 1-2)

**Goal:** every composition mutation is a tool that emits exactly ONE typed `PlanOperation` into the per-turn
op-queue; a single `process_operations` superstep drains them to the STAGING graph (never canonical). This is the
single biggest divergence from today's code.

- **CREATE** `.../chat/orchestration/PlanOperation.java` — the discriminated op union (`addInstances` /
  `removeInstance` / `updateInstance` / `setWiring` / `mergeWiring` / `removeWiring` / `rename` / `setName` /
  `setPipelineSetting` / `clear`), keyed by **`instanceRef` = instance NAME** with apply-time id resolution (the
  live `Plan.draftRefDeclarations`/`draftRefBindings` mechanism `[read]` per §7.0, `Plan.java:75-80`) `[spec]`
  §7.4, fragment 06 §E. `addInstances` carries **NO initial params** (3-F4 — a following `set_params` carries
  values) `[spec]` §7.3 B.
- **CREATE** `.../chat/orchestration/OpQueue.java` + the **append/clear reducer** on the `AgentState.opQueue`
  channel (mirroring n8n's `operationsReducer`: append; `{op:'clear'}` empties; null = reset) `[spec]` fragment
  06 §B.2/§E.
- **CREATE** `.../chat/orchestration/StagingGraph.java` + `applyOps(clone(canonical), ops)` — the immutable fold
  (n8n's `applyOperations` `[spec]` fragment 06 §C), applied to a **clone of the per-turn canonical snapshot**
  (STAGING, NOT canonical). The op-handler bodies (additive `mergeWiring` dedup on
  `{sourceRef,sourcePort,targetRef,targetPort}`; `clear` reset) are Java reimplementations of the n8n handlers.
- **CREATE the `process_operations` node** (Phase 1's wiring point) — after each composer superstep, drain the
  queue as ONE fold to the STAGING graph, reset the queue, invalidate any cached `validate_plan`, and emit one
  `candidate_graph` event (Phase 5) `[spec]` fragment 05 §C.3, 06 §E.
- **CREATE/RE-TIME the mutation tools** (snake_case; live convention has 0 hyphens). Each: Zod/JSON-schema-validate
  → read STAGING (`applyOps(clone(canonical), pending)`) → semantic-validate → enqueue ONE op → `tool_result`;
  NEVER write `sub_pipeline_instances`/`port_wirings` or the Command Log `[spec]` §7.3 B:
  - `add_blueprint_instance` → `addInstances` — re-times `[read]` `ChatTools.java:166` (`plan_add_step`); REQUIRED
    `reasoning`; rejects orchestration-policy/deprecated (`STEP_REQUIRES_PIPELINE_ORCHESTRATION` /
    `BLUEPRINT_COMPAT_READ_ONLY` `[read]` `ChatTools.java:167`).
  - `wire_ports` → `mergeWiring` — re-times the PREVIEW `plan_wire_ports` `[read]` `ChatTools.java:175` AND
    **replaces the direct-write** `wire_ports` `[read]` `ChatTools.java:295` / executor `[read]`
    `ChatToolExecutor.java:279` (route-to-queue, NOT delete — §7.16 #7); REQUIRED `reasoning`; port-type +
    dataset-schema-contract compat (§7.13 layer 2).
  - `set_params` → `updateInstance` — re-times `plan_set_step_params` `[read]` `ChatTools.java:185` AND replaces
    direct `configure_step_params` `[read]` `ChatTools.java:274` / executor `[read]` `ChatToolExecutor.java:277`;
    STRUCTURED params (no sub-LLM, ADR 0013); ARCH-018 forbidden-key blockers `[read]` `ChatTools.java:186`.
  - `remove_instance` → `removeInstance` — replaces direct `remove_step` `[read]` `ChatTools.java:305` / executor
    `[read]` `ChatToolExecutor.java:280`.
  - `remove_wire` → `removeWiring` (new); `rename_instance` → `rename` (new) `[spec]` §7.3 B N5/N6.
  - `set_pipeline_setting` → `setPipelineSetting` (folds to `pipeline.update` at Apply, §7.16 #9) — re-times
    `update_pipeline_orchestration` `[read]` `ChatTools.java:283` / executor `[read]` `ChatToolExecutor.java:278`.
- **READ-TOOLS-SEE-STAGING invariant** — `get_composition` (`[read]` `ChatTools.java:142`) and `get_step_schema`
  run mid-turn against `applyOps(clone(canonical), pending)`, NOT canonical, once the queue is non-empty `[spec]`
  §7.1, fragment 06 §B.3.
- **DELIVERABLE:** the composer node builds a STAGING graph op-by-op; a multi-tool turn stays self-consistent;
  NOTHING is written to canonical or the Command Log yet (Apply is Phase 4).

### Phase 4 — First-class Command-Logged Apply + the universal Plan→Apply gate (serial, on Phase 3)

**Goal:** `apply_plan` is the SOLE canonical writer; composition mutations become first-class idempotent
Command-Log entries; entity-provisioning `create_*` also goes through Plan→Apply.

- **REGISTER the six `composition.*` command types** on `CommandService` (do NOT exist today — composition writes
  bypass the Command Log `[read]` `ChatToolExecutor.java:277-280` call `CompositionService` directly; P3):
  `composition.addInstance` / `removeInstance` / `updateInstance` / `wirePorts` / `removeWiring` / `renameInstance`
  (the `noun.verb` convention matching `pipeline.createRevision` `[read]` `PipelineCommandHandlers.java:19-23`)
  `[spec]` §7.4/§7.16 #9, fragment 06 §F. `setName`/`setPipelineSetting` map to the existing `pipeline.update`.
- **EXTEND `apply_plan`** (the live SOLE generic mutator `[read]` `ChatTools.java:368`, ARCH-009; the non-APPROVED/
  cross-session reject contract `[read]` `ChatTools.java:368-376`) to, in ONE DB transaction: re-validate APPROVED
  + same-session → run `validate_plan` (interim, P4) → resolve `instanceRef`s → write canonical (`sub_pipeline_instances`
  / `port_wirings`) AND one Command-Log row per staged op (the §7.4 mapping table) under one shared `planId` with
  `idempotencyKey = tenantId + ":" + commandType + ":" + aggregateId + ":" + UUID` (the live key `[read]` per §7.0,
  `CommandService.java:34`) → emit `tool_result{mutationApplied=true, planId, commandIds, refreshHints}` → promote
  ghosts to real `[spec]` §7.9, fragment 06 §F.
- **CREATE the plan-decision endpoint** `POST /api/v1/chat/sessions/{sessionId}/plans/{planId}/decision`
  (body `{decision: approve|modify|reject}`) — the session-scoped first-class transport event (the LangGraph4j
  `interruptBefore` resume / n8n `Command({resume})` analogue), NOT a reuse of the tenant-scoped
  `…/plans/{planId}/approve|apply|cancel` `[read]` (per §7.17 B #19-21) `[spec]` §7.5/§7.16 #10, fragment 07 §1.4.
- **UNIVERSAL GATE — route the entity-provisioning `create_*` X-tier tools through Plan→Apply** (today direct-write):
  `create_data_source` `[read]` `ChatToolExecutor.java:249` / `ChatTools.java:36`; `create_domain` `[read]`
  `ChatToolExecutor.java:251` / `ChatTools.java:62`; `create_connector` `[read]` `ChatToolExecutor.java:252` /
  `ChatTools.java:45`; `create_dataset` `[read]` `ChatToolExecutor.java:254` / `ChatTools.java:72` (+ `create_sink_target`,
  `apply_dq_expectations`, `create_dataset_from_discovery`). Entity creation appears as explicit commands in the
  Plan Preview — nothing writes product state silently; draft-saves are the only non-gated writes `[spec]`
  §7.15/§7.16 #18.
- **BLOCKED concurrency while staged** — while a turn is mid-build or a Plan Preview is open, disable direct
  canonical mutation; cosmetic-only moves MAY remain; show the non-blocking notice `[spec]` §7.12/§7.16 #14.
- **DELIVERABLE:** the FIRST end-to-end pipeline — compose → Plan Preview → Apply → canonical + Command Log →
  Builder builds it (the **gating milestone**, ADR 0025 §5).

### Phase 5 — Streaming-to-canvas + the diff (on Phases 3-4; the canvas payload)

**Goal:** the new SSE events stream the STAGING graph + tool status; the client (or backend) computes the
canonical-vs-staging diff that IS the Plan Preview.

- **ADD the new SSE events** to the live SSE channel (`POST /api/v1/chat/sessions/{sessionId}/messages`,
  `produces=TEXT_EVENT_STREAM_VALUE` `[read]` `ChatController.java:76`; emitter `[read]` `ChatService.java:462`),
  all **underscore** to match the live `chunk`/`tool_call`/`tool_result`/`navigate`/`done`/`error` names
  (`[read]` `ChatService.java:550,691,710,698,750,523`): `tool_progress`, `candidate_graph` (= the full STAGING
  `CompositionView` snapshot, the n8n `workflow-updated` analogue — coarse snapshot, NOT per-edge deltas),
  `questions`, `plan`, `messages_compacted` `[spec]` §7.5, fragment 05 §C.2. Cancellation: `emitterDead`
  `[read]` `ChatService.java:462-465` stops the loop + discards staging (the n8n graceful abort).
- **CREATE** `.../chat/diff/CompareGraphs.java` (or frontend) — `compareGraphs(canonical, staging)` → 4-status
  content-diff (`equal`/`modified`/`added`/`deleted`) keyed by `instanceRef`, the n8n `compareWorkflowsNodes`
  analogue `[spec]` fragment 06 §G/§D. Instance equality over the P7 projection (`{name, blueprintKey,
  blueprintVersion, params, lakeLayer, lakeFormat, storageBackend}`, excluding `dqExpectations`/`executionOrder`).
  Wiring diff = a parallel set-diff over `{sourceRef, sourcePort, targetRef, targetPort}`. Computed **PRE-commit**
  (the inversion: the diff IS the plan, not a post-hoc audit) `[spec]` §7.7, fragment 06 §G.
- **WIRE the "Review N changes" banner** = `count(added)+count(modified)+count(deleted)+changedWires`; clicking it
  opens the before/after + per-op `reasoning`; the banner IS the Plan Preview `[spec]` §7.7, fragment 06 §G.
- **DELIVERABLE:** the Customer watches `candidate_graph` snapshots stream; the diff drives the Plan Preview.

### Phase 6 — Client reconcile + Dagre auto-layout (frontend, on Phase 5)

**Goal:** React Flow renders the STAGING graph as ghost/candidate nodes; auto-layout runs on structural change only.

- **ADD (frontend)** `@dagrejs/dagre` to `[read]` `frontend/package.json` (NOT present today; `@xyflow/react`
  IS, `[read]` `package.json:17` `^12.10.1`) `[spec]` §7.6, fragment 07 §3, P8.
- **CREATE** the React Flow reconcile (in/near `chat-panel.tsx`) — on `candidate_graph`: `categorize → {toAdd,
  toUpdate, toRemove}` by `instanceRef`; `updateExisting` in place **preserving manual positions**; `removeStale`;
  `addNew` (ghosts get auto-positions); reconcile wirings; status-colored ghost borders `[spec]` §7.6, fragment 05
  §C.5, fragment 07 §3.2.
- **CREATE** `autoLayout()` via Dagre — **ONLY on STRUCTURAL change** (add/remove/wire, NOT pure param edits, so
  the canvas does not jump during tuning); **seed with explicit positions** (compute only for unpositioned ghosts);
  **snap to grid** (the two behaviors fragment 07 §3 confirmed against the real n8n source `[ref #29850/#30455]`)
  `[spec]` §7.6, fragment 07 §3.2.
- **NODE-POSITION store (P8)** — client-side position map keyed by `instanceRef` during a turn, persisted to
  instance metadata at Apply (no x/y column today).
- **DELIVERABLE:** ghosts appear step-by-step (node → ports light → edge draws) on the STAGING layer; canonical
  stays read-only during a streaming turn.

### Phase 7 — Snapshot / revert + history compaction (on Phase 1's checkpointer)

**Goal:** revert is checkpoint-restore (not inverse plan); restore truncates chat to the anchor; long sessions
auto-compact.

- **WIRE the per-turn snapshot** — at turn start, the full `CompositionView` of the active version is the revert
  point, persisted as a LangGraph4j checkpoint (`langgraph4j-postgres-saver`), keyed by thread (session) +
  checkpoint (turn); the STAGING graph is its deep clone `[spec]` §7.8, fragment 07 §2.2.
- **WIRE revert** — reject / cancel-mid-turn / Restore: drop the staging clone and re-render from the snapshot
  (canonical untouched in all three — write-locked behind Apply). For an ALREADY-APPLIED turn: **restore the
  checkpoint** (`getState`/restore/time-travel) AND truncate chat back to the turn's anchor message (the n8n
  `truncateMessagesAfter(messageId)` analogue `[spec]` fragment 07 §2.1 `[read I]`); reset phase to a fresh build
  baseline. **Undo = restore the checkpoint snapshot, NOT an inverse plan** (one turn = one checkpoint; canonical
  write-locked behind Apply) `[spec]` §7.11/§7.16 #13, ADR 0025 §3.
- **CREATE token-based history auto-compaction** — when session message history exceeds ~50% of the context budget
  (tunable), summarize older turns into a conversation-summary block and emit `messages_compacted` (the live loop
  has none) `[spec]` §7.11/§7.16 #13, §7.18 row 21.
- **DELIVERABLE:** "Apply, then changed my mind" recovery; bounded session token growth.

### Phase 8 — The 7 per-stage prompts + dump-all catalog + 5 category guides + context wrappers (PARALLELIZABLE fan-out)

**Goal:** split the one concatenated `buildSystemPrompt` into the 7 per-stage assemblies, the cached dump-all
catalog, the 5 category guides, and the per-turn context-tag wrappers — each as a node's prompt mode.

- **SPLIT** `[read]` `ChatService.java:576` `buildSystemPrompt` (today phase-gated by `ConversationPhase`
  `[read]` `:1089`) into the **7 per-stage assemblies** authored in `[spec]` fragment `01-system-prompts.md`
  (§1 Router · §2 Discovery · §3 Build/Composer · §4 Configure · §5 Provision · §6 Planner · §7 Responder + the
  §8 shared preamble). The verbatim prompt TEXT is fragment 01's; this phase wires each as a node's `SystemMessage`.
  **RETAIN** the live `PulseSystemPrompt.java` coverage the fragments map onto (IDENTITY, ABSOLUTE_RULES,
  MEDALLION_RULES, the onboarding/CONNECTOR_VOCABULARY/RUNTIME_FIELDS_PUNCH_LIST → Provision, PLANNER_PACKET →
  Planner) `[spec]` fragment 01, §7.14.
- **CREATE the dump-all cached catalog block** — inject ALL active Blueprints as **tight entries**
  (`name · category · layer · ports · key-params`) into a prompt block marked for provider prompt-caching; today
  the dump is `blueprint_key + 80-char description` only `[read]` (per §7.14, `ChatService.java:1081`). The
  `cache_control` marker stays a forward item (none in the live request body `[read]` `ChatService.java:649-662`)
  `[spec]` §7.14/§7.16 #16, fragment 04 §6, P10. Fallback if tokens bloat: hybrid (summaries + on-demand
  `get_blueprint_detail`).
- **CONSOLIDATE the 5 per-category best-practice guides** (Ingestion/Transform/Modeling/DQ/Orchestration), data-eng
  voice, from the ~18 per-Blueprint cards + example packets `[read]` `ChatService.java:40-94` (`DBT_BEST_PRACTICE_CARDS`)
  and `:96-285` (`BLUEPRINT_EXAMPLE_PACKETS`); inject the relevant category guide when composer/discovery selects a
  category `[spec]` fragment 03, §7.14.
- **CREATE the per-turn context-tag wrappers** — `<current_composition>` / `<dataset_schemas>` / `<selected_step>` /
  `<run_status>` + the plain-text conversation-summary, stripped from streamed text; the live prompt already injects
  dataset schemas `[read]` (per §7.14, `ChatService.java:1031-1072`) `[spec]` §7.14, fragment 02.
- **WIRE the ACTIVE MODE injection** — storage is the GLOBAL Mode (`RuntimeAuthorityService.getActivePersona()` →
  `GCP_PULSE`/`DPC_PULSE`), injected into Build/Composer/Configure/Provision/Planner; the stage sets only user-tier
  fields, never the storage backend / lake format `[spec]` §7.14, fragment 01 §8b.
- **GROUND the Planner's in-chat DAG** on the EXISTING `chat-dag` renderer — the Planner emits a markdown table
  that `[read]` `chat-dag.tsx:66` (`parsePipelineSteps`) / `:95` (`parsePipelineTable`) / `:35` (`ChatDag`) draws,
  rendered at `[read]` `chat-panel.tsx:552,563`; NO invented `graph` field; bare markdown tables (never code-fenced,
  per `PulseSystemPrompt.java:35`) `[spec]` fragment 01 §6, §7.14.
- **DELIVERABLE:** each node assembles its own prompt; the catalog is cached; the DAG renders in-chat unchanged.

### Phase 9 — The new discovery + validation read tools (PARALLELIZABLE fan-out)

**Goal:** the read/discovery tier the LOCKED §6 names; each is one tool registration + executor case.

- **CREATE** `get_composition_overview` (compact "N steps, M wires, layers, open ports, unresolved schema") —
  new; `get_step_schema` — **rename** the live `get_upstream_schema` (`[read]` `ChatTools.java:251`, executor
  `[read]` `ChatToolExecutor.java:274`); `get_blueprint_op_list` (the declared op-list, schema/row effects) —
  new, depends on V153's declared `schema_behavior` (P5) `[spec]` §7.3 A/§7.17 A N8-N9.
- **CREATE** `validate_structure` (orphans/cycles/reachability/ports), `validate_configuration` (per-step
  completeness vs the Blueprint contract + the runtime punch-list `[read]` per §7.3 A, `PulseSystemPrompt.java:190-248`)
  — new; some checks exist under other names (`check_table_contract_readiness` `[read]` `ChatTools.java:424`,
  `derive_contract_impact` `[read]` `ChatTools.java:460`) and fold in `[spec]` §7.3 A.
- **CREATE** `validate_plan` — runs the **deterministic Builder pre-flight** over the STAGING graph; **interim**
  composition = `validate_structure` + `validate_configuration` + `check_table_contract_readiness` until #1/#2
  lands (P4), then the full compile pre-flight swaps in (§7.16 #15) `[spec]` §7.13 layer 3.
- **CREATE** `validate_sql_expression` — Calcite-validate a derived-column / `sql-model` body; the
  schema-returning branch depends on #6 / `CALCITE-PHASE-2` (P6), parse-only/declared until then `[spec]` §7.3 A.
- **DELIVERABLE:** the discovery + validation tier complete; `validate_plan` is the Apply gate's layer-3 check.

---

## 4. TESTS

| Test | What it asserts | Harness | Phase |
|---|---|---|---|
| **Graph-wiring + routing tests** | `StateGraph` compiles; `route_next_phase` is deterministic (not LLM-re-asked); the recursion bound (40) graceful-halts without throwing; the 7 nodes' tool allow-lists enforced `[spec]` §7.1, fragment 07 §1.2 | JUnit (mock LLM seam) | 1 |
| **Checkpointer round-trip** | a turn checkpoints to Postgres; `getState`/restore returns the per-turn snapshot; thread=session, checkpoint=turn `[spec]` ADR 0025 §3 | **real Postgres** (CI service container — the LangGraph4j Postgres saver schema cannot be faithfully exercised on H2) | 1, 7 |
| **Vertex adapter contract** | `CHAT_CHEAP` resolves to the cheap-tier model; structured tool-call replay stays `function` (not flattened); thought-signature replayed byte-for-byte; OpenRouter omits `extra_content` `[spec]` fragment 04 §3/§4/§5 | hermetic SSE contract test (fake server) + the opt-in `@Tag("live-vertex")` `VertexLlmConnectivityIT` `[read]` `:20-21,94-99,135-136` (skipped when `VERTEX_PROJECT_ID` unset) | 2 |
| **Op-queue + atomic drain** | a mutation tool enqueues exactly one op + writes neither canonical nor Command Log; `process_operations` folds all ops atomically to STAGING; read tools mid-turn see `applyOps(clone(canonical), pending)`; `{op:'clear'}` empties `[spec]` §7.4, fragment 06 §E | JUnit (in-memory composition) | 3 |
| **Universal-gate Plan→Apply** | composition AND `create_*` entity tools produce a PREVIEW plan + write no product state until `apply_plan`; non-APPROVED/cross-session/`validate_plan`-fail reject without side effects `[read]` `ChatTools.java:368-376`; BLOCKED concurrency while staged `[spec]` §7.9/§7.12/§7.15 | JUnit + **real Postgres** (one-transaction Apply writes canonical + 6 `composition.*` Command-Log rows under one `planId`) | 4 |
| **Streaming + diff** | the new underscore SSE events emit; `candidate_graph` carries the full STAGING `CompositionView`; `compareGraphs` 4-status content-diff over the P7 projection (excludes `dqExpectations`/`executionOrder`); "Review N changes" count correct `[spec]` §7.5/§7.7, fragment 06 §G | JUnit (backend diff) + frontend component test | 5 |
| **Reconcile + Dagre layout** | ghost reconcile preserves manual positions; `autoLayout` runs ONLY on structural change (not param edits); seed-with-positions + grid-snap `[spec]` §7.6, fragment 07 §3 | frontend (`@xyflow/react` + `@dagrejs/dagre`) | 6 |
| **Snapshot/revert + compaction** | reject/cancel drops staging (canonical untouched); applied-turn restore = checkpoint-restore + truncate-chat-to-anchor (not inverse plan); compaction emits `messages_compacted` above threshold `[spec]` §7.8/§7.11 | **real Postgres** (checkpointer) | 7 |
| **End-to-end first-pipeline (the gating milestone)** | NL → composer ops → Plan Preview → Apply → canonical + Command Log → ghost-to-real → the deterministic Builder compiles it `[spec]` ADR 0025 §5 | **real harness** (Postgres + the #1/#2 Builder once it lands; interim `validate_plan` before) | 1-6 vertical slice |

> **H2 vs real:** the op-queue/diff/routing UNITS run on JUnit with an in-memory composition + a mocked LLM seam.
> The **checkpointer** (LangGraph4j Postgres saver), the **one-transaction Apply** (canonical + `composition.*`
> Command-Log rows), and the **end-to-end milestone** need the **real Postgres / Builder harness** — CI already
> runs Java 21 + Postgres 16 `[report]` `CLAUDE.md` CI section. The live Vertex path is opt-in only `[read]`
> `VertexLlmConnectivityIT.java:135-136`, so the default lanes make no paid calls.

---

## 5. MILESTONES — "done" per phase

> **The gating milestone (ADR 0025 §5): "first pipeline composed + built end-to-end," built incrementally.** It
> is reached at the END of the **Phase 1→2→3→4→5→6 composer vertical slice** (Milestone M4 below), NOT after the
> whole plan. The remaining stages, the universal entity gate, compaction, prompts, and read tools layer ON TOP.

- **Phase 1 done (M1):** the `StateGraph` compiles + runs the 7 nodes for a turn; `AgentState` threads the
  channels; `route_next_phase` is deterministic; a turn checkpoints to Postgres; the inline `while`-loop
  (`[read]` `ChatService.java:649`) is replaced by the graph driver. No new mutation contract yet.
- **Phase 2 done:** `LlmSurface.CHAT_CHEAP` + the two config keys exist; each node resolves its tier through the
  Vertex adapter; thought-signature + structured replay preserved (the two Vertex tests green); OpenRouter still
  default + switchable.
- **Phase 3 done:** every composition mutation is a tool that emits ONE typed op into the op-queue; the
  `process_operations` drain builds a STAGING graph; read tools see pending staging; canonical + Command Log
  untouched. `add_blueprint_instance` carries NO initial params (3-F4).
- **Phase 4 done (M4 — THE GATING MILESTONE):** the six `composition.*` command types registered; `apply_plan`
  writes canonical + one Command-Log row per op in one transaction under one `planId`; the `…/plans/{planId}/decision`
  endpoint live; entity `create_*` is Plan→Apply-gated; concurrency BLOCKED while staged. **A first pipeline composes
  → Plan Preview → Apply → the deterministic Builder builds it end-to-end.**
- **Phase 5 done:** the new underscore SSE events stream; `candidate_graph` carries the full STAGING composition;
  `compareGraphs` drives the "Review N changes" banner = the Plan Preview, computed PRE-commit.
- **Phase 6 done:** the canvas renders the STAGING graph as ghost nodes; reconcile preserves manual positions;
  Dagre re-layouts only on structural change (seed-with-positions + grid-snap).
- **Phase 7 done:** reject/cancel drops staging (canonical untouched); applied-turn undo = checkpoint-restore +
  truncate-chat-to-anchor; long sessions auto-compact with `messages_compacted`.
- **Phase 8 done:** `buildSystemPrompt` is split into the 7 per-stage assemblies; the dump-all catalog is cached
  (tight entries); the 5 category guides + context wrappers + ACTIVE MODE injection wired; the Planner's in-chat
  DAG renders via the existing `chat-dag` renderer. (Verbatim DE-voice review is a separate content pass, §7.16 #17.)
- **Phase 9 done:** `get_composition_overview` / `get_step_schema` (renamed) / `get_blueprint_op_list` /
  `validate_structure` / `validate_configuration` / `validate_plan` (interim) / `validate_sql_expression`
  (parse-only/declared until #6) all registered; `validate_plan` is the Apply layer-3 check.

---

## 6. FAN-OUT — what parallelizes

- **Phase 8 — the 7 per-stage prompt assemblies + 5 category guides.** Each stage prompt is an independent
  assembly authored in fragment 01 (§1-§7) + the shared preamble (§8); the 5 category guides (fragment 03) are
  independent of each other and of the orchestration code. **The obvious prose fan-out** — 7+5 parallel work-items,
  each grounded in its fragment section + the live `PulseSystemPrompt`/`ChatService` card anchors. Gate: the
  carrier (Phase 1) + the model adapter (Phase 2) must exist for a node to assemble + call its prompt.
- **Phase 9 — the 7 new read tools.** Each is one `ChatTools` registration + one `ChatToolExecutor` switch case +
  one service call; they share only the read-tier contract. `get_step_schema` is a rename of the live
  `get_upstream_schema`. Gate: `get_blueprint_op_list` needs V153's declared `schema_behavior` (P5);
  `validate_plan`/`validate_sql_expression` have interim bodies until #1/#2 (P4) and #6 (P6).
- **Phase 3's mutation tools** parallelize per tool (each tool = enqueue one op), but they share the `PlanOperation`
  union + `OpQueue` reducer + `StagingGraph.applyOps` from earlier in the phase — fan those out only after the
  union/queue/fold land.
- **Phase 5 (backend diff) and Phase 6 (frontend reconcile/Dagre)** are split across the backend/frontend boundary
  and can proceed in parallel once `candidate_graph` is specified (Phase 5's first sub-task).
- **NOT parallelizable:** Phase 1 (the carrier/graph spine — everything threads `AgentState`), Phase 2 (the model
  seam every node calls), Phase 4 (the single canonical writer + the one-transaction Apply), and the Phase 1→6
  composer vertical slice that reaches the gating milestone (M4) — that slice is the serial critical path.

---

## 7. RISKS — genuine build risks

1. **`langgraph4j` is a community-maintained library taken as a core orchestration dependency.** It is
   Apache-licensed + active but not an official LangChain product (ADR 0025 "Trade-off"). The bet is bounded: the
   `StateGraph` concepts are standard, so a worst-case migration is a refactor of the orchestration layer (stages,
   tools, prompts unaffected) — which is also the Option-B (phase-gated single loop) fallback. **Mitigation:** keep
   the stage/tool/prompt logic OUT of `langgraph4j` types where cheap; the named fallback is the prior default.
2. **Big-bang rewrite would break the rescue.** Replacing the working single tool-loop `[read]`
   `ChatService.java:649` with a half-built graph before any pipeline composes is the single biggest schedule
   risk. **Mitigation:** ADR 0025 §5 milestone discipline — the Phase 1→6 composer vertical slice (M4) is the
   first deliverable; the graph grows behind "first pipeline composed + built end-to-end" (memory: never big-bang).
3. **The op-queue re-timing is the biggest divergence from today's code.** The live mutators split: some PREVIEW
   (`plan_*` `[read]` `ChatTools.java:166,175,185`), some direct-write `CompositionService` (`[read]`
   `ChatToolExecutor.java:277,279,280`). Re-timing ALL to enqueue-one-op (never write) touches every mutation path.
   **Mitigation:** route-to-queue (NOT delete, §7.16 #7) keeps the tool surface stable; land the union/queue/fold
   first, then re-time tools one at a time behind the staging graph.
4. **Composition still bypasses the Command Log.** The six `composition.*` types do NOT exist; composition writes
   go direct to `CompositionService` `[read]` `ChatToolExecutor.java:277-280`. Until Phase 4 registers them + routes
   Apply through them, composition is not idempotent/auditable. **Mitigation:** Phase 4 is on the critical path to
   M4; the one-transaction Apply test asserts the 6 rows under one `planId`.
5. **`validate_plan` + `validate_sql_expression` + B3 depend on specs that aren't ready.** #1/#2 (Builder, P4) and
   #6 (Calcite, P6) are upstream; B3 (design-time row preview, §7.17 B) has **no live analogue** — every existing
   `preview_*` tool returns contract/metadata, not data rows. **Mitigation:** ship interim `validate_plan`
   (structure+config+contract-readiness, §7.16 #15) and a parse-only/declared `validate_sql_expression`; B3 returns
   *projected schema*, not *sampled rows*, until the Builder's sample-execution path lands — out of THIS plan's
   core scope (#4 territory).
6. **Prompt-cache markers are unimplemented on every provider.** The dump-all catalog's cost story rests on
   provider prompt-caching, but the live request body carries **no `cache_control`** `[read]` `ChatService.java:649-662`
   (fragment 04 §6 confirms none on `cg-env-transition` either). **Mitigation:** Phase 8 marks the block for caching
   but treats budget-fit as a measurable fact to verify; the §6 hybrid (summaries + on-demand `get_blueprint_detail`)
   is the named fallback if the ~50 entries bloat the cache budget.
7. **The Vertex replay path is fragile + test-gated.** Thought-signature + structured tool-call replay must survive
   the persist→un-wrap→replay round-trip across every node; a node that flattens a completed tool call breaks it
   (`f852686`: "would weaken structured tool-call replay and lose ... thought signatures"). **Mitigation:** the
   node→`LlmEndpointService` adapter is the single replay boundary; do NOT modify it without re-running the
   hermetic SSE contract test + the opt-in `VertexLlmConnectivityIT` `[read]` `:20-21,135-136` (the binding
   directive, fragment 04 §5).
8. **No node-position store; positions are not durable.** `SubPipelineInstance` has no x/y column (P8), so a
   client-side `instanceRef` position map must survive a turn + persist at Apply, or the canvas reflows on every
   reload. **Mitigation:** persist positions to instance metadata at Apply (no schema change); Dagre seed-with-
   positions means a missing position degrades to auto-layout, not a crash.

---

## REPORT

- **Build phases: 9** (Phase 1 LangGraph4j foundation → Phase 9 new read/validation tools), with the gating
  milestone "first pipeline composed + built end-to-end" reached at the Phase 1→6 composer vertical slice (M4).
- **Prerequisites: 10** (P1 langgraph4j deps, P2 `CHAT_CHEAP`+cheap-keys, P3 `composition.*` types, P4 #1/#2
  Builder, P5 #5 catalog/V153, P6 #6 Calcite, P7 diff projection, P8 node-position store, P9 AgentState channels,
  P10 catalog tight-entry/cache markers).
