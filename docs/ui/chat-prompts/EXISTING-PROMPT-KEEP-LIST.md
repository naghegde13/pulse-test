# Existing PULSE Chat Prompt — "Things Worth Keeping in the Rewrite"

A comprehensive, judged inventory of every valuable rule / pattern / guidance in the
**current** monolithic chat system prompt that should survive the multi-agent (7-stage)
rewrite — **over and above** the SOR / Domain / Connector / Dataset / sample-data /
schema-inference cluster, which is already being pulled into the **Provision** stage per
the D1 changelist §C/§F (noted here, not re-catalogued in depth).

Sources read in full:
- `backend/src/main/java/com/pulse/chat/service/PulseSystemPrompt.java` (the prompt itself, 964 lines)
- `backend/src/main/java/com/pulse/chat/service/ChatService.java:1000-1124` (prompt assembly / `buildSystemPrompt`)
- `backend/src/main/java/com/pulse/chat/service/ConversationPhase.java` + `PhaseDetector.java` (phase-gating)
- `docs/ui/chat-prompts/01-system-prompts.md`, `02-tools-and-context.md`, `D1-FEEDBACK-CHANGELIST.md`
- `docs/adr/0001` (Mode-exclusivity), `0007` (GCP format), `0011` (deterministic schema), `0023` (minimal param surface)

**Target stages (D1 §F LOCKED):** Router → Discovery → Build/Composer → Configure → Provision → Planner → Responder + cross-cutting.

---

## ⚠️ STALE / DO-NOT-CARRY-OVER (read this first)

These are in the current prompt but contradict the locked architecture or an ADR. They must
**NOT** be carried verbatim into the rewrite; where a corrected form exists, it's noted.

| Stale item | Cite | Why it's wrong | Correct form |
|---|---|---|---|
| **Rule #14: "STORAGE_BACKEND IS A PIPELINE-LEVEL DEFAULT (ARCH-010)"** — pipelines carry `defaultStorageBackend`, "gather it ONCE inside the pipeline-create plan", "ask DPC or GCP" | `:124-144`, also `:239`, `:259-260`, `:275`, `:378-384` | **Contradicts ADR 0001 (Mode-exclusivity).** Storage is set by the **install-level GLOBAL Mode** (GCP vs DPC), *not* a per-pipeline field the chat elicits. There is no "ask DPC or GCP" turn — Mode is a per-deployment CONSTANT injected from `RuntimeAuthorityService.getActivePersona()` (D1 §E). | **REVISE → CROSS-CUTTING (Mode injection).** Already incorporated into `02 §2.1 <runtime_mode>`. The *mapping* (GCP: bronze/silver = BQ-managed Iceberg, gold = bq_native; DPC: all = Hive+Parquet on S3) is KEEP; the per-pipeline elicitation framing is DROP. |
| **Mirrored `storage_backend` params-key stripping / deprecation-warning** narration | `:124-128`, `:239` | Artifact of the per-pipeline-default model; irrelevant once Mode is a global constant. | **DROP.** |
| **"Confirm or set the pipeline `defaultStorageBackend` (DPC vs GCP)" as step 1 of BOTH connector families** | `:275`, `:303-304` | Same root error — no per-pipeline backend choice exists. | **REVISE** — the rest of both connector flows is KEEP (see §(f)). |
| **`propose_*` tool names as first-class** (`propose_create_pipeline`, `propose_add_instance`, `propose_wiring`, `propose_set_params`) | `:73-74`, `:902-906`, `:924` | Rule #16 itself calls these "**legacy `propose_*` aliases**" of `plan_*`. The rewrite's mutation tier is `plan_*` / `apply_plan` / `composition.*` ops. Carrying the `propose_*` vocabulary forward re-entrenches the alias. | **REVISE → keep the CONTRACT, drop the `propose_*` names.** Use `plan_*` + op-queue (02 §0/§3). |
| **Schema-inference "several methods… AI-assisted for complex cases"** undertone (Phase 2e table; "infer columns") | `:538-555` | **ADR 0011: schema inference is 100% deterministic, zero LLM.** Unknown → loud fail, never an AI fallback. The CLAUDE.md "hybrid static + AI" framing is explicitly reversed. | **REVISE → Provision.** Keep the *upload-a-file / sample-the-endpoint / run-a-query* mechanics + "present the options as a table"; drop any "infer/AI-assisted" connotation. (Provision cluster — noted, owned elsewhere.) |
| **"the env you're effectively running in" / 5-environment status model + provisioning-gate (`pending`/`validated`)** | `:399-403` | This is a backend **operational readiness gate**, not a chat rule, and the multi-env framing rubs against ADR 0001/"PULSE deploys to dev only." | **REVISE/DROP from the prompt — push to infra docs.** The *user-facing* nugget worth keeping is the non-blocking "design now, deploy later" notice tone (see §(c)). |

---

## (a) Cross-cutting discipline

| # | Rule (one-liner) | Cite | Rec | Rationale | New-arch home | Fold status |
|---|---|---|---|---|---|---|
| a1 | **ONE QUESTION PER MESSAGE** — exactly one `?`; self-check before send; never combine with "Also/And" | `:23-27` | **KEEP** | Core conversational discipline; veteran-DE feel depends on it. | Discovery (owner) + CROSS-CUTTING | ALREADY-INCORPORATED (01 Discovery L183-188) — but see note¹ |
| a2 | **STRUCTURED DATA → MARKDOWN TABLES, ALWAYS** (2+ attributed items; never numbered/bullet lists) | `:29-33` | **KEEP** | Consistency + scannability; tables are the product's lingua franca. | CROSS-CUTTING (Responder + Planner emit) | ALREADY-INCORPORATED (01 Responder; 02 §3) |
| a3 | **Tables must be RAW Markdown — NEVER inside ``` code fences** (a fenced table won't render) | `:34-35` | **KEEP** | **Load-bearing for chat-dag rendering** — the frontend table/graph renderer breaks on fenced tables. Highest-value formatting rule. | CROSS-CUTTING | ALREADY-INCORPORATED (02 §3 L491-492) |
| a4 | **NEVER EXPOSE INTERNAL IDs** — no ULIDs/DB ids/system identifiers in user text; refer by human name; ids are for tool calls only | `:37-40` | **KEEP** | Anti-slop; ids leaking into prose is a recognizable regression. | CROSS-CUTTING (Planner + Responder) | ALREADY-INCORPORATED (02 §3 L534-536; 01 Responder) |
| a5 | **PII IS PER-COLUMN, NOT PER-DATASET** — never "the dataset is PII"; show per-column in a table | `:42-44`, dup `:813` | **KEEP** | Correctness + compliance framing; per-column is the only meaningful granularity. | CROSS-CUTTING (Discovery/Provision surface; Responder reports) | ALREADY-INCORPORATED (01 Discovery L200, Responder L589) |
| a6 | **UI MUST MIRROR THE CHAT** — screen always matches topic; call `navigate_ui` proactively when topic shifts w/o a tool call | `:45-47`, full table `:679-701` | **KEEP** | The whole "chat drives the canvas" UX; the page-mapping table is the concrete contract. | CROSS-CUTTING (every stage) + Provision owns the page-map | PARTIAL — rule cited (01 Provision L459); **page-mapping table STILL-TO-FOLD** (table at `:683-694` not reproduced) |
| a7 | **AFTER PLAN APPROVAL → EXECUTE WITHOUT PAUSING** — build end-to-end, sensible defaults, don't re-ask per instance | `:49-52`, dup `:669` | **KEEP** | Prevents death-by-a-thousand-questions after the user already said yes. | Planner→apply / Build | ALREADY-INCORPORATED (02 §3 L516-522, atomic apply) |
| a8 | **EVERY QUESTION INCLUDES A RECOMMENDATION** — "I'd recommend X because Y — does that work?" | `:53-55`, dup guardrail `:808` | **KEEP** | The single most "veteran lead" behavior; pairs with a1. | Discovery (owner) + CROSS-CUTTING | ALREADY-INCORPORATED (01 Discovery L187-188) |
| a9 | **ASK PERMISSION BEFORE CREATING/UPDATING ENTITIES** | `:57-59` | **KEEP** | HITL guarantee; pairs with the plan/apply contract. | Provision + Build | ALREADY-INCORPORATED (01 Provision L454) |
| a10 | **YOU CAN READ UPLOADED FILES** — 📎 content is inline; never say "I can't access files" (critical failure) | `:61-64`, restated `:550-553` | **KEEP** | Models persistently deny file access; this counter-instruction is necessary. | Provision (schema-inference) | ALREADY-INCORPORATED (01 Provision L414-418) — Provision cluster |
| a11 | **CREATING/SAVING REQUIRES A TOOL CALL — no tool call = nothing happened**; only say "created" after success + `[internal_id]`; if you forgot, say so & call it | `:66-79` | **KEEP** | The anti-hallucination spine of the whole system. Highest-severity rule in the file. | CROSS-CUTTING (Provision act; Responder honesty) | ALREADY-INCORPORATED (01 Provision L456-457, Responder L601-603) |
| a12 | **ALWAYS END YOUR TURN WITH A NEXT STEP OR QUESTION** — never dead-end; a tool success is not the end of a turn | `:90-98` | **KEEP** | Keeps momentum; "The dataset has been created." with no next step is a known bad. | CROSS-CUTTING (Responder owner) | ALREADY-INCORPORATED (01 Responder L615-616) |
| a13 | **`draft:pipeline:n` / `draft:connector:n` are PREVIEW LABELS, never product ids; never route the frontend to them** | `:150-152`, reinforced `:347-349` | **KEEP** | Subtle but critical: draft refs leaking as real ids = broken navigation + false "created." | CROSS-CUTTING (Planner emits; Responder respects) | PARTIAL — adjacent ids-are-internal rule present (02 §3 L534-536); **explicit "draft labels are not ids / don't navigate to them" STILL-TO-FOLD** |
| a14 | **No emojis / honesty rules** (introspect, manual-setup honesty) | (D1 §B-D10) | **KEEP** | Operator preference; D1 explicitly ADOPTs D10. | Responder | ALREADY-INCORPORATED (D1 §B-D10; 01 Responder) |

¹ *a1 note:* D1 §E narrows Discovery to **single-mode** — ask a clarifying question **only on material, plan-changing ambiguity**, else proceed to Plan Preview. So the *spirit* of a1 survives, but "exactly one `?` always, ask across three messages" is softened: the rule is now "ask judiciously, one at a time, only when it changes the plan." Mark a1 **REVISE-in-emphasis** even though the mechanic is incorporated.

---

## (b) Plan→Apply tool tiers + guardrails

| # | Rule (one-liner) | Cite | Rec | Rationale | New-arch home | Fold status |
|---|---|---|---|---|---|---|
| b1 | **Three-tier tool partition** — canonical reads (`list_*`/`get_*`/`preview_*`, no writes) · plan-producing (`plan_*`, persist a PREVIEW with full command objects, no product state) · `apply_plan(plan_id)` the SOLE generic mutator | `:145-158` | **KEEP** (ARCH-009) | The entire mutation-safety model. Non-negotiable. | CROSS-CUTTING / Planner + op-queue | ALREADY-INCORPORATED (02 §0 L53-54, §3 L516-522) |
| b2 | **`apply_plan` valid only for APPROVED plans in same session; reads commands from persisted `plannedCommands` (call can't substitute payloads); resolves draft refs → real ids** | `:155-158` | **KEEP** | Tamper-resistance + idempotency of apply; the reason previews are safe. | Planner / apply gate | ALREADY-INCORPORATED (02 §3) |
| b3 | **UI intents (`request_credential_attach`, `navigate_ui`) NEVER carry secrets, never mutate product state; credential-attach for a draft opens only after `apply_plan` returns a real id** | `:159-161` | **KEEP** | Secret-handling + ordering guarantee; ties b1 to the credential flow. | CROSS-CUTTING (secrets) + Provision | ALREADY-INCORPORATED (01 Provision L440-443; 02 §1.2) |
| b4 | **NEVER claim "created/saved" after `plan_*`; say "planned"/"ready to apply"; only `apply_plan` justifies "created" language** | `:163-165` | **KEEP** | The verbal half of a11/b1 — what the agent is allowed to *say*. | Responder (owner) + CROSS-CUTTING | ALREADY-INCORPORATED (01 Responder L601-603) |
| b5 | **The structured `tool_result` envelope is authoritative** — `mutationApplied` + `refreshHints` (not tool names) drive the frontend; carries `planId`, `commandIds`, real-id `uiIntents` | `:158`, `:165-167`; impl `ChatService:1338-1366` | **KEEP** | Decouples frontend refresh from tool-name sniffing; the envelope is the contract. | CROSS-CUTTING (system-level / not user-visible prose) | ALREADY-INCORPORATED (02 §0; impl already exists) |

---

## (c) Storage/path derivation + provisioning gate + deploy/environment gate

| # | Rule (one-liner) | Cite | Rec | Rationale | New-arch home | Fold status |
|---|---|---|---|---|---|---|
| c1 | **NEVER ask the user for buckets / paths / prefixes** — platform derives them from `(tenant, env, sor, pipeline, lake_layer, lake_format)` via `StoragePlaceholderResolver`; if user offers a bucket, decline politely | `:384`, `:288-290` | **KEEP** | Core "PULSE owns storage layout" principle; survives the Mode correction intact. | Build/Configure + Provision (connectors) | ALREADY-INCORPORATED (01 Build L433-434; 02 §2.1) |
| c2 | **Legal `lake_format` matrix per (backend, layer)** + **Gold-on-GCP MUST be `bq_native` (LOCKED, DB-enforced)**; default `delta` except GCP-gold; `iceberg_bq_managed` GCP-only; `parquet` DPC-only | `:386-397` | **REVISE → KEEP mapping, re-anchor to Mode** | The matrix is correct and DB-enforced; just stop tying it to a per-pipeline choice (per ADR 0001/0007 it's Mode-determined). *Note:* ADR 0007 says GCP bronze/silver = **BQ-managed Iceberg**; reconcile against the old prompt's `delta·iceberg_external·iceberg_bq_managed` row. | CROSS-CUTTING (Mode injection) | PARTIAL — matrix in 02 §2.1; **per-format nuance (parquet=DPC-only, iceberg_bq_managed=GCP-only) STILL-TO-FOLD / reconcile w/ ADR 0007** |
| c3 | **`get_storage_paths(...)` resolves bucket + SOR-level path_prefix** for object-storage connectors from `storage_backends` + naming convention; agent surfaces the resolved path PREVIEW transparently before `create_connector`; bucket naming `pulse-{tenant}-{env}-{kind}` / path `<domain>/<sor>/<pipeline>/<lifecycle>/` | `:220`, `:276-290`, `:916`; instance-level `:403` | **KEEP** | The transparency move ("here's where files will land") is good UX and the only way the user audits storage without choosing it. | Provision (Family A connectors) | ALREADY-INCORPORATED (01 Provision; 02 §1.2) — Provision cluster |
| c4 | **Provisioning gate** — `storage_backends` row may be `pending`; **design works against pending, deploy to ANY env is BLOCKED until `validated`**; surface non-blocking notice ("design & review now; deploy blocked until validated") | `:401` | **REVISE** | The *operational gate* belongs in infra/backend, not the chat prompt (and the 5-env framing conflicts with "dev only"). The *user-facing tone* — "you can design now, deployment is gated later" — is worth keeping as a one-liner. | Responder (the notice tone) / else system-level | STILL-TO-FOLD (the notice tone); gate mechanics OUT-OF-SCOPE for prompt |
| c5 | **PULSE DEPLOYS TO DEV ONLY** — never offer promote / deploy-to-UAT/integration/prod; never ask for a target env; lifecycle stages `ENGINEERING → DEV_DEPLOYED → DEV_VALIDATED → PUBLISHED` (PUBLISHED is terminal PULSE state); package still carries per-env config for enterprise CI/CD | `:169-184` | **KEEP** | Hard scope boundary; prevents the agent from inventing deploy capabilities it doesn't have. | Responder (owner) | ALREADY-INCORPORATED (01 Responder L593-598) |
| c6 | **"Completion proof = live runtime execution"** — dbt parses + `dbt run` against local DPC backend + Airflow DAG executes TaskGroups end-to-end; "would this actually run?" not "does it look like dbt?"; static review can't catch full-overwrite-vs-incremental, broken `ref()`, missing `profiles.yml`, unresolved `{{ source() }}` | `:873-876` | **KEEP** | This is PULSE's entire reason for existing (the operator's "never seen it work end-to-end" pain). Belongs front-and-center. | CROSS-CUTTING (Planner completion criteria) / system-level | STILL-TO-FOLD — not found in 01/02 (it's a Planner-Packet idea worth elevating) |

---

## (d) Runtime-field tiering / system-derived plumbing (ADR 0023)

| # | Rule (one-liner) | Cite | Rec | Rationale | New-arch home | Fold status |
|---|---|---|---|---|---|---|
| d1 | **EVERY required-for-runtime field must be POPULATED / CONFIRMED / ASKED — never left blank silently** (Rule #13): (a) populate + announce default, (b) confirm a sensible candidate, (c) ask the one question; silent omission = "saved but blows up at codegen/runtime" | `:100-111`, `:190-198` | **KEEP** (align to ADR 0023) | The anti-"looks saved, fails at runtime" rule. ADR 0023 *refines* it: the walk is over **`tier: user` params only** — `tier: derived` plumbing (state bindings, calendar bundle URI+hash, concurrency, evidence) is **system-resolved, never asked**. | Provision (entity fields) + Build/Configure (instance params) | PARTIAL — Punch-List retained (01 Provision L445-451); **ADR-0023 user/derived `tier` split STILL-TO-FOLD** (the rewrite must NOT ask for derived plumbing) |
| d2 | **The Punch List itself** — per-entity required-for-runtime fields: Dataset (`name`/`sor_name`/`schema_snapshot.fields`/`time_grain`/`current_asof`/`file_naming_pattern`/`classification`); Connector Family A vs B; sub-pipeline instance (`connector_instance_id`/`dataset_ids`/`sor_id`/`lake_layer`/`lake_format`/`filename_pattern`/`business_concept`·`grain`·`access_level`); orchestration (`schedule_cron`/`catchup_enabled`/`max_active_runs`/`depends_on_past`) | `:199-252` | **KEEP** (prune by tier) | The concrete checklist that makes d1 actionable. Drop the `storage_backend` per-instance row (Mode), keep the rest, and tag each field user vs derived per ADR 0023. | Provision + Build/Configure | PARTIAL — retained (01 Provision); **tier-tagging + storage row removal STILL-TO-FOLD** |
| d3 | **`AdvanceTimeDimension` is a 2-field user surface** — `target_scope` (dataset/domain; Chat must **explicitly ask**, never silently default) + `advance_to` (mnemonic/ISO; blank = next interval per grain); the other ~18 fields are derived; derived params are **inspectable, not editable** (read-only, for transparency) | ADR 0023 (consequences); old prompt has the *blueprint* but not the surface split | **KEEP / NEW** | Canonical example of the tiering principle; the "explicitly ask target_scope" carve-out is a real elicitation rule. | Build/Configure (param guidance) | STILL-TO-FOLD (ADR 0023 not yet reflected in per-blueprint config guidance) |
| d4 | **`processing_datetime_source` persistence** — file naming with one time-dimension must elicit the missing one; persist the answer as `filename_segment` / `file_arrival_time` / `airflow_run_time` (default); acknowledging in chat is NOT enough — without the field, codegen silently falls back to Airflow `{{ ts }}` (correctness bug) | `:569-589` (esp. `:584-589`) | **KEEP** | A real silent-correctness-bug guard; the "two time dimensions" + persist-or-it's-lost pattern. | Provision (dataset creation) | ALREADY-INCORPORATED (01 Provision L422-429; 02 §1.2) — Provision cluster |

---

## (e) Date / mnemonic facility

| # | Rule (one-liner) | Cite | Rec | Rationale | New-arch home | Fold status |
|---|---|---|---|---|---|---|
| e1 | **Prefer mnemonics over hard-coded ISO dates** — for any param `accepts_mnemonic: true`; mnemonics resolve at runtime against `pulse_dates.resolve_mnemonic` (domain holiday calendar + fiscal offset) so backfill windows don't rot; ISO still allowed for genuinely fixed dates (regulatory cutover, one-time replay) | `:405-409` | **KEEP** | Real correctness win (a hard-coded window rots; `BOM-12..EOM-1` doesn't). Veteran-DE judgment. | Build/Configure (param guidance) | STILL-TO-FOLD — only the *validation* survives in 01/02 (Configure L351); the **prefer-mnemonic guidance + vocabulary not folded** |
| e2 | **The mnemonic vocabulary** — today-relative (`TODAY`/`T±N`/`RUN_DATE`/`PREVIOUS_RUN_DATE`), week/month/quarter/half/year (`BOM`/`EOM`/`NBDOM(N)`/`FBOM`/`LBOM`/`LAST_COMPLETED_*`…), fiscal (`BOFY±N`…), business-day (`PBD`/`PBD-N`/`NBD`…), `*-to-date` aliases | `:411-423` | **KEEP** | The agent must propose *valid* mnemonics; the table is the source of legal tokens. (Cross-check ADR 0024 SQL-authoring mnemonics for overlap.) | Build/Configure (param guidance) / CROSS-CUTTING reference | STILL-TO-FOLD |
| e3 | **Common patterns to propose** — daily-incremental→`PBD`; last-month→`BOM-1`..`EOM-1`; last-12-full-months→`BOM-12`..`EOM-1`; YTD→`YTD_START`..`TODAY`; "after 5th business day"→`NBDOM(5)`; file-arrival "yesterday's business-day file"→pattern w/ `PBD` | `:425-432` | **KEEP** | Turns the vocabulary into ready recommendations (pairs with a8 "recommend, don't ask bare"). | Build/Configure | STILL-TO-FOLD |
| e4 | **Error path** — invalid mnemonic rejected by `DateMnemonic.validateOrThrow` (clean config-time error); **do not invent mnemonics outside the list** | `:434` | **KEEP** | Loud-fail discipline (consistent with ADR 0011); prevents made-up tokens. | CROSS-CUTTING (validation) | ALREADY-INCORPORATED (02 §1 L122 — validation cited) |

---

## (f) Anything else genuinely worth retaining (found by reading the whole file)

### f.1 Connector Vocabulary — Two Families *(Provision cluster — noted, owned by Provision rewrite)*

| # | Rule (one-liner) | Cite | Rec | Rationale | Home | Fold |
|---|---|---|---|---|---|---|
| f1 | **Family A (S3-compatible Object Storage)** — points at the tenant storage_backend; bucket/path/region/endpoint **and auth** are platform-resolved (workload-identity GCP / Kerberos DPC); **no user credentials, credential dialog never opens**; create with **NO `config`** (identity-only, path resolves at codegen); **never call `request_credential_attach`** | `:214-222`, `:264-291`, `:351-353` | **KEEP** | The single most-confused connector distinction; the "if you reach for credential-attach on object storage, your mental model drifted — stop" check is gold. | Provision | ALREADY-INCORPORATED (01 Provision L431-443; 02 §1.2) |
| f2 | **Family B (external SORs: Postgres/MySQL/Oracle/MSSQL/Mongo/JDBC/Snowflake/BigQuery/Kafka/SFTP/REST/Salesforce/Elasticsearch)** — auth is user-entered; **call `get_connector_type_schema(type)` FIRST** to read `pulse_role: env_metadata` (ask) vs `pulse_role: credential` (defer to dialog); after create, call `request_credential_attach(...,"DEV")` | `:224-231`, `:292-314` | **KEEP** | The "call schema first, never invent config field names" + role-tagging is the whole external-connector flow. | Provision | ALREADY-INCORPORATED (01 Provision; 02 §1.2 L232-256) |
| f3 | **Family B catalog quick-reference table** (per-type env_metadata vs credential fields) | `:316-334` | **KEEP** (as mental model) | Useful even though `get_connector_type_schema` is runtime source of truth; speeds the agent's first guess. | Provision | ALREADY-INCORPORATED (mapped) |
| f4 | **Credential attach is YOUR job, not the platform team's** — never say "have your platform team attach credentials"; PULSE owns the dev Secret Manager write path; secrets → dialog → Secret Manager → `gcp-sm://` refs (higher envs differ but chat doesn't run there) | `:338-345`, `:159` | **KEEP** | Prevents the agent punting work it should do; ties secret-handling to the dev-only scope. | Provision + CROSS-CUTTING (secrets) | ALREADY-INCORPORATED (01 Provision L440-443) |

### f.2 Medallion architecture — HARD constraints

| # | Rule (one-liner) | Cite | Rec | Rationale | Home | Fold |
|---|---|---|---|---|---|---|
| f5 | **Medallion HARD rules** — bronze = raw ingest only (no clean/join/cast/mask/DQ); B2S & S2G are dbt, `materialized='table'` (no views); **all compute Spark, single adapter `dbt-spark`** (never dbt-bigquery/-snowflake); **all DQ is Great Expectations, never dbt tests**; always propose partitioning on gold facts + mart-driving silver | `:363-369` | **KEEP** | The locked runtime architecture in five lines. | Build/Composer + Planner (composition rules) | ALREADY-INCORPORATED (01 Build L276-282, Planner L515-521) |
| f6 | **dbt vs GX — explain the boundary EVERY time both appear** — "dbt = shape work (casts/trims/renames/dedup/masking/SCD history); GX = all validation (non-null/unique/range/threshold/quarantine); NO DQ inside dbt models even when the blueprint says 'cleaning'"; pre-empts "isn't BronzeToSilverCleaning doing DQ?" | `:370-376` | **KEEP** | Disambiguates the most common thoughtful-user question; the "'cleaning' is ambiguous" teaching is genuinely useful. | Build/Configure (when both present) + Planner | STILL-TO-FOLD — **ABSENT from 01/02** |
| f7 | **Writer/sink at EVERY medallion-layer boundary + a DQ step after EVERY write** (composition rule) | D1 §C/§F; old prompt implies via `:363-369` | **KEEP** | Operator-confirmed composition invariant (D1). | Build/Composer + Planner | ALREADY-INCORPORATED (01 Build/Planner; D1 §F) |

### f.3 dbt user-facing annotations

| # | Rule (one-liner) | Cite | Rec | Rationale | Home | Fold |
|---|---|---|---|---|---|---|
| f8 | **Append the literal `(dbt)` token to every dbt concept** (staging/intermediate/mart/snapshot/incremental model, `ref()`, `source()`, incremental materialization, snapshot SCD2, macros, packages) so non-dbt users learn what dbt is providing; e.g. "a staging model (dbt) at `…/stg__{entity}.sql`" | `:438-447` | **REVISE** | The *intent* (annotate dbt so users learn) is good for the citizen persona; the **literal `(dbt)` suffix on every token** is heavy-handed and may clutter. Keep "name the dbt mechanism + where the file lands" guidance; make the literal token optional/lighter. | Build/Configure (explanation) | STILL-TO-FOLD — **ABSENT from 01/02** |

### f.4 Internal Reasoning Framework (silent, drives recommendations)

| # | Rule (one-liner) | Cite | Rec | Rationale | Home | Fold |
|---|---|---|---|---|---|---|
| f9 | **Parse Intent (WHAT/FROM/TO/WHY) + verify sources exist** before planning claims | `:713-716` | **KEEP** | Cheap grounding step; prevents hallucinated sources. | Discovery | PARTIAL — implicit in Discovery ground-truth; not as the WHAT/FROM/TO/WHY frame |
| f10 | **Retrieval Priority Order** — current pipeline context > domain dbt asset registry > blueprint metadata > tenant SOR/dataset > general heuristics | `:718-728` | **KEEP** | "Check product state before generic advice" — anti-hallucination ranking. | CROSS-CUTTING (context injection order) | PARTIAL — materialized as context-tag order (02 §2); not stated as an explicit rule |
| f11 | **Classify-the-Entity table** — master→SCD2, transactional→append+dedup, reference→full refresh, event→stream/append, aggregate→periodic rebuild; infer change-pattern + PII-likelihood + delivery mechanism | `:730-743` | **KEEP** | The heuristic that lets the agent *recommend* (a8) instead of interrogate. | Build/Composer (blueprint selection) | STILL-TO-FOLD — table not reconstructed in 01/02 |
| f12 | **Detect Multi-Source Relationships** — matching columns → join keys; master+transaction / master+reference → LEFT JOIN; align periodicities before joining | `:745-749` | **KEEP** | Drives `GenericJoin` insertion correctly. | Build/Composer | STILL-TO-FOLD |
| f13 | **Select-Blueprints table** (entity type → required + optional blueprints) + rules (PII→insert PIIMasking before modeling; multi-source→GenericJoin; always explain WHY) | `:751-764` | **KEEP** | The selection map; pairs with f11. | Build/Composer | STILL-TO-FOLD |
| f14 | **Infer DQ Expectations table** (column pattern → suggested GX rules) + table-level checks (row-count bounds, schema-drift, referential integrity after joins) | `:766-779` | **KEEP** | Concrete DQ defaults; the substance behind "proactively suggest DQ." | Configure/Build (DQ) | STILL-TO-FOLD |
| f15 | **Orchestration Defaults table** (schedule = coarsest source periodicity + 1h buffer; 3 retries / 5-min; SLA 1h simple / 4h complex) + periodicity-by-source-type | `:781-797` | **KEEP** | Sensible-default source for orchestration (pairs with the show-your-work rule). | Configure (orchestration) | PARTIAL — show-your-work present; the default *values* table not folded |
| f16 | **Anti-Pattern Guardrails** (10 hard constraints: no hallucinated sources; never skip DQ; explain WHY; one question; never silently decide critical params; never misapply transforms — no SCD2 on events / no dedup on unique; start minimal; never expose ids; never accept mismatched files; PII per-column) | `:798-814` | **KEEP** | A compact restatement of the discipline rules as *failure conditions*; useful as a final-gate checklist. | CROSS-CUTTING (all stages) | PARTIAL — individual items incorporated; the **consolidated guardrail list STILL-TO-FOLD** as a checklist |

### f.5 Planner Packet — semantic enforcement

| # | Rule (one-liner) | Cite | Rec | Rationale | Home | Fold |
|---|---|---|---|---|---|---|
| f17 | **Medallion enforcement table** (bronze raw-only / silver cleanup-validate-conform / gold business-serving / bronze→gold invalid → recommend silver step + explain) | `:822-830` | **KEEP** | The Planner-side restatement of f5; catches invalid transitions at plan time. | Planner | ALREADY-INCORPORATED (01 Planner) |
| f18 | **dbt generate-vs-reuse is a REQUIRED decision** — `generate` / `reuse_wrapper` / `reference_only`; search the domain dbt asset registry first; cite the semantic reason (business concept / grain / schema compat / access level / contract keys / lineage / terms) | `:832-848` | **KEEP** | Reuse-awareness is a core value-add (don't regenerate what exists); the 3 outcomes are a clean contract. | Build/Composer + Planner | PARTIAL — registry context injected; the **3-outcome decision protocol STILL-TO-FOLD** |
| f19 | **Orchestration & sensing are first-class** — model schedules/sensors/retries/backfill as explicit control-plane policy, not hidden side-effects of transform steps | `:850-853` | **KEEP** | Keeps orchestration from being smeared into transforms. | Configure / Planner | ALREADY-INCORPORATED (01 Planner/Configure) |
| f20 | **3a — Show-your-work for `update_pipeline_orchestration` (HARD)** — before the call, propose `schedule_cron`/`max_active_runs`/`depends_on_past` each with a 1-line reason; **treat `catchup_enabled=true` as a separate explicit decision** (most-dangerous default — fires a run per interval from start_date on first deploy) and state the consequence; never announce orchestration as a fait accompli | `:855-870` | **KEEP** | The single best "veteran shows reasoning, especially for things that fire N runs" rule. High value. | Configure (orchestration) | ALREADY-INCORPORATED (02 §1 L149-156, catchup-explicit) |

### f.6 Generation Packet + targeted retrieval

| # | Rule (one-liner) | Cite | Rec | Rationale | Home | Fold |
|---|---|---|---|---|---|---|
| f21 | **After approval, call build/update tools in rapid succession; keep prompt-talk aligned with registry decision** (don't say "reuse" if registry said `generate`); don't present invalid medallion transitions just because a tool exists | `:922-928` | **KEEP** | Consistency between what the agent *says* and what the tools *do*. | Build/Composer (post-approve) | ALREADY-INCORPORATED (atomic apply) |
| f22 | **Use only the TARGETED dbt best-practice cards + blueprint example packets for the ACTIVE blueprint set** — not generic corpus-wide examples when a narrower packet exists; treat **structured session facts as short-term memory** for reuse decisions / DQ suggestions / linking state | `:927-928`; assembly `ChatService:1244-1330`, packet defs `:96-300` | **KEEP** | The retrieval-narrowing + session-memory mechanic; D1 §E/§F says Configure & Build **inject per-blueprint config guidance** (the PULSE analogue of n8n `get_documentation`). | Build/Configure (per-blueprint guidance) + CROSS-CUTTING (session facts) | PARTIAL — per-blueprint guidance present (01 Build, 02 §2.2); **session-facts-as-memory + example-packet narrowing STILL-TO-FOLD** |

### f.7 Data model + workflow + proactive elicitation

| # | Rule (one-liner) | Cite | Rec | Rationale | Home | Fold |
|---|---|---|---|---|---|---|
| f23 | **The Data Model tree** (Tenant → Domains → SORs → Connectors → Datasets[grain/current_asof/time_grain/file_naming]; Pipelines → Versions → Composition → Sub-pipelines) + key relationships (Domains = canonical ownership; dataset business-time = `current_asof`+`time_grain`; domain global business date advances independently; **AdvanceTimeDimension** moves as-of forward, audit-logged; two-level model no deeper nesting) | `:455-479` | **KEEP** | The shared mental model every stage needs; the AdvanceTimeDimension + global-business-date facts are load-bearing for orchestration. | Provision (owner) + CROSS-CUTTING reference | ALREADY-INCORPORATED (01 Provision L385-394) — Provision cluster |
| f24 | **The "How Pipelines Work" layer table** (Ingestion/Bronze=PySpark · Transform/Silver=dbt · Modeling/Silver-Gold=dbt · DQ/any=GX · Orchestration/control-plane=Airflow) | `:484-492` | **KEEP** | One-glance tech-per-layer map; reinforces f5. | Build/Composer reference | PARTIAL — embedded in medallion rules |
| f25 | **PROACTIVELY ASK ABOUT HISTORY TRACKING for master/dimensional data** — when entity is master (employees/customers/vendors/products/accounts), ALWAYS ask "track historical changes (SCD2)?"; don't wait for the user | `:620-625` (Phase 4) | **KEEP** | A real "the agent leads" behavior; SCD2 omission on master data is a classic miss. | Build/Composer (on master-data detection) | STILL-TO-FOLD — **ABSENT from 01/02** |
| f26 | **PROACTIVELY SUGGEST DQ RULES** — don't wait; when schema known, recommend a DQValidator + present rules in a table BEFORE building (via `suggest_dq_expectations`); **then persist via `apply_dq_expectations`** — suggested rules that live only in chat history never reach codegen; NEVER `remove_step`+`propose_add_instance` to apply DQ | `:629-638`, `:917` | **KEEP** | Two-part: the proactive-suggestion behavior **and** the persist-or-it's-lost guard (parallels d4 / a11). | Configure/Build (DQ) | PARTIAL — `apply_dq_expectations` present (02 §1.2); **proactive-suggestion rule + `suggest_dq_expectations` STILL-TO-FOLD** |
| f27 | **Phase 7 plan presentation** — ask if more sub-pipelines needed before final plan; **ALWAYS include an AdvanceTimeDimension step at the end**; present FULL plan as one table (Step/Blueprint/Name/Purpose) with suggested instance names; one question "does this look good? I'll build once you confirm"; build rules (no orphaned DAG elements; PySpark ingest; dbt/GX Spark-offloadable; wrapped as single Airflow artifact) | `:651-675` | **KEEP** | The plan-presentation template + the "always end with AdvanceTimeDimension" invariant. | Planner | PARTIAL — Plan Preview present (D1 = text + graph); **the plan-table template + AdvanceTimeDimension-at-end invariant STILL-TO-FOLD** |
| f28 | **`remove_step` is DESTRUCTIVE — structural changes only, never to "reset"/reconfigure** — it deletes the instance + drops all wirings; to update config use `configure_step_params` (generic) or `apply_dq_expectations` (DQ); if the right tool doesn't exist, STOP and tell the user — don't improvise with `remove_step` | `:113-122`, `:909` | **KEEP** | Prevents the watch-it-rebuild-in-real-time destructive dance; a recognizable bad behavior. | Configure (owner) + Build | ALREADY-INCORPORATED (01 Build L339; 02 §1 L132-137) |

### f.8 SOR-directory anti-hallucination *(context-section rule — Provision-adjacent)*

| # | Rule (one-liner) | Cite | Rec | Rationale | Home | Fold |
|---|---|---|---|---|---|---|
| f29 | **SOR directory HARD RULE** — the registered-SOR list is a directory, NOT a purpose match; a name keyword ("Payment Gateway") does **not** imply purpose ("servicing"); before acting on an existing SOR you must satisfy (a) user named it by display name, or (b) you confirmed the match this conversation; until then call `list_data_sources` and present options; **NEVER `navigate_ui(data_source_detail, resource_id=...)` on an unconfirmed SOR** (hallucinating a resource_id is a quality regression) | `ChatService:953-956` | **KEEP** | A sharp, specific anti-hallucination rule for resource resolution — generalizes to ANY entity directory (datasets, connectors, pipelines), not just SORs. | CROSS-CUTTING (resource resolution) + Provision | PARTIAL — list-then-confirm pattern present (01 Provision L396-401); **the explicit "name-keyword ≠ purpose / never navigate to unconfirmed id" rule STILL-TO-FOLD and should be generalized** |

### f.9 Identity / voice

| # | Rule (one-liner) | Cite | Rec | Rationale | Home | Fold |
|---|---|---|---|---|---|---|
| f30 | **Persona: "25-year veteran data engineering lead — direct, unpretentious, grounded in medallion mechanics; users lean on you for judgment, not just execution"; "operating inside a redesign — prefer the canonical semantic model even when compatibility shims exist"** | `:5-15` | **KEEP** | The voice that makes a8 (recommend) / f20 (show-your-work) land; the "prefer canonical model" line is exactly right for a rearchitecture. | CROSS-CUTTING (shared identity preamble, all stages) | PARTIAL — voice present; the **explicit veteran-lead persona + "prefer canonical model" preamble STILL-TO-FOLD as a shared identity block** |

---

## Counts

### By category — KEEP / REVISE / DROP

| Category | KEEP | REVISE | DROP | Total | Still-to-fold (incl. partials) |
|---|---|---|---|---|---|
| **STALE table** (do-not-carry) | 0 | 5 | 2 | 7 | n/a (corrections) |
| (a) Cross-cutting discipline | 13 | 1 (a1 emphasis) | 0 | 14 | 3 (a6 page-map, a13 draft-labels, a1 emphasis) |
| (b) Plan→Apply tiers + guardrails | 5 | 0 | 0 | 5 | 0 |
| (c) Storage/provisioning/deploy gate | 4 | 2 | 0 | 6 | 4 (c2 nuance, c4 notice+gate, c6 completion-proof) |
| (d) Runtime-field tiering (ADR 0023) | 4 | 0 | 0 | 4 | 3 (d1 tier-split, d2 tier-tag, d3 AdvanceTimeDim surface) |
| (e) Date/mnemonic facility | 4 | 0 | 0 | 4 | 3 (e1 prefer, e2 vocab, e3 patterns) |
| (f) Everything else | 28 | 2 (f8 dbt-annot, plus c-overlaps) | 0 | 30 | 15 (see below) |
| **TOTAL (excl. stale)** | **58** | **5** | **0** | **63** | **28** |

*(KEEP/REVISE/DROP counts the 63 substantive items in (a)–(f); the 7 STALE rows are corrections, counted separately: 5 REVISE-to-corrected-form + 2 outright DROP.)*

### Still-to-fold tally

**28 of 63** substantive items are **not yet fully in 01/02** (either ABSENT or only PARTIAL). The 11 cleanly **ABSENT** (highest-priority folds):

1. **f6** — dbt-vs-GX boundary teaching (ABSENT)
2. **f8** — dbt `(dbt)` annotation convention (ABSENT)
3. **f11** — Classify-the-Entity table (ABSENT)
4. **f12** — Detect-Multi-Source heuristics (ABSENT)
5. **f13** — Select-Blueprints table (ABSENT)
6. **f14** — Infer-DQ-Expectations table (ABSENT)
7. **f25** — proactive SCD2 / history-tracking elicitation (ABSENT)
8. **c6** — "completion proof = live runtime execution" (ABSENT — and arguably the single most important to elevate, given the operator's pain)
9. **e1/e2/e3** — mnemonic *guidance + vocabulary + patterns* (only validation survives)
10. **d3** — AdvanceTimeDimension 2-field user surface (ADR 0023, not yet in per-blueprint guidance)
11. **f29** — SOR-directory "name-keyword ≠ purpose / never navigate to unconfirmed id" (should be generalized to all entity directories)

The remaining ~17 are **PARTIAL** (the rule's spirit is present but a concrete table/template/nuance — page-map, draft-labels, lake-format nuance, tier-tagging, reasoning-framework tables, generate-vs-reuse 3-outcome protocol, plan-table template + AdvanceTimeDimension-at-end, proactive-DQ suggestion, session-facts memory, veteran persona block — hasn't been carried).

---

## Top recommendations for the fold-in pass

1. **Elevate c6 (completion-proof = live runtime execution)** to a CROSS-CUTTING / Planner non-negotiable — it's PULSE's reason to exist and is currently absent.
2. **Re-author the Reasoning-Framework tables (f11–f16)** into Build/Composer (entity-classify → blueprint-select → DQ-infer) — they're the substance behind "recommend, don't interrogate" and are mostly absent.
3. **Fold the mnemonic facility (e1–e3) into per-blueprint config guidance** (Build/Configure), not just the validator.
4. **Apply the ADR-0023 user/derived `tier` split (d1–d3)** to the Punch List so the rewrite never asks for derived plumbing, and add the AdvanceTimeDimension 2-field surface + "explicitly ask target_scope" rule.
5. **Carry f6 (dbt-vs-GX), f25 (proactive SCD2), f26 (proactive DQ suggest+persist), f8 (dbt annotation)** — these are the proactive-leadership behaviors that make the agent feel like a veteran rather than an order-taker.
6. **Generalize f29** from SOR-only to an all-entity-directory anti-hallucination rule.
7. **Purge the STALE storage-as-per-pipeline framing** everywhere it still lurks (Rule #14, both connector flows, the params-key-stripping narration) — replace with Mode injection.
