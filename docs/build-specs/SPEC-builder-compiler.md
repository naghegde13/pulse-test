# SPEC — The Builder: a deterministic op-composition compiler

> Status: **DRAFT — grilling in progress (2026-06-15).** Authored under grill-with-docs +
> the spec-discipline (`docs/SPEC-DISCIPLINE.md`). **Gate before any dispatch:** the
> SPEC-GATE (`docs/spec-gate/SPEC-GATE.md`) must return GUESSES / CONTRADICTIONS /
> DANGLING-REFS all empty.
> Constitution (ADRs): 0011 (deterministic schema = the enforced contract) · 0012
> (behavior = composable primitive ops; 32-op closed vocabulary) · 0013 (LLM is OUT of
> codegen) · 0003 (materialization tiers) · 0009 (byte-exact output) · 0023 (param-tiering)
> · 0006/0007 (Spark-per-Mode / GCP format) · 0001 (Mode exclusivity).

## Scope — LOCKED (operator, 2026-06-15): ONE comprehensive spec, nothing carved out
**IN** ("1, 2 and 3 are in"):
- The metadata-driven **op engine** — reads each blueprint's declared **op-list** (the
  redefined `schema_behavior` field; current `{effect_type,...}` content is throwaway).
- All **32 ops' schema-effect rules** — the deterministic schema inference; **replaces**
  the hardcoded switch `SchemaPropagationService.deriveBaseOutputSchema:814-854`.
- All emission targets — **dbt-SQL · PySpark · GX · dbt-snapshot · DAG-only** (per-op handlers).
- **Param-tiering** — `tier: user|derived` + `derivedFrom` in `params_schema` (ADR 0023).
- **Kill the LLM fallback** — `deriveBaseOutputSchema:843-851` → loud-fail on unknown.
- **Config-externalization** — generated code reads the env-var-selected per-env config
  slice; no literal-baking.
- The **Calcite "Phase-2" SQL validator** — for the `sql-model` op (full validate path).
- The **V153 catalog migration** — write op-lists + param-tiering into the blueprints;
  deprecate the 4 dead blueprints. (V153: G1's V152 is already integrated on the branch.)
- The **12 fix-items** (`docs/blueprints/OP-VOCABULARY-AND-DECOMPOSITION.md`) are the
  corrected behaviors, folded in.

## The design tree (grill order) — each filled to zero-fuzziness before the gate
- [ ] **A. Metadata model** — op-list shape + how an op binds to the blueprint's params + emission declaration.
- [ ] **B. Schema engine** — apply op-list → output columns (the 32 schema-effect rules); contract enforcement; nested structs; kill the fallback.
- [ ] **C. Emission engine** — per-op handlers per target (dbt-SQL/PySpark/GX/snapshot/DAG); config-externalization; `sql-model` + Calcite Phase-2.
- [ ] **D. Catalog migration V153** — op-list + param-tiering seed; the 4 deprecations.
- [ ] **E. Oracle / behavioral test** — byte-exact (ADR 0009); the anchor pipeline cross-module scenario.

## ▶ RESUME HERE (fresh session — continue the grill clean)
> This session's live context got polluted by a heavy skill dump. All decisions are durable here, so a
> **new session loses nothing** — it reloads this file + the orientation and continues. **Do NOT `/compact`
> the old session** (would summarize good context too) — just start fresh.
>
> **Read to resume:** `AGENTS.md` → this spec (esp. the **Grill log** below) → ADRs 0011/0012/0013/0023/0003/0009.
> Operate under **grill-with-docs** (one question at a time, recommend each). **Defined vocab only:**
> **Customer** (not "user"), **op / op-list** (not "recipe"/"steps"), **params** (not "settings" —
> collides with the defined **Pipeline Setting**), **Blueprint**, **Designer/Builder** (design time vs build time).

**LOCKED so far (node A — metadata model):**
- **Scope:** one comprehensive spec, all-in (see Scope above; Calcite + V153 IN; nothing carved out).
- **Q1 — op→param binding:** ops reference params by name; op-list fixed per Blueprint (in `schema_behavior`);
  Customer fills only params; engine substitutes the instance's values at **design time** (Schema Propagation,
  live column preview) **and build time** (Builder, codegen). One op-list, two readers.
- **Constraint:** generic op-engine backend, **blueprint-specific friendly UI**; op-list is backend-only.
- **Correction:** the op-list **DOES** redrive the param surface → the config panels **will be redone** (UI-grill).
- **Q2 — settings tiering:** two-tier `user|derived` **confirmed**; derived params resolved at **design time**
  (from domain/dataset), shown **read-only and ALWAYS VISIBLE — never hidden** (ADR 0022/0023). Nuance: a few are
  **package-stamped** (e.g. calendar bundle hash) → show source at design time, final value at packaging.
- **Cleaning op-list (anchor, BronzeToSilverCleaning):** every param optional with a **do-nothing default**
  (unconfigured = passthrough; configured = apply only the set ops — fixes fix-item #4); `null_handling="drop_row"`
  → **`filter-rows` op** (Cleaning drops the rows itself, not quarantine).

**Q2 sub-call — LOCKED (operator agreed):** when a `derived` param can't resolve (e.g. domain has no
calendar) → **fail loudly** (per ADR 0011's "unknown → loud fail"); platform-default only where provably
harmless (concurrency policy, evidence prefixes).

**Q3 — LOCKED (operator agreed).** The param surface **derives from the op-list**: each op declares the params
it needs; the Blueprint's param surface = the **union**; blueprint-level params not tied to one op (e.g.
`partition_by`, output table name) declared **separately** at Blueprint level; `params_schema` carries each
param's presentation/tier metadata. **UI refinement (operator): present params OP-BY-OP** — the config panel
groups/orders params by the op-list, each op a **friendly labeled section** (NOT a flat list, NOT a raw op
editor; the Customer cannot add/remove/reorder ops). ⇒ each op-list entry needs a friendly UI label (detail → UI-grill).

**A3 — LOCKED (operator agreed): two-layer emission.** (1) **Orchestration (universal): Airflow** — every
Blueprint emits a DAG element (data Blueprints → a task; control Blueprints → a sensor/trigger/schedule); one
DAG per pipeline. (2) **Compute (data Blueprints only): PySpark / dbt / Great-Expectations** — the artifact the
Airflow task runs; control Blueprints have none. dbt's model/snapshot/incremental **kind is per-op**
(`track-history-scd2`→snapshot, `take-periodic-snapshot`→incremental, else standard model). Mode swaps the
concrete flavor (Composer / plain Airflow; Dataproc / Livy — ADR 0006). **ADR 0012 touch-up pending:** fold its
`dbt-SQL`+`dbt-snapshot` emission forms → `dbt` (kind is op-level) + record Airflow as the universal layer.

**B1 — LOCKED (operator confirmed): enforcement is THREE parts; the columns are a DESIGN-TIME fact.**
(1) **Design time = the column authority** — Schema Propagation deterministically computes the FULL schema
(every column, every step) live as the Customer designs; re-propagation on any edit + surface downstream
conflicts (per-op expression refs validated via Expression Builder/Calcite). The columns are KNOWN here,
completely, before any build. Already exists (topo-sort + SchemaConflict); this spec makes it reliable by
removing the LLM gate. (2) **Build time = code only** — op handlers emit PySpark/dbt that *produces* exactly
the design-time columns; codegen is **subordinate** to the design-time schema and never decides columns itself
⇒ it cannot conflict with Schema Propagation. (3) **Runtime** — validate the produced table matches the
contract → loud-fail; explicit DDL only where the engine doesn't make the table (PySpark bronze/external).
The ADR-0011 AI-body **repair loop is dropped** (no LLM in codegen). Re-propagation is a separate, KEPT mechanism.

**B2 — LOCKED (operator agreed):** unknown op / no op-list / op with no schema rule → **loud-fail at design
time**, surfaced clearly, blocking that Blueprint's use until its metadata is complete (never silent passthrough,
never an LLM guess). The teeth behind "100% deterministic."

**Node B — remaining work split:** the straightforward per-op column-rules (add/drop/rename/change-types/mask/
transform-values/join/group-and-aggregate/dedupe/read-source/write-sink/…) are **mechanical** — authored into §B
from the decomposition doc + code (incl. mechanical fix-item corrections #2/#3 SCD2 & Snapshot column sets, #5
join `right_`-prefix, #6 aggregate output types), gated by the SPEC-GATE. **Genuine forks still to grill:**
(B3) DQ semantics (#7); (B4) route-rows N-output port model (#1); (B5) nested-struct schema model (#12).

**B3 — LOCKED (operator agreed): DQ behaviors are Customer design-time params, not hardcoded.** `on_failure`
(block=fail the run / warn=continue) + `report_mode` (append=history / overwrite) are per-check params on the
check-data/emit-report ops, surfaced op-by-op, sensible defaults (block, append) the Customer can override.
Quarantine (bad rows → managed side-table, ADR 0012) is likewise a configurable choice. **Broader principle
(operator): a behavioral choice defaults to a Customer param with a sensible default — not hardcoded.**

**B4 — LOCKED (operator agreed):** route-rows = **dynamic output ports**, one per Customer-defined branch
(label + condition; optional catch-all default), each carrying the **input schema** (routing splits rows, not
columns). The canvas grows a port per branch. Fixes #1.

**B5 — LOCKED (operator agreed):** the column-type model **supports nested types** — simple | **struct**
(recursive named sub-fields) | **list**; nested shape from schema discovery (sampling), never LLM (ADR 0011).
flatten-json expands struct→flat; build-struct packs cols→struct. Fixes #12. **Node B design forks COMPLETE
(B1–B5);** the mechanical per-op column-rules are authored into §B from the decomposition + code.

**C1 — LOCKED (operator corrected): use native Airflow data-aware scheduling; PULSE hides the syntax.**
Same-Airflow cross-pipeline deps = **native Airflow Datasets/Assets** — PULSE emits `outlets=[Dataset(uri)]`
(producer) + `schedule=[Dataset(uri)]` (consumer); **Airflow matches by URI**, no PULSE index. The **Customer
just selects a registered dataset** from the registry (no URIs, no Airflow syntax); **PULSE generates the
canonical URI** + the outlets/schedule (hidden). The dependency edge is **surfaced on the canvas** (ADR 0022
transparency) but configured by dataset-pick. **Cross-Airflow** (separate instance/Mode, e.g. DPC↔GCP) =
**RemotePipelineInvocation** (ADR 0021). Build = wire the existing (unwired) `DatasetScheduleService` + fix the
`event`/`dataset_event` mismatch `[report]`.

**DESIGN FORKS — RESOLVED (2026-06-15).** The genuinely-open forks were settled this session (node A metadata
model; B1 enforcement/column-authority; B3 DQ-as-params; B4 router dynamic ports; B5 nested types) + C1
(cross-pipeline). **Everything else is ADR-settled — APPLY, do not re-grill:** `sql-model`/Calcite (ADR 0013);
sensors = one emitter, two entry points, surfaced on canvas (ADR 0022); config-externalization (ADR 0013);
materialization tiers (ADR 0003); Mode-specific emission incl. Dataproc/Livy + Iceberg/Hive (ADR 0006/0007, G1);
the 4 deprecations CostMonitoringHook/BackfillAndReplay/ObjectStoreKeySensor/DatasetDependencySensor
(ADRs 0020–0022); byte-exact oracle method (ADR 0009/0004; anchor key verified 242/242). Mechanical fix-items
#2/#3/#5/#6/#8/#9/#10/#11 → authored from the decomposition doc + code.

**NEXT PHASE = AUTHOR + GATE (orchestrator work, not operator grilling).** Write the full §A–E contracts
(metadata/op-list shape · the 32 ops' schema-effect rules · per-op emission handlers per engine · V153 migration
· the anchor byte-exact oracle), applying every decision above + the ADRs, then run the **SPEC-GATE** (fresh
agent → GUESSES / CONTRADICTIONS / DANGLING-REFS empty). May fan mechanical drafting to agents (design locked;
producer ≠ verifier). Bring the operator the gate result + any genuine gap it surfaces.

**NEXT NODES (grill order):** A3 emission declaration (per-Blueprint emission type) → **B** the 32 ops'
schema-effect rules (+ kill the LLM fallback `:843-851`, nested-struct types, the 12 fix-items) → **C** per-op
emission handlers (dbt-SQL / PySpark / GX / dbt-snapshot / DAG-only + config-externalization + `sql-model`/Calcite)
→ **D** V153 migration (op-list + param-tiering seed; 4 deprecations) → **E** byte-exact oracle (the anchor pipeline).

## Spec-discipline template (filled as nodes lock)
### Inputs — _(TBD)_
### Outputs / deterministic oracle — _(TBD)_
### Boundaries (with each adjacent component) — _(TBD)_
### Constraints — _(TBD)_

---
## Grill log (decisions as they land, newest last)
- 2026-06-15 — Scope locked: one comprehensive spec, all-in (above). Session = spec-only
  (no GCP/Composer this session; operator out 5–10 days after, agents build from the gated spec).
- 2026-06-15 — **Q1 (op→param binding) LOCKED.** Each op in a Blueprint's **op-list** carries config
  that **references the Blueprint's params (`params_schema`) by name** (e.g. `rename-columns` →
  `rename_map`). The op-list is authored **once per Blueprint** and is fixed; the **Customer fills only
  the params**; the engine **substitutes this instance's param values** into the ops — **at design time**
  (Schema Propagation, live column preview, runs on every composition change) **and at build time** (the
  Builder, codegen). One op-list, two readers. The op-list lives in `schema_behavior`. ("Build time"
  alone was imprecise — the param values exist from design time.)
- 2026-06-15 — **CONSTRAINT (operator): generic backend, blueprint-specific friendly UI.** The op-engine
  is generic, but the **Customer-facing config UI must stay per-Blueprint and friendly** (driven by the
  params), **never a generic op-list editor**. The op-list is backend-only.
- 2026-06-15 — **CORRECTION (operator): the op-list DOES invalidate the config panels.** `schemaBehavior`
  is unread by the UI **today only because the field is dead** (`types/index.ts:266`; panels currently
  render from `paramsSchema`, `configure-transform-dialog.tsx:232,603`). This spec makes the op-list the
  **driver of a Blueprint's behavior and therefore of what the Customer must configure** — so the param
  surface is redefined per Blueprint and **the config panels will be redone** (the **UI-audit-grill's**
  work). My earlier "does not invalidate" described the as-is, not the to-be.
- 2026-06-15 — **Cleaning op-list decisions (BronzeToSilverCleaning).** (a) Every param is **optional with
  a do-nothing default**: an unconfigured Cleaning = passthrough; configured = apply **only** the set ops
  (fixes fix-item #4 — today it `SELECT *`s and ignores params even when set). (b)
  `null_handling="drop_row"` → **Cleaning itself drops those rows** (`filter-rows` op), NOT quarantine.
  (Parked drop_row fork RESOLVED.)
