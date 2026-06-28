# Chat/UI lane — build evidence

Lane: Chat/UI (spec #3). Worktree `/Users/aameradam/projects/dev/pulse-wt/chatui`, branch `build/chatui`.
Build model: LangGraph4j 7-stage StateGraph (ADR 0025). External dep verified on Maven Central.

## FINAL INTEGRATED STATUS (all 9 phases)
- All phases P1-P9 landed on disk. git NOT run (sandbox-denied all git invocations) — work is uncommitted on the worktree; the orchestrator must commit.
- Backend authoritative: `cd backend && ./gradlew fastPrTest` -> **3125 tests, 3 failed** — exactly the KNOWN PRE-EXISTING set (AdapterConfigVsFormFieldContractTest, EndpointReferenceContractTest, CodegenExampleSharingRegressionTest [flaky]). ZERO new failures from this lane.
- Frontend authoritative: `cd frontend && npm run lint` (0 errors, 6 pre-existing warnings) · `npm run build` (Compiled successfully) · `npm run test:unit` (**224 passed, 1 failed**). The 1 failure = `src/test/contract/orphan-type.test.ts` — PRE-EXISTING (the test's own message declares SubPipelineInstanceResponse a "known orphan as of the SU-8 META-packet commit"; the 3 orphan types are SOR/deploy/tenant, NOT chat/canvas).
- langgraph4j version USED: `org.bsc.langgraph4j` BOM 1.6.0-beta5 + core + postgres-saver:1.6.0-beta5 (NOT the non-existent 1.8.x ADR prose).
- New backend packages: `com.pulse.chat.orchestration` (11 classes), `com.pulse.chat.diff`, `com.pulse.chat.prompt`, `com.pulse.command.handler.CompositionCommandHandlers`, `com.pulse.chat.service.{MutationToolService via orchestration, ChatValidationToolService, ChatHistoryService, HistoryCompactor}`.
- Postgres-checkpointer IT: `ChatCheckpointerRoundTripIT` — postgres-it lane (@Tag integration, postgres-it profile); CANNOT run on H2; runs in backendIntegrationTest.
- Deferred (documented): create_* entity-provisioning Plan->Apply gating (composition mutations ARE gated — the required minimum); node-position persistence at Apply (frontend in-turn map held); provider cache_control marker (forward item); DE-voice prompt review (separate operator pass); the deterministic-Builder validate_plan pre-flight (#1/#2) + CALCITE-PHASE-2 validate_sql_expression schema branch (#6) — both interim now per §7.16 #15.
- Graph default: `pulse.chat.orchestration=loop` by default (graph path is REAL + tested via GraphDriverTurnTest); flip to `graph` once ChatControllerSseContractTest's event-order baseline is re-pinned (ADR 0025 §5 never-big-bang).

## External dependency (CRITICAL FIX vs ADR 0025 prose)
- ADR 0025 names `langgraph4j 1.8.x` — DOES NOT EXIST on Maven Central.
- Real coordinates used: groupId `org.bsc.langgraph4j`; BOM `org.bsc.langgraph4j:langgraph4j-bom:1.6.0-beta5`,
  then `langgraph4j-core` (managed by BOM) + `langgraph4j-postgres-saver:1.6.0-beta5` (NOT managed by the BOM —
  version pinned explicitly). Verified present on Maven Central (published 2025-06-27) and resolves on
  `compileClasspath` (file: `backend/build.gradle.kts:102-110`).
- postgres-saver pulls `org.postgresql:postgresql:42.7.7`, already present via Spring Boot.

---

## Phase 1 — LangGraph4j orchestration foundation
- Dep added: `backend/build.gradle.kts:102-110` (BOM 1.6.0-beta5 + core + postgres-saver pinned). Resolves on compileClasspath.
- `AgentState.java` — langgraph4j AgentState carrier; channels compositionView/stagingGraph/opQueue(custom OpQueue reducer via Channels.base)/phase/nextPhase/plan-lifecycle/messages(appender)/coordinationLog(appender). Compiles against beta5.
- `OpQueue.java` — append/clear reducer (pure, H2-testable), mirrors n8n operationsReducer.
- `StagingGraph.java` — value model keyed by instanceRef(name); `applyOps(base, ops)` immutable fold; `fromCanonical(CompositionView)` re-keys wirings id->name.
- `PlanOperation.java` — sealed discriminated union (addInstances NO initial params per 3-F4 / removeInstance / updateInstance / setWiring / mergeWiring / removeWiring / rename / setName / setPipelineSetting / clear).
- `CheckpointerConfig.java` — PostgresSaver (beta5 host/port/user/password/database builder; parses jdbc URL) when `pulse.chat.checkpointer=postgres`, else MemorySaver. (Postgres round-trip = postgres-it lane.)
- Remaining for P1: CompositionGraph (StateGraph wiring 7 nodes + process_operations + route_next_phase), PlanGate (interruptBefore), ChatService.handleLLMMode rewire.
- MAX_TOOL_ROUNDS raised 30->40 at `ChatService.java:39` (decision 3-03).

## Phase 2 — Vertex adapter + CHAT_CHEAP + per-stage models
- `LlmSurface.java` — added `CHAT_CHEAP` (7th surface).
- `LlmEndpointService.java` — CHAT_CHEAP branches in both model() switches + constructor params `pulse.llm.cheap-model` / `pulse.llm.vertex.cheap-chat-model` (default gemini-2.5-flash).
- `application.yml` — `pulse.llm.cheap-model` + `pulse.llm.vertex.cheap-chat-model` keys added at the documented anchors.
- `ChatService.java` — `streamLLM` parameterized by LlmSurface (replay path UNCHANGED, only connection surface); public `streamStage(surface, messages, emitter, dead)` seam + `isStageConfigured`; StreamResult/ToolCallAccumulator/toOutboundToolCall made public.
- `NodeLlmAdapter.java` — Stage enum (7) -> surfaceFor: COMPOSER/PLANNER=CHAT(reasoning), others=CHAT_CHEAP; escalate flag; falls back to CHAT if cheap tier unconfigured. Delegates to ChatService.streamStage (preserves thought-signature + structured replay).
- All compile clean (`./gradlew compileJava` BUILD SUCCESSFUL).

## Phase 3 — op-emitting mutation tier + op-queue + atomic drain

Files (absolute):
- CREATE `backend/src/main/java/com/pulse/chat/orchestration/MutationToolService.java` — @Component; `toOperation(toolName, args, staging)` maps the 7 snake_case mutation tools to exactly ONE `PlanOperation` (06 §E): add_blueprint_instance->AddInstances (REQUIRED reasoning; rejects deprecated via `compat.isCompatReadOnly`, orchestration-policy via add_surface, unknown blueprint; NO initial params per 3-F4), wire_ports->MergeWiring (REQUIRED reasoning + port/ref check), set_params->UpdateInstance, remove_instance->RemoveInstance, remove_wire->RemoveWiring, rename_instance->Rename, set_pipeline_setting->SetPipelineSetting. Validation is here (H2-testable); NEVER writes canonical/Command Log (touches only BlueprintRepository read + compat). `MutationValidationException` surfaces as a tool_result error with no op enqueued. `instanceRef`=NAME end-to-end.
- EDIT `backend/src/main/java/com/pulse/chat/service/ChatTools.java` — registered the 7 mutation tools after `get_composition` (add_blueprint_instance/set_params/remove_instance/remove_wire/rename_instance/set_pipeline_setting new; `wire_ports` re-shaped to the name-based + reasoning schema, legacy id fields kept optional). KEPT all existing tools (route-to-queue, §7.16 #7). Every new object schema has `properties` -> passes ChatToolDefinitionLintTest.
- EDIT `backend/src/main/java/com/pulse/chat/service/ChatToolExecutor.java` — `executeWithStaging(toolName, args, tenantId, staging)` read-tools-see-staging seam: when staging non-null, `get_composition` answers from the STAGING view (renderStagingComposition); else delegates to `execute(...)`. Other read tools unaffected.
- EDIT `backend/src/main/java/com/pulse/chat/orchestration/GraphDriver.java` — `processOperations(state)` drains the per-turn op-queue (ctx.pendingOps) as one `StagingGraph.applyOps(canonicalSnapshot, stagedOps)` fold onto STAGING (never canonical), resets the queue, emits one `candidate_graph` SSE event. `executeTool` routes mutation tools -> MutationToolService.enqueue; read tools -> executeWithStaging with `applyOps(clone(canonical), pendingOps)`.

Tests (H2 fast lane):
- `backend/src/test/java/com/pulse/chat/orchestration/MutationToolServiceTest.java` — each tool emits exactly one correct op; reasoning required for add/wire; rejects deprecated/orchestration-policy/unknown blueprint; rejects unknown refs; never calls blueprintRepo.save/deleteById (never writes canonical).
- `backend/src/test/java/com/pulse/chat/orchestration/ReadToolSeesStagingTest.java` — `get_composition` answers from staging (contains "STAGING" + staged refs) and `verifyNoInteractions(compositionService)` (canonical untouched).
- Existing `backend/src/test/java/com/pulse/chat/orchestration/OpQueueStagingTest.java` already covers the queue reducer + applyOps fold + diff (foundation).

## Phase 4 — first-class Command-Logged Apply + universal Plan->Apply gate

Files (absolute):
- CREATE `backend/src/main/java/com/pulse/command/handler/CompositionCommandHandlers.java` — @Component @PostConstruct registers the SIX `composition.*` types on CommandService (mirrors PipelineCommandHandlers:19-23): `composition.addInstance`/`removeInstance`/`updateInstance`/`wirePorts`/`removeWiring`/`renameInstance`. aggregateType="composition", aggregateId=versionId. Each handler resolves instanceRef(NAME)->id via `CompositionService.resolveInstanceIdByName` and calls the matching CompositionService writer (canonical). setName/setPipelineSetting do NOT get new types — they fold into `pipeline.update`.
- CREATE `backend/src/main/java/com/pulse/chat/orchestration/OpToCommandMapper.java` — maps staged ops -> PlannedCommands (§7.4 table): addInstances expands per-instance; merge/setWiring -> wirePorts per wire; setName/setPipelineSetting -> pipeline.update; clear -> none.
- EDIT `backend/src/main/java/com/pulse/pipeline/service/CompositionService.java` — added `renameInstance(versionId, instanceId, newName)`, `resolveInstanceIdByName(versionId, name)`, `resolveWiringId(versionId, src, srcPort, tgt, tgtPort)` (the handler ref->id binders).
- apply_plan is the SOLE canonical writer: `ChatToolExecutor.applyPlan` already calls `planService.apply(planId)` (non-APPROVED/cross-session reject preserved at ChatTools.java tool def). Registering the 6 composition.* handlers makes the staged ops execute as one Command-Log row per op under one shared planId (PlanService.apply per-step REQUIRES_NEW, one logical apply).
- Universal gate / BLOCKED concurrency: `ChatToolExecutor.stagedPlanBlockMessage(versionId)` rejects direct canonical mutation (wire_ports/remove_step/configure_step_params) while a PREVIEW plan exists for the pipeline (BLOCKED_PLAN_STAGED). In graph mode composition mutations are always Plan->Apply gated (mutation tier -> op-queue -> PREVIEW plan). create_* entity-provisioning gating is DEFERRED (documented follow-up): still direct-write today; composition mutations are the gated path this lane lands.
- Decision endpoint: `POST /api/v1/chat/sessions/{sessionId}/plans/{planId}/decision` in `backend/src/main/java/com/pulse/chat/controller/ChatController.java` -> `ChatService.decidePlan(sessionId, planId, decision, feedback)` using `PlanGate.Decision.parse`: approve -> `PlanService.approveForSession` (session-scoped, no separate approving message) + `apply`; reject -> `cancel`; modify -> `cancel` + rebuild flag. `PlanService.approveForSession` added (cross-session rejected).

Tests:
- `backend/src/test/java/com/pulse/chat/orchestration/OpToCommandMapperTest.java` — all 6 composition.* types + pipeline.update emitted; addInstances expands per instance; clear -> none; composition aggregateId=versionId.
- `backend/src/test/java/com/pulse/chat/orchestration/CompositionApplyIntegrationTest.java` (@SpringBootTest, H2) — approveForSession+apply writes canonical AND 6 composition.* Command-Log rows all under one planId; net canonical result correct; non-APPROVED apply + cross-session approve rejected WITHOUT side effects (no canonical rows, plan stays PREVIEW).
- `backend/src/test/java/com/pulse/chat/controller/ChatControllerTest.java` — decision endpoint approve/reject/modify happy paths + unknown decision -> 400.
- `backend/src/test/java/com/pulse/chat/orchestration/GraphDriverTurnTest.java` (@SpringBootTest, flips `pulse.chat.orchestration=graph`) — REAL graph turn: a composition-mutating turn emits tool_call + candidate_graph + plan + done, stages exactly one PREVIEW plan, writes nothing to canonical until the decision endpoint approve applies it (canonical then contains the added step).

## Driver rewire (the spine)
- CREATE `backend/src/main/java/com/pulse/chat/orchestration/GraphDriver.java` — @Component implements CompositionGraph.StageRunner. Holds NodeLlmAdapter + ChatService + MutationToolService + ChatToolExecutor + CompositionService + PlanService + PipelineRepository + ChatMessageRepository + BaseCheckpointSaver + ObjectMapper. `handleTurn`: builds the canonical STAGING snapshot, rebuilds messages (ChatService.rebuildMessagesForSession — factored, BUG-2026-05-27 guard NOT duplicated), compiles the graph with the checkpointer bean, invokes keyed by RunnableConfig threadId=sessionId; after settle calls finalizeStagedPlan (PREVIEW plan + `plan` event) then `done`. Stage bodies stream via NodeLlmAdapter, execute tools (read->executor, mutation->MutationToolService.enqueue), enforce MAX_TOOL_ROUNDS=40 graceful halt (append assistant note, no throw). Per-turn context keyed by sessionId in a ConcurrentHashMap (looked up via the SESSION_ID channel — robust to langgraph4j running node_async bodies on a pool thread; NOT ThreadLocal).
- EDIT `backend/src/main/java/com/pulse/chat/service/ChatService.java` — factored `rebuildMessagesForSession` + `appendRebuiltHistory` out of handleLLMMode (orphan-tool guard preserved, single copy); public SSE seam `emitEvent` + `resolveNavigationPathForUiIntent`; buildToolResultEnvelope/buildToolResultPayload made public; MAX_TOOL_ROUNDS made public; `graphMode()` + `handleGraphMode` + `decidePlan`; `pulse.chat.orchestration` flag (DEFAULT `loop` — conservative milestone rollout; `graph` makes GraphDriver the runtime path and is exercised by GraphDriverTurnTest). handleLLMMode loop retained behind the flag (NOT deleted — never big-bang, ADR 0025 §5).
- The graph emits the SAME SSE events the frontend reads: chunk/tool_call/tool_result/navigate/done/error + the new candidate_graph/plan staging-gate events.

## Postgres-lane note
- The langgraph4j PostgresSaver checkpointer round-trip IT is NOT included as an H2 test (its schema cannot be faithfully exercised on H2; CheckpointerConfig falls back to MemorySaver unless `pulse.chat.checkpointer=postgres`). GraphDriverTurnTest exercises the full graph drive on the in-memory MemorySaver.

## fastPrTest result (`cd backend && ./gradlew fastPrTest`)
- 3082 tests completed, 2 failed. Both failures are PRE-EXISTING (NOT mine):
  - `com.pulse.contract.AdapterConfigVsFormFieldContractTest` (BUG-67 layer 2.5)
  - `com.pulse.contract.EndpointReferenceContractTest` (BUG-67 layer 2.5)
  - (`CodegenExampleSharingRegressionTest`, the 3rd known-pre-existing, PASSED this run.)
- New tests, all green: MutationToolServiceTest (13), OpToCommandMapperTest (1), ReadToolSeesStagingTest (1), CompositionApplyIntegrationTest (3), GraphDriverTurnTest (1), ChatControllerTest (7 incl. 4 new decision cases). Foundation OpQueueStagingTest + CompositionGraphTest still green.
- DID NOT regress: ChatServiceTest, ChatToolExecutorTest, ChatToolsTest, ChatControllerSseContractTest, ChatTurn2CallIdPairingContractTest, ChatToolDefinitionLintTest.

## Incidental fix (required for correctness, not gold-plating)
- `PlanService.coerceMap` could silently drop persisted `plannedCommands` payloads on the H2 jsonb round-trip: the H2 JSON FormatMapper deserializes nested objects into a `scala.collection.immutable.Map` (not a `java.util.Map`), which `coerceMap`'s `instanceof Map` + Jackson-bean fallback turned into `{}`. Added `reflectiveMapBridge` (no compile-time Scala dependency — iterates the Scala map's `iterator()`/`Tuple2`) so composition.* command payloads survive apply. This latent bug was never caught because every existing apply(planId) test registers payload-agnostic handlers; the composition.* handlers are the first to depend on the persisted payload.

## Phase 5 — streaming-to-canvas + diff
(pending)

## Phase 6 — client reconcile + Dagre auto-layout
(pending)

## Phase 7 — snapshot/revert + history compaction
(pending)

## Phase 8 — 7 per-stage prompts + dump-all catalog + 5 category guides

New `com.pulse.chat.prompt` package (authored from `docs/ui/chat-prompts/01-system-prompts.md` §1-§8,
`02-tools-and-context.md` §2, `03-best-practices.md` §1-§5; retains the `EXISTING-PROMPT-KEEP-LIST` coverage):

- `backend/src/main/java/com/pulse/chat/prompt/StagePrompts.java` — the 7 per-stage system-prompt TEXTS
  authored VERBATIM from fragment 01 §1-§7 (ROUTER / DISCOVERY / COMPOSER / CONFIGURE / PROVISION / PLANNER /
  RESPONDER). DE-voice review is a separate operator pass; this only wires the drafted text. The Planner text
  instructs a BARE `| Step | Blueprint | Name | Purpose |` markdown table (never code-fenced) so the live
  `chat-dag.tsx` `parsePipelineTable` renders the in-chat DAG.
- `backend/src/main/java/com/pulse/chat/prompt/SharedPreamble.java` — §8 shared preamble + cross-cutting blocks.
  RETAINS live `PulseSystemPrompt.IDENTITY` / `ABSOLUTE_RULES` (preamble), `MEDALLION_RULES` (cross-cutting),
  `CONNECTOR_VOCABULARY` + `RUNTIME_FIELDS_PUNCH_LIST` (→ Provision `provisionRetained()`), `PLANNER_PACKET`
  (→ Planner `plannerRetained()`). Adds §8e completion-proof, §8f entity-directory anti-hallucination, §8g
  page-map, §8h draft-labels, §8i deploy-gate notice, §8j guardrails checklist.
- `backend/src/main/java/com/pulse/chat/prompt/BlueprintCatalogBlock.java` — DUMP-ALL cached catalog: every
  active Blueprint as a TIGHT entry (key · category · layer · in/out ports · key-params) read from
  `BlueprintRepository.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active")`. Wrapped in a
  `CACHE_MARKER` sentinel for a FUTURE provider `cache_control` breakpoint (INERT today — the live request body
  does not emit `cache_control`). Hybrid token-bloat fallback implemented as `renderSummaries()` (summaries +
  on-demand `get_blueprint_detail`), documented in the class javadoc.
- `backend/src/main/java/com/pulse/chat/prompt/CategoryGuides.java` — the 5 category guides (Ingestion /
  Transform / Modeling / Data Quality / Orchestration) consolidated from fragment 03 §1-§5; folds
  SINK/DESTINATION → Transform (writer tail) and CONTROL → Orchestration (`forCategory`).
- `backend/src/main/java/com/pulse/chat/prompt/ActiveModeBlock.java` — §8b ACTIVE MODE injection: reads
  `RuntimeAuthorityService.getActivePersona()` (GCP_PULSE/DPC_PULSE) and bakes the Mode + storage mapping
  (GCP: bronze/silver=iceberg_bq_managed, gold=bq_native; DPC: all=parquet on S3A) as a per-deployment CONSTANT.
- `backend/src/main/java/com/pulse/chat/prompt/ContextWrappers.java` — per-turn context-tag wrappers
  `<current_composition>` / `<dataset_schemas>` / `<selected_step>` / `<run_status>` + the plain-text
  conversation-summary block + `stripContextTags()` (strips the wrappers from streamed text). The live
  dataset-schema injection is REUSED (not duplicated) via `ChatService.buildTurnContextPrompt`.
- `backend/src/main/java/com/pulse/chat/prompt/StagePromptAssembler.java` — Spring component that composes each
  stage's OWN system prompt (preamble + stage body + the cross-cutting/Mode/catalog/guides the stage needs +
  the live per-turn context appended from `ChatService.buildTurnContextPrompt`).

Wiring (graph path only; legacy single-loop `buildSystemPrompt` left intact):

- `backend/src/main/java/com/pulse/chat/orchestration/GraphDriver.java` — injects `StagePromptAssembler`; each
  LLM stage now calls `applyStageSystemPrompt(ctx, stage)` to SWAP the message-list system prompt (index 0)
  with its per-stage assembly before `llmAdapter.stream`. `handleLLMMode` (loop path) is untouched.
- `backend/src/main/java/com/pulse/chat/service/ChatService.java` — added the public accessor
  `buildTurnContextPrompt(tenantId, pipelineId, sessionId)` delegating to the package-private
  `buildSystemPrompt(3-arg)`, so the prompt package reuses the live context injection. The package-private
  `buildSystemPrompt` (and its ChatServiceTest assertions) are unchanged.

Test: `backend/src/test/java/com/pulse/chat/prompt/StagePromptAssemblerTest.java` — pure unit (Mockito, no
Spring). Asserts each of the 7 stage prompts contains its key sections; the catalog block lists blueprints
(with layer + ports + cache marker); ACTIVE MODE is injected into composer/configure/provision/planner (GCP +
DPC); the 5 category guides are present and fold SINK→Transform / CONTROL→Orchestration; the hybrid fallback
emits summaries only; the context-tag stripper removes wrappers.

What's wired: 7 stage prompts ✅ · dump-all catalog ✅ (cache marker inert/forward) · 5 category guides ✅ ·
ACTIVE MODE injection ✅ · per-turn context-tag wrappers ✅.

fastPrTest result (`cd backend && ./gradlew fastPrTest`): 3115 tests completed, 3 failed — and all 3 are the
KNOWN PRE-EXISTING failures named in the handoff:
- `AdapterConfigVsFormFieldContractTest` (AdapterConfigVsFormFieldContractTest.java:155)
- `EndpointReferenceContractTest` (EndpointReferenceContractTest.java:150)
- `CodegenExampleSharingRegressionTest` (H2 SQL grammar; CodegenExampleSharingRegressionTest.java:47)
No NEW failures. `GraphDriverTurnTest` (exercises the per-stage prompt swap path) and the prompt unit test pass.

Deferrals: (1) provider `cache_control` on the dumped catalog is a forward item — the marker/structure exist
but the live `ChatService.streamStage` request body does not emit a `cache_control` field, so caching is not
enforced. (2) Router/Responder stage bodies in the current `GraphDriver` do not invoke the LLM (router routes
deterministically to composer; the responder reply is streamed during the LLM stage), so their assembled
prompts are authored and unit-tested but not yet hit on the live path. (3) Targeted per-blueprint config
guidance + `<blueprint_config_guidance>` / `<session_facts>` tag injection (fragment 02 §2.2) is carried as
guidance text in the stage bodies; the live `buildSystemPrompt` already injects session facts + targeted
generation packets, reused via `buildTurnContextPrompt`.

## Phase 9 — new discovery/validation read tools
See "Phase 9 + Phase 5 (read tools + events)" below.

---

## Phase 9 + Phase 5 (read tools + events)

### Phase 9 — new discovery + validation read tools (all read-only; no canonical/Command-Log writes)
Registered in `ChatTools.getToolDefinitions()` (`backend/src/main/java/com/pulse/chat/service/ChatTools.java`),
routed in `ChatToolExecutor.execute()` switch (`.../chat/service/ChatToolExecutor.java`), logic in the new
focused, unit-testable `ChatValidationToolService` (`.../chat/service/ChatValidationToolService.java`).

- **T10 `get_step_schema`** — RENAME of live `get_upstream_schema`. Handler `ChatToolExecutor.getStepSchema(...)`;
  switch case `case "get_step_schema", "get_upstream_schema" -> getStepSchema(args)` keeps the deprecated alias
  routing to the same handler (existing tests/prompts unbroken). Both names also handled in `getNavigationPath`.
- **T8 `get_composition_overview`** — compact "N steps, M wires, layers present, open/unwired required ports,
  unresolved-schema count" off `CompositionService.getComposition(activeVersionId)`.
- **T9 `get_blueprint_op_list`** — reads `Blueprint.getSchemaBehavior()` (`ops` array). NOTE: on this branch the
  migration dir DOES contain `V153__builder_op_lists_and_param_tiering.sql` (head is V154), so `schema_behavior`
  IS seeded for gate blueprints — the tool returns the real op-list. When a blueprint row has no seeded behavior
  it falls back to declared ports/params with a clear "op-list not yet seeded (V153 pending)" note (per brief).
- **T11 `validate_structure`** — orphans / cycles (DFS) / reachability / unwired-required-ports over the version
  composition (graph checks, no LLM).
- **T12 `validate_configuration`** — per-step required-param + unwired-required-port completeness vs the Blueprint
  contract; folds in `check_table_contract_readiness` (ChatReadToolHandler) as the runtime punch-list.
- **T13 `validate_plan`** — INTERIM (§7.16 #15): `validate_structure` + `validate_configuration` +
  contract-readiness. Result is explicitly labelled INTERIM; the real deterministic-Builder compile pre-flight
  (#1/#2) is not on this branch.
- **T14 `validate_sql_expression`** — parse-only / declared via existing
  `com.pulse.expression.service.ExpressionValidationService.validate(...)`; returns parse-valid/invalid +
  diagnostics + referenced columns; optional `input_schemas` flags unknown columns. The schema-returning
  CALCITE-PHASE-2 branch (#6) is not wired here.

Lint compliance: the `validate_sql_expression` nested `input_schemas` schema is built by
`ChatTools.validateSqlInputSchemasProp()` so every `type:"object"` has `properties` and every `type:"array"`
has `items` — `ChatToolDefinitionLintTest` still passes.

### Phase 5 — SSE events + diff (GraphDriver, small/localized)
`backend/src/main/java/com/pulse/chat/orchestration/GraphDriver.java`:
- Event-name constants added: `EVENT_TOOL_PROGRESS="tool_progress"`, `EVENT_QUESTIONS="questions"`,
  `EVENT_MESSAGES_COMPACTED="messages_compacted"` (the last is the Phase-7 compaction-path name; constant exists
  for the frontend contract, not triggered here).
- `tool_progress` — emitted once per tool at call start (`emitToolProgress(ctx, name, "started")`), distinct
  from the existing `tool_call` event.
- `questions` — `emitQuestionsIfAsking(...)` on a terminal text reply from the elicitation stages
  (DISCOVERY/PROVISION/CONFIGURE) whose content contains a question; conservative + empty-safe.
- `candidate_graph` + `plan` payloads now carry the `compareGraphs` diff via `diffPayload(ctx)` =
  `CompareGraphs.compare(ctx.canonicalSnapshot, ctx.staging)` → `{changeCount, instanceStatus{ref->STATUS},
  wireStatus[]}`. (`plan` already carried `changeCount`; now also carries the full per-instance/per-wire diff.)
  Edits confined to the allowed emit helpers in GraphDriver; nothing else under `chat/orchestration/` touched;
  `CompareGraphs.java` unchanged.

### Tests
New: `backend/src/test/java/com/pulse/chat/service/ChatValidationToolServiceTest.java` — 14 cases:
overview counts steps/wires (2 steps, 1 wire, bronze+silver, 2 unresolved schemas); structure detects ORPHAN,
CYCLE, UNWIRED_REQUIRED_PORT, and passes a clean graph; configuration flags MISSING_REQUIRED_PARAM + punch-list;
validate_plan labelled INTERIM; op-list reads seeded schema_behavior AND falls back with "V153 pending";
sql-expression parse-valid / parse-invalid / unknown-column.
Added to `ChatToolsTest.java`: `phase9ReadToolsAreRegistered` (all 8 names), `get_step_schema` required-args,
`validate_sql_expression` required-args, `get_blueprint_op_list` V153 description.
Constructor arg `ChatValidationToolService` added to 5 existing `new ChatToolExecutor(...)` test call sites
(ChatToolExecutorTest x2, ReadToolSeesStagingTest, SchemaDiscoveryChatToolTest, PlanAddStepPolicyRejectionTest).

### fastPrTest result
`cd backend && ./gradlew fastPrTest` → **3098 tests completed, 2 failed**:
- `com.pulse.contract.AdapterConfigVsFormFieldContractTest` (AdapterConfigVsFormFieldContractTest.java:155)
- `com.pulse.contract.EndpointReferenceContractTest` (EndpointReferenceContractTest.java:150)

Both are in the documented KNOWN PRE-EXISTING set and reference NO chat code (deploy-adapter form-field contract
+ API endpoint-reference contract). `CodegenExampleSharingRegressionTest` (also pre-existing in the brief) did
not fail this run. No new failures introduced; `ChatToolsTest`, `ChatToolExecutorTest`,
`ChatToolDefinitionLintTest`, `ChatServiceTest`, `ReadToolSeesStagingTest`, `SchemaDiscoveryChatToolTest`,
`PlanAddStepPolicyRejectionTest`, and the new `ChatValidationToolServiceTest` all pass.

---

## Phase 6 (frontend canvas + Dagre)

Client-side STAGING reconcile + Dagre auto-layout for the streaming composition canvas (FRONTEND ONLY).
Spec: `docs/ui/chat-prompts/07-orchestration-revert-layout.md` §3 (auto-layout) +
`05-streaming-canvas-protocol.md` §C.5/§C.6 (reconcile + diff) + `IMPL-ui-composition.md` Phase 6.

### Dependency
- Added `@dagrejs/dagre` (n8n's exact dep) to `frontend/package.json` dependencies (`^1.1.4`).
- `npm install` (node_modules was ABSENT): **exit 0** — "added 926 packages, and audited 927 packages in 9s".
  Resolved version installed: `@dagrejs/dagre@1.1.8`.

### Files created
- `frontend/src/lib/auto-layout.ts` — pure, unit-testable Dagre layout. `autoLayout()` (seeds explicit/manual
  positions, computes only unpositioned ghosts, snaps to 16px grid), `isStructuralChange()` (the
  add/remove-instance OR add/remove-wire gate), `graphStructure()`, `snapToGrid()`/`snapPositionToGrid()`.
- `frontend/src/lib/staging-reconcile.ts` — pure reconcile of the `candidate_graph` STAGING snapshot keyed by
  `instanceRef`: `categorize()` ({toAdd,toUpdate,toRemove}), `reconcilePositions()` (preserve manual / drop
  removed), `buildStagingGraph()` (auto-layout ONLY on structural change; status-colored ghost nodes/edges from
  `diff`), `computeChangeCount()` (banner = `diff.changeCount`, with a client-derived fallback).
- `frontend/src/lib/staging-events.ts` — in-process pub/sub bus (mirrors `composition-events.ts`) carrying
  `candidate_graph` / `plan` / `turn_started` / `turn_ended` / `staging_cleared` from the SSE reader to the canvas.
- `frontend/src/components/pipeline/staging-canvas.tsx` — `@xyflow/react` ghost-layer renderer (dashed
  status-colored borders: added=emerald, modified=amber, deleted=red) + the "Review N changes" banner that opens
  the Plan Preview dialog (before/after summary + per-op reasoning) and posts the plan decision to
  `POST /api/v1/chat/sessions/{sessionId}/plans/{planId}/decision` (approve/reject).
- `frontend/src/lib/auto-layout.test.ts` — 12 tests (structural-change gate fires on add/remove instance + wire,
  NOT on param edit; manual positions respected; grid snap; all nodes placed).
- `frontend/src/lib/staging-reconcile.test.ts` — 10 tests (categorize; reconcilePositions preserves manual &
  drops removed; buildStagingGraph re-lays-out only on structural change & preserves a dragged position across a
  param edit; diff status → ghost nodes/edges; change-count banner math).

### Files changed
- `frontend/package.json` — added `@dagrejs/dagre` dependency.
- `frontend/src/components/pipeline/chat-panel.tsx` — SSE reader now handles `candidate_graph` and `plan`
  frames (emits onto the staging bus) and brackets the turn with `turn_started` / `turn_ended`.
- `frontend/src/components/pipeline/composition-panel.tsx` — mounts `<StagingCanvas sessionId={session?.id}/>`
  above the canonical composition view (reads sessionId from `useChat()`).

### Position store + deferrals
- In-turn node-position map keyed by `instanceRef` is held client-side in the StagingCanvas (a ref) and
  reconciled per snapshot (preserve manual / drop removed). DEFERRED (documented per the handoff): persisting
  positions to instance metadata at Apply — the backend metadata-write path is not wired in this frontend-only
  lane.

### BAR results (real output)
- `npm run lint` → **0 errors, 6 warnings** — all 6 are PRE-EXISTING and in files NOT touched by Phase 6
  (`readiness-category-panel-retirement.test.ts` x4, `credential-dialog.tsx`, `test/setup.ts`). Zero warnings
  from the new Phase 6 files.
- `npm run build` → **green**. "✓ Compiled successfully in 3.5s", TypeScript pass, 13 routes generated.
- `npm run test:unit` → **224 passed, 1 failed** (28 files: 27 passed, 1 failed). The 22 new Phase 6 tests
  (`auto-layout.test.ts` 12 + `staging-reconcile.test.ts` 10) all pass. The single failure is the PRE-EXISTING
  `src/test/contract/orphan-type.test.ts` orphan-type guard listing `SubPipelineInstanceResponse`,
  `TenantGcpRuntimeTopologyForm`, `DeploymentTargetCreateRequest` — all unrelated to Phase 6 (SOR/deploy/tenant
  types); the test's own assertion message pre-declares `SubPipelineInstanceResponse` a "known orphan as of the
  SU-8 META-packet commit." No `orphan-type.test.ts` / `types/index.ts` edits were made in this lane.

---

## Phase 7 (snapshot/revert + compaction)

Snapshot/revert is checkpoint-restore (NOT an inverse plan, ADR 0025 §3 / SPEC §7.16 #13); long sessions
auto-compact with a `messages_compacted` SSE event. Undo = restore the checkpoint snapshot; one turn = one
checkpoint; canonical is write-locked behind Apply, so pre-Apply revert = "drop the staging clone."

### Files (absolute)
- `backend/src/main/java/com/pulse/chat/service/ChatHistoryService.java` — NEW; `truncateMessagesAfter(sessionId,
  anchorMessageId)` deletes the anchor + every message at-or-after it (the n8n `truncateMessagesAfter` analogue,
  `[read I]`); unknown/blank anchor = no-op.
- `backend/src/main/java/com/pulse/chat/service/HistoryCompactor.java` — NEW; token-based auto-compaction. Cheap
  token estimate (`chars/4`); triggers above `pulse.chat.compaction.token-threshold` (default 96000) *
  `trigger-fraction` (default 0.5); keeps the system message + last `keep-recent` (default 8) verbatim, folds the
  rest into a marked summary block. **Summary is DETERMINISTIC (no-LLM) by default** (concatenation + truncation,
  documented in-class); an optional `BiFunction` summarizer hook accepts `ChatService.reasoningCall` for a real
  summary.
- `backend/src/main/java/com/pulse/chat/orchestration/GraphDriver.java` — EDITED; (a) per-turn snapshot now written
  to serializable `AgentState.COMPOSITION_VIEW` (canonical revert point) + `STAGING_GRAPH` (its clone) at turn
  start so the checkpointer persists them (the live `StagingGraph`/`CompositionView` value objects are NOT
  Serializable, so a Map/List/String projection is checkpointed); (b) `process_operations` writes the advanced
  STAGING projection back to the channel; (c) compaction call + `emitMessagesCompacted` (the sole
  `messages_compacted` emitter); (d) `canonicalGraphPayload`, `restoreCompositionFromSnapshot` (deterministic
  snapshot reconstruction onto canonical for the applied-turn case), `latestCheckpointCompositionView` (reads the
  turn-start snapshot via `CompiledGraph.getState`).
- `backend/src/main/java/com/pulse/chat/service/ChatService.java` — EDITED; `restoreToTurn(sessionId,
  anchorMessageId)` (truncate chat → restore composition from the checkpoint snapshot → phase reset to build);
  pre-Apply revert via `decidePlan` reject/modify now returns the canonical `restoredGraph` (drop the staging
  clone); field-injected `ChatHistoryService` (constructor untouched).
- `backend/src/main/java/com/pulse/chat/controller/ChatController.java` — EDITED; `POST
  /api/v1/chat/sessions/{sessionId}/turns/{anchorMessageId}/restore`.

### Tests
- `backend/src/test/java/com/pulse/chat/service/HistoryCompactorTest.java` — NEW (5 tests, H2-free): token
  estimate = chars/4; below-threshold no-op; above-threshold compacts + emits a marked summary block + shrinks
  estimated tokens + keeps the recent tail; real-summarizer path; blank-summarizer deterministic fallback.
- `backend/src/test/java/com/pulse/chat/service/ChatHistoryServiceTest.java` — NEW (4 tests, in-memory repo):
  truncate deletes anchor + everything after; unknown anchor no-op; blank/null anchor no-op; anchor-at-head wipes
  the session.
- `backend/src/test/java/com/pulse/chat/orchestration/PreApplyRevertStagingTest.java` — NEW (1 test): pre-Apply
  revert drops the staging clone and the canonical snapshot is never mutated (diff back to zero changes).
- `backend/src/test/java/com/pulse/chat/orchestration/ChatCheckpointerRoundTripIT.java` — NEW; **POSTGRES LANE
  ONLY** (`@Tag("integration")`, `@ActiveProfiles("postgres-it")`, `pulse.chat.checkpointer=postgres`). Asserts the
  `langgraph4j-postgres-saver` `PostgresSaver` is wired and that a turn checkpoints (thread=sessionId) + `getState`
  restores the per-turn `COMPOSITION_VIEW` snapshot verbatim. **CANNOT run on H2** — the langgraph4j Postgres saver
  schema needs real Postgres; it lands in the `backendIntegrationTest` lane, NOT `fastPrTest` (verified excluded by
  the `*IT.java` + `@Tag("integration")` rules in `build.gradle.kts`).

### BAR results (real output)
- `cd backend && ./gradlew fastPrTest` → **3125 tests completed, 2 failed.** Both failures are on the KNOWN
  PRE-EXISTING list (NOT mine): `AdapterConfigVsFormFieldContractTest` (BUG-67 adapter-config-vs-form contract) +
  `EndpointReferenceContractTest` (BUG-67 endpoint-reference contract). (`CodegenExampleSharingRegressionTest` did
  not appear in this lane's run.) No other new failures.
- New Phase 7 units all green: `HistoryCompactorTest` 5/0/0, `ChatHistoryServiceTest` 4/0/0,
  `PreApplyRevertStagingTest` 1/0/0.
- No regression in the protected set: `ChatServiceTest` 9/0/0, `ChatToolExecutorTest` 24/0/0, `ChatToolsTest`
  10/0/0, `ChatControllerSseContractTest` 8/0/0, `ChatTurn2CallIdPairingContractTest` 3/0/0,
  `ChatToolDefinitionLintTest` 1/0/0, `GraphDriverTurnTest` 1/0/0; `com.pulse.chat.orchestration.*` /
  `com.pulse.chat.prompt.*` clean.
- `ChatCheckpointerRoundTripIT` runs in the **postgres-it lane** (`./gradlew backendIntegrationTest`) — it is NOT
  exercised by `fastPrTest` (cannot run on H2).

### Deferrals
- The applied-turn composition restore reconstructs canonical from the snapshot via `CompositionService`'s public
  add/remove/wire methods (deterministic snapshot reconstruction, not an inverse plan). Schema-propagation
  side effects of re-add are best-effort (wrapped in try/catch). Persisting node positions at Apply remains the
  frontend deferral noted in Phase 6.
