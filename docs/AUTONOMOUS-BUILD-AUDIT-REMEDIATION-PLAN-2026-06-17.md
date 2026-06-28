# Autonomous Build Audit Remediation Plan - 2026-06-17

Status: plan captured after independent Codex audit. Evidence tags:

- `[read]` means verified directly from local files.
- `[report]` means a sub-agent finding, reconciled by the orchestrator before use.
- `[run]` means a command was actually run in this session.

This is an execution plan, not a new domain-model decision. No `CONTEXT.md` update or ADR is required by this document alone. If implementation changes the domain language or locks a hard-to-reverse architectural trade-off, update `CONTEXT.md` or create an ADR under `docs/adr/` at that point.

## Audit Verdict

The prior Claude audit is directionally correct: major pieces were built but are not live through the default product entrypoints.

Verified active in default or normal boot:

- Schema op-engine is live on the Designer/schema path when a blueprint has a `schema_behavior` op-list. `[read]` `backend/src/main/java/com/pulse/pipeline/service/SchemaPropagationService.java:879`
- V153/V154 catalog migrations exist under Flyway's default migration path. `[read]` `backend/src/main/resources/application.yml:31`
- Inline `[[ ]]` mnemonic lowering is active in live codegen. `[read]` `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java:237`
- Global shell with left app sidebar and right chat drawer exists. `[report]`
- Add Blueprint dialog is functionally wired. `[report]`

Verified dormant, unreachable, or not built:

- Builder codegen op-engine is built but dormant. `CodeGenerationService.generate()` still calls legacy branches and never invokes `CodegenOpEngine`. `[read]` `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java:211`, `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java:1738`
- `SqlModel` and `SourceSQL` catalog rows exist, but live codegen is not wired. `[read]` `backend/src/main/resources/db/migration/V153__builder_op_lists_and_param_tiering.sql:625`, `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java:1654`
- `SqlModel` Calcite schema resolution is built when reached; `SourceSQL` source-prepare remains TODO. `[read]` `backend/src/main/java/com/pulse/pipeline/service/SchemaPropagationService.java:989`
- Chat LangGraph4j graph, staging, op queue, and checkpointer are built but default runtime remains `loop`. `[read]` `backend/src/main/java/com/pulse/chat/service/ChatService.java:332`, `backend/src/main/java/com/pulse/chat/service/ChatService.java:501`
- Frontend pipeline workspace is not the locked canvas-first UX. Missing: canvas-first main surface, persistent right step inspector, top action bar, bottom run logs, outline, schema diff, and conflict overlay. `[report]`
- Frontend type has `ui_construct`, but not `tier` / `derivedFrom`; V153 does not seed `ui_construct` hints into catalog params. `[read]` `frontend/src/types/index.ts:222`, `[report]`

Corrections to the Claude audit:

- Add Blueprint should not be treated as a missing surface; it is functionally wired as an on-demand dialog.
- Staging canvas is mounted and event-driven on the frontend. It appears dormant because the backend graph path is off by default and therefore does not emit the graph staging events.
- The frontend type already has `ui_construct`; the missing pieces are catalog seeding and `tier` / `derivedFrom`.

## Tests To Distrust As Completion Evidence

These tests may still be useful, but their previous green status must not be treated as proof that the default running product uses the new path:

- `com.pulse.blueprint.V153BuilderOpListsTest`
- `com.pulse.codegen.opengine.CodegenOpEngineTest`
- `com.pulse.codegen.opengine.handlers.*`
- `com.pulse.codegen.opengine.Phase4DagAndConfigTest`
- `com.pulse.codegen.service.MnemonicLoweringTest`
- `com.pulse.codegen.service.CodeGenerationServiceTest`
- `com.pulse.deploy.controller.RepresentativeStaticDeployabilityProofIT`
- `com.pulse.deploy.CompositionToCodegenToPackageE2EIT`
- `com.pulse.chat.orchestration.GraphDriverTurnTest`
- `com.pulse.chat.orchestration.CompositionApplyIntegrationTest`
- `com.pulse.chat.orchestration.ChatCheckpointerRoundTripIT`
- `com.pulse.chat.orchestration.OpQueueStagingTest`
- `com.pulse.chat.orchestration.MutationToolServiceTest`
- `com.pulse.chat.orchestration.ReadToolSeesStagingTest`
- frontend construct vitests as evidence for the full pipeline workspace

Reason: `backend/src/main/resources/application-test.yml` uses H2, `ddl-auto=create-drop`, and `flyway.enabled=false`, so many tests do not prove real Postgres/Flyway/default boot behavior.

## Parallel Implementation Plan

Use separate worktrees and producer/evaluator separation. No implementer report is accepted as proof until a different evaluator reruns the default-entrypoint evidence.

### 1. Trust Baseline Lane

- Agent T1: fix or document real Postgres/Flyway test setup from `docker-compose.yml`; ensure local tests can reach `localhost:5432` with the expected credentials or explicit `DB_*` env vars.
- Agent T2: create a test inventory that labels tests as `default-entrypoint`, `opt-in-path`, `unit-only`, `static-only`, or `false-green-risk`. Status: evidence captured in `docs/evidence/TRUST-BASELINE-TEST-INVENTORY-2026-06-17.md`.
- Agent T3 evaluator: rerun default-entrypoint proofs after each merge and record results in `docs/evidence/`.

### 2. Builder Activation Lane

- Agent B1: cut `CodeGenerationService.generate()` over to `CodegenOpEngine` for op-listed blueprints. Add an assertion/test proving the op-engine ran through the default `generate()` entrypoint.
- Agent B2: wire live `SqlModel` dbt emission through the existing op handler.
- Agent B3: build `SourceSQL` live emission plus source-query mnemonic lowering.
- Agent B4: implement `SourceSQL` source-prepare via connector/JDBC metadata, with declared-schema fallback when the source is unreachable.
- Agent B5 evaluator: run an anchor Builder proof against real V153/V154 seeded catalog, not H2 hand fixtures.

### 3. Chat Activation Lane

- Agent C1: make graph mode the default after SSE contract tests pass.
- Agent C2: pin SSE events for `candidate_graph`, plan preview, apply/reject/modify, and staging cleanup.
- Agent C3: switch durable checkpointer defaults for real Postgres boot, or explicitly gate memory mode as dev-only.
- Agent C4 evaluator: run one default chat turn that stages composition changes and proves the UI receives staging events.

### 4. Catalog / Controls Wiring Lane

- Agent K1: add a migration to seed `ui_construct` hints where the construct spec requires them.
- Agent K2: add `tier` and `derivedFrom` to frontend blueprint param types.
- Agent K3: render derived params read-only/inspectable in the configuration UI.
- Agent K4: add tests using real seeded blueprint payloads, not only hand-authored component props.

## Wave 1 Implementation Results

Status as of this file update:

- Builder B1: `CodeGenerationService.generate()` now routes op-listed dbt blueprints through `CodegenOpEngine`, records `codegenOpEngineInstances` in run metadata, and tags generated dbt artifacts with `codegenEngine=CodegenOpEngine`. Special non-op-listed legacy dbt emitters remain on legacy branches by design. `[run]`
- Builder B2: `SqlModel` now emits V153 `steps` through the op-engine path instead of falling back to empty/default SQL. `[run]`
- Builder B3: `SourceSQL` now emits a JDBC `.option('query', ...)` PySpark read through the live generation path, including runtime substitution of inline `[[ ]]` date mnemonics. `[run]`
- Builder B4: `SourceSQL` source-prepare is wired into `SchemaPropagationService` through `SourceSqlSchemaResolver`. The op-listed `read-source -> add-audit-columns -> write-sink` path now receives source columns without pre-appended audit columns, preventing duplicate audit output; source-unreachable cases fall back to `declared_output_schema`. `[run]`
- Builder B5 generate/package proof: `CodeGenerationPostgresCatalogIT` boots Postgres/Flyway catalog rows, resolves the seeded `SqlModel` blueprint, calls the live `CodeGenerationService.generate()` entrypoint, asserts persisted run/artifact metadata prove `CodegenOpEngine` ran, then builds a package through `DeployController.buildPackage()` with clean Git provenance. This uncovered and fixed the Postgres/Spark classpath JSON mapping issue where `schema_behavior` arrays/maps deserialize as Scala collections; `OpList`, `ParamRef`, and `ResolvedConfig` now normalize those shapes instead of falling back to legacy. `[run]`
- Chat C1: default chat orchestration is now `graph`, with `PULSE_CHAT_ORCHESTRATION=loop` as rollback. `GraphDriverTurnTest` no longer forces the property and asserts the default `ChatService` path is graph mode. `[run]`
- Chat C2-C4: graph plan SSE now carries previewable `steps` and `summary`, the staging canvas posts approve/reject/modify only to the valid session-scoped decision endpoint, Apply clears staging and refreshes canonical composition, and default real boot now uses the Postgres checkpointer while `application-test.yml` explicitly keeps H2 tests on memory. `GraphDriverTurnTest`, `staging-canvas.test.tsx`, and `ChatCheckpointerRoundTripIT` cover these paths. A targeted running-app browser proof opened the Loan Master workspace, opened the Chat drawer, streamed `candidate_graph` + `plan` SSE frames through the real `ChatPanel`, and asserted the staging Review banner, ghost node, and plan preview command step; screenshot saved at `.omx/context/pulse-chat-staging-proof.png`. `[run]` `[app]`
- Catalog/Controls K1-K4: V155 seeds `ui_construct` hints, frontend `BlueprintParamDefinition` carries `tier`/`derivedFrom`, derived params render read-only, and seeded-like control tests cover representative payloads. `[run]`
- Trust Baseline T2: test inventory is captured at `docs/evidence/TRUST-BASELINE-TEST-INVENTORY-2026-06-17.md`. `[read]`
- Trust Baseline T1/T3: repo-local Postgres/Redis were started through OrbStack Docker, using `docker-compose.override.yml`'s host port `5433`; the combined real Postgres/Flyway proof passed for migration smoke, V153/V154 seeded catalog, schema propagation, default Postgres chat checkpointer, and seeded codegen/package. `[run]`
- Workspace UI U1-U9: the pipeline page now opens with a canvas-first workspace shell, top action bar, outline, persistent right inspector with Config / Ports-Schema / Validation / Observability tabs, bottom run-log drawer, node-level input/output/diff badges, and a schema conflict gate with classification, impact radius, and one-click resolution actions. A targeted browser proof logged in as Dev Builder, selected `tenant-home-lending`, opened the seeded Loan Master pipeline, asserted all workspace locators, and saved `.omx/context/pulse-workspace-proof-authenticated.png`. `[run]` `[app]`
- Independent review follow-up: restored an editable workspace step path through `Edit Step`, made the top-bar Save action perform a canonical server refresh/sync, lowered SourceSQL source-prepare mnemonics before JDBC metadata probing, and changed JDBC dialect inference to use connector definition metadata instead of mutable instance names. `[run]`

Still open:

- Live external runtime execution remains separate from this remediation because local Airflow/Spark are not provisioned in this repo.

### 5. Workspace UI Build Lane

- Agent U1: rebuild the pipeline page around a canvas-first workspace, not a stacked-card layout. Status: done. `[run]` `[app]`
- Agent U2: replace modal-only config with a persistent right step inspector. Status: done. `[run]` `[app]`
- Agent U3: implement inspector tabs: Config, Ports/Schema, Validation, Observability. Status: done. `[run]` `[app]`
- Agent U4: add top action bar for Generate, Package, Git branch/commit/PR, and Save. Status: done. `[run]` `[app]`
- Agent U5: add bottom collapsible run logs. Status: done. `[run]` `[app]`
- Agent U6: add graph outline alongside the existing minimap. Status: done. `[run]` `[app]`
- Agent U7: show node-level input schema, output schema, and schema diffs. Status: done. `[run]` `[app]`
- Agent U8: build conflict overlay with classification, impact radius, one-click fix, and Apply gate. Status: done. `[run]` `[app]`
- Agent U9 evaluator: run targeted browser screenshots plus interaction assertions. Status: done for desktop authenticated workspace, mobile workspace, and chat staging proofs. `[run]` `[app]`

## Required Verification Commands

Run these after the relevant lanes land.

Real boot / Flyway:

```bash
docker compose up -d postgres redis
cd backend
DB_PORT=5433 ./gradlew backendIntegrationTest --tests 'com.pulse.config.PostgresFlywayMigrationSmokeIT' --tests 'com.pulse.config.V153V154PostgresCatalogIT'
DB_PORT=5433 ./gradlew backendIntegrationTest --tests 'com.pulse.pipeline.SchemaPropagationE2EIntegrationIT'
```

Original default-port form, when no local override remaps Postgres:

```bash
cd backend
./gradlew backendIntegrationTest --tests 'com.pulse.config.PostgresFlywayMigrationSmokeIT'
./gradlew backendIntegrationTest --tests 'com.pulse.config.V153V154PostgresCatalogIT'
./gradlew backendIntegrationTest --tests 'com.pulse.pipeline.SchemaPropagationE2EIntegrationIT'
```

Default chat path after graph activation:

```bash
cd backend
./gradlew test --tests 'com.pulse.chat.controller.ChatControllerSseContractTest'
./gradlew test --tests 'com.pulse.chat.orchestration.GraphDriverTurnTest'
./gradlew test --tests 'com.pulse.chat.orchestration.CompositionApplyIntegrationTest'
./gradlew backendIntegrationTest --tests 'com.pulse.chat.orchestration.ChatCheckpointerRoundTripIT'
```

Builder and package proof after codegen cutover:

```bash
cd backend
./gradlew test --tests 'com.pulse.codegen.opengine.handlers.SqlModelDbtSqlHandlerTest' --tests 'com.pulse.codegen.service.CodeGenerationServiceTest' --tests 'com.pulse.blueprint.V155BlueprintParamUiConstructHintsTest' --tests 'com.pulse.chat.orchestration.GraphDriverTurnTest' --tests 'com.pulse.pipeline.service.SchemaPropagationServiceTest' --tests 'com.pulse.pipeline.controller.SchemaPropagationControllerTest'
DB_PORT=5433 ./gradlew backendIntegrationTest --tests 'com.pulse.codegen.service.CodeGenerationPostgresCatalogIT'
./gradlew test --tests 'com.pulse.deploy.controller.RepresentativeStaticDeployabilityProofIT'
./gradlew test --tests 'com.pulse.deploy.CompositionToCodegenToPackageE2EIT'
```

Frontend:

```bash
cd frontend
npm run test:unit -- src/components/pipeline/staging-canvas.test.tsx src/components/pipeline/configure-transform-dialog.test.tsx
npm run lint
npm run build
```

Runtime proof, with correct interpretation:

```bash
cd backend
./gradlew runtimeNightlyTest --tests 'com.pulse.e2e.runtime.CanonicalLoanMasterAirflowRuntimeIT'
```

This only proves runtime behavior for the current generated artifacts. It proves the new Builder only after `CodeGenerationService.generate()` routes through `CodegenOpEngine` and the evidence confirms that path was reached.

## Acceptance Bar

A capability is not done when classes exist or isolated tests pass. It is done only when all of these are true:

- It is reached by the default product entrypoint.
- It is demonstrated against real seeded catalog data where the feature depends on migrations.
- A test or captured run proves the new path ran, not the legacy path.
- A separate evaluator reruns the proof.
- The result is recorded with `[read]`, `[run]`, or `[app]` evidence, not just `[report]`.
