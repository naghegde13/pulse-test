# SPEC #4 — Construct Library (the reusable purpose-built UI controls the bespoke panels assemble from)

> **Status: DRAFT — grilling in progress (started 2026-06-16).** This is the shared toolkit of smart, domain-specific
> input controls. #3 (composition + Chat) lays these out into each Blueprint's bespoke panel; #5 (catalog) names,
> per param, WHICH construct renders it (the "UI-construct hint"). Must pass the SPEC-GATE (ADR 0010) before build.
> Evidence: `[read]` = verified at the cited path this session.

## Existing inventory (verified — most of this is EXTEND/POLISH, not greenfield)

- `[read]` **Expression builder EXISTS** — `frontend/src/components/pipeline/expression-input.tsx`, backed by
  `ExpressionController` (`/api/v1/expressions`) + `ExpressionValidationService` (parse-only Calcite Babel validator,
  `ExpressionValidationService.java:99-113`; returns `outputType="unknown"` today — its named "Phase 2" is the
  schema-deriving upgrade = CALCITE-PHASE-2, `SPEC-calcite-sql-model.md`). → **POLISH, don't rebuild.**
- `[read]` **A SQL/filter builder EXISTS (partial)** — `frontend/src/components/pipeline/sql-filter-builder.tsx`
  (+ `configure-transform-dialog.tsx`). The full `sql-builder` below EXTENDS this.
- `[read]` A **Spark-function list** exists in **exactly ONE** copy — `SPARK_FUNCTIONS`
  (`frontend/src/components/pipeline/sql-filter-builder.tsx:47-184`). Neither `composition-panel.tsx` nor
  `configure-transform-dialog.tsx` defines or imports a Spark-function list (verified by grep this session).
  > RESOLVED (operator 2026-06-16): the earlier "three copies" claim was FALSE — there is **one** copy
  > (`sql-filter-builder.tsx:47-184`). The registry-fork risk is forking a *second* copy when constructs EXTEND;
  > the mitigation (single owner, import everywhere; reconcile to the Calcite function registry, Calcite spec G-1)
  > still holds.

## The shared control set

1. **`sql-builder` — TWO variants** (operator 2026-06-16; dialect axis = Spark vs source-DB):
   - **1a. Rich SQL builder (Spark dialect)** — for **SqlModel** (in-pipeline). Extends `sql-filter-builder.tsx`.
     **Spark-function palette** (ALL Spark SQL functions — `len(str)`, `coalesce`, `date_add`, window funcs; single
     source of truth = the Calcite function registry, Calcite spec G-1; unknown → loud fail), autocomplete,
     **Calcite-backed live validation** (`/api/v1/expressions` extended to the schema-deriving CALCITE-PHASE-2), the
     **multi-step `steps:[{name,sql,materialize}]` chain editor** with per-step `materialize` toggle,
     **`[[ … ]]` mnemonic insertion**, and **per-step DATA preview** (operator 2026-06-16) — run each step on a
     cached input sample and show the result rows at every step in the chain (see "Preview execution" below).
     PULSE owns the one Spark dialect.
   - **1b. Simple SQL builder (source-DB dialect)** — for **SourceSQL** (`source_query`). A plain SQL editor + a
     **Validate button ONLY**, which round-trips to the bound JDBC source (prepare / `getMetaData`): **the source DB
     validates its own dialect + functions and returns the result schema.** NO rich function palette, NO per-dialect
     support (PULSE does NOT build Oracle/Postgres/SQL-Server/Teradata parsers). `[[ … ]]` mnemonics still supported
     (substituted to a dummy `DATE` before the prepare). Plus the connector/dataset picker.
   - **NOTE:** pyspark-vs-dbt is NOT a dialect split — both are Spark SQL; dbt only adds a Jinja layer handled by the
     `[[ … ]]` lowering. The real split is **in-pipeline-Spark (SqlModel) vs source-DB (SourceSQL)**.
2. **`expression-builder`** (EXISTS — `expression-input.tsx`; POLISH) — single-expression authoring (derived columns,
   filters); Calcite-backed validation already wired; add `[[ … ]]` mnemonic insertion + polish.
3. **`column-picker`** — pick column(s) from the live input schema.
4. **`rename-mapper`** — old → new column-name mapping.
5. **`condition-builder`** — build a filter / WHERE condition.
6. **`sensing-config`** — file / sql-query / trigger sensing config.
7. **`dq-outcome-control`** — quarantine vs report vs fail-job.
8. **`date-mnemonic-picker`** — pick a `PBD`/`BOM`/`RUN_DATE` mnemonic (the closed vocabulary, `DateMnemonic.java`).

## RESOLVED — the dialect axis (operator 2026-06-16)

> Two `sql-builder` variants (§1 above): **rich (Spark) for SqlModel**; **simple validate-only (source-DB) for
> SourceSQL**, where the source DB validates its own SQL via JDBC prepare/`getMetaData`.
>
> **RIPPLE to apply to ADR 0024 + the Calcite spec (queued — after the in-flight re-gate):** the **Calcite validator
> becomes `sql-model`-only.** SourceSQL's output schema is derived by **asking the source** (JDBC prepare/metadata),
> NOT by Calcite-over-the-discovered-catalog. So drop the SourceSQL/discovered-catalog path from the Calcite spec
> (§A.1's second catalog context + §C.2) and update ADR 0024's SourceSQL decision (Calcite → in-pipeline only; source
> validation → the source). This is a net simplification of the Calcite validator (one catalog context: `input` +
> prior steps).

## Preview execution — per-step data preview (operator 2026-06-16)

> The rich SQL builder (1a) previews ACTUAL result rows at **each step** of a SqlModel chain. Design:
> - **Engine = the same one the pipeline targets (Spark), dev-builder-only.** The preview MUST match what the real
>   run produces, so it executes the Spark SQL on **dev Spark** (local or dev Dataproc) — NOT a divergent local
>   engine (e.g. DuckDB), which would give previews that differ from the real run.
> - **Sample, cached.** Sample the chain's input ONCE (top-N rows, cached for the session); run the chain
>   **incrementally** on that cached sample — step N's preview = the cached sample through steps 1..N. Small sample
>   → fast/interactive; same engine → faithful.
> - **`[[ … ]]` mnemonics** resolve against the dev business date (or a user-picked as-of) for the preview.
> - **Design-time + dev-only** (like discovery); no bearing on the immutable package or runtime (no phone-home).
> - **Backend:** a design-time preview endpoint `(input-dataset sample, chain steps 1..N) → top-N result rows +
>   schema` — enumerate it in #3's backend API surface.
> - `> CONFIRM:` the engine choice (dev-Spark-on-cached-sample). Tradeoff = interactivity (Spark startup latency,
>   mitigated by the cached small sample) vs fidelity (exact match to the real run). Recommend **fidelity**.
> - (SourceSQL's simple builder can offer a single-query preview too — `SELECT … LIMIT N` against the source — but
>   the per-step chain preview is a SqlModel/rich-builder feature.)

## The remaining constructs (authored to depth)

> Each subsection below is one construct from the control set (§3–§8). The `sql-builder` (§1), `expression-builder`
> (§2), and per-step "Preview execution" are specified above — NOT repeated here. Every #5 UI-construct hint that points
> at one of these six is enumerated under (b). Where a real frontend component already covers the shape, the construct
> is **EXTEND-<file>** (`[read]` = the file was read this session); where today's render is a raw JSON `<Textarea>` /
> generic `<Select>` fallback, it is **BUILD-NEW**.
>
> **One-time finding that applies to ALL six (`[read]` `configure-transform-dialog.tsx:727-808`):** the bespoke panel's
> generic param renderer routes by `definition.type`: `accepts_mnemonic` → `MnemonicDateInput`; predicate fields →
> `ExpressionInput`; `enum` → generic `<Select>`; `object`/`object[]` → raw JSON `<Textarea>` (lines 792-800);
> `string[]` → newline `<Textarea>`. So today the **typed-shape params below (`object`/`object[]`/multi-`enum`) all fall
> through to a raw-JSON or generic-select editor** — the construct library replaces those fallbacks with the
> purpose-built controls. This is the "wire the construct into the type-switch" task #3's panel framework consumes.

### 3. `column-picker`

- **(a) Purpose:** pick one column — or a set of columns — from the live input schema (single-select combobox or
  multi-select badge toggles).
- **(b) Param shapes / #5 hints it owns:** `string` (single) and `string[]` (multi). The most-referenced construct in #5
  — ~35 hints (`[read]` SPEC-blueprint-catalog.md). Single: `incremental_field` (ApiIngestion), `incremental_column` /
  `watermark_column` / `ordering_field` (CDCIngestion), `merge_priority` (DedupeAndMerge), `effective_date_column`
  (SCD2Dimension), `time_column` (AggregateMaterialization), `entity_key` / `point_in_time_column` (FeatureTablePublish),
  `timestamp_column` (FreshnessChecks). Multi (`column-picker (multi)`): `primary_key` (CDCIngestion), `compare_key`
  (SnapshotIngestion), `columns_to_trim` / `drop_columns` / `drop_null_columns` / `dedup_key` (BronzeToSilverClean V153),
  `match_keys` (DedupeAndMerge), `columns_to_mask` (PIIMasking), `select_columns` (GenericJoin), `group_by_columns`
  (GenericAggregate), `source_columns` (JsonFlatten/JsonStruct), `passthrough_columns`, `business_key` /
  `tracked_columns` (SCD2Dimension), `unique_key` (SnapshotModel), `measures` / `dimension_keys` (FactBuild), `group_by`
  (WideDenormalizedMart), `merge_key(s)` / `z_order_columns` / `upsert_keys` (modeling/sink merges), `key_columns`
  (KafkaWriter), `monitored_columns` (AnomalyDetection). NOTE: `partition_by` is **system-derived `(inspector-readonly)`**
  in #5 (storage convention) — NOT a column-picker hint; only the `user`-tier column params above bind here.
- **(c) Inputs / props (`[read]` `column-picker.tsx:38-45,185-191`):** `columns: SchemaColumn[]` (the **live input
  schema** of the upstream port — #3 supplies it from `useUpstreamSchema`); `value: string` / `onChange` (single) OR
  `selected: string[]` / `onChange` (multi); optional `filterTypes?: string[]` (e.g. restrict to `timestamp`/`date` for
  `timestamp_column`, struct types for JsonFlatten `source_columns`); `placeholder` / `fallbackPlaceholder`.
- **(d) Validation / behavior:** searchable single-select with a type badge (`getTypeColor`); multi = badge toggles.
  `filterTypes` narrows the choosable set (substring match on `c.type`). `> GUESS:` a picked column that later drops out
  of the input schema (upstream edit) should surface as a stale-reference warning at the panel level (#3's
  schema-conflict overlay), not silently — flag for #3.
- **(e) Empty / loading / error:** **empty schema → graceful degrade to a plain text `Input`** (single,
  `column-picker.tsx:82-91`; multi, `:206-222`) so the user can still type a name before the schema resolves — this is
  the loading/empty state today. `> GUESS:` distinguish *loading* (schema fetch in flight → skeleton/spinner) from
  *genuinely empty* (a source with zero columns → "no columns" + the text fallback); today both collapse to the text
  input — recommend #3 pass a `loading` flag.
- **(f) EXTEND vs BUILD-NEW:** **EXTEND-`column-picker.tsx` `[read]`** — `ColumnPicker` (single,
  `column-picker.tsx:47`) + `MultiColumnPicker` (multi, `:193`) already exist, both schema-reading with type filter,
  search, and text fallback. Add: `filterTypes` wiring from #5 per-param (e.g. struct-only for JsonFlatten), the
  loading-vs-empty split (e), and wire it into `configure-transform-dialog`'s type-switch for `string`/`string[]`
  column-role params (today those `string[]` params render as a newline `Textarea`, `configure-transform-dialog.tsx:784-791`).

### 4. `rename-mapper`

- **(a) Purpose:** edit an old-column → new-column-name map as a two-column table (left = source column from the live
  schema, right = the new name), instead of hand-typing JSON.
- **(b) Param shapes / #5 hints it owns:** `object` (a `{source: target}` map). Two #5 hints: `rename_map`
  (BronzeToSilverClean V153, `[read]` SPEC-blueprint-catalog.md:305) and `mapping_rules` (SchemaNormalization
  source→target field map, `:328`). Sibling same-shape constructs named in the same #5 entries — `key-value-mapper`
  (`fill_null_map`), `type-cast-mapper` (`type_coercions`) — are **out of this task's six** but are the identical
  table-of-pairs pattern; `> GUESS:` build `rename-mapper` as the general two-column-map control and parameterize the
  right-hand cell (free-text name vs a type `<Select>` vs a value input) so all three share one component — flag for #3
  consolidation.
- **(c) Inputs / props:** the **live input schema** (`SchemaColumn[]`) to populate the left-hand source-column picker;
  `value: Record<string,string>` (the current map) / `onChange`. `> GUESS:` prop shape — define `{ columns, value,
  onChange }` mirroring `column-picker`.
- **(d) Validation / behavior:** add/remove rows; the source cell is a `column-picker` (must reference an existing input
  column — reuse §3); the target cell is a free-text name. Validate: no duplicate source keys; no duplicate target
  names (would collide on rename); non-empty target. Round-trips to the `object` JSON the param stores.
- **(e) Empty / loading / error:** empty map → an "add first mapping" affordance + one blank row. Loading schema →
  source cells fall back to free-text (inherit §3's degrade). Error → inline "duplicate source/target column" per
  offending row.
- **(f) EXTEND vs BUILD-NEW:** **BUILD-NEW.** No rename/mapping component exists (`grep -rn -i rename frontend/src`
  hits only tenant/GCP rename prose, none a column-map control). Today `rename_map`/`mapping_rules` (`type: object`)
  render as a raw JSON `<Textarea>` (`configure-transform-dialog.tsx:792-800`). Build the two-column table; **reuse
  `ColumnPicker` for the source cell** (so it stays schema-bound, not free typing).

### 5. `condition-builder`

- **(a) Purpose:** build a filter / WHERE (or per-route) predicate visually as a list of `{column, operator, value}`
  rows, with a SQL-text escape hatch.
- **(b) Param shapes / #5 hints it owns:** `object[]` shaped `[{column,operator,value}]`. #5 hints: `conditions`
  (GenericFilter, `[read]` SPEC-blueprint-catalog.md:420) and each `routes[].condition` (GenericRouter `route-builder`
  delegates the per-route predicate to `condition-builder`, `:445-446`). Adjacent: GenericFilter's `raw_sql` and
  GenericAggregate's `having_clause` are the **SQL-text** sibling and bind to `expression-builder` (§2), not here — the
  visual `conditions` and the `raw_sql` are the two halves of GenericFilter's `filter_mode` toggle (`:419,425`), so
  `condition-builder` MUST round-trip its visual rows to a `raw_sql` WHERE string (`:425`).
- **(c) Inputs / props (`[read]` `sql-filter-builder.tsx:186-197`):** `conditions: FilterCondition[]`
  (`{column,operator,value,logic}`); `availableColumns: string[]` AND/OR `columnSchema?: SchemaColumn[]` (the **live
  input columns** — `condition-builder` reads the input columns + the **operator set**); `rawSql: string`; `filterMode:
  "visual" | "sql"`; `onChange`. Operators are a fixed catalog (`OPERATORS`, `sql-filter-builder.tsx:27-42` — `eq/neq/gt/
  gte/lt/lte/like/not_like/rlike/in/not_in/between/is_null/is_not_null`), with nullary ops (`is_null/is_not_null`) hiding
  the value field (`:44,352`).
- **(d) Validation / behavior:** per-row column (schema-bound `ColumnPicker` when schema present, else a `<Select>` of
  `availableColumns`, else free text — `sql-filter-builder.tsx:303-334`), operator `<Select>`, value `Input`; AND/OR
  joiner between rows; a live WHERE-clause preview (`:381-386`). The SQL mode is a Spark-SQL `Textarea` with the shared
  Spark-function palette. `> GUESS:` per-value **mnemonic** support on `conditions[].value` is via a `date-mnemonic-picker`
  cell (§8), NOT the `[[ … ]]` token (that's the SQL/`expression-builder` path) — `[read]` SPEC-blueprint-catalog.md:426;
  this value-cell-becomes-a-mnemonic-picker wiring is **the one net-new behavior** to add on EXTEND.
- **(e) Empty / loading / error:** no conditions → "+ Add Condition" only, empty preview. Loading schema → column cell
  degrades to the `<Select>`/text path (inherited). Error → an invalid `raw_sql` should surface via the Calcite/Spark
  validate path (reuse `expression-builder`'s validator when in SQL mode); `> GUESS:` visual-mode rows are
  structurally-valid-by-construction (picked column + enumerated operator), so the only visual error is an empty value on
  a non-nullary operator — flag inline.
- **(f) EXTEND vs BUILD-NEW:** **EXTEND-`sql-filter-builder.tsx` `[read]`** — the visual `FilterCondition[]` builder
  (`sql-filter-builder.tsx:283-387`) is exactly the `[{column,operator,value}]` shape, already schema-bound (reuses
  `ColumnPicker`), with the visual/SQL toggle, operator catalog, and WHERE preview. Add: the per-value
  `date-mnemonic-picker` cell (d), and expose it for GenericRouter's per-route `condition` (the route-builder embeds one
  `condition-builder` per route). The embedded Spark-function palette (`SPARK_FUNCTIONS`, `:47-184`) is the **one and
  only** copy (§inventory; the registry-fork risk is forking a *second* copy on EXTEND) — reconcile to the one Calcite
  registry, don't fork a second copy.

### 6. `sensing-config`

- **(a) Purpose:** configure a readiness sensor (file-arrival / sql-probe / external-event) and the
  schedule/trigger block — the "what must be true before the DAG runs" surface.
- **(b) Param shapes / #5 hints it owns:** the three CONTROL sensor Blueprints' param blocks + ScheduleAndTriggers.
  **FileArrivalSensor** (`[read]` SPEC-blueprint-catalog.md:847-866): `storage_kind`/`bucket`/`path_prefix`/
  `filename_pattern`/`pattern_kind`/`expected_max_age_hours`/`multiple_files_mode`/`soft_fail` + `date_value`
  (→ §8). **DatabaseReadinessSensor** (`:880-894`): `sql` (probe SQL → `expression-builder` §2) + `expected_count_min`/
  `_max` + `date_value` (→ §8). **ExternalEventSensor** (`:904-911`): `event_url`. **ScheduleAndTriggers** (`:921-932`):
  `schedule_type` (`segmented-control` cron/event/manual), `cron_expression` (`cron-builder` — named but **out of these
  six**; `> GUESS:` keep `cron-builder` as its own #4 construct, `sensing-config` hosts it), `trigger_dataset`
  (`dataset-picker`), `catchup_enabled`/`depends_on_past` toggles. The poke/timeout/mode reliability knobs are
  `system-derived (inspector-readonly)` — NOT part of the user sensing surface (`:860-864`).
- **(c) Inputs / props (`[read]` `orchestration-panel.tsx:33-56,178-191`):** the pipeline's sensor blueprint instances
  (`GATE_FOUR_SENSOR_BLUEPRINT_KEYS`, `:33-39`) + the schedule policy (`SCHEDULE_POLICY_BLUEPRINT_KEY`, `:41`); the bound
  connector (for DatabaseReadinessSensor's `connection_id`, derived) and the dataset list (for `trigger_dataset`). Reads
  the tenant calendar context for the derived holiday/fiscal fields.
- **(d) Validation / behavior:** `filename_pattern` is a `{date}`-templated string (NOT `[[ ]]`); `date_value` feeds the
  `{date}` substitution via the §8 picker (the G-10 unification — one date experience). `schedule_type` gates which of
  `cron_expression` / `trigger_dataset` is required (`:923-925`). `soft_fail`/`multiple_files_mode` toggles. The panel
  already builds a human schedule summary (`buildScheduleSummary`, `:178-191`) and warns when an event schedule has no
  explicit sensor (`:579-584`).
- **(e) Empty / loading / error:** no sensors → "No orchestration steps yet. Add Gate 4 sensor blueprints…" empty state
  (`orchestration-panel.tsx:784`). Loading connector/dataset lists → the derived `connection_id`/`trigger_dataset`
  pickers spin. Error → required-field gating per `schedule_type`; stale-file guard via `expected_max_age_hours`.
- **(f) EXTEND vs BUILD-NEW:** **EXTEND-`orchestration-panel.tsx` `[read]`** — it already enumerates the Gate-4 sensor
  keys, the ScheduleAndTriggers policy, the cron/event/manual `ScheduleMode`, and rollback. It does NOT yet render the
  **per-sensor field surface** (bucket/path/pattern/SLA) as purpose-built inputs — those flow through the generic
  `configure-transform-dialog` JSON/Select fallback today. Add: the FileArrival location+match+SLA field group, the
  DatabaseReadiness probe-SQL (`expression-builder` §2) + count bounds, and wire `date_value` to §8. `cron-builder` and
  `dataset-picker` are sibling constructs hosted here. NOTE the #5 sensor key set drops the **deprecating**
  `ObjectStoreKeySensor`/`DatasetDependencySensor` (absorbed; `:868,934`) which `orchestration-panel.tsx:35,37` still
  lists — `> GUESS:` prune those two from the panel's set on EXTEND to match #5.

### 7. `dq-outcome-control`

> RESOLVED (operator 2026-06-16): the **hint-routing token the builder wires is `dq-outcome-control` (singular)** —
> canonical per SPEC-blueprint-catalog.md (the `on_failure`/`drift_policy` rows both name `dq-outcome-control`).
> Normalized the §7 heading and the §inventory item from the plural `DQ-outcome-controls` to the singular token. The
> §7 body already used the singular everywhere; this only fixes the token-form occurrences.

- **(a) Purpose:** pick what happens when a data-quality check fails — quarantine the bad rows, block (fail) the run, or
  warn/report — the single canonical failure-policy control.
- **(b) Param shapes / #5 hints it owns:** an `enum`. Two #5 hints: `on_failure` (DQValidator,
  `quarantine`/`block`/`warn` — the **canonical** `dq-outcome-control`, `[read]` SPEC-blueprint-catalog.md `on_failure`
  row) and `drift_policy` (SchemaDriftDetection, `warn`/`block`/…, `drift_policy` row).
  > RESOLVED (operator 2026-06-16): the **hint token the builder wires is the singular `dq-outcome-control`** (both the
  > `on_failure` and `drift_policy` catalog rows emit this exact singular token — do NOT wire the plural). DISTINCT from per-expectation `severity`
  (`critical`/`warning`/`info`) which lives **inside** the `dq-expectation-builder` rows
  (`[read]` `expectation-picker.tsx:118-122`) — `dq-outcome-control` is the **Blueprint-level** disposition, one per DQ
  step, and it governs which output port the data lands on (`validated_output` vs `quarantine_output`, `:669-670`).
- **(c) Inputs / props:** `value: string` (the chosen enum) / `onChange`; the option set (`quarantine`/`block`/`warn`)
  comes from the param's `options` in #5. `> GUESS:` it should also know whether the host Blueprint declares a
  `quarantine_output` port — only DQValidator does (`:670`); SchemaDriftDetection emits a `drift_report` (`:707`), so its
  `drift_policy` should NOT offer `quarantine` (no quarantine port). So the construct's option set is **port-aware**, not
  a fixed three — flag for #3 to pass the declared output roles.
- **(d) Validation / behavior:** a segmented-control / radio over the enum with a one-line consequence caption per
  option ("quarantine → bad rows to the side table", "block → fail the run", "warn → emit a report, keep running").
  Selecting `quarantine` requires the host to have the quarantine port (DQValidator); otherwise that option is hidden
  (per (c)).
- **(e) Empty / loading / error:** has a default (DQValidator defaults to the canonical policy; `on_failure` is optional
  in #5 so a default applies). No genuine loading state (static enum). Error → none beyond "pick one"; the choice is
  always valid within the offered set.
- **(f) EXTEND vs BUILD-NEW:** **BUILD-NEW.** No outcome-policy control exists — `expectation-picker.tsx` handles
  per-expectation `severity` (`:118-122,189-191`), a **different** axis. Today `on_failure`/`drift_policy` (`type: enum`)
  render as the generic `<Select>` (`configure-transform-dialog.tsx:751-770`). Build a small port-aware
  segmented-control with consequence captions. Trivial surface — its value is the consequence copy + port-awareness, not
  the widget.

### 8. `date-mnemonic-picker`

- **(a) Purpose:** pick a business-date mnemonic (or an ISO literal) from the closed PULSE vocabulary — the single date
  experience that unifies the SQL `[[ … ]]` token and the legacy `{date}`/`date_value` facility (G-10).
- **(b) Param shapes / #5 hints it owns:** a `string` with `accepts_mnemonic: true`. #5 hints: `date_range_start` /
  `date_range_end` (BulkBackfill, `[read]` SPEC-blueprint-catalog.md:181-182), `date_value` (FileIngestion `:235`,
  FileArrivalSensor `:855`, DatabaseReadinessSensor `:885`, StreamIngestion/SnapshotIngestion date fields `:855,885`),
  and `advance_to` (AdvanceTimeDimension `:961`). The closed vocabulary is authoritative in `DateMnemonic.java`
  (`[read]` backend, `DateMnemonic.java:23-118`) and mirrored client-side.
- **(c) Inputs / props (`[read]` `mnemonic-date-input.tsx:28-32`):** `id`, `value: string`, `onChange`. No schema input
  — the vocabulary is the closed `VOCABULARY` map (`:35-89`), grouped Today-relative / Week / Month / Quarter / Half-year
  / Year / Fiscal / Business-day. `> GUESS:` for the per-step SQL-preview (§Preview), the picker also needs an optional
  "as-of business date" so a mnemonic resolves for preview — flag as a prop add.
- **(d) Validation / behavior (`[read]` `mnemonic-date-input.tsx:91-126`):** tri-mode — **Mnemonic** (combobox of the
  vocabulary + an `±N` offset stepper shown only for offset-supporting heads, `:140-145,209-226`), **ISO date** (native
  date picker), **Free-text** (power users type `NBDOM(5)`, `BOM-12`). Client regex mirrors `DateMnemonic.java`
  (`MNEMONIC_RE`, `:93`); offset-forbidden vs offset-allowed heads match the Java `OFFSET_FORBIDDEN`/`HEADS_WITH_OFFSET`
  sets (`DateMnemonic.java:32-52`). Invalid → inline "not a recognized date or mnemonic" with examples (`:250-255`). The
  authoritative validate is server/codegen-side via `DateMnemonic.validateOrThrow` (`:105-118`).
- **(e) Empty / loading / error:** empty → mode defaults to Mnemonic with a "Pick a mnemonic…" placeholder
  (`mnemonic-date-input.tsx:129,191`). No loading (static vocabulary). Error → the inline invalid-token message (d).
- **(f) EXTEND vs BUILD-NEW:** **EXTEND-`mnemonic-date-input.tsx` `[read]`** (the component IS the `date-mnemonic-picker`,
  already the full tri-mode control, vocabulary-complete and kept in lockstep with `DateMnemonic.java`). It is already
  wired into the panel via `accepts_mnemonic` (`configure-transform-dialog.tsx:727-736`). Add: (i) the optional as-of
  prop for §Preview resolution; (ii) the **embedded-cell variant** so `condition-builder` (§5) can drop one into a
  `conditions[].value` cell; (iii) confirm it feeds the `{date}` substitution for `filename_pattern` (the G-10
  unification — sensors/FileIngestion use `{date}`+`date_value`, not `[[ ]]`). `> GUESS:` rename the file/export to
  `DateMnemonicPicker` to match the #5 hint name (cosmetic) — flag for build.

## Coverage basis

> **Every construct the #5 UI-construct hints reference** (`[read]` SPEC-blueprint-catalog.md G-9, `:1118-1124`).
> This spec authors the **six** named in the task + the three already locked above; the remainder are forward-declared
> here (named, owner-construct identified) and either fold into one of these six or are deferred to a later #4 pass.

- **Authored to depth this spec:** `sql-builder` (§1, two variants) · `expression-builder` (§2, polish) ·
  `column-picker` (§3) · `rename-mapper` (§4) · `condition-builder` (§5) · `sensing-config` (§6) ·
  `dq-outcome-control` (§7) · `date-mnemonic-picker` (§8). Plus the per-step **Preview execution** (above).
  > RESOLVED (operator 2026-06-16): **Eleven** deliverables — eight constructs (§1–§8), with `sql-builder` split into
  > two variants (S1a rich Spark/Calcite · S1b simple source-DB validate-only) and the per-step preview split into the
  > control (S9) + the design-time preview endpoint (S10). (Corrects the earlier "Ten deliverables" miscount; this file
  > carries no SCOPE-ID table, so the count is stated here.)
- **`sql-builder` hint→variant binding (reconciles this spec's umbrella name with #5's actual hint tokens):** the
  `sql-builder` umbrella is NOT itself a #5 hint token; #5 emits two distinct hints, each binding to exactly one §1
  variant — **`sql-chain-editor` → §1a** (rich Spark/Calcite, for `SqlModel.steps`) and **`simple-sql-builder` → §1b**
  (simple source-DB validate-only, for `SourceSQL.source_query` **and** `BulkBackfill.source_query` — both JDBC source
  reads validated by the source, not in-pipeline Spark). Both `sql-chain-editor` and `simple-sql-builder` are therefore
  **authored here** (as the §1a/§1b variants), not folded and not deferred.
- **Same-shape siblings folded into an authored construct** (build as parameterizations, not separate components):
  `key-value-mapper` + `type-cast-mapper` → fold into `rename-mapper`'s two-column-map (§4); `route-builder` embeds
  `condition-builder` per route (§5).
- **Hosted as authored sub-controls of `sensing-config` (§6) — NOT folded, NOT deferred** (own #4 constructs, owned by
  §6 per W-8): `cron-builder` + `dataset-picker`.
- **Forward-declared, deferred to a later #4 pass** (named in #5, NOT in this task's six — listed so coverage is
  honest): `aggregation-builder`, `window-function-builder`, `join-key-mapper`, `join-spec-builder`, `sort-spec-builder`,
  `struct-builder`, `dq-expectation-builder` (EXISTS as `expectation-picker.tsx` `[read]` — POLISH, like §2),
  `schema-spec-builder`, `feature-builder`, `sink-target-picker`, `peer-target-picker`, `remote-dag-picker`,
  `schema-reference-picker`.
- **Primitive controls** (`segmented-control`, `select`, `toggle`, `text-input`, `number-input`, `multi-select`,
  `inspector-readonly`): the shadcn/ui primitives the bespoke panel already renders by `definition.type` — not bespoke
  #4 constructs; enumerated in #5 G-9 only so every hint resolves.

## OPEN WORKLIST

> The `> GUESS:` items raised while authoring §3–§8 (each is an invention/assumption to ratify, not operator-agreed).
> These join the parent SPEC-GATE worklist; resolve before build.

> RESOLVED (operator 2026-06-16) — per WORKLIST-RESOLUTIONS.md §4 (rows 4-W1…4-W13): **all thirteen resolve with no
> DECIDE residual.** W-1…W-11 and W-13 are **DEFAULT** (code/spec-grounded, applied as written below); **W-12 is
> DERIVED** from #5 G-10 (the `date-mnemonic-picker` emits `[[ … ]]` in SQL context and `{date}` in `filename_pattern`
> context — one date experience). The grounded values: W-1 → warning lives in #3's schema-conflict overlay (not the
> construct); W-2 → #3 passes a `loading` flag (loading→skeleton, empty→text fallback); W-3 → consolidate to ONE
> general two-column-map control, `fill_null_map`/`type_coercions` reuse it; W-4 → prop shape
> `{ columns, value: Record<string,string>, onChange }`; W-5 → embedded `date-mnemonic-picker` cell for visual rows,
> `[[ … ]]` is raw-SQL-mode only; W-6 → visual rows structurally-valid-by-construction (only error = empty value on a
> non-nullary op); W-7 → prune `ObjectStoreKeySensor`/`DatasetDependencySensor` from `orchestration-panel.tsx:35,37`;
> W-8 → `cron-builder` stays standalone, hosted (not folded) by `sensing-config`; W-9 → port-aware option set, #3
> passes the declared output roles; W-10 → add the optional "as-of business date" prop; W-11 → add the embedded-cell
> variant (couples W-5); W-12 → confirmed (DERIVED ← #5 G-10); W-13 → #3 owns wiring each construct into the
> `configure-transform-dialog` type-switch, replacing the raw-JSON/`<Select>` fallbacks.
>
> **Build sequencing (per task #4 note):** the build model is the **LangGraph4j graph (ADR 0025)** — not the legacy
> single OpenRouter loop. The live SQL-validation constructs (§1a `sql-chain-editor` Calcite-backed validation;
> `validate_sql_expression` wiring) depend on the **Calcite validate backend (#6, `SPEC-calcite-sql-model.md`)** —
> `CALCITE-PHASE-2` must land before those constructs validate live. The §1b simple builder does NOT depend on Calcite
> (the source DB validates its own dialect via JDBC prepare/`getMetaData`), so it can ship independently of #6.

- **W-1 (§3 column-picker):** stale-column-reference (an upstream edit drops a picked column) should surface as a panel
  warning, not silently — owned by #3's schema-conflict overlay; confirm placement.
- **W-2 (§3 column-picker):** split *loading* (schema fetch in flight) from *empty* (zero-column source); today both
  collapse to the text fallback — recommend #3 pass a `loading` flag.
- **W-3 (§4 rename-mapper):** build `rename-mapper` as the general two-column-map control and parameterize the
  right-hand cell so `key-value-mapper` (`fill_null_map`) and `type-cast-mapper` (`type_coercions`) reuse it — confirm
  the consolidation.
- **W-4 (§4 rename-mapper):** prop shape `{ columns, value: Record<string,string>, onChange }` (mirrors column-picker) —
  confirm.
- **W-5 (§5 condition-builder):** per-value mnemonic on `conditions[].value` uses an embedded `date-mnemonic-picker`
  cell (§8), NOT the `[[ … ]]` token — confirm (carries SPEC-blueprint-catalog.md:426).
- **W-6 (§5 condition-builder):** visual-mode rows are structurally-valid-by-construction; the only visual error is an
  empty value on a non-nullary operator — confirm no deeper validation needed in visual mode.
- **W-7 (§6 sensing-config):** prune the deprecating `ObjectStoreKeySensor` / `DatasetDependencySensor` from
  `orchestration-panel.tsx:35,37`'s sensor set to match #5 (absorbed into FileArrivalSensor / ScheduleAndTriggers).
- **W-8 (§6 sensing-config):** `cron-builder` kept as its own #4 construct, hosted by `sensing-config` (not folded) —
  confirm.
- **W-9 (§7 DQ-outcome-control):** the option set is **port-aware** — `quarantine` offered only when the host Blueprint
  declares a `quarantine_output` port (DQValidator yes, SchemaDriftDetection no); #3 must pass the declared output roles.
- **W-10 (§8 date-mnemonic-picker):** add an optional "as-of business date" prop so mnemonics resolve for the §Preview
  per-step SQL preview.
- **W-11 (§8 date-mnemonic-picker):** add an embedded-cell variant for `condition-builder` value cells (couples to W-5).
- **W-12 (§8 date-mnemonic-picker):** confirm it feeds the `{date}` substitution for `filename_pattern` (the G-10
  unification: one date experience across `[[ ]]` SQL tokens and the legacy `{date}`/`date_value` facility).
- **W-13 (cross-cutting):** the construct library's task is to replace the generic `configure-transform-dialog`
  type-switch fallbacks (raw JSON `<Textarea>` for `object`/`object[]`; generic `<Select>` for `enum`) with these
  purpose-built controls — confirm #3 owns the wiring of each construct into the type-switch.

## Cross-references
- #3 `SPEC-ui-composition.md` (panels assemble these constructs) · #5 `SPEC-blueprint-catalog.md` (per-param construct
  hints, the forward refs) · `SPEC-calcite-sql-model.md` (the validate backend + function registry + `[[ … ]]`) ·
  ADR 0023 (param-tiering) · ADR 0024 (SQL authoring).
