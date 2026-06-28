# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PULSE is an enterprise GenAI Pipeline Builder — a three-tier web application for designing, composing, and deploying data pipelines with AI assistance.

- **Frontend**: Next.js 16 (App Router) with React 19, TypeScript, Tailwind CSS 4, shadcn/ui
- **Backend**: Spring Boot 3.4.3, Java 21, Gradle (Kotlin DSL)
- **Database**: PostgreSQL 16 with Flyway migrations (current head **V151** on `main`; **V152** is parked on the `g1` branch; **V153** is the planned builder-op-list / param-tiering seed, owned by `IMPL-catalog-seed`), Redis 7 for caching
- **Deployment**: GCP Cloud Run + Cloud SQL; local dev via docker-compose

## Development Commands

### Local Infrastructure
```bash
docker-compose up            # Start Postgres + Redis
```

### Frontend (working directory: frontend/)
```bash
npm run dev                  # Dev server on :3000
npm run build                # Production build
npm run lint                 # ESLint
```

(No frontend test runner is wired up yet — the legacy Playwright e2e suite was
removed because the architecture and UI have significantly changed since it
was written. New specs will be authored against the current surface.)

### Backend (working directory: backend/)
```bash
./gradlew bootRun            # Dev server on :8080
./gradlew build              # Build + run all tests
./gradlew test               # Run tests only
./gradlew test --tests "com.pulse.pipeline.PipelineServiceTest"  # Single test class
```

### GCP Deployment
```bash
scripts/gcp-deploy.sh all          # Deploy everything
scripts/gcp-deploy.sh frontend     # Deploy frontend only
scripts/gcp-deploy.sh backend      # Deploy backend only
```

## Architecture

### Multi-Tenant Design
Tenants are defined in `application.yml` under `pulse.tenants.definitions`. All core entities (pipelines, datasets, connectors) are scoped to a tenant. The frontend manages tenant context via `TenantContext`.

### Authentication
Controlled by `pulse.auth.enabled` (default: `false` for dev — returns a stub DATA_ENGINEER user). When enabled, uses JWT (email/password login → Bearer token). Roles: CITIZEN, DATA_ENGINEER, DEPLOYER, ADMIN with granular permissions.

### Backend Package Structure (`backend/src/main/java/com/pulse/`)
- `auth/` — JWT authentication, user/role management
- `pipeline/` — Core pipeline CRUD, versioning, lifecycle stages
- `sor/` — System of Record: connectors, datasets, credential profiles, domains
- `blueprint/` — Reusable pipeline templates with categories
- `codegen/` — Deterministic op-composition compiler: emits artifacts from a blueprint's op-list via per-op handlers, no LLM in codegen (ADR 0012/0013)
- `chat/` — AI chat with tool execution; orchestration is a LangGraph4j multi-stage StateGraph with per-stage models (ADR 0025). LLM is Chat-side assistance only (compose / author expressions / draft SQL), never codegen (ADR 0013). Provider: Vertex / OpenRouter (switchable)
- `command/` — Command logging and execution plans
- `deploy/` — Package creation and deployment workflow with approval gates
- `git/` — Git integration for version-controlled pipeline code
- `config/` — Spring configs: SecurityConfig, WebSocketConfig, TenantConfig, CorsConfig

### Frontend Structure (`frontend/src/`)
- `app/` — Next.js App Router pages: dashboard, pipelines, producers, blueprints, domains, chat, commands
- `components/ui/` — shadcn/ui primitives; `components/pipeline/` and `components/sor/` for domain components
- `contexts/` — AuthContext, TenantContext, ChatContext for global state
- `lib/api.ts` — Centralized API client that handles Bearer token injection and error handling
- `types/index.ts` — All TypeScript interfaces (Pipeline, Blueprint, Connector, Dataset, etc.)

### Key Integration: Pipeline Composition
Pipelines have versions → versions have compositions (sub-pipeline instances + port wiring). The flow editor uses `@xyflow/react` to visualize and edit DAGs. Compositions are persisted via the `/api/v1/versions/{versionId}/composition` endpoint.

### AI Services
The backend connects to LLMs via Vertex / OpenRouter (`pulse.llm.api-key`, switchable). The LLM serves **Chat only** — composition assistance, expression authoring, and SQL drafting — all downstream-validated deterministically (ADR 0013). It does **not** generate pipeline code, and it does **not** infer schemas (schema inference is 100% deterministic, zero-LLM — ADR 0011).
- Primary / reasoning tier (`pulse.llm.model`): the Build/Composer and Planner chat stages.
- Cheap tier (a Flash-class chat model under a dedicated chat-stage config key): the Router / Discovery / Configure / Provision / Responder stages (ADR 0025). The old `pulse.schema-inference.model` key is **dead** — ADR 0011 retired model-based schema inference; do not reuse it as a chat-stage model.

### API Convention
All REST endpoints live under `/api/v1/`. Tenant-scoped resources use `/api/v1/tenants/{tenantId}/...`. WebSocket endpoint at `/ws` for real-time chat streaming.

### Database Migrations
Flyway migrations in `backend/src/main/resources/db/migration/`. Follow naming: `V{number}__{description}.sql`. Backend tests use an H2 in-memory database.

## CI
GitHub Actions runs on push/PR to main: frontend lint + build (Node 22), backend build + test (Java 21 + Postgres 16 service container).

## Domain Concepts

### Core Interaction Model: "Chat → Plan → Command"
PULSE is **chat-first**. Users describe what they want in natural language, the system proposes a **Plan Preview** (ordered list of commands), and the user clicks **Apply Plan** to execute. Every state-changing action beyond saving drafts requires Plan Preview + explicit Apply. All surfaces (chat, structured cards, wizard) emit the same command schema.

### Two-Level Pipeline Model (strict, no deeper nesting)
- **Business Pipeline** (Level 1): the end-to-end pipeline the user thinks about.
- **Sub-Pipeline Instances** (Level 2): reusable building blocks instantiated from the Blueprint catalog.
- Tasks exist inside sub-pipelines but are not independently composed.

### Blueprints
Versioned (SemVer), first-class patterns that generate artifacts and Airflow TaskGroups. Each blueprint declares: dataset I/O ports, typed params with validation, generated artifacts (dbt/Spark/Airflow), runtime requirements, and observability obligations. Categories: Ingestion, Transform, Modeling, Data Quality, Orchestration/CI/CD/Governance. ~50 blueprint types in the catalog.

### Datasets & Schema Contracts
Datasets are the universal I/O unit connecting blueprints. Schema contracts are required for promotion to integration+. Schema inference is **100% deterministic, zero-LLM** (ADR 0011): a step's output columns come from a deterministic rule, the blueprint's declared op-list / `schema_behavior`, or schema discovery (sampling the source); an unknown blueprint **fails loudly** — there is no AI fallback. The deterministic per-port schema is the Builder's **enforced contract** (codegen must produce exactly those columns).

### Producer Registry (System of Record)
Three-layer model: **Producer** (business entity) → **ServiceInstance** (technical endpoint) → **Bindings** (Airbyte for data movement, OpenMetadata for metadata ingestion). Single onboarding flow presents both as one model. Credential profiles are reusable across bindings with per-binding override support. Schema discovery is dev-builder only.

### Command Bus
Current-state tables + append-only Command Log (not full event sourcing). Domain-level idempotent commands with IAM enforced at both validation and execution time. Commands use ULID for IDs.

### Lifecycle & Deployment
- **Environments**: dev, integration, UAT, prod — physically separated. Builder exists only in dev; higher envs are deploy portals.
- **Packages**: Immutable, env-stamped, signed deployment artifacts containing code reference, env config, deploy manifest, DDL plan, and scheduling info. Packages contain SecretRefs only, never secret values.
- **Promotion**: Build new env-stamped package + deploy. Rollback = redeploy last-good package.
- **Approvals**: Policy-based (Casbin/OPA) with env allowlist matrix. Approvals bind to `package_id`. Deployer groups are env-specific (SoD).

### Execution Runtime
- **Airflow** runs all tasks in all environments; PULSE triggers/observes only.
- **dbt Core** is the primary transform/modeling layer, executed on Spark/Databricks via adapter in a pinned dbt-runner container.
- **PySpark** is default for non-SQL heavy-lift tasks (ingestion/stream/encryption).
- One DAG per Business Pipeline with TaskGroups per sub-pipeline. Airflow callbacks POST to PULSE API.

### Periodicity & Time Dimensions
Typed temporal columns on datasets/versions (not JSONB). Time-grain-aware defaults, sensor generation inferred from time grain, strftime filename format. Three sensing patterns: file, sql_query, trigger.

### OpenMetadata Integration
White-label integration for catalog/lineage/DQ. Publishing starts at integration+ only — dev experiments never publish. OpenMetadata change-events are ingested to drive proposals (e.g., schema drift → migration plan suggestion).

### Git Model
Core platform monorepo + one tenant monorepo per tenant. Tenant repo layout: `/domains/<domain>/pipelines/<id>/…`. PR-only (PR created at GenerateArtifacts). Merge produces tag: `pipeline/<id>/vX.Y.Z`. Tenant repo pins a `platform_release` manifest from core platform.

## Seeded Data
Two tenants (`home-lending`, `unsecured-lending` — renamed from the old `acme`/`globex`, `V87`), 4 demo users, 35 blueprints across categories, 24 connectors. Auth disabled by default returns a stub DATA_ENGINEER user.

## Key Design Decisions

> The **canonical, current** decisions live in **`docs/adr/`** (ADRs) and **`docs/PULSE-MAP.md`** (the living map). The build is specified by the **6 build specs** (`docs/build-specs/SPEC-INDEX.md` is the map of which spec covers what) and built per the **6 impl plans** in `docs/impl-plans/`. (The older standalone analysis docs — schema-discovery, airflow-integration, periodicity, benchmark-suite — were retired in the 2026-06-15 doc audit; their decisions now live in the ADRs/specs below.)

- **Builder = deterministic op-composition compiler (ADR 0012/0013):** a blueprint's behavior is one ordered list drawn from a **closed 32-op vocabulary**; the Builder composes per-op codegen handlers; **no LLM writes codegen**, output is byte-exact (ADR 0009). The LLM is Chat-side assistance only. Specs: `docs/build-specs/SPEC-schema-op-engine.md` (#1, design-time column authority) + `docs/build-specs/SPEC-codegen-compiler.md` (#2, build-time emission + the V153 migration). Decision record cited by both: `docs/build-specs/SPEC-builder-compiler.md`.
- **Schema inference (ADR 0011):** **100% deterministic, zero LLM** — rule / blueprint-declared op-list / discovery; unknown → loud fail. The deterministic per-port schema is the Builder's **enforced contract**. See `docs/adr/0011-*` + `docs/PULSE-MAP.md`.
- **SQL authoring (ADR 0024):** `SourceSQL` (relational source via a source-validated SQL query) + `SqlModel` (in-pipeline transform chain) blueprints over a deterministic Calcite validator, with inline `[[ ]]` date mnemonics — **no new op** (built on `read-source` + the `sql-model` op). Spec: `docs/build-specs/SPEC-calcite-sql-model.md` (#6).
- **Chat orchestration (ADR 0025):** a **LangGraph4j multi-stage StateGraph** — 7 stages as graph nodes with **per-stage model assignment** (cheap Flash tier for Router/Discovery/Configure/Provision/Responder; reasoning tier for Build/Composer + Planner), a Postgres checkpointer for durable/resumable session state, and an `interruptBefore` at the Plan-Preview approval gate. Spec: `docs/ui/SPEC-ui-composition.md` (#3) + `docs/ui/SPEC-construct-library.md` (#4); prompt fragments in `docs/ui/chat-prompts/`.
- **Blueprint catalog (ADR 0020):** the 39 surviving + atomic blueprints, each declaring its op-list + tiered params (ADR 0023) + ports. Spec: `docs/build-specs/SPEC-blueprint-catalog.md` (#5); decomposition in `docs/blueprints/OP-VOCABULARY-AND-DECOMPOSITION.md`.
- **Airflow / runtime, periodicity, modes:** one DAG per pipeline (PULSE triggers/observes only); dbt-on-Spark + GX as Dataproc jobs; typed temporal columns + sensor inference from time grain + 3 sensing patterns; Mode-aware emission (GCP Composer/Dataproc/Iceberg vs DPC plain-Airflow/Livy/Hive-Parquet). See `docs/adr/0006-*`, `docs/adr/0007-*`, `docs/adr/0021-*`, `docs/adr/0022-*` + `docs/PULSE-MAP.md`.

## Presentations & Slide Generation

We frequently create presentation decks about PULSE and related topics. There are two output formats: **Python-generated PPTX** files and **web slides** (React/Next.js components rendered at 1280×720 and screenshot-captured).

### SlideWriter Project (`/Users/aameradam/projects/DataOffSite/`)

A Next.js slide-rendering app at `/Users/aameradam/projects/DataOffSite/cio-slide/` that generates high-fidelity web slides. Tech: Next.js 15, React 19, Tailwind CSS 3, Recharts, Lucide icons.

```bash
cd /Users/aameradam/projects/DataOffSite/cio-slide && npm run dev   # Dev server
```

**Slide routes** (each route renders a set of related slides with a nav bar):
- `/pulse` — PULSE deck (8 slides): Problem, What Is, What Is V2, Architecture, Agent System, Why PULSE/Comparison, Scenarios, Staffing & Funding
- `/resiliency` — Cloud Resiliency deck (5 slides): Architecture & Threats, Decision Matrix, GCP Native, Governance, Action Plan
- `/headwinds` — Headwinds deck
- `/cases` — Case studies (dual-panel format)
- `/appendix` — Data products appendix

**Component pattern**: Each slide is a standalone React component in `cio-slide/src/components/` (e.g., `PulseWhatIsV2Slide.tsx`, `ResiliencyArchSlide.tsx`). Slides are 1280×720px fixed-size divs. Data/content is extracted into `src/lib/` files (`pulseData.ts`, `resiliencyData.ts`, `caseStudies.ts`, `data.ts`).

**Capture-to-PPTX workflow**: Python scripts screenshot web slides and assemble them into PPTX:
- `capture_slides.py` — Screenshots web slides via browser automation
- `generate_pulse_v2_pptx.py` — Assembles screenshots into PPTX (13.333" × 7.5" widescreen)

### Python PPTX Generation (standalone, no web rendering)

For decks that don't use web slides, Python scripts generate PPTX directly using `python-pptx`:
- `generate_pulse_pptx.py` — Original PULSE deck (programmatic shapes/text)
- `generate_resiliency_pptx.py` — Resiliency deck
- `generate_pptx.py` — SST Data Products deck
- `generate_headwinds_pptx.py` — Headwinds deck
- `generate_staffing_pptx.py` — Staffing slide

Common conventions in Python PPTX scripts: 13.333"×7.5" slide size, helper `tb()` function for textboxes, consistent color palette (NAVY `#1A1A2E`, WF_RED `#C8102E`, WF_CHARCOAL `#2D2D2D`).

### PULSE Deck Content (current V2 — 4 core slides + staffing)

1. **What PULSE Is** — "The Missing Layer": no agentic coder exists for data engineering. Two modes (no-code agentic + low-code visual DAG), same engine. 43+ production blueprints. AI reasoning collapses ~40 engineering decisions into 6-7 conversation turns (81% handled by AI, only 8 need human input).
2. **Agent System** — 6 specialized agents coordinated by a lead agent: Pipeline Engineering, DQ & Observability, Policy & Standards, Requirements & Story, Test & Validation. Runtime layer is all OSS (Spark, Airflow, dbt, Great Expectations, OpenMetadata).
3. **Why PULSE / Comparison** — Left side: current architecture constrained by fixed schema, StoryWeaver→CAMP→CDO Frameworks chain, limited to simple ingestion, no policy engine, no inline DQ, Autosys orchestration with no dependency awareness. Right side: PULSE agent interviews user, generates pipeline code + visual DAG + DQ rules + JIRA story + automated test validation. Handles full curation not just ingestion.
4. **Scenarios** — 7 scenarios across 4 categories: Greenfield (ingestion + curation), Legacy Migration (accelerated migration + agent-assisted code conversion [planned]), Retrofit & Uplift (DQ retrofit + accelerated extension), Rationalization [planned]. 5 of 7 supported now; 2 on roadmap.
5. **Staffing & Funding** — Team and budget requirements.

### Presentation Scripts
- `/Users/aameradam/projects/DataOffSite/PULSE_presentation_script.md` — 7-min talk track for PULSE V2 deck
- `/Users/aameradam/projects/DataOffSite/RESILIENCY_presentation_script.md` — Talk track for resiliency deck

### Key Talking Points for Slide Generation
- PULSE is positioned as "the missing agentic layer for data engineering" — all existing AI coding agents target app engineering
- Two personas: Citizen (natural language) and Data Engineer (full technical control), same system
- Current-state pain points: fixed-schema pipeline chain, no curation capability, blocked scenarios (variable-length EBCDIC, Kafka-to-S3, Iceberg format)
- Value prop: agent-elicited requirements → generated code + story + tests, end-to-end
- OSS runtime stack with yellow-dot callouts for items new to the organization's stack
