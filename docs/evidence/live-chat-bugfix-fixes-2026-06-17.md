# Live-Chat Bugfix — Implementation Evidence (2026-06-17)

Tracking durable, class-level fixes for the bugs in
`docs/evidence/live-chat-bugfix-report-2026-06-17.md` (LCT-012..LCT-054).
Work proceeds in verified waves; each wave lands a central fix + regression + verification.

---

## Wave A — JSONB / API Shape Normalization (central)

LCTs addressed: LCT-016, LCT-020, LCT-021, LCT-043, LCT-044 (and removes the
shared root cause behind the JSONB symptoms in LCT-036, LCT-039, LCT-041, LCT-052).

### Root cause (proven)

The DB stores correct JSON arrays/objects, but the REST API returned
`{"empty":true,"traversableAgain":true}` for those values.

Spark is on the classpath (cobol preview), which transitively provides
`jackson-module-scala`. Hibernate's default `JacksonJsonFormatMapper` auto-discovers
that module via the Jackson `ServiceLoader`, so untyped (`Object`) values nested in
`@JdbcTypeCode(SqlTypes.JSON)` columns deserialized into **Scala immutable
collections**. The clean Spring MVC `ObjectMapper` (no Scala module) then
bean-serialized those collections as `{empty,traversableAgain}`, corrupting every
JSON array/object exposed through a managed-entity REST DTO.

Proof — with the fix disabled, the regression IT fails with:

```
blueprint DQScorecardPublish holds a non-java collection
(scala.collection.immutable.$colon$colon) from jsonb
```

(`$colon$colon` is Scala's cons-list `::`.)

### Fix

`backend/src/main/java/com/pulse/config/HibernateJsonConfig.java` (new) — a
`HibernatePropertiesCustomizer` that pins Hibernate's JSON format mapper
(`hibernate.type.json_format_mapper` / `AvailableSettings.JSON_FORMAT_MAPPER`) to a
`JacksonJsonFormatMapper` backed by a clean, no-Scala `ObjectMapper`. All
`jsonb` columns now deserialize to plain `java.util` collections, so arrays
round-trip as arrays for every entity exposed via REST.

Defense-in-depth frontend guards (do not mask backend corruption; backend is the
real fix):

- `frontend/src/components/pipeline/column-picker.tsx` — `ColumnPicker` and
  `MultiColumnPicker` normalize `columns`/`selected` with `Array.isArray` before
  `.filter`/`.map`/`.join`/`.includes`.
- `frontend/src/components/pipeline/configure-transform-dialog.tsx` — enum options
  rendered via `Array.isArray(definition.options) ? … : []`.
- `frontend/src/components/sor/credential-dialog.tsx` — `isRequired` guards
  `spec.required` with `Array.isArray` (fixes `required?.includes is not a function`,
  LCT-016).
- `frontend/src/hooks/use-upstream-schema.ts` — `cols` now
  `Array.isArray(data?.columns) ? … : []` (the old `|| []` let a non-array object
  through).
- `frontend/src/lib/schema-diff.ts` — `computeSchemaDiff` normalizes both inputs
  with `Array.isArray` (fixes `output.map is not a function`, LCT-021).

### Regression test

`backend/src/test/java/com/pulse/config/JsonbCollectionShapePostgresIT.java` (new,
`@Tag("integration")`, `postgres-it` profile) — loads all seeded blueprints through
the real Hibernate read path and asserts every nested collection in
`paramsSchema`/`inputPorts`/`outputPorts`/`artifactTypes`/`validLayers` is a
`java.util` type and re-serializes (clean mapper) without `traversableAgain`.

Verified the test is a genuine regression by toggling the fix off (env
`PULSE_DISABLE_JSON_FORMAT_MAPPER_FIX`, since removed): **fails** without the fix,
**passes** with it.

### Verification

- `backendIntegrationTest --tests JsonbCollectionShapePostgresIT` → BUILD SUCCESSFUL
  (DB_PORT=5433 local Postgres).
- Frontend `vitest run` → 29 files, **229 tests passed**.
- ESLint on changed files → 0 errors (1 pre-existing unrelated unused-var warning).

---

## Remaining waves (planned)

- **Wave B/C** — V153 param-schema repair (enum options, param tiering) + Java
  legacy-param drift (`pii_columns`, `delta` default, `fields` vs `columns`):
  LCT-023, 024, 026, 028, 030, 045, 052, 053.
- **Wave D** — DQ canonical state path (read `dqExpectations`, node count,
  schema-aware picker, AI suggest): LCT-035, 036.
- **Wave E** — DAG authoring UX: LCT-012, 013, 032, 046, 049, 050, 051.
- **Wave F** — Dataset/SOR sample upload + infer/PII, duplicate-name 409, DQ
  read-only source gating: LCT-017, 018, 019, 034.
- **Wave G** — connector/sink capability semantics: LCT-045, 047, 048.

---

## Wave B/C — Blueprint Catalog, Runtime Authority, and Param Drift

LCTs addressed: LCT-023, LCT-024, LCT-026, LCT-028, LCT-030, LCT-052, LCT-053.

### Fix

Implemented in isolated worktree `pulse-wt-bc` (branch `bugfix/wave-bc`), merged.

**V156 migration** (6 idempotent UPDATEs):
- LCT-023: Restored `pattern_kind` enum options `["template","glob","regex"]` on FileIngestion + FileArrivalSensor; restored `holiday_calendar_id` options.
- LCT-024: `date_format` promoted `derived`→`user` on all affected blueprints.
- LCT-026: `partition_by` promoted `derived`→`user`.
- LCT-028: Seeded `lake_format` option vocabulary matching `legalLakeFormats()` matrix.
- LCT-030: Added optional `upstream` input port to FileIngestion.

**SchemaPropagationService.java**:
- LCT-030/053: PIIMasking reads canonical `columns_to_mask` first, legacy `pii_columns` as fallback.
- LCT-052: `extractColumns` accepts both `columns` and `fields` wrappers; idempotent audit-column append.

---

## Wave D/E — DAG Authoring UX + DQ Canonical State

LCTs addressed: LCT-012, LCT-013, LCT-032, LCT-034, LCT-035, LCT-036, LCT-046, LCT-049, LCT-050, LCT-051.

### Fix

Implemented in isolated worktree `pulse-wt-de` (branch `bugfix/wave-de`), merged.

- LCT-012: Responsive `fitView({padding, minZoom, maxZoom})` replacing hardcoded `zoom:2.0`.
- LCT-013: `FocusSignal` + `setCenter()` for outline-to-viewport navigation.
- LCT-032: Compact authoring mode toggle.
- LCT-034: `supportsUserDqExpectations()` gate excludes SOR-original sources.
- LCT-035: ExpectationPicker receives `schema`; column params render via `ColumnPicker`/`MultiColumnPicker`; "Suggest DQ rules" button.
- LCT-036: Read canonical `dqInstance.dqExpectations`; node shows "DQ rules (n)".
- LCT-046: Split `selectedInstanceId` (highlight) from `inspectorInstanceId` (panel open); single-click selects, double-click inspects.
- LCT-049: `NewNodePlacement` seeds new nodes next to selected anchor.
- LCT-050: DagNodeComponent accepts `selected` prop with visual ring.
- LCT-051: Custom `DeletableEdge` with inline delete button.

---

## Wave F — Dataset/SOR Workflow

LCTs addressed: LCT-017, LCT-018, LCT-019.

### Fix

Implemented in isolated worktree `pulse-wt-f` (branch `bugfix/wave-f`), merged.

- LCT-017: `SAMPLE_UPLOAD` first-class method added to `DatasetDefinitionMethods` for all connector types.
- LCT-018: Real server-side sample ingestion via `SchemaDiscoveryService.inferSample` (deterministic, zero-LLM, reuses `PiiClassificationService`). Returns typed schema + preview + PII/classification.
- LCT-019: `DatasetController.saveNewDataset` pre-checks `existsByQualifiedName` → HTTP 409; frontend shows inline error banner.

---

## Wave G — Connector/Sink Capability Semantics

LCTs addressed: LCT-045, LCT-047, LCT-048.

### Fix

Implemented in isolated worktree `pulse-wt-g` (branch `bugfix/wave-g`), merged.

- LCT-045: Server-side `enforceConnectorDirectionCompatibility` (only for SORs with explicit `registry_type`); `listFiltered?type=DESTINATION`; `sinkWriterKind=DOCUMENT` mapping for Mongo.
- LCT-047: Central `computeForConnector` readiness; `UNTESTED` is soft yellow, not orange.
- LCT-048: `write_dispositions` metadata per connector family; `sink-capability.ts` helper (27 tests).

### Post-merge fix

The direction enforcement was too aggressive for undifferentiated SORs (no `registry_type`). Fixed to only enforce when the SOR explicitly declares its registry type, preventing 422 errors in existing tests that create both source and destination connectors on the same SOR.

---

## LCT-055 — Common Add/Remove Column Affordances

LCT addressed: LCT-055 (supersedes LCT-037).

### Root cause

The op vocabulary includes `add-column` and `drop-columns` ops, and `SchemaPropagationService` already has universal `applyDerivedColumns()` / `applyDroppedColumns()` addenda that process `derived_columns` and `dropped_columns` params from EVERY blueprint. The gap is that most blueprints don't declare these params in their `params_schema`, so the metadata-driven UI never surfaces them.

### Blueprint audit

| Verdict | Count | Blueprints |
|---|---|---|
| SKIP | 7 | All INGESTION (source-root) |
| HAS-NATIVE | 1 | Derive |
| NEEDS-ADD | 1 | BronzeToSilverCleaning |
| NEEDS-BOTH | 16 | 9 TRANSFORM + 7 MODELING |

### Fix

**V159 migration** — adds `derived_columns` (object[], tier=user) and/or `dropped_columns` (string[], tier=user) to the `params_schema` of the 17 blueprints that need them. Not added to source-root INGESTION blueprints or where a native first-class param already exists (BronzeToSilverCleaning's `drop_columns`).

**DAG node affordances** — "+ Add Column" and "- Remove Col" buttons on nodes whose blueprints have the respective params. Gated by `isSourceRoot()` and `hasNativeDrop` (so BronzeToSilverCleaning gets Add but NOT Remove). Clicking opens the ConfigureTransformDialog where the user can define derived column name/type/expression or select columns to drop via the schema-aware column picker.

### Regression tests

`frontend/src/components/pipeline/dag-node.test.tsx` (5 tests):
- TRANSFORM with both params → both affordances
- BronzeToSilverCleaning → Add only (native drop)
- Source-root INGESTION → neither
- Derive → both (native surface also works)
- DESTINATION → neither

---

## Consolidated Verification

- Backend `fastPrTest` (unit, H2): **BUILD SUCCESSFUL**
- Backend `backendIntegrationTest` (Postgres, DB_PORT=5433): **BUILD SUCCESSFUL** (JsonbCollectionShapePostgresIT, V153V154PostgresCatalogIT, CodeGenerationPostgresCatalogIT)
- Frontend `vitest run`: **32 files, 265 tests passed**
- Frontend ESLint on all changed files: **0 errors**
