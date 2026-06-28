# PULSE — Code Geography (where to look)

This is a **map of where the code lives**, so a new session re-grounds in minutes instead of re-surveying for hours. It is **geography, not gospel** — these are pointers, not verdicts. Open the file and confirm before you rely on anything (the operator distrusts unverified claims, rightly). Verdicts on what works live in `PULSE-MAP.md`. Paths are under `backend/src/main/java/com/pulse/` and `frontend/src/` unless noted. Line numbers drift — treat them as "start here."

## Components → code

**Chat** (Designer's LLM agent) — `chat/`
- `chat/service/ChatService.java` — LLM calls over OpenRouter (`:775` stream, `:954` system-prompt assembly), SSE streaming.
- `chat/service/ChatToolExecutor.java` — executes 40+ tools, real DB writes (`:243` dispatch; `:1251` DQ rules save → `DqExpectationService`; `:1927` step params save → `CompositionService`).
- `chat/service/ChatTools.java` — tool definitions/schemas. `chat/service/PulseSystemPrompt.java` — prompt segments incl. medallion rules (`:352`). `chat/service/PhaseDetector.java` — conversation-phase classifier.

**Builder** (design → code; ADR 0002, 0003) — `codegen/`
- `codegen/service/CodeGenerationService.java` (~3800 lines) — the generator. `generatePySparkJobs` (`:801`, one job per INGESTION instance, JDBC branches `:867-889`, bronze write `:1140-1146` hardcodes `USING DELTA`); `generateDbtModels` (`:1584`, `materialized` choices incl. `ephemeral` `:1625`); blueprint-keyed `if/switch` on `bpKey`. **No LLM here today** — pure templating; ignores blueprint description + examples.
- `codegen/service/CompilePlanService.java` — compile plan, layer resolution (`:210`). `codegen/service/CodegenExampleService.java` — loads `codegen-examples/`, but only feeds Chat + blueprint UI (NOT the generator). `codegen/scan/ForbiddenTokenScanner.java:22` — post-gen secret/hallucination scan. `codegen/service/DbtAssetRegistryService.java` — dbt model-reuse scoring (wired? verify).

**Designer model + Schema Propagation** — `pipeline/`
- Models: `Pipeline`, `PipelineVersion`, `SubPipelineInstance` (params/lake columns), `PortWiring`, `InstancePortSchema` (per-port columns), `SchemaConflict`.
- `pipeline/service/CompositionService.java` — add/wire/reorder instances, persists params. `pipeline/service/SchemaPropagationService.java` — topological (Kahn) schema spread + conflict detection. `pipeline/service/SchemaInferenceService.java` — **LLM** output-schema inference; the deterministic rules are written out in its system prompt (`:212-292`) — proof it could be deterministic. `DqReadinessService`, `StoryGenerationService` also call the LLM.

**Blueprint catalog** — `blueprint/` + data
- `blueprint/model/Blueprint.java` (`:15`; `example_keys` link `:95`). `blueprint/controller/BlueprintController.java`. `blueprint/service/DeprecatedBlueprintCompatibilityService.java`. Seed data: migration `V7` (blueprints), `V81` (blueprint rationalization / deprecations), code samples in `backend/src/main/resources/codegen-examples/`.

**Packager + Deployer** — `deploy/`
- `deploy/.../DeploymentRunOrchestrator` — state machine. `deploy/.../DeploymentTargetAdapter` (interface, 4 legs). `deploy/adapter/local/LocalMaterializationAdapter` — writes the package tree (`package/...`, `:279`). `deploy/adapter/gcp/` — `GcpComposerDataprocAdapter`, `StubComposerDagSyncClient` (deterministic), `DefaultComposerDagSyncClient` (**throws** until real wiring). `deploy/adapter/dpc/` — `DpcAirflowOpenShiftSparkAdapter`, `Default*` clients (**throw**). `deploy/controller/DeployController`.

**Storage layout / format rules / DDL** — `storage/`
- `storage/PathConventionService.java` — physical paths + URI scheme (`schemeFor :163`: gs / s3a / hdfs). `storage/StorageBackendValidator.java` — legal (backend×layer×format) matrix (`:32-38`). `storage/DdlPlanService` — CREATE TABLE plans (overlaps dbt; verify need). `storage/StorageScaffoldService` — real GCS provisioning (gated). `storage/StorageBackendDeployGate`.

**Mode authority** — `runtime/`
- `runtime/RuntimeAuthorityService.java` — per-Mode rulebook (GCP preset `:134`, DPC preset `:156`; legal partition transforms). `runtime/RuntimePersona` (GCP_PULSE | DPC_PULSE). `runtime/RuntimeBindingValidationService.java:71` — LIVE_GCP/LIVE_HDFS "not yet implemented".

**Secrets** — `secret/`
- `secret/.../GcpSecretManagerService.java` — dual mode GCP / local-stub (`:90-140`, ref parse `:193`). `secret/.../CredentialPersistenceService.java:56` — store. `secret/.../CredentialValidationService.java:72` — "validate" only checks the secret **exists**; does NOT dial the source.

**Sources/sinks (Producer Registry)** — `sor/`
- `sor/service/SchemaDiscoveryService.java:223` — `resolveTableSchema` returns a **hardcoded `loan_master`** schema; no live JDBC. Models: `Dataset`, `ConnectorInstance`, `CredentialProfile`, `Domain`. `sor/controller/ConnectorInstanceController`, `DatasetController`. `sor/service/TimeDimensionService` (as-of/business-date).

**Expression Builder** — `expression/`
- `expression/service/ExpressionValidationService.java:45` — Apache Calcite formula validator. `expression/controller/ExpressionController.java:13` — `POST /api/v1/expressions/validate`. Frontend caller: `components/pipeline/expression-input.tsx:87`.

**EBCDIC discovery** — `cobol/` (standalone island; NOT wired into File Ingestion)
- `cobol/service/CobolDiscoveryService.java`, `CobolDiscoveryAssistantService.java` (LLM refine loop), `CobolSparkPreviewService.java` (Cobrix/Spark), `CobolCopybookAnalyzer.java`.

**Supporting** — `command/` (Plan→Apply bus: `CommandService.java:20/:37`, `PlanService.java:217`), `auth/` (`AuthorizationPolicyService.java:45` role matrix; stub user when `pulse.auth.enabled=false`), `git/` (`RepoScaffoldService.java:170-577` tenant-repo tree; `Default GitHubApiClient` gated by `pulse.git.github.enabled`), `broker/` (federated peer-PULSE / remote Airflow), `tenant/` (16-category readiness verdict, GCP topology), `common/` (`BaseEntity` ULID, `Slugify`), `config/` (`GcpEnvironmentConfig` secret-manager-mode, `SecurityConfig`, `TenantConfig`).

## Frontend → code (`frontend/src/`)

- Pages (`app/`): `pipelines/[pipelineId]/` (composition canvas + deploy), `producers/[sorId]/` (SOR + credentials), `targets/`, `blueprints/`, `chat/`, `domains/`, `ebcdic-discovery/`, `settings/` (16-cat readiness, GCP setup, secret manager, deployment targets), `commands/`.
- Pipeline components: `components/pipeline/dag-view.tsx` (xyflow graph), `dag-node.tsx` (schema badges `:141-352`), `composition-panel.tsx` (DQ panel reads stale field `:380` — known bug), `configure-transform-dialog.tsx` (params/schema/examples tabs; schema-graph `:896`), `chat-panel.tsx`, `deploy-panel.tsx`, `expression-input.tsx`.
- SOR components: `components/sor/credential-dialog.tsx` (credential entry — exists; verify it works), `define-dataset-dialog.tsx` (partition columns `:84`).
- `lib/api.ts` — base URL (`NEXT_PUBLIC_API_URL`), Bearer token, `X-Pulse-Tenant-Id` header.

## Migrations & toggles

- Migrations `V1..V151` in `backend/src/main/resources/db/migration/`. Key: `V7` (blueprint seed), `V81` (blueprint rationalization / deprecations), `V84` (`instance_port_schemas`, `schema_conflicts`), `V96` (`storage_backend`/`lake_layer`/`lake_format` + gold-on-GCP CHECK).
- Master "real vs stub" toggles: `pulse.auth.enabled`, `pulse.git.github.enabled`, `pulse.deploy.runtime.{gcp,dpc}.enabled` (the production clients **throw** when true), `pulse.storage.scaffold.live-writes-enabled`, `pulse.gcp.secret-manager-mode`.
- The five LLM call sites (only): `chat/ChatService`, `cobol/CobolDiscoveryAssistantService`, `pipeline/{SchemaInferenceService, DqReadinessService, StoryGenerationService}`. **The Builder (`codegen/`) is NOT one of them.**
