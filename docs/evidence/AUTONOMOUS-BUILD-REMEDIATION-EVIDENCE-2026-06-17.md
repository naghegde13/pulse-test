# Autonomous Build Remediation Evidence - 2026-06-17

This file records the default-entrypoint remediation evidence for the autonomous-build re-do.
Tags:

- `[read]` code or spec inspection.
- `[run]` local command output.
- `[app]` running-app/browser proof against the real backend.

## Backend Codegen Cutover

- `[read]` `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java` has no `LEGACY_DBT_SPECIAL_BLUEPRINTS` carve-out. Op-listed DBT, PySpark, GX, and DAG-only paths now mark generated artifacts with `codegenEngine=CodegenOpEngine` and fail if an op-listed blueprint is not emitted by the engine.
- `[read]` `backend/src/main/java/com/pulse/codegen/opengine/CodegenOpEngine.java` registers the DBT, PySpark, GX, snapshot, and DAG-only handlers used by the live generator.
- `[read]` `backend/src/main/java/com/pulse/codegen/opengine/handlers/MergeRowsDbtSqlHandler.java` emits incremental merge config with `unique_key` and `incremental_strategy='merge'`.
- `[read]` `backend/src/main/java/com/pulse/codegen/opengine/handlers/TrackHistoryScd2DbtSnapshotHandler.java` emits a dbt snapshot block. It relies on dbt snapshot validity columns rather than re-emitting the old legacy `effective_from/effective_to` SQL aliases.
- `[read]` `backend/src/main/java/com/pulse/codegen/opengine/handlers/ReadSourcePySparkHandler.java` emits JDBC query reads and preserves runtime `[[ ]]` mnemonic resolution.
- `[read]` `backend/src/test/java/com/pulse/codegen/service/CodeGenerationPostgresCatalogIT.java` is the real-seed, default-entrypoint proof. It boots with `postgres-it`, uses Flyway-seeded blueprints, calls `CodeGenerationService.generate(...)`, and asserts `codegenOpEngineInstances`, artifact metadata `codegenEngine=CodegenOpEngine`, and body marker `-- Codegen engine: CodegenOpEngine`.
- `[read]` `CodeGenerationPostgresCatalogIT.generate_reachesCodegenOpEngineForEverySeededOpListedBlueprint` iterates every seeded op-listed blueprint, including `IncrementalMerge`, `SCD2Dimension`, `SnapshotModel`, `GenericRouter`, and `SourceSQL`.
- `[read]` The same IT asserts materialization specifics: `IncrementalMerge` includes `materialized='incremental'`, `unique_key`, and `incremental_strategy='merge'`; `SCD2Dimension` is `DBT_SNAPSHOT` and contains `{% snapshot %}`; `SnapshotModel` is incremental; `SourceSQL` is PySpark with `.option('query', ...)` and `pulse_resolve_mnemonic`; `GenericRouter` emits multiple op-engine artifacts.

## UI Remediation

- `[read]` Top action bar: `frontend/src/app/pipelines/[pipelineId]/page.tsx` calls real backend operations from the bar:
  - Generate: `POST /api/v1/versions/{versionId}/generate`.
  - Package: `POST /api/v1/versions/{versionId}/packages`.
  - Git: workspace context, workspace creation when needed, workspace code generation, branch creation, commit, and PR creation.
  - Save: existing composition persistence path.
- `[read]` Run logs: `page.tsx` builds `RunLogDrawer` entries from backend generation/package/workspace records and composition events. The drawer empty state is explicit: `No backend run records for this version.`
- `[read]` Shared right region: `frontend/src/contexts/chat-context.tsx` and `frontend/src/components/layout/auth-gate.tsx` now make chat and inspector share one drawer. Opening chat clears `rightRegionContent`; selecting a step closes chat and installs `WorkspaceStepInspector`.
- `[read]` Schema diff: `frontend/src/lib/schema-diff.ts`, `frontend/src/components/pipeline/dag-view.tsx`, `frontend/src/components/pipeline/dag-node.tsx`, and the inspector Ports-Schema tab compute and render added, renamed, retyped, and dropped columns.
- `[read]` Conflict overlay: `backend/src/main/java/com/pulse/pipeline/service/SchemaPropagationService.java` exposes a backend preview, `backend/src/main/java/com/pulse/pipeline/service/PipelineService.java` blocks promotion when open conflicts exist, and `frontend/src/components/pipeline/schema-conflict-panel.tsx` requires preview before apply and dispatches the canvas impact event.
- `[read]` C4 caveat for audit: classification and impact radius are backend-produced from persisted schema-conflict details. This is not a claim of full SQL-consumption lineage beyond what schema propagation currently stores.

## Verification Commands

- `[run]` `docker compose up -d postgres redis`
  - Result: failed in this shell because `docker` was not on PATH: `zsh:1: command not found: docker`.
  - Existing Postgres on `DB_PORT=5433` was used for the Postgres/Flyway integration proofs below.
- `[run]` `DB_PORT=5433 ./gradlew backendIntegrationTest --tests 'com.pulse.config.PostgresFlywayMigrationSmokeIT' --tests 'com.pulse.config.V153V154PostgresCatalogIT' --console=plain`
  - Result: passed.
- `[run]` `DB_PORT=5433 ./gradlew backendIntegrationTest --tests 'com.pulse.codegen.service.CodeGenerationPostgresCatalogIT' --console=plain`
  - Result: passed.
- `[run]` `DB_PORT=5433 ./gradlew backendIntegrationTest --tests 'com.pulse.pipeline.SchemaPropagationE2EIntegrationIT' --tests 'com.pulse.chat.orchestration.ChatCheckpointerRoundTripIT' --console=plain`
  - Result: passed.
- `[run]` `DB_PORT=5433 ./gradlew backendIntegrationTest --tests 'com.pulse.blueprint.CodegenExampleSharingRegressionIT' --console=plain`
  - Result: passed.
- `[run]` `DB_PORT=5433 ./gradlew test --console=plain`
  - Result: passed. Plain unit test excludes Docker/Testcontainers-only `suite-a`; the dedicated `suiteA` task remains for that lane.
- `[run]` `cd frontend && npm run lint`
  - Result: passed with 6 pre-existing warnings and 0 errors.
- `[run]` `cd frontend && npm run test:unit`
  - Result: passed, 29 files / 229 tests.
- `[run]` `cd frontend && npm run build`
  - Result: passed.

## Running-App Proof

- `[app]` `node .omx/context/run-chat-staging-proof.mjs`
  - Result: passed.
  - Backend: real Spring Boot backend started by the proof script with `PULSE_CHAT_ORCHESTRATION=graph`.
  - Frontend: real Next dev server.
  - LLM: deterministic OpenAI-compatible stub at the backend boundary via `LLM_BASE_URL`; no browser API/SSE interception.
  - Browser network stubs: `0`.
  - Real pipeline: `01JDEMO0HL0LOANMASTER0001` (`Loan Master`).
  - Observed result:

```json
{
  "fakeLlmCallCount": 2,
  "reviewBanner": "Review 1 change\nOpen Plan Preview \u2192",
  "stagedNode": 1,
  "planPreviewBeforeClick": 1,
  "browserNetworkStubs": 0,
  "backendGraphMode": true,
  "planStep": 1,
  "planBlueprint": 3
}
```

- `[app]` Screenshot: `.omx/context/pulse-chat-staging-proof.png`.

## Previously Over-Trusted Tests Re-Proven

- `com.pulse.codegen.opengine.CodegenOpEngineTest` and handler unit tests remain useful as isolated handler checks, but completion evidence now comes from `CodeGenerationPostgresCatalogIT`.
- The byte-exact/default-entrypoint claim is now anchored to real Postgres + Flyway seed and `CodeGenerationService.generate(...)`, not H2 fixtures.
- The `IncrementalMerge` anti-duplicate guard is explicitly asserted through the generated op-engine artifact.
- The PySpark `SourceSQL` route is explicitly asserted through the generated op-engine artifact.
- The chat staging proof no longer stubs the browser SSE stream; the SSE is backend-produced.

## NOT-LIVE / Limits

- Local Airflow and Spark runtime execution are not live in this repo environment per `AGENTS.md`; this remediation proves static package/deployability and generated artifacts, not actual Airflow/Spark execution.
- The shell could not start Docker because `docker` was unavailable. Postgres/Flyway integration still ran against the existing local Postgres on port `5433`.
- Conflict classification is real backend data flow, but full downstream SQL-column-consumption lineage is not claimed unless schema propagation has persisted that detail for a given conflict.
