# Wave G Evidence: Connector/Sink Capability Semantics

Date: 2026-06-17
Branch: bugfix/wave-g
LCTs addressed: LCT-045, LCT-047, LCT-048

## LCT-045: MongoDB sink built from SOURCE metadata; lake/S3 path + LakeWriter

### Root causes fixed

(a) **No source/target filtering** — `ConnectorInstanceController.list` returned ALL connectors for a SOR with no `ConnectorType.DESTINATION` filter. The frontend `add-sink-dialog` rendered SOURCE and DESTINATION connectors side-by-side.

Fix:
- Added `listFiltered` endpoint (`GET /api/v1/sors/{sorId}/connectors?type=DESTINATION&environment=...`) that filters by connector type via `ConnectorInstanceRepository.findBySorIdAndConnectorTypeOrderByNameAsc`.
- Frontend now fetches `?type=DESTINATION&environment=dev` so only destination connectors appear in the Add Sink picker.
- Added `enforceConnectorDirectionCompatibility` on the `create` endpoint: a TARGET registry must receive DESTINATION-typed connector definitions; a source registry must receive SOURCE-typed. 422 UNPROCESSABLE_ENTITY on mismatch.

(b) **Writer-type by name heuristic** — `mapConnectorToBlueprint` substring-matched and fell through to `LakeWriter` for anything unmatched (including MongoDB).

Fix:
- Created `SinkWriterKind` enum (LAKE, WAREHOUSE, RELATIONAL, DOCUMENT, STREAM) on `ConnectorDefinition`.
- Created V158 migration populating `sink_writer_kind` for all 9 destination connector definitions.
- Frontend `resolveSinkBlueprintKey` drives from declared `sinkWriterKind`, mapping `DOCUMENT → DatabaseWriter` (Mongo uses `DatabaseWriter` with a collection target, NOT `LakeWriter`).
- Heuristic fallback (for legacy connectors without declared metadata) defaults to `RELATIONAL` (DatabaseWriter), never `LAKE`, so no unknown connector ever asks for an S3 path.

(c) **Credential/path: lake-path synthesis on non-lake connectors** — `buildDefaultSinkConfig` synthesized `s3://...` from any connector's `configTemplate`, treating connector metadata as platform lake storage.

Fix:
- `buildDefaultSinkConfig` now only synthesizes the lake path when `sinkWriterKind === "LAKE"` AND `credentialStrategy === "INHERIT_TENANT_GCP_SERVICE_ACCOUNT"`. CONNECTOR_SPECIFIC lake connectors (and all non-lake connectors) never have their config template interpreted as platform storage.
- DOCUMENT/RELATIONAL connectors ask for `target_table` (with appropriate label: "Target Collection" for Mongo, "Target Table" for SQL).
- `credential_strategy` is now included in the sink params payload so codegen/deploy can resolve the right credential path.

### Class audit

| Connector family | sinkWriterKind | Blueprint | Target field | Credential strategy |
|---|---|---|---|---|
| S3/GCS Object Storage | LAKE | LakeWriter | output_path | INHERIT_TENANT_GCP_SERVICE_ACCOUNT |
| Snowflake/BigQuery | WAREHOUSE | WarehouseWriter | target_table | CONNECTOR_SPECIFIC |
| Postgres/MySQL/Oracle/MSSQL | RELATIONAL | DatabaseWriter | target_table | CONNECTOR_SPECIFIC |
| MongoDB | DOCUMENT | DatabaseWriter | target_table (= collection) | CONNECTOR_SPECIFIC |
| Kafka | STREAM | StreamWriter | topic | CONNECTOR_SPECIFIC |
| Elasticsearch/Salesforce/REST/SFTP | N/A (source-only) | — | — | — |

Defensive backfill: any DESTINATION definition without a declared `sink_writer_kind` defaults to `RELATIONAL` so the sink UI never silently falls back to lake-path semantics.

## LCT-047: Add Sink says credentials need attention despite configured creds

### Root cause

Two readiness sources disagree. `add-sink-dialog.tsx buildCredentialWarning` read `credentialStatuses[env]` (a flat status map built in `enrich`) and mapped `UNTESTED → "needs attention"` (orange). The authoritative, strategy-aware readiness lives in `CredentialReadinessService.compute` which treats `UNTESTED` differently for `INHERIT_TENANT_GCP_SERVICE_ACCOUNT` connectors.

### Fix

- Extracted `buildConnectorReadinessRow` from `CredentialReadinessService.compute` into a reusable method.
- Added `computeForConnector(ConnectorInstance, String environment)` that produces the same strategy-aware readiness row for a single connector, resolving tenant from the connector's SOR.
- The `listFiltered` endpoint attaches `credentialReadiness` to each enriched connector when `environment` is provided.
- Frontend `buildCredentialWarning` now reads `credentialReadiness.status` first (central contract), falling back to `credentialStatuses[env]` only for older payloads.
- `UNTESTED` is now a soft yellow "not yet validated" notice, NOT an orange "needs attention" gate. `INVALID`/`EXPIRED`/`BLOCKED`/`FAILED` remain orange.

## LCT-048: Mongo write modes generic append/overwrite/merge

### Root cause

`add-sink-dialog.tsx` hardcoded `["append","overwrite","merge"]` with default `write_mode: "append"`. These labels and tokens didn't match any blueprint's actual `write_mode` enum options (V95: DatabaseWriter supports `overwrite_partition`, `append`, `merge_on_pk`).

### Fix

- Added `write_dispositions` JSONB column to `connector_definitions` (V158 migration). Each entry carries `value` (blueprint-legal token) and `label` (connector-family-appropriate copy).
- Frontend `resolveWriteDispositions` reads declared dispositions from the connector, falling back to per-writer-kind defaults.
- Write-mode buttons now render family-specific labels:
  - **Mongo (DOCUMENT)**: Insert documents / Upsert by key / Replace collection
  - **SQL (RELATIONAL)**: Append / Upsert / merge on keys / Truncate & reload
  - **Warehouse**: Overwrite partition / Append / Merge on keys (upsert)
  - **Lake**: Merge on keys (upsert) / Append partition / Overwrite
  - **Stream (Kafka)**: Batch publish / Streaming publish
- `dispositionParamName` maps the selected disposition to the correct blueprint param: `publish_mode` for StreamWriter, `write_mode` for all others.

## Capability metadata model

New enum: `SinkWriterKind { LAKE, WAREHOUSE, RELATIONAL, DOCUMENT, STREAM }` in `com.pulse.sor.model`.

New columns on `connector_definitions`:
- `sink_writer_kind VARCHAR(40)` — nullable, NULL for SOURCE-only definitions
- `write_dispositions JSONB` — array of `{value:string, label:string}`, default first

## Files changed

### Backend (new)
- `backend/src/main/java/com/pulse/sor/model/SinkWriterKind.java`
- `backend/src/main/resources/db/migration/V158__connector_sink_capability_metadata.sql`

### Backend (modified)
- `backend/src/main/java/com/pulse/sor/model/ConnectorDefinition.java` — added `sinkWriterKind`, `writeDispositions` fields + accessors
- `backend/src/main/java/com/pulse/sor/repository/ConnectorInstanceRepository.java` — added `findBySorIdAndConnectorTypeOrderByNameAsc`
- `backend/src/main/java/com/pulse/sor/controller/ConnectorInstanceController.java` — added `listFiltered` endpoint, `enforceConnectorDirectionCompatibility`, `isTargetRegistry`, extended `enrich` with capability fields + readiness, changed `enrich` signature to accept `environment`
- `backend/src/main/java/com/pulse/secret/service/CredentialReadinessService.java` — extracted `buildConnectorReadinessRow`, added `computeForConnector`, `isConnectionReady`
- `backend/src/test/java/com/pulse/sor/controller/ConnectorInstanceControllerTest.java` — added `create_rejectsSourceDefinitionForTargetRegistry`, updated `create_createsConnectorInstance` stubs

### Frontend (new)
- `frontend/src/lib/sink-capability.ts` — pure, testable helper module
- `frontend/src/lib/sink-capability.test.ts` — 27 tests

### Frontend (modified)
- `frontend/src/types/index.ts` — added `SinkWriterKind`, `WriteDisposition`, `ConnectorCredentialReadiness` types; added `sinkWriterKind`, `writeDispositions`, `credentialReadiness` fields to `ConnectorDefinition` and `ConnectorInstance`
- `frontend/src/components/pipeline/add-sink-dialog.tsx` — removed name-heuristic `mapConnectorToBlueprint`, `readString`, `joinPathSegments`, local `SinkConfig`/`CredentialWarning`; replaced with imports from `sink-capability.ts`; fetches `?type=DESTINATION&environment=...`; uses capability-driven target field + write dispositions; uses `credentialReadiness` for warning

## Verification results

- `cd backend && ./gradlew compileJava compileTestJava -q` — **PASS**
- `cd frontend && npx eslint src/components/pipeline/add-sink-dialog.tsx src/lib/sink-capability.ts` — **PASS** (0 errors)
- `cd frontend && npx vitest run` — **256 tests PASS** (30 files, including 27 new sink-capability tests)

## Cross-stream concerns

- `credential-dialog.tsx` was NOT edited (owned by prior wave). The `CredentialWarning` type is exported from `sink-capability.ts` and is structurally identical to the original local type, so the credential dialog is unaffected.
- The `add-connector-dialog.tsx` on the targets page still filters `SOURCE` only (line 56). This should be updated in a separate wave to filter `DESTINATION` for target registries, but the server-side enforcement in `enforceConnectorDirectionCompatibility` will now reject any mismatched attempt at the API level.
- The `targets/[targetId]/page.tsx` and `producers/[sorId]/page.tsx` use the unfiltered `/api/v1/sors/{sorId}/connectors` endpoint. They will continue to receive all connectors (no `type` param), which is backward-compatible.
