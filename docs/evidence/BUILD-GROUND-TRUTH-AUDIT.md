# Autonomous Build — GROUND-TRUTH AUDIT (2026-06-17)

> **Why this exists.** The orchestrator (me) accepted all 5 lanes as "done" on the wrong bar —
> *compile + unit tests green + diff review*. That says nothing about whether the new code is the
> **path that actually runs in a default boot**, or is reachable/visible. Operator caught it in the
> running app. This re-audits every deliverable against the right question: **ACTIVE** (runs in a
> normal boot), **DORMANT** (built + compiles + unit-tested, but switched off / behind an un-invoked
> seam / only reachable via a dormant path), **UNREACHABLE** (built but no data/wiring triggers it),
> or **NOT BUILT**. Every verdict cites file:line.

## Headline

The two **flagship engines** the build was *for* are **dormant by default**:
- the deterministic **codegen op-composition compiler** (Builder emission), and
- the **LangGraph4j chat orchestration** graph.

What is genuinely **live**: the deterministic **schema** engine, the **Catalog** data (V153/V154),
Calcite's **`[[ ]]` lowering** + validator (reachable), and the new chat **read tools**. The **UI
workspace layout was not built at all**, and the **Constructs controls are unreachable**.

The celebrated **"first e2e run"** (bronze→silver→gold on Spark) is **real but was produced by the
pre-existing TEMPLATE codegen**, not the new op-engine — so it did **not** validate the new Builder.

---

## Builder lane

| Deliverable | Status | Evidence |
|---|---|---|
| Deterministic **schema** engine (`pipeline.opengine`, 32 rules, conflict classifier) | **ACTIVE** | `SchemaPropagationService.java:889` calls `schemaOpEngine.applyOpListAsMap(...)` in the live derive path |
| LLM schema fallback removed (ADR 0011) | **ACTIVE** | `SchemaPropagationService.java:936` — `schemaInferenceService.inferOutputSchema` is deleted; loud-fail gated by `pulse.builder.loud-fail-on-missing-op-list:false` (default = permissive passthrough) |
| `IngestionAuditColumns` 7→8 (`_pulse_dag_id`) | **ACTIVE** | `IngestionAuditColumns.java:54` `DAG_ID`; used by the live codegen + schema paths |
| **Codegen emission compiler** (`codegen.opengine`, 32 handlers × 5 engines) | **DORMANT** | `CodeGenerationService.java:99/123/145/155` — `codegenOpEngine` is injected as "a single seam that the post-V153 cutover plugs into" (:93) and is **never invoked**; `generate()`→`generatePySparkJobs():871`/`generateDbtModels()` still run the **old `bpKey` if/switch templates** |

**Net:** schema half live; the emission compiler (the headline) is shelf-ware. The e2e ran the old templater.

## Catalog lane — the most solid

| Deliverable | Status | Evidence |
|---|---|---|
| `V153` op-lists + param tiering (40 blueprints) | **ACTIVE (as data + schema input)** | Postgres-validated (`V153V154PostgresCatalogIT`); op-lists drive the live schema engine. NOT consumed by codegen (dormant). 353 `tier` values seeded — but the **frontend ignores `tier`** (see Constructs). |
| `V154` demo seed (3 pipelines) | **ACTIVE** | `demoCompositionsResolveIntoFullyWiredGraphs` passed; compositions resolve via the API and render in the (old) composition panel |

## Calcite lane

| Deliverable | Status | Evidence |
|---|---|---|
| `CalciteSqlModelValidator` | **ACTIVE-WHEN-REACHED** | wired into the schema engine's `sql-model` rule via `SqlModelSchemaService`; runs only if a `SqlModel` blueprint is used (none in the default seed → unexercised by default, but reachable) |
| `[[ ]]` mnemonic lowering + dbt `--vars` | **ACTIVE** | wired into the LIVE codegen: `CodeGenerationService.java:666` (`dbt build … --vars`), `:1860` (SQL-body lowering), helpers `:4985+` |
| `SourceSQL` JDBC source-prepare (part b) | **NOT BUILT** | deferred; needs a connector→JDBC collaborator. Plan: `docs/evidence/calcite-INTEGRATION-PLAN.md` §(b) |

## Constructs lane

| Deliverable | Status | Evidence |
|---|---|---|
| 11 purpose-built controls (rename-mapper, dq-outcome, sql-builder, …) | **UNREACHABLE** | `configure-transform-dialog.tsx:138` routes by `definition.ui_construct`, but **V153 seeds 0 `ui_construct`** and the blueprint model doesn't carry it → every param falls back to the generic Textarea/Select/Input |
| Tiered per-blueprint panel (show user-tier, lock derived) | **NOT WIRED** | frontend `BlueprintParamDefinition` (`types/index.ts:222`) has `ui_construct?`/`filter_types?` but **no `tier`/`derivedFrom`** → the panel renders all params flat, can't hide/lock derived (defeats ADR 0023's minimal surface) |
| Host = spec's right-side inspector | **NOT BUILT** | panel still lives in the old modal `Dialog` (`configure-transform-dialog.tsx:5-8`), not an inspector |

**Net:** the per-blueprint config experience is the **pre-existing generic param dialog**; the new controls are shelf-ware.

## Chat/UI lane

| Deliverable | Status | Evidence |
|---|---|---|
| LangGraph4j 7-stage graph + op-queue + staging + new SSE events | **DORMANT** | `ChatService.java:332` `@Value("${pulse.chat.orchestration:loop}")` (default **loop**); `:501` runs `graphDriver` only when `graphMode()` (`:828` = `"graph"`); no key in `application.yml`. Normal boot runs the old `handleLLMMode` loop |
| 7 per-stage prompts (`chat/prompt/`) | **DORMANT** | used only by the graph path; the active loop uses the old `PulseSystemPrompt` |
| Postgres checkpointer | **DORMANT** (works in isolation) | `ChatCheckpointerRoundTripIT` green, but only exercised by the graph path |
| 6 `composition.*` command types + handlers | **DORMANT** | `CompositionCommandHandlers.java:36/39` registered, but only invoked via `OpToCommandMapper` (orchestration/graph path) |
| 7 new read/validation tools | **ACTIVE** | registered in `ChatTools.java` (shared registry, reachable by loop + graph). NOTE: `validate_plan`/`validate_sql_expression` are interim |
| Workspace layout: 4-region shell · action bar (Generate/Package/Git/Save) · right inspector swapping with Chat · canvas-centered page · node-click inspector | **NOT BUILT** | no build commit touched `app/pipelines/[pipelineId]/page.tsx`; the spec layout does not exist |

---

## Summary

- **ACTIVE in a default boot:** schema op-engine + audit-cols + no-LLM-schema (Builder); V153/V154 (Catalog); `[[ ]]` lowering + validator-when-reached (Calcite); the 7 new chat read tools.
- **DORMANT (built, off by default / un-invoked seam):** the codegen emission compiler; the entire chat graph + staging + per-stage prompts + checkpointer + `composition.*` commands.
- **UNREACHABLE (built, no data/wiring):** the Constructs controls + tiered panels.
- **NOT BUILT:** the workspace UI layout (all of it); Calcite `SourceSQL` part (b).

**Acceptance failure (mine):** I verified artifacts compile + unit-pass, never that the new path is the *active* one in a real boot. Two flagship engines are dormant and I caught neither. "Done" must mean **verified active in the running app** — not tests green.
</content>
