# PULSE Project Context

Last refreshed: 2026-05-14
Current integration branch at refresh: `codex-master-integration-sequencing`
Reference integration commit at refresh: `eda7145` (`Keep deployed runtime packages independent of Pulse`)

This file is the first-read context pack for a new agent session. It should let an agent begin useful PULSE work without rediscovering the architecture, test lanes, and recent integration decisions from scratch.

If this file conflicts with code, trust code for current behavior. If code conflicts with the architecture ledger, treat that as an implementation gap and check the ledger plus the relevant plan before changing direction.

## What PULSE Is

PULSE is an AI-assisted enterprise data pipeline builder.

Core product areas:

- Tenant-scoped pipeline design and lifecycle management.
- SOR, connector, dataset, and domain registry.
- AI chat with tool-calling that can plan and apply product-state mutations.
- Pipeline composition as blueprint instances plus port wiring.
- Deterministic code generation for Airflow DAGs, PySpark, dbt, GX/DQ, config, and runtime helper artifacts.
- Git identity, repository scaffolding, developer workspace, package, release, and deploy workflows.
- Runtime authority for GCP and DPC/on-prem personas.
- Static deployability proofs in place of local live Airflow/Spark execution for most development.

PULSE is a design-time and deployment-time authority. Generated higher-environment runtime packages must run standalone without calling back into PULSE services.

## Read First

Read these files before coding in a fresh session:

1. `/Users/aameradam/projects/dev/PULSE-integration/AGENTS.md`
2. `/Users/aameradam/projects/dev/PULSE-integration/docs/ARCHITECTURE_LEDGER.md`
3. `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/master-implementation-sequencing-plan-2026-05-13.md`
4. `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/codex-implementation-prompt-2026-05-13.md`
5. `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/deploy-runtime-orchestration-boundary-architecture-plan-2026-05-14.md`
6. `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/advance-time-dimension-airflow-runtime-state-architecture-plan-2026-05-14.md`
7. `/Users/aameradam/projects/dev/PULSE-integration/docs/testing/backend-test-lanes.md`
8. `/Users/aameradam/projects/dev/PULSE-integration/backend/build.gradle.kts`
9. `/Users/aameradam/projects/dev/PULSE-integration/frontend/package.json`
10. `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/resources/application.yml`

Read migrations before assuming schema. The architecture-heavy state is mostly Flyway-backed.

Important seed/catalog migrations:

- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/resources/db/migration/V7__blueprints_catalog.sql`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/resources/db/migration/V10__sor_connector_catalog.sql`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/resources/db/migration/V60__rbac_auth.sql`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/resources/db/migration/V63__periodicity_temporal.sql`

## Current Architecture Invariants

Use `/Users/aameradam/projects/dev/PULSE-integration/docs/ARCHITECTURE_LEDGER.md` as the durable decision record. These are the high-risk invariants that prevent repeated architecture mistakes:

- Runtime persona is deployment-level authority. A deployed PULSE instance is either `GCP_PULSE` or `DPC_PULSE`; chat must not ask users to choose DPC/GCP per pipeline leg.
- Tenant/env runtime bindings configure physical resources allowed by the active persona. Opposite-persona resources are diagnostic evidence, not active routing authority.
- Blueprint instances own canonical storage/lake fields as first-class API/entity fields, not hidden params. Use `BlueprintInstanceConfigurationService` for storage/lake extraction, validation, legacy normalization, and persistence.
- `Pipeline.defaultStorageBackend` is the pipeline-level default. `SubPipelineInstance.storageBackend`, `lakeLayer`, `lakeFormat`, and related fields are instance-level authority.
- `StorageAuthorityFacade`, table contracts, dataset landing contracts, runtime projections, and deploy readiness services are now the storage/package/deploy read surfaces. Do not bypass them by reading raw storage backend rows or rebuilding paths ad hoc.
- `PathConventionService` still exists, but it is a compatibility/helper surface. It is not the long-term authority for physical table contracts.
- Chat mutation must use plan/apply semantics. `plan_*` tools are side-effect-free except for creating plan records; `apply_plan(plan_id)` is the generic mutation path.
- Chat SSE tool results use the structured envelope fields `planCreated`, `mutationApplied`, `planId`, `commandIds`, `affectedEntities`, `refreshHints`, and `message`.
- Deprecated or compatibility blueprints are read-only/add-blocked through the blueprint compatibility services. Do not reopen deprecated blueprint add/mutation paths.
- Git repository attachment and Git credentials are separate. Tenant repo/scaffold config belongs to tenant/repo state; user credentials belong to user Git identity.
- Developer workspaces are package/release authority. Promotion needs exact-head acceptance and PR binding checks.
- Stable Airflow DAG IDs are runtime identity, not a dev-only convenience. They give deployed standalone Airflow DAGs durable names across regeneration, package versions, scheduling, monitoring, and cross-substrate invocation.
- Deploy targets are Airflow only: Composer for GCP or on-prem/DPC Airflow for DPC. Deploy time must not submit PySpark/Dataproc/Spark jobs directly. Spark/dbt/GX execution happens when Airflow runs the DAG.
- Higher-environment runtime packages must not require PULSE APIs, `PULSE_API_URL`, peer PULSE endpoints, or an internal PULSE broker. The corrected cross-substrate model is direct Airflow-to-Airflow/runtime config, not PULSE-to-PULSE.
- `AdvanceTimeDimension` is a blueprint and must compile to standalone runtime. Its historical PULSE API callback is not valid for promoted environments. Follow ARCH-019 and the AdvanceTimeDimension runtime-state plans before touching it.
- Optional data-plane `date_dim` tables are normal package assets when downstream SQL/dbt needs them; they are not PULSE control-plane state.

## Recent Integrated Architecture

The current integration branch has absorbed the large Codex, Claude, and Droid workstreams and their follow-ups.

Major delivered areas:

- Git identity and selective tenant repo scaffold.
- Runtime binding/persona authority foundation.
- Broker trust/target foundation revised to avoid peer PULSE runtime dependencies.
- Active blueprint contracts, add surfaces, deprecated compatibility, and orchestration split.
- Blueprint instance field authority.
- Chat plan/apply contract and structured tool-result SSE.
- Workspace foundation and workspace release flow.
- Table contracts, dataset landing contracts, physical design fields, DDL plans, storage preview/readiness APIs.
- Runtime projection, source package manifest v2, entrypoint catalog, deploy readiness blockers.
- Pipeline slug and orchestration namespace services.
- Direct Airflow remote invocation model for cross-substrate runtime packages.
- Airflow runtime support package plans for stateful runtime helpers such as AdvanceTimeDimension.

Recent architecture docs created for deferred or corrected design:

- `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/deploy-runtime-orchestration-boundary-architecture-plan-2026-05-14.md`
- `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/advance-time-dimension-airflow-runtime-state-architecture-plan-2026-05-14.md`
- `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/advance-time-dimension-airflow-runtime-state-plan-2026-05-14.md`
- `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/advance-time-dimension-composer-installation-2026-05-14.md`
- `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/advance-time-dimension-onprem-airflow-installation-2026-05-14.md`
- `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/airflow-callback-runtime-policy-architecture-plan-2026-05-14.md`
- `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/airflow-callback-runtime-policy-2026-05-14.md`
- `/Users/aameradam/projects/dev/PULSE-integration/docs/architecture/plan-draft-ref-aliasing-architecture-plan-2026-05-14.md`

The older ARCH-008 peer-PULSE broker docs may contain stale PULSE-mediated runtime language. The integrated code and newer handoff establish this correction: deployed runtime code talks directly to target Airflow/runtime endpoints via projected config/secrets and must not call PULSE.

## Subsystem Map

Use this map to load focused context rather than reading the entire repository.

### Chat, Planning, and Tool Calling

- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/chat/service/PulseSystemPrompt.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/chat/service/ChatTools.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/chat/service/ChatToolExecutor.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/chat/service/ChatService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/chat/model/ToolResult.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/chat/plan/StepParamsForbiddenKeyValidator.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/chat/plan/ChatPlanErrorCodes.java`
- `/Users/aameradam/projects/dev/PULSE-integration/frontend/src/components/pipeline/chat-panel.tsx`

### Composition, Blueprint Instances, and Field Authority

- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/pipeline/service/CompositionService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/pipeline/controller/CompositionController.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/pipeline/service/BlueprintInstanceConfigurationService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/blueprint/model/BlueprintSurface.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/blueprint/service/DeprecatedBlueprintCompatibilityService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/frontend/src/components/pipeline/composition-panel.tsx`
- `/Users/aameradam/projects/dev/PULSE-integration/frontend/src/components/pipeline/configure-transform-dialog.tsx`

### Runtime Persona and Runtime Bindings

- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/runtime/`
- `/Users/aameradam/projects/dev/PULSE-integration/frontend/src/types/runtime.ts`
- `/Users/aameradam/projects/dev/PULSE-integration/frontend/src/components/runtime/runtime-persona-badge.tsx`
- `/Users/aameradam/projects/dev/PULSE-integration/frontend/src/components/runtime/runtime-bindings-panel.tsx`

### Storage, Table Contracts, Landing Contracts, and DDL

- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/storage/contract/service/TableContractService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/storage/contract/service/TableContractProjectionService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/storage/contract/service/DatasetLandingContractService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/storage/contract/service/DdlPlanService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/storage/contract/service/StorageAuthorityFacade.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/storage/contract/controller/StorageContractController.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/storage/PathConventionService.java`

### Code Generation and Compile Plans

- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/codegen/service/CompilePlanService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/codegen/controller/CodeGenController.java`
- `/Users/aameradam/projects/dev/PULSE-integration/frontend/src/components/pipeline/code-editor-panel.tsx`

### Package, Deploy, Runtime Projection, and Airflow Boundary

- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/deploy/service/PackageService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/deploy/controller/DeployController.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/deploy/preflight/DeploymentPreflightService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/deploy/preflight/PreflightCheckCode.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/deploy/projection/service/RuntimeProjectionService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/deploy/projection/controller/RuntimeProjectionController.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/deploy/adapter/gcp/GcpComposerDataprocAdapter.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/deploy/adapter/dpc/DpcAirflowOpenShiftSparkAdapter.java`
- `/Users/aameradam/projects/dev/PULSE-integration/frontend/src/components/pipeline/deploy-panel.tsx`

Deploy adapters should materialize/publish/sync/activate Airflow artifacts. They must not submit PySpark directly during deploy. Any smoke run should trigger Airflow and poll an Airflow DAG run.

### Orchestration Namespace and Scheduling

- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/pipeline/service/OrchestrationNamespaceService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/pipeline/service/PipelineSlugService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/pipeline/controller/OrchestrationNamespaceController.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/pipeline/service/DatasetScheduleService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/pipeline/model/EventRef.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/deploy/runtime/AirflowRuntimeClient.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/deploy/runtime/StubAirflowRuntimeClient.java`
- `/Users/aameradam/projects/dev/PULSE-integration/frontend/src/components/pipeline/orchestration-namespace-preview.tsx`

### Git Identity, Repository Scaffold, Workspace, and Release Flow

- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/git/controller/GitController.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/git/service/UserGitIdentityService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/git/service/GitCredentialResolver.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/git/service/RemoteGitService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/git/service/RepoScaffoldService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/git/workspace/`
- `/Users/aameradam/projects/dev/PULSE-integration/frontend/src/components/pipeline/git-panel.tsx`

### SOR, Connectors, Datasets, and Time Dimension

- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/sor/controller/SORController.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/sor/controller/ConnectorInstanceController.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/sor/controller/DatasetController.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/sor/model/Dataset.java`
- `/Users/aameradam/projects/dev/PULSE-integration/backend/src/main/java/com/pulse/sor/service/TimeDimensionService.java`
- `/Users/aameradam/projects/dev/PULSE-integration/frontend/src/app/producers/[sorId]/page.tsx`

`AdvanceTimeDimension` needs ARCH-019 implementation before it can be considered standalone higher-env safe.

## Testing and Verification

The test matrix is part of the architecture. Choose the smallest gate that proves your change, but use the full gates for integration.

### Backend Gradle Test Lanes

Run from `/Users/aameradam/projects/dev/PULSE-integration/backend`.

- `./gradlew test`
  - Local broad backend gate.
  - Runs unit, slice, Spring, static proof, and non-runtime integration classes.
  - Excludes tests tagged `runtime`.
  - Excludes Spark/Cobrix COBOL local parser tests because they need a different ANTLR runtime.
- `./gradlew fastPrTest`
  - Fast PR lane.
  - Unit and slice tests only.
  - Excludes `*IT`, `@Tag("integration")`, and `@Tag("runtime")`.
  - Uses H2/application-test profile.
- `./gradlew backendIntegrationTest`
  - Postgres integration PR lane.
  - Includes `*IT`; excludes `@Tag("runtime")`.
  - Defaults to `spring.profiles.active=postgres-it`.
  - Runs one fork at a time because Spring/Postgres/Spark contexts are memory-heavy.
- `./gradlew runtimeNightlyTest`
  - Live-runtime/Docker lane.
  - Includes `@Tag("runtime")`.
  - For runtime proof classes such as `CanonicalLoanMasterAirflowRuntimeIT`, `JsonBlueprintLiveRuntimeProofIT`, and `AggregateBlueprintLiveRuntimeProofIT`.
- `./gradlew cobolSparkTest`
  - Dedicated Spark/Cobrix COBOL lane with Spark-compatible ANTLR 4.9.3 runtime.
  - Includes `CobolDiscoverySparkJobRunnerTest`, `CobolFlatteningServiceTest`, and `CobolSparkPreviewServiceTest`.

For a clean full backend proof, prefer a fresh DB name:

```bash
cd /Users/aameradam/projects/dev/PULSE-integration/backend
DB_NAME=pulse_integration_full_green ./gradlew test --console=plain
```

Dirty local Flyway history in a reused `pulse` database is not a product regression. Use a fresh DB for final proof unless the task specifically concerns migration recovery.

### Frontend Test Lanes

Run from `/Users/aameradam/projects/dev/PULSE-integration/frontend`.

- `npm run lint`
  - ESLint/Next lint gate. Current integration has passed with 3 existing warnings.
- `npm run test:unit`
  - Vitest unit tests.
  - Current unit files include `frontend/src/lib/api.test.ts`, `frontend/src/test/mock-api.test.ts`, and `frontend/src/test/smoke.test.ts`.
- `npm run test:e2e`
  - Playwright E2E tests.
  - Current specs include `frontend/e2e/login.spec.ts`, `frontend/e2e/dag-designer.spec.ts`, `frontend/e2e/producer-onboarding.spec.ts`, and `frontend/e2e/ebcdic-discovery.spec.ts`.
- `npm run build`
  - Next production build. Use for frontend architecture changes or deploy-facing UI changes.

### High-Value Backend Suites By Area

Use targeted `--tests` patterns when iterating, then run broader gates.

Deployment/package/static deployability:

- `com.pulse.deploy.controller.RepresentativeStaticDeployabilityProofIT`
- `com.pulse.deploy.DeployControllerTest`
- `com.pulse.deploy.PackageProvenanceContractTest`
- `com.pulse.deploy.DeploymentPreflightServiceContractTest`
- `com.pulse.deploy.RuntimePreflightCapabilityTest`
- `com.pulse.secret.CredentialReadinessPreflightTest`
- `com.pulse.deploy.PullRequestPreflightPolicyTest`
- `com.pulse.deploy.DeterministicPackageMaterializationTest`
- `com.pulse.deploy.DeploymentTargetAdapterPlanContractTest`
- `com.pulse.deploy.DeterministicAdapterPlanTest`
- `com.pulse.deploy.GcpComposerDataprocAdapterPlanTest`
- `com.pulse.deploy.DpcAirflowOpenShiftSparkAdapterPlanTest`

Runtime standalone and broker/cross-substrate:

- `com.pulse.broker.RuntimeStandaloneStaticSearchTest`
- `com.pulse.broker.trust.PeerPulseTrustBindingControllerTest`
- `com.pulse.codegen.service.CodeGenerationServiceTest`
- Static searches for forbidden runtime tokens such as `peer/v1`, `peer_pulse_url`, `/api/v1/internal/broker`, `PULSE_BROKER_API_URL`, and `PULSE_BROKER_INTERNAL_TOKEN`.

Storage contracts, table contracts, and runtime projection:

- `com.pulse.storage.contract.service.TableContractServiceTest`
- `com.pulse.storage.contract.service.DatasetLandingContractServiceTest`
- `com.pulse.storage.contract.service.DdlPlanServiceTest`
- `com.pulse.deploy.projection.RuntimeProjectionServiceTest`
- `com.pulse.sor.DatasetPhysicalDesignFieldsTest`

Runtime persona and runtime authority:

- `com.pulse.runtime.RuntimeAuthorityServiceTest`
- `com.pulse.runtime.RuntimeBindingAuthorityFacadeTest`
- `com.pulse.runtime.RuntimeBindingPersonaGuardTest`
- `com.pulse.runtime.RuntimeAuthorityControllerTest`
- `com.pulse.deploy.DeployControllerRuntimeAuthorityTest`

Blueprint, composition, and field authority:

- `com.pulse.blueprint.BlueprintControllerTest`
- `com.pulse.pipeline.service.BlueprintInstanceConfigurationServiceTest`
- `com.pulse.pipeline.service.CompositionServiceTest`
- `com.pulse.pipeline.controller.CompositionControllerTest`
- `com.pulse.storage.StorageBackendDeployGateTest`
- `com.pulse.pipeline.controller.DqControllerTest`

Chat, plan/apply, and prompt/tool contracts:

- `com.pulse.chat.service.ChatToolExecutorTest`
- `com.pulse.chat.controller.ChatControllerSseContractTest`
- `com.pulse.chat.service.PulseSystemPromptTest`
- `com.pulse.chat.plan.StepParamsForbiddenKeyValidatorTest`
- `com.pulse.chat.service.PlanAddStepPolicyRejectionTest`
- `com.pulse.command.controller.PlanControllerTest`
- `com.pulse.command.service.PlanServiceTest`
- `com.pulse.chat.plan.ContractImpactDerivationTest`
- `com.pulse.chat.service.ChatReadToolHandlerTest`

Workspace, Git identity, and release flow:

- `com.pulse.git.workspace.DeveloperWorkspaceServiceTest`
- `com.pulse.git.workspace.WorkspaceFileServiceTest`
- `com.pulse.git.workspace.WorkspaceGitStorageServiceTest`
- `com.pulse.pipeline.model.VersionAcceptanceTest`
- `com.pulse.git.GitControllerTest`
- `com.pulse.git.GitCommitIdentityTest`
- `com.pulse.git.CodegenToGitCommitE2EIT`

Orchestration namespace and Airflow runtime support:

- `com.pulse.pipeline.service.OrchestrationNamespaceServiceTest`
- `com.pulse.pipeline.service.PipelineSlugServiceTest`
- `com.pulse.pipeline.service.OrchestrationNamespaceSlugIntegrationTest`
- `com.pulse.pipeline.service.DatasetScheduleServiceTest`
- `com.pulse.deploy.runtime.AirflowRuntimeClientTest`

Flyway and migration smoke:

- `com.pulse.config.PostgresFlywayMigrationSmokeIT`
- `com.pulse.pipeline.SchemaV84MigrationTest`
- `com.pulse.auth.V87TenantsTableMigrationTest`

E2E/scenario coverage:

- `com.pulse.e2e.api.ApiDrivenLoanMasterScenarioIT`
- `com.pulse.e2e.builder.ApiScenarioBuilderTest`
- `com.pulse.e2e.validation.ApiScenarioValidationIT`
- `com.pulse.e2e.builder.LoanMasterScenarioCatalogExecutionIT`
- `com.pulse.e2e.coverage.ActiveBlueprintCoverageCatalogIT`
- `com.pulse.e2e.validation.DatabaseWriterLiveRuntimeEvidenceIT`
- Runtime-tagged proof classes under `com.pulse.e2e.runtime.*`.

COBOL discovery:

- Normal unit tests under `com.pulse.cobol.*`.
- Spark/Cobrix parser tests through `./gradlew cobolSparkTest`.

### Final Integration Gate

For final integration or architecture-wide changes, run:

```bash
cd /Users/aameradam/projects/dev/PULSE-integration/backend
DB_NAME=pulse_integration_full_green ./gradlew test --console=plain
./gradlew test --tests 'com.pulse.deploy.controller.RepresentativeStaticDeployabilityProofIT' --console=plain

cd /Users/aameradam/projects/dev/PULSE-integration/frontend
npm run lint
```

Also run the static drift searches from the master sequencing plan and any architecture-specific negative searches. For standalone runtime/package changes, include checks for PULSE callback tokens in generated artifacts.

## Static Drift Searches To Run Often

These searches catch common regressions:

```bash
rg -n "peer/v1|peer_pulse_url|peerPulseBaseUrl|/api/v1/internal/broker|PULSE_BROKER_API_URL|PULSE_BROKER_INTERNAL_TOKEN" backend frontend docs
rg -n "PULSE_API_URL|localhost:8080" backend/src/main/java backend/src/test/java
rg -n "PathConventionService|dpcIcebergSupported|gold_input|servingDatasetTemplate|goldPublishBoundary" backend/src/main/java backend/src/test/java
rg -n "ask.*DPC|ask.*GCP|DPC or GCP|GCP or DPC" backend/src/main/java/com/pulse/chat
rg -n "submit.*Dataproc|submit.*Spark|run.*Spark" backend/src/main/java/com/pulse/deploy/adapter
```

Interpretation matters:

- `PULSE_API_URL` currently remains relevant to the pending AdvanceTimeDimension remediation. Do not silently spread it to new runtime surfaces.
- Legacy names may remain in Java/schema aliases where behavior has been corrected. Do not rename broadly unless the task is naming cleanup.
- Negative test literals are acceptable hits.

## Runtime and Deploy Mental Model

Package/deploy is intentionally split:

Deploy time:

- Materialize an immutable package.
- Upload/copy package files.
- Sync/install Airflow DAGs, runtime helpers, dependencies, variables, connections, and config.
- Optionally unpause/register/update the DAG.
- Optionally trigger an explicit Airflow smoke DAG run and poll Airflow.

Pipeline run time:

- Airflow executes the generated DAG.
- DAG tasks submit PySpark to Dataproc or DPC Spark.
- DAG tasks run dbt, GX/DQ, sensors, and runtime helpers.

Do not write deploy adapter code that directly runs pipeline Spark/dbt work at deploy time. Deploy adapters prepare Airflow. Airflow orchestrates execution.

The ideal package shape is one immutable package artifact/prefix that can be exploded or published into the target Airflow environment. Airflow itself does not consume a generic PULSE zip as a native deployment unit; PULSE adapters/projectors translate that package into the correct Composer/on-prem Airflow artifact layout.

## Known Open Risks and Deferred Work

- AdvanceTimeDimension is not yet fully standalone-runtime compliant. Use ARCH-019 and the 2026-05-14 AdvanceTimeDimension plans. It needs Airflow-native state, packaged calendar assets, and no PULSE API/PULSE Postgres dependency in promoted environments.
- ARCH-008 docs may still describe the old PULSE-mediated broker. Trust integrated code and the newer deploy/runtime boundary plan for runtime behavior.
- Some classes retain legacy names such as `PeerPulseTrustBinding*` although runtime behavior now avoids peer PULSE calls. Cleanup is allowed only as a scoped naming migration.
- Security is not production-complete. `/api/v1/**` has been permissive in prior code, and `/api/v1/auth/me` has used a dev identity path. Verify current `SecurityConfig` before claiming production auth behavior.
- Default or local secrets in `application.yml` are development conveniences, not production guidance.
- Local infra is Postgres plus Redis. Airflow/Spark are not generally provisioned locally, so static deployability and generated package proof tests are the normal local proof.
- Full live runtime behavior belongs in runtime-tagged/nightly lanes unless the task explicitly provisions live services.

## Editing Rules For Agents

- Do not modify unrelated files.
- Respect user changes in the worktree. Never reset or revert changes you did not make unless the user explicitly asks.
- Use existing services and architecture boundaries before adding abstractions.
- Add migrations carefully and keep Flyway numbering unique.
- Add focused tests for the changed behavior, then run the appropriate broad gate.
- For frontend work, keep the UI consistent with the existing operational product style; avoid marketing-page patterns.
- For commit messages, follow the Lore Commit Protocol in `AGENTS.md`.

## Minimal Bootstrap Prompt For New Sessions

If a new agent session needs a short kickoff:

```text
You are working in /Users/aameradam/projects/dev/PULSE-integration on PULSE.
Read PROJECT_CONTEXT.MD, AGENTS.md, docs/ARCHITECTURE_LEDGER.md, and the architecture plan relevant to the task before coding.
Trust code for current behavior, ledger/plans for target architecture, and flag contradictions explicitly.
Preserve the invariant that promoted runtime packages run standalone without PULSE API callbacks.
Use RuntimeAuthorityService/RuntimeBindingAuthorityFacade, BlueprintInstanceConfigurationService, StorageAuthorityFacade, RuntimeProjectionService, PackageService, DeploymentPreflightService, and plan/apply chat boundaries instead of bypassing them.
Run targeted tests while iterating and use the final backend/frontend gates before reporting done.
```
