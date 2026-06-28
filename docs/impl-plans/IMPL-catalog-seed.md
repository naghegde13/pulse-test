# IMPL — #5 Blueprint catalog + V153 seed migration + demo-pipeline seed regeneration

> **Type:** build plan (NOT code). Producer artifact for a building agent. Every load-bearing fact
> carries a `[read]` tag with `file:line`. `> GUESS:` marks an inference the author had to make that a
> verifier should re-confirm. This plan is the build-order for turning the **#5 catalog spec** + the
> locked **op-list decomposition** into the **V153 migration** and a **regenerated demo-pipeline seed**
> that renders 100% in the composition page (the operator's acceptance criterion).
>
> **Inputs read for this plan:**
> - `[read]` `docs/build-specs/SPEC-blueprint-catalog.md:14-19` — coverage basis: **39 survivors + SqlModel
>   + SourceSQL = 41**, INGESTION 6 / TRANSFORM 10 / MODELING 8 / DATA_QUALITY 4 / SINK 4 / CONTROL 7 = 39,
>   plus the **4 DEPRECATING** (CostMonitoringHook, BackfillAndReplay, ObjectStoreKeySensor, DatasetDependencySensor)
>   explicitly OUT of the active set `[read]` SPEC-blueprint-catalog.md:1073-1084.
> - `[read]` `docs/build-specs/SPEC-codegen-compiler.md:253-319` — §D the V153 migration contract.
> - `[read]` `docs/build-specs/SPEC-schema-op-engine.md:46-103` — §A.1 the `schema_behavior` op-list JSON
>   shape V153 serializes; `:147-181` §A.3 the param-tiering shape.
> - `[read]` `docs/blueprints/OP-VOCABULARY-AND-DECOMPOSITION.md:41-66` — the per-Blueprint op-list decomposition
>   (the authority V153 serializes from).

---

## 1. SCOPE

Three deliverables, one ordered build:

### (a) The V153 migration — `V153__builder_op_lists_and_param_tiering.sql`

`[read]` File name + version pinned: `backend/src/main/resources/db/migration/V153__builder_op_lists_and_param_tiering.sql`;
V151 is the current head, no V152/V153 exists on this branch (`SPEC-codegen-compiler.md:255-258`; head confirmed
`V151__widen_user_git_identities_scopes.sql`). V153 does four things to the **`blueprints`** table:

1. **Write the op-list** into the `schema_behavior` JSONB column for **all 41 active entries** = the 39 surviving
   Blueprints **+ the 2 new** (`SqlModel`, `SourceSQL`) — replacing the throwaway `{effect_type,conflict_policy}`
   content `[read]` `SPEC-codegen-compiler.md:260-263` + `SPEC-schema-op-engine.md:34-35,98-103` (the op-list lives in
   the existing `schema_behavior` column added at `V74:17`; V153 **replaces content, adds no column**
   `[read]` `SPEC-schema-op-engine.md:29-35`).
   - **NOTE — count reconciliation:** §D's contract text says "39 surviving Blueprints" `[read]` `SPEC-codegen-compiler.md:260`.
     The 2 new SQL Blueprints (`SqlModel`/`SourceSQL`) are net-new catalog rows from ADR 0024 — they are part of the
     **41-entry coverage basis** `[read]` `SPEC-blueprint-catalog.md:14-19` but are **INSERTs, not UPDATEs of existing rows**.
     `> GUESS:` V153 must therefore both UPDATE 39 existing rows' `schema_behavior`/`params_schema` AND INSERT 2 new
     blueprint rows (SqlModel/SourceSQL) with full metadata. The §D text scopes only the 39; the 2 new rows are an
     **explicit addition this plan flags for operator/spec confirmation** (cross-ref OPEN-WORKLIST G-11 — the new-Blueprint
     param-key/port names must be reconciled with #1 §A.1 before INSERT). See **PREREQ P-NEW** below.

2. **Write `tier`/`derivedFrom`** into each element of the `params_schema` JSONB array for every active entry, per the
   per-param tier assignments in the #5 catalog `[read]` `SPEC-codegen-compiler.md:260-264` + the tiering shape
   `[read]` `SPEC-schema-op-engine.md:147-181` (`tier:"user"|"derived"`; `derivedFrom` present iff `tier=="derived"`).
   - The spec's human-readable spelling is `tier: system-derived`; the **code-level serialized value is `tier:"derived"`**
     `[read]` `SPEC-blueprint-catalog.md:32` + `SPEC-schema-op-engine.md:160`. V153 MUST serialize `"derived"`, not
     `"system-derived"`.

3. **Deprecate the 4 dead Blueprints** following the V81 deprecation shape (idempotent UPDATE) `[read]`
   `SPEC-codegen-compiler.md:271-285`.

4. **Correct SnapshotModel's `artifact_types`** mis-tag from `["dbt_snapshot"]` → `["incremental"]` `[read]`
   `SPEC-codegen-compiler.md:287-297` (the live mis-tag is at `V81__blueprint_catalog_rationalization.sql:426`,
   confirmed `[read]` `V81:426` `WHEN 'SnapshotModel' THEN '["dbt_snapshot"]'`; SCD2Dimension's `["dbt_snapshot"]` at
   `V81:425` is correct and stays).

### (b) The demo-pipeline seed — regenerated real compositions

The acceptance criterion: **the seeded blueprints + demo pipelines render 100% in the new composition page.**

- **Composition page surface** `[read]` (Explore): route `/pipelines/[pipelineId]`
  (`frontend/src/app/pipelines/[pipelineId]/page.tsx`) → `CompositionPanel`
  (`frontend/src/components/pipeline/composition-panel.tsx`) → `DagView`
  (`frontend/src/components/pipeline/dag-view.tsx`, `@xyflow/react`). It fetches via
  `GET /api/v1/versions/{versionId}/composition` `[read]` (Explore: composition-panel.tsx).
- **Composition storage tables** `[read]` (Explore): `pipeline_versions` (`V4:5-17`),
  `sub_pipeline_instances` (`V1:51-63`; `version_id` added `V4:45-46`; `blueprint_key` added `V8:4`),
  `port_wirings` (`V8:10-20`, unique on `(version_id, target_instance_id, target_port_name)` `V8:19`).
- **Why regeneration (not reuse) is in scope:** the current demo composition seed in
  `V5__seed_test_data.sql:165-193` `[read]` references **stale `acme.*` dataset-ref JSON strings** (the
  `"ref": "acme.finance…"` values inside `input_datasets`/`output_datasets`, e.g. `[read]` `V5:171,177` — NOT
  the `tenant_id` column) and **legacy free-form params** that do not
  match the V153 op-list/tiered-param surface (e.g. `DQValidator` seeded with `{"rules":[...],"policy":"quarantine"}`
  `[read]` `V5:183`, but the V153 surface is `expectations`/`on_failure`/`threshold_percent`/`mostly`
  `[read]` `SPEC-blueprint-catalog.md:661-666`). A composition whose instance params do not satisfy the new
  `params_schema` will not render cleanly. The seed must be **regenerated** as a new migration (the demo-seed migration,
  below) that instantiates #5's blueprints into compositions whose params/ports match the V153 surface, scoped to the
  **renamed tenants**, with **port wirings** that match the catalog's declared port names/roles.

**Scope boundary (NOT in this build):** the #1 schema-engine code (op-walking propagation), the #2 codegen emission
handlers, the #3 config panels, and the #4 construct library are **consumers** of this catalog, not part of this build
`[read]` `SPEC-blueprint-catalog.md:36-37,1147-1158`. This build produces the **data** (V153 + demo seed) they consume.
The `Derive`/V102 keep-or-fold decision (would be a 40th survivor) is an operator decision **out of scope** until ruled
`[read]` `SPEC-blueprint-catalog.md:1091-1099,1141`.

---

## 2. PREREQUISITES (resolve FIRST — the op-list column is mechanical NOW; the tier column is NOT)

**The dependency that orders this whole build** `[read]` `SPEC-codegen-compiler.md:316-319` (G-D2):
- The **op-list column** (`schema_behavior`) is **mechanically derivable NOW** — the decomposition is locked
  (`OP-VOCAB:43-66`), the JSON **shape is pinned** (`SPEC-schema-op-engine.md:96-103`, G-1 RESOLVED), and the Cleaning
  param keys are pinned (`SPEC-schema-op-engine.md:142-145`, G-A2 RESOLVED). This half is **unblocked**.
- The **param-tiering column** (`tier`/`derivedFrom`) is **NOT fully mechanical** — ADR 0023 names only two Blueprints'
  surfaces in full (AdvanceTimeDimension + RemotePipelineInvocation `[read]` `SPEC-blueprint-catalog.md:71`); the other
  39 carry **`> GUESS:`-flagged per-param tier calls** from the §0.1 heuristic `[read]` `SPEC-blueprint-catalog.md:69-95`.
  These are **not operator-agreed** and MUST be resolved before they are serialized into a migration (a migration is
  hard to walk back; tiering is an immutable contract once seeded). **The byte-exact shape comes from #1 §A.1/§A.3**
  `[read]` `SPEC-schema-op-engine.md:46-103,147-181`.

**Conclusion:** Phase A (op-list serialization) can start immediately. Phases B–C (tiering, demo seed) **block on the
~15 OPEN-WORKLIST items** below being resolved by the operator/#4 author. (Total prerequisite count = **15**, grouped 8
tiering + 2 construct/date-facility + 2 new-Blueprint + 3 intent/catalog.)

### Tiering confirmations (8) — `[read]` `SPEC-blueprint-catalog.md:1105-1117`
- **G-1** — confirm the §0.1 tiering heuristic itself (one ruling unblocks ~300 param calls).
- **G-2** — `storage_backend`/`lake_layer`/`lake_format` are **system-derived everywhere** (required-true in JSON but
  codegen/deploy-resolved).
- **G-3** — `partition_by`/`cluster_by` **system-derived** vs power-user override (affects 10+ MODELING/INGESTION entries).
- **G-4** — reliability knobs (`retry_count`/`poke_interval_seconds`/`timeout_seconds`/`mode`/`chunk_*`/`parallelism`/
  `batch_size`/`connection_pool_size`) **system-derived** vs `user`.
- **G-5** — `SchemaDriftDetection.expected_columns`: `user` vs `system-derived` from input-port contract.
- **G-6** — SINK perf knobs (`optimize_after_write`/`z_order_columns`/`clustering_columns`) `user` vs system-derived.
- **G-7** — `StreamWriter.checkpoint_location` derivation path (system-derived, must be unique per pipeline).
- **G-8** — `ScheduleAndTriggers.timezone` and ingestion/sensor `date_format` derived vs `user`.

### UI-construct / date-facility hints (2) — the #4 forward-references `[read]` `SPEC-blueprint-catalog.md:1119-1129`
- **G-9** — the ~33 named constructs (`column-picker`, `rename-mapper`, `sql-chain-editor`, `dq-outcome-control`,
  `inspector-readonly`, etc.) are forward-references to the to-author #4 construct library. **V153 does NOT serialize
  construct names** (those live in #3 panels / #4 constructs, derived from this catalog `[read]`
  `SPEC-blueprint-catalog.md:36`); they are needed only to confirm the demo seed renders. `> GUESS:` the composition
  page renders instances from `params_schema`/ports today **without** #4's bespoke constructs (it predates them), so the
  demo-seed render check (acceptance criterion) does **not** block on #4 — confirm against the current
  `composition-panel.tsx` behavior.
- **G-10** — unify the legacy `{date}`+`date_value` mnemonic facility (FileIngestion/sensors) vs the ADR-0024 `[[ … ]]`
  SQL token at the #4 `date-mnemonic-picker` level (does not block V153 data; affects #4).

### New-Blueprint shape confirmations (2) — **P-NEW: block the 2 INSERTs** `[read]` `SPEC-blueprint-catalog.md:1131-1135`
- **G-11 (RESOLVED 2026-06-16)** — `SqlModel`/`SourceSQL` param key names = `steps`, `source_query`,
  **`connector_instance_id`** (sink/ServiceInstance convention, NOT `connector_id`); reserved `input` port name; output
  ports `sql_output`/`source_output`; declared-schema-fallback param. Reconciled with #1 §A.1 +
  `SPEC-calcite-sql-model.md` §B.1/§C.1 (6-G06 RESOLVED) and #5 G-11. These are the exact names V153 INSERTs.
- **G-12** — `SqlModel.lake_layer` default (silver vs gold) `[read]` `SPEC-blueprint-catalog.md:1135`.

### Intent / catalog decisions (3) — `[read]` `SPEC-blueprint-catalog.md:1137-1143`
- **G-13** — ratify the `AdvanceTimeDimension` INTENT surface (2 user + 18 derived, V132+ADR-0023) over the stale 4-field
  JSON row, and the `advance_to` consolidation of `advance_mode`+`requested_asof_expr`. **V153 re-seeds AdvanceTimeDimension's
  full 20-field `params_schema`** `[read]` `SPEC-blueprint-catalog.md:984-990` — this is the one entry where V153 rewrites
  the param array wholesale, not just adds `tier`/`derivedFrom`.
- **G-14** — `Derive`/V102: keep as 40th atomic Blueprint or fold (out of scope until ruled; affects whether a 42nd entry exists).
- **G-15** — merge candidates (`AggregateMaterialization ≡ GenericAggregate`; `BulkBackfill` vs deprecating
  `BackfillAndReplay`) — until ruled, both kept distinct `[read]` `SPEC-blueprint-catalog.md:584`.

> **G-D1 already RESOLVED** `[read]` `SPEC-codegen-compiler.md:299-315`: all 4 deprecation-target keys are verified
> **ACTIVE today** and not previously deprecated, so V153 is the first to deprecate them — the deprecation contract is
> sound (no missing/already-deprecated key). This is NOT an open prerequisite; it is a confirmed precondition.

---

## 3. BUILD PHASES (ordered)

### Phase A — Op-list serialization (the "mechanical drafting", G-D2) — UNBLOCKED, start now

Serialize the **39 surviving op-lists** from the decomposition doc into `schema_behavior` UPDATE statements, in the §A.1
shape. **Per-entry mechanical procedure:**

1. Read the entry's op-list from `OP-VOCAB:43-66` (e.g. all 6 INGESTION = `read-source → add-audit-columns →
   write-sink(bronze)` `[read]` `OP-VOCAB:43`; BronzeToSilverCleaning per the worked example
   `[read]` `SPEC-schema-op-engine.md:115-136`).
2. Emit each op as an op-entry `{"op","ui_label","config"}` `[read]` `SPEC-schema-op-engine.md:66-103`; `config` values
   are literals or the **exact** param-ref `{"param":"<name>"}` `[read]` `SPEC-schema-op-engine.md:82-90,98-101`.
3. Set `emission` per category `[read]` `SPEC-schema-op-engine.md:212-217`: INGESTION/SINK → `compute:"pyspark"`;
   TRANSFORM/MODELING → `compute:"dbt"` (SCD2's op makes dbt-kind `snapshot`, SnapshotModel's makes it `incremental`
   `[read]` `SPEC-schema-op-engine.md:208-210`); DATA_QUALITY → `compute:"gx"`; CONTROL → `compute:null`.
4. Wrap as `{"version":1,"ops":[…],"blueprint_params":[…],"emission":{…}}` `[read]` `SPEC-schema-op-engine.md:48-57`.

**Key per-category anchors to serialize (from #5 + OP-VOCAB):**
- **INGESTION (6)** — canonical shape ×6 `[read]` `OP-VOCAB:43`.
- **TRANSFORM (10)** — BronzeToSilverCleaning = the 6-op decomposed list (worked example
  `[read]` `SPEC-schema-op-engine.md:118-132`, supersedes the `null_handling` enum); the 9 others per
  `OP-VOCAB:47-51` (SchemaNormalization, DedupeAndMerge=`deduplicate`, PIIMasking=`mask-columns`, GenericJoin=`join`,
  GenericAggregate=`group-and-aggregate`, GenericFilter=`filter-rows`, GenericRouter=`route-rows`, JsonFlatten=`flatten-json`,
  JsonStruct=`build-struct`+`drop-columns`).
- **MODELING (8)** `[read]` `OP-VOCAB:53-59`.
- **DATA_QUALITY (4)** — DQValidator=`check-data`; Freshness/Drift/Anomaly=`check-data`+`emit-report` `[read]` `OP-VOCAB:61`.
- **SINK (4)** — all `write-sink(target,mode)` `[read]` `OP-VOCAB:63`. **Serialize `category` UNCHANGED** = `"DESTINATION"`
  (NOT "SINK") `[read]` `SPEC-blueprint-catalog.md:128-137`.
- **CONTROL (7)** — `sense`×3 / `schedule-and-triggers` / `rollback` / `advance-time` / `invoke-remote`
  `[read]` `OP-VOCAB:65`. **Serialize `category` UNCHANGED** = `"ORCHESTRATION"` (NOT "CONTROL")
  `[read]` `SPEC-blueprint-catalog.md:128-137`. (V153 does NOT touch `category`.)

> **Critical V153 invariant** `[read]` `SPEC-blueprint-catalog.md:125-137`: the spec's section headers (SINK/CONTROL) are
> DISPLAY groupings, NOT the serialized `category`. V153 **preserves the live `category` column value** for every Blueprint.

**Output of Phase A:** 39 `UPDATE blueprints SET schema_behavior = '<json>'::jsonb WHERE blueprint_key = '<key>'` statements.
This phase does NOT need the tiering resolved — it can be drafted and committed to the SQL file body immediately.

> **AdvanceTimeDimension caveat:** its **op-list** (`schema_behavior`) is unblocked in Phase A like every other
> entry; only its **`params_schema` array rewrite** (the 20-field V132 intent surface + `advance_to`
> consolidation) is the **Phase-B / G-13-gated exception** — that param-array rewrite waits, the op-list does not.

### Phase B — Param-tiering serialization (BLOCKS on §2 G-1…G-8, G-13)

For each of the 39 (after the §2 tiering items are operator-confirmed), rewrite each `params_schema` element to add
`tier` + (conditionally) `derivedFrom` `[read]` `SPEC-schema-op-engine.md:151-164`. Procedure:
1. For each param in the entry's #5 table, copy its confirmed `tier` (serialize `"derived"`, not `"system-derived"`
   `[read]` `SPEC-blueprint-catalog.md:32`) and, if derived, its `derivedFrom`.
2. **AdvanceTimeDimension is special** — wholesale rewrite to the 20-field V132 surface + consolidated `advance_to`
   `[read]` `SPEC-blueprint-catalog.md:958-990` (this is the only entry that replaces the array, gated on G-13).
3. **Default rule:** absent `tier` ⇒ `user` `[read]` `SPEC-schema-op-engine.md:166-168` — so any param accidentally left
   untagged degrades safely to `user`, but the gate requires every active param explicitly tiered
   `[read]` `SPEC-blueprint-catalog.md:16-18` (an untiered param = an omission finding).

**Output of Phase B:** 39 `UPDATE blueprints SET params_schema = '<json>'::jsonb WHERE blueprint_key = '<key>'`
statements (or a single combined UPDATE per blueprint touching both `schema_behavior` and `params_schema`).

### Phase C — The 2 new INSERTs: SqlModel + SourceSQL (BLOCKS on §2 P-NEW: G-11, G-12)

INSERT two new `blueprints` rows with full metadata:
- **SqlModel** (TRANSFORM, `compute:"dbt"`) — op-list `[{"op":"sql-model",…}]` `[read]` `SPEC-blueprint-catalog.md:1022-1025`;
  single `steps` param (`user`, accepts mnemonic) `[read]` `SPEC-blueprint-catalog.md:1030`; required `input` port +
  `sql_output` port `[read]` `SPEC-blueprint-catalog.md:1036-1039`; storage block → silver default (G-12).
- **SourceSQL** (INGESTION, `compute:"pyspark"`) — op-list `read-source → add-audit-columns → write-sink(bronze)`
  `[read]` `SPEC-blueprint-catalog.md:1047-1051`; params `source_query`(user)+`connector_instance_id`(derived)
  `[read]` `SPEC-blueprint-catalog.md:1055-1058`; no input port, `source_output` port `[read]` `SPEC-blueprint-catalog.md:1064-1066`.
- Both rows need the non-null columns the table requires (`name`, `description`, `category`, `version`, `input_ports`,
  `output_ports`, etc. `[read]` V7 base DDL via Explore; `add_surface` default `'composition'` `[read]` `V112:25`).

> **Phase C must wait for G-11** (param-key/port-name reconciliation with #1 §A.1) — the names are `> GUESS:` in #5 today.
> Do NOT INSERT under a guessed key name (a wrong `blueprint_key` is a permanent catalog row).

### Phase D — The 4 deprecations (idempotent, V81 shape) — UNBLOCKED (G-D1 resolved)

Emit 4 idempotent UPDATEs following the V81 deprecation shape `[read]` `V81:19-40` + `SPEC-codegen-compiler.md:271-285`:
- `ObjectStoreKeySensor` → `replacement_blueprint_key='FileArrivalSensor'`
- `DatasetDependencySensor` → `replacement_blueprint_key='ScheduleAndTriggers'`
- `BackfillAndReplay` → `replacement_blueprint_key='BulkBackfill'`
- `CostMonitoringHook` → `replacement_blueprint_key=NULL`

Each: `UPDATE blueprints SET status='deprecated', deferred=true, replacement_blueprint_key=<repl>, add_surface='none'
WHERE blueprint_key='<key>'` — the `add_surface='none'` pin prevents instantiation `[read]` `SPEC-codegen-compiler.md:283-285`
(`Blueprint.java:110-116`). Idempotent by construction (UPDATE … WHERE key = no-op if absent) per V81's convention
`[read]` `SPEC-codegen-compiler.md:300-302` (`V81:11-13`).

### Phase E — SnapshotModel artifact_types correction — UNBLOCKED

`UPDATE blueprints SET artifact_types='["incremental"]'::jsonb WHERE blueprint_key='SnapshotModel'`
`[read]` `SPEC-codegen-compiler.md:287-297`. Overrides the live mis-tag `[read]` `V81:426`. SCD2Dimension stays
`["dbt_snapshot"]` `[read]` `V81:425` — do NOT touch it. (This is FIX #9 / GAP1 `[read]` `OP-VOCAB:82`.)

**Phases A–E together = the single `V153__…sql` file.** Statement order inside the file: deprecations (D) + corrections
(E) first or last is immaterial (different rows), but author them as one transactional migration.

### Phase F — Demo-pipeline seed regeneration (BLOCKS on Phases A–C — needs the new surface to exist)

Author a **new seed migration** (e.g. `V154__regenerate_demo_compositions.sql` `> GUESS:` number; must be > V153 and
> the highest at author time) that:
1. **Deletes/supersedes** the stale `acme.*`-ref demo composition rows from `V5:165-193` (the stale data is the
   `"ref": "acme.finance…"` dataset-ref JSON `[read]` `V5:171,177`, not the tenant_id column) — or inserts fresh
   pipelines under the renamed tenants and leaves V5's behind (`> GUESS:` operator preference; cleanest is fresh
   rows scoped to `tenant-home-lending`/`tenant-unsecured-lending`, the IDs **defined** at `[read]` `V2:19-20`).
2. **Instantiates #5's surviving blueprints** into real `sub_pipeline_instances` whose `params` satisfy the V153
   `params_schema` (user-tier params populated with valid values; derived params omitted/inspector-only) and whose
   `input_datasets`/`output_datasets` use the catalog's declared **port roles** (bronze/silver/gold) and the renamed
   tenant's dataset namespace.
3. **Wires ports** via `port_wirings` `[read]` (Explore: `V8:10-20`) matching the catalog's declared port **names**
   per blueprint (e.g. ApiIngestion `api_output` → BronzeToSilverCleaning `raw_input` → … `[read]`
   `SPEC-blueprint-catalog.md:167,313`), respecting the unique `(version_id, target_instance_id, target_port_name)`
   constraint `[read]` `V8:19`.
4. **Anchor pipeline:** include the canonical `loan_master` home-lending pipeline (GCS file → bronze → silver) so the
   demo exercises FileIngestion + BronzeToSilverCleaning end-to-end `[read]` `SPEC-codegen-compiler.md:325-327`
   (tenant `tenant-home-lending`). `> GUESS:` build at least one composition per major category (INGESTION→TRANSFORM→
   MODELING→DQ→SINK) so the render check covers the breadth, not just one path.

**Output of Phase F:** a seed migration producing compositions that fetch cleanly via
`GET /api/v1/versions/{versionId}/composition` and render in `CompositionPanel`/`DagView` `[read]` (Explore).

---

## 4. TESTS

1. **V153 applies cleanly (Flyway).** A migration smoke test that runs the full Flyway chain through V153 against a
   clean DB and asserts no error. **Test-style precedent** `[read]` (Explore): SQL-text assertion
   (`V88BlueprintDqClarityTest` reads the migration file and asserts its UPDATE clauses) **or** SpringBootTest +
   `JdbcTemplate` querying post-migration state (`SchemaV84MigrationTest`, `V87TenantsTableMigrationTest`). Use the
   JdbcTemplate style so the assertions below are queryable.
2. **Op-list shape validity** — for each of the 41 active rows, assert `schema_behavior` parses as the §A.1 shape:
   top-level keys `{version,ops,blueprint_params,emission}`; every `ops[].op` ∈ the 32-op closed vocabulary
   `[read]` `SPEC-schema-op-engine.md:76,98-101`; every `config` value is a literal or exactly `{"param":"<name>"}`;
   every param-ref name has a matching `params_schema` descriptor (the A.4 derivation-completeness check
   `[read]` `SPEC-schema-op-engine.md:185-193` — a referenced-but-undescribed param = loud-fail).
3. **Param-tiering completeness** — every active param has an explicit `tier` ∈ `{user,derived}`, and every `derived`
   param has a `derivedFrom` `[read]` `SPEC-schema-op-engine.md:151-164`. (Gate yardstick: an untiered param = omission
   finding `[read]` `SPEC-blueprint-catalog.md:16-18`.)
4. **Deprecation correctness** — after V153, the 4 dead keys are `status='deprecated'`, `deferred=true`,
   `add_surface='none'`; replacement keys exactly as Phase D; the 39+2 actives remain instantiable.
5. **SnapshotModel correction** — `artifact_types = ["incremental"]`; SCD2Dimension unchanged at `["dbt_snapshot"]`.
6. **No duplicate-key conflicts** — the 2 INSERTs (SqlModel/SourceSQL) do not collide with existing `blueprint_key`
   values (UNIQUE constraint on `blueprint_key` `[read]` (Explore: V7 base DDL)); re-running V153 is idempotent for the
   UPDATEs (the INSERTs guard with `ON CONFLICT (blueprint_key) DO NOTHING` or a NOT EXISTS check — `> GUESS:` choose
   the project's prevailing idempotency idiom).
7. **Acceptance — demo pipelines render 100%** (the operator's criterion): for each seeded demo pipeline, fetch
   `GET /api/v1/versions/{versionId}/composition` and assert it returns every instance + every wiring with no error, and
   (frontend) that `CompositionPanel`/`DagView` mounts every node/edge. `> GUESS:` if no frontend test runner is wired
   (per CLAUDE.md the Playwright suite was removed), satisfy this via (a) a backend assertion that the composition API
   returns a fully-resolved graph for each demo version, and (b) a manual render check in the running composition page —
   confirm the operator's bar is the API-complete graph + a manual visual pass, not an automated e2e.

---

## 5. MILESTONES

- **M1 — Op-lists drafted (Phase A).** All 39 `schema_behavior` JSONs serialized into the V153 body — including
  AdvanceTimeDimension's op-list (its **`params_schema` rewrite** alone is the Phase-B/G-13-gated exception, not
  its op-list). *Gate: each parses as §A.1, every op ∈ the 32-op vocab.* (Unblocked — no prerequisite.)
- **M2 — Prerequisites resolved.** The 15 OPEN-WORKLIST items confirmed by operator/#4 (esp. G-1 unblocks tiering; G-11
  unblocks the 2 INSERTs; G-13 unblocks AdvanceTimeDimension rewrite).
- **M3 — Tiering + INSERTs complete (Phases B, C).** All 41 actives carry explicit `tier`/`derivedFrom`; SqlModel +
  SourceSQL inserted under reconciled keys/ports.
- **M4 — Deprecations + correction (Phases D, E).** 4 deprecated, SnapshotModel corrected; V153 is one transactional file.
- **M5 — V153 green.** Flyway applies clean through V153; tests 1–6 pass.
- **M6 — Demo seed regenerated (Phase F).** New seed migration under renamed tenants; compositions match the V153 surface.
- **M7 — Acceptance.** Demo pipelines render 100% in the composition page (test 7) — the operator's bar.

---

## 6. FAN-OUT

The **41 op-list rows are a fan-out** — 41 nearly-independent serialization units (39 UPDATEs + 2 INSERTs), each a
self-contained `(op-list JSON, tiered params JSON)` pair derivable from its #5 entry + OP-VOCAB line. They share only the
pinned §A.1 shape and the §0.1 tiering heuristic (one shared decision, not 41). **Parallelizable** across building agents
by category (INGESTION 6 / TRANSFORM 10 / MODELING 8 / DATA_QUALITY 4 / SINK 4 / CONTROL 7 + 2 new), with a single
convergence pass to assemble the one V153 file and run the shape/tiering tests. The demo-seed compositions (Phase F) are
a second, smaller fan-out (one unit per demo pipeline) but **serial after** the catalog fan-out (they instantiate it).

> **Producer ≠ verifier discipline** `[read]` `SPEC-codegen-compiler.md:428` (G-D2): the 41 op-list JSONs are mechanical
> drafting; whoever drafts them is NOT the agent that verifies them against the §A.1 shape + OP-VOCAB. Tests 2–3 are the
> independent verification gate.

---

## 7. RISKS

- **R1 — Tiering serialized wrong is a permanent contract.** The 39 non-ADR-named entries carry `> GUESS:` tiers; a
  migration is hard to walk back. *Mitigation:* Phase B blocks on G-1…G-8 confirmation (§2); never serialize an
  unconfirmed tier. The `absent ⇒ user` default `[read]` `SPEC-schema-op-engine.md:166-168` is a safety net, not a license
  to skip.
- **R2 — `tier` value spelling.** The spec spells `system-derived` for humans but the serialized value MUST be `"derived"`
  `[read]` `SPEC-blueprint-catalog.md:32` + `SPEC-schema-op-engine.md:160`. *Mitigation:* test 3 asserts `tier ∈
  {user,derived}` exactly (reject `"system-derived"`).
- **R3 — `category` accidentally rewritten to SINK/CONTROL.** The headers are display-only; the column is
  DESTINATION/ORCHESTRATION `[read]` `SPEC-blueprint-catalog.md:128-137`. *Mitigation:* V153 never writes `category`;
  add an assertion that the 8 sink/control rows keep DESTINATION/ORCHESTRATION.
- **R4 — The 2 INSERTs collide or use guessed keys.** SqlModel/SourceSQL param-keys/port-names are `> GUESS:` (G-11).
  *Mitigation:* Phase C blocks on G-11; INSERTs guard `ON CONFLICT DO NOTHING`; test 6.
- **R5 — Demo seed references stale `acme.*` dataset-ref strings.** The stale data is the `"ref": "acme.finance…"`
  JSON in the old seed's `input_datasets`/`output_datasets` `[read]` `V5:171,177` — **NOT** the `tenant_id`
  column (the demo rows already carry the correct tenant IDs). The tenant IDs themselves are **DEFINED** at
  `[read]` `V2:19-20` (`'tenant-home-lending'` / `'tenant-unsecured-lending'` INSERT VALUES); `V87:13` is only a
  comment noting the rename, not the definition. *Mitigation:* Phase F regenerates the `acme.*` dataset refs
  under the home-lending/unsecured-lending dataset namespaces; the render check (test 7) catches a stale-ref /
  param mismatch.
- **R6 — Demo params don't satisfy the new `params_schema` → render fails.** The old seed's free-form params
  (e.g. `DQValidator {"rules":…,"policy":…}` `[read]` `V5:183`) don't match the V153 surface (`expectations`/`on_failure`/…
  `[read]` `SPEC-blueprint-catalog.md:661-666`). *Mitigation:* Phase F populates instance params from each blueprint's
  V153 user-tier params only; test 7 is the catch.
- **R7 — AdvanceTimeDimension wholesale rewrite.** It is the one entry replacing its whole `params_schema` (20 fields)
  not just adding tiers `[read]` `SPEC-blueprint-catalog.md:984-990`; getting the 18 derived fields wrong corrupts a
  runtime contract (V132). *Mitigation:* G-13 ratification + serialize directly from the V132 field list + ADR 0023:11.
- **R8 — AdvanceTimeDimension calendar prereq gap (out of #5 scope but visible).** `create_domain` never sets
  `businessDateConfig`, so derived-calendar resolution silently falls to `US-FED` `[read]` `SPEC-blueprint-catalog.md:989-990`.
  Tracked, NOT fixed by V153 — flag so a verifier doesn't treat the demo's calendar fallback as a V153 defect.

---

## 8. CROSS-REFERENCES

- **#1** `SPEC-schema-op-engine.md` — §A.1 op-entry shape (`:46-103`), §A.3 param-tiering (`:147-181`), §B the 32 ops.
- **#2** `SPEC-codegen-compiler.md` — §D the V153 contract (`:253-319`), §E the anchor oracle (`:323-`).
- **#5** `SPEC-blueprint-catalog.md` — the 41 entries + OPEN WORKLIST (`:1103-1143`).
- **OP-VOCAB** `docs/blueprints/OP-VOCABULARY-AND-DECOMPOSITION.md` — the op-list authority (`:41-66`).
- **Live migrations** — `V74:17` (`schema_behavior` col), `V81:19-40` (deprecation shape), `V81:425-426`
  (artifact_types), `V2:19-20` (tenant IDs **defined**; `V87:13` is only a rename comment), `V112:25`
  (`add_surface`), `V5:165-193` (stale demo compositions; `acme.*` dataset-ref strings at `V5:171,177`),
  `V8:10-20` (port_wirings), `V4:5-17` (pipeline_versions).
