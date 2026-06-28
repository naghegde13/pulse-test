# PULSE Spec Index — the build specs + their boundaries (operator-confirmed 2026-06-15)

> The single map of which spec covers what. **Six specs** — NOT one bundled "Builder spec", NOT 50 per-blueprint
> panel specs. Every spec must pass the **SPEC-GATE** (4 lists: guesses / contradictions / dangling-refs /
> **omissions**) before any coding. Specs **cite each other** and are **cross-referenced** for mutual consistency.

## The 6 specs
1. **Schema / Op engine** (DESIGN-time; the column authority) — `docs/build-specs/SPEC-schema-op-engine.md`
   - Op-list metadata model (`schema_behavior` shape) + param-tiering (`tier`/`derivedFrom` + param-surface-derives-from-op-list).
   - The 32 ops' schema-effect rules.
   - Propagation (topological spread) + the 3-tier conflict classification (breaking/partial/non-breaking) + impact-radius + enforcement.
   - CONSUMED BY: the codegen (#2 must satisfy its output), the UI (#3 renders its schema-visibility + conflict-overlay), and Chat (#3 composes onto it).
2. **Codegen / compiler** (BUILD-time) — `docs/build-specs/SPEC-codegen-compiler.md`
   - The 32 ops' emission handlers PER ENGINE (dbt-SQL / PySpark / GX / dbt-snapshot / DAG-only), **Mode-aware** (GCP Composer/Dataproc/Iceberg-on-GCS vs DPC plain-Airflow/Livy/Hive-Parquet).
   - config-externalization + `sql-model`/Calcite-Phase-2 (named prerequisite) + the **V153 migration** + the byte-exact anchor oracle.
3. **Composition workspace + Chat** — `docs/ui/SPEC-ui-composition.md` (✅ GATE-CLEAN; LangGraph4j multi-stage orchestration per ADR 0025)
   - The UI (layout, canvas, bespoke panels, schema-visibility, conflict-overlay) + the Chat framework (agent stages, tools, prompts, streaming, staging→Apply) + per-page behavior. **Vertex + OpenRouter** provider (switchable).
   - MUST explicitly enumerate **every LLM tool** + the **full backend API surface** it requires (incl. the new `composition.*` commands + the plan-decision endpoint).
4. **Construct library** — `docs/ui/SPEC-construct-library.md` (✅ GATE-CLEAN)
   - The reusable purpose-built UI controls: **sql-builder** (rich-Spark + simple-source), expression builder (polish-existing), column picker, rename-mapper, condition-builder, sensing config, DQ outcome controls, date-mnemonic-picker.
5. **Blueprint catalog / metadata** — `docs/build-specs/SPEC-blueprint-catalog.md` (authored, 41 blueprints; ✅ GATE-CLEAN)
   - Each Blueprint's op-list + params(tiered) + ports + UI-construct hints (the 39 survivors + SqlModel + SourceSQL). The per-blueprint **panels DERIVE from this + #4 + #3's panel framework** — not per-blueprint specs (bespoke coded override only by exception).
6. **Calcite / SQL-authoring** — `docs/build-specs/SPEC-calcite-sql-model.md` (implements ADR 0024; ✅ GATE-CLEAN)
   - `CALCITE-PHASE-2` (the schema-deriving Calcite validator, **sql-model-only**) + the **SqlModel** (in-pipeline SQL transform; discrete-statement chain + per-step materialize) and **SourceSQL** (relational source via SQL; source-validated) blueprints + inline `[[ ]]` date mnemonics. CONSUMED BY: #1 §B rule 27 (OUT derivation) + #2 §C.4 (SQL emission); UI = #4's sql-builder.

## Cross-reference (mutual consistency — a required check)
Once #1 + #3 are drafted, run a **cross-spec consistency check**: they must agree on every shared concept — the op-list
model, the typed-operation → Command-Log mapping, the schema-propagation output, the conflict model, the staging/Apply
flow. #2 must satisfy #1's schema output. #3's panels derive from #5 + #4. Drift here = the boundary-mismatch slop.

## Seed data (cross-cutting deliverable — operator, 2026-06-15)
PULSE's existing seeded pipelines were built for the OLD model; under the new op-composition model (op-lists, the
redefined `schema_behavior`, the new composition/Chat model) they are STALE and won't render correctly.
**Deliverable: regenerate or update the seed data so every seeded pipeline uses the new model and renders 100% in the
new composition page** ("fully renders" = the acceptance criterion). A clean regenerate is acceptable if easier than
patching. Homes: the **blueprint-catalog seed** (op-lists + param-tiering) = the V153 migration in #2; the
**demo-pipeline seed** (real, fully-rendering compositions) = a deliverable validated against #3's UI + instantiating
#5's catalog. (The Vertex merge's 1 failing test — a duplicate `BronzeToSilverCleaning` seed — is an early symptom.)

## Status (2026-06-16 — DESIGN PHASE COMPLETE: all 6 specs GATE-CLEAN; worklist resolved; build roadmap authored)
- **#1 `SPEC-schema-op-engine.md` + #2 `SPEC-codegen-compiler.md`:** ✅ **GATE-CLEAN** (full gate → fixes → delta
  re-gate PASS). Worklist GUESSes resolved (`WORKLIST-RESOLUTIONS.md`); gold-layer = BigQuery-native folded into #2 §C
  (ADR 0007). Impl plan: `docs/impl-plans/IMPL-builder.md` (gated).
- **#3 `SPEC-ui-composition.md` (Composition + Chat):** ✅ **GATE-CLEAN** (full gate → fragment-07 5→7-stage fix + 4
  cross-section fixes → delta re-gate PASS). Build model = **LangGraph4j multi-stage StateGraph** (ADR 0025);
  per-stage models (cheap `CHAT_CHEAP` tier + reasoning tier). Detail = the 7 fragments `docs/ui/chat-prompts/01..07`.
  Impl plan: `docs/impl-plans/IMPL-ui-composition.md` (gated).
- **#4 `SPEC-construct-library.md` (Construct library):** ✅ **GATE-CLEAN** (gate → fixes → re-gate PASS). Impl plan:
  `docs/impl-plans/IMPL-construct-library.md` (gated).
- **#5 `SPEC-blueprint-catalog.md` (Blueprint catalog):** ✅ **GATE-CLEAN** (41 blueprints; 43 worklist GUESSes
  resolved — tiering ratified). Impl plan: `docs/impl-plans/IMPL-catalog-seed.md` (gated) — **owns the V153 migration**
  + the demo-seed regen.
- **#6 `SPEC-calcite-sql-model.md` (Calcite / SQL-authoring — implements ADR 0024):** ✅ **GATE-CLEAN**. 4 open items +
  6-G08 resolved (connector = `connector_instance_id`; validator = `com.pulse.expression.service.CalciteSqlModelValidator`;
  function seed; slug rule). Impl plan: `docs/impl-plans/IMPL-calcite-sql.md` (gated).
- **Backlog:** `docs/impl-plans/IMPL-dpc-livy.md` (the DPC / Livy / Hive+Parquet emission path) — deferred; needs a
  gated Livy-submission mini-spec before code.
- **NOW = the BUILD phase.** The autonomous-build orchestration spec is **`docs/HANDOFF-AUTONOMOUS-BUILD.md`** (gate it
  like any spec). Gating milestone: "first pipeline composed + built end-to-end" (ADR 0025 §5).
- **Rule:** every spec → SPEC-GATE (4 lists) → resolve → re-gate to zero → THEN code. No coding against an ungated
  spec. (All 6 are gate-clean; the resolved worklists live in `WORKLIST-RESOLUTIONS.md`.)
