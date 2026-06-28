# PULSE Session Bootstrap

This is the canonical startup file for any new coding session in this repo.

Use it to reload only the context that matters, in the right order, without re-reading the entire codebase.

If any older handoff doc conflicts with the code, trust the code.

## Goal

PULSE is an AI-assisted enterprise data pipeline builder.

Core product areas:

- Tenant-scoped pipeline design and lifecycle management
- SOR / connector / dataset registry
- AI chat with tool-calling that can mutate product state
- Pipeline composition as blueprint instances + port wiring
- Deterministic code generation for Airflow, PySpark, dbt, and config
- Git linking, packaging, deployment, and approval workflows

## Startup Order

When starting a fresh session, load context in this exact order.

### 1. Repo-level orientation

Read:

- `AGENTS.md`
- `docker-compose.yml`
- `frontend/package.json`
- `backend/build.gradle.kts`
- `backend/src/main/resources/application.yml`

Quick reference:

- `docs/server-startup.md`

Purpose:

- Confirm stack, local services, scripts, and runtime defaults
- Check for sensitive defaults or environment assumptions

### 2. Data model and seeded reality

Read these migrations first:

- `backend/src/main/resources/db/migration/V7__blueprints_catalog.sql`
- `backend/src/main/resources/db/migration/V10__sor_connector_catalog.sql`
- `backend/src/main/resources/db/migration/V60__rbac_auth.sql`
- `backend/src/main/resources/db/migration/V63__periodicity_temporal.sql`

Read these if you need seeded product examples:

- `backend/src/main/resources/db/migration/V5__seed_test_data.sql`

Purpose:

- Understand the real catalog, sample tenants, users, SORs, connectors, datasets, and pipeline seeds
- See what the app boots with before assuming behavior from docs

### 3. Backend execution path

Read these files next:

- `backend/src/main/java/com/pulse/config/SecurityConfig.java`
- `backend/src/main/java/com/pulse/auth/controller/AuthController.java`
- `backend/src/main/java/com/pulse/pipeline/service/PipelineService.java`
- `backend/src/main/java/com/pulse/pipeline/service/CompositionService.java`
- `backend/src/main/java/com/pulse/chat/service/ChatService.java`
- `backend/src/main/java/com/pulse/chat/service/ChatToolExecutor.java`
- `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java`

Purpose:

- Reconstruct the mutation path for pipelines and composition
- Understand chat/tool orchestration
- Understand how generated artifacts are produced

### 4. Frontend execution path

Read these files next:

- `frontend/src/lib/api.ts`
- `frontend/src/contexts/auth-context.tsx`
- `frontend/src/contexts/tenant-context.tsx`
- `frontend/src/contexts/chat-context.tsx`
- `frontend/src/app/pipelines/[pipelineId]/page.tsx`
- `frontend/src/components/pipeline/composition-panel.tsx`
- `frontend/src/components/pipeline/chat-panel.tsx`
- `frontend/src/components/pipeline/code-editor-panel.tsx`

Read these if the task touches Git / deploy / SOR detail:

- `frontend/src/components/pipeline/git-panel.tsx`
- `frontend/src/components/pipeline/deploy-panel.tsx`
- `frontend/src/app/producers/[sorId]/page.tsx`

Purpose:

- Rebuild the main client-side state flow
- See how SSE events, composition refreshes, and codegen UX are wired

### 5. Docs worth keeping

Read these only after code-level orientation:

- `docs/USER_JOURNEYS.md`
- `docs/AI_REQUIREMENTS_GATHERING_ANALYSIS.md`
- `docs/AIRFLOW_INTEGRATION_DECISIONS.md`
- `docs/PERIODICITY_ANALYSIS.md`
- `docs/server-startup.md`

Use these as intent documents, not source of truth.

## Minimal Context Pack

If you need the shortest viable reload for a normal task, load only:

1. `AGENTS.md`
2. `backend/src/main/resources/application.yml`
3. `backend/src/main/resources/db/migration/V10__sor_connector_catalog.sql`
4. `backend/src/main/java/com/pulse/pipeline/service/CompositionService.java`
5. `backend/src/main/java/com/pulse/chat/service/ChatService.java`
6. `backend/src/main/java/com/pulse/chat/service/ChatToolExecutor.java`
7. `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java`
8. `frontend/src/app/pipelines/[pipelineId]/page.tsx`
9. `frontend/src/components/pipeline/composition-panel.tsx`
10. `frontend/src/components/pipeline/chat-panel.tsx`
11. `frontend/src/components/pipeline/code-editor-panel.tsx`

That pack is enough to recover the core product shape.

## Current Reality

These are the most important facts to remember at session start.

- Backend is Spring Boot 3.4.x on Java 21.
- Frontend is Next.js 16 with React 19.
- Local infra in `docker-compose.yml` is Postgres + Redis only. Airflow and Spark are **not** provisioned locally right now, so local completion proof for pipeline redesign work uses static package/deployability validation rather than live orchestrator/compute execution.
- The app is tenant-scoped, but tenant definitions are config-driven in `application.yml`.
- Auth exists, but endpoint protection is effectively disabled right now because `/api/v1/**` is `permitAll`.
- `/api/v1/auth/me` currently returns a stub dev user rather than deriving identity from the JWT.
- Chat is not just conversational UI. It can execute tools that create and modify domains, SORs, datasets, pipelines, composition, and DQ state.
- Code generation is deterministic template/code assembly, not LLM-based business-logic generation.
- The schema and catalog live heavily in Flyway migrations; reading migrations is mandatory.
- Pipeline redesign foundations now exist for:
  - canonical `domainId` on pipelines and domain-scoped Git attachment with legacy shim support
  - compile-plan snapshots and static deployability assessment in package metadata
  - first-class sensing/orchestration blueprints and orchestration UI section
  - dbt asset registry and reuse-wrapper compilation foundations

## Known Issues To Recheck Early

Re-verify these at the start of any session that touches them.

- `backend/src/main/java/com/pulse/config/SecurityConfig.java`
  Security is still permissive and should not be treated as production-complete.

- `backend/src/main/resources/application.yml`
  Default JWT and LLM secrets are in config. Treat as a security cleanup item if touching config/auth.

- `frontend/src/components/pipeline/chat-panel.tsx`
  SSE handling is central and easy to regress when changing chat behavior.

## Task-Based Loading

Use these focused loads instead of re-reading everything.

### Chat / AI behavior

Load:

- `backend/src/main/java/com/pulse/chat/service/PulseSystemPrompt.java`
- `backend/src/main/java/com/pulse/chat/service/ChatTools.java`
- `backend/src/main/java/com/pulse/chat/service/ChatService.java`
- `backend/src/main/java/com/pulse/chat/service/ChatToolExecutor.java`
- `frontend/src/components/pipeline/chat-panel.tsx`

### Composition / DAG wiring

Load:

- `backend/src/main/java/com/pulse/pipeline/controller/CompositionController.java`
- `backend/src/main/java/com/pulse/pipeline/service/CompositionService.java`
- `frontend/src/components/pipeline/composition-panel.tsx`
- `frontend/src/components/pipeline/dag-view.tsx`
- `frontend/src/components/pipeline/configure-transform-dialog.tsx`
- `backend/src/main/java/com/pulse/codegen/service/CompilePlanService.java`

### SOR / dataset management

Load:

- `backend/src/main/java/com/pulse/sor/controller/SORController.java`
- `backend/src/main/java/com/pulse/sor/controller/ConnectorInstanceController.java`
- `backend/src/main/java/com/pulse/sor/controller/DatasetController.java`
- `backend/src/main/java/com/pulse/sor/controller/SORController.java`
- `backend/src/main/java/com/pulse/sor/model/SystemOfRecord.java`
- `backend/src/main/java/com/pulse/sor/model/Dataset.java`
- `frontend/src/app/producers/[sorId]/page.tsx`
- `frontend/src/components/sor/create-sor-dialog.tsx`
- `frontend/src/components/sor/define-dataset-dialog.tsx`

### Code generation

Load:

- `backend/src/main/java/com/pulse/codegen/controller/CodeGenController.java`
- `backend/src/main/java/com/pulse/codegen/controller/DbtAssetController.java`
- `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java`
- `backend/src/main/java/com/pulse/codegen/service/CompilePlanService.java`
- `backend/src/main/java/com/pulse/codegen/service/DbtAssetRegistryService.java`
- `backend/src/main/java/com/pulse/codegen/GxCodeGenerator.java`
- `frontend/src/components/pipeline/code-editor-panel.tsx`
- `frontend/src/components/pipeline/dbt-asset-panel.tsx`

### Git / deploy

Load:

- `backend/src/main/java/com/pulse/git/controller/GitController.java`
- `backend/src/main/java/com/pulse/deploy/controller/DeployController.java`
- `backend/src/main/resources/db/migration/V74__pipeline_semantics_foundation.sql`
- `backend/src/main/resources/db/migration/V75__sensing_orchestration_blueprints.sql`
- `backend/src/main/resources/db/migration/V76__dbt_asset_registry.sql`
- `frontend/src/components/pipeline/git-panel.tsx`
- `frontend/src/components/pipeline/deploy-panel.tsx`
- `frontend/src/components/pipeline/orchestration-panel.tsx`

## Verification Commands

Run these early if the task might break the main app.

### Frontend

From `frontend/`:

```bash
npm run lint
```

### Backend

From `backend/`:

```bash
./gradlew test
```

For the representative static deployability proof used in lieu of local Airflow/Spark runtime execution:

```bash
./gradlew test --tests 'com.pulse.deploy.controller.RepresentativeStaticDeployabilityProofIT'
```

If Java is missing locally, note that immediately instead of pretending the backend was verified.

## Working Rules For Future Sessions

- Prefer code over docs when they disagree.
- Prefer targeted file loading over repo-wide scanning.
- Read migrations before assuming entity or API behavior.
- If touching chat, composition, or codegen, verify the whole loop, not just the edited file.
- If you learn something materially important that should be reloaded next session, update this file.

## When To Update This File

Update `AGENTS.md` when any of these change:

- The startup load order
- Core architecture boundaries
- Canonical source files for a subsystem
- High-risk known issues
- Build/verification commands
- Major product areas added or removed

Do not turn this into a changelog. Keep it optimized for session bootstrap.
