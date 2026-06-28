-- V5: Seed realistic test data for development.
-- Pipelines across Home Lending D&I domains at various lifecycle stages with version history.
--
-- Per PKT-FINAL-2 (2026-05-25), PULSE only tracks PULSE-observable lifecycle
-- stages: DRAFT, ENGINEERING, DEV_DEPLOYED, DEV_VALIDATED, PUBLISHED.
-- PUBLISHED is the terminal PULSE-managed state — anything past it is
-- "handed off to enterprise CD" and not reflected in pipeline_versions.

-- =====================================================
-- ACME CORP PIPELINES
-- =====================================================

-- 1. Sales Daily Revenue (mature: v1.0.0 + v0.9.0 published; v1.1.0 in draft)
INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JPIPE0SALES0REVENUE0001', 'tenant-home-lending', 'finance', 'Sales Daily Revenue',
        'Ingests POS transaction data from Salesforce, applies currency normalization and fraud filtering, builds daily revenue fact table with regional breakdowns. SLA: 6am EST availability.',
        'stub-user-001', '01JVER0SALES0REV0V100001', NOW() - INTERVAL '90 days', NOW());

INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES
    ('01JVER0SALES0REV0V100001', '01JPIPE0SALES0REVENUE0001', '1.0.0', 'PUBLISHED', 'stub-user-001',
     '{"freshness_hours": 6, "business_sla": "Daily by 6am EST"}',
     '{"git_tag": "pipeline/01JPIPE0SALES0REVENUE0001/v1.0.0", "artifactory_path": "acme/pipelines/sales-daily-revenue/1.0.0"}',
     'Initial published release (handed off to enterprise CD)', NOW() - INTERVAL '30 days', NOW() - INTERVAL '30 days'),
    ('01JVER0SALES0REV0V011001', '01JPIPE0SALES0REVENUE0001', '0.9.0', 'PUBLISHED', 'stub-user-001',
     '{"freshness_hours": 8}', '{}', 'Pre-release with basic transformations', NOW() - INTERVAL '60 days', NOW() - INTERVAL '45 days'),
    ('01JVER0SALES0REV0V110001', '01JPIPE0SALES0REVENUE0001', '1.1.0', 'DRAFT', 'stub-user-001',
     '{"freshness_hours": 4, "business_sla": "Daily by 4am EST"}', '{}',
     'Add APAC region support and tighten SLA to 4am', NOW() - INTERVAL '2 days', NOW());

-- 2. Customer 360 Profile (published; downstream UAT is owned by enterprise CD)
INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JPIPE0CUST03600PROFILE1', 'tenant-home-lending', 'marketing', 'Customer 360 Profile',
        'Consolidates customer data from CRM, support tickets, web analytics, and billing into a unified SCD2 dimension. Feeds recommendation engine and marketing segmentation.',
        'stub-user-001', '01JVER0CUST0360V010PRD01', NOW() - INTERVAL '75 days', NOW() - INTERVAL '3 days');

INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES
    ('01JVER0CUST0360V010PRD01', '01JPIPE0CUST03600PROFILE1', '1.0.0', 'PUBLISHED', 'stub-user-001',
     '{"freshness_hours": 12, "business_sla": "Daily refresh"}',
     '{"git_tag": "pipeline/01JPIPE0CUST03600PROFILE1/v1.0.0", "artifactory_path": "acme/pipelines/customer-360/1.0.0"}',
     'Full customer consolidation with SCD2 and PII masking', NOW() - INTERVAL '15 days', NOW() - INTERVAL '3 days');

-- 3. Marketing Campaign Attribution (published, awaiting UAT)
INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JPIPE0MKT0ATTRIBUTION01', 'tenant-home-lending', 'marketing', 'Campaign Attribution Pipeline',
        'Multi-touch attribution model for marketing campaigns. Joins ad spend data from Google Ads, Meta, and LinkedIn with conversion events to calculate ROAS and CAC by channel.',
        'stub-user-001', '01JVER0MKT0ATTR0V100PUB1', NOW() - INTERVAL '45 days', NOW() - INTERVAL '5 days');

INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES
    ('01JVER0MKT0ATTR0V100PUB1', '01JPIPE0MKT0ATTRIBUTION01', '1.0.0', 'PUBLISHED', 'stub-user-001',
     '{"freshness_hours": 24, "business_sla": "Weekly on Monday"}',
     '{"git_tag": "pipeline/01JPIPE0MKT0ATTRIBUTION01/v1.0.0", "artifactory_path": "acme/pipelines/campaign-attribution/1.0.0"}',
     'Initial release with Google Ads and Meta integration', NOW() - INTERVAL '10 days', NOW() - INTERVAL '5 days');

-- 4. Supply Chain Inventory Tracker (published; previously labelled "integration qualified" pre-PKT-FINAL-2)
INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JPIPE0OPS0INVENTORY001', 'tenant-home-lending', 'operations', 'Supply Chain Inventory',
        'Real-time inventory reconciliation across 12 warehouses. CDC ingestion from SAP, dedup and merge with shipping manifests, anomaly detection for stock discrepancies.',
        'stub-user-001', '01JVER0OPS0INV0V010INT01', NOW() - INTERVAL '30 days', NOW() - INTERVAL '1 day');

INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES
    ('01JVER0OPS0INV0V010INT01', '01JPIPE0OPS0INVENTORY001', '0.1.0', 'PUBLISHED', 'stub-user-001',
     '{"freshness_hours": 1, "business_sla": "Hourly during business hours"}', '{}',
     'Initial version with SAP CDC and 3 warehouse onboarded', NOW() - INTERVAL '15 days', NOW() - INTERVAL '1 day');

-- 5. Financial Close Reconciliation (dev validated)
INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JPIPE0FIN0RECONCILE001', 'tenant-home-lending', 'finance', 'Financial Close Reconciliation',
        'Month-end close reconciliation pipeline. Matches GL entries against sub-ledger transactions, flags unreconciled items, and produces variance reports for the controller team.',
        'stub-user-001', '01JVER0FIN0RECON0V010DV1', NOW() - INTERVAL '20 days', NOW() - INTERVAL '2 days');

INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES
    ('01JVER0FIN0RECON0V010DV1', '01JPIPE0FIN0RECONCILE001', '0.1.0', 'DEV_VALIDATED', 'stub-user-001',
     '{"freshness_hours": 24, "business_sla": "Monthly T+2 close"}', '{}',
     'Core matching logic with AP/AR sub-ledgers', NOW() - INTERVAL '20 days', NOW() - INTERVAL '2 days');

-- 6. Ops Logistics ETL (dev deployed)
INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JPIPE0OPS0LOGISTICS001', 'tenant-home-lending', 'operations', 'Logistics Route Optimization',
        'Ingests GPS telemetry and delivery schedules, computes route efficiency metrics, and feeds the optimization model with historical patterns and fuel cost data.',
        'stub-user-001', '01JVER0OPS0LOGI0V010DD01', NOW() - INTERVAL '10 days', NOW() - INTERVAL '1 day');

INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES
    ('01JVER0OPS0LOGI0V010DD01', '01JPIPE0OPS0LOGISTICS001', '0.1.0', 'DEV_DEPLOYED', 'stub-user-001',
     '{"freshness_hours": 2}', '{}',
     'GPS ingestion and basic route metrics', NOW() - INTERVAL '10 days', NOW() - INTERVAL '1 day');

-- 7. Marketing Email Engagement (engineering — pipeline composition in progress)
INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JPIPE0MKT0EMAIL0ENG01', 'tenant-home-lending', 'marketing', 'Email Engagement Analytics',
        'Tracks email open rates, click-through rates, and conversion funnels from Marketo and Mailchimp. Builds engagement scoring model for lead prioritization.',
        'stub-user-001', '01JVER0MKT0EMAIL0V010CF1', NOW() - INTERVAL '5 days', NOW() - INTERVAL '1 day');

INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES
    ('01JVER0MKT0EMAIL0V010CF1', '01JPIPE0MKT0EMAIL0ENG01', '0.1.0', 'ENGINEERING', 'stub-user-001',
     '{"freshness_hours": 6}', '{}',
     'Marketo connector configured, engagement scoring defined', NOW() - INTERVAL '5 days', NOW() - INTERVAL '1 day');

-- 8. Finance Budget Forecasting (draft)
INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JPIPE0FIN0BUDGET0FC001', 'tenant-home-lending', 'finance', 'Budget Forecasting Pipeline',
        'Combines historical spend data with departmental budget inputs to produce rolling 12-month forecasts. Integrates with Adaptive Insights for plan vs actual variance.',
        'stub-user-001', '01JVER0FIN0BUDGET0V010D1', NOW() - INTERVAL '2 days', NOW());

INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES
    ('01JVER0FIN0BUDGET0V010D1', '01JPIPE0FIN0BUDGET0FC001', '0.1.0', 'DRAFT', 'stub-user-001',
     '{}', '{}', 'Initial draft', NOW() - INTERVAL '2 days', NOW());


-- =====================================================
-- GLOBEX INDUSTRIES PIPELINES
-- =====================================================

-- 9. Sales Pipeline Forecasting (published; downstream production owned by enterprise CD)
INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JPIPE0GLX0SALES0FCST01', 'tenant-unsecured-lending', 'sales', 'Sales Pipeline Forecasting',
        'Pulls opportunity data from HubSpot CRM, enriches with historical win rates and deal velocity metrics, and produces weekly sales forecasts by region and product line.',
        'stub-user-001', '01JVER0GLX0SALES0V200P01', NOW() - INTERVAL '120 days', NOW() - INTERVAL '10 days');

INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES
    ('01JVER0GLX0SALES0V100P01', '01JPIPE0GLX0SALES0FCST01', '1.0.0', 'PUBLISHED', 'stub-user-001',
     '{"freshness_hours": 24, "business_sla": "Weekly Monday 8am"}',
     '{"git_tag": "pipeline/01JPIPE0GLX0SALES0FCST01/v1.0.0"}',
     'Initial published release with basic forecasting', NOW() - INTERVAL '90 days', NOW() - INTERVAL '60 days'),
    ('01JVER0GLX0SALES0V200P01', '01JPIPE0GLX0SALES0FCST01', '2.0.0', 'PUBLISHED', 'stub-user-001',
     '{"freshness_hours": 12, "business_sla": "Daily by noon"}',
     '{"git_tag": "pipeline/01JPIPE0GLX0SALES0FCST01/v2.0.0", "artifactory_path": "globex/pipelines/sales-forecast/2.0.0"}',
     'Major rewrite: switched to ML-based forecasting, daily cadence', NOW() - INTERVAL '30 days', NOW() - INTERVAL '10 days');

-- 10. Warehouse Throughput Analytics (engineering — codegen output drafted)
INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JPIPE0GLX0WHSE0THRPT01', 'tenant-unsecured-lending', 'logistics', 'Warehouse Throughput Analytics',
        'Monitors pick/pack/ship rates across distribution centers. Ingests WMS events via CDC, computes throughput KPIs, and detects bottlenecks using volume spike detection.',
        'stub-user-001', '01JVER0GLX0WHSE0V010GN01', NOW() - INTERVAL '14 days', NOW() - INTERVAL '3 days');

INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES
    ('01JVER0GLX0WHSE0V010GN01', '01JPIPE0GLX0WHSE0THRPT01', '0.1.0', 'ENGINEERING', 'stub-user-001',
     '{"freshness_hours": 1}', '{}',
     'WMS CDC ingestion and throughput KPI models generated', NOW() - INTERVAL '14 days', NOW() - INTERVAL '3 days');

-- 11. Analytics Data Mart (draft)
INSERT INTO pipelines (id, tenant_id, domain_name, name, description, created_by, active_version_id, created_at, updated_at)
VALUES ('01JPIPE0GLX0ANALYTICS0M1', 'tenant-unsecured-lending', 'analytics', 'Executive Analytics Data Mart',
        'Wide denormalized mart combining sales, logistics, and financial data for the executive dashboard. Refreshes nightly with full rebuild on weekends.',
        'stub-user-001', '01JVER0GLX0ANALYT0V010D1', NOW() - INTERVAL '1 day', NOW());

INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, change_summary, created_at, updated_at)
VALUES
    ('01JVER0GLX0ANALYT0V010D1', '01JPIPE0GLX0ANALYTICS0M1', '0.1.0', 'DRAFT', 'stub-user-001',
     '{}', '{}', 'Initial mart schema design', NOW() - INTERVAL '1 day', NOW());

-- =====================================================
-- SAMPLE SUB-PIPELINE INSTANCES (for the production Sales Daily Revenue pipeline)
-- =====================================================

INSERT INTO sub_pipeline_instances (id, pipeline_id, version_id, blueprint_id, blueprint_version, name, execution_order, params, input_datasets, output_datasets, created_at, updated_at)
VALUES
    ('01JSUB0SALESFORCE0ING001', '01JPIPE0SALES0REVENUE0001', '01JVER0SALES0REV0V100001',
     'ApiIngestion', '1.2.0', 'Salesforce Transaction Ingest', 1,
     '{"source_type": "salesforce", "object": "Opportunity", "incremental": true, "rate_limit_rpm": 100}',
     '[]',
     '[{"ref": "acme.finance.raw.sf_opportunities", "format": "delta"}]',
     NOW() - INTERVAL '30 days', NOW() - INTERVAL '30 days'),

    ('01JSUB0CURRENCY0NORM0001', '01JPIPE0SALES0REVENUE0001', '01JVER0SALES0REV0V100001',
     'SchemaNormalization', '1.0.0', 'Currency Normalization', 2,
     '{"target_currency": "USD", "rate_source": "ecb_daily", "decimal_precision": 4}',
     '[{"ref": "acme.finance.raw.sf_opportunities"}]',
     '[{"ref": "acme.finance.silver.normalized_transactions", "format": "delta"}]',
     NOW() - INTERVAL '30 days', NOW() - INTERVAL '30 days'),

    ('01JSUB0FRAUD0FILTER00001', '01JPIPE0SALES0REVENUE0001', '01JVER0SALES0REV0V100001',
     'DQValidator', '2.1.0', 'Fraud & Anomaly Filter', 3,
     '{"rules": ["amount_range_check", "duplicate_txn_check", "velocity_check"], "policy": "quarantine"}',
     '[{"ref": "acme.finance.silver.normalized_transactions"}]',
     '[{"ref": "acme.finance.silver.validated_transactions", "format": "delta"}]',
     NOW() - INTERVAL '30 days', NOW() - INTERVAL '30 days'),

    ('01JSUB0REVENUE0FACT00001', '01JPIPE0SALES0REVENUE0001', '01JVER0SALES0REV0V100001',
     'FactBuild', '1.1.0', 'Daily Revenue Fact Build', 4,
     '{"grain": "daily", "dimensions": ["region", "product_line", "channel"], "incremental": true}',
     '[{"ref": "acme.finance.silver.validated_transactions"}]',
     '[{"ref": "acme.finance.gold.fact_daily_revenue", "format": "delta"}]',
     NOW() - INTERVAL '30 days', NOW() - INTERVAL '30 days');
