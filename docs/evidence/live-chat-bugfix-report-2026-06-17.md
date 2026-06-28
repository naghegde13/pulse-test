# PULSE Live Chat Bug-Fix Report

Date: 2026-06-17  
Source ledger: `docs/evidence/live-chat-test-ledger.md`  
Primary live test pipeline: `/pipelines/01KVBVCFXGPB1T5TQGWTDR826E`  
Primary version: `01KVBVCFXH8E3GBAWN6T1YJ8BX`  
Primary dataset: `Loan Master` / `01KVBVTNG3QZNGX0F5Z881VVTM`

## Executive Summary

This report consolidates issues found during the live PULSE builder rehearsal after LCT-001 through LCT-011 were intentionally removed from the ledger. It covers LCT-012 through LCT-054.

No durable app-code fixes from the live session should be treated as complete. During testing, several local hot patches were applied to unblock the tester and gather more evidence. Those tracked app-code hot patches have now been reverted. The local Postgres database was not reset because it contains the live test objects and evidence state; a fixing agent should start from a clean seeded DB or explicitly document any local DB state it uses.

The bugs are not isolated. The largest defect classes are:

- JSONB/API shape corruption: arrays and objects arrive in the frontend as `{empty,traversableAgain}` or other non-array shapes, causing crashes and hidden data.
- Blueprint catalog/parameter drift: current UI/catalog params do not match backend propagation/codegen assumptions, especially `PIIMasking.columns_to_mask` vs legacy `pii_columns`.
- Generic metadata UI without validation: enum options, required fields, column pickers, map editors, and derived values are rendered too generically and fail common blueprint-specific semantics.
- Connector capability drift: source/target connector type, credential strategy, sink writer type, and write modes are not consistently governed by connector capabilities.
- DAG authoring UX gaps: selection, inspector open/close, edge deletion, node placement, zoom, compact mode, and outline navigation need first-class graph-editor behavior.
- Schema propagation/source binding ambiguity: schema badges and propagated schemas do not reliably reflect current graph wiring, dataset binding, or dropped columns.

## Cleanup Status

Tracked app-code hot patches reverted:

- Backend controllers/services/op-engine files touched for JSONB normalization and schema propagation unblocks.
- Frontend pipeline/SOR UI files touched for edit-dialog guards, DAG inspector behavior, DQ labels, Mongo sink metadata, and credential UI unblocks.

Preserved artifacts:

- `docs/evidence/live-chat-test-ledger.md`
- This report
- Companion prompt: `docs/evidence/live-chat-bugfix-agent-prompt-2026-06-17.md`

Not reverted:

- Local Postgres test data and data patches. These include created pipeline/version rows, dataset recovery rows, local blueprint metadata changes, instance schema patches, and a connector-instance repoint used during testing. Resetting those would destroy the live evidence scenario. The fixing agent should either reset/reseed Postgres or record that it is working against this patched local DB.

## Evidence Collection Standard

For every fix, collect evidence in a new markdown file under `docs/evidence/`, for example:

`docs/evidence/live-chat-bugfix-evidence-2026-06-18.md`

Each issue section should include:

- LCT id(s) fixed.
- Reproduction before fix or a test that fails before fix.
- Root cause with file/line references.
- Code/data changes made.
- Class-level audit performed, not only the single repro.
- Automated verification commands and results.
- Manual browser verification when UI behavior is involved.
- Remaining risk or intentionally deferred follow-up.

## Class-Level Workstreams

### A. JSONB/API Shape Normalization

Related LCTs: LCT-016, LCT-020, LCT-021, LCT-022, LCT-033, LCT-036, LCT-039, LCT-041, LCT-043, LCT-044, LCT-045, LCT-052.

Symptoms:

- Dataset schema fields render as no fields even though DB has JSON arrays.
- Composition `outputSchema.columns` can arrive as a non-array, crashing `output.map`.
- Connector credential specs can expose `required` or `properties` as non-standard shapes.
- Empty JSON collections leak into UI as `empty` and `traversableAgain`.
- Schema-bound controls crash when upstream schema is malformed.

Likely root cause:

- Hibernate/Jackson JSONB mapping into `Map<String,Object>` and nested collection types is not consistently converted to plain DTO-safe JSON before API serialization.
- Frontend controls trust API shapes too much and do not have consistent `Array.isArray` / object guards.

Fix direction:

- Audit every `@JdbcTypeCode(SqlTypes.JSON)` field exposed in REST DTOs.
- Prefer explicit DTO conversion and plain Jackson `JsonNode` / typed DTOs over returning managed entities with raw `Map<String,Object>`.
- Normalize schema wrappers consistently: `fields` vs `columns`.
- Add server tests for JSONB response shapes.
- Keep frontend guards around generic metadata controls, but do not let guards hide backend corruption.

### B. Blueprint Catalog, Runtime Authority, And Param Drift

Related LCTs: LCT-023, LCT-024, LCT-026, LCT-028, LCT-030, LCT-045, LCT-052, LCT-053.

Symptoms:

- FileIngestion `pattern_kind` enum has no options.
- `date_format` and `partition_by` are incorrectly derived/read-only.
- Lake format defaults to or allows Delta despite GCP/DPC rules.
- FileIngestion has no input port for the tested workflow.
- PIIMasking UI uses `columns_to_mask`, while schema propagation checks legacy `pii_columns`.
- Mongo sink metadata is treated like a lake writer.

Likely root cause:

- Later migrations/catalog updates overwrite earlier valid metadata.
- Runtime authority rules are not the single source of truth for storage/lake format defaults.
- Backend propagation and frontend metadata are hardcoded against stale param names.
- Connector/blueprint capabilities are inferred by name rather than declared.

Fix direction:

- Reconcile blueprint catalog migrations with current specs.
- Add catalog validation tests for every blueprint `params_schema`, enum options, ports, and `schema_behavior`.
- Drive derived values from runtime authority and expose resolved values to UI.
- For schema propagation, prefer blueprint `schema_behavior` or a canonical param registry over hardcoded per-blueprint legacy names.

### C. Schema Propagation And Graph Semantics

Related LCTs: LCT-022, LCT-029, LCT-031, LCT-033, LCT-037, LCT-051, LCT-053.

Symptoms:

- FileIngestion binding is not discoverable and does not reliably propagate dataset fields.
- Config save drops hidden dataset/connector binding.
- Schema badges can show fields inconsistent with visible wiring.
- Wired ingestion can collapse to audit-only columns or duplicate audit columns.
- Dropping `ssn` upstream does not create a conflict when PIIMasking masks `ssn`.
- Wire deletion exists in code but is not discoverable.

Likely root cause:

- Dataset binding is hidden in generic params instead of modeled as stable instance/source binding.
- Schema propagation depends on inconsistent params/schema wrappers.
- Audit-column op is not contractually idempotent.
- Missing-column conflict detection only covers selected legacy params.
- Graph UI does not make schema source and wire lifecycle visible.

Fix direction:

- Model source/dataset binding separately or protect it from config-param overwrites.
- Recompute schema deterministically from current graph and explicit bindings.
- Emit conflicts for every params-referenced column absent from the relevant input port.
- Add edge selection/deletion UI and recompute schema after deletion.
- Add tests for graph rewiring, unwiring, upstream drop, and conflict display.

### D. Metadata-Driven Config UI And Validation

Related LCTs: LCT-014, LCT-023, LCT-024, LCT-025, LCT-026, LCT-027, LCT-035, LCT-038, LCT-039, LCT-041, LCT-042, LCT-043, LCT-052, LCT-054.

Symptoms:

- Right config panel is read-only or disconnected from editing.
- Filename/date pattern controls are too abstract and not grouped.
- Derived fields say `Resolved by PULSE` without showing resolved values.
- DQ rule editor requires typing column names and lacks AI suggestions.
- Ports-schema panel looks truncated despite counts.
- Edit dialogs crash on non-array metadata.
- Fill-null and rename map controls share wrong validation semantics.
- Required PIIMasking fields are not enforced.
- Step display name cannot be edited.

Likely root cause:

- Generic renderer lacks typed construct contracts for enums, column selectors, maps, derived values, and required fields.
- Instance metadata and blueprint params are conflated.
- Validation is not enforced consistently client-side or server-side.

Fix direction:

- Formalize UI construct types and validation semantics.
- Add component tests for every seeded blueprint edit dialog.
- Add server-side validation for required blueprint params before saving.
- Expose editable instance metadata (`name`) separately from blueprint params.
- Improve schema list rendering with counts/search/expand affordances.

### E. DAG Authoring UX

Related LCTs: LCT-012, LCT-013, LCT-032, LCT-046, LCT-049, LCT-050, LCT-051.

Symptoms:

- Initial zoom is too large.
- Outline click does not pan/center graph to selected node.
- Canvas feels congested.
- Right inspector reopens after close or selection behavior is unclear.
- New nodes land in a second row.
- Selected node feedback is not obvious.
- Wire deletion has no visible affordance.

Fix direction:

- Implement graph viewport control: fit-to-graph, center-on-outline-selection, predictable zoom.
- Split selected node state from inspector open state.
- Add compact authoring mode or make graph nodes much lighter during editing.
- Add context-aware node placement, such as `insert after selected`.
- Add visible edge deletion.
- Add browser/component regression tests for graph interactions.

### F. Dataset Definition And SOR Flow

Related LCTs: LCT-017, LCT-018, LCT-019, LCT-020, LCT-034, LCT-036.

Symptoms:

- First define-dataset screen hides sample upload.
- Uploaded sample file produces draft/no schema, no preview, no PII call.
- Manual duplicate dataset create crashes generically.
- SOR/source-root node allows DQ authoring when it should be read-only.
- Saved DQ rules do not reopen cleanly.

Fix direction:

- Make sample upload first-class and actually parse file content.
- Show file preview, inferred schema, and PII/classification evidence.
- Convert duplicate-name DB errors to inline validation.
- Add DQ authorability metadata/policy.
- Use canonical `dqExpectations` everywhere.

### G. Connector And Sink Capability Semantics

Related LCTs: LCT-045, LCT-047, LCT-048.

Symptoms:

- Target MongoDB connector was created using source connector metadata.
- MongoDB sink asked for lake/S3 path and lake writer semantics.
- Credential UI copy implied platform storage credentials instead of connector-specific Secret Manager reference.
- Add Sink says Dev credentials need attention despite configured credentials.
- Write modes are generic `Append`, `Overwrite`, `Merge` for MongoDB.

Fix direction:

- Enforce source vs destination connector compatibility server-side.
- Add connector capability metadata for credential strategy, sink writer kind, required config, and write dispositions.
- Centralize credential readiness status so target detail and Add Sink agree.
- Rename/write-mode options by connector family:
  - MongoDB: insert, replace/upsert by key, update by key, explicit destructive drop/truncate reload if supported.
  - SQL/JDBC: append, upsert/merge by key where supported, truncate and reload instead of vague overwrite.
  - Lake/warehouse: follow storage/runtime authority.

## Issue Detail Index

### LCT-012: Pipeline DAG opens too zoomed in

Recreate: open seeded Card Transaction Curation pipeline and inspect initial graph zoom.  
Expected: graph fits comfortably without immediate zoom out.  
Root-cause lead: forced/fixed ReactFlow fit zoom likely ignores graph size and viewport.  
Class: DAG authoring UX.  
Fix: responsive fitView bounds and browser regression for seeded 5-step DAG.

### LCT-013: Outline selection does not navigate graph view

Recreate: click a DAG node in the outline.  
Expected: graph pans/centers to that node and inspector/config context updates.  
Root-cause lead: selected-instance state is wired to inspector, but viewport control is missing.  
Class: DAG authoring UX.  
Fix: expose DagView focus/center API and test outline-to-node navigation.

### LCT-014: Right config panel is read-only

Recreate: select a blueprint and inspect the right panel.  
Expected: right panel should be editable or provide a clear edit path.  
Root-cause lead: inspector shell renders static rows; editable form remains in modal/dialog path.  
Class: metadata config UI.  
Fix: integrate editable constructs or a clear `Edit` mode in the panel.

### LCT-015: Create pipeline missing derived storage backend

Recreate: create a pipeline from `/pipelines` without sending `defaultStorageBackend`.  
Expected: backend/UI derive storage backend from global runtime authority.  
Root-cause lead: API requires `defaultStorageBackend`, but create UI/service does not resolve it.  
Class: runtime authority/catalog drift.  
Fix: derive server-side and show resolved value in UI; add create-pipeline regression.

### LCT-016: Producer credential dialog crashes on `required`

Recreate: open producer credentials for `01KVBVEAWY355TZEY19PN6V1HV`; observe `spec?.required?.includes is not a function`.  
Expected: no crash; malformed spec handled or normalized.  
Root-cause lead: credential spec `required` is not guaranteed to be `string[]`.  
Class: JSONB/API shape normalization.  
Fix: normalize connector spec in API and guard UI.

### LCT-017: Define Dataset missing first-class sample upload option

Recreate: click `Define Dataset`.  
Expected: `Upload Sample File` is a first-class method.  
Root-cause lead: sample upload capability is buried under later method-specific paths.  
Class: SOR/dataset UX.  
Fix: add direct method and route to sample ingestion workflow.

### LCT-018: Uploaded sample file saved as draft with no schema

Recreate: upload sample file during dataset definition.  
Expected: preview, inferred schema, PII/classification call, persisted schema.  
Root-cause lead: upload path appears to record filename/path, not parse uploaded content.  
Class: SOR/dataset workflow.  
Fix: implement sample parse/preview/infer/classify/persist flow with evidence.

### LCT-019: Manual dataset create crashes on duplicate qualified name

Recreate: create manual dataset with duplicate qualified name.  
Expected: inline duplicate validation.  
Root-cause lead: DB unique constraint propagates as opaque API error/Next overlay.  
Class: API error handling / SOR UX.  
Fix: service-level uniqueness check and frontend dialog error display.

### LCT-020: API corrupts schema field array on read

Recreate: inspect `Loan Master` dataset after `SCHEMA_DEFINED`; API returns `schemaSnapshot.fields` as non-array.  
Expected: `fields` is a JSON array and UI renders fields.  
Root-cause lead: JSONB mapping returns proxy-like collection serialized as `{empty,traversableAgain}`.  
Class: JSONB/API shape normalization.  
Temporary fix status: local code workaround was reverted.  
Fix: DTO-safe JSONB conversion and regression on dataset API.

### LCT-021: Composition output schema serializes as non-array shape

Recreate: add canvas step and load pipeline; `output.map is not a function`.  
Expected: DAG renders or handles malformed schema gracefully.  
Root-cause lead: `SubPipelineInstance.outputSchema` JSONB mapping returns non-array for `columns`.  
Class: JSONB/API shape normalization.  
Temporary fix status: local code workaround was reverted.  
Fix: DTO-safe composition response plus frontend schema-diff guards.

### LCT-022: FileIngestion binding is not discoverable and does not propagate source fields

Recreate: add FileIngestion step and try to link it to defined dataset/connector.  
Expected: explicit binding UI and output schema from dataset fields plus audit columns.  
Root-cause lead: binding is hidden in params and propagation cannot reliably read JSONB params/schema.  
Class: schema propagation/source binding.  
Temporary fix status: code workaround reverted; local DB still contains test patches.  
Fix: first-class binding model/UI, protected config saves, propagation regression.

### LCT-023: File pattern kind dropdown empty and filename mnemonic assist missing

Recreate: edit FileIngestion `pattern_kind`.  
Expected: enum options and filename/date assist.  
Root-cause lead: migration V153 overwrote enum options; filename builder not implemented.  
Class: blueprint metadata UI.  
Temporary fix status: local DB patch remains only in DB.  
Fix: repair catalog/migration and implement filename-pattern builder.

### LCT-024: File date format must be user-overridable

Recreate: FileIngestion `date_format` renders derived/read-only.  
Expected: default suggestion but editable value.  
Root-cause lead: catalog marks `date_format` as derived from platform default.  
Class: blueprint metadata/runtime authority.  
Fix: change canonical metadata and grouped filename UI.

### LCT-025: Date Value / Mnemonic control needs product-language assist

Recreate: inspect isolated `Date Value` control.  
Expected: clear relation to `{date}` in filename pattern with example preview.  
Root-cause lead: controls are separate abstract fields.  
Class: metadata UI.  
Fix: grouped filename-pattern builder with preview.

### LCT-026: File partitioning must be user-configurable

Recreate: FileIngestion `partition_by` is derived/read-only.  
Expected: user-configurable partition columns.  
Root-cause lead: metadata marks it derived.  
Class: blueprint metadata/runtime authority.  
Fix: make user-tier and render with column picker.

### LCT-027: `Resolved by PULSE` must reveal resolved value

Recreate: inspect derived fields.  
Expected: actual value and source, or unresolved error.  
Root-cause lead: UI has metadata but not resolver output.  
Class: metadata UI/runtime authority.  
Fix: expose resolved config view model from backend.

### LCT-028: Lake format must follow runtime storage authority, not Delta defaults

Recreate: inspect storage/lake format defaults/options for GCP/DPC.  
Expected: GCP uses BigQuery-managed Iceberg; DPC uses Hive-managed Parquet on S3.  
Root-cause lead: catalog/frontend still seed/allow Delta; runtime authority not central.  
Class: runtime authority/catalog drift.  
Fix: centralize legal formats, update catalog/options/codegen/deploy validation.

### LCT-029: Config save must preserve dataset/connector binding

Recreate: bind FileIngestion to dataset, edit filename config, inspect params/schema.  
Expected: binding persists.  
Root-cause lead: config save overwrites params with visible metadata draft and loses hidden keys.  
Class: schema propagation/source binding.  
Fix: protect binding namespace or model separately; merge saves safely.

### LCT-030: FileIngestion needs left-side input handle for this workflow

Recreate: attempt to wire into FileIngestion; no left handle.  
Expected: input port if workflow supports upstream source/context wiring.  
Root-cause lead: catalog has `input_ports=[]`.  
Class: blueprint catalog/graph semantics.  
Fix: decide canonical FileIngestion port semantics and update catalog/spec/codegen.

### LCT-031: Schema badges must match wiring state

Recreate: inspect unwired FileIngestion after binding/schema changes.  
Expected: badge reflects current graph source, or labels hidden dataset binding.  
Root-cause lead: stale/manual schema and hidden binding are indistinguishable from wired propagation.  
Class: schema propagation/graph semantics.  
Fix: recompute from graph+explicit bindings and expose schema source.

### LCT-032: Graph editor needs compact authoring mode

Recreate: build/edit pipeline in current graph editor.  
Expected: less congested authoring surface with minimal node state.  
Class: DAG authoring UX.  
Fix: compact node mode, collapsible side panels, better fit/search/minimap behavior.

### LCT-033: Wired ingestion schema propagation drops business fields

Recreate: wire SnapshotIngestion to FileIngestion and recompute.  
Expected: Loan Master business fields plus exactly 8 audit columns.  
Root-cause lead: dataset `fields` vs schema `columns` mismatch plus JSONB param/schema read issues; audit op duplicates.  
Class: JSONB/schema propagation.  
Temporary fix status: local code workaround was reverted.  
Fix: central schema normalization and idempotent audit columns.

### LCT-034: SOR-original source nodes must not offer `+ Add DQ`

Recreate: inspect `Ingest from MSP` source-root node.  
Expected: no DQ authoring on SOR-original/read-only source node.  
Root-cause lead: DQ affordance gated by broad blueprint category.  
Class: DQ policy/schema authoring.  
Fix: blueprint/instance capability `supportsUserDqExpectations`.

### LCT-035: Feature request: DQ editor needs schema-aware column selection and AI suggestions

Recreate: add DQ rule; column name must be typed manually.  
Expected: column selector and `Suggest DQ rules` AI action.  
Class: DQ authoring UX/AI.  
Fix: pass schema to expectation picker and add structured LLM recommendation flow.

### LCT-036: Saved DQ rules must reopen and change node affordance

Recreate: save DQ rule on FileIngestion; reopen/view node label.  
Expected: `DQ rules (n)`/edit state and existing rules loaded.  
Root-cause lead: UI reads legacy `params.dq_expectations`, not canonical `dqExpectations`; JSONB `kwargs` shape also suspect.  
Class: DQ state/JSONB.  
Temporary fix status: local code workaround was reverted.  
Fix: canonical DQ state path, required-param validation, tests.

### LCT-037: FileIngestion needs visible add/drop column path

Recreate: configure FileIngestion and look for add/drop controls.  
Expected: direct controls or guided downstream cleaning/derive step.  
Class: schema-shaping UX.  
Fix: product decision and implementation path for add/drop after ingestion.

### LCT-038: Ports-Schema column lists look truncated

Recreate: open Ports-Schema with 29 columns.  
Expected: clear complete list with counts/scroll/search.  
Root-cause lead: tiny nested scroll area hides most rows.  
Class: inspector UX.  
Fix: list counts, larger/table/expandable schema view.

### LCT-039: Bronze-to-Silver edit step crashes in MultiColumnPicker

Recreate: click Edit Step on Bronze-to-Silver Cleaning.  
Expected: dialog renders.  
Root-cause lead: non-array upstream schema passed to picker.  
Class: JSONB/API shape plus frontend guards.  
Temporary fix status: local code workaround was reverted.  
Fix: normalize upstream schema API and guard controls.

### LCT-040: Browser annotation tool is not persisting comments

Recreate: use in-app browser Annotate, type comment, submit.  
Expected: annotation persists and appears with target metadata.  
Class: Codex/in-app browser tooling, not PULSE product.  
Fix owner: tooling owner, not PULSE unless reproduced in PULSE-owned annotation feature.

### LCT-041: FileIngestion edit step crashes on enum options

Recreate: click Edit Step on FileIngestion after guard patch context.  
Expected: dialog renders enum/string controls.  
Root-cause lead: enum option computation calls `.map` on non-array.  
Class: metadata UI/JSONB shape.  
Temporary fix status: local code workaround was reverted.  
Fix: normalize params schema and guard enum rendering; smoke test every blueprint dialog.

### LCT-042: Bronze cleaning mapper controls are misleading and over-validate

Recreate: edit Trim Columns, Fill Null Map, Rename Map.  
Expected: string-column bulk select; Fill Null left column selector/right value; Rename Map validates targets only after input.  
Root-cause lead: one rename-centric component reused for semantically different map params.  
Class: metadata construct semantics.  
Temporary fix status: local code workaround was reverted.  
Fix: formal mapper variants and tests.

### LCT-043: JSONB placeholder keys leak into config controls

Recreate: edit Bronze cleaning maps after empty JSONB params.  
Expected: no `empty`/`traversableAgain` rows.  
Root-cause lead: JSONB collection proxy serialized to UI.  
Class: JSONB/API shape.  
Temporary fix status: local code workaround was reverted.  
Fix: backend JSONB normalization and UI quarantine tests.

### LCT-044: Target sink credential dialog crashes and leaks JSONB placeholders

Recreate: click target sink credentials.  
Expected: no hook-order crash and no fake credential fields.  
Root-cause lead: conditional hooks plus malformed connector spec/properties.  
Class: credential UI/JSONB shape.  
Temporary fix status: local code workaround was reverted.  
Fix: repair hooks, normalize spec, add credential dialog smoke tests.

### LCT-045: MongoDB sink metadata and credential reference semantics are wrong

Recreate: create/use MongoDB target sink and inspect credentials/add-sink config.  
Expected: destination connector, connector-specific Secret Manager reference inputs, database writer/collection target.  
Root-cause lead: target add-connector filtered source connectors; AddSink uses name heuristics/default LakeWriter; credential strategy not passed consistently.  
Class: connector capabilities.  
Temporary fix status: local code workaround was reverted; local DB connector instance may still be repointed.  
Fix: server-side source/target enforcement, connector capability metadata, Mongo credential model and tests.

### LCT-046: DAG node click reopens right inspector after explicit close

Recreate: close inspector, single-click node.  
Expected: close is sticky; single-click selects; double-click opens.  
Root-cause lead: selected instance state directly rendered inspector.  
Class: DAG authoring UX.  
Temporary fix status: local code workaround was reverted.  
Fix: split selection/open state and test.

### LCT-047: Add Data Sink credential-status gate disagrees with configured Dev credentials

Recreate: Add Sink -> Target / CCODS -> MongoDB connector.  
Expected: no false `Credentials need attention for Dev`.  
Root-cause lead: Add Sink and target detail use inconsistent credential readiness sources.  
Class: connector credentials.  
Fix: central credential status contract and regression.

### LCT-048: MongoDB Add Sink write modes use generic relational labels

Recreate: configure MongoDB sink and inspect write modes.  
Expected: connector-specific document/collection write modes; SQL destructive mode should say truncate/reload.  
Root-cause lead: `AddSinkDialog` hardcodes generic modes.  
Class: connector capabilities.  
Fix: capability-driven write disposition metadata and docs-backed labels.

### LCT-049: New DAG nodes are placed on a second row

Recreate: add a new pipeline step.  
Expected: placed near selected/insert point.  
Root-cause lead: generic layout lacks current selection/context.  
Class: DAG authoring UX.  
Fix: context-aware placement and layout tests.

### LCT-050: DAG single-click selection is not visually obvious

Recreate: single-click DAG node after inspector behavior changes.  
Expected: obvious selected state and edit target.  
Root-cause lead: custom node did not visibly consume ReactFlow selected prop.  
Class: DAG authoring UX.  
Temporary fix status: local code workaround was reverted.  
Fix: selected badge/ring/toolbar summary and tests.

### LCT-051: DAG wiring has no discoverable delete affordance

Recreate: try deleting wire from Bronze-to-Silver Cleaning to MongoDB sink.  
Expected: visible delete wire action.  
Root-cause lead: deletion only exists through implicit ReactFlow edge selection and keyboard path.  
Class: DAG authoring UX/schema lifecycle.  
Fix: edge selection toolbar/inspector/delete action and recompute verification.

### LCT-052: PII Masking required dropdowns are empty and save validation is missing

Recreate: add/edit PIIMasking and inspect masking strategy/hash algorithm; save missing required fields.  
Expected: populated options and blocked save.  
Root-cause lead: enum metadata not reaching renderer or renderer/validation fails.  
Class: metadata validation/catalog drift.  
Fix: verify live `params_schema`, enforce required client/server, tests.

### LCT-053: PIIMasking does not flag missing masked column after upstream drop

Recreate: Bronze-to-Silver drops `ssn`; PIIMasking masks `ssn`; inspect schema conflict state.  
Expected: `MISSING_COLUMN` conflict for `ssn`, DAG `Schema conflict`, Validation tab row.  
Root-cause lead: `SchemaPropagationService.collectMissingChecks` checks legacy `pii_columns`; current catalog/UI use `columns_to_mask`.  
Class: schema propagation/param drift.  
Fix: recognize current and legacy params or drive checks from `schema_behavior`; add upstream-drop regression.

### LCT-054: Blueprint step name is suggested and not editable

Recreate: add/edit blueprint step and try to rename display name.  
Expected: user can edit step/display name.  
Root-cause lead: instance metadata name is derived from blueprint/suggestion and not exposed in edit dialog.  
Class: config UI/instance metadata.  
Fix: editable instance metadata API/UI and consistency across outline/DAG/inspector/codegen.

## Suggested Verification Matrix

Backend:

```bash
cd backend
./gradlew test
./gradlew test --tests 'com.pulse.deploy.controller.RepresentativeStaticDeployabilityProofIT'
```

Frontend:

```bash
cd frontend
npm run lint
npm run test:unit
```

Targeted browser checks:

- Create pipeline with no explicit `defaultStorageBackend`.
- Define dataset via sample upload and verify preview/schema/PII evidence.
- Add FileIngestion, bind dataset, edit config, confirm binding survives.
- Wire SnapshotIngestion -> FileIngestion -> BronzeToSilver -> PIIMasking -> sink.
- Drop `ssn` upstream and verify PIIMasking conflict.
- Save/reopen DQ rules.
- Add MongoDB target sink and verify connector/credential/write-mode semantics.
- Graph editor: zoom, outline focus, selection, double-click inspector, edge deletion, node placement.

