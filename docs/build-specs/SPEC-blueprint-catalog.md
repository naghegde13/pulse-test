# SPEC #5 — Blueprint catalog / metadata (the consolidated per-Blueprint op-list + params + ports + UI-construct hints)

> **Status:** contract-level draft for the **SPEC-GATE** (ADR 0010 zero-fuzziness bar). This is the consolidated
> per-Blueprint metadata that (a) the **V153 seed** (#2 §D) serializes into `schema_behavior` + `params_schema`, and
> (b) #3's bespoke config panels **derive** from (`#3 panels = #5 metadata × #4 constructs × #3's panel framework` —
> `SPEC-INDEX.md:22`). It is **INTENT-CANONICAL** (ADR 0012/0013): each entry specs what the Blueprint is *meant* to
> do, not what today's Builder emits; where today's catalog/codegen deviates, the deviation is a **fix-item** in the
> entry's **notes**, never encoded as the contract.
>
> **`> GUESS:` lines WERE the SPEC-GATE's findings to resolve** — each marked a tier-assignment, UI-construct binding,
> name, or value this author had to invent rather than read from a LOCKED source or live code. **As of 2026-06-16 every
> inline `> GUESS:` is RESOLVED** (operator-agreed; each rewritten in place as `> RESOLVED (operator 2026-06-16):` +
> value + rationale, and the §OPEN WORKLIST G-1…G-15 closed). The `> GUESS:` mentions that remain below are the
> convention's own description, not unresolved calls.
>
> **Coverage basis (the gate yardstick):** **all 39 surviving Blueprints + `SqlModel` + `SourceSQL` = 41 entries**,
> each with **op-list** (`[read]` from the decomposition doc) + **params (tiered per ADR 0023)** + **ports** + **UI-construct
> hints (#4 refs)**. Grouped INGESTION 6 / TRANSFORM 10 / MODELING 8 (7 live + AggregateMaterialization folded into GenericAggregate, G-15) / DATA_QUALITY 4 / SINK 4 / CONTROL 7 (= 39 catalog entries to cover; 38 live blueprints post-merge) +
> the 2 new SQL Blueprints. A dropped Blueprint, a missing op-list, an untiered param, or a param with no UI-construct
> in any of the 41 = an omission finding. The **4 DEPRECATING** Blueprints (CostMonitoringHook, BackfillAndReplay,
> ObjectStoreKeySensor, DatasetDependencySensor) are explicitly OUT of the active set (§DEPRECATING).
>
> **Defined vocab (used verbatim):** **Blueprint** · **op / op-list** (the ordered self-describing ops; never
> "recipe"/"steps") · **param** (never "setting") · **tier** (`user` | `system-derived`) · **derivedFrom** (the
> resolution source of a `system-derived` param) · **Customer** (never "user") · **port** (input/output dataset
> attachment) · **dataset role** (the semantic role a port plays — raw/bronze/silver/gold/quarantine/report/signal) ·
> **UI-construct** (a named reusable control from #4, the construct library) · **accepts_mnemonic** (the param accepts
> an inline date mnemonic / `[[ … ]]` token — ADR 0024).
>
> **Tier vocabulary (ADR 0023):** every param is `tier: user` (a genuine Customer choice) or `tier: system-derived`
> (PULSE resolves it from `derivedFrom` ∈ {calendar / dataset / platform / connector / pipeline convention} at
> build/package time). MANDATE: **"no human hand-types runtime plumbing."** `system-derived` params render **read-only
> and always visible** (inspectable, not editable) — `[read]` ADR 0023:14,22; `[read]` SPEC-schema-op-engine.md:170-181.
> This spec uses `tier: system-derived` as the human-readable spelling of ADR 0023's code-level `tier:"derived"`.
>
> **Implements / cites:** ADR 0023 (param-tiering) · ADR 0024 (SqlModel/SourceSQL/Calcite/mnemonics) · ADR 0011
> (deterministic zero-LLM schema) · ADR 0012 (closed 32-op vocabulary) · ADR 0013 (deterministic Builder).
> **Consumed by:** #2 §D (V153 seed serializes these op-lists + tiered params), #3 (panels derive from this), #4
> (constructs referenced by NAME). **Op-list authority:** `docs/blueprints/OP-VOCABULARY-AND-DECOMPOSITION.md`. **Params
> + ports source:** `backend/src/test/resources/e2e/coverage/api-active-blueprints.json` (spot-checked vs migrations).

---

## 0. Grounding ledger (READ vs GUESSED)

`[read]` = read from a LOCKED ADR/spec, the decomposition doc, the active-blueprints JSON, or a migration at the cited
path. Everything else is flagged `> GUESS:` inline.

- `[read]` The op-list per Blueprint for all 39 survivors + the 32-op closed vocabulary — `OP-VOCABULARY-AND-DECOMPOSITION.md:43-66`
  (groups) and `:16-39` (op table). Op names used verbatim from the 32-op vocabulary `[read]` SPEC-schema-op-engine.md:268-381.
- `[read]` Each active Blueprint's `params_schema` (name/type/required/options/default/description/accepts_mnemonic) +
  `inputPorts`/`outputPorts` — `api-active-blueprints.json` (42 active rows; 38 of the 39 survivors + the 4 deprecating;
  `RemotePipelineInvocation` is the 39th survivor and is sourced from its migration, below).
- `[read]` `RemotePipelineInvocation` params/ports — `V131__remote_pipeline_invocation_blueprint.sql:17-28`.
- `[read]` `AdvanceTimeDimension` intent surface (2 user fields + 17 system-derived) — ADR 0023:11 + the V132 20-field
  runtime schema `V132__advance_time_dimension_runtime_state_contract.sql:8-29`. (The JSON's 4-field row is **stale** —
  see the entry's notes; this spec is INTENT-CANONICAL and uses the V132/ADR-0023 surface.)
- `[read]` Param-tiering shape (`tier`/`derivedFrom`) that V153 serializes — SPEC-schema-op-engine.md:147-181 (§A.3);
  ADR 0023:20-22 (§Mechanism). Op-entry shape (`op`/`ui_label`/`config` + `{"param":"<name>"}`) — SPEC-schema-op-engine.md:66-103 (§A.1).
- `[read]` The two new SQL Blueprints `SqlModel` + `SourceSQL` — ADR 0024:17-20; SPEC-calcite-sql-model.md §B/§C; the
  `sql-model` op is op 27 `[read]` SPEC-schema-op-engine.md:365.
- `[read]` Mask type rule (`hash → string`, else preserve) — SPEC-schema-op-engine.md:286-288; aggregate output types
  (COUNT→long, SUM int→long / decimal→double, AVG→double, MIN/MAX→source) — SPEC-schema-op-engine.md:301-305.
- `[read]` The audit-column set is **8 columns** — SPEC-schema-op-engine.md:348-361.
- `[read]` Enum option lists cited inline where load-bearing (masking_strategy, join_type, etc.) come from the migrations.
- **`> GUESS:` covers:** every per-param `tier` assignment that ADR 0023 does not name explicitly (ADR 0023 names only
  AdvanceTimeDimension + RemotePipelineInvocation in full); every UI-construct binding (#4 is to-author — all #4 refs
  are forward references); and a few `ui_label` / port-role spellings. These are flagged per-entry and consolidated in
  **§OPEN WORKLIST**.

### 0.1 The tiering heuristic applied uniformly (the rule behind every `tier:` call)

ADR 0023 names only two Blueprints' surfaces in full. For the other 39, this spec applies a single deterministic
heuristic (so a reviewer can audit each call), then flags the result `> GUESS:` where the heuristic is a judgment call:

1. **`system-derived` if the value is runtime plumbing PULSE can resolve from context** — i.e. `derivedFrom` ∈:
   - `pipeline.storage` — `storage_backend`, `lake_layer`, `lake_format` (resolved from the pipeline's storage backend
     + the Blueprint's category-default layer; `[read]` JSON descriptions: "Resolved at codegen and deploy time via
     storage_backends (tenant_id, environment, backend) lookup"). **Always `system-derived`** across all data Blueprints.
   - `domain.calendar` — `holiday_calendar_id`, `fiscal_offset_months`, `calendar_*`, `grain`, `timezone` (resolved
     from the domain's business-date config; `[read]` ADR 0023:11). **Always `system-derived`.**
   - `connector` — `connector_instance_id`, `connector_name`, `target_credential_ref`, `auth_credential_ref`,
     `target_id`, `_inherited_from_connector`, broker refs, and any field the JSON marks "Inherited from connector"
     or "Auto-populated by the … wizard." The Customer **selects the connector/target**; the refs **derive** from it.
   - `platform_default` — concurrency/evidence/retry/poke/timeout/pool/batch knobs that have a safe platform default
     and that "no one can hand-fill" (ADR 0023:11). `system-derived` with a platform default; surfaced read-only.
2. **`user` otherwise** — a genuine business choice: which columns, which keys, which expression, which condition,
   which mode/strategy, the SQL, the schedule, the SLA threshold, the target table name.
3. **Tie-break toward `user`** only when the value is a real semantic decision a citizen would make in chat; tie-break
   toward `system-derived` for anything that looks like deployment wiring. Every tie-break is flagged `> GUESS:`.

> RESOLVED (operator 2026-06-16): This heuristic is **RATIFIED as written** — one ruling unblocks the ~300 per-param
> tier calls; the per-param calls below apply it consistently and need not be re-litigated. Rationale: it directly
> implements ADR 0023's minimal-user-surface mandate ("no human hand-types runtime plumbing"). Confirmed
> highest-leverage calls: (a) `storage_backend`/`lake_layer`/`lake_format` are **system-derived everywhere** (the JSON
> marks them `required:true` today but their values are codegen/deploy-resolved — ADR 0023 says exactly this class is
> derived); (b) `partition_by`/`cluster_by` default **system-derived** (convention from grain/keys) but are a
> **power-user override**: they are **user-flippable to `tier: user`** as an override (the one heuristic carve-out the
> operator added — a DE who needs an explicit physical layout may assert it; default stays system-derived).

### 0.2 The universal data-Blueprint param block (stated once; referenced per entry)

Every **data** Blueprint (INGESTION/TRANSFORM/MODELING/DATA_QUALITY/SINK) carries this storage block. To avoid
repeating it 30+ times, each data entry's param table omits it and is understood to append:

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `storage_backend` | enum | true | system-derived | `pipeline.storage` | no | (inspector-readonly) |
| `lake_layer` | enum | true | system-derived | `pipeline.storage` (category default: ingestion→bronze, transform→silver, modeling/dq→gold) | no | (inspector-readonly) |
| `lake_format` | enum | true | system-derived | `pipeline.storage` (Mode rule: GCP gold MUST be `bq_native` — `[read]` JSON desc) | no | (inspector-readonly) |

`[read]` These three appear on every data Blueprint in the JSON with the "Resolved at codegen and deploy time"
description. SINK Blueprints carry `lake_format`/`output_path` differently (see SINK). DQ/Control entries that have ONLY
`storage_backend` say so. `> RESOLVED (operator 2026-06-16):` the `(inspector-readonly)` construct name stands — a #4
read-only inspector chip for system-derived params (ADR 0023 "inspectable, not editable"); the name is a forward
reference confirmed/owned at #4 (5-G09). Also a universal **`derived_columns` / `dropped_columns`
addenda** escape hatch exists on every TRANSFORM/MODELING Blueprint (`[read]` SPEC-schema-op-engine.md:382-387); it is
NOT a first-class param in `params_schema` and is omitted from the per-entry tables (a Blueprint needing those as
ordered steps uses the `add-column`/`drop-columns` ops instead).

### 0.3 Universal op-list addenda (audit + emission)

- **INGESTION & SINK** emit `compute:"pyspark"`; **TRANSFORM & MODELING** emit `compute:"dbt"` (SCD2→dbt-snapshot,
  SnapshotModel→dbt-incremental); **DATA_QUALITY** emit `compute:"gx"`; **CONTROL** emit `compute:null` (DAG-only).
  `[read]` SPEC-schema-op-engine.md:212-217.
- `add-audit-columns` appends the **8-column** PULSE audit set (`_pulse_ingested_at`, `_pulse_processing_ts`,
  `_pulse_pipeline`, `_pulse_task`, `_pulse_run_id`, `_pulse_source_uri`, `_pulse_business_date`, `_pulse_dag_id`).
  `[read]` SPEC-schema-op-engine.md:348-361. Shown in op-lists but the column set is owned by #1 rule 25 / #2 §E.

### 0.4 Category serialization (the section headers are DISPLAY groupings, NOT the serialized `category`)

> The "SINK" and "CONTROL" section headers below (and the `(SINK)`/`(CONTROL)` per-entry tags) are **display groupings
> for this spec only** — they are NOT the value V153 serializes into the `category` column. **V153 PRESERVES the live
> `category` column value** for every Blueprint. Concretely: the 4 sink/writer Blueprints serialize `category =
> "DESTINATION"` (NOT `"SINK"`), and the 7 control/sensor Blueprints serialize `category = "ORCHESTRATION"` (NOT
> `"CONTROL"`); the INGESTION / TRANSFORM / MODELING / DATA_QUALITY groups keep their existing live values unchanged.
> `[read]` `api-active-blueprints.json` category values: `DESTINATION` → {DatabaseWriter, LakeWriter, StreamWriter,
> WarehouseWriter}; `ORCHESTRATION` → {AdvanceTimeDimension, BackfillAndReplay, CostMonitoringHook,
> DatabaseReadinessSensor, DatasetDependencySensor, ExternalEventSensor, FileArrivalSensor, ObjectStoreKeySensor,
> RollbackOnFailure, ScheduleAndTriggers}; `INGESTION` / `TRANSFORM` / `MODELING` / `DATA_QUALITY` as in the JSON. (The
> §DEPRECATING table already labels its rows `ORCHESTRATION` — this note makes the rest of the spec internally
> consistent with that and with the live column.)

---

# INGESTION (6, PySpark — `compute:"pyspark"`)

> All ingestion Blueprints share the canonical shape `read-source → add-audit-columns → write-sink(bronze)`
> `[read]` OP-VOCABULARY-AND-DECOMPOSITION.md:43. They have **no input port** (they pull from a connector) and **one
> output port** landing bronze. The connector binding (which source) is the Customer choice; the per-source mechanics
> below tier most knobs `system-derived` per the connector/calendar/storage rule.

### ApiIngestion (INGESTION)

- **op-list:** `[read]` `read-source → add-audit-columns → write-sink(bronze)` (OP-VOCAB:43). `read-source` config =
  the API source (url + auth + pagination + watermark).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `api_url` | string | true | user | — | no (may carry `{{ ds }}` templating `[read]` JSON) | `text-input` (with date-template helper) |
| `auth_type` | enum | true | user | — | no | `select` (bearer/…) |
| `auth_credential_ref` | string | false | system-derived | `connector` (secret-manager ref) | no | (inspector-readonly) |
| `pagination_type` | enum | false | user | — | no | `select` |
| `rate_limit_rpm` | integer | false | user | — | no | `number-input` |
| `incremental_field` | string | false | user | — | no | `column-picker` (response field) |
| `response_json_path` | string | false | user | — | no | `text-input` (JSONPath) |
| `retry_count` | integer | false | system-derived | `platform_default` (=3) | no | (inspector-readonly) |
| `timeout_seconds` | integer | false | system-derived | `platform_default` (=60) | no | (inspector-readonly) |

  Plus the universal storage block (§0.2; ingestion → `bronze`). `[read]` JSON ApiIngestion (12 params).
- **ports:** in — none. out — `api_output` (dataset role: **bronze/raw**). `[read]` JSON.
- **notes:** `> RESOLVED (operator 2026-06-16):` `retry_count`/`timeout_seconds` = `derived` — platform reliability knobs
  with safe defaults fall in the §0.1 `platform_default` class (the ratified heuristic). `> RESOLVED (operator
  2026-06-16):` `incremental_field` → `column-picker` (with free-text fallback at #4) — a column reference even though the
  set is a response shape; UI-construct binding owned at #4 (5-G09).

### BulkBackfill (INGESTION)

- **op-list:** `[read]` `read-source → add-audit-columns → write-sink(bronze)` (OP-VOCAB:43). `read-source` carries a
  `source_query` (JDBC SELECT, predicate-pushdown) — the same param shape `SourceSQL` reuses `[read]` ADR 0024:20.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `source_query` | string | true | user | — | yes (`[[ … ]]` per ADR 0024) | `simple-sql-builder` (JDBC source read, validate-only — same source-DB shape as `SourceSQL`, NOT in-pipeline Spark `[read]` ADR 0024:20) |
| `date_range_start` | string | true | user | — | **yes** `[read]` JSON | `date-mnemonic-picker` |
| `date_range_end` | string | true | user | — | **yes** `[read]` JSON | `date-mnemonic-picker` |
| `chunk_size` | integer | false | system-derived | `platform_default` (=100000) | no | (inspector-readonly) |
| `chunk_days` | integer | false | system-derived | `platform_default` (=31) | no | (inspector-readonly) |
| `parallelism` | integer | false | system-derived | `platform_default` (=4) | no | (inspector-readonly) |
| `holiday_calendar_id` | enum | false | system-derived | `domain.calendar` | no | (inspector-readonly) |
| `fiscal_offset_months` | integer | false | system-derived | `domain.calendar` | no | (inspector-readonly) |

  Plus the universal storage block (§0.2; → `bronze`). `[read]` JSON BulkBackfill (11 params).
- **ports:** in — none. out — `backfill_output` (role: **bronze**). `[read]` JSON.
- **notes:** `holiday_calendar_id`/`fiscal_offset_months` are `domain.calendar`-derived per ADR 0023 (the calendar
  facility is a derived contract, not hand-typed). `> RESOLVED (operator 2026-06-16):` `chunk_*`/`parallelism` = `derived`
  — platform reliability/throughput knobs in the §0.1 `platform_default` class (ratified heuristic). NOT to be confused
  with the deprecating `BackfillAndReplay` (§DEPRECATING); **BulkBackfill is KEPT** as the surviving replacement target
  and `BackfillAndReplay` is dropped (5-G15) `[read]` OP-VOCAB:66.

### CDCIngestion (INGESTION)

- **op-list:** `[read]` `read-source → add-audit-columns → write-sink(bronze)` (OP-VOCAB:43). **Fix-item (intent):** the
  decomposition flags that today's codegen is a plain JDBC read with **no change-column** — an intent gap, not a
  contract change `[read]` OP-VOCAB:44. The op-list stays the canonical ingestion shape; the CDC change-event semantics
  (before/after) are a `read-source` config concern, not a new op.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `source_type` | enum | true | system-derived | `connector` | no | (inspector-readonly) |
| `tables` | string[] | true | user | — | no | `multi-select` (source tables) |
| `primary_key` | string[] | true | user | — | no | `column-picker` (multi) |
| `cdc_mode` | enum | false | user | — | no | `select` (debezium/incremental_poll/…) |
| `incremental_column` | string | false | user | — | no | `column-picker` (req. when cdc_mode=incremental_poll) |
| `watermark_column` | string | false | user | — | no | `column-picker` |
| `initial_snapshot` | boolean | false | user | — | no | `toggle` |
| `delete_handling` | enum | false | user | — | no | `select` (soft_delete/…) |
| `ordering_field` | string | false | user | — | no | `column-picker` |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` (convention) | no | (inspector-readonly) |

  Plus storage block (§0.2; → `bronze`). `[read]` JSON CDCIngestion (13 params).
- **ports:** in — none. out — `cdc_stream` (role: **bronze** / change-events). `[read]` JSON.
- **notes:** `source_type` is connector-derived (the connector already knows it; JSON has no description but it mirrors
  the StreamIngestion "Inherited from connector" pattern). `> RESOLVED (operator 2026-06-16):` `partition_by` default
  `derived` (storage convention from grain/keys), **user-flippable to `tier: user` as a power-user override** per the
  ratified §0.1 carve-out.

### FileIngestion (INGESTION)

- **op-list:** `[read]` `read-source → add-audit-columns → write-sink(bronze)` (OP-VOCAB:43). `read-source` reads a
  file at the connector path matched by `filename_pattern` (+`{date}` substitution).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `_inherited_from_connector` | string[] | false | system-derived | `connector` (read-only at this surface `[read]` JSON) | no | (inspector-readonly) |
| `filename_pattern` | string | true | user | — | no (uses `{date}` not `[[ ]]`) | `text-input` (filename template) |
| `pattern_kind` | enum | false | user | — | no | `select` (template/glob/exact) |
| `date_format` | string | false | system-derived | `platform_default` (`yyyyMMdd`) | no | (inspector-readonly) |
| `date_value` | string | false | user | — | **yes** (mnemonic, default `RUN_DATE` `[read]` JSON) | `date-mnemonic-picker` |
| `delimiter` | string | false | user | — | no | `text-input` |
| `has_header` | boolean | false | user | — | no | `toggle` |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` (convention `["ds"]`) | no | (inspector-readonly) |
| `expected_size_min` | integer | false | system-derived | `platform_default` (=0) | no | (inspector-readonly) |
| `holiday_calendar_id` | enum | false | system-derived | `domain.calendar` | no | (inspector-readonly) |
| `fiscal_offset_months` | integer | false | system-derived | `domain.calendar` | no | (inspector-readonly) |

  Plus storage block (§0.2; → `bronze`). `[read]` JSON FileIngestion (14 params).
- **ports:** in — none. out — `raw_output` (role: **bronze/raw**). `[read]` JSON.
- **notes:** `date_value` is a genuine Customer choice (which business date drives the filename) → `user` + mnemonic.
  `> RESOLVED (operator 2026-06-16):` `date_format` = `derived` (platform convention, §0.1 `platform_default` class) —
  the ratified heuristic keeps it derived; no per-source `user` exception (5-G08).
  `filename_pattern` uses the legacy `{date}` placeholder, NOT the ADR-0024 `[[ ]]` token (that token is for SQL); the
  `date_value` mnemonic feeds `{date}`. Fix-item: align the two date facilities (`{date}`+`date_value` vs `[[ ]]`) at
  the #4 level so the Customer sees one date-mnemonic experience.

### SnapshotIngestion (INGESTION)

- **op-list:** `[read]` `read-source → add-audit-columns → write-sink(bronze)` (OP-VOCAB:43) — a full point-in-time
  source pull.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `source_table` | string | false | system-derived | `connector` ("Inherited from connector" `[read]` JSON) | no | (inspector-readonly) |
| `snapshot_frequency` | enum | false | user | — | no | `select` (daily/…) |
| `compare_key` | string[] | false | user | — | no | `column-picker` |

  Plus storage block (§0.2; → `bronze`). `[read]` JSON SnapshotIngestion (6 params). (`compare_key` typed `string` in
  JSON; `> RESOLVED (operator 2026-06-16):` it is a column reference → `column-picker` (multi) — UI-construct binding
  owned at #4 (5-G09).)
- **ports:** in — none. out — `snapshot_output` (role: **bronze**). `[read]` JSON.
- **notes:** Minimal user surface already (1 connector-derived + 2 user). Good ADR-0023 citizen.

### StreamIngestion (INGESTION)

- **op-list:** `[read]` `read-source → add-audit-columns → write-sink(bronze)` (OP-VOCAB:43) — micro-batched stream pull.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `stream_type` | enum | false | system-derived | `connector` ("Inherited from connector") | no | (inspector-readonly) |
| `topic` | string | false | system-derived | `connector` ("Inherited from connector") | no | (inspector-readonly) |
| `consumer_group` | string | false | system-derived | `connector` ("Inherited from connector") | no | (inspector-readonly) |
| `batch_window_seconds` | integer | false | user | — | no | `number-input` |
| `deserialization_format` | enum | false | user | — | no | `select` (json/avro/…) |
| `starting_offsets` | string | false | user | — | no | `text-input` |

  Plus storage block (§0.2; → `bronze`). `[read]` JSON StreamIngestion (9 params).
- **ports:** in — none. out — `stream_output` (role: **bronze**). `[read]` JSON.
- **notes:** `stream_type`/`topic`/`consumer_group` connector-derived per the JSON's explicit "Inherited from connector".

---

# TRANSFORM (10, dbt-SQL — `compute:"dbt"`)

### BronzeToSilverCleaning (TRANSFORM)

- **op-list:** `[read]` `transform-values(trim/fill-nulls) + rename-columns + change-types + drop-columns`
  ` + deduplicate` (the base Cleaning ops, OP-VOCAB:47) **+ `filter-rows`** (the drop-null-rows decomposition —
  `drop_null_columns` → `filter-rows`/`drop_when_null`, `[read]` SPEC-schema-op-engine.md:107-113; full locked
  decomposition `[read]` SPEC-schema-op-engine.md:107-135). The anchor's silver op.
  Every op optional → unconfigured = passthrough. **THE op-list KEYS V153 seeds** are the locked `trim_columns`,
  `fill_null_map`, `rename_map`, `type_coercions`, `drop_columns`, `drop_null_columns`, `dedup_key`
  `[read]` SPEC-schema-op-engine.md:142-145.
- **params** (INTENT surface — the locked V153 keys, NOT today's JSON `null_handling` enum):

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `columns_to_trim` (`trim_columns`) | string[] | false | user | — | no | `column-picker` (multi) |
| `fill_null_map` | object | false | user | — | no | `key-value-mapper` (column→fill value) |
| `rename_map` | object | false | user | — | no | `rename-mapper` |
| `type_coercions` | object | false | user | — | no | `type-cast-mapper` (column→type) |
| `drop_columns` | string[] | false | user | — | no | `column-picker` (multi) |
| `drop_null_columns` | string[] | false | user | — | no | `column-picker` (multi; feeds `filter-rows` drop-when-null) |
| `dedup_key` | string[] | false | user | — | no | `column-picker` (multi) |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` (default `["ds"]`) | no | (inspector-readonly) |

  Plus storage block (§0.2; → `silver`).
- **ports:** in — `raw_input` (role: **bronze**). out — `cleaned_output` (role: **silver**). `[read]` JSON.
- **notes:** **Strongest intent-vs-code bug (fix-item #4):** today's row is `null_handling` enum + `SELECT *` body that
  ignores every declared param `[read]` OP-VOCAB:47,77 + SPEC-schema-op-engine.md:107-113. V153 **supersedes**
  `null_handling`; this entry specs the decomposed key set. `> RESOLVED (operator 2026-06-16):` `key-value-mapper` /
  `type-cast-mapper` / `rename-mapper` construct names stand — defined/owned at #4 (5-G09; #4 W3 consolidates them onto
  one parameterized two-column-map base). They are forward references, not open decisions.

### SchemaNormalization (TRANSFORM)

- **op-list:** `[read]` `rename-columns (+ change-types via target_schema, + drop-columns if strict_mode)` (OP-VOCAB:48).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `target_schema` | string | true | user | — | no | `schema-reference-picker` (reference to a standard schema) |
| `mapping_rules` | object | false | user | — | no | `rename-mapper` (source→target field map) |
| `strict_mode` | boolean | false | user | — | no | `toggle` |

  Plus storage block (§0.2; → `silver`). `[read]` JSON SchemaNormalization (6 params).
- **ports:** in — `source_data` (role: **bronze/silver**). out — `normalized_output` (role: **silver**). `[read]` JSON.
- **notes:** `strict_mode=true` activates the `drop-columns` op (drop columns not in target schema). `> RESOLVED
  (operator 2026-06-16):` `schema-reference-picker` construct (selects a registered target schema/contract) stands —
  owned/defined at #4 (5-G09).

### DedupeAndMerge (TRANSFORM)

- **op-list:** `[read]` `deduplicate` (OP-VOCAB:49).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `match_keys` | string[] | true | user | — | no | `column-picker` (multi; the dedup partition) |
| `order_by_columns` | object[] | true | user | — | no | `sort-spec-builder` (`[{column,direction}]`) |
| `match_strategy` | enum | false | user | — | no | `select` (exact/fuzzy/composite) |
| `merge_priority` | string | false | user | — | no | `column-picker` (tie-break column) |
| `dedup_method` | enum | false | system-derived | `platform_default` (`row_number`) | no | (inspector-readonly) |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |

  Plus storage block (§0.2; → `silver`). `[read]` JSON DedupeAndMerge (9 params).
- **ports:** in — `input_data` (role: **silver**). out — `deduped_output` (role: **silver**). `[read]` JSON.
- **notes:** `dedup_method` (row_number vs qualify) is a dialect/emission concern, not a business choice → system-derived.
  `> RESOLVED (operator 2026-06-16):` `sort-spec-builder` construct (ordered column+direction rows) stands —
  owned/defined at #4 (5-G09); a `match_strategy=fuzzy` fuzzy-config sub-control is likewise a #4 concern.

### PIIMasking (TRANSFORM)

- **op-list:** `[read]` `mask-columns` (OP-VOCAB:49). Type rule: `hash → string`, every other strategy preserves source
  type `[read]` SPEC-schema-op-engine.md:286-288.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `columns_to_mask` | string[] | true | user | — | no | `column-picker` (multi) |
| `masking_strategy` | enum | true | user | — | no | `select` (`hash`/`redact`/`tokenize`/`encrypt` `[read]` migration) |
| `preserve_format` | boolean | false | user | — | no | `toggle` |
| `hash_algorithm` | enum | false | user | — | no | `select` (`sha256`/`sha512`/`md5` `[read]` migration) |

  Plus storage block (§0.2; → `silver`). `[read]` JSON PIIMasking (7 params).
- **ports:** in — `sensitive_data` (role: **silver**). out — `masked_output` (role: **silver**). `[read]` JSON.
- **notes:** `hash_algorithm` only meaningful when `masking_strategy=hash`; #4 should conditionally reveal it. The
  schema-effect (hash→string) is owned by #1 rule 7, not a param.

### GenericJoin (TRANSFORM)

- **op-list:** `[read]` `join` (OP-VOCAB:49). Collision rule (FIX #5): on same-name, prefix the right side `right_<name>`
  — keep both `[read]` SPEC-schema-op-engine.md:295-300.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `join_type` | enum | true | user | — | no | `segmented-control` (`inner`/`left`/`right`/`full_outer`/`cross` `[read]` migration) |
| `join_keys` | object[] | true | user | — | no | `join-key-mapper` (`[{left_column,right_column}]`) |
| `select_columns` | string[] | false | user | — | no | `column-picker` (multi, from both sides) |
| `alias_left` | string | false | user | — | no | `text-input` |
| `alias_right` | string | false | user | — | no | `text-input` |

  Plus storage block (§0.2; → `silver`). `[read]` JSON GenericJoin (8 params).
- **ports:** in — `left_input`, `right_input` (both role **silver**). out — `joined_output` (role: **silver**). `[read]` JSON.
- **notes:** Two input ports (the only multi-input TRANSFORM besides nothing). Fix-item #5 is a schema-rule fix (#1), not
  a param. `> RESOLVED (operator 2026-06-16):` `join-key-mapper` construct (paired left/right column rows) stands —
  owned/defined at #4 (5-G09).

### GenericAggregate (TRANSFORM)

- **op-list:** `[read]` `group-and-aggregate` (OP-VOCAB:49). Output types (FIX #6): COUNT→long, SUM int→long /
  decimal→double, AVG→double, MIN/MAX→source `[read]` SPEC-schema-op-engine.md:301-305.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `group_by_columns` | string[] | false | user | — | no | `column-picker` (multi) |
| `aggregations` | object[] | false | user | — | no | `aggregation-builder` (`[{column,function,alias}]`) |
| `having_clause` | string | false | user | — | **yes** (`[[ … ]]` per ADR 0024 §D.6 `[read]` SPEC-calcite-sql-model.md:326-330) | `condition-builder` (SQL HAVING) |
| `window_functions` | object[] | false | user | — | no | `window-function-builder` |

  Plus storage block (§0.2; → `silver`). `[read]` JSON GenericAggregate (7 params).
- **ports:** in — `data_input` (role: **silver**). out — `aggregated_output` (role: **silver**). `[read]` JSON.
- **notes:** **MERGED ≡ AggregateMaterialization** — `> RESOLVED (operator 2026-06-16):` `AggregateMaterialization` is
  merged into `GenericAggregate` (one Blueprint; same `group-and-aggregate` op / intent), per 5-G15 `[read]` OP-VOCAB:56,87.
  GenericAggregate is the surviving entry; the gold-landing `refresh_strategy`/`partition_by`/`cluster_by` knobs from the
  former AggregateMaterialization fold in here (the merged surface = this entry + those derived knobs). `> RESOLVED
  (operator 2026-06-16):` `aggregation-builder` / `window-function-builder` constructs stand — owned/defined at #4
  (5-G09); `having_clause` is the canonical SQL-expression param that inherits ADR-0024 mnemonics.

### GenericFilter (TRANSFORM)

- **op-list:** `[read]` `filter-rows` (OP-VOCAB:49).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `filter_mode` | enum | false | user | — | no | `segmented-control` (`visual`/`sql`) |
| `conditions` | object[] | false | user | — | no (per-value mnemonic via #4) | `condition-builder` (`[{column,operator,value}]`) |
| `raw_sql` | string | false | user | — | **yes** (`[[ … ]]` — SQL expression, ADR 0024 §D.6) | `expression-builder` (SQL WHERE) |

  Plus storage block (§0.2; → `silver`). `[read]` JSON GenericFilter (6 params).
- **ports:** in — `data_input` (role: **silver**). out — `filtered_output` (role: **silver**). `[read]` JSON.
- **notes:** `filter_mode` toggles which of `conditions`/`raw_sql` is active; #4's `condition-builder` should round-trip
  to `raw_sql`. `> RESOLVED (operator 2026-06-16):` mnemonic support on the visual `conditions[].value` is via #4 (a
  `date-mnemonic-picker` cell), not the `[[ ]]` token (that's the SQL path) — confirmed at #4 (4-W5/4-W11; 5-G09/5-G10).

### GenericRouter (TRANSFORM)

- **op-list:** `[read]` `route-rows` (N outputs) (OP-VOCAB:51). Each route → one dynamic output port carrying the input
  schema `[read]` SPEC-schema-op-engine.md:317-319.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `routes` | object[] | false | user | — | no (per-condition mnemonic via #4) | `route-builder` (`[{name,condition,description}]`) |
| `include_default` | boolean | false | user | — | no | `toggle` |

  Plus storage block (§0.2; → `silver`). `[read]` JSON GenericRouter (5 params).
- **ports:** in — `data_input` (role: **silver**). out — `default_output` (role: **silver**) **+ one dynamic port per
  route** (named by `routes[].name`, role **silver**) `[read]` JSON (static `default_output`) + §B rule 18.
- **notes:** **Breaks-model #1 / fix-item #1:** codegen emits N+1 models but schema-prop gives one passthrough port
  `[read]` OP-VOCAB:74. The INTENT is one port per route + a default; that is a #1 (schema) + #2 (codegen) fix, surfaced
  here so the panel/#4 `route-builder` knows it materializes dynamic ports. `> RESOLVED (operator 2026-06-16):` each
  route's `condition` uses `condition-builder` (5-G09); dynamic-port naming = `routes[].name`.

### JsonFlatten (TRANSFORM)

- **op-list:** `[read]` `flatten-json` (OP-VOCAB:51) — nested struct → flat sub-fields.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `source_columns` | string[] | false | user | — | no | `column-picker` (multi; struct columns) |
| `separator` | string | false | system-derived | `platform_default` (`_`) | no | (inspector-readonly) |
| `max_depth` | integer | false | user | — | no | `number-input` |
| `explode_arrays` | boolean | false | user | — | no | `toggle` |
| `keep_original` | boolean | false | user | — | no | `toggle` |
| `prefix` | string | false | user | — | no | `text-input` |

  Plus storage block (§0.2; → `silver`). `[read]` JSON JsonFlatten (9 params).
- **ports:** in — `data_input` (role: **silver**, has nested columns). out — `flat_output` (role: **silver**). `[read]` JSON.
- **notes:** Nested-type model owned by #1 §B.0. `> RESOLVED (operator 2026-06-16):` `separator` = `derived` (a naming
  convention, §0.1 `platform_default` class); `max_depth` stays `user` (a real depth choice) — both per the ratified
  heuristic.

### JsonStruct (TRANSFORM)

- **op-list:** `[read]` `build-struct (+ drop-columns)` (OP-VOCAB:51) — flat columns → nested struct.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `output_format` | enum | false | user | — | no | `segmented-control` (`struct`/`json`) |
| `mappings` | object[] | false | user | — | no | `struct-builder` (`[{struct_name,fields:[{source_column,as}]}]`) |
| `drop_source_columns` | boolean | false | user | — | no | `toggle` (drives the `drop-columns` op) |
| `passthrough_columns` | string[] | false | user | — | no | `column-picker` (multi) |

  Plus storage block (§0.2; → `silver`). `[read]` JSON JsonStruct (7 params).
- **ports:** in — `data_input` (role: **silver**, flat). out — `struct_output` (role: **silver**, nested). `[read]` JSON.
- **notes:** Fix-item #12: nested-struct types not carried by the flat `{name,type}` schema model `[read]` OP-VOCAB:84 —
  resolved by #1 §B.0's recursive encoding. `> RESOLVED (operator 2026-06-16):` `struct-builder` construct (nested
  column→field grouping) stands — owned/defined at #4 (5-G09).

---

# MODELING (8, dbt-SQL — `compute:"dbt"`; SCD2 = dbt-snapshot, SnapshotModel = dbt-incremental)

### SCD2Dimension (MODELING)

- **op-list:** `[read]` `track-history-scd2` (OP-VOCAB:54; dbt `{% snapshot %}`). Adds `dbt_valid_from`/`dbt_valid_to`/
  `dbt_scd_id`/`dbt_updated_at` `[read]` SPEC-schema-op-engine.md:323-328 (FIX #2 — today the rule is transposed).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `business_key` | string[] | true | user | — | no | `column-picker` (multi) |
| `tracked_columns` | string[] | true | user | — | no | `column-picker` (multi; trigger a new version) |
| `effective_date_column` | string | false | user | — | no | `column-picker` (default `effective_from`) |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |
| `cluster_by` | string[] | false | system-derived | `pipeline.storage` (BigQuery) | no | (inspector-readonly) |

  Plus storage block (§0.2; → `gold`). `[read]` JSON SCD2Dimension (8 params).
- **ports:** in — `source_data` (role: **silver**, current state). out — `scd2_output` (role: **gold**, full history). `[read]` JSON.
- **notes:** Fix-items #2 (schema rule transposed) + #10 (redundant custom cols) + #8 (zero-LLM gap) `[read]` OP-VOCAB:75,83,81
  — all schema/codegen fixes (#1/#2), not param changes. `> RESOLVED (operator 2026-06-16):` `partition_by`/`cluster_by`
  default `derived` (storage convention), **user-flippable to `tier: user` as a power-user override** per the ratified
  §0.1 carve-out.

### SnapshotModel (MODELING)

- **op-list:** `[read]` `take-periodic-snapshot` (OP-VOCAB:54; dbt INCREMENTAL model). Adds `ds` + `_pulse_processing_ts`
  + `_pulse_run_id` + `_pulse_snapshot_model` `[read]` SPEC-schema-op-engine.md:329-333 (FIX #3 — today transposed).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `snapshot_frequency` | enum | false | user | — | no | `select` (daily/…) |
| `retention_days` | integer | false | user | — | no | `number-input` |
| `unique_key` | string[] | false | user | — | no | `column-picker` (multi) |
| `strategy` | enum | false | user | — | no | `select` (`timestamp`/…) |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |
| `cluster_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |

  Plus storage block (§0.2; → `gold`). `[read]` JSON SnapshotModel (9 params).
- **ports:** in — `source_data` (role: **silver**). out — `snapshot_output` (role: **gold**). `[read]` JSON.
- **notes:** Fix-items #3 (rule transposed, swapped with SCD2) + #9 (artifact_types mis-tag `dbt_snapshot`) `[read]`
  OP-VOCAB:76,82 — schema/metadata fixes. `> RESOLVED (operator 2026-06-16):` `snapshot_frequency` stays `user` — a
  genuine modeling-cadence business choice (not `domain.calendar`-derived) per the ratified heuristic, despite the
  time-grain overlap.

### FactBuild (MODELING)

- **op-list:** `[read]` `join + keep-columns` (OP-VOCAB:55).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `grain` | string | true | user | — | no | `text-input` (what one row represents) |
| `measures` | string[] | true | user | — | no | `column-picker` (multi) |
| `dimension_keys` | string[] | true | user | — | no | `column-picker` (multi) |
| `incremental` | boolean | false | user | — | no | `toggle` |
| `time_column` | string | false | user | — | no | `column-picker` |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |
| `cluster_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |

  Plus storage block (§0.2; → `gold`). `[read]` JSON FactBuild (10 params).
- **ports:** in — `transaction_data` (role: **silver**, facts), `dimension_refs` (role: **gold**, dimensions for key
  lookup). out — `fact_output` (role: **gold**). `[read]` JSON.
- **notes:** Fix-item #8 (still falls to LLM despite deterministic decomposition) `[read]` OP-VOCAB:81 — a #1/#2 fix.
  `> RESOLVED (operator 2026-06-16):` `grain` is free text (a semantic label), not a column — stays `user` + `text-input`
  per the ratified heuristic (a genuine business label).

### WideDenormalizedMart (MODELING)

- **op-list:** `[read]` `join(N) + group-and-aggregate + keep-columns` (OP-VOCAB:55).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `fact_source` | string | true | user | — | no | `dataset-picker` (the core fact) |
| `dimension_joins` | object[] | true | user | — | no | `join-spec-builder` (dimension join list) |
| `pre_aggregations` | object[] | false | user | — | no | `aggregation-builder` (optional pre-agg) |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |
| `cluster_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |

  Plus storage block (§0.2; → `gold`). `[read]` JSON WideDenormalizedMart (8 params).
- **ports:** in — `fact_data` (role: **gold**, core fact), `dimension_data` (role: **gold**, dims). out — `mart_output`
  (role: **gold**, wide BI mart). `[read]` JSON.
- **notes:** `> RESOLVED (operator 2026-06-16):` `dataset-picker` / `join-spec-builder` constructs stand — owned/defined
  at #4 (5-G09). Fix-item #8 (LLM fallback) applies `[read]` OP-VOCAB:81.

### AggregateMaterialization (MODELING)

- **op-list:** `[read]` `group-and-aggregate` (OP-VOCAB:56) — **≡ GenericAggregate (MERGED, operator 2026-06-16, 5-G15)**.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `group_by` | string[] | true | user | — | no | `column-picker` (multi) |
| `aggregations` | object[] | true | user | — | no | `aggregation-builder` (`[{measure,aggregation_function}]`) |
| `refresh_strategy` | enum | false | user | — | no | `select` (`incremental`/…) |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |
| `cluster_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |

  Plus storage block (§0.2; → `gold`). `[read]` JSON AggregateMaterialization (8 params).
- **ports:** in — `detail_data` (role: **silver**). out — `aggregate_output` (role: **gold**, summary). `[read]` JSON.
- **notes:** **MERGED into GenericAggregate** (operator 2026-06-16, 5-G15) — same `group-and-aggregate` op/intent
  `[read]` OP-VOCAB:56,87. `GenericAggregate` is the surviving Blueprint key; this entry is retained only to document the
  gold-landing surface that folds into the merged Blueprint (`refresh_strategy` + the gold `lake_layer` +
  `partition_by`/`cluster_by`). V153 seeds ONE Blueprint (`GenericAggregate`) whose surface is the union; it does NOT
  seed `AggregateMaterialization` as a separate row. Fix-item #8 (LLM fallback) applies `[read]` OP-VOCAB:81.

### IncrementalMerge (MODELING)

- **op-list:** `[read]` `merge-rows (upsert)` (OP-VOCAB:57).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `merge_key` | string[] | true | user | — | no | `column-picker` (multi) |
| `merge_strategy` | enum | false | user | — | no | `select` (`upsert`/…) |
| `soft_delete` | boolean | false | user | — | no | `toggle` |
| `late_data_policy` | string | false | user | — | no | `select` |
| `late_threshold_hours` | integer | false | user | — | no | `number-input` |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |
| `cluster_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |

  Plus storage block (§0.2; → `gold`). `[read]` JSON IncrementalMerge (10 params).
- **ports:** in — `incremental_data` (role: **silver**, new/changed). out — `merged_output` (role: **gold**, updated
  target). `[read]` JSON.
- **notes:** `> RESOLVED (operator 2026-06-16):` `late_data_policy` (typed `string` in JSON, reads enum-like) is modeled
  as `select`, tier `user` — a genuine business choice per the ratified heuristic; #4 owns the option-set definition
  (5-G09).

### ReferenceDataPublish (MODELING)

- **op-list:** `[read]` `keep-columns + deduplicate + filter-rows + write-sink` (OP-VOCAB:58).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `reference_type` | string | true | user | — | no | `text-input` |
| `publish_frequency` | enum | false | user | — | no | `select` (`on_change`/…) |
| `versioned` | boolean | false | user | — | no | `toggle` |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |
| `cluster_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |

  Plus storage block (§0.2; → `gold`). `[read]` JSON ReferenceDataPublish (8 params).
- **ports:** in — `reference_source` (role: **silver/gold**, curated). out — `published_reference` (role: **gold**,
  versioned). `[read]` JSON.
- **notes:** The op-list ends in `write-sink` (a self-publishing modeling Blueprint). Fix-item #8 (LLM fallback) applies
  `[read]` OP-VOCAB:81. `> RESOLVED (operator 2026-06-16):` `reference_type` free text → `user` + `text-input` per the
  ratified heuristic (a genuine business label).

### FeatureTablePublish (MODELING)

- **op-list:** `[read]` `join + group-and-aggregate + keep-columns + write-sink` (OP-VOCAB:59).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `entity_key` | string | true | user | — | no | `column-picker` |
| `features` | object[] | true | user | — | no | `feature-builder` (feature definitions) |
| `point_in_time_column` | string | true | user | — | no | `column-picker` |
| `output_format` | enum | false | system-derived | `pipeline.storage` (overlaps `lake_format`) | no | (inspector-readonly) |
| `partition_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |
| `cluster_by` | string[] | false | system-derived | `pipeline.storage` | no | (inspector-readonly) |

  Plus storage block (§0.2; → `gold`). `[read]` JSON FeatureTablePublish (9 params).
- **ports:** in — `source_data` (role: **silver/gold**). out — `feature_output` (role: **gold**, ML-ready). `[read]` JSON.
- **notes:** Fix-item #8 (LLM fallback) applies `[read]` OP-VOCAB:81. `> RESOLVED (operator 2026-06-16):` `output_format`
  = `derived` — it duplicates `lake_format` (a storage convention, `pipeline.storage`), so it resolves with the storage
  block per the ratified heuristic (not surfaced as a separate ML-format `user` choice). `> RESOLVED (operator
  2026-06-16):` `feature-builder` construct stands — owned/defined at #4 (5-G09).

---

# DATA_QUALITY (4, GX — `compute:"gx"`)

> DQ Blueprints carry **only** `storage_backend` from the storage block (no `lake_layer`/`lake_format` — they don't
> land a medallion table) `[read]` JSON (each DQ row has just `storage_backend`). DQValidator splits into validated +
> quarantine ports; the others emit a report side-output.

### DQValidator (DATA_QUALITY)

- **op-list:** `[read]` `check-data` (quarantine → managed table) (OP-VOCAB:61). A FAIL raises and fails the run when
  `on_failure=block`; quarantine = a `filter-rows`-derived managed side-table `[read]` SPEC-schema-op-engine.md:336-339.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `expectations` | object[] | true | user | — | no | `dq-expectation-builder` (GX `{type,kwargs,severity}`) |
| `on_failure` | enum | false | user | — | no | `dq-outcome-control` (quarantine/block/warn) |
| `threshold_percent` | number | false | user | — | no | `number-input` (min success %) |
| `mostly` | number | false | user | — | no | `number-input` (0.0–1.0 GX mostly) |

  Plus `storage_backend` only (§0.2). `[read]` JSON DQValidator (5 params).
- **ports:** in — `data_to_validate` (role: **silver/gold**). out — `validated_output` (role: **silver/gold**, passed)
  **+** `quarantine_output` (role: **quarantine**, failed) `[read]` JSON.
- **notes:** `on_failure` is the canonical **`dq-outcome-control`** construct (singular token; #4, named in
  SPEC-INDEX.md:20). `> RESOLVED (operator 2026-06-16):` `dq-expectation-builder` construct (per-expectation rows of GX
  type/kwargs/severity) stands — owned/defined at #4 (5-G09).

### FreshnessChecks (DATA_QUALITY)

- **op-list:** `[read]` `check-data + emit-report` (OP-VOCAB:61). `emit-report` = append-only report table (FIX #7 — today
  overwrites) `[read]` SPEC-schema-op-engine.md:340-342.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `timestamp_column` | string | true | user | — | no | `column-picker` |
| `max_age_minutes` | integer | false | user | — | no | `number-input` (sub-hour SLA) |
| `max_age_hours` | integer | false | user | — | no | `number-input` (default 24) |
| `max_age_business_days` | integer | false | user | — | no | `number-input` (business-day SLA) |
| `holiday_calendar_id` | enum | false | system-derived | `domain.calendar` | no | (inspector-readonly) |
| `fiscal_offset_months` | integer | false | system-derived | `domain.calendar` (unused here `[read]` JSON) | no | (inspector-readonly) |

  Plus `storage_backend` only (§0.2). `[read]` JSON FreshnessChecks (7 params).
- **ports:** in — `monitored_dataset` (role: **any**). out — `freshness_result` (role: **report**, append-only). `[read]` JSON.
- **notes:** The three `max_age_*` are alternative SLA ceilings — a real Customer choice (which SLA dimension) → `user`.
  `holiday_calendar_id`/`fiscal_offset_months` derive from the domain calendar (consistent with ADR 0023).

### SchemaDriftDetection (DATA_QUALITY)

- **op-list:** `[read]` `check-data + emit-report` (OP-VOCAB:61).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `expected_columns` | object[] | true | user | — | no | `schema-spec-builder` (`[{name,type}]`) |
| `strict_order` | boolean | false | user | — | no | `toggle` |
| `allow_extra_columns` | boolean | false | user | — | no | `toggle` |
| `drift_policy` | enum | false | user | — | no | `dq-outcome-control` (`warn`/block/…) |

  Plus `storage_backend` only (§0.2). `[read]` JSON SchemaDriftDetection (5 params).
- **ports:** in — `incoming_data` (role: **any**). out — `drift_report` (role: **report**, append-only). `[read]` JSON.
- **notes:** `> RESOLVED (operator 2026-06-16):` `expected_columns` = **`user`** (5-G05) — drift detection is semantically
  "I assert this baseline"; auto-deriving it from the current contract would defeat the purpose (you'd never detect drift
  from intent). It does NOT flip to system-derived. `> RESOLVED (operator 2026-06-16):` `schema-spec-builder` construct
  stands — owned/defined at #4 (5-G09).

### AnomalyDetection (DATA_QUALITY)

- **op-list:** `[read]` `check-data + emit-report` (OP-VOCAB:61).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `monitored_columns` | string[] | true | user | — | no | `column-picker` (multi) |
| `sensitivity_percent` | number | false | user | — | no | `number-input` (default 2.0) |
| `detection_method` | enum | false | user | — | no | `select` (`z_score`/…) |
| `lookback_runs` | integer | false | user | — | no | `number-input` (default 10) |
| `volume_monitoring` | boolean | false | user | — | no | `toggle` |

  Plus `storage_backend` only (§0.2). `[read]` JSON AnomalyDetection (6 params).
- **ports:** in — `data_to_monitor` (role: **any**). out — `anomaly_report` (role: **report**, append-only). `[read]` JSON.
- **notes:** Fix-item #7 (report mode + raise-on-FAIL) applies to all three report DQ Blueprints `[read]` OP-VOCAB:80 —
  a #2 codegen fix, not a param change here.

---

# SINK (4, PySpark — `compute:"pyspark"`)

> All SINK = `write-sink(target, mode)` `[read]` OP-VOCABULARY-AND-DECOMPOSITION.md:63. One **input** port, **no
> output** port (terminal). The connector/target selection is the Customer choice; the resulting refs are
> connector-derived (ADR 0023 RemotePipelineInvocation pattern generalized to sinks). SINK rows do NOT carry the §0.2
> `lake_layer` block; `lake_format`/`output_path` are sink-specific.

### WarehouseWriter (SINK)

- **op-list:** `[read]` `write-sink(target, mode)` (OP-VOCAB:63) — Warehouse target.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `target_id` | string | true | user | — | no | `sink-target-picker` (SoR target select) |
| `connector_instance_id` | string | true | system-derived | `connector` (the chosen target's instance) | no | (inspector-readonly) |
| `connector_name` | string | false | system-derived | `connector` (auto-populated `[read]` JSON) | no | (inspector-readonly) |
| `target_credential_ref` | string | false | system-derived | `connector` (override default binding) | no | (inspector-readonly) |
| `target_table` | string | true | user | — | no | `text-input` (fully-qualified table) |
| `write_mode` | enum | false | user | — | no | `select` (`overwrite_partition`/`merge_on_pk`/…) |
| `merge_keys` | string[] | false | user | — | no | `column-picker` (req. when `write_mode=merge_on_pk`) |
| `batch_size` | integer | false | system-derived | `platform_default` (=10000) | no | (inspector-readonly) |
| `clustering_columns` | string[] | false | system-derived | `pipeline.storage` (must exist on dest) | no | (inspector-readonly) |

  `[read]` JSON WarehouseWriter (9 params). No §0.2 block (SINK).
- **ports:** in — `data_input` (role: **gold**). out — none (terminal). `[read]` JSON.
- **notes:** `target_id` is the genuine choice; `connector_instance_id`/`connector_name`/`target_credential_ref` derive
  from it (ADR 0023 generalization). `> RESOLVED (operator 2026-06-16):` `sink-target-picker` construct stands —
  owned/defined at #4 (5-G09). `> RESOLVED (operator 2026-06-16):` `clustering_columns` default `derived` (a storage/perf
  convention), **user-flippable to `tier: user` as a power-user override** per the ratified §0.1 carve-out (same family as
  `partition_by`/`cluster_by`).

### LakeWriter (SINK)

- **op-list:** `[read]` `write-sink(target, mode)` (OP-VOCAB:63) — Lake target (Delta/Iceberg).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `target_id` | string | true | user | — | no | `sink-target-picker` |
| `connector_instance_id` | string | true | system-derived | `connector` | no | (inspector-readonly) |
| `connector_name` | string | false | system-derived | `connector` | no | (inspector-readonly) |
| `target_credential_ref` | string | false | system-derived | `connector` | no | (inspector-readonly) |
| `lake_format` | enum | true | system-derived | `pipeline.storage` (selects delta vs iceberg codegen `[read]` JSON) | no | (inspector-readonly) |
| `output_path` | string | true | user | — | no | `text-input` (s3a://… / catalog.namespace.table) |
| `write_mode` | enum | false | user | — | no | `select` (`merge_on_pk`/`append_partition`) |
| `merge_keys` | string[] | false | user | — | no | `column-picker` (req. when `write_mode=merge_on_pk`) |
| `optimize_after_write` | boolean | false | user | — | no | `toggle` |
| `z_order_columns` | string[] | false | user | — | no | `column-picker` (req. when `optimize_after_write=true`) |

  `[read]` JSON LakeWriter (10 params).
- **ports:** in — `data_input` (role: **gold**). out — none (terminal). `[read]` JSON.
- **notes:** `lake_format` here selects the codegen variant; tiered system-derived (storage Mode) consistent with §0.2.
  `output_path` kept `user` (a real destination choice). `> RESOLVED (operator 2026-06-16):` `optimize_after_write`
  (and its dependent `z_order_columns`) stay **`user`** — an explicit opt-in DE tuning decision (run OPTIMIZE/Z-ORDER or
  not, not derivable from grain/keys, not safe-default plumbing), so the ratified heuristic's tie-break-toward-`user`
  applies. (Distinct from `clustering_columns`, which IS a storage convention → derived-with-override.)

### DatabaseWriter (SINK)

- **op-list:** `[read]` `write-sink(target, mode)` (OP-VOCAB:63) — Database (JDBC) target.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `target_id` | string | true | user | — | no | `sink-target-picker` |
| `connector_instance_id` | string | true | system-derived | `connector` | no | (inspector-readonly) |
| `connector_name` | string | false | system-derived | `connector` | no | (inspector-readonly) |
| `target_credential_ref` | string | false | system-derived | `connector` | no | (inspector-readonly) |
| `target_table` | string | true | user | — | no | `text-input` (e.g. analytics.fct_orders) |
| `write_mode` | enum | false | user | — | no | `select` (append/overwrite_partition/merge_on_pk) |
| `upsert_keys` | string[] | false | user | — | no | `column-picker` (req. when `write_mode=merge_on_pk`) |
| `batch_size` | integer | false | system-derived | `platform_default` (=5000) | no | (inspector-readonly) |
| `connection_pool_size` | integer | false | system-derived | `platform_default` (=5) | no | (inspector-readonly) |
| `partition_by` | string[] | false | user | — | no | `column-picker` (must exist on dest) |

  `[read]` JSON DatabaseWriter (10 params).
- **ports:** in — `data_input` (role: **gold**). out — none (terminal). `[read]` JSON.
- **notes:** `> RESOLVED (operator 2026-06-16):` `partition_by` stays **`user`** here — it is a destination-table
  partition choice (the column must exist on the dest table), a genuine DE decision distinct from the upstream
  storage-convention `partition_by` (which defaults derived). This is exactly the power-user case the ratified §0.1
  override anticipates.

### StreamWriter (SINK)

- **op-list:** `[read]` `write-sink(target, mode)` (OP-VOCAB:63) — Stream (Kafka) target.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `target_id` | string | true | user | — | no | `sink-target-picker` |
| `connector_instance_id` | string | true | system-derived | `connector` | no | (inspector-readonly) |
| `connector_name` | string | false | system-derived | `connector` | no | (inspector-readonly) |
| `target_credential_ref` | string | false | system-derived | `connector` | no | (inspector-readonly) |
| `topic` | string | true | user | — | no | `text-input` (destination Kafka topic) |
| `publish_mode` | enum | false | user | — | no | `select` (`batch_publish`/`streaming_publish`) |
| `schema_strategy` | enum | false | user | — | no | `select` (`json_envelope`/`avro_schema_registry`) |
| `key_columns` | string[] | false | user | — | no | `column-picker` (multi; hashed into Kafka key) |
| `delivery_guarantee` | enum | false | user | — | no | `select` (`at_least_once`/`exactly_once`) |
| `checkpoint_location` | string | false | system-derived | `pipeline.storage` (req. for streaming_publish; unique per pipeline) | no | (inspector-readonly) |

  `[read]` JSON StreamWriter (10 params).
- **ports:** in — `data_input` (role: **gold**). out — none (terminal). `[read]` JSON.
- **notes:** `checkpoint_location` MUST be unique per pipeline (`[read]` JSON: "sharing checkpoints corrupts state") →
  PULSE should derive it from the pipeline identity, not have the Customer hand-type it → system-derived (ADR 0023
  exemplar of plumbing PULSE resolves). `> RESOLVED (operator 2026-06-16):` `checkpoint_location` = `derived` with
  derivation = a **unique-per-pipeline** path from `pipeline.id` + step (ADR 0023 explicitly requires it be unique per
  pipeline; 5-G07). The Customer never hand-types it.

---

# CONTROL (7, DAG-only — `compute:null`, no schema effect)

> CONTROL Blueprints are **portless w.r.t. data** (no column schema-effect) `[read]` SPEC-schema-op-engine.md:375-381.
> Sensors expose a `ready_signal` output and trigger blocks expose no ports; AdvanceTimeDimension/RemotePipelineInvocation
> have a `trigger` input + a status/completion output (`[read]` JSON / V131). These are **DAG control facts**, not
> dataset ports. Each carries `storage_backend` (the §0.2 block reduces to this one field for portless control).

### FileArrivalSensor (CONTROL)

- **op-list:** `[read]` `sense` (OP-VOCAB:65; `sense` op).
- **params (17 — `[read]` JSON; the genuine user surface is the file location + match + SLA):**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `storage_kind` | enum | true | user | — | no | `select` (`s3`/`gcs`/`sftp`) |
| `bucket` | string | true | user | — | no | `text-input` (`{tenant}_{domain}` convention) |
| `path_prefix` | string | true | user | — | no | `text-input` |
| `filename_pattern` | string | true | user | — | no (uses `{date}`) | `text-input` (filename template) |
| `pattern_kind` | enum | false | user | — | no | `select` (template/glob/exact) |
| `date_format` | string | false | system-derived | `platform_default` (`yyyyMMdd`) | no | (inspector-readonly) |
| `date_value` | string | false | user | — | **yes** (mnemonic, default `RUN_DATE`) | `date-mnemonic-picker` |
| `expected_size_min` | integer | false | system-derived | `platform_default` (=0) | no | (inspector-readonly) |
| `expected_max_age_hours` | integer | false | user | — | no | `number-input` (stale-file guard) |
| `multiple_files_mode` | boolean | false | user | — | no | `toggle` |
| `soft_fail` | boolean | false | user | — | no | `toggle` |
| `poke_interval_seconds` | integer | false | system-derived | `platform_default` (=300) | no | (inspector-readonly) |
| `timeout_seconds` | integer | false | system-derived | `platform_default` (=14400 / 4h) | no | (inspector-readonly) |
| `mode` | enum | false | system-derived | `platform_default` (`reschedule`) | no | (inspector-readonly) |
| `holiday_calendar_id` | enum | false | system-derived | `domain.calendar` | no | (inspector-readonly) |
| `fiscal_offset_months` | integer | false | system-derived | `domain.calendar` | no | (inspector-readonly) |

  Plus `storage_backend` (system-derived; §0.2). `[read]` JSON FileArrivalSensor (17 params).
- **ports:** in — none. out — `ready_signal` (role: **signal**, DAG dependency) `[read]` JSON.
- **notes:** **Absorbs the deprecating ObjectStoreKeySensor** (`ObjectStoreKeySensor ⊂ FileArrivalSensor`
  `[read]` OP-VOCAB:66,87). `> RESOLVED (operator 2026-06-16):` the poke/timeout/mode reliability knobs = `derived`
  (§0.1 `platform_default` class per the ratified heuristic; 5-G04) — no per-sensor `user` exception. The `{date}` +
  `date_value` mnemonic facility aligns with the #4 `date-mnemonic-picker` (same unification as FileIngestion; 5-G10).

### DatabaseReadinessSensor (CONTROL)

- **op-list:** `[read]` `sense` (OP-VOCAB:65). The decomposition groups `DatabaseReadinessSensor` under `sense`
  `[read]` SPEC-schema-op-engine.md:376 (rule 28). `> RESOLVED (operator 2026-06-16):` confirmed `sense` — OP-VOCAB:65
  lists DatabaseReadinessSensor under `sense` (alongside FileArrivalSensor / ExternalEventSensor); this is a `[read]`
  fact, not a judgment call.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `sql` | string | true | user | — | **yes** (`[[ … ]]` per ADR 0024; today uses `{date}`) | `expression-builder` (probe SQL) |
| `connection_id` | string | false | system-derived | `connector` (Airflow conn; default `pulse_sql_default`) | no | (inspector-readonly) |
| `date_format` | string | false | system-derived | `platform_default` (`yyyy-MM-dd`) | no | (inspector-readonly) |
| `date_value` | string | false | user | — | **yes** (mnemonic, default `RUN_DATE`) | `date-mnemonic-picker` |
| `expected_count_min` | integer | false | user | — | no | `number-input` (default 1) |
| `expected_count_max` | integer | false | user | — | no | `number-input` (runaway-load guard) |
| `poke_interval_seconds` | integer | false | system-derived | `platform_default` (=300) | no | (inspector-readonly) |
| `timeout_seconds` | integer | false | system-derived | `platform_default` (=14400) | no | (inspector-readonly) |
| `mode` | enum | false | system-derived | `platform_default` (`reschedule`) | no | (inspector-readonly) |
| `holiday_calendar_id` | enum | false | system-derived | `domain.calendar` | no | (inspector-readonly) |
| `fiscal_offset_months` | integer | false | system-derived | `domain.calendar` | no | (inspector-readonly) |

  Plus `storage_backend` (system-derived). `[read]` JSON DatabaseReadinessSensor (12 params).
- **ports:** in — none. out — `ready_signal` (role: **signal**) `[read]` JSON.
- **notes:** `connection_id` is connector-derived (the source DB the Customer already bound). `sql` is the canonical
  SQL-expression param inheriting ADR-0024 inline mnemonics `[read]` SPEC-calcite-sql-model.md §D.6.

### ExternalEventSensor (CONTROL)

- **op-list:** `[read]` `sense` (OP-VOCAB:65).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `event_url` | string | true | user | — | no | `text-input` (event endpoint) |
| `success_status_code` | integer | false | system-derived | `platform_default` (=200) | no | (inspector-readonly) |
| `poke_interval_seconds` | integer | false | system-derived | `platform_default` (=300) | no | (inspector-readonly) |
| `timeout_seconds` | integer | false | system-derived | `platform_default` (=3600) | no | (inspector-readonly) |

  Plus `storage_backend` (system-derived). `[read]` JSON ExternalEventSensor (5 params).
- **ports:** in — none. out — `ready_signal` (role: **signal**) `[read]` JSON.
- **notes:** `> RESOLVED (operator 2026-06-16):` `success_status_code` = `derived` (200 is the safe default, §0.1
  `platform_default` class per the ratified heuristic) — no `user` exception for non-200 codes by default.

### ScheduleAndTriggers (CONTROL)

- **op-list:** `[read]` `schedule-and-triggers` (OP-VOCAB:65; rule 29 `[read]` SPEC-schema-op-engine.md:377).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `schedule_type` | enum | true | user | — | no | `segmented-control` (cron/event/manual) |
| `cron_expression` | string | false | user | — | no | `cron-builder` (req. when schedule_type=cron) |
| `trigger_dataset` | string | false | user | — | no | `dataset-picker` (req. when schedule_type=event) |
| `timezone` | string | false | system-derived | `domain.calendar` (IANA tz; default `UTC`) | no | (inspector-readonly) |
| `max_active_runs` | integer | false | system-derived | `platform_default` (=1) | no | (inspector-readonly) |
| `catchup_enabled` | boolean | false | user | — | no | `toggle` |
| `depends_on_past` | boolean | false | user | — | no | `toggle` |
| `retry_count` | integer | false | system-derived | `platform_default` (=3) | no | (inspector-readonly) |

  Plus `storage_backend` (system-derived). `[read]` JSON ScheduleAndTriggers (9 params).
- **ports:** in — none. out — none (a DAG-level schedule/trigger block; no port) `[read]` JSON.
- **notes:** **Absorbs the deprecating DatasetDependencySensor** triggers (`DatasetDependencySensor → ScheduleAndTriggers
  triggers` `[read]` OP-VOCAB:66). `> RESOLVED (operator 2026-06-16):` `timezone` = `derived` ← `domain.calendar`
  (consistent with the calendar facility; 5-G08) per the ratified heuristic. `cron-builder` construct stands —
  owned/defined at #4 (5-G09).

### RollbackOnFailure (CONTROL)

- **op-list:** `[read]` `rollback` (OP-VOCAB:65; rule 30 `[read]` SPEC-schema-op-engine.md:378).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `rollback_trigger` | enum | false | user | — | no | `select` (`deploy_failure`/…) |
| `keep_failed_artifacts` | boolean | false | user | — | no | `toggle` |

  Plus `storage_backend` (system-derived). `[read]` JSON RollbackOnFailure (3 params).
- **ports:** in — none. out — none `[read]` JSON.
- **notes:** Minimal surface (2 user knobs). Good ADR-0023 citizen.

### AdvanceTimeDimension (CONTROL)

- **op-list:** `[read]` `advance-time` (OP-VOCAB:65; rule 31 `[read]` SPEC-schema-op-engine.md:379).
- **params (INTENT surface per ADR 0023:11 — 2 user fields + 17 system-derived from the V132 runtime contract; NOT the
  stale 4-field JSON row):**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `target_scope` | enum | true | **user** | — | no (Chat MUST ask, never silently default `[read]` ADR 0023:11) | `segmented-control` (`dataset`/`domain`) |
| `advance_to` | string | false | **user** | — | **yes** (date mnemonic or ISO; blank = next interval per grain) | `date-mnemonic-picker` |
| `state_binding_ref` | string | true | system-derived | `target_dataset.state_binding` | no | (inspector-readonly) |
| `variable_key` | string | true | system-derived | `target_dataset.state_binding` | no | (inspector-readonly) |
| `calendar_binding_ref` | string | true | system-derived | `domain.calendar` | no | (inspector-readonly) |
| `calendar_bundle_uri` | string | true | system-derived | `domain.calendar` (packaged bundle) | no | (inspector-readonly) |
| `calendar_bundle_hash` | string | false | system-derived | `domain.calendar` (package-stamped) | no | (inspector-readonly) |
| `calendar_id` | string | false | system-derived | `domain.calendar` (default `US-FED`) | no | (inspector-readonly) |
| `grain` | string | false | system-derived | `domain.calendar` (default `DAILY_BUSINESS_DAY`) | no | (inspector-readonly) |
| `timezone` | string | false | system-derived | `domain.calendar` (default `America/New_York`) | no | (inspector-readonly) |
| `replay_policy` | enum | false | system-derived | `platform_default` (`reject_backward`) | no | (inspector-readonly) |
| `initial_value` | string | false | system-derived | `platform_default` | no | (inspector-readonly) |
| `initialization_policy` | enum | false | system-derived | `platform_default` (`require_existing`) | no | (inspector-readonly) |
| `concurrency_policy` | enum | false | system-derived | `platform_default` (`serialized_airflow`) | no | (inspector-readonly) |
| `evidence_prefix` | string | true | system-derived | `platform_default` (evidence object-store prefix) | no | (inspector-readonly) |
| `evidence_required` | boolean | false | system-derived | `platform_default` (=true) | no | (inspector-readonly) |
| `notes_template` | string | false | system-derived | `platform_default` | no | (inspector-readonly) |
| `advanced_by` | string | false | system-derived | `platform_default` (actor) | no | (inspector-readonly) |
| `source` | string | false | system-derived | `platform_default` | no | (inspector-readonly) |

  `[read]` ADR 0023:11 (2-field user surface) + V132:8-29 (the 20-field runtime schema). `advance_to` **consolidates**
  the V132 `advance_mode` + `requested_asof_expr` into one user field `[read]` ADR 0023:11.
- **ports:** in — `trigger` (role: **signal**, "connect to the last processing step"). out — `status` (role: **signal**,
  previous + new as-of values) `[read]` JSON.
- **notes:** **DEVIATION-FROM-TODAY (fix-item):** the active-blueprints JSON row is **stale** — 4 fields
  (`dataset_name`/`advance_domain`/`notes`/`storage_backend`) `[read]` JSON. V132 already replaced it with the 20-field
  runtime contract `[read]` V132:8-29; ADR 0023 then locked the **2-field user surface** over it. This entry is
  INTENT-CANONICAL on ADR 0023 + V132. `> RESOLVED (operator 2026-06-16):` the **2-user / 17-derived intent surface is
  RATIFIED** over the stale 4-field JSON, including the `advance_to` consolidation of `advance_mode`+`requested_asof_expr`
  (5-G13) — it is the ADR-0023 minimal-surface treatment and V153 re-seeds it. V153 MUST seed the V132 schema with
  `tier`/`derivedFrom` per this table and add the consolidated `advance_to` (dropping
  `advance_mode`+`requested_asof_expr` from the user surface — they become the blank/next-interval logic).
  **Prerequisite gap (`[read]` ADR 0023:12):** the derived calendar assumes the domain has
  one; `create_domain` never sets `businessDateConfig`, so resolution silently falls to `US-FED` — tracked, out of #5's scope.

### RemotePipelineInvocation (CONTROL)

- **op-list:** `[read]` `invoke-remote` (OP-VOCAB:65; rule 32 `[read]` SPEC-schema-op-engine.md:380).
- **params (`[read]` V131:17-28; tiering per ADR 0023:13 — the Customer picks the peer target, broker refs derive):**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `remote_target_ref` | string | true | **user** | — (the peer-target selection) | no | `peer-target-picker` |
| `remote_dag_id` | string | false | **user** | — (the remote pipeline) | no | `remote-dag-picker` |
| `environment` | string | true | system-derived | `pipeline` (the executing environment) | no | (inspector-readonly) |
| `federated_tenant_key` | string | true | system-derived | `connector`/target (derived from the selection `[read]` ADR 0023:13) | no | (inspector-readonly) |
| `airflow_connection_id` | string | true | system-derived | `connector`/target (broker ref `[read]` ADR 0023:13) | no | (inspector-readonly) |
| `poll_interval_seconds` | integer | false | system-derived | `platform_default` | no | (inspector-readonly) |
| `timeout_seconds` | integer | false | system-derived | `platform_default` | no | (inspector-readonly) |
| `payload_template` | object | false | user | — | no | `key-value-mapper` (remote payload) |

  `[read]` V131:17-28 (8 params) + ADR 0023:13 (tiering).
- **ports:** in — `trigger` (role: **signal**, upstream dependency). out — `remote_completion` (role: **signal**, remote
  completion facts) `[read]` V131:27-28.
- **notes:** **39th survivor — NOT in the active-blueprints JSON** (sourced from V131). The broker refs
  (`federated_tenant_key`/`remote_target_ref`/`airflow_connection_id`) are the ADR-0023 §13 exemplar: derived from the
  target selection, never typed. `> RESOLVED (operator 2026-06-16):` `peer-target-picker` / `remote-dag-picker`
  constructs stand — owned/defined at #4 (5-G09). `> RESOLVED (operator 2026-06-16):` `poll_interval_seconds` /
  `timeout_seconds` = `derived` (§0.1 `platform_default` class); the V131 params carry no `default`, so the platform
  default is supplied at build (5-G04).

---

# NEW SQL BLUEPRINTS (2 — ADR 0024 / SPEC-calcite-sql-model.md)

### SqlModel (TRANSFORM)

- **op-list:** `[read]` `sql-model` (the single op 27; ADR 0024:18 + SPEC-calcite-sql-model.md §B). **Required input
  port.** The op carries `steps: [{name, sql, materialize}]` — an ordered chain of discrete statements; the **last
  step's SELECT is the output schema** (Calcite-derived, or declared fallback) `[read]` SPEC-calcite-sql-model.md:148-191.
  Emission `compute:"dbt"` (dbt-SQL) `[read]` OP-VOCAB:36.
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `steps` | object[] | true | user | — | **yes** (each step's `sql` accepts `[[ … ]]` `[read]` ADR 0024:26) | `sql-chain-editor` (`[{name,sql,materialize}]`) |

  `> RESOLVED (operator 2026-06-16):` the declared-schema fallback adds a `declared_output_schema` (object[]) param when
  Calcite can't parse a construct (SPEC-calcite-sql-model.md §A.6), modeled as a `user`-tier `schema-spec-builder`
  (V153-seeded; reconciled at #6, 5-G11/6-G06). Plus the universal storage block (§0.2; → `silver` default). `> RESOLVED
  (operator 2026-06-16):` `SqlModel.lake_layer` default = **silver** (5-G12) — a SqlModel transform is the typical
  bronze→silver/silver→silver hop; gold remains reachable via the §0.1 storage block, but silver is the default.
- **ports:** in — **`input`** (REQUIRED, role: **any upstream** — the reserved relation name the SQL references
  `[read]` ADR 0024:18). out — `sql_output` (role: **silver**, the Calcite-derived result schema). `> RESOLVED (operator
  2026-06-16):` output port name = `sql_output`, input port name = `input` (ADR 0024 pins the *relation* name `input`;
  the *port* name follows it to match). Reconciled with #6 §B.1 before V153 seeds (5-G11/6-G06).
- **notes:** **NEW (ADR 0024).** `materialize:true|false` per step is a perf hint, schema-irrelevant
  `[read]` SPEC-calcite-sql-model.md §B.1 (the `steps:[{name,sql,materialize}]` chain) + §B.3 (materialize emission). The `sql-chain-editor` is the #4 multi-statement construct with per-step
  materialize toggle + inline `date-mnemonic-picker` tokens. `> RESOLVED (operator 2026-06-16):` the user param surface
  is the single `steps` array (plus the `declared_output_schema` fallback above); every other knob is derived. No
  separate `materialize_default` param — `materialize` is per-step inside `steps`.

### SourceSQL (INGESTION)

- **op-list:** `[read]` `read-source(source_query, JDBC connector) → add-audit-columns → write-sink(bronze)`
  (ADR 0024:19; SPEC-calcite-sql-model.md §C.1). Reuses the existing `read-source` op (no new op). Output port = the
  **source-derived** result schema — NOT Calcite: schema derivation round-trips to the bound JDBC source
  (`PreparedStatement.getMetaData()` / `SELECT … WHERE 1=0` / `LIMIT 0`), mapping JDBC types → PULSE types `[read]`
  ADR 0024:19 + SPEC-calcite-sql-model.md §C.2 (LOCKED 2026-06-16 — Calcite is `sql-model`-only).
  Emission `compute:"pyspark"` (ingestion).
- **params:**

| name | type | required | tier | derivedFrom | accepts_mnemonic | UI-construct (#4) |
|---|---|---|---|---|---|---|
| `source_query` | string | true | user | — | **yes** (`[[ … ]]` `[read]` ADR 0024:26) | `simple-sql-builder` (single source-SELECT; Validate button round-trips to the bound JDBC source, NOT Calcite `[read]` ADR 0024:19 + SPEC-calcite-sql-model.md §C.2) |
| `connector_instance_id` | string | true | system-derived | `connector` (JDBC connector + credential profile = SecretRefs) | no | (inspector-readonly) |

  Plus the universal storage block (§0.2; → `bronze`). `> RESOLVED (operator 2026-06-16):` param names = `source_query`
  (aligned to `BulkBackfill.source_query` `[read]` SPEC-calcite-sql-model.md §C.1) and **`connector_instance_id`** — the
  connector field is normalized to the **`connector_instance_id`** SINK convention (6-G06; was `connector_id`).
  `> RESOLVED (operator 2026-06-16):` a `declared_output_schema` fallback param is added (user-tier `schema-spec-builder`)
  for when the source is unreachable at design time (SPEC-calcite-sql-model.md §C.2; parallels SqlModel's fallback).
- **ports:** in — none (it reads from a connector, like all ingestion). out — `source_output` (role: **bronze**, the
  **source-derived** result schema via JDBC prepare/metadata, NOT Calcite `[read]` SPEC-calcite-sql-model.md §C.2).
  `> RESOLVED (operator 2026-06-16):` output port name = `source_output` (mirrors other ingestion `*_output` names);
  reconciled with #6 §C.1 before V153 seeds (5-G11/6-G06).
- **notes:** **NEW (ADR 0024).** A dedicated connector-bound ingestion Blueprint — NOT a repurposed BulkBackfill
  `[read]` ADR 0024:24. The chain/`materialize` of SqlModel does NOT apply (one source query) `[read]` SPEC-calcite-sql-model.md §C.3.
  Feeds SqlModel `[read]` ADR 0024:19.

---

## DEPRECATING (4, OUT of the active catalog — verified active TODAY, V153-deprecated; NOT in #5's 39 active set)

These 4 are `status:active` in the live JSON today but are **deprecated by V153** and are deliberately **excluded** from
this spec's 41 active entries `[read]` OP-VOCABULARY-AND-DECOMPOSITION.md:66 (the DEPRECATING line). Listed here so the
gate's completeness pass sees them accounted for, not dropped:

| Blueprint (category) | V153 disposition | Replacement | `[read]` |
|---|---|---|---|
| `CostMonitoringHook` (ORCHESTRATION) | deprecate | none (platform concern) | OP-VOCAB:66; JSON row exists (5 params) |
| `BackfillAndReplay` (ORCHESTRATION) | deprecate | `BulkBackfill` + `ScheduleAndTriggers` | OP-VOCAB:66,87; JSON row exists (7 params, `start_date`/`end_date` accept mnemonics) |
| `ObjectStoreKeySensor` (ORCHESTRATION) | deprecate | `FileArrivalSensor` (⊂) | OP-VOCAB:66,87; JSON row exists (16 params) |
| `DatasetDependencySensor` (ORCHESTRATION) | deprecate | `ScheduleAndTriggers` triggers | OP-VOCAB:66,87; JSON row exists (6 params) |

> **Reconciliation note (count audit):** the live JSON has **42 active rows** = 38 of the 39 survivors + these 4
> deprecating. The 39th survivor, **`RemotePipelineInvocation`**, is **not** in the JSON snapshot (sourced from V131).
> So: 38 (JSON survivors) + 1 (RemotePipelineInvocation from V131) + 2 (new SqlModel/SourceSQL) = **41 entries** above,
> and 42 (JSON) − 4 (deprecating) = 38 JSON survivors. Counts reconcile.

> **Also-noted (not one of the 41): `Derive` / "Derive Columns" (TRANSFORM, V102).** `[read]` V102:33-45 — a standalone
> Blueprint that is **NOT in the active-blueprints JSON** and **NOT in the decomposition doc's TRANSFORM-10 list**. Its
> op-list is exactly the single `add-column` op (1:1 with op rule 1) plus the universal `derived_columns`/`dropped_columns`
> addenda `[read]` SPEC-schema-op-engine.md:382-387. It is a thin wrapper of an op the closed vocabulary already
> provides. **It is intentionally excluded from the 41** because the binding coverage basis is the decomposition doc's
> 39 survivors, which omit it. `> RESOLVED (operator 2026-06-16):` **KEEP `Derive` (V102) as the 40th atomic Blueprint**
> (5-G14) — like `GenericFilter`=`filter-rows`, it is a user-facing intent ("derive a column") and a symmetric 1:1
> wrapper of the `add-column` op; it is NOT folded into the op/addenda. SPEC-calcite-sql-model.md §D.6 references its
> `derived_columns[].expression` as a mnemonic-bearing SQL-expression param `[read]` SPEC-calcite-sql-model.md §D.6, so
> its `expression` is `user`-tier + `expression-builder` + `accepts_mnemonic:yes`. (The coverage basis stays the 39
> decomposition survivors + `SqlModel` + `SourceSQL` = 41; `Derive` is the 40th *Blueprint* counted separately as the
> kept atomic wrapper, not a 42nd gate entry.)

---

## OPEN WORKLIST → RESOLVED (operator 2026-06-16 — all G-items closed; recorded for the build/V153 seed)

> All G-1…G-15 are **RESOLVED by the operator on 2026-06-16** and applied inline above. None remain open.

**Tiering judgment-calls (the §0.1 heuristic is RATIFIED — one ruling closed the ~300 param calls):**
- **G-1 RESOLVED:** the §0.1 tiering heuristic is **RATIFIED as written**, with one carve-out: `partition_by`/`cluster_by`
  (and the same-family `clustering_columns`) default `derived` but are **user-flippable to `tier: user` as a power-user
  override**. Unblocks the ~300 per-param calls.
- **G-2 RESOLVED:** `storage_backend` / `lake_layer` / `lake_format` are **`derived` everywhere** (codegen/deploy-resolved
  via `pipeline.storage`; §0.2).
- **G-3 RESOLVED:** `partition_by` / `cluster_by` default **`derived`** (storage convention) **with the power-user
  `tier: user` override** from G-1. (Destination-table `partition_by` on DatabaseWriter stays `user` — it is a genuine
  dest-table choice, the override case.)
- **G-4 RESOLVED:** reliability knobs (`retry_count`, `poke_interval_seconds`, `timeout_seconds`, `mode`, `chunk_*`,
  `parallelism`, `batch_size`, `connection_pool_size`, `separator`, `success_status_code`, `poll_interval_seconds`) are
  **`derived`** (§0.1 `platform_default` class). No per-knob `user` exception by default.
- **G-5 RESOLVED:** `SchemaDriftDetection.expected_columns` = **`user`** (Customer asserts the baseline; auto-deriving
  from the contract would defeat drift detection). Does NOT flip to system-derived.
- **G-6 RESOLVED:** `optimize_after_write`/`z_order_columns` stay **`user`** (explicit opt-in DE tuning, not derivable);
  `clustering_columns` default **`derived` with the G-1 power-user override**.
- **G-7 RESOLVED:** `StreamWriter.checkpoint_location` = **`derived`**, derivation = a unique-per-pipeline path from
  `pipeline.id` + step (ADR 0023 "must be unique per pipeline").
- **G-8 RESOLVED:** `ScheduleAndTriggers.timezone` = **`derived`** ← `domain.calendar`; ingestion/sensor `date_format` =
  **`derived`** ← `platform_default`. No `user` exception.

**UI-construct bindings (ALL forward references to #4 — confirmed; #4 owns/defines each; #3 panels derive #5 × #4 × #3):**
- **G-9 RESOLVED (forward-ref confirmed):** every named construct stands as a #4-owned forward reference — `rename-mapper`,
  `type-cast-mapper`, `key-value-mapper`, `column-picker`, `condition-builder`, `expression-builder`, `sql-chain-editor`,
  `simple-sql-builder`, `date-mnemonic-picker`, `aggregation-builder`, `window-function-builder`, `join-key-mapper`,
  `join-spec-builder`, `sort-spec-builder`, `struct-builder`, `route-builder`, `cron-builder`, `dq-expectation-builder`,
  `dq-outcome-control` (singular token), `schema-spec-builder`, `feature-builder`, `sink-target-picker`,
  `peer-target-picker`, `remote-dag-picker`, `dataset-picker`, `schema-reference-picker`, `segmented-control`, `select`,
  `toggle`, `text-input`, `number-input`, `multi-select`, `inspector-readonly`. #4 confirms/renames each.
- **G-10 RESOLVED:** the legacy `{date}` + `date_value` facility and the ADR-0024 `[[ … ]]` SQL-token facility **unify at
  the #4 `date-mnemonic-picker`** so the Customer sees one date experience (picker emits `[[ … ]]` in SQL context, `{date}`
  in `filename_pattern` context; 4-W12).

**New-Blueprint shape confirmations (reconciled with SPEC-calcite-sql-model.md §B.1/§C.1 + #1 §A.1 before V153 seeds):**
- **G-11 RESOLVED:** `SqlModel`/`SourceSQL` keys = `steps`, `source_query`, **`connector_instance_id`** (normalized to the
  SINK convention; 6-G06); reserved `input` port name + relation name; output port names `sql_output` / `source_output`;
  the `declared_output_schema` fallback param (user-tier `schema-spec-builder`) is seeded. Reconciled with #6 + #1 §A.1.
- **G-12 RESOLVED:** `SqlModel.lake_layer` default = **silver** (the typical bronze→silver/silver→silver hop).

**Intent / catalog decisions (operator):**
- **G-13 RESOLVED:** `AdvanceTimeDimension`'s **2-user / 17-derived INTENT surface is RATIFIED** over the stale 4-field
  JSON, including the `advance_to` consolidation of `advance_mode`+`requested_asof_expr`. V153 re-seeds it.
- **G-14 RESOLVED:** **KEEP `Derive` (V102) as the 40th atomic Blueprint** (NOT folded into the `add-column` op); its
  `expression` is `user`-tier + `expression-builder` + `accepts_mnemonic:yes`.
- **G-15 RESOLVED:** **MERGE `AggregateMaterialization` ≡ `GenericAggregate`** (one Blueprint, `GenericAggregate`
  surviving); **KEEP `BulkBackfill`, DROP `BackfillAndReplay`** (the latter stays in §DEPRECATING) `[read]` OP-VOCAB:87.

---

## Cross-references

- **#1 `SPEC-schema-op-engine.md`** — §A.1 op-entry shape (`op`/`ui_label`/`config` + `{"param":"<name>"}`) that V153
  serializes these op-lists into; §A.3 param-tiering shape (`tier`/`derivedFrom`); §B the 32 ops' schema-effect rules
  (every op-list above references ops defined there); §B.0 nested-type encoding (JsonFlatten/JsonStruct).
- **#2 `SPEC-codegen-compiler.md`** — §D the **V153 migration** that mechanically serializes every entry above into
  `schema_behavior` + tiered `params_schema`; §C the per-op emission handlers; §E the audit-column set.
- **#3 `docs/ui/SPEC-ui-composition.md`** — the bespoke config **panels DERIVE from this catalog** (× #4 constructs ×
  the panel framework); schema-visibility + conflict-overlay render #1's output over these ports.
- **#4 `docs/ui/SPEC-construct-library.md` (to author)** — defines every UI-construct named above (see G-9). All
  construct refs in this spec are **forward references** to #4.
- **ADR 0023** (param-tiering — `tier`/`derivedFrom`, minimal user surface) · **ADR 0024** (SqlModel/SourceSQL/Calcite/
  inline `[[ … ]]` mnemonics) · **ADR 0011** (deterministic zero-LLM schema) · **ADR 0012** (closed 32-op vocabulary) ·
  **ADR 0013** (deterministic Builder) · **ADR 0010** (the SPEC-GATE this spec must pass).
- **`SPEC-calcite-sql-model.md`** (the SqlModel/SourceSQL contract this catalog adds the catalog rows for).
- **`OP-VOCABULARY-AND-DECOMPOSITION.md`** (the op-list authority) · **`api-active-blueprints.json`** (params + ports).
