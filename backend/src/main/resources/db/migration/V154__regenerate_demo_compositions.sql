-- V154: Regenerate the demo-pipeline composition seed against the V153 op-composition model.
--
-- The legacy V5 demo compositions referenced stale `acme.*` dataset-ref strings and
-- free-form params (e.g. DQValidator {"rules":...,"policy":...}) that do not satisfy the
-- V153 params_schema surface, so they no longer render cleanly in the composition page.
--
-- This migration inserts FRESH demo pipelines/versions/instances/wirings scoped to the
-- renamed tenants (tenant-home-lending / tenant-unsecured-lending), leaving V5's rows in
-- place. Every instance:
--   * references a V153-active blueprint_key (so the composition page resolves its ports),
--   * carries `params` populated only from that blueprint's V153 user-tier params with
--     valid values (derived params are inspector-only and omitted),
--   * uses input_datasets/output_datasets refs in the renamed tenant's dataset namespace,
--   * sets storage columns to DPC/delta (V96 CHECK-safe: no GCP gold/bq_native conflict).
-- Every port_wiring connects a declared V153 OUTPUT port -> a declared V153 INPUT port,
-- respecting uq_wiring (version_id, target_instance_id, target_port_name).
--
-- Coverage: >= 1 composition per major category (INGESTION, TRANSFORM, MODELING,
-- DATA_QUALITY, DESTINATION/SINK, ORCHESTRATION/CONTROL). Anchor = loan_master
-- (FileIngestion -> BronzeToSilverCleaning) under tenant-home-lending.
--
-- The composition API (GET /api/v1/versions/{versionId}/composition) returns
-- sub_pipeline_instances (ordered by execution_order) + port_wirings as-is; the frontend
-- DagView builds nodes from blueprint_key/name/execution_order/params and looks up ports
-- from the V153 catalog. No read-time validation: a fully-resolved graph requires only
-- that blueprint_key is active and wiring port names match the declared ports.

-- =============================================================================
-- HOME LENDING — Pipeline 1: loan_master (ANCHOR)
--   FileIngestion(raw_output) -> BronzeToSilverCleaning(raw_input -> cleaned_output)
-- =============================================================================

INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JDEMO0HL0LOANMASTER0001', 'tenant-home-lending', 'lending', 'Loan Master',
        'Anchor demo pipeline: ingests the daily loan master file from object storage into bronze, then cleans/standardizes it into silver. Exercises FileIngestion -> BronzeToSilverCleaning end to end.',
        'stub-user-001', '01JDEMO0HL0LOANMASTERV0101', NOW() - INTERVAL '30 days', NOW());

INSERT INTO pipeline_versions (id, pipeline_id, revision, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES ('01JDEMO0HL0LOANMASTERV0101', '01JDEMO0HL0LOANMASTER0001', 1, 'PUBLISHED', 'stub-user-001',
        '{"freshness_hours": 6, "business_sla": "Daily by 6am EST"}',
        '{"git_tag": "pipeline/01JDEMO0HL0LOANMASTER0001/v1.0.0"}',
        'Loan master ingest + bronze-to-silver cleaning (V153 op-composition model)',
        NOW() - INTERVAL '15 days', NOW());

INSERT INTO sub_pipeline_instances (id, pipeline_id, version_id, blueprint_id, blueprint_key, blueprint_version, name, execution_order, params, input_datasets, output_datasets, schema_status, storage_backend, lake_layer, lake_format, created_at, updated_at)
VALUES
    ('01JDEMO0HL0LM0FILEING0001', '01JDEMO0HL0LOANMASTER0001', '01JDEMO0HL0LOANMASTERV0101',
     'FileIngestion', 'FileIngestion', '1.0.0', 'Loan Master File Ingest', 1,
     '{"filename_pattern": "loan_master_{date}.csv", "pattern_kind": "template", "date_value": "RUN_DATE", "delimiter": ",", "has_header": true}',
     '[]',
     '[{"ref": "home-lending.lending.bronze.loan_master_raw", "format": "delta", "role": "bronze"}]',
     'clean', 'DPC', 'bronze', 'delta', NOW() - INTERVAL '15 days', NOW()),

    ('01JDEMO0HL0LM0CLEANING001', '01JDEMO0HL0LOANMASTER0001', '01JDEMO0HL0LOANMASTERV0101',
     'BronzeToSilverCleaning', 'BronzeToSilverCleaning', '1.0.0', 'Loan Master Cleaning', 2,
     '{"trim_columns": ["borrower_name", "property_address"], "type_coercions": {"loan_amount": "decimal", "origination_date": "date"}, "drop_null_columns": ["loan_id"], "dedup_key": ["loan_id"]}',
     '[{"ref": "home-lending.lending.bronze.loan_master_raw", "role": "bronze"}]',
     '[{"ref": "home-lending.lending.silver.loan_master_clean", "format": "delta", "role": "silver"}]',
     'clean', 'DPC', 'silver', 'delta', NOW() - INTERVAL '15 days', NOW());

INSERT INTO port_wirings (id, version_id, source_instance_id, source_port_name, target_instance_id, target_port_name)
VALUES ('01JDEMO0WIRE0HL0LM00010002', '01JDEMO0HL0LOANMASTERV0101',
        '01JDEMO0HL0LM0FILEING0001', 'raw_output',
        '01JDEMO0HL0LM0CLEANING001', 'raw_input');

-- =============================================================================
-- HOME LENDING — Pipeline 2: loan_risk_mart (full medallion + DQ + MODELING + SINK)
--   FileArrivalSensor(ready_signal) ........ control gate
--   FileIngestion(raw_output) -> DQValidator(data_to_validate -> validated_output)
--      -> SCD2Dimension(source_data -> scd2_output) -> WarehouseWriter(data_input)
-- =============================================================================

INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JDEMO0HL0RISKMART000001', 'tenant-home-lending', 'risk', 'Loan Risk Mart',
        'Demo pipeline exercising render breadth across categories: a file-arrival sensor gates a file ingest, validated by GX, tracked as an SCD2 risk dimension, and published to the warehouse.',
        'stub-user-001', '01JDEMO0HL0RISKMARTV010001', NOW() - INTERVAL '20 days', NOW());

INSERT INTO pipeline_versions (id, pipeline_id, revision, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES ('01JDEMO0HL0RISKMARTV010001', '01JDEMO0HL0RISKMART000001', 1, 'DEV_VALIDATED', 'stub-user-001',
        '{"freshness_hours": 12}', '{}',
        'Sensor -> ingest -> DQ -> SCD2 -> warehouse (V153 op-composition model)',
        NOW() - INTERVAL '10 days', NOW());

INSERT INTO sub_pipeline_instances (id, pipeline_id, version_id, blueprint_id, blueprint_key, blueprint_version, name, execution_order, params, input_datasets, output_datasets, schema_status, storage_backend, lake_layer, lake_format, created_at, updated_at)
VALUES
    ('01JDEMO0HL0RM0SENSOR00001', '01JDEMO0HL0RISKMART000001', '01JDEMO0HL0RISKMARTV010001',
     'FileArrivalSensor', 'FileArrivalSensor', '1.0.0', 'Risk File Arrival', 1,
     '{"storage_kind": "gcs", "bucket": "home-lending-risk", "path_prefix": "inbound/loans", "filename_pattern": "loan_risk_{date}.csv", "pattern_kind": "template", "date_value": "RUN_DATE", "expected_max_age_hours": 26}',
     '[]', '[]',
     'clean', 'DPC', NULL, NULL, NOW() - INTERVAL '10 days', NOW()),

    ('01JDEMO0HL0RM0INGEST00001', '01JDEMO0HL0RISKMART000001', '01JDEMO0HL0RISKMARTV010001',
     'FileIngestion', 'FileIngestion', '1.0.0', 'Risk File Ingest', 2,
     '{"filename_pattern": "loan_risk_{date}.csv", "pattern_kind": "template", "date_value": "RUN_DATE", "delimiter": ",", "has_header": true}',
     '[]',
     '[{"ref": "home-lending.risk.bronze.loan_risk_raw", "format": "delta", "role": "bronze"}]',
     'clean', 'DPC', 'bronze', 'delta', NOW() - INTERVAL '10 days', NOW()),

    ('01JDEMO0HL0RM0DQ000000001', '01JDEMO0HL0RISKMART000001', '01JDEMO0HL0RISKMARTV010001',
     'DQValidator', 'DQValidator', '1.0.0', 'Risk Data Validation', 3,
     '{"expectations": [{"type": "expect_column_values_to_not_be_null", "kwargs": {"column": "loan_id"}}, {"type": "expect_column_values_to_be_between", "kwargs": {"column": "ltv_ratio", "min_value": 0, "max_value": 1.5}}], "on_failure": "quarantine", "threshold_percent": 99.0, "mostly": 0.99}',
     '[{"ref": "home-lending.risk.bronze.loan_risk_raw", "role": "bronze"}]',
     '[{"ref": "home-lending.risk.silver.loan_risk_validated", "format": "delta", "role": "silver"}, {"ref": "home-lending.risk.quarantine.loan_risk_quarantine", "format": "delta", "role": "quarantine"}]',
     'clean', 'DPC', NULL, NULL, NOW() - INTERVAL '10 days', NOW()),

    ('01JDEMO0HL0RM0SCD2000001', '01JDEMO0HL0RISKMART000001', '01JDEMO0HL0RISKMARTV010001',
     'SCD2Dimension', 'SCD2Dimension', '1.0.0', 'Loan Risk SCD2 Dimension', 4,
     '{"business_key": ["loan_id"], "tracked_columns": ["risk_grade", "ltv_ratio", "dti_ratio"], "effective_date_column": "as_of_date"}',
     '[{"ref": "home-lending.risk.silver.loan_risk_validated", "role": "silver"}]',
     '[{"ref": "home-lending.risk.gold.dim_loan_risk_scd2", "format": "delta", "role": "gold"}]',
     'clean', 'DPC', 'gold', 'delta', NOW() - INTERVAL '10 days', NOW()),

    ('01JDEMO0HL0RM0WHWRITE0001', '01JDEMO0HL0RISKMART000001', '01JDEMO0HL0RISKMARTV010001',
     'WarehouseWriter', 'WarehouseWriter', '1.0.0', 'Publish Risk Dimension', 5,
     '{"target_id": "wh-home-lending-analytics", "target_table": "analytics.dim_loan_risk", "write_mode": "merge_on_pk", "merge_keys": ["loan_id"]}',
     '[{"ref": "home-lending.risk.gold.dim_loan_risk_scd2", "role": "gold"}]',
     '[]',
     'clean', 'DPC', NULL, NULL, NOW() - INTERVAL '10 days', NOW());

INSERT INTO port_wirings (id, version_id, source_instance_id, source_port_name, target_instance_id, target_port_name)
VALUES
    ('01JDEMO0WIRE0HL0RM00020003', '01JDEMO0HL0RISKMARTV010001',
     '01JDEMO0HL0RM0INGEST00001', 'raw_output',
     '01JDEMO0HL0RM0DQ000000001', 'data_to_validate'),
    ('01JDEMO0WIRE0HL0RM00030004', '01JDEMO0HL0RISKMARTV010001',
     '01JDEMO0HL0RM0DQ000000001', 'validated_output',
     '01JDEMO0HL0RM0SCD2000001', 'source_data'),
    ('01JDEMO0WIRE0HL0RM00040005', '01JDEMO0HL0RISKMARTV010001',
     '01JDEMO0HL0RM0SCD2000001', 'scd2_output',
     '01JDEMO0HL0RM0WHWRITE0001', 'data_input');

-- =============================================================================
-- UNSECURED LENDING — Pipeline 3: card_txn_curation (TRANSFORM breadth + MODELING + LAKE SINK)
--   ApiIngestion(api_output) -> SchemaNormalization(source_data -> normalized_output)
--      -> GenericJoin(left_input + right_input -> joined_output)
--      -> FactBuild(transaction_data + dimension_refs -> fact_output)
--      -> LakeWriter(data_input)
-- =============================================================================

INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JDEMO0UL0CARDTXN000001', 'tenant-unsecured-lending', 'cards', 'Card Transaction Curation',
        'Demo pipeline exercising TRANSFORM + MODELING + lake-sink breadth: API ingest of card transactions, schema normalization, a join to the merchant dimension, a daily spend fact, written to the lake.',
        'stub-user-001', '01JDEMO0UL0CARDTXNV0100001', NOW() - INTERVAL '14 days', NOW());

INSERT INTO pipeline_versions (id, pipeline_id, revision, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES ('01JDEMO0UL0CARDTXNV0100001', '01JDEMO0UL0CARDTXN000001', 1, 'ENGINEERING', 'stub-user-001',
        '{"freshness_hours": 4}', '{}',
        'API ingest -> normalize -> join -> fact -> lake (V153 op-composition model)',
        NOW() - INTERVAL '7 days', NOW());

INSERT INTO sub_pipeline_instances (id, pipeline_id, version_id, blueprint_id, blueprint_key, blueprint_version, name, execution_order, params, input_datasets, output_datasets, schema_status, storage_backend, lake_layer, lake_format, created_at, updated_at)
VALUES
    ('01JDEMO0UL0CT0APIING00001', '01JDEMO0UL0CARDTXN000001', '01JDEMO0UL0CARDTXNV0100001',
     'ApiIngestion', 'ApiIngestion', '1.0.0', 'Card Transactions API Ingest', 1,
     '{"api_url": "https://api.cards.example.com/v2/transactions", "auth_type": "bearer", "pagination_type": "cursor", "incremental_field": "posted_at", "response_json_path": "$.data", "rate_limit_rpm": 120}',
     '[]',
     '[{"ref": "unsecured-lending.cards.bronze.card_txn_raw", "format": "delta", "role": "bronze"}]',
     'clean', 'DPC', 'bronze', 'delta', NOW() - INTERVAL '7 days', NOW()),

    ('01JDEMO0UL0CT0NORM0000001', '01JDEMO0UL0CARDTXN000001', '01JDEMO0UL0CARDTXNV0100001',
     'SchemaNormalization', 'SchemaNormalization', '1.0.0', 'Normalize Card Transactions', 2,
     '{"target_schema": "card_txn_canonical", "mapping_rules": {"txn_amt": "transaction_amount", "mrch_id": "merchant_id"}, "strict_mode": false}',
     '[{"ref": "unsecured-lending.cards.bronze.card_txn_raw", "role": "bronze"}]',
     '[{"ref": "unsecured-lending.cards.silver.card_txn_normalized", "format": "delta", "role": "silver"}]',
     'clean', 'DPC', 'silver', 'delta', NOW() - INTERVAL '7 days', NOW()),

    ('01JDEMO0UL0CT0JOIN0000001', '01JDEMO0UL0CARDTXN000001', '01JDEMO0UL0CARDTXNV0100001',
     'GenericJoin', 'GenericJoin', '1.0.0', 'Join Merchant Dimension', 3,
     '{"join_type": "left", "join_keys": [{"left_column": "merchant_id", "right_column": "merchant_id"}], "select_columns": ["transaction_id", "transaction_amount", "merchant_id", "merchant_name", "merchant_category"]}',
     '[{"ref": "unsecured-lending.cards.silver.card_txn_normalized", "role": "silver"}, {"ref": "unsecured-lending.cards.silver.dim_merchant", "role": "silver"}]',
     '[{"ref": "unsecured-lending.cards.silver.card_txn_enriched", "format": "delta", "role": "silver"}]',
     'clean', 'DPC', 'silver', 'delta', NOW() - INTERVAL '7 days', NOW()),

    ('01JDEMO0UL0CT0FACT0000001', '01JDEMO0UL0CARDTXN000001', '01JDEMO0UL0CARDTXNV0100001',
     'FactBuild', 'FactBuild', '1.0.0', 'Daily Card Spend Fact', 4,
     '{"grain": "one row per card per day", "measures": ["transaction_amount"], "dimension_keys": ["merchant_id", "card_id", "date_key"], "incremental": true, "time_column": "posted_at"}',
     '[{"ref": "unsecured-lending.cards.silver.card_txn_enriched", "role": "silver"}, {"ref": "unsecured-lending.cards.gold.dim_merchant", "role": "gold"}]',
     '[{"ref": "unsecured-lending.cards.gold.fact_card_spend_daily", "format": "delta", "role": "gold"}]',
     'clean', 'DPC', 'gold', 'delta', NOW() - INTERVAL '7 days', NOW()),

    ('01JDEMO0UL0CT0LAKEWR00001', '01JDEMO0UL0CARDTXN000001', '01JDEMO0UL0CARDTXNV0100001',
     'LakeWriter', 'LakeWriter', '1.0.0', 'Publish Spend Fact to Lake', 5,
     '{"target_id": "lake-unsecured-lending", "output_path": "s3a://unsecured-lending-lake/gold/fact_card_spend_daily", "write_mode": "merge_on_pk", "merge_keys": ["card_id", "date_key", "merchant_id"], "optimize_after_write": true, "z_order_columns": ["card_id"]}',
     '[{"ref": "unsecured-lending.cards.gold.fact_card_spend_daily", "role": "gold"}]',
     '[]',
     'clean', 'DPC', NULL, 'delta', NOW() - INTERVAL '7 days', NOW());

INSERT INTO port_wirings (id, version_id, source_instance_id, source_port_name, target_instance_id, target_port_name)
VALUES
    ('01JDEMO0WIRE0UL0CT00010002', '01JDEMO0UL0CARDTXNV0100001',
     '01JDEMO0UL0CT0APIING00001', 'api_output',
     '01JDEMO0UL0CT0NORM0000001', 'source_data'),
    ('01JDEMO0WIRE0UL0CT00020003', '01JDEMO0UL0CARDTXNV0100001',
     '01JDEMO0UL0CT0NORM0000001', 'normalized_output',
     '01JDEMO0UL0CT0JOIN0000001', 'left_input'),
    ('01JDEMO0WIRE0UL0CT00030004', '01JDEMO0UL0CARDTXNV0100001',
     '01JDEMO0UL0CT0JOIN0000001', 'joined_output',
     '01JDEMO0UL0CT0FACT0000001', 'transaction_data'),
    ('01JDEMO0WIRE0UL0CT00040005', '01JDEMO0UL0CARDTXNV0100001',
     '01JDEMO0UL0CT0FACT0000001', 'fact_output',
     '01JDEMO0UL0CT0LAKEWR00001', 'data_input');
