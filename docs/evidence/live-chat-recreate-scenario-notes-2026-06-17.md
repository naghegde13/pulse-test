# Live Chat Recreate Scenario Notes

Purpose: after the bug-fix session, use this note to control the browser and recreate the same end-to-end PULSE builder scenario from the live test.

Source ledger: `docs/evidence/live-chat-test-ledger.md`  
Bug-fix handoff: `docs/evidence/live-chat-bugfix-report-2026-06-17.md`

## Starting Assumptions

- Start from a clean seeded local DB if possible.
- Backend: `http://localhost:8080`
- Frontend: `http://localhost:3000`
- Tenant/domain used during live test: Unsecured Lending / Domain 1.
- Runtime storage mode during live test: GCP.
- The prior live-test pipeline id was `01KVBVCFXGPB1T5TQGWTDR826E`, but after bug fixes recreate a fresh pipeline unless explicitly asked to use the old local DB.

## High-Level Scenario

Create a pipeline that ingests a Loan Master file from MSP/S3-compatible object storage, performs file ingestion, cleans Bronze-to-Silver, tests DQ authoring, tests PIIMasking against a dropped `ssn` column, and writes to a MongoDB target sink.

Pipeline shape:

```text
Ingest from MSP (SnapshotIngestion)
  -> File Ingestion (FileIngestion)
  -> Bronze-to-Silver Cleaning (BronzeToSilverCleaning)
  -> PII Masking & Tokenization (PIIMasking)

Bronze-to-Silver Cleaning
  -> Write to MongoDB Connector (DatabaseWriter)
```

## Dataset To Recreate

SOR/source name used: `MSP`  
Dataset name: `Loan Master`  
Classification used in workaround: `CONFIDENTIAL`  
Expected schema fields:

| Name | Type | Notes |
|---|---|---|
| loan_id | VARCHAR | not nullable |
| borrower_name | VARCHAR |  |
| ssn | VARCHAR | PII |
| email | VARCHAR | PII |
| phone | VARCHAR | PII-ish/contact |
| property_address | VARCHAR |  |
| loan_amount | DECIMAL |  |
| interest_rate | DECIMAL |  |
| loan_term_months | INTEGER |  |
| origination_date | DATE |  |
| maturity_date | DATE |  |
| current_balance | DECIMAL |  |
| monthly_payment | DECIMAL |  |
| escrow_balance | DECIMAL |  |
| ltv | DECIMAL |  |
| dti | DECIMAL |  |
| credit_score | INTEGER |  |
| loan_status | VARCHAR |  |
| last_payment_date | DATE |  |
| next_payment_date | DATE |  |
| as_of_date | DATE |  |

Expected PULSE audit columns after ingestion/propagation:

- `_pulse_ingested_at`
- `_pulse_processing_ts`
- `_pulse_pipeline`
- `_pulse_task`
- `_pulse_run_id`
- `_pulse_source_uri`
- `_pulse_business_date`
- `_pulse_dag_id`

## Browser Recreate Steps

1. Open `http://localhost:3000/pipelines`.
2. Select tenant/domain context: Unsecured Lending / Domain 1.
3. Create pipeline:
   - Name: `Pipeline 1` or `Loan Master Curation Pipeline`.
   - If storage backend is shown, use GCP.
   - Expected after fixes: no `defaultStorageBackend is required` error.
4. Create or open source/SOR `MSP`.
5. Add source connector:
   - Connector family: S3-compatible Object Storage.
   - Use existing seeded connector if available.
6. Define dataset:
   - Dataset name: `Loan Master`.
   - Prefer sample upload path after fixes.
   - Verify preview, inferred schema, PII/classification, and persisted fields.
   - If manual fallback is needed, enter the 21 fields listed above.
7. Return to the new pipeline.
8. Add source/ingestion node:
   - Source step: `Ingest from MSP` / `SnapshotIngestion`.
   - It should represent the original SOR/source read.
   - Verify it does not allow DQ authoring if source-root DQ policy is fixed.
9. Add FileIngestion node:
   - Step name: `File Ingestion`.
   - Expected editable fields:
     - `filename_pattern`: `loan_master_{date}.csv` or `loan_master_yyyymmdd`
     - `pattern_kind`: `template`
     - date value/mnemonic: use default run date/PBD-like value if presented.
     - `date_format`: `yyyyMMdd`, but should be editable.
     - `delimiter`: `,`
     - `has_header`: `true`
     - `partition_by`: should be user-configurable.
   - Bind it to dataset `Loan Master` and S3/MSP source connector if binding is separate.
10. Wire:
    - `Ingest from MSP.snapshot_output` -> `File Ingestion.source_input`
    - Expected after fixes: FileIngestion has a left-side input handle if this workflow remains valid.
11. Verify schema propagation:
    - Source output has 29 columns: 21 Loan Master fields + 8 audit columns.
    - FileIngestion input/output preserve the 29 columns without audit duplication.
12. Add Bronze-to-Silver Cleaning:
    - Step name: `Bronze-to-Silver Cleaning`.
    - Wire `File Ingestion.raw_output` -> `Bronze-to-Silver Cleaning.raw_input`.
    - Config used during live test:
      - `trim_columns`: include string columns; at minimum `loan_status`.
      - `drop_columns`: include `ssn`, `property_address`, `loan_status`.
      - `dedup_key`: `loan_id`.
      - `rename_map`: `loan_id` -> `LOAN_ID2`.
      - `fill_null_map`: `loan_status` -> `UNKNOWN`.
      - `type_coercions`: `phone` -> `long`.
      - Do not use separate `drop_null_columns` if removed by fix.
13. Verify Bronze schema:
    - Input has 29 columns.
    - Output should have 26 columns after dropping `ssn`, `property_address`, `loan_status`.
    - `loan_id` may be renamed to `LOAN_ID2`.
14. DQ test:
    - Add a DQ rule to FileIngestion or the appropriate pipeline-owned output.
    - Test that column picker appears; no manual-only `column_name` typing.
    - Save a rule and verify node changes from `+ Add DQ` to `DQ rules (1)` or similar.
    - Reopen and verify saved DQ rule is present.
    - Check whether an AI `Suggest DQ rules` button exists after fixes.
15. Add PIIMasking:
    - Step name: `PII Masking & Tokenization`.
    - Wire `Bronze-to-Silver Cleaning.cleaned_output` -> `PII Masking & Tokenization.sensitive_data`.
    - Config:
      - `columns_to_mask`: `ssn`
      - `masking_strategy`: `hash`
      - `hash_algorithm`: `sha256`
      - `preserve_format`: `false`
16. Expected schema conflict test:
    - Since Bronze dropped `ssn`, PIIMasking should show a missing-column conflict.
    - DAG node should show `Schema conflict`.
    - Right inspector -> `Validation` should show `MISSING_COLUMN` for `ssn`, source param `columns_to_mask`, port `sensitive_data`.
17. Create target/sink:
    - Target name used: `CCODS`.
    - Connector: MongoDB destination connector.
    - Credentials should be connector-specific, not storage-backend derived.
    - Credential fields expected:
      - Secret Manager URI
      - optional regional location
      - credential secret reference/pointer
    - MongoDB connection URI / database details should be collected appropriately if still part of connector metadata.
18. Add MongoDB sink to pipeline:
    - Use target `CCODS` / MongoDB connector.
    - Expected writer blueprint: `DatabaseWriter`, not `LakeWriter`.
    - Expected target label: collection/table appropriate to MongoDB, e.g. target collection.
    - Write modes should be connector-specific, not generic `Append / Overwrite / Merge` unless mapped to real Mongo semantics.
19. Wire:
    - `Bronze-to-Silver Cleaning.cleaned_output` -> `Write to MongoDB Connector.data_input`.
20. Test graph UX:
    - Initial zoom should fit graph comfortably.
    - Outline click should center selected node in graph.
    - Single click selects visibly.
    - Double click opens right inspector after it was closed.
    - Closing inspector should stay closed until explicit reopen.
    - Wire deletion should be discoverable.
    - New nodes should place near selected/insert context, not second row.
    - Compact authoring mode or lighter nodes should reduce congestion.

## Key Bugs To Recheck During Recreate

- LCT-015: create pipeline derives storage backend.
- LCT-017/LCT-018: sample upload path actually previews/infers/classifies schema.
- LCT-020/LCT-021/LCT-033/LCT-043: JSONB shapes stay arrays/objects, no `{empty,traversableAgain}` leaks.
- LCT-022/LCT-029/LCT-031: FileIngestion binding persists and schema matches visible graph.
- LCT-023/LCT-024/LCT-025/LCT-026/LCT-027/LCT-028: filename/storage/partition metadata is correct.
- LCT-034/LCT-035/LCT-036: DQ policy, selector, AI suggestion, and saved-state behavior.
- LCT-039/LCT-041/LCT-042/LCT-052/LCT-054: edit panels render, validate, and allow name editing.
- LCT-045/LCT-047/LCT-048: Mongo target connector, credentials, Add Sink status, writer type, and write modes.
- LCT-046/LCT-049/LCT-050/LCT-051: graph selection/open/delete/placement behavior.
- LCT-053: PIIMasking conflict appears when masking dropped `ssn`.

## Useful Live-Test IDs For Reference Only

Do not hardcode these when recreating on a clean DB, but they are useful if inspecting the old local DB:

- Pipeline: `01KVBVCFXGPB1T5TQGWTDR826E`
- Version: `01KVBVCFXH8E3GBAWN6T1YJ8BX`
- FileIngestion instance: `01KVBWNWKKMPX2QA08R359DY4P`
- SnapshotIngestion instance: `01KVBXN6X6BT9DT3KM1G93XC2V`
- BronzeToSilverCleaning instance: `01KVBZ6CHYYS5DFRGY493EVN2X`
- DatabaseWriter/Mongo sink instance: `01KVC3HSMV2XZD5N0V6W8SPX2F`
- PIIMasking instance: `01KVC3RQFXPBXHVRQGTREJCQ9B`
- Dataset: `01KVBVTNG3QZNGX0F5Z881VVTM`
- Target: `01KVC126VJRGPDHQQM6DZCMX1Z`
- Mongo connector instance: `01KVC12RQSWHQNRYYT8KR1BEWK`

