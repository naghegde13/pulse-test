# PULSE Chat — per-blueprint-category best-practices guides (the `get_documentation` content)

> **Status: Operator voice-reviewed + signed off (2026-06-16); assembled into `backend/src/main/java/com/pulse/chat/prompt/CategoryGuides.java` in the autonomous build.** These are the five per-category best-practices guides PULSE
> Chat injects into the **composer** stage's context under a `Best Practices:` header (the PULSE analogue of n8n's
> `get_documentation`/`type:"best_practices"` tool — SPEC-ui-composition.md §7.14; the prior n8n prompt summary §3, since removed).
> One guide per blueprint **category**: **Ingestion · Transform · Modeling · Data Quality · Orchestration**.
>
> **D1-FEEDBACK addition (2026-06-16).** The five CATEGORY guides (§1–§5) are **APPROVED as-is** and RETAINED
> unchanged. This revision ADDS, per `D1-FEEDBACK-CHANGELIST.md` §C ("02 §4 row 5") + the operator's §4.5 note, a set
> of PULSE **data-engineering TECHNIQUE guides** (§7) — the PULSE analogue of n8n's "techniques" concept, in PULSE
> vocabulary. n8n splits its `get_documentation` content into per-CATEGORY guides AND per-TECHNIQUE guides
> (`type:"best_practices", techniques:[...]` — `[read get-documentation.tool.ts]`, §0.3 / 02 §4 row 5); PULSE mirrors
> that split: the category guides answer "how do I build an Ingestion/Transform/... pipeline", the technique guides
> answer "how do I implement SCD2 history / an incremental merge / partitioning / DQ-gating / late-arriving data /
> backfill" — cross-category patterns that recur across blueprints. The technique guides follow the SAME guide shape
> (`## Workflow Design` with **CRITICAL** rules → recommended approach → `## Common Pitfalls to Avoid`) and reference
> the **real PULSE blueprints** (§1–§5 keys, SPEC-blueprint-catalog.md #5) + the mnemonic/date facilities
> (`[read PulseSystemPrompt.java:405-434]`).
>
> **Provenance.** Each guide follows the SHAPE of the **real** n8n best-practices guides (read from source, cited
> below) — `# Best Practices: <X>` → `## Workflow Design` (with **CRITICAL** rules) → topical sections →
> `## Recommended <Blueprints>` (per item: **Purpose / Use cases / Configuration / Best practice**) → optionally
> `## Common Pitfalls to Avoid`. The CONTENT is re-expressed in **data-engineering voice** (medallion, SCD2, dataset
> contracts, schema propagation, dbt/Spark/Airflow/Great-Expectations/OpenMetadata) using the **real PULSE blueprint
> keys** from SPEC-blueprint-catalog.md (#5).
>
> **Tags.** `[read]` = grounded in a cited PULSE spec/source or a cited n8n source. `> GUESS:` = an invention this
> author had to make (a best-practice claim, a recommended pattern, or a blueprint behavior) NOT directly read from a
> LOCKED source — to be resolved in operator voice review.
>
> **License posture (same as the prior n8n prompt summary, since removed):** n8n is source-available (Sustainable Use License). We
> read the real guides to learn their SHAPE and re-author in PULSE's voice; we do **not** copy n8n's guide text.

---

## 0. Grounding ledger — n8n sources READ + the divergences vs our summary (§3)

### 0.1 The actual n8n guides read (verbatim, cited)

The real best-practices guides were read from the n8n monorepo. `[read]` Each is a class whose `getDocumentation():
string` returns a markdown guide. Four guides were read in full + one path-confirmation:

- `[read]` **Notification** — `https://github.com/n8n-io/n8n/blob/214a7e9af3c0c74772526ff24c2fdce9da50058c/packages/@n8n/ai-workflow-builder.ee/src/tools/best-practices/notification.ts`
- `[read]` **Data Transformation** — `.../214a7e9.../best-practices/data-transformation.ts`
- `[read]` **Scheduling** — `.../214a7e9.../best-practices/scheduling.ts`
- `[read]` **Monitoring** — `.../214a7e9.../best-practices/monitoring.ts`
- `[read]` Introducing commit (all categories added at once): `https://github.com/n8n-io/n8n/commit/214a7e9af3c0c74772526ff24c2fdce9da50058c`
  ("feat: Add best practices for all builder categories", 2025-11-24).
- `[read]` **Current master location** (the guides MOVED): `https://github.com/n8n-io/n8n/blob/master/packages/@n8n/workflow-sdk/src/prompts/best-practices/index.ts`
  imports 17 guide modules from `./guides/` (chatbot, content-generation, data-analysis, data-extraction,
  data-persistence, data-transformation, document-processing, enrichment, form-input, human-in-the-loop,
  knowledge-base, monitoring, notification, scheduling, scraping-and-research, triage, web-app) and builds a
  `bestPracticesRegistry`. The `.ee` package's `best-practices/index.ts` now **re-exports** from
  `@n8n/workflow-sdk/prompts/best-practices` (the guide bodies left the `.ee` package).

### 0.2 The REAL per-guide SHAPE (from the four guides read)

Reading the four real guides, the shape is **richer** than a clean four-part skeleton. The verbatim structure is:

1. `# Best Practices: <X> Workflows` (title; n8n appends "Workflows").
2. `## Workflow Design` — the lead section. Carries **CRITICAL:** lines (uppercase, inline — e.g. notification's
   `CRITICAL: Multi-channel notifications should branch from a single condition check…`; monitoring's
   `CRITICAL: Enable "Continue On Fail"…`; scheduling uses bolded `**CRITICAL**:` with the rule in quotes). Often
   includes an `Example pattern:` block. **Data Transformation** instead uses sub-headers (`### Core Principles`,
   `### Design Best Practices`) with bulleted rules — so the section's INTERNAL shape varies per guide.
3. **Topical middle sections** (these vary per guide, NOT a fixed list): e.g. notification has
   `## Condition Logic & Filtering`, `## Message Construction`, `## Authentication & Permissions`,
   `## Alert Management`; scheduling has `## Scheduling Patterns`, `## Time Zone Configuration`,
   `## Error Handling & Monitoring`; monitoring has `## Service Status Checking`, `## Alert Configuration`,
   `## Logging & State Management`; data-transformation has `## Error Handling`.
4. `## Recommended Nodes` — grouped (e.g. `### Trigger Nodes`, `### Notification Nodes`). Each node is
   `**Name** (n8n-nodes-base.x):` then bullets — **Purpose / Use cases / Configuration / Best practice** — but
   **not all four are always present** (many nodes carry only Purpose + Best practice; data-transformation adds
   **Pitfalls** per node). Some nodes also carry `**Modes**:` / `**Key Setting**:`.
5. `## Common Pitfalls to Avoid` — present on notification/monitoring/data-transformation/scheduling; a list of
   `### <Pitfall>` → **Problem** / **Solution** blocks (or a flat bullet list in data-transformation).

### 0.3 Divergences found — the prior n8n prompt summary §3 (since removed) vs the real guides

`[read]` Our §3 says: *"Per-guide shape: `# Best Practices: <X>` → `## Workflow Design` (CRITICAL: rules) →
`## Condition Logic` → `## Recommended Nodes` (each: Purpose / Use cases / Configuration / Best practice)."*

Verified divergences:

1. **DIVERGENCE — "18 per-technique guides" is wrong; it is 17.** §3 (and the repo-structure note) say **18**.
   The real master `index.ts` registry imports **17** guide modules. `> GUESS:` our "18" likely counted an
   index/registry file or a since-removed guide. **Correct count is 17** (the prior summary's "18" is moot — that summary doc has since been removed).
2. **DIVERGENCE — the guides MOVED packages, and §3's path is stale.** §3 cites
   `workflow-sdk/.../best-practices/guides/` for the bodies but also implies the `.ee` tool owns them. Reality:
   bodies were ADDED in the `.ee` package (commit `214a7e9`, `ai-workflow-builder.ee/src/tools/best-practices/*.ts`)
   and LATER relocated to `@n8n/workflow-sdk/src/prompts/best-practices/guides/*`; the `.ee` package now re-exports.
   So **both** paths are "real" at different points in history. **Flag: §3 should cite the master SDK path for the
   live bodies and note the `.ee` origin.**
3. **DIVERGENCE — `## Condition Logic` is not a universal section.** §3 lists `## Condition Logic` as if it were a
   fixed part of the shape. It is **guide-specific** (notification has `## Condition Logic & Filtering`; the others
   do not). The shape's only reliable spine is `## Workflow Design` + `## Recommended Nodes`; middle sections are
   per-guide topical. **Flag: §3 over-generalized one guide's section into the canonical shape.**
4. **DIVERGENCE — Purpose/Use cases/Configuration/Best practice is the IDEAL per-node block, not a strict template.**
   Real nodes frequently omit some of the four and add others (**Pitfalls**, **Modes**, **Key Setting**). §3's
   "(each: Purpose / Use cases / Configuration / Best practice)" reads as mandatory-four; reality is "0–4 of these +
   extras." **Flag: soften §3's phrasing to "typically some of Purpose/Use cases/Configuration/Best practice."**
5. **ADDITION — `## Common Pitfalls to Avoid` exists on every real guide and §3 omits it.** This is a substantial,
   load-bearing section (state-of-the-art failure catalog). **Flag: add it to §3's shape description.** The PULSE
   guides below adopt it (it maps cleanly to data-eng failure modes).
6. **MINOR — title suffix "Workflows."** Real titles are `# Best Practices: Notification Workflows` etc. PULSE
   guides drop "Workflows" (PULSE builds *pipelines*, and the title reads better as `# Best Practices: Ingestion`).
   `> GUESS:` cosmetic; flagged so it is a deliberate choice, not an oversight.

### 0.4 PULSE grounding (the blueprint keys + category facts used below)

`[read]` Categories and member blueprint keys are from **SPEC-blueprint-catalog.md (#5)**:
INGESTION 6 · TRANSFORM 10 · MODELING 8 · DATA_QUALITY 4 (the five guides here cover those five categories; SINK and
CONTROL are folded into Ingestion/Transform sinks and the Orchestration guide respectively — see each guide's scope
note). `[read]` Compute mapping: INGESTION/SINK→PySpark, TRANSFORM/MODELING→dbt, DATA_QUALITY→Great Expectations,
CONTROL→DAG-only (#5 §0.3). `[read]` Audit-column set is **8 columns** appended by `add-audit-columns` (#5 §0.3).
`[read]` `system-derived` params (storage_backend/lake_layer/lake_format/calendar/connector refs) are NEVER
hand-typed (#5 §0.2; ADR 0023) — the guides tell Chat to leave those alone. `[read]` Secrets are **SecretRefs only,
never values** (SPEC-ui-composition.md §7.2; ADR 0023) — the PULSE analogue of n8n's "never hardcode credentials."

> GUESS: **All "Best practice" / "Use cases" / pattern claims in §1–§5 below are this author's data-engineering
> judgment**, not read from a PULSE spec (the specs define params/ports/op-lists, not prose guidance). They are the
> primary target of the operator voice review. Where a claim restates a #5 fact (e.g. "DQValidator splits into a
> validated port + a quarantine port"), it carries `[read]`; everything else is the author's recommendation.

---

## 1. `# Best Practices: Ingestion`

> Scope: the 6 INGESTION blueprints (land raw source data into **bronze**). `[read]` All share the canonical shape
> `read-source → add-audit-columns → write-sink(bronze)`, have **no input port** (they pull from a connector) and
> **one output port** landing bronze (#5 INGESTION header). Sink/writer blueprints (WarehouseWriter / LakeWriter /
> DatabaseWriter / StreamWriter) are the terminal counterparts and are covered under §2's "Recommended" tail.

### Workflow Design

Every ingestion step lands **bronze**: a faithful, append-friendly copy of the source with PULSE audit columns added
and **no business logic**. Do not clean, dedupe, mask, or join in an ingestion step — that is silver/gold work
(§2/§3). Keep bronze as close to the source as the format allows so you can always re-derive downstream layers.

`[read]` PULSE appends the **8-column audit set** (`_pulse_ingested_at`, `_pulse_processing_ts`, `_pulse_pipeline`,
`_pulse_task`, `_pulse_run_id`, `_pulse_source_uri`, `_pulse_business_date`, `_pulse_dag_id`) automatically via
`add-audit-columns` — never add your own ingestion-timestamp columns; they already exist (#5 §0.3).

**CRITICAL:** never hand-type storage or connector plumbing. `[read]` `storage_backend`, `lake_layer` (bronze for
ingestion), `lake_format`, and connector refs (`auth_credential_ref`, `connector_instance_id`, `source_type`, topic /
consumer-group / stream-type) are **system-derived** — PULSE resolves them at codegen/deploy time from the chosen
connector and the pipeline's storage backend (#5 §0.2, §0.1; ADR 0023). The Customer **selects the connector**; the
refs follow. Do not invent credential values — PULSE carries **SecretRefs only, never secret values** (the n8n
"credentials are handled by the credential system, never set keys yourself" rule, mapped to PULSE SecretRefs).

**CRITICAL:** one ingestion blueprint per source dataset. Branching one ingestion step to land "many tables at once"
hides per-source schema and breaks schema propagation downstream. `> GUESS:` use one ingestion step per logical
source table (CDCIngestion's `tables[]` is the documented exception — it ingests a related change-stream set).

**CRITICAL:** make the run **idempotent and re-runnable for a business date**. `[read]` Date-driven sources use a
date mnemonic (`date_value`, default `RUN_DATE`) feeding the filename/predicate so a re-run for the same business date
reproduces the same bronze partition (#5 FileIngestion/BulkBackfill). Do not hardcode an absolute date.

Example pattern (a daily file feed):
- `FileArrivalSensor` (wait for the file) → `FileIngestion` (land bronze, partitioned by `ds`) → downstream silver.

### Choosing the right ingestion blueprint

`> GUESS:` pick by the **source's delivery mechanics**, not by convenience:
- A **file lands on a schedule** (CSV/Parquet on object store / SFTP) → `FileIngestion`.
- A **full point-in-time copy** of a source table each period → `SnapshotIngestion`.
- **Changed rows only** from an operational DB (insert/update/delete) → `CDCIngestion`.
- A **bounded historical reload** over a date range → `BulkBackfill`.
- A **streaming topic** (Kafka and similar), micro-batched → `StreamIngestion`.
- A **REST/HTTP API** with pagination/watermark → `ApiIngestion`.

### Recommended Blueprints

#### FileIngestion

- **Purpose:** land a date-stamped source file (the connector path matched by `filename_pattern`) into bronze.
- **Use cases:** `[read]` daily/periodic flat-file feeds; the anchor lending-data pattern (a `loan_master` CSV).
- **Configuration:** `[read]` set `filename_pattern` (uses the legacy `{date}` placeholder), `date_value` (business-
  date mnemonic, default `RUN_DATE`), `delimiter`/`has_header`; leave `date_format`, `partition_by` (`["ds"]`),
  `expected_size_min`, and the calendar refs system-derived (#5 FileIngestion).
- **Best practice:** `> GUESS:` pin the filename to the business date so a backfill of an old date lands the right
  partition; set `has_header` honestly — a wrong header flag silently shifts every column.

#### SnapshotIngestion

- **Purpose:** `[read]` a full point-in-time source pull into bronze (#5).
- **Use cases:** `> GUESS:` reference/dimension sources where you want the whole table each period (small-to-medium
  tables, slowly changing) and intend to build SCD2 history downstream from the snapshots.
- **Configuration:** `[read]` `snapshot_frequency`, optional `compare_key`; `source_table` is connector-derived (#5).
- **Best practice:** `> GUESS:` feed SnapshotIngestion → `SCD2Dimension` when you need history; a daily snapshot +
  SCD2 snapshot is the cleanest deterministic history without CDC infrastructure.

#### CDCIngestion

- **Purpose:** `[read]` land change events (insert/update/delete) from an operational DB into bronze (#5).
- **Use cases:** `> GUESS:` high-volume operational tables where a full snapshot is too expensive; you need
  near-real-time deltas.
- **Configuration:** `[read]` `tables[]`, `primary_key[]`, `cdc_mode` (debezium / incremental_poll), and for
  `incremental_poll` an `incremental_column` + `watermark_column`; `delete_handling` (e.g. soft_delete); `source_type`
  is connector-derived (#5).
- **Best practice:** `[read]` set `delete_handling` deliberately — silver/gold semantics depend on whether deletes are
  soft or hard. **Fix-item flagged in #5:** today's CDC codegen is a plain JDBC read with no change-column; treat the
  before/after change semantics as a `read-source` config concern, judged on intent (#5 CDCIngestion).

#### BulkBackfill

- **Purpose:** `[read]` a bounded historical reload over a date range into bronze (#5).
- **Use cases:** `> GUESS:` first-load of a new pipeline; re-deriving bronze after a source correction.
- **Configuration:** `[read]` `source_query` (JDBC SELECT, supports `[[ … ]]` mnemonics), `date_range_start` /
  `date_range_end` (both accept date mnemonics); `chunk_size`, `chunk_days`, `parallelism`, and calendar refs are
  system-derived (#5).
- **Best practice:** `[read]` BulkBackfill is the **surviving** backfill blueprint — do NOT use the deprecating
  `BackfillAndReplay` (#5 BulkBackfill notes). `> GUESS:` keep the date window aligned to the same partition grain the
  daily ingestion uses, so backfilled and incremental partitions are interchangeable.

#### StreamIngestion

- **Purpose:** `[read]` micro-batched pull from a streaming topic into bronze (#5).
- **Use cases:** `> GUESS:` event streams where you want lakehouse bronze, not a live serving store.
- **Configuration:** `[read]` `batch_window_seconds`, `deserialization_format` (json/avro), `starting_offsets`;
  `stream_type` / `topic` / `consumer_group` are connector-derived ("Inherited from connector") (#5).
- **Best practice:** `> GUESS:` size `batch_window_seconds` to your freshness SLA, not as small as possible — tiny
  windows create small-file storms in bronze.

#### ApiIngestion

- **Purpose:** `[read]` paginated REST/HTTP pull into bronze (#5).
- **Use cases:** `> GUESS:` SaaS/third-party APIs without a database or file export.
- **Configuration:** `[read]` `api_url` (may carry `{{ ds }}` templating), `auth_type`, `pagination_type`,
  `rate_limit_rpm`, `incremental_field`, `response_json_path`; `auth_credential_ref`, `retry_count` (=3),
  `timeout_seconds` (=60) are system-derived (#5).
- **Best practice:** `> GUESS:` set `incremental_field` + a watermark so you pull deltas, not the full history each
  run; respect `rate_limit_rpm` to avoid 429s.

### Common Pitfalls to Avoid

- **Cleaning in bronze.** `> GUESS:` trimming/casting/deduping at ingestion erases the source-fidelity you need to
  re-derive silver. Push all of it into `BronzeToSilverCleaning` (§2).
- **Hand-typed storage/credentials.** `[read]` editing `storage_backend`/`lake_*`/credential refs — they are
  system-derived and surfaced read-only; PULSE resolves them (#5 §0.2; ADR 0023).
- **Non-idempotent dates.** `> GUESS:` absolute dates in `filename_pattern`/`source_query` make a re-run land the
  wrong partition; use the business-date mnemonic.
- **Small-file storms.** `> GUESS:` over-frequent stream/API micro-batches fragment bronze; size windows to the SLA.
- **No upstream sensor.** `> GUESS:` ingesting before the file/source is ready yields empty or partial bronze; gate
  ingestion behind the matching sensor (§5).

---

## 2. `# Best Practices: Transform`

> Scope: the 10 TRANSFORM blueprints (turn **bronze → silver**: clean, conform, mask, join, aggregate, route,
> flatten/structure). `[read]` Compute is **dbt-SQL** (`compute:"dbt"`); default landing layer is **silver**; most
> ops are optional → an unconfigured op is a passthrough (#5 TRANSFORM header, BronzeToSilverCleaning).

### Workflow Design

The transform layer's job is to produce **silver**: cleaned, conformed, deduplicated, contract-shaped data the
modeling layer can trust. Build it in this order — **clean → conform (normalize) → mask → join/aggregate → route /
flatten** — so each step's INPUT contract is the previous step's OUTPUT contract.

**CRITICAL:** rely on **schema propagation**, not hope. `[read]` Every transform op has a declared schema effect (the
closed 32-op vocabulary; ADR 0012/0013); PULSE computes each step's output columns from its input columns + its op
(#5 §0.2 escape-hatch note; SPEC-ui-composition.md §7.6/§7.7). Configure a step against the **actual upstream
columns** Chat can read (`get_step_schema`), never against assumed column names.

**CRITICAL:** PULSE-specific collision and type rules are owned by the schema engine — do not work around them in SQL.
`[read]` Join collisions prefix the right side `right_<name>` and keep both (#5 GenericJoin). Aggregate output types
are fixed: COUNT→long, SUM int→long / decimal→double, AVG→double, MIN/MAX→source (#5 GenericAggregate). Masking is
type-preserving except `hash → string` (#5 PIIMasking). Don't hand-cast around these.

**CRITICAL:** keep one transform = one concern. `> GUESS:` a step that both joins and aggregates and masks is
un-reviewable and breaks impact-radius analysis; chain single-concern steps. (This is the data-eng form of n8n's
data-transformation `### Core Principles` "Modularity / keep main workflows small.")

**CRITICAL:** mask PII **before** it can leak into a join key, an aggregate, or a sink. `[read]` `PIIMasking` runs at
silver; placing it after a join that already exposed the raw value defeats it (#5 PIIMasking). Put masking as early as
the lineage allows.

Example pattern (conform + protect a feed):
- bronze → `BronzeToSilverCleaning` → `SchemaNormalization` (conform to a standard contract) → `PIIMasking` → silver.

### Schema & contract discipline

`[read]` silver is where **dataset contracts** start to matter — schema contracts are required for promotion to
integration+ (project CLAUDE.md; ADR 0011). Make silver column names and types stable: rename/cast in
`SchemaNormalization` once, then keep them fixed so downstream consumers don't break on every edit. `[read]` PULSE
classifies an edit as non-breaking / partial / breaking from how downstream consumes the column (the conflict overlay,
SPEC-ui-composition.md §2) — prefer non-breaking changes (additive columns) over renames/drops of consumed columns.

### Recommended Blueprints

#### BronzeToSilverCleaning

- **Purpose:** `[read]` the anchor silver step — trim, fill nulls, rename, re-type, drop columns, drop null-rows,
  deduplicate (#5; decomposes to `transform-values + rename-columns + change-types + drop-columns + filter-rows +
  deduplicate`).
- **Use cases:** `> GUESS:` first hop out of bronze for almost every feed.
- **Configuration:** `[read]` the locked V153 keys — `columns_to_trim`, `fill_null_map`, `rename_map`,
  `type_coercions`, `drop_columns`, `drop_null_columns`, `dedup_key`; every op optional → unconfigured = passthrough
  (#5). **Fix-item flagged in #5:** today's row exposes a `null_handling` enum + `SELECT *` body that ignores the
  declared params — judge on the INTENT (the decomposed key set), which V153 supersedes.
- **Best practice:** `> GUESS:` dedupe with an explicit `dedup_key` (don't rely on `DISTINCT *`); fill nulls
  deliberately per column rather than blanket — a wrong fill corrupts downstream aggregates.

#### SchemaNormalization

- **Purpose:** `[read]` conform incoming columns to a standard/target schema (rename, re-type, optionally drop extras)
  (#5).
- **Use cases:** `> GUESS:` aligning multiple sources to one canonical silver contract; enforcing a registered schema.
- **Configuration:** `[read]` `target_schema`, `mapping_rules` (source→target field map), `strict_mode` (true →
  activates `drop-columns` for columns not in the target) (#5).
- **Best practice:** `> GUESS:` normalize **once**, early — re-conforming the same dataset in multiple steps creates
  drift. Use `strict_mode` when the contract must be exact (promotion-bound silver).

#### DedupeAndMerge

- **Purpose:** `[read]` deduplicate within a partition with a defined tie-break order (#5).
- **Configuration:** `[read]` `match_keys[]` (the dedup partition), `order_by_columns` (`[{column,direction}]`),
  `match_strategy` (exact/fuzzy/composite), `merge_priority`; `dedup_method` is system-derived (#5).
- **Best practice:** `> GUESS:` always set `order_by_columns` so "which duplicate wins" is deterministic — silent
  arbitrary tie-breaks produce non-reproducible silver.

#### PIIMasking

- **Purpose:** `[read]` mask sensitive columns (#5; `mask-columns`).
- **Configuration:** `[read]` `columns_to_mask[]`, `masking_strategy` (hash/redact/tokenize/encrypt),
  `preserve_format`, `hash_algorithm` (sha256/sha512/md5, only when strategy=hash) (#5).
- **Best practice:** `[read]` remember `hash` changes the column type to `string` (the schema engine owns this — #1
  rule 7); `> GUESS:` choose `tokenize`/`encrypt` (not `hash`) when a downstream system must still join on the value.

#### GenericJoin

- **Purpose:** `[read]` join two silver inputs (#5; the only multi-input TRANSFORM — two input ports).
- **Configuration:** `[read]` `join_type` (inner/left/right/full_outer/cross), `join_keys`
  (`[{left_column,right_column}]`), `select_columns`, `alias_left`/`alias_right` (#5).
- **Best practice:** `[read]` expect `right_<name>` prefixing on column collisions and `select_columns` away what you
  don't need; `> GUESS:` prefer `left` joins to preserve the driving dataset's row count, and verify the join key
  cardinality (a many-to-many join silently fans out rows).

#### GenericAggregate

- **Purpose:** `[read]` group-and-aggregate at silver (#5).
- **Configuration:** `[read]` `group_by_columns`, `aggregations` (`[{column,function,alias}]`), `having_clause`
  (SQL, accepts `[[ … ]]` mnemonics), `window_functions` (#5).
- **Best practice:** `[read]` know the output-type rules (COUNT→long, etc.); `> GUESS:` alias every aggregate
  explicitly — an unaliased `sum(x)` makes a fragile downstream contract. **Merge candidate** with
  `AggregateMaterialization` (§3) — use GenericAggregate when the result stays silver, AggregateMaterialization when
  it materializes a gold summary (#5).

#### GenericFilter

- **Purpose:** `[read]` filter rows (#5).
- **Configuration:** `[read]` `filter_mode` (visual/sql); `conditions` (`[{column,operator,value}]`) or `raw_sql`
  (WHERE, accepts `[[ … ]]`) (#5).
- **Best practice:** `> GUESS:` prefer the visual `conditions` builder so the filter is inspectable and round-trips;
  reserve `raw_sql` for expressions the builder can't express. Filter **early** to shrink downstream cost (the n8n
  "filter and reduce data early" principle, in data-eng terms).

#### GenericRouter

- **Purpose:** `[read]` route rows to N named output ports + a default (each route carries the input schema) (#5).
- **Configuration:** `[read]` `routes` (`[{name,condition,description}]`), `include_default` (#5).
- **Best practice:** `[read]` each route name becomes a dynamic output port — name routes for what downstream consumes
  them. **Fix-item flagged in #5:** codegen emits N+1 models but schema-prop currently gives one passthrough port; the
  INTENT is one port per route + default (judge on intent — #5 GenericRouter). `> GUESS:` keep `include_default` on so
  unmatched rows are never silently dropped.

#### JsonFlatten

- **Purpose:** `[read]` nested struct → flat sub-fields (#5).
- **Configuration:** `[read]` `source_columns[]`, `max_depth`, `explode_arrays`, `keep_original`, `prefix`;
  `separator` is system-derived (#5).
- **Best practice:** `> GUESS:` bound `max_depth` — flattening deeply nested JSON unbounded explodes the column count;
  `explode_arrays` multiplies rows, so use it intentionally.

#### JsonStruct

- **Purpose:** `[read]` flat columns → nested struct/json (#5).
- **Configuration:** `[read]` `output_format` (struct/json), `mappings` (`[{struct_name,fields:[{source_column,as}]}]`),
  `drop_source_columns`, `passthrough_columns` (#5).
- **Best practice:** `> GUESS:` use `passthrough_columns` to keep keys you'll join on outside the struct; structuring
  is usually a final pre-sink shaping step, not a mid-pipeline transform.

### Recommended sink (writer) blueprints — the terminal of a transform/model chain

> `[read]` The 4 SINK blueprints write the curated result out; one **input** port (role gold), **no** output port,
> compute PySpark; `write_mode=merge_on_pk` requires `merge_keys` (#5 SINK).

- **WarehouseWriter** — `[read]` write gold to a warehouse target; pick `target_id`, `target_table`, `write_mode`;
  `connector_*` refs and `batch_size`/`clustering_columns` are system-derived (#5). `> GUESS:` use `merge_on_pk` for
  upsert targets, `overwrite_partition` for full-partition refresh.
- **LakeWriter** — `[read]` write gold to a Delta/Iceberg lake target; `output_path`, `write_mode`,
  `optimize_after_write` + `z_order_columns`; `lake_format` system-derived (#5). `> GUESS:` enable
  `optimize_after_write` for large tables read by BI.
- **DatabaseWriter** — `[read]` write gold to a JDBC database target; `target_table`, `write_mode`, `upsert_keys`;
  `batch_size`/`connection_pool_size` system-derived (#5).
- **StreamWriter** — `[read]` publish gold to a Kafka topic; `topic`, `publish_mode`, `schema_strategy`,
  `delivery_guarantee`; `checkpoint_location` is system-derived and **must be unique per pipeline** (#5).

### Common Pitfalls to Avoid

- **Configuring against assumed columns.** `[read]` read the real upstream schema (`get_step_schema`) — silver columns
  may already be renamed/cast (SPEC-ui-composition.md §7.3).
- **Hand-casting around the schema engine.** `[read]` join-collision prefixing, aggregate output types, and
  hash→string are owned by the engine; fighting them in SQL desyncs the propagated schema from reality (#5).
- **Renaming/dropping a consumed silver column.** `[read]` that's a breaking change in the conflict overlay — prefer
  additive (SPEC-ui-composition.md §2).
- **Masking too late.** `[read]` PII that already flowed into a join/aggregate is exposed; mask first (#5 PIIMasking).
- **Dropping rows silently in a router.** `> GUESS:` `include_default=false` discards unmatched rows with no trace.

---

## 3. `# Best Practices: Modeling`

> Scope: the 8 MODELING blueprints (build **gold**: dimensions, facts, marts, SCD2 history, snapshots, incremental
> merges, reference/feature publishes). `[read]` Compute is **dbt-SQL** (`compute:"dbt"`); SCD2Dimension →
> dbt-snapshot, SnapshotModel → dbt-incremental; default landing layer is **gold** (#5 MODELING header).

### Workflow Design

Modeling turns conformed silver into **gold**: the dimensional/serving layer BI and downstream consumers query.
Decide the **grain** of every model first — a fact is "one row per ___", a dimension is "one row per business key" —
and let the grain drive the blueprint choice. Gold reads from silver (and from other gold for marts), never from
bronze directly.

**CRITICAL:** choose the **history strategy** deliberately, because it is hard to change later:
- Need to **track changes to a dimension over time** (with valid-from/valid-to) → `SCD2Dimension`.
- Need a **periodic full picture** (point-in-time snapshots) → `SnapshotModel`.
- Need to **upsert the latest state** (no history) → `IncrementalMerge`.

`> GUESS:` mixing these on one entity (e.g. SCD2 plus a separate snapshot of the same dimension) duplicates history and
confuses lineage — pick one per entity unless you have a specific dual-use need.

**CRITICAL:** `[read]` SCD2Dimension adds `dbt_valid_from` / `dbt_valid_to` / `dbt_scd_id` / `dbt_updated_at`;
SnapshotModel adds `ds` + `_pulse_processing_ts` + `_pulse_run_id` + `_pulse_snapshot_model` (#5). Do not add your own
versioning columns — the blueprint owns them. **Fix-items flagged in #5:** the schema rules for SCD2 and SnapshotModel
are currently transposed (swapped with each other) and SnapshotModel is mis-tagged `dbt_snapshot` — judge these on
intent (SCD2 = dbt snapshot semantics, SnapshotModel = dbt incremental); the rules are #1/#2 fixes, not param changes.

**CRITICAL:** declare keys explicitly. `[read]` SCD2 needs `business_key[]` + `tracked_columns[]`; facts need
`dimension_keys[]` + `measures[]`; merges need `merge_key[]` (#5). A wrong or missing business key silently corrupts
history or fans out rows.

`> GUESS:` leave `partition_by` / `cluster_by` system-derived (storage convention from grain/keys, #5) unless you have
a measured performance reason to override.

Example pattern (a conformed dimension with history):
- silver `customer` → `SCD2Dimension` (business_key=customer_id) → gold `dim_customer` (full history).

### Recommended Blueprints

#### SCD2Dimension

- **Purpose:** `[read]` track slowly-changing dimension history via dbt snapshot (#5).
- **Use cases:** `> GUESS:` any dimension where "what did this look like on date X" matters (customer, product,
  account attributes).
- **Configuration:** `[read]` `business_key[]`, `tracked_columns[]` (a change in these opens a new version),
  `effective_date_column`; `partition_by`/`cluster_by` system-derived (#5).
- **Best practice:** `> GUESS:` track only the columns whose changes are business-meaningful — tracking volatile
  columns (e.g. a last-login timestamp) explodes version churn.

#### SnapshotModel

- **Purpose:** `[read]` take periodic point-in-time snapshots via dbt incremental (#5).
- **Configuration:** `[read]` `snapshot_frequency`, `retention_days`, `unique_key[]`, `strategy` (e.g. timestamp);
  `partition_by`/`cluster_by` system-derived (#5).
- **Best practice:** `> GUESS:` use SnapshotModel when you want "the state at each period" rather than a change-log;
  set `retention_days` to your audit requirement, not indefinitely.

#### FactBuild

- **Purpose:** `[read]` build a fact table by joining transactions to dimension keys and keeping measures (#5;
  `join + keep-columns`; two input ports — transaction data + dimension refs).
- **Configuration:** `[read]` `grain` (what one row represents), `measures[]`, `dimension_keys[]`, `incremental`,
  `time_column`; `partition_by`/`cluster_by` system-derived (#5).
- **Best practice:** `> GUESS:` state the `grain` precisely — most fact bugs are grain bugs (duplicated measures from a
  fan-out join). Make it `incremental` with a `time_column` once the fact is large.

#### WideDenormalizedMart

- **Purpose:** `[read]` build a wide BI mart by joining a core fact to N dimensions (+ optional pre-aggregation) (#5).
- **Configuration:** `[read]` `fact_source`, `dimension_joins`, `pre_aggregations`; partition/cluster system-derived.
- **Best practice:** `> GUESS:` denormalize for read performance only when consumers actually need it — a wide mart is
  expensive to maintain; keep the underlying fact/dim as the source of truth.

#### AggregateMaterialization

- **Purpose:** `[read]` materialize a gold summary via group-and-aggregate (#5; **≡ GenericAggregate** merge
  candidate).
- **Configuration:** `[read]` `group_by[]`, `aggregations` (`[{measure,aggregation_function}]`), `refresh_strategy`
  (e.g. incremental); partition/cluster system-derived (#5).
- **Best practice:** `[read]` use this (not GenericAggregate) when the aggregate **lands gold** with a refresh
  strategy; `> GUESS:` set `refresh_strategy=incremental` for large detail tables.

#### IncrementalMerge

- **Purpose:** `[read]` upsert the latest state into a gold target (#5; `merge-rows`).
- **Configuration:** `[read]` `merge_key[]`, `merge_strategy` (upsert), `soft_delete`, `late_data_policy` +
  `late_threshold_hours`; partition/cluster system-derived (#5).
- **Best practice:** `> GUESS:` define `late_data_policy` for out-of-order arrivals; use `soft_delete` rather than hard
  delete when downstream needs the tombstone for reconciliation.

#### ReferenceDataPublish

- **Purpose:** `[read]` publish curated, deduplicated reference data (this blueprint ends in `write-sink` — it
  self-publishes) (#5).
- **Configuration:** `[read]` `reference_type`, `publish_frequency` (e.g. on_change), `versioned`; partition/cluster
  system-derived (#5).
- **Best practice:** `> GUESS:` keep `versioned=true` for reference sets that consumers pin to (so a reference change
  doesn't silently alter historical joins).

#### FeatureTablePublish

- **Purpose:** `[read]` publish an ML-ready feature table with point-in-time correctness (#5; ends in `write-sink`).
- **Configuration:** `[read]` `entity_key`, `features` (feature definitions), `point_in_time_column`; `output_format`
  and partition/cluster system-derived (#5).
- **Best practice:** `> GUESS:` always set `point_in_time_column` — feature leakage (using future data) is the classic
  feature-store bug; this column is how PULSE enforces as-of correctness.

### Common Pitfalls to Avoid

- **Wrong grain.** `> GUESS:` the most common modeling bug — a fan-out join duplicates measures; state and verify the
  `grain`.
- **Missing/incorrect business or merge key.** `[read]` SCD2/merge silently corrupt history with the wrong key (#5).
- **Adding your own version columns.** `[read]` SCD2/SnapshotModel own their history columns; adding more collides
  with the blueprint (#5).
- **Reading from bronze.** `> GUESS:` gold should read conformed silver (or other gold), not raw bronze — skipping
  silver re-introduces the cleaning you skipped.
- **Over-denormalizing.** `> GUESS:` wide marts are costly; build them only for real consumer demand.

---

## 4. `# Best Practices: Data Quality`

> Scope: the 4 DATA_QUALITY blueprints (validate, check freshness, detect schema drift, detect anomalies).
> `[read]` Compute is **Great Expectations** (`compute:"gx"`); DQ blueprints carry **only** `storage_backend` from the
> storage block (they don't land a medallion table); `DQValidator` splits into a validated port + a quarantine port,
> the others emit an **append-only report** side-output (#5 DATA_QUALITY header).

### Workflow Design

Data quality is a **gate and a signal**, not a transform. Place DQ where a contract must hold: at the **bronze→silver
boundary** (is the raw feed usable?) and at the **silver/gold boundary** (does the curated output meet the contract
before it's published or promoted?). `> GUESS:` validate the inputs you're about to trust, not every intermediate step
— over-gating slows the DAG without adding assurance.

**CRITICAL:** decide the **failure outcome** explicitly. `[read]` `DQValidator`'s `on_failure` is the canonical
outcome control — **quarantine** (route failing rows to a managed quarantine table, keep the good rows flowing),
**block** (FAIL raises and fails the run), or **warn** (record but continue) (#5 DQValidator). This is the data-eng
analogue of n8n monitoring's `CRITICAL: Enable "Continue On Fail"` rule — here you choose, per check, whether a failure
should stop the run or be quarantined.

**CRITICAL:** `[read]` a FAIL with `on_failure=block` raises and **fails the Airflow run** — wire DQ where a failed run
is the correct response (e.g. before a publish), not where it would needlessly halt an otherwise-recoverable pipeline
(#5 DQValidator).

**CRITICAL:** reports are **append-only**. `[read]` `FreshnessChecks` / `SchemaDriftDetection` / `AnomalyDetection`
emit an append-only report table (#5; **fix-item flagged: today they overwrite** — judge on the append-only intent,
#5 FreshnessChecks/AnomalyDetection). Trend analysis depends on history; never design around an overwriting report.

`> GUESS:` quarantine is the default-good outcome for ingestion-boundary checks (keep the pipeline moving, isolate bad
rows for inspection); `block` is the default-good outcome for promotion-boundary checks (don't publish bad gold).

Example pattern (gate a feed, then publish):
- silver → `DQValidator` (on_failure=quarantine) → `SchemaDriftDetection` (report) → gold model → `DQValidator`
  (on_failure=block) → sink.

### DQ readiness & OpenMetadata

`[read]` Schema contracts are required for promotion to integration+ and **publishing to OpenMetadata starts at
integration+ only** — dev experiments never publish (project CLAUDE.md; ADR/PULSE-MAP). `> GUESS:` use DQ checks to
earn promotion readiness: a green DQValidator + a stable SchemaDriftDetection report are evidence the contract holds.
PULSE also ingests OpenMetadata change-events (e.g. schema drift → migration-plan proposal) — so a drift report can
feed a proposal, not just an alert.

### Recommended Blueprints

#### DQValidator

- **Purpose:** `[read]` validate rows against Great-Expectations expectations; split passed vs quarantined (#5;
  `check-data`, two output ports — validated + quarantine).
- **Use cases:** `> GUESS:` the workhorse gate at any contract boundary.
- **Configuration:** `[read]` `expectations` (`[{type,kwargs,severity}]` GX), `on_failure`
  (quarantine/block/warn), `threshold_percent` (min success %), `mostly` (0.0–1.0 GX `mostly`) (#5).
- **Best practice:** `> GUESS:` start with a few high-value expectations (not-null on keys, accepted-values on
  enums, range on amounts) rather than dozens of brittle ones; set `mostly` for real-world noisy data instead of
  demanding 100%.

#### FreshnessChecks

- **Purpose:** `[read]` assert the data is recent enough against an SLA; emit an append-only report (#5;
  `check-data + emit-report`).
- **Configuration:** `[read]` `timestamp_column`, and **one** of `max_age_minutes` / `max_age_hours` (default 24) /
  `max_age_business_days` (business-day SLA); calendar refs system-derived (#5).
- **Best practice:** `> GUESS:` choose the SLA dimension that matches the cadence — `max_age_business_days` for
  business-calendar feeds (so a weekend gap isn't a false alarm), sub-hour `max_age_minutes` for streaming.

#### SchemaDriftDetection

- **Purpose:** `[read]` detect when incoming columns diverge from an expected baseline; emit an append-only report
  (#5; `check-data + emit-report`).
- **Configuration:** `[read]` `expected_columns` (`[{name,type}]`), `strict_order`, `allow_extra_columns`,
  `drift_policy` (warn/block/…) (#5).
- **Best practice:** `[read]` you assert the expected baseline (drift = deviation from what you declared) — `> GUESS:`
  keep `allow_extra_columns=true` for additive sources, `false` for strict contracts; route a drift to a migration
  proposal via OpenMetadata rather than just alerting. `> GUESS:` if PULSE auto-snapshots the upstream contract, this
  baseline may become system-derived — confirm in #5 (flagged there).

#### AnomalyDetection

- **Purpose:** `[read]` detect statistical anomalies in monitored columns/volume; emit an append-only report (#5;
  `check-data + emit-report`).
- **Configuration:** `[read]` `monitored_columns[]`, `sensitivity_percent` (default 2.0), `detection_method`
  (e.g. z_score), `lookback_runs` (default 10), `volume_monitoring` (#5).
- **Best practice:** `> GUESS:` give it enough `lookback_runs` to learn a baseline before trusting alerts; turn on
  `volume_monitoring` to catch "feed ran but landed 0 rows" — a class of failure row-level expectations miss.

### Common Pitfalls to Avoid

- **No explicit failure outcome.** `[read]` leaving `on_failure` undecided means a fail-vs-quarantine surprise at the
  worst moment — choose per check (#5; the n8n "Continue On Fail" lesson).
- **Blocking where you should quarantine (and vice-versa).** `> GUESS:` blocking an ingestion-boundary check halts the
  whole DAG on a few bad rows; quarantining a promotion-boundary check publishes bad gold.
- **Designing around overwriting reports.** `[read]` reports are append-only by intent; trend/anomaly analysis needs
  the history (#5 fix-item).
- **Alert storms / over-gating.** `> GUESS:` validating every intermediate step floods alerts and slows the DAG;
  gate at contract boundaries (the n8n "alert storms" pitfall, in data-eng terms).
- **Too-tight expectations.** `> GUESS:` demanding 100% on noisy real data turns DQ into noise; use `mostly`/
  `threshold_percent`.

---

## 5. `# Best Practices: Orchestration`

> Scope: the 7 CONTROL blueprints (sensors + schedule/trigger + rollback + time-dimension advance + remote
> invocation). `[read]` Compute is **DAG-only** (`compute:null`, no column schema-effect); these are **DAG control
> facts**, not dataset ports — sensors expose a `ready_signal` output, schedule/rollback expose no ports (#5 CONTROL
> header). `[read]` One DAG per Business Pipeline with TaskGroups per sub-pipeline; PULSE triggers/observes Airflow,
> Airflow callbacks POST to PULSE (project CLAUDE.md).

### Workflow Design

Orchestration decides **when** a pipeline runs and **what it waits for** — the data steps decide *what* it does. Gate
data work behind the readiness signals it actually depends on: don't ingest before the file lands, don't model before
the source DB is loaded.

**CRITICAL — the activation analogue:** every Business Pipeline needs **exactly one** `ScheduleAndTriggers` to run on
a cadence (the data-eng form of n8n scheduling's `CRITICAL: "scheduled workflows only run in active mode"`). Without a
schedule/trigger, the DAG only runs on manual invoke. `[read]` `ScheduleAndTriggers` is portless — it's a DAG-level
schedule/trigger block, not a step in the data flow (#5 ScheduleAndTriggers).

**CRITICAL — timezone and business calendar:** `[read]` `timezone` and the holiday/fiscal calendar refs are
**system-derived from the domain calendar** (default IANA tz; ADR 0023) — do not hand-type them (#5
ScheduleAndTriggers, AdvanceTimeDimension). This is the data-eng counterpart of n8n scheduling's
`CRITICAL: "Explicitly set timezone…"` — in PULSE the domain owns the calendar so business-day logic
(holiday-aware SLAs, fiscal offsets) is consistent across the pipeline, not per-node guesswork.

**CRITICAL — sensors are guards, not data:** `[read]` `FileArrivalSensor` / `DatabaseReadinessSensor` /
`ExternalEventSensor` emit a `ready_signal` (a DAG dependency), not a dataset — wire the `ready_signal` to the data
step it gates, and the data step pulls the source itself (#5 sensors).

**CRITICAL — make re-runs safe:** `> GUESS:` set `catchup_enabled` / `depends_on_past` deliberately on
`ScheduleAndTriggers` — a backfill with `catchup` on can launch many historical runs at once; pair with
idempotent ingestion (§1). `max_active_runs` is system-derived (=1) to serialize by default (#5).

Example pattern (a guarded daily run):
- `ScheduleAndTriggers` (cron, domain tz) → `FileArrivalSensor` (ready_signal) → ingestion → … → sink →
  `AdvanceTimeDimension` (advance the business date) ; `RollbackOnFailure` attached for deploy-failure recovery.

### Sensor selection

`> GUESS:` choose the sensor by **what readiness means** for this dependency:
- A **file/object must exist** (and be the right size/age) → `FileArrivalSensor`.
- A **source DB must be loaded** (a probe query returns the expected count) → `DatabaseReadinessSensor`.
- An **external system signals done** (an endpoint returns success) → `ExternalEventSensor`.

### Recommended Blueprints

#### ScheduleAndTriggers

- **Purpose:** `[read]` the DAG-level schedule/trigger (cron / event / manual) (#5).
- **Configuration:** `[read]` `schedule_type` (cron/event/manual); for cron a `cron_expression`, for event a
  `trigger_dataset`; `catchup_enabled`, `depends_on_past`; `timezone`, `max_active_runs`, `retry_count`
  system-derived (#5).
- **Best practice:** `[read]` absorbs the deprecating `DatasetDependencySensor`'s triggers — use a
  `schedule_type=event` `trigger_dataset` instead of a separate dataset-dependency sensor (#5). `> GUESS:` validate
  cron expressions before relying on them (the n8n "use crontab.guru" lesson); use `event` triggers for
  data-dependency-driven runs rather than guessing a cron that "should" line up.

#### FileArrivalSensor

- **Purpose:** `[read]` wait for a file/object to arrive (and meet size/age guards) before proceeding (#5).
- **Configuration:** `[read]` `storage_kind` (s3/gcs/sftp), `bucket`, `path_prefix`, `filename_pattern` (`{date}`),
  `date_value` (mnemonic), `expected_max_age_hours` (stale-file guard), `multiple_files_mode`, `soft_fail`; poke/
  timeout/mode and calendar refs system-derived (#5).
- **Best practice:** `[read]` this **absorbs** the deprecating `ObjectStoreKeySensor` — use FileArrivalSensor, not the
  deprecated key sensor (#5). `> GUESS:` set `expected_max_age_hours` so a leftover old file from a prior day doesn't
  satisfy the sensor; use `soft_fail` when a missing file should skip (not fail) the run.

#### DatabaseReadinessSensor

- **Purpose:** `[read]` wait until a probe SQL returns the expected row count (#5).
- **Configuration:** `[read]` `sql` (probe, accepts `[[ … ]]` mnemonics), `expected_count_min` (default 1),
  `expected_count_max` (runaway-load guard); `connection_id` connector-derived; poke/timeout/mode and calendar refs
  system-derived (#5).
- **Best practice:** `> GUESS:` use `expected_count_max` to catch a runaway/duplicated load, not just `min`; keep the
  probe cheap (a `COUNT` on an indexed predicate), since it pokes on an interval.

#### ExternalEventSensor

- **Purpose:** `[read]` wait for an external endpoint to signal success (#5).
- **Configuration:** `[read]` `event_url`; `success_status_code` (default 200), poke/timeout system-derived (#5).
- **Best practice:** `> GUESS:` set a realistic `timeout` (system-derived 3600s default) so a never-arriving event
  doesn't hold the DAG indefinitely.

#### AdvanceTimeDimension

- **Purpose:** `[read]` advance the pipeline's business date/time dimension after processing (#5; `advance-time`).
- **Configuration:** `[read]` the genuine user surface is tiny — `target_scope` (dataset/domain, **Chat MUST ask,
  never silently default** — ADR 0023:11) and `advance_to` (date mnemonic or ISO; blank = next interval per grain).
  The other ~18 fields (state binding, calendar bundle/hash/id, grain, timezone, replay/concurrency/evidence policy)
  are **system-derived** from the dataset state binding + the domain calendar + platform defaults (#5; V132).
- **Best practice:** `[read]` wire its `trigger` input to the **last processing step** so the business date advances
  only after a successful run; `> GUESS:` leave `advance_to` blank for normal daily advance (next interval), set it
  only for an explicit catch-up/replay.

#### RollbackOnFailure

- **Purpose:** `[read]` recover on failure (e.g. a deploy failure) (#5; `rollback`).
- **Configuration:** `[read]` `rollback_trigger` (e.g. deploy_failure), `keep_failed_artifacts` (#5).
- **Best practice:** `> GUESS:` keep `keep_failed_artifacts=true` while stabilizing a pipeline (you'll want the
  evidence); note rollback is redeploy-last-good, consistent with PULSE's package/promotion model.

#### RemotePipelineInvocation

- **Purpose:** `[read]` invoke/trigger another pipeline remotely (a control dependency, not a data port) (#5; the
  39th survivor, sourced from V131; `broker.remoteInvocation.configure`).
- **Configuration:** `[read]` per V131 (the broker/target invocation refs). `> GUESS:` use it to chain Business
  Pipelines (one pipeline's completion triggers another) rather than cramming two domains into one DAG.
- **Best practice:** `> GUESS:` prefer an `event`-triggered `ScheduleAndTriggers` on the downstream pipeline when the
  dependency is data-arrival; reserve remote invocation for true cross-pipeline orchestration.

### Common Pitfalls to Avoid

- **No schedule/trigger.** `[read]` a pipeline with no `ScheduleAndTriggers` only runs on manual invoke (the n8n
  "forgot to activate" pitfall) — add exactly one (#5).
- **Hand-typed timezone/calendar.** `[read]` tz and holiday/fiscal refs are domain-derived; hand-typing them
  desyncs business-day logic across the pipeline (#5; ADR 0023).
- **Unguarded data steps.** `> GUESS:` ingesting/modeling before the dependency is ready yields empty/partial output;
  gate behind the matching sensor.
- **Catchup surprises.** `> GUESS:` `catchup_enabled` on a backfill launches many historical runs at once; pair with
  idempotent ingestion and consider `depends_on_past`.
- **Advancing the time dimension before success.** `[read]` wire `AdvanceTimeDimension.trigger` to the last
  processing step, not in parallel — advancing on a failed run corrupts the as-of date (#5).
- **Stale-file false positives.** `> GUESS:` a `FileArrivalSensor` without `expected_max_age_hours` can be satisfied
  by yesterday's leftover file.

---

## 7. PULSE data-engineering TECHNIQUE guides

> **Scope.** The technique guides are the PULSE analogue of n8n's per-technique `get_documentation` content
> (`type:"best_practices", techniques:[...]` — §0.3, 02 §4 row 5). They are **cross-category** — each describes a
> recurring data-engineering PATTERN that spans several blueprints/categories, not a single blueprint. They are injected
> alongside the category guides (§3 of the open worklist applies equally) and are **selected by the technique(s) in
> play** (e.g. a pipeline that builds a dimension with history pulls the SCD2 guide). Each follows the §0.2 shape:
> `## Workflow Design` (with **CRITICAL** rules) → a recommended approach → `## Common Pitfalls to Avoid`. Every
> blueprint key, param, and mnemonic is `[read]` from #5 (the §1–§5 guides above already cite the exact rows) or from
> `PulseSystemPrompt.java:405-434` (the date-mnemonic facility); pattern/recommendation lines are the author's
> data-engineering judgment, tagged `> GUESS:` and targeted at the operator voice review.

> GUESS: the **set** of techniques chosen here (SCD2/history, medallion layering, incremental/CDC merge,
> schema-contract evolution, partitioning & periodicity, deduplication, DQ-gating at layer boundaries, late-arriving
> data, backfill/replay) is the operator's §4.5 list; n8n's technique TAXONOMY (chatbot, enrichment, triage, …) does
> NOT map to data engineering, so this is a PULSE-native technique vocabulary. Operator/SPEC-GATE to confirm the set is
> complete (candidate additions: PII-masking strategy, multi-source conforming, streaming micro-batch sizing).

---

### 7.1 `# Technique: SCD2 / history strategy`

#### Workflow Design

History strategy is the decision of HOW you record change in a dimension over time, and it is **hard to change after
data accumulates** — so decide it before you build the model, not after. PULSE gives you three deterministic history
strategies, each a real blueprint:

- **Track every change with valid-from/valid-to** (full Type-2 history) → `SCD2Dimension` (`[read]` #5: dbt-snapshot;
  adds `dbt_valid_from`/`dbt_valid_to`/`dbt_scd_id`/`dbt_updated_at`).
- **Keep a periodic full picture** (point-in-time snapshots, no per-row versioning) → `SnapshotModel` (`[read]` #5:
  dbt-incremental).
- **Keep only the latest state** (no history at all) → `IncrementalMerge` (`[read]` #5: `merge-rows` upsert).

**CRITICAL:** declare the **business key** explicitly — `[read]` `SCD2Dimension.business_key[]` is what history hangs
on; a wrong or missing key silently corrupts every version chain (#5 SCD2Dimension). The business key is the natural
key of the entity (`customer_id`, `account_id`), NOT a surrogate.

**CRITICAL:** track only **business-meaningful** columns — `[read]` `SCD2Dimension.tracked_columns[]` decides what
opens a new version; tracking a volatile column (a `last_login_ts`) explodes version churn and bloats the dimension
(#5 SCD2Dimension). Track the attributes whose change you would actually want to query "as of date X."

**CRITICAL:** `> GUESS:` pick **one** strategy per entity. Running SCD2 AND a separate SnapshotModel on the same
dimension duplicates history and confuses lineage — choose by the QUESTION you must answer: "what did it look like
on date X" → SCD2; "what was the full population each period" → SnapshotModel; "what is it now" → IncrementalMerge.

**CRITICAL:** `[read]` do NOT add your own versioning columns — the blueprint owns them (SCD2 owns the four `dbt_*`
columns; SnapshotModel owns `ds` + `_pulse_*`). Adding hand-rolled `valid_from`/`version` columns collides with the
blueprint (#5).

Recommended approach (a conformed dimension with history): silver `customer` → `SCD2Dimension`
(`business_key=customer_id`, `tracked_columns=[address, risk_tier, segment]`) → gold `dim_customer`. `> GUESS:` feed
SCD2 from a daily `SnapshotIngestion` when you have no CDC stream — a daily snapshot + SCD2 gives deterministic
history without CDC infrastructure (#5 SnapshotIngestion best-practice).

#### Common Pitfalls to Avoid

- **Tracking volatile columns.** `> GUESS:` a timestamp in `tracked_columns` opens a new version on every run — bloats
  the dimension and makes history meaningless.
- **Wrong/surrogate business key.** `[read]` SCD2 hangs history on `business_key[]`; a surrogate or a non-unique key
  corrupts the chain (#5).
- **Mixing strategies on one entity.** `> GUESS:` SCD2 + SnapshotModel on the same dimension = duplicated, conflicting
  history.
- **Hand-rolled version columns.** `[read]` the blueprint owns `dbt_valid_from`/`dbt_valid_to`/...; your own collide (#5).
- **Choosing history you don't need.** `> GUESS:` if no one asks "as of date X," SCD2 is cost with no payoff — use
  IncrementalMerge.

---

### 7.2 `# Technique: medallion layering`

#### Workflow Design

The medallion model (**bronze → silver → gold**) is PULSE's spine: each layer has a job, and skipping a layer
re-introduces the work it does. `[read]` PULSE blueprints carry a declared layer (INGESTION lands bronze; TRANSFORM
lands silver; MODELING lands gold — #5 category headers), and the layer is part of every step's contract.

**CRITICAL — bronze is faithful, not clean:** `[read]` ingestion lands a faithful, append-friendly copy of the source
with the 8-column audit set added and **no business logic** (#5 §1 Workflow Design). Do not trim/cast/dedupe/join in
bronze — that erases the source fidelity you need to re-derive everything downstream.

**CRITICAL — a writer/sink at EVERY layer boundary:** `[read]` PULSE's composition rule (D1-FEEDBACK §C / §F Planner
RULES) is a **writer at every medallion-layer boundary** (bronze→silver→gold). Each layer materializes to a real table
(the sink/writer blueprints, #5 §2 tail) so the next layer reads a stable, queryable input — not an un-persisted
in-memory frame. Never chain bronze straight into a gold model with no silver materialization between.

**CRITICAL — a DQ step after every write:** `[read]` the companion composition rule (§F Planner RULES) is a **DQ step
after every write** — gate the data you are about to trust at each boundary (`DQValidator`, #5 §4). This is the
data-eng form of "validate before you build on it."

**CRITICAL — read direction is one-way:** `> GUESS:` gold reads conformed silver (or other gold for marts), never raw
bronze directly (#5 §3 pitfall). Reading bronze from gold skips the cleaning silver does and re-introduces the dirt.

Recommended approach (the canonical lending feed):
`FileArrivalSensor` → `FileIngestion` (bronze) → `WarehouseWriter`/`LakeWriter` (materialize bronze) →
`DQValidator` (gate) → `BronzeToSilverCleaning` → `SchemaNormalization` (silver) → writer → `DQValidator` →
`SCD2Dimension`/`FactBuild` (gold) → writer → `DQValidator` → sink.

#### Common Pitfalls to Avoid

- **Cleaning in bronze.** `> GUESS:` destroys re-derivability — push cleaning into silver (`BronzeToSilverCleaning`, §2).
- **Skipping silver.** `> GUESS:` bronze→gold directly re-introduces the cleaning/conforming silver owns.
- **No writer at a boundary.** `[read]` violates the §F composition rule — the next layer has no stable input.
- **No DQ after a write.** `[read]` violates the §F composition rule — you build gold on unvalidated silver.
- **Cross-layer back-reads.** `> GUESS:` a gold model reading bronze is a lineage smell — re-route through silver.

---

### 7.3 `# Technique: incremental / CDC merge`

#### Workflow Design

Incremental processing means you process only what changed since last run, then **merge** it into the target — instead
of reprocessing the whole history every run. PULSE expresses this with `CDCIngestion` (capture the change) +
`IncrementalMerge` (apply it), plus the incremental modes on `BulkBackfill`/`FactBuild`.

**CRITICAL — declare the merge key:** `[read]` `IncrementalMerge.merge_key[]` is the upsert key; a wrong key
double-counts or overwrites the wrong rows (#5 IncrementalMerge). It must uniquely identify a target row at the
target's grain.

**CRITICAL — decide delete semantics deliberately:** `[read]` `CDCIngestion.delete_handling` (e.g. soft_delete) and
`IncrementalMerge.soft_delete` decide whether a deleted source row becomes a tombstone or vanishes — silver/gold
semantics depend on it (#5 CDCIngestion, IncrementalMerge). `> GUESS:` prefer `soft_delete` when any downstream needs
the tombstone for reconciliation; hard delete only when you are certain nothing audits removals.

**CRITICAL — make the increment idempotent:** `[read]` drive the increment off a **mnemonic** watermark, not a
hard-coded date — `CDCIngestion.incremental_column`/`watermark_column` for poll-mode CDC, and date mnemonics
(`PBD` for "last business day", `RUN_DATE`) for date-windowed increments, so a re-run for the same window reproduces
the same merge (`[read PulseSystemPrompt.java:405-434,426]`; #5 CDCIngestion).

**CRITICAL — incremental needs a time column:** `[read]` `FactBuild.incremental` + `time_column` make a large fact
incremental; without the time column the "incremental" run silently rebuilds everything (#5 FactBuild).

Recommended approach (operational table → gold latest-state):
`CDCIngestion` (`cdc_mode=incremental_poll`, `incremental_column=updated_at`, `delete_handling=soft_delete`) →
bronze writer → `DQValidator` → `BronzeToSilverCleaning` → `IncrementalMerge` (`merge_key=[account_id]`,
`soft_delete=true`, `late_data_policy` set — see §7.8) → gold.

#### Common Pitfalls to Avoid

- **Wrong/non-unique merge key.** `[read]` `IncrementalMerge` double-counts or clobbers with the wrong key (#5).
- **Undecided delete handling.** `[read]` leaving `delete_handling`/`soft_delete` to default surprises downstream
  reconciliation (#5).
- **Hard-coded watermark.** `[read]` an absolute date makes a re-run reprocess the wrong window — use a mnemonic
  (`PulseSystemPrompt.java:426`).
- **"Incremental" with no time column.** `[read]` `FactBuild.incremental` without `time_column` rebuilds the whole fact (#5).
- **Plain JDBC read mislabeled as CDC.** `[read]` #5 flags today's CDC codegen as a plain read with no change-column —
  judge on intent, set the change semantics deliberately (#5 CDCIngestion fix-item).

---

### 7.4 `# Technique: schema-contract evolution`

#### Workflow Design

A dataset's schema is a **contract** other steps depend on — `[read]` schema contracts are required for promotion to
integration+ (project CLAUDE.md; ADR 0011). Evolving that contract safely is its own technique: PULSE classifies an
edit as non-breaking / partial / breaking from how downstream CONSUMES the column (the conflict overlay,
SPEC-ui-composition.md §2).

**CRITICAL — prefer additive change:** `[read]` adding a column is non-breaking; renaming or dropping a CONSUMED column
is breaking (SPEC-ui-composition.md §2; #5 §2 "schema & contract discipline"). When you must change a contract, add the
new column and migrate consumers, rather than renaming in place.

**CRITICAL — conform ONCE, early:** `[read]` do the rename/cast in `SchemaNormalization` once, then keep silver names
and types stable so downstream consumers don't break on every edit (#5 SchemaNormalization, §2). Re-conforming the same
dataset in multiple steps creates drift.

**CRITICAL — detect drift, don't discover it in prod:** `[read]` `SchemaDriftDetection` asserts an expected baseline
and emits an **append-only** report when incoming columns diverge (#5 SchemaDriftDetection). `> GUESS:` keep
`allow_extra_columns=true` for additive sources, `false` for strict contracts; route a drift to an OpenMetadata-driven
**migration-plan proposal** (a drift → proposal, not just an alert — #5 §4 "DQ readiness & OpenMetadata").

**CRITICAL — read the real schema before you build on it:** `[read]` configure against the actual upstream columns
(`get_step_schema`), never assumed names — silver columns may already be renamed/cast (#5 §2; ADR 0011 schema is
rule/declared/discovery, never an LLM guess).

Recommended approach (evolving a silver contract): land the new source column additively in
`BronzeToSilverCleaning` → expose it in `SchemaNormalization` (`strict_mode=false` while migrating) →
`SchemaDriftDetection` (baseline updated) → migrate consumers → tighten `strict_mode=true` when the contract is final.

#### Common Pitfalls to Avoid

- **Renaming/dropping a consumed column.** `[read]` that's a breaking change in the conflict overlay — add, don't
  rename (SPEC-ui-composition.md §2).
- **Re-conforming in multiple steps.** `[read]` normalize once in `SchemaNormalization`; repeated conforming drifts (#5).
- **Configuring against assumed columns.** `[read]` read `get_step_schema`; silver may already be reshaped (#5 §2).
- **Drift report that overwrites.** `[read]` reports are append-only by intent — trend analysis needs the history
  (#5 fix-item).
- **Treating drift as only an alert.** `> GUESS:` a drift can FEED a migration proposal via OpenMetadata, not just page
  someone.

---

### 7.5 `# Technique: partitioning & periodicity`

#### Workflow Design

Partitioning (how the table is physically split) and periodicity (the dataset's time grain + how the business date
advances) are coupled in PULSE: the time grain drives the partition column, the sensor cadence, and the filename
format. `[read]` `partition_by`/`cluster_by` are **system-derived** — a storage convention from the dataset's grain and
keys (#5 §0.2; default `["ds"]` on most rows). Do NOT hand-type them unless you have a measured performance reason.

**CRITICAL — partition is system-derived, leave it alone:** `[read]` editing `partition_by`/`cluster_by` fights the
storage authority (they are inspector-readonly, derived from grain/keys — #5 §0.2). The available partition transforms
are themselves Mode-bound (`[read]` GCP: identity/year/month/day/hour/truncate/bucket; DPC: identity/year/month/day —
`RuntimeAuthorityService` persona presets), which is exactly why they are system-resolved, not user-typed.

**CRITICAL — align grain across the chain:** `[read]` a daily ingestion partitioned by `ds`, a daily backfill, and a
daily fact must share the same partition grain so backfilled and incremental partitions are interchangeable (#5
BulkBackfill best-practice). `> GUESS:` a grain mismatch (daily ingestion, monthly fact) makes incremental refreshes
reprocess far more than they should.

**CRITICAL — periodicity drives the sensor and the filename:** `[read]` the dataset's `time_grain`
(DAILY | DAILY_BUSINESS_DAY | WEEKLY | MONTHLY | HOURLY | REAL_TIME — `[read ChatTools.java:80-81]`) infers the sensor
pattern and the strftime filename format; the business calendar comes from the domain (#5 §5 Orchestration; project
CLAUDE.md "Periodicity"). Use a **business-day** grain (and `PBD`/`NBD` mnemonics) for business-calendar feeds so a
weekend/holiday gap is not a false "missing partition."

**CRITICAL — partition by the column you filter on:** `> GUESS:` the partition column should be the one downstream
predicates and incremental merges filter on (usually the business date `ds`), so partition pruning actually fires.

Recommended approach (a daily business-day feed): dataset `time_grain=DAILY_BUSINESS_DAY` →
`FileArrivalSensor` (`filename_pattern` with `PBD` substitution) → `FileIngestion` (partition `["ds"]`,
`date_value=RUN_DATE`) → downstream all keyed on the same `ds` grain.

#### Common Pitfalls to Avoid

- **Hand-typing partition/cluster.** `[read]` system-derived, inspector-readonly — overriding fights the storage
  authority (#5 §0.2).
- **Grain mismatch across the chain.** `> GUESS:` daily ingestion into a monthly model reprocesses too much.
- **Calendar-blind SLAs.** `> GUESS:` a plain DAILY grain false-alarms on weekends — use DAILY_BUSINESS_DAY + business-day
  mnemonics (`PulseSystemPrompt.java:422`).
- **Over-partitioning / small files.** `> GUESS:` too-fine a partition (e.g. hourly on a daily feed) fragments storage;
  match partition grain to the query grain.
- **Partitioning on a non-filter column.** `> GUESS:` partition pruning never fires; you scan everything.

---

### 7.6 `# Technique: deduplication`

#### Workflow Design

Deduplication removes duplicate rows, but "which duplicate survives" is a **business decision** that must be
deterministic — an arbitrary survivor makes silver non-reproducible. PULSE has two dedupe surfaces:
`BronzeToSilverCleaning.dedup_key` (inline, simple) and `DedupeAndMerge` (dedicated, with tie-break + merge strategy).

**CRITICAL — make the survivor deterministic:** `[read]` set `DedupeAndMerge.order_by_columns`
(`[{column,direction}]`) so the winning row is defined; a silent arbitrary tie-break produces non-reproducible silver
(#5 DedupeAndMerge). The same applies to `BronzeToSilverCleaning.dedup_key` — pair it with a deterministic ordering.

**CRITICAL — dedupe on the right key:** `[read]` `DedupeAndMerge.match_keys[]` is the dedup partition; choose the
columns that define a logical duplicate, not the whole row (#5). `> GUESS:` deduping on every column (`DISTINCT *`)
misses "same entity, one field changed" duplicates — partition on the entity key.

**CRITICAL — dedupe early, in silver, not gold:** `> GUESS:` dedupe at the bronze→silver hop so every downstream
consumer sees clean rows; deduping per-consumer in gold repeats the work and risks divergent results (#5 §2 ordering).

**CRITICAL — dedupe ≠ history:** `> GUESS:` deduplication collapses duplicates to one row; if you actually need to KEEP
the different versions over time, that is SCD2 (§7.1), not dedupe. Don't dedupe away change you need to track.

Recommended approach (a feed with vendor re-sends): bronze → `BronzeToSilverCleaning` (basic clean) →
`DedupeAndMerge` (`match_keys=[loan_id]`, `order_by_columns=[{_pulse_ingested_at, desc}]`,
`match_strategy=exact`) → silver.

#### Common Pitfalls to Avoid

- **Non-deterministic tie-break.** `[read]` no `order_by_columns` = arbitrary survivor = non-reproducible silver (#5).
- **`DISTINCT *` deduping.** `> GUESS:` misses entity-level duplicates where one field differs — partition on the key.
- **Deduping in gold per-consumer.** `> GUESS:` repeated work + divergent results — dedupe once in silver.
- **Deduping away history you need.** `> GUESS:` if versions matter, that's SCD2, not dedupe.
- **Fuzzy match without review.** `> GUESS:` `match_strategy=fuzzy` can collapse genuinely distinct rows — validate the
  match rate before trusting it.

---

### 7.7 `# Technique: DQ-gating at layer boundaries`

#### Workflow Design

DQ-gating is the technique of placing data-quality checks WHERE a contract must hold and choosing the right FAILURE
OUTCOME there — it is the runtime enforcement of the §7.2 "DQ after every write" rule. `[read]` `DQValidator` is the
gate (validated port + quarantine port); `FreshnessChecks`/`SchemaDriftDetection`/`AnomalyDetection` are signals
(append-only reports) (#5 §4).

**CRITICAL — gate at boundaries, not everywhere:** `[read]` validate the inputs you are about to TRUST — the
bronze→silver boundary (is the raw feed usable?) and the silver/gold boundary (does curated output meet the contract
before publish/promotion?) — not every intermediate step (#5 §4 Workflow Design). Over-gating floods alerts and slows
the DAG.

**CRITICAL — choose the failure outcome per check:** `[read]` `DQValidator.on_failure` is the outcome control —
**quarantine** (route failing rows aside, keep good rows flowing), **block** (FAIL raises and fails the Airflow run),
or **warn** (record + continue) (#5 DQValidator). `> GUESS:` default **quarantine** at the ingestion boundary (keep the
pipeline moving, isolate bad rows) and **block** at the promotion boundary (never publish bad gold).

**CRITICAL — block where a failed run is the correct response:** `[read]` `on_failure=block` fails the run — wire it
where stopping is right (before a publish), not where it would needlessly halt a recoverable pipeline (#5 DQValidator).

**CRITICAL — start small, use `mostly`:** `> GUESS:` a few high-value expectations (not-null on keys, accepted-values
on enums, range on amounts) beat dozens of brittle ones; set `DQValidator.mostly`/`threshold_percent` for noisy
real-world data instead of demanding 100% (#5 DQValidator).

Recommended approach (gate a feed, then publish): silver → `DQValidator` (`on_failure=quarantine`) →
`SchemaDriftDetection` (report) → gold model → `DQValidator` (`on_failure=block`) → sink.

#### Common Pitfalls to Avoid

- **No explicit failure outcome.** `[read]` undecided `on_failure` = fail-vs-quarantine surprise at the worst moment (#5).
- **Blocking where you should quarantine (and vice-versa).** `> GUESS:` blocking an ingestion check halts the DAG on a
  few bad rows; quarantining a promotion check publishes bad gold.
- **Over-gating.** `> GUESS:` validating every step floods alerts and slows the DAG — gate at boundaries.
- **Too-tight expectations.** `> GUESS:` 100% on noisy data turns DQ into noise — use `mostly`/`threshold_percent`.
- **Designing around overwriting reports.** `[read]` reports are append-only by intent (#5 fix-item).

---

### 7.8 `# Technique: late-arriving data`

#### Workflow Design

Late-arriving data is the row that shows up AFTER the window it belongs to has already been processed — a fact for
business-date D that lands on D+2, a dimension member referenced by a fact before the dimension row exists. Handling it
is a deliberate policy, not an afterthought.

**CRITICAL — set the late-data policy explicitly:** `[read]` `IncrementalMerge.late_data_policy` +
`late_threshold_hours` define how out-of-order arrivals are handled (#5 IncrementalMerge). `> GUESS:` decide whether a
late row updates its original business-date partition (correct history, more reprocessing) or lands in the current
partition (cheaper, but the as-of view drifts) — and set the threshold to your real SLA.

**CRITICAL — idempotent re-processing makes late data safe:** `> GUESS:` if a re-run for business-date D reproduces D's
partition deterministically (mnemonic-driven dates, stable dedupe — §7.3/§7.5/§7.6), then handling a late D-row is just
a re-run of D's window. Non-idempotent steps make late data corrupting (#5 §1 idempotency CRITICAL).

**CRITICAL — late dimensions vs late facts differ:** `> GUESS:` a late FACT is a merge/partition concern
(`IncrementalMerge.late_data_policy`); a late DIMENSION member (a fact references a not-yet-existing dimension row)
needs either a placeholder/inferred member or a re-run after the dimension lands. Don't drop the fact silently.

**CRITICAL — freshness is the detector:** `[read]` `FreshnessChecks` (with `max_age_business_days` for
business-calendar feeds) is how you DETECT that expected data is late before it silently skews downstream (#5
FreshnessChecks). Wire it so a late feed alarms rather than quietly producing a thin partition.

Recommended approach (a fact that can arrive late): `IncrementalMerge` (`late_data_policy` set,
`late_threshold_hours=72`, `soft_delete=true`) gated by a `FreshnessChecks` (`max_age_business_days`) report; pair
with idempotent mnemonic-driven ingestion (§7.3) so reprocessing a late window is safe.

#### Common Pitfalls to Avoid

- **No late-data policy.** `[read]` `IncrementalMerge` defaults silently decide where late rows land (#5).
- **Non-idempotent windows.** `> GUESS:` if a re-run doesn't reproduce the partition, late data corrupts it.
- **Dropping late facts on a missing dimension.** `> GUESS:` silently discarded rows = silent data loss — use a
  placeholder or re-run.
- **No freshness detector.** `[read]` without `FreshnessChecks` a late feed produces a thin partition unnoticed (#5).
- **Threshold mismatched to SLA.** `> GUESS:` a 1-hour threshold on a feed that's routinely a day late fights itself.

---

### 7.9 `# Technique: backfill / replay`

#### Workflow Design

Backfill is loading history you don't have yet (first-load, or re-deriving after a source correction); replay is
re-running a window you already ran. Both must be **bounded, idempotent, and partition-aligned** so they don't double-
load or clobber good data.

**CRITICAL — use the surviving backfill blueprint:** `[read]` `BulkBackfill` is the surviving backfill blueprint — do
NOT use the deprecating `BackfillAndReplay` (#5 BulkBackfill notes). It does a bounded historical reload over a date
range into bronze.

**CRITICAL — bound the window with mnemonics, not hard dates:** `[read]` `BulkBackfill.date_range_start`/`date_range_end`
accept date mnemonics (`[read PulseSystemPrompt.java:405-434]`) — `BOM-12`/`EOM-1` for "last 12 full months",
`BOM-1`/`EOM-1` for "last month" (`PulseSystemPrompt.java:426-428`). A mnemonic window stays correct on every run; a
hard-coded `2025-04-01..2026-03-31` rots immediately (`PulseSystemPrompt.java:407`).

**CRITICAL — align the backfill grain to the daily grain:** `[read]` keep the backfill's partition grain identical to
the daily ingestion's so backfilled and incremental partitions are interchangeable (#5 BulkBackfill best-practice; see
§7.5). A grain mismatch makes the backfilled history un-mergeable with the live feed.

**CRITICAL — idempotent replay over destructive reload:** `> GUESS:` a replay should re-derive a partition
deterministically (mnemonic dates, stable dedupe/merge keys — §7.3/§7.6), so re-running a window REPLACES it cleanly
rather than appending duplicates. Pair with `IncrementalMerge`/`write_mode=overwrite_partition` so the replay overwrites
exactly the target partition, not the whole table.

**CRITICAL — catchup is a separate, explicit decision:** `[read]` on `ScheduleAndTriggers`, `catchup_enabled` on first
deploy schedules one run for EVERY interval from the start date to today — a backfill-by-catchup can launch many
historical runs at once (#5 §5 Orchestration "catchup surprises"; 02 §1 `set_pipeline_setting` catchup note). Treat it
as a deliberate choice, pair with idempotent ingestion and consider `depends_on_past`.

Recommended approach (first-load then daily): one-time `BulkBackfill` (`date_range_start=BOM-12`,
`date_range_end=EOM-1`, partition grain = `["ds"]` matching the daily feed) → bronze writer → DQ → silver/gold;
then the daily `FileIngestion` path takes over with `catchup_enabled=false`.

#### Common Pitfalls to Avoid

- **Using the deprecated `BackfillAndReplay`.** `[read]` use `BulkBackfill` instead (#5).
- **Hard-coded backfill window.** `[read]` a literal date range rots — use `BOM-N`/`EOM-N` mnemonics
  (`PulseSystemPrompt.java:407,428`).
- **Grain mismatch with the daily feed.** `[read]` backfilled partitions become un-mergeable (#5 BulkBackfill; §7.5).
- **Append-mode replay.** `> GUESS:` replaying with append duplicates rows — overwrite the target partition.
- **Catchup surprise.** `[read]` `catchup_enabled=true` launches many historical runs at once — make it explicit (#5 §5).

---

## 6. Open worklist (operator voice review)

1. **Resolve the §0.3 divergences (the prior n8n prompt summary §3 is removed; carry these corrections forward into this fragment):** 18→17 guides; the SDK-path/`.ee`-origin move;
   `## Condition Logic` is not universal; Purpose/Use cases/Configuration/Best practice is "0–4 + extras"; add
   `## Common Pitfalls to Avoid` to the documented shape.
2. **Voice + claim review:** every `> GUESS:` "Best practice"/"Use cases"/pattern line is the author's data-eng
   judgment — confirm or correct in operator voice. Highest-leverage: the DQ outcome defaults (quarantine at
   ingestion boundary, block at promotion boundary); the modeling history-strategy decision tree; the
   one-schedule-per-pipeline rule.
3. **Injection mechanics (defer to SPEC-ui-composition.md §7.14):** confirm these five guides are injected per the
   composer stage's `Best Practices:` header, whether all five are always injected or selected by the categories in
   play, and the token budget (the dump-all catalog is already cached — these guides add ~5 sections).
4. **Title suffix:** keep `# Best Practices: <Category>` (no "Workflows" suffix) — confirm the cosmetic choice.
5. **Scope folding:** confirm SINK→Transform-tail and CONTROL→Orchestration foldings (so the five-guide,
   five-category mapping covers all 41 blueprints without a sixth/seventh guide).
6. **Technique guides (§7 — NEW per D1-FEEDBACK §C / §4.5):** confirm the **set** of 9 techniques (SCD2/history,
   medallion layering, incremental/CDC merge, schema-contract evolution, partitioning & periodicity, deduplication,
   DQ-gating at boundaries, late-arriving data, backfill/replay) is complete (candidate additions flagged: PII-masking
   strategy, multi-source conforming, streaming micro-batch sizing). Voice-review every `> GUESS:` pattern line —
   highest-leverage: the late-data partition policy (original vs current partition), the dedupe-vs-SCD2 boundary, and
   the catchup-is-explicit rule. Confirm the **selection** mechanic (technique guides injected by the technique(s) in
   play, alongside the category guides) and the added token budget.
