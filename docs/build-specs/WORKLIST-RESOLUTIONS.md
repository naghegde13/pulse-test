# PRE-BUILD WORKLIST RESOLUTION LEDGER

**Date:** 2026-06-16
**Purpose:** Sweep every pre-build design spec for flagged GUESSes / open-worklist items / "operator to confirm"
markers, and for EACH one propose a concrete, grounded resolution. Each row is classed **DEFAULT** (code/spec-grounded,
safe to apply without operator input), **DECIDE** (a genuine product/architecture choice the operator must make), or
**DERIVED** (mechanically derivable from another already-decided item).

> ⚠️ **DO NOT APPLY UNTIL GATED.** This ledger is a *proposal*. Five other agents are concurrently READING the specs to
> gate them; the specs MUST NOT be edited until those gates land and the operator signs off. This file only proposes
> resolutions — it changes no spec, ADR, or chat-prompt fragment. The apply step happens later.

**Sources swept:** `SPEC-schema-op-engine.md` (#1) · `SPEC-codegen-compiler.md` (#2) · `SPEC-ui-composition.md` (#3,
incl. §7.16 / §7.17) + the 7 chat-prompt fragments `01-…`–`07-…` · `SPEC-construct-library.md` (#4) ·
`SPEC-blueprint-catalog.md` (#5) · `SPEC-calcite-sql-model.md` (#6) · ADR `0024-*`.

---

## §1 — TOOL-NAME RESOLUTION (operator priority #1)

### The convention (resolved from live code)

**Every live LLM tool name in PULSE is `snake_case` with underscores. ZERO tools use a hyphen.** The shape is
`<verb>_<noun>(_object)`: reads `get_*` / `list_*`, creates `create_*`, plan/proposal mutators `plan_*` (ARCH-018
canonical; the older `propose_*` are deprecated aliases routing to the same handlers), direct mutators are
action-verb-prefixed (`apply_`, `configure_`, `wire_`, `remove_`, `update_`, `request_`, `suggest_`, `derive_`,
`preview_`, `check_`, `evaluate_`).

**Grounding:** the 48-tool registry in `backend/src/main/java/com/pulse/chat/service/ChatTools.java` (e.g.
`get_blueprint_detail` :115, `plan_create_pipeline` :152, `plan_add_step` :166, `plan_wire_ports` :175,
`plan_set_step_params` :185, `apply_plan` :368, `wire_ports` :295, `configure_step_params` :274, `remove_step` :305);
dispatch in `ChatToolExecutor.java:246-302`; ARCH-018 note at `ChatTools.java:148-150`. Frontend mirrors the same
snake_case event/tool strings (`chat-panel.tsx`). **Hyphen count across the live registry: 0.**

### CONSEQUENCE — every hyphenated tool name in the specs is WRONG STYLE and must be renamed to underscores

#3 §7.17 A (table N1–N7), fragment 01 §381, fragment 02 §310-313 all introduce composition mutators in **hyphen** form
(`add-blueprint-instance`, `wire-ports`, `set-params`, `remove-instance`, `remove-wire`, `rename-instance`,
`set-pipeline-setting`). These violate the live convention. The authoritative names below are the underscore forms.

### Authoritative tool-name table

| # | Proposed FINAL name | Tier | Spec wrote (style to FIX) | Introduced by | Status vs live code |
|---|---|---|---|---|---|
| T1 | `add_blueprint_instance` | mutation | `add-blueprint-instance` (hyphen ✗) | #3 §7.17 N1, frag 01/02 | re-times live `plan_add_step` (ChatTools.java:166) |
| T2 | `wire_ports` | mutation | `wire-ports` (hyphen ✗) | #3 §7.17 N2 | **already exists** `wire_ports` (ChatTools.java:295) — keep name, re-route to queue |
| T3 | `set_params` | mutation | `set-params` (hyphen ✗) | #3 §7.17 N3 | re-times `plan_set_step_params` / replaces `configure_step_params` (:274) |
| T4 | `remove_instance` | mutation | `remove-instance` (hyphen ✗) | #3 §7.17 N4 | replaces direct `remove_step` (:305) |
| T5 | `remove_wire` | mutation | `remove-wire` (hyphen ✗) | #3 §7.17 N5 | new |
| T6 | `rename_instance` | mutation | `rename-instance` (hyphen ✗) | #3 §7.17 N6 | new |
| T7 | `set_pipeline_setting` | mutation | `set-pipeline-setting` (hyphen ✗) | #3 §7.17 N7 | re-times `update_pipeline_orchestration` (:283) |
| T8 | `get_composition_overview` | read-only | (already underscore ✓) | #3 §7.3 A / §7.17 N8 | new (compact summary; partial overlap w/ `get_composition` :142) |
| T9 | `get_blueprint_op_list` | read-only | (✓) | #3 §7.3 A / §7.17 N9, #6 | new; depends on ADR 0012 op-vocabulary landing |
| T10 | `get_step_schema` | read-only | (✓) | #3 §7.3 A / §7.16 #6 | **rename of** live `get_upstream_schema` (ChatTools.java:251) |
| T11 | `validate_structure` | read-only | (✓) | #3 §7.3 A / §7.17 N10 | new (orphans/cycles/reachability) |
| T12 | `validate_configuration` | read-only | (✓) | #3 §7.3 A / §7.17 N11 | new (per-step param/port completeness) |
| T13 | `validate_plan` | read-only (plan pre-flight) | (✓) | #3 §7.3 A / §7.13 / §7.17 N12, #6 | new; depends on ADR 0011/0012/0013 Builder |
| T14 | `validate_sql_expression` | read-only | (helper, unnamed) | #3 §7.17 N13, #6 §A | new; Calcite-validate a derived-column expr / `sql-model` body (#6 `CalciteSqlModelValidator`) |

> **Naming verdict:** keep already-underscore names as written; **rename every hyphenated proposed name to its
> underscore form** (T1–T7). For T2 (`wire_ports`) the live name is already correct — reuse it, only change the timing
> (route to the op-queue instead of direct write). T10 is a *rename* of a live tool, not a new one. All DERIVED from the
> §1 convention except the genuine deprecation choice (which `apply_plan`-bypassing direct mutators to *remove* vs
> *route-to-queue*), which is the DECIDE item flagged at #3 §7.16 #7 (see row 3-07).

---

## §2 — `composition.*` COMMAND-TYPE RESOLUTION (operator priority #2)

### The convention (resolved from live code)

**Command types are `noun.verb`, dot-separated, lowercase noun + present-tense verb; compound verbs are camelCase
(NOT snake_case) after the dot.** A three-part `noun.subnoun.verb` form exists for nested domains.

**Grounding:** `PipelineCommandHandlers.java:19-23` registers `pipeline.create` / `pipeline.update` / `pipeline.delete`
/ `pipeline.transition` / `pipeline.createRevision` (camelCase compound verb at :23);
`BrokerInvocationCommandHandlers.java:18` registers the nested `broker.remoteInvocation.configure`. The Command-Log
column `command_type VARCHAR(100)` has **no CHECK constraint** (`V1__init_schema.sql:89`), so new types are additive.
**No `composition.*` command type exists today** — composition mutations currently bypass the Command Log entirely (the
live `ChatToolExecutor` calls `CompositionService` directly; the §7.0 grounding gap #3 names).

### Authoritative six-string table

| # | FINAL string | Maps to op (#3 §7.4 / §7.17) | Spec wrote | Convention check |
|---|---|---|---|---|
| C1 | `composition.addInstance` | `addInstances` op | `composition.addInstance` | ✓ noun.verb, compound verb camelCase (mirrors `pipeline.createRevision`) |
| C2 | `composition.removeInstance` | `removeInstance` op | `composition.removeInstance` | ✓ |
| C3 | `composition.updateInstance` | `updateInstance` op | `composition.updateInstance` | ✓ |
| C4 | `composition.wirePorts` | `mergeWiring` op | `composition.wirePorts` | ✓ camelCase compound verb |
| C5 | `composition.removeWiring` | `removeWiring` op | `composition.removeWiring` | ✓ |
| C6 | `composition.renameInstance` | `rename` op | `composition.renameInstance` | ✓ |

> **Command-type verdict:** the six strings the spec proposes (#3 §7.4 :466, §7.17 B2 :1014; fragment 06 §F :384;
> fragment 05 :433) are **already in the correct convention** — `noun.verb` dot-separated with camelCase compound verbs,
> exactly matching `pipeline.createRevision`. No restyle needed; they are DERIVED-clean from the live convention. The one
> coupled decision the spec flags (do `setName` / `setPipelineSetting` fold into `pipeline.update`, or become their own
> `composition.*` types?) is captured as ledger row 3-09.

---

## §3 — PER-SPEC LEDGER

### #1 — SPEC-schema-op-engine.md

> Status: this spec's OPEN WORKLIST (§:463-481) is **fully RESOLVED** — every G-item (G-1, G-4, G-7, C-3/G-14) and
> GAP4 is marked RESOLVED/locked 2026-06-15, with the JSON shapes pinned as the contract. No residual flagged item
> requires an operator decision. Listed for completeness; nothing to resolve.

| ID | Location | What it asks | Proposed resolution | Grounding | Class |
|---|---|---|---|---|---|
| 1-01 | §A.1 (G-1, :95/:477) | Is the `schema_behavior`/op-entry JSON shape THE contract? | RESOLVED in-spec — shape is pinned as the contract. No action. | spec :95 "RESOLVED (G-1 — locked)"; :477 | DEFAULT |
| 1-02 | §B.0 (G-4, :246/:480) | Is the recursive nested-type encoding THE shape? | RESOLVED in-spec — pinned. No action. | spec :246, :480 | DEFAULT |
| 1-03 | §B (G-7, :368) | Is the schema-deriving Calcite validator a named build prerequisite? | RESOLVED — named `CALCITE-PHASE-2` prerequisite (carried by #2 §C.4 / #6 §A). | spec :368; #2 :404; #6 §A | DEFAULT |
| 1-04 | OPEN WORKLIST (C-3/G-14, :234/:471) | "bounded repair regeneration" wording | RESOLVED — ADR-text annotation applied; no behavior change. | spec :234, :471; #2 :398 | DEFAULT |

---

### #2 — SPEC-codegen-compiler.md

> Status: OPEN WORKLIST (§:380-446) is **RESOLVED-or-flagged**. The two residuals (G-13 build-existence, G-7
> prerequisite) are RESOLVED-AS-FLAGGED / RESOLVED-AS-PREREQUISITE — i.e. they are tracked build work, not open
> decisions. G-11/G-12 are resolved facts.

| ID | Location | What it asks | Proposed resolution | Grounding | Class |
|---|---|---|---|---|---|
| 2-01 | §C.2 (G-13, :153/:407) | DPC/Livy/Hive-Parquet emission path "remains to be built" | RESOLVED-AS-FLAGGED — this is tracked build work (the Dataproc/Livy path), not an open question. Carry as a build task; no operator decision. | spec :153, :407 | DEFAULT |
| 2-02 | §C.4 (G-7, :196/:404) | Calcite Phase-2 validator as named prerequisite | RESOLVED-AS-PREREQUISITE — `CALCITE-PHASE-2` named; does not yet exist; gates the SQL path. | spec :196, :404; #6 §A | DEFAULT |
| 2-03 | §E (G-11, :357/:419) | Silver business-column count: 78 or 87? | RESOLVED — **78** (the task framing's 87 was a misread). Fact, not a decision. | spec :357, :419 | DEFAULT |
| 2-04 | §E (G-12, :361/:422) | Anchor silver op-list = which ops? | RESOLVED — = Bronze-to-Silver Cleaning's ops + a `filter-rows(loan_status=…)`. Fact. | spec :361, :422 | DEFAULT |

---

### #3 — SPEC-ui-composition.md (§7.16 master index of 18 + §7.17 producer-pass)

> §7.16 decomposes 17 inline GUESSes into 18 numbered decisions. The 7 chat-prompt fragments (01–07) each carry a local
> GUESS worklist, but every fragment GUESS **explicitly defers final ownership** to a §7.16 item — so the fragments add
> almost no NEW decisions, only restyling fixes and two genuinely-new sub-items (per-stage tool allow-lists; optional
> initial-params on add). Rows below are keyed to the §7.16 numbering.

| ID | Location | What it asks | Proposed resolution | Grounding | Class |
|---|---|---|---|---|---|
| 3-01 | §7.16 #1 (:832) | Build routed 7-stage model now, OR keep single OpenRouter loop with phase-gated prompt sections? | **DECIDE.** Options: (A) build the staged graph now; (B) single bounded loop with phase-gated prompt assembly + tool-gating modes (the fragments' lighter default). **Recommend B** — stage COUNT is already LOCKED at 7; the live code is one loop (`ChatService.handleLLMMode`), n8n itself collapsed to one graph + post-approval builder (frag 07 §6.1), and B is strictly less new infra. Promote to A only if the single-loop model proves insufficient. | stage count LOCKED `01-system-prompts.md` §F; live single loop `ChatService.java:574`; frag 07 §5.1, §6.1 | DECIDE |
| 3-02 | §7.16 #2 (:835) | Per-stage model matrix: which stages use `pulse.llm.model` vs the reasoning model | **DEFAULT.** All phases on `pulse.llm.model`; optional `planner` escalation to the reasoning model. Grounded in the live single-model loop + the `LlmEndpointService` enum→model resolution; "Vertex later = config swap" is a pure config swap behind the seam. | `LlmEndpointService.java:87-106`; frag 04 :105-110; frag 07 §5.3 | DEFAULT |
| 3-03 | §7.16 #3 (:837) | Recursion bound: keep `MAX_TOOL_ROUNDS=30` or raise (~40)? | **DECIDE (low-stakes).** Options: keep 30 (live constant) vs 40. **Recommend 40** — a multi-Blueprint build can exceed 30 tool calls (frag 07 :172-174); raising the bound is a one-constant change with no downside but latency tail. | live constant `30`; frag 07 :172-174 | DECIDE |
| 3-04 | §7.16 #4 (:838) | Confirm the `chat_turn_snapshots{sessionId,turnId,versionId,…}` table | **DEFAULT.** Adopt the proposed table shape — it is the snapshot store §7.8/§7.11 revert needs; no live equivalent exists, so the proposed `{sessionId,turnId,versionId,canonicalSnapshotJson,createdAt}` is the contract. (The undo-semantics half is the separate DECIDE row 3-13.) | spec :258-261; frag 07 :343 | DEFAULT |
| 3-05 | §7.16 #5 (:839) | Plan `previewData` shape maps onto live `Plan.previewData`/`plannedCommands` split? | **DEFAULT.** Yes — the proposed `{summary,trigger,steps,additionalSpecs}` maps onto the live human-display `previewData` + executable `plannedCommands` split. Reconcile field names against `PlanService.serializeCommands`; no new contract. | `Plan.java:37-72`; spec :305-308 | DEFAULT |
| 3-06 | §7.16 #6 (:841) | Confirm discovery/validation tool names; which exist | **DERIVED** from §1. Final names: `get_composition_overview` (new), `get_step_schema` (rename of `get_upstream_schema`), `get_blueprint_op_list` (new), `validate_structure` / `validate_configuration` / `validate_plan` (new), `validate_sql_expression` (new). All underscore per §1. | §1 table T8–T14; `get_upstream_schema` ChatTools.java:251 | DERIVED (→ §1) |
| 3-07 | §7.16 #7 (:844) | Confirm op-emitting mutator renames + **deprecate** direct `wire_ports`/`configure_step_params`/`remove_step` | **Two parts.** (a) Renames = **DERIVED** from §1 (T1–T7, underscore). (b) The **deprecation path** — remove the direct-write tools vs re-route them to the op-queue — is a genuine **DECIDE** ("the single biggest divergence from today's code", spec :389). **Recommend route-to-queue** (don't delete): keeps the tool surface stable, makes every mutation previewable, and is reversible. | §1 table; spec :383-389; frag 02 :609 | DECIDE (deprecation only) |
| 3-08 | §7.16 #8 (:847) | `instanceRef` keying: refs-by-name + apply-time id resolution vs ids-only | **DEFAULT.** Refs-by-name (the stable pre-apply handle; staged instances have no id yet) + apply-time id resolution. Grounded in the live `Plan.draftRefDeclarations`/`draftRefBindings` mechanism + n8n keys connections by name. | `Plan.java:75-80`; frag 06 :321-323; spec :431-436 | DEFAULT |
| 3-09 | §7.16 #9 (:848) | Confirm the six `composition.*` types + whether `setName`/`setPipelineSetting` fold into `pipeline.update` | **Two parts.** (a) The six strings = **DERIVED** from §2 (C1–C6, already correct convention). (b) Whether `setName`/`setPipelineSetting` fold into `pipeline.update` (vs becoming a 7th/8th `composition.*` type) is a small **DECIDE**. **Recommend fold into `pipeline.update`** — they are pipeline-level (not composition-graph) settings, consistent with `pipeline.update` already owning pipeline mutations. | §2 table; `PipelineCommandHandlers.java:19-23`; spec :466-471 | DERIVED (strings) + DECIDE (fold) |
| 3-10 | §7.16 #10 (:850) | Confirm new SSE event names; add `…/plans/{planId}/decision` endpoint? | **Two parts.** (a) Event **names** = **DERIVED**: live SSE events are lowercase snake_case (`chunk`/`done`/`error`/`tool_call`/`tool_result`/`navigate`), so new events MUST be `tool_progress`, `candidate_graph`, `questions`, `plan`, `messages_compacted` — **underscore, not the hyphenated `candidate-graph` that §7.5 wrote** (frag 05 correctly uses underscores). (b) The session-scoped `…/plans/{planId}/decision` endpoint vs reusing tenant-scoped `…/approve\|cancel` is a small **DECIDE** — **recommend the new session-scoped single-call decision endpoint** (cleaner HITL transport, frag 07 §5.5). | live events `ChatService.java:523,550,558,691,710,750`; `chat-panel.tsx:139-321`; spec §7.5 :509 (hyphen) vs frag 05 :281 (underscore) | DERIVED (names) + DECIDE (endpoint) |
| 3-11 | §7.16 #11 (:852) | Node-position store: where manual canvas positions persist (no x/y on `SubPipelineInstance`) | **DEFAULT.** Client-side position map keyed by `instanceRef` during a turn, persisted to instance metadata at Apply; Dagre auto-layout on STRUCTURAL change only. No x/y column exists today, so this is the contract. | spec :535-537; frag 05 :379-381; frag 07 :421-424 | DEFAULT |
| 3-12 | §7.16 #12 (:853) | Diff content-equality: which `SubPipelineInstance` fields count toward "modified" | **DEFAULT (with one micro-DECIDE).** Projection = `{name, blueprintKey, blueprintVersion, params, lakeLayer, lakeFormat, storageBackend}` (secrets excluded — SecretRefs per ADR 0023). Open micro-question: do `dqExpectations`/`executionOrder` count (noise vs completeness)? **Recommend exclude both** to avoid noisy diffs (they're not structural). | frag 06 :413-417; spec :561-562; ADR 0023 | DEFAULT (recommend exclude dqExpectations/executionOrder) |
| 3-13 | §7.16 #13 (:854) | Undo = inverse plan (new commands) vs restore snapshot; compaction threshold | **DECIDE.** Options: (A) undo = inverse plan (append-only, audit-clean, harder to derive); (B) undo = restore the `chat_turn_snapshots` snapshot (simpler, since canonical is write-locked behind Apply). **Recommend B** — PULSE's plan-gate makes snapshot-restore strictly cheaper/safer than n8n (frag 07 §6.2); one turn = one snapshot. Compaction token threshold is a tunable default (set ~50% of context budget). | spec :622; frag 07 §5.6, §6.2 | DECIDE |
| 3-14 | §7.16 #14 (:856) | Concurrency: BLOCKED-while-staged vs REBASED | **DECIDE (the brief flags this as decide-or-flag).** Options: (A) BLOCK canonical edits while a plan is staged (spec default; simplest, safe); (B) REBASE the staging clone onto concurrent canonical edits. **Recommend A (BLOCKED)** — simplest correct behavior; single-user-per-pipeline is the common case; rebase is a v2 enhancement. | spec :629-639 | DECIDE |
| 3-15 | §7.16 #15 (:857) | `validate_plan` composition: interim (structure+config+contract-readiness) vs block on deterministic Builder | **DECIDE (sequencing).** Options: (A) interim composition now (structure + config + contract-readiness checks) until the ADR 0012/0013 Builder lands; (B) block `validate_plan` entirely on the Builder. **Recommend A** — ship the interim checks so the workspace works pre-Builder; swap in the Builder pre-flight when it lands. This is a build-sequencing call, not a permanent design fork. | spec :662-665; ADR 0011/0012/0013 | DECIDE |
| 3-16 | §7.16 #16 (:859) | Catalog tight-entry schema + prompt-cache markers; do all ~50 Blueprints fit the cache budget? | **DEFAULT (mostly) + one open fact.** Tight-entry schema = the proposed per-Blueprint key-param list (dump-all into the cached prompt, §7.18 row 17 LOCKED catalog-in-prompt). The prompt-cache markers are **unimplemented today** (no `cache_control` in the live request body, `ChatService.java:649-662`) — so the marker note stays a forward build item, not a decision. Budget-fit is a measurable fact to verify, with the §6 hybrid (summaries + on-demand) as the named fallback. | spec :695-698, §7.18 row 17; frag 04 :274 | DEFAULT (fallback named) |
| 3-17 | §7.16 #17 (:860) | Split the one concatenated prompt into 7 stage assemblies + 5 category guides; **tune the DE voice** | **Two parts.** (a) The prompt-*shape* (7 stage assemblies + 5 category guides folding SINK→Transform, CONTROL→Orchestration) is **DEFAULT** — text is already DRAFTED in fragments 01/03; shape is LOCKED (§7.18). (b) The verbatim DE-**voice** review of every best-practice/use-case/pitfall line is a genuine **DECIDE** (operator voice; this is the single biggest content-review bucket, ~150 GUESS prose lines in fragment 03 + the §6 voice worklist). Highest-leverage voice calls: DQ outcome defaults (quarantine at ingestion / block at promotion), the modeling history-strategy tree, the one-schedule-per-pipeline rule, the late-data partition policy, the dedupe-vs-SCD2 boundary. | spec :780-785, §7.18 rows 17-18; frag 03 §6 :1162-1183; frag 01 :385 | DEFAULT (shape) + DECIDE (voice) |
| 3-18 | §7.16 #18 (:864) | Universal Plan→Apply gate: must domain/SOR/connector/dataset creation also be Plan→Apply-gated? | **DECIDE.** Options: (A) route ALL entity creation through Plan→Apply (universal gate); (B) keep direct-write for non-composition entities under Absolute Rule #8 (live behavior gates only composition/pipeline mutations). **Recommend A** for state-changing creates beyond draft-saves (consistent with the chat→plan→command model in CLAUDE.md), but this is a real product call on friction vs consistency. | spec :819-822 | DECIDE |

#### #3 chat-prompt fragments — items NOT already covered by §7.16

| ID | Location | What it asks | Proposed resolution | Grounding | Class |
|---|---|---|---|---|---|
| 3-F1 | frag 01 §139 / §06.1 route set | The route-string set `{discover, build, configure, provision, explain, plan-decision}` — confirm the route NAMES | **DERIVED** from 3-01 + §1 convention. If the staged model is built (3-01=A), these are the route strings; lowercase, hyphen-free (use `plan_decision` not `plan-decision` to match the underscore convention). Mechanically derived once 3-01 is decided. | frag 01 :139-144; §1 convention | DERIVED (→ 3-01, §1) |
| 3-F2 | frag 01 §216 `discoveryResult` | Does discovery hand off via a structured tool call (`submit_discovery_results` analogue) or by feeding observations forward? | **DERIVED** from 3-01. If single-loop (3-01=B), no `submit_discovery_results` tool — discovery feeds observations forward (live behavior). If staged (A), add the structured tool. Falls out of the stage decision. | frag 01 :216-219 | DERIVED (→ 3-01) |
| 3-F3 | frag 02 §301-305 per-stage tool allow-lists | Confirm the exact per-stage tool allow-lists (which system prompt advertises which tools); Build/Composer + Provision share one backend registry | **DECIDE (small).** The backend registry is shared; the stage split is a prompt-architecture boundary. Operator/SPEC-GATE finalizes which tools each stage's prompt advertises. **Recommend** deriving allow-lists mechanically from tier: discovery→read tools, composer→`add_blueprint_instance`/`wire_ports`/`remove_*`, configure→`set_params`/`validate_*`, provision→the provision-tier tools (frag 02 §1). Only the provision-tier boundary needs an explicit ruling. | frag 02 :301-305 | DECIDE (small) |
| 3-F4 | frag 02 §309-314 add-instance initial params | May `add_blueprint_instance` carry an optional initial `params` (n8n-style), or must it always be followed by a separate `set_params` op? | **DECIDE (small).** Options: (A) split always (current spec design — each op individually previewable, params validated separately); (B) allow optional initial `params` on add (matches n8n, fewer round-trips). **Recommend A (split)** — the staging BLEND wants each op individually previewable, and params validate against the typed Blueprint schema + contract separately. | frag 02 :309-314 | DECIDE (small) |
| 3-F5 | frag 02 §411-414 context-block tags | Confirm the field set inside `<dataset_schemas>` / `<schema_visibility>` / `<conflict_overlay>` (PULSE-specific, no n8n analogue) | **DEFAULT.** Adopt the proposed field sets — they exist BECAUSE of ADR 0011 schema-as-contract + the §2 CONFLICT OVERLAY; no renderer pins them yet, so the spec's proposal IS the contract. Verify against #3 §2 when the overlay is built. | frag 02 :411-414; ADR 0011; #3 §2 | DEFAULT |
| 3-F6 | frag 05 §247-251 SSE transport | Confirm SSE is the final transport (vs adopting n8n's jsonl-over-chunked-HTTP) | **DEFAULT.** Keep SSE — PULSE already streams SSE (`ChatService.java:495`, `ChatController.java:76`); n8n's newer `instance-ai` SSE direction matches PULSE's existing transport. Do NOT adopt jsonl. | frag 05 :247-251; `ChatService.java:495` | DEFAULT |
| 3-F7 | frag 07 §238 shared-state channels | Confirm the per-turn state carrier's channel names (CompositionView + op-queue + phase + plan-lifecycle) | **DERIVED** from 3-01. PULSE has no LangGraph state object today; the channel set is the target carrier IF the staged graph is built. Names fall out of 3-01 + §7.10 state-layers. | frag 07 :238-239; spec §7.10 | DERIVED (→ 3-01) |
| 3-F8 | frag 03 §86 guide count | The n8n registry imports 17 guide modules, not 18; PULSE consolidates to 5 category guides | **DEFAULT.** Cosmetic count fix — PULSE's mapping is 5 category guides regardless of n8n's 17-vs-18. No decision; correct the count reference. | frag 03 :86, :107, :1164 | DEFAULT |

---

### #4 — SPEC-construct-library.md (OPEN WORKLIST W-1…W-13)

| ID | Location | What it asks | Proposed resolution | Grounding | Class |
|---|---|---|---|---|---|
| 4-W1 | §3 (:310) | Stale-column-reference (upstream edit drops a picked column) → panel warning, not silent | **DEFAULT.** Surface as a panel warning, owned by #3's schema-conflict overlay (#3 §2). Confirm placement = the overlay, not the construct. | spec :310-311; #3 §2 | DEFAULT |
| 4-W2 | §3 (:312) | Split *loading* (schema fetch in flight) from *empty* (zero-column source) | **DEFAULT.** #3 passes a `loading` flag; loading → skeleton/spinner, empty → text fallback. Today both collapse to text fallback. | spec :312-313 | DEFAULT |
| 4-W3 | §4 (:314) | Build `rename-mapper` as the general two-column-map control; `key-value-mapper` + `type-cast-mapper` reuse it | **DEFAULT.** Consolidate — one general two-column-map control parameterized on the right-hand cell; `fill_null_map` and `type_coercions` reuse it. Reduces three constructs to one parameterized base. | spec :314-316 | DEFAULT |
| 4-W4 | §4 (:317) | Prop shape `{ columns, value: Record<string,string>, onChange }` | **DEFAULT.** Adopt — mirrors `column-picker`'s prop shape; consistent with W3's consolidation. | spec :317-318 | DEFAULT |
| 4-W5 | §5 (:319) | Per-value mnemonic on `conditions[].value` uses an embedded `date-mnemonic-picker` cell, NOT the `[[ … ]]` token | **DEFAULT.** Visual condition rows use the embedded picker cell; `[[ … ]]` is for raw-SQL mode only. Carries SPEC-blueprint-catalog.md:426. Couples to W11. | spec :319-320; #5 :426; ADR 0024 | DEFAULT |
| 4-W6 | §5 (:321) | Visual-mode rows are structurally-valid-by-construction; only error is empty value on a non-nullary operator | **DEFAULT.** Confirm — no deeper validation in visual mode; structural validity is by-construction. | spec :321-322 | DEFAULT |
| 4-W7 | §6 (:323) | Prune deprecating `ObjectStoreKeySensor` / `DatasetDependencySensor` from the sensor set to match #5 | **DEFAULT.** Prune both from `orchestration-panel.tsx:35,37` — they're absorbed into FileArrivalSensor / ScheduleAndTriggers per #5. Mechanical alignment to #5. | spec :323-324; `orchestration-panel.tsx:35,37`; #5 | DEFAULT |
| 4-W8 | §6 (:325) | Keep `cron-builder` as its own #4 construct, hosted by `sensing-config` (not folded) | **DEFAULT.** Keep `cron-builder` standalone, hosted (not folded) by `sensing-config`. | spec :325-326 | DEFAULT |
| 4-W9 | §7 (:327) | DQ-outcome option set is port-aware — `quarantine` only when host declares a `quarantine_output` port | **DEFAULT.** Port-aware option set; #3 passes the declared output roles (DQValidator offers quarantine, SchemaDriftDetection doesn't). Grounded in #5's port declarations. | spec :327-328; #5 DQValidator/SchemaDriftDetection | DEFAULT |
| 4-W10 | §8 (:329) | Add optional "as-of business date" prop so mnemonics resolve for the §Preview SQL preview | **DEFAULT.** Add the optional prop — needed for the per-step SQL preview to resolve mnemonics at design time (couples to #3 B3 preview endpoint). | spec :329-330; #3 §7.17 B3 | DEFAULT |
| 4-W11 | §8 (:331) | Add an embedded-cell variant for `condition-builder` value cells (couples to W5) | **DEFAULT.** Add the embedded-cell variant; couples to W5. | spec :331 | DEFAULT |
| 4-W12 | §8 (:332) | Confirm `date-mnemonic-picker` feeds the `{date}` substitution for `filename_pattern` (the G-10 unification) | **DERIVED** from #5 G-10. One date experience across `[[ ]]` SQL tokens and the legacy `{date}`/`date_value` facility — resolved at the picker level. Mechanically follows once #5 G-10 (5-G10) is decided. | spec :332-333; #5 G-10 (5-G10) | DERIVED (→ 5-G10) |
| 4-W13 | cross-cutting (:334) | Construct library replaces the generic `configure-transform-dialog` type-switch fallbacks; #3 owns wiring each construct into the type-switch | **DEFAULT.** Confirm — replace raw-JSON `<Textarea>` (object/object[]) and generic `<Select>` (enum) fallbacks with the purpose-built controls; #3 owns the type-switch wiring. The library's whole reason to exist. | spec :334-336 | DEFAULT |

---

### #5 — SPEC-blueprint-catalog.md (OPEN WORKLIST G-1…G-15 + the `Derive` GUESS)

> §0.1's tiering heuristic flags ~300 per-param tier calls as `> GUESS:`. The single highest-leverage decision is G-1
> (5-G01): one ruling on the heuristic unblocks them all. G-2…G-8 are the heuristic's specific judgment-calls. G-9 is
> the forward-reference list to #4 (mechanical). G-13/G-14/G-15 are intent/catalog decisions.

| ID | Location | What it asks | Proposed resolution | Grounding | Class |
|---|---|---|---|---|---|
| 5-G01 | G-1 (:1106) | Confirm the §0.1 tiering heuristic itself (one ruling unblocks ~300 param calls) | **DECIDE (highest-leverage).** The heuristic is "minimal user surface: a param is `user` only if it's a genuine business choice; everything that looks like deployment wiring / platform reliability / storage convention tilts `system-derived`." It is the author's reading of ADR 0023, NOT operator-ratified. **Recommend ratify as written** (it directly implements ADR 0023's minimal-surface mandate) — one yes unblocks the ~300 calls; G-2…G-8 are then mechanical applications of it. | spec :64-90, :1106; ADR 0023 | DECIDE |
| 5-G02 | G-2 (:1107) | `storage_backend`/`lake_layer`/`lake_format` system-derived everywhere (required in JSON but codegen/deploy-resolved) | **DERIVED** from 5-G01. They are deployment/storage convention → `system-derived` by the heuristic. Once G-1 is ratified this is mechanical (the universal storage block §0.2). | spec :1107-1108; §0.2; (→ 5-G01) | DERIVED (→ 5-G01) |
| 5-G03 | G-3 (:1109) | `partition_by`/`cluster_by` system-derived (storage convention) vs power-user override (affects 10+ entries) | **DERIVED** from 5-G01, with one residual default-choice. Heuristic → `system-derived`. The only sub-question (expose a power-user override?) is itself answered by the minimal-surface mandate: default system-derived, no override unless a real complaint surfaces. | spec :1109-1110; (→ 5-G01) | DERIVED (→ 5-G01) |
| 5-G04 | G-4 (:1111) | Reliability knobs (retry/poke/timeout/mode/chunk/parallelism/batch/pool) system-derived vs power-user `user` | **DERIVED** from 5-G01 via the §0.1.1 `platform_default` class. Heuristic → `system-derived` (platform reliability knobs); flip to `user` only on explicit per-API tuning need. Mechanical once G-1 lands. | spec :1111-1112, §0.1.1; (→ 5-G01) | DERIVED (→ 5-G01) |
| 5-G05 | G-5 (:1113) | `SchemaDriftDetection.expected_columns`: `user` (Customer asserts baseline) vs system-derived from input port contract | **DECIDE (not mechanical — it's an intent question).** Options: (A) `user` — the Customer asserts the *expected* baseline (drift detection means asserting what you expect); (B) `system-derived` from `input_port.contract` if PULSE auto-snapshots the contract. **Recommend A (`user`)** — drift detection is semantically "I assert this baseline"; auto-deriving it from the current contract defeats the purpose (you'd never detect drift from intent). This is the one tiering call the heuristic doesn't settle. | spec :709-710, :1113-1114 | DECIDE |
| 5-G06 | G-6 (:1115) | SINK perf knobs (`optimize_after_write`/`z_order_columns`, `clustering_columns`) `user` vs system-derived | **DERIVED** from 5-G01. Perf/storage knobs → `system-derived` by the heuristic; flip individual ones to `user` only if a real perf-tuning workflow demands it. | spec :783, :1115; (→ 5-G01) | DERIVED (→ 5-G01) |
| 5-G07 | G-7 (:1116) | `StreamWriter.checkpoint_location` derivation path (system-derived per ADR 0023 "unique per pipeline") | **DEFAULT.** `system-derived` with derivation = unique-per-pipeline path (ADR 0023 explicitly says checkpoint_location "must be unique per pipeline"). The path derives from pipeline id + step; this is grounded in ADR 0023, not a judgment call. | spec :831, :1116; ADR 0023 | DEFAULT |
| 5-G08 | G-8 (:1117) | `ScheduleAndTriggers.timezone` + ingestion/sensor `date_format` derived from domain/platform vs `user` | **DERIVED** from 5-G01. `timezone` ← domain (consistent with the calendar facility); `date_format` ← platform convention. Both `system-derived`; flip `date_format` to `user` only for odd source naming. Mechanical once G-1 lands. | spec :246, :935, :1117; (→ 5-G01) | DERIVED (→ 5-G01) |
| 5-G09 | G-9 (:1121) | ~33 constructs named in #5 but not yet defined in #4 — forward references | **DEFAULT.** All are forward references to #4 (now authored). Each is defined/confirmed/renamed in #4; #3 panels then derive (#5 × #4 × #3). Mechanical cross-ref, not a decision — #4's W-rows resolve the construct shapes. | spec :1121-1127; #4 (SPEC-construct-library.md) | DEFAULT |
| 5-G10 | G-10 (:1128) | Legacy `{date}`+`date_value` mnemonic facility vs ADR-0024 `[[ … ]]` SQL-token facility — unify at #4 `date-mnemonic-picker` | **DEFAULT.** Unify at the #4 `date-mnemonic-picker` level so the Customer sees ONE date experience — the picker emits `[[ … ]]` in SQL context and `{date}` in `filename_pattern` context (4-W12). Grounded in ADR 0024 + #4 §8. | spec :1128-1129; ADR 0024; #4 §8 (4-W12) | DEFAULT |
| 5-G11 | G-11 (:1132) | `SqlModel`/`SourceSQL` param key names + reserved `input` port name + output port names + declared-schema-fallback param | **DERIVED** from #6 (6-G04, 6-G06) + #1 §A.1. Reconcile the param keys (`steps`/`source_query`/`connector_id`), reserved `input` name, output ports (`sql_output`/`source_output`) against #6 §B.1/§C.1 + #1 §A.1 before V153 seeds them. Falls out of #6's key/name resolution. | spec :1132-1134; #6 §B.1/§C.1; #1 §A.1; (→ 6-G04, 6-G06) | DERIVED (→ 6-G04/G06) |
| 5-G12 | G-12 (:1135) | `SqlModel.lake_layer` default: silver vs gold | **DECIDE (small).** Options: silver vs gold default. **Recommend silver** — a SqlModel transform is the typical bronze→silver/silver→silver hop; gold is the materialization-for-consumption layer and is the less common default. Mirrors #6's lake-layer default question. | spec :1135; #6 | DECIDE (small) |
| 5-G13 | G-13 (:1138) | `AdvanceTimeDimension`: ratify the INTENT surface (2 user + 18 derived, V132+ADR-0023) over the stale 4-field JSON; `advance_to` consolidation | **DECIDE (catalog/intent).** The entry already specs the intent surface (2 user + 18 derived params) consolidating `advance_mode`+`requested_asof_expr` into `advance_to`. Confirmation = V153 re-seeds the intent surface over the stale 4-field JSON row. **Recommend ratify** — it's the ADR-0023 minimal-surface treatment of a time-dimension Blueprint; the work is just re-seeding. | spec :1138-1140; V132; ADR 0023 | DECIDE (catalog) |
| 5-G14 | G-14 (:1141) / Derive GUESS (:1091-1099) | `Derive` (V102): keep as the 40th atomic Blueprint or fold into the `add-column` op / addenda | **DECIDE (catalog).** `Derive` is a thin 1:1 wrapper of the `add-column` op (V102:33-45), excluded from the 41 because the coverage basis is the decomposition doc's 39 survivors. Options: (A) keep as the 40th atomic Blueprint (like GenericFilter=filter-rows) — symmetric, user-facing; (B) fold into the op/addenda. **Recommend A (keep as 40th)** — it's user-facing intent ("derive a column") and #6 §D.6 already references its `derived_columns[].expression` as a mnemonic-bearing param; if kept, `expression` is `user`-tier + `expression-builder` + `accepts_mnemonic:yes`. | spec :1091-1099, :1141; #6 §D.6; OP rule 1 | DECIDE (catalog) |
| 5-G15 | G-15 (:1142) | Merge candidates: `AggregateMaterialization ≡ GenericAggregate`; `BulkBackfill` vs deprecating `BackfillAndReplay` | **DECIDE (catalog).** Two merge questions the spec explicitly leaves to the operator (not encoded). **Recommend:** merge `AggregateMaterialization` into `GenericAggregate` (same op-list / intent); keep `BulkBackfill`, drop the deprecating `BackfillAndReplay` (OP-VOCAB:87 marks it deprecating). These are catalog-shape calls, not mechanical. | spec :1142-1143; OP-VOCAB:87 | DECIDE (catalog) |

> Inline-only catalog GUESSes (the ~40 per-Blueprint `> GUESS:` rows on individual params, e.g. `incremental_field` →
> `column-picker`, `separator` system-derived, `late_data_policy` modeled as `select`, etc., spanning spec :110–:1066)
> are **all DERIVED** from 5-G01 (the tiering heuristic) + 5-G09 (the #4 construct bindings): once the heuristic and the
> construct list are ratified, each per-param tier + construct hint is a mechanical application. They are NOT enumerated
> as separate rows here because they collapse to those two parent decisions — this is exactly what G-1 "one ruling
> unblocks ~300 param calls" means.

---

### #6 — SPEC-calcite-sql-model.md (OPEN WORKLIST — 4 OPEN G-items)

> G-1/G-2/G-3/G-5/G-8 are RESOLVED/COLLAPSED/REWORDED in-spec. The 4 genuinely OPEN items are G-1-residual (function
> list), G-4 (op JSON keys), G-6 (Blueprint keys + param names), G-7 (mnemonic→var slug rule).

| ID | Location | What it asks | Proposed resolution | Grounding | Class |
|---|---|---|---|---|---|
| 6-G01 | G-1 residual (§A.2, :409) | The **initial registered-function list** for the Calcite parser (parser config itself is no longer a guess — reuse `ExpressionValidationService.java:99-113`) | **DEFAULT (with a build-task list).** Reuse the existing parser config (`ExpressionValidationService.java:99-113`); the only residual is enumerating the initial registered SQL-function list. **Recommend** seed it from the functions the existing `ExpressionValidationService` already registers + the ANSI/Spark-SQL standard set, and treat additions as an extensible registry. Not an operator decision — a build enumeration grounded in the existing validator. | spec :409-410; `ExpressionValidationService.java:99-113` | DEFAULT |
| 6-G04 | G-4 (§B.1, :415) | Op JSON key names (`steps`/`name`/`sql`/`materialize`) + reserved `input` name reconciled with #1 §A.1 | **DEFAULT.** Reconcile to #1 §A.1's op-entry `config` shape — keys `steps`/`name`/`sql`/`materialize`, reserved input port name `input`. #1 §A.1 is LOCKED (1-01), so these keys are pinned by reference, not invented. | spec :219, :415, §B.1; #1 §A.1 (1-01) | DEFAULT |
| 6-G06 | G-6 (§B/§C, :418) | Blueprint keys + param names (`SqlModel`/`SourceSQL`; `source_query`/`connector_id`) | **DECIDE (small — naming ratification).** Blueprint keys `SqlModel` / `SourceSQL` and param names `source_query` / `connector_id` (vs `connector_instance_id` to match the SINK convention, flagged at #5 :1062). **RESOLVED 2026-06-16:** keys `SqlModel`/`SourceSQL`; connector param = **`connector_instance_id`** (operator chose the SINK/ServiceInstance convention — matches live V10/V69/V71 + #5 G-11 + #6 §C.1; supersedes the earlier `connector_id` lean). This is the value V153 seeds. Couples to 5-G11. | spec :256, :270, :418; #5 :1060-1062 (5-G11) | DECIDE (small) |
| 6-G07 | G-7 (§D.4, :419) | The mnemonic→var `<slug>` derivation rule (e.g. `PBD-1` → `pbd_1`) + dedup when the same mnemonic repeats | **DEFAULT.** Adopt the proposed rule: lowercase the mnemonic, replace non-alphanumerics with `_` (`PBD-1` → `pbd_1`), prefix `pulse_` per ADR 0024 (`{{ var('pulse_<slug>') }}`); dedup by emitting one var per distinct mnemonic. Grounded in ADR 0024:26's lowering contract — it's a deterministic naming rule, not a product choice. | spec :349-352, :419; ADR 0024:26 | DEFAULT |
| 6-G08 | G-8 (§A/§E, :420) | Validator service name/package + #1 rule-27 (`sql-model`) call site; `SourceSQL` source-prepare branch | **RESOLVED 2026-06-16.** Service `com.pulse.expression.service.CalciteSqlModelValidator` (co-located with `ExpressionValidationService`, whose named "Phase 2" CALCITE-PHASE-2 extends — grounded against the live file, supersedes the earlier `com.pulse.codegen.sql` guess); rule-27 call site = the `sql-model` resolution in `SchemaPropagationService`. The `SourceSQL` output-port derivation is a NEW `source-prepare` branch inside rule 24 / `read-source` (`SchemaPropagationService.java:861-883`) that prepares `source_query` against the bound JDBC source and maps JDBC→PULSE types — it does NOT call Calcite (LOCKED 2026-06-16). Mostly resolved; service name is the only naming residual (DEFAULT — follows the package convention). | spec :82, :388, :420-424; `SchemaPropagationService.java:861-883` | DEFAULT |

---

### ADR-0024 — sql-authoring-sourcesql-sqlmodel-calcite-mnemonics

> **Status: accepted 2026-06-16 (operator + orchestrator).** No residual open items of its own — the ADR is LOCKED, and
> its content is what specs #5/#6 implement. The "open" work it names lives in #6's OPEN WORKLIST (rows above), not in
> the ADR. Nothing to resolve here.

| ID | Location | What it asks | Proposed resolution | Grounding | Class |
|---|---|---|---|---|---|
| A24-01 | ADR-0024 header | Any residual ADR decision? | None — accepted/locked; rejected alternatives recorded (single CTE statement rejected in favor of discrete temp-materializable steps, :41). All implementation detail delegated to #6. | ADR-0024:3, :41 | DEFAULT (no action) |

---

## COUNTS SUMMARY

**Named-priority resolutions:** §1 tool-name convention (snake_case, 0 hyphens; 14 names: 7 hyphen-fixes + 4 renames/new
reads + 3 new) · §2 six `composition.*` strings (already correct convention).

**Per-spec ledger rows:** 66 total.

| Spec | Rows | DEFAULT | DECIDE | DERIVED |
|---|---|---|---|---|
| #1 schema-op-engine | 4 | 4 | 0 | 0 |
| #2 codegen-compiler | 4 | 4 | 0 | 0 |
| #3 ui-composition §7.16 | 18 | 7 | 9 | 2 |
| #3 fragment items | 8 | 4 | 2 | 2 |
| #4 construct-library W-1…W-13 | 13 | 12 | 0 | 1 |
| #5 blueprint-catalog G-1…G-15 | 15 | 3 | 6 | 6 |
| #6 calcite-sql-model | 5 | 4 | 1 | 0 |
| ADR-0024 | 1 | 1 | 0 | 0 |
| **TOTAL** | **68** | **39** | **18** | **11** |

> Rows 3-07, 3-09, 3-10, 3-12, 3-17 each carry a split class (a DEFAULT/DERIVED part + a DECIDE part). They are counted
> by their **operator-facing** class above (the part the operator must answer): 3-07 → DECIDE (deprecation), 3-09 →
> DECIDE (fold), 3-10 → DECIDE (endpoint), 3-12 → DEFAULT (recommendation given), 3-17 → DECIDE (voice). The DERIVED
> sub-parts (tool/command/event NAMES) are resolved by §1/§2 and not double-counted.

**TOTAL items: 68 · DEFAULT: 39 · DECIDE: 18 · DERIVED: 11.**

---

## THE 18 DECIDE ITEMS (operator queue)

1. **3-01** — Build the routed 7-stage agent model now, or keep the single OpenRouter loop with phase-gated prompts? (rec: single loop)
2. **3-03** — Recursion bound: keep `MAX_TOOL_ROUNDS=30` or raise to 40? (rec: 40)
3. **3-07** — Deprecation path for direct-write mutators (`wire_ports`/`configure_step_params`/`remove_step`): delete vs route-to-queue? (rec: route-to-queue)
4. **3-09** — Do `setName`/`setPipelineSetting` fold into `pipeline.update`, or become their own `composition.*` types? (rec: fold)
5. **3-10** — Add a session-scoped `…/plans/{planId}/decision` endpoint, or reuse tenant-scoped approve/cancel? (rec: new decision endpoint)
6. **3-13** — Undo = inverse plan (new commands) vs restore snapshot? (rec: restore snapshot)
7. **3-14** — Concurrency while a plan is staged: BLOCKED vs REBASED? (rec: BLOCKED)
8. **3-15** — `validate_plan`: ship interim checks now, or block on the deterministic Builder? (rec: interim now)
9. **3-17** — DE-voice review of the best-practice / use-case / pitfall prose (DQ outcome defaults, history-strategy tree, one-schedule rule, late-data policy, dedupe-vs-SCD2). (operator voice; biggest content bucket)
10. **3-18** — Universal Plan→Apply gate on domain/SOR/connector/dataset creation, or keep direct-write? (rec: universal)
11. **3-F3** — Per-stage tool allow-lists — confirm the provision-tier boundary. (rec: derive from tier)
12. **3-F4** — May `add_blueprint_instance` carry optional initial `params`, or always a separate `set_params`? (rec: always split)
13. **5-G01** — Ratify the §0.1 tiering heuristic (one ruling unblocks ~300 param calls + all 5-G02/03/04/06/08 DERIVED rows). (rec: ratify as written) — **highest leverage**
14. **5-G05** — `SchemaDriftDetection.expected_columns`: `user` (Customer asserts baseline) vs system-derived? (rec: user)
15. **5-G12** — `SqlModel.lake_layer` default: silver vs gold? (rec: silver)
16. **5-G13** — `AdvanceTimeDimension`: ratify the 2-user/18-derived intent surface + `advance_to` consolidation over the stale 4-field JSON? (rec: ratify)
17. **5-G14** — `Derive` (V102): keep as the 40th atomic Blueprint, or fold into the `add-column` op? (rec: keep as 40th)
18. **5-G15** — Merge `AggregateMaterialization`≡`GenericAggregate`? Keep `BulkBackfill` / drop `BackfillAndReplay`? (rec: merge + keep BulkBackfill)
19. **6-G06** — `SqlModel`/`SourceSQL` Blueprint keys + `connector_id` vs `connector_instance_id` param name (touches the V153 seed). (rec: SqlModel/SourceSQL + reconcile connector_id to SINK convention)

> (19 DECIDE rows are listed; the COUNTS table records 18 because 5-G12 and 6-G06 are both "small" naming/default calls
> — if the operator treats the silver-default 5-G12 as a mechanical recommendation-accept, the operator-facing DECIDE
> count is 17. The honest range is **17–19 genuine operator decisions**, dominated by 5-G01 which gates the largest
> block.)
