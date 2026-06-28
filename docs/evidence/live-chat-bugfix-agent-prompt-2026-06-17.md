# Agent Prompt: Fix PULSE Live Chat Rehearsal Bugs

You are taking over a PULSE bug-fixing session.

Start by reading:

1. `AGENTS.md`
2. `docs/evidence/live-chat-bugfix-report-2026-06-17.md`
3. `docs/evidence/live-chat-test-ledger.md`
4. Relevant source files named in the report for the workstream you are fixing.

Do not assume that the previous live-test local database is clean. The prior session used local DB patches to keep testing moving. Prefer starting from a clean seeded Postgres database, or explicitly document the DB state you use.

## Goal

Fix the bugs found during the live PULSE builder rehearsal, especially LCT-012 through LCT-054. Treat temporary workarounds in the ledger as evidence, not as finished fixes.

For every bug, you must be able to answer:

- How do I recreate it?
- What is the root cause?
- Is this a one-off bug, or part of a larger class?
- What is the durable fix?
- What evidence proves the fix works?

## Parallelism Requirement

Maximize safe parallelism. Use Codex native subagents for independent workstreams where available. Do not run multiple agents against the same files without coordination.

Suggested lanes:

1. JSONB/API shape normalization
   - LCT-016, LCT-020, LCT-021, LCT-022, LCT-033, LCT-036, LCT-039, LCT-041, LCT-043, LCT-044, LCT-045, LCT-052.
   - Audit all JSONB fields exposed through REST DTOs, not only the specific failing screens.

2. Blueprint catalog/runtime authority/schema propagation
   - LCT-023, LCT-024, LCT-026, LCT-028, LCT-030, LCT-052, LCT-053.
   - Audit the entire blueprint catalog for enum options, required params, ports, storage/lake format defaults, and param-name drift.

3. Metadata-driven config UI and validation
   - LCT-014, LCT-025, LCT-027, LCT-035, LCT-038, LCT-039, LCT-041, LCT-042, LCT-043, LCT-052, LCT-054.
   - Audit all blueprint edit panels, not only BronzeToSilverCleaning and PIIMasking.

4. DAG authoring UX
   - LCT-012, LCT-013, LCT-032, LCT-046, LCT-049, LCT-050, LCT-051.
   - Verify behavior in the browser: zoom, outline navigation, selection, inspector open/close, node placement, wire deletion, compactness.

5. Dataset/SOR/DQ workflow
   - LCT-017, LCT-018, LCT-019, LCT-034, LCT-035, LCT-036.
   - Audit sample upload, manual dataset create errors, DQ authorability, saved DQ state, and DQ column picker/AI suggestion flow.

6. Connector and sink capability semantics
   - LCT-045, LCT-047, LCT-048.
   - Audit all source/target connectors and all sink families, not just MongoDB. MongoDB was the example that revealed the class.

Use one coordinating owner to integrate results, avoid conflicts, and run final verification.

## Non-Negotiable Definition Of Done

A bug is not fixed until:

- There is a failing reproduction or test before the fix, or a clearly documented pre-fix observation from the ledger.
- Root cause is documented with file/line references.
- The fix is durable and does not rely on local DB hand edits.
- Related class-level audit is performed.
- Automated tests or browser checks prove the behavior.
- Evidence is written under `docs/evidence/`.

Create an evidence file such as:

`docs/evidence/live-chat-bugfix-evidence-YYYY-MM-DD.md`

Each section should include:

```md
## LCT-0XX - Title

Status: Fixed / Deferred / Not Reproducible

Reproduction:
- ...

Root cause:
- file:line evidence

Class-level audit:
- Other affected endpoints/components/catalog rows checked
- Findings

Fix:
- Files changed
- Behavioral change

Verification:
- Command/browser check
- Result

Remaining risk:
- ...
```

## Specific Guidance By Bug Class

### JSONB/API Shape Normalization

Do not patch individual controllers by re-reading raw JSONB as a workaround unless that is the chosen central API strategy. Find the shared model/DTO/serialization boundary and fix it there.

Audit at least:

- Dataset `schemaSnapshot`
- SubPipelineInstance `params`
- SubPipelineInstance `outputSchema`
- DQ expectation `kwargs`
- Connector definition `connectionSpec`
- Blueprint `paramsSchema`, `inputPorts`, `outputPorts`, and `schemaBehavior`
- Any upstream-schema endpoint response

Add tests that assert arrays are arrays in JSON responses. The UI should also guard malformed data, but frontend guards must not become the primary data correction mechanism.

### Blueprint Catalog And Runtime Authority

Treat catalog data as executable product configuration. Audit every blueprint touched by migrations V7, V81, V97, V153, and V155 for:

- enum params with missing options
- required params with no validation
- derived params that should be user-editable
- user params that should be derived
- stale param names between UI, schema propagation, codegen, and blueprint `schema_behavior`
- illegal storage/lake defaults
- missing or incorrect input/output ports

For storage/lake format:

- GCP should use BigQuery-managed Iceberg tables.
- DPC should use Hive-managed Parquet files on S3 storage.
- Delta should not appear as a default unless the product rules explicitly support it.

### PIIMasking

Fix both LCT-052 and LCT-053 together.

Required behavior:

- `columns_to_mask` is the current canonical param.
- Legacy `pii_columns` may be accepted only as backward compatibility.
- Missing masked columns must emit a `MISSING_COLUMN` schema conflict.
- The conflict should appear on the DAG node and in the right inspector `Validation` tab.
- Masking strategy/hash algorithm options must render.
- Required params must block save.

Add a regression:

1. Upstream schema contains `ssn`.
2. Bronze-to-Silver drops `ssn`.
3. PIIMasking has `columns_to_mask=["ssn"]`.
4. Recompute emits conflict and marks PIIMasking conflict.

### MongoDB And Sink Capabilities

Do not fix MongoDB by adding more name heuristics. Introduce or use connector capabilities.

Required behavior:

- Target pages list destination connector definitions, not source connector definitions.
- Backend rejects incompatible source/target connector-definition attachment.
- MongoDB target uses connector-specific credentials, not storage-backend derived credentials.
- Mongo credential UI collects:
  - Secret Manager URI
  - optional location for regional secrets
  - credential secret reference/pointer
- Mongo sink should not be modeled as `LakeWriter`.
- Add Sink credential readiness must agree with target detail readiness.
- Write modes must be connector-specific.

Class audit:

- Check MongoDB, JDBC/SQL Server if present, warehouse connectors, lake/object storage connectors, stream connectors.
- Verify each maps to the correct writer blueprint and config surface.

### DAG UX

Do browser verification, not only unit tests.

Required behavior:

- Initial graph fit is comfortable.
- Outline click centers/selects the graph node.
- Single-click selects visibly.
- Double-click opens the inspector if the inspector was closed.
- Closing the inspector is sticky.
- New node placement is context-aware.
- Wire deletion is discoverable.
- Compact mode or lighter node presentation addresses authoring congestion.

### Dataset And DQ Workflow

Sample upload must be real sample ingestion, not a filename-only path.

Required behavior:

- First method screen exposes sample upload clearly.
- Uploaded sample shows preview.
- Schema is inferred and persisted.
- PII/classification analysis is invoked or explicitly unavailable with explanation.
- Duplicate manual dataset names show inline errors.
- Source-root/SOR-owned nodes do not allow DQ authoring.
- DQ editor uses column selectors and can invoke AI suggestions.
- Saved DQ rules reopen and node affordance reflects saved count.

## Verification Commands

Run at least:

```bash
cd backend
./gradlew test
./gradlew test --tests 'com.pulse.deploy.controller.RepresentativeStaticDeployabilityProofIT'
```

```bash
cd frontend
npm run lint
npm run test:unit
```

Add targeted tests where coverage is missing. For browser-visible UX, run local backend/frontend and capture manual evidence or Playwright evidence.

## Final Response Requirements

When finished, report:

- Bugs fixed by LCT id.
- Bugs intentionally deferred and why.
- Evidence file path.
- Commands run and results.
- Remaining risks.
- Whether the local DB was clean or patched.

