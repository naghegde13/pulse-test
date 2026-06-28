# Catalog lane — EVIDENCE (V153 op-lists/param-tiering + V154 demo seed)

Branch: `build/catalog` (worktree `/Users/aameradam/projects/dev/pulse-wt/catalog`). Base SHA `e54751c` (verified).
Migration head before this lane: **V151** (confirmed). V152 left free; this lane adds **V153** + **V154**.

Authority read: SPEC #5 `docs/build-specs/SPEC-blueprint-catalog.md`; IMPL `docs/impl-plans/IMPL-catalog-seed.md`;
shape SPEC #1 `docs/build-specs/SPEC-schema-op-engine.md` §A.1 (op-entry) / §A.3 (tiering); op authority
`docs/blueprints/OP-VOCABULARY-AND-DECOMPOSITION.md`; ADR 0020 / 0023; migration idioms V81 (deprecation),
V102 (`ON CONFLICT (blueprint_key) DO NOTHING`), V7 (base DDL), V112 (`add_surface`), V132 (AdvanceTimeDimension).

Files created:
- `backend/src/main/resources/db/migration/V153__builder_op_lists_and_param_tiering.sql`
- `backend/src/main/resources/db/migration/V154__regenerate_demo_compositions.sql`
- `backend/src/test/java/com/pulse/blueprint/V153BuilderOpListsTest.java` (fast-lane SQL-text assertion)
- `backend/src/test/java/com/pulse/config/V153V154PostgresCatalogIT.java` (Postgres-IT, JdbcTemplate)

---

## V153 contents (counts)

- **38** `UPDATE ... SET schema_behavior + params_schema` statements (the 39 decomposition survivors **minus**
  AggregateMaterialization, which is MERGED into GenericAggregate per G-15 — one row seeded, not two).
- **2** `INSERT ... ON CONFLICT (blueprint_key) DO NOTHING` (SqlModel, SourceSQL).
- **4** deprecations (Phase D), **1** artifact_types correction (Phase E).
- Total seeded rows carrying op-lists = **40** (38 updated + 2 inserted). RemotePipelineInvocation is present
  (the "39th survivor"). `AggregateMaterialization` and `Derive` are correctly absent.
- Params tiered: **>= 200** active params across the 40 rows, each `tier ∈ {user, derived}` with `derivedFrom`
  present iff derived. (The fast-lane test counts and asserts this.)

### Phase A/B — op-lists + tiered params (file:line in V153, by group)

| Group (count) | blueprint_keys | V153 first-line anchor |
|---|---|---|
| INGESTION (6) | ApiIngestion, BulkBackfill, CDCIngestion, FileIngestion, SnapshotIngestion, StreamIngestion | V153:35 (ApiIngestion `UPDATE`) |
| TRANSFORM (10) | BronzeToSilverCleaning, SchemaNormalization, DedupeAndMerge, PIIMasking, GenericJoin, GenericAggregate, GenericFilter, GenericRouter, JsonFlatten, JsonStruct | V153:~140 (BronzeToSilverCleaning) |
| MODELING (7) | SCD2Dimension, SnapshotModel, FactBuild, WideDenormalizedMart, IncrementalMerge, ReferenceDataPublish, FeatureTablePublish | V153:~300 (SCD2Dimension) |
| DATA_QUALITY (4) | DQValidator, FreshnessChecks, SchemaDriftDetection, AnomalyDetection | V153:~380 (DQValidator) |
| DESTINATION/SINK (4) | WarehouseWriter, LakeWriter, DatabaseWriter, StreamWriter | V153:~430 (WarehouseWriter) |
| ORCHESTRATION/CONTROL (7) | FileArrivalSensor, DatabaseReadinessSensor, ExternalEventSensor, ScheduleAndTriggers, RollbackOnFailure, AdvanceTimeDimension, RemotePipelineInvocation | V153:~500 (FileArrivalSensor) |

(Exact anchors: each blueprint is a single `UPDATE blueprints SET schema_behavior = '...'::jsonb, params_schema =
'...'::jsonb WHERE blueprint_key = '<key>';` block, in the order above; the comment headers `-- INGESTION (6)` etc.
delimit the groups.)

- Every `ops[].op` is one of the **32 closed ops**; every `config` value is a literal or exactly `{"param":"<name>"}`;
  every param-ref resolves to a `params_schema` descriptor (independently verified — see Verification below).
- `emission.compute`: INGESTION/DESTINATION → `"pyspark"`, TRANSFORM/MODELING → `"dbt"`, DATA_QUALITY → `"gx"`,
  ORCHESTRATION → `null`. `emission.orchestration` always `"airflow"`.
- The `category` column is **never** assigned (DESTINATION/ORCHESTRATION preserved). Verified by the fast-lane test
  `doesNotTouchTheCategoryColumn`.
- **BronzeToSilverCleaning** supersedes the legacy `null_handling` enum with the locked 6-op decomposed surface
  (trim/fill, rename, cast, drop, drop-null-rows, dedup).
- **AdvanceTimeDimension** (V153 ~line for `WHERE blueprint_key = 'AdvanceTimeDimension'`): wholesale `params_schema`
  rewrite to the V132 + ADR-0023 intent surface = **2 user** (`target_scope`, `advance_to`) + **17 derived**;
  `advance_to` consolidates V132 `advance_mode` + `requested_asof_expr`; `storage_backend` dropped from the surface.
  Verified by `advanceTimeDimensionIsTheConsolidatedIntentSurface`.

### Phase C — 2 new SQL blueprints (V153 INSERTs)

- **SqlModel** id `01JBP0TRANSFORM0SQLMODEL01`, category TRANSFORM, op `sql-model`, input port `input`,
  output `sql_output`, params `steps`(user)+`declared_output_schema`(user)+storage block; `lake_layer` default `silver`.
- **SourceSQL** id `01JBP0INGESTION0SOURCESQL1`, category INGESTION, ops `read-source → add-audit-columns →
  write-sink(bronze)`, output `source_output`, params `source_query`(user)+`connector_instance_id`(derived/connector)
  +`declared_output_schema`(user)+storage block; `lake_layer` default `bronze`.
- Both guarded `ON CONFLICT (blueprint_key) DO NOTHING` (V102 idiom). Connector param uses the resolved SINK
  convention `connector_instance_id` (6-G06). Verified by `insertsTheTwoNewSqlBlueprintsIdempotently`.

### Phase D — 4 deprecations (idempotent, V81 shape; `add_surface='none'`)

| key | replacement |
|---|---|
| ObjectStoreKeySensor | FileArrivalSensor |
| DatasetDependencySensor | ScheduleAndTriggers |
| BackfillAndReplay | BulkBackfill |
| CostMonitoringHook | NULL |

Each: `status='deprecated', deferred=true, replacement_blueprint_key=<repl>, add_surface='none'`. Idempotent (no-op if
key absent). Verified by `deprecatesTheFourDeadBlueprintsWithReplacements`.

### Phase E — SnapshotModel artifact_types correction

`UPDATE blueprints SET artifact_types = '["incremental"]'::jsonb WHERE blueprint_key = 'SnapshotModel';`
SCD2Dimension is NOT touched (stays `["dbt_snapshot"]`). Verified by
`correctsSnapshotModelArtifactTypesAndLeavesScd2Untouched`.

---

## V154 contents (demo composition seed)

Fresh rows scoped to `tenant-home-lending` / `tenant-unsecured-lending`; V5 left in place. 3 pipelines, **12**
sub_pipeline_instances, **8** port_wirings. Every instance references a V153-active `blueprint_key`; every wiring
connects a declared V153 OUTPUT port → a declared V153 INPUT port (uq_wiring respected). Storage columns set to
`DPC`/`delta` (V96 CHECK-safe — no GCP-gold/bq_native conflict). NOT-NULL `sub_pipeline_instances` columns
populated (id, pipeline_id, version_id, blueprint_id, blueprint_key, blueprint_version, name, execution_order,
schema_status, storage_backend).

| Pipeline (tenant) | composition (categories exercised) |
|---|---|
| **Loan Master** (home-lending) — ANCHOR | FileIngestion(`raw_output`) → BronzeToSilverCleaning(`raw_input`→`cleaned_output`) — INGESTION→TRANSFORM |
| **Loan Risk Mart** (home-lending) | FileArrivalSensor(`ready_signal`) ; FileIngestion(`raw_output`) → DQValidator(`data_to_validate`→`validated_output`) → SCD2Dimension(`source_data`→`scd2_output`) → WarehouseWriter(`data_input`) — CONTROL+INGESTION+DATA_QUALITY+MODELING+DESTINATION |
| **Card Transaction Curation** (unsecured-lending) | ApiIngestion(`api_output`) → SchemaNormalization(`source_data`→`normalized_output`) → GenericJoin(`left_input`→`joined_output`) → FactBuild(`transaction_data`→`fact_output`) → LakeWriter(`data_input`) — INGESTION+TRANSFORM×2+MODELING+DESTINATION |

Coverage: ≥1 composition per major category (INGESTION, TRANSFORM, MODELING, DATA_QUALITY, DESTINATION/SINK,
ORCHESTRATION/CONTROL). The composition API (`GET /api/v1/versions/{versionId}/composition`, `CompositionService`)
does NO read-time validation — it returns instances (ordered by execution_order) + wirings; the frontend DagView
resolves ports from the catalog by `blueprint_key`. A fully-rendered graph therefore requires only active
`blueprint_key`s and wiring port names matching declared ports — both enforced by the seed and the IT.

---

## Tests + results

### Fast lane (Flyway DISABLED — V153/V154 do NOT execute; this is the SQL-text contract test)

Command:
```
cd /Users/aameradam/projects/dev/pulse-wt/catalog/backend && ./gradlew fastPrTest --tests "com.pulse.blueprint.V153BuilderOpListsTest"
```
Result: **BUILD SUCCESSFUL — 11/11 tests pass** (executed 2026-06-16). Parses every `'...'::jsonb` literal with a
linear scanner + Jackson and asserts: every op ∈ 32-vocab; every op-entry has op+ui_label+config; every param-ref
resolves; every active param tiered user/derived (derivedFrom iff derived, never the spelling "system-derived");
category never assigned; 4 deprecations + replacements; SnapshotModel→["incremental"] + SCD2 untouched; 2 INSERTs
idempotent w/ connector_instance_id; AggregateMaterialization merged + Derive excluded; AdvanceTimeDimension
consolidated surface.

Regression check:
```
cd .../backend && ./gradlew fastPrTest --tests "com.pulse.blueprint.*"
```
Result: **BUILD SUCCESSFUL** — the new V153 test + the existing V88BlueprintDqClarityTest both pass; no regression.

Compile check (both test classes incl. the IT):
```
cd .../backend && ./gradlew compileTestJava
```
Result: **BUILD SUCCESSFUL** — `V153V154PostgresCatalogIT` compiles.

### Postgres-IT lane (Flyway ON — the REAL V153/V154 execution test) — WRITTEN, NOT RUN HERE

Command:
```
cd /Users/aameradam/projects/dev/pulse-wt/catalog/backend && ./gradlew backendIntegrationTest --tests "com.pulse.config.V153V154PostgresCatalogIT"
```
Result here: **COULD NOT EXECUTE in this environment — NOT a pass.** Attempted 2026-06-16; the Spring context failed
to start with `org.postgresql.util.PSQLException: FATAL: password authentication failed for user "pulse"`. The
project's own Postgres (docker-compose: db/user/pass `pulse`/`pulse`/`pulse` on :5432) is NOT running in this sandbox;
port 5432 is occupied by an unrelated `execintel-postgres` container (per the main checkout's
`docker-compose.override.yml`, which remaps the project Postgres to :5433), and that DB has no `pulse` role — hence
the auth failure. All 6 IT methods failed at context-load (cascading `IllegalStateException`), i.e. an
infrastructure/credentials limitation, NOT an assertion failure and NOT a migration/test defect.

The IT is authored fully (JdbcTemplate, `@Tag("integration")`, `@ActiveProfiles("postgres-it")`) and will run on the
CI integration lane (GitHub Actions Postgres 16 service container with the `pulse` role), asserting against the
post-migration DB: op-lists ∈ 32-vocab; every param-ref resolves; every active param tiered; the 4 deprecations;
SnapshotModel corrected / SCD2 unchanged; no duplicate blueprint_key; SqlModel+SourceSQL inserted; DESTINATION
category preserved; the 3 demo compositions resolve into fully-wired graphs whose wiring port names match the
catalog's declared input/output ports.

---

## Independent verification (producer ≠ verifier)

The 41 op-list/param JSONs were drafted by 6 category sub-agents; the assembled V153 file was then audited by a
SEPARATE verifier sub-agent that parsed all 40 JSON blobs with `json.loads` and checked 17 invariants
(32-op vocab; op-entry shape; param-ref resolution; tier user|derived + derivedFrom-iff-derived; emission-by-category;
category never set; idempotent INSERTs; 4 deprecations + replacements; SnapshotModel/SCD2 artifact_types;
AggregateMaterialization/Derive absent; AdvanceTimeDimension 2-user/17-derived consolidation). Verdict: **CLEAN —
zero violations.** The 38-UPDATE/2-INSERT count (vs a naive 39/41) is correct-by-design (AggregateMaterialization
merged into GenericAggregate).

## Blockers

None blocking the deliverable. One environment limitation: the Postgres-IT lane cannot run in this sandbox (no
`pulse` Postgres; 5432 occupied by an unrelated container). The fast-lane SQL-text test fully passes and the IT is
written + compiles for CI.
