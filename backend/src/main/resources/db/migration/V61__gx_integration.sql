-- V61: Great Expectations (GX) integration -- DQ validation results table,
--       GX expectation suite blueprint, DQ readiness score, and DQ expectations per instance.
--       Also updates existing DQ blueprints with GX-mapped params_schema.

-- =====================================================
-- 1. DQ Validation Results table
-- =====================================================

CREATE TABLE dq_validation_results (
    id                VARCHAR(26)   PRIMARY KEY,
    pipeline_run_id   VARCHAR(255),
    instance_id       VARCHAR(26)   NOT NULL REFERENCES sub_pipeline_instances(id) ON DELETE CASCADE,
    tenant_id         VARCHAR(255)  NOT NULL,
    suite_name        VARCHAR(255)  NOT NULL,
    success           BOOLEAN       NOT NULL,
    statistics        JSONB         NOT NULL DEFAULT '{}',
    results           JSONB         NOT NULL DEFAULT '[]',
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dqvr_instance   ON dq_validation_results(instance_id);
CREATE INDEX idx_dqvr_tenant     ON dq_validation_results(tenant_id);
CREATE INDEX idx_dqvr_suite      ON dq_validation_results(suite_name);
CREATE INDEX idx_dqvr_created    ON dq_validation_results(created_at);
CREATE INDEX idx_dqvr_run        ON dq_validation_results(pipeline_run_id);

-- =====================================================
-- 2. Add dq_readiness_score to pipeline_versions
-- =====================================================

ALTER TABLE pipeline_versions ADD COLUMN dq_readiness_score INTEGER;

-- =====================================================
-- 3. Add dq_expectations to sub_pipeline_instances
-- =====================================================

ALTER TABLE sub_pipeline_instances ADD COLUMN dq_expectations JSONB;

-- =====================================================
-- 4. New blueprint: GX Expectation Suite
-- =====================================================

INSERT INTO blueprints (id, blueprint_key, name, description, category, version, params_schema, input_ports, output_ports, deferred) VALUES
('01JBP0DQ0GXSUITE00000001', 'GXExpectationSuite', 'GX Expectation Suite',
 'Direct access to the full Great Expectations catalog. Configure individual expectations with parameters, severity levels, and failure behavior. Runs GX Checkpoint inline in PySpark jobs with ephemeral context.',
 'DATA_QUALITY', '1.0.0',
 '[{"name":"expectations","type":"object[]","required":true,"description":"Array of GX expectations: {type, kwargs, severity}"},{"name":"on_failure","type":"enum","options":["fail","warn","quarantine"],"default":"quarantine","description":"Behavior when expectations fail"},{"name":"data_docs_enabled","type":"boolean","default":false,"description":"Generate GX Data Docs HTML"},{"name":"slack_webhook","type":"string","description":"Slack webhook URL for failure notifications"},{"name":"email_recipients","type":"string[]","description":"Email addresses for failure notifications"}]',
 '[{"name":"data_to_validate","description":"Spark DataFrame or table reference to validate"}]',
 '[{"name":"validation_result","description":"GX ValidationResult JSON"},{"name":"validated_data","description":"Original DataFrame (annotated)"},{"name":"quarantine_data","description":"Rows that failed critical expectations"}]',
 false);

-- =====================================================
-- 5. Update existing 7 DQ blueprints with GX-mapped params_schema
-- =====================================================

-- 5a. DQValidator -> GX Expectation Suite + Checkpoint
UPDATE blueprints SET params_schema = '[
  {"name":"expectations","type":"object[]","required":true,"description":"Array of GX expectation configs: {type, kwargs, severity}. Example type: ExpectColumnValuesToNotBeNull"},
  {"name":"on_failure","type":"enum","options":["fail_fast","quarantine","alert_only"],"default":"quarantine","description":"Behavior on validation failure"},
  {"name":"threshold_percent","type":"number","default":99.0,"description":"Minimum success percentage to pass"},
  {"name":"mostly","type":"number","default":1.0,"description":"Default GX mostly parameter (0.0-1.0) for all expectations"}
]'::jsonb
WHERE blueprint_key = 'DQValidator';

-- 5b. AnomalyDetection -> GX Statistical Expectations
UPDATE blueprints SET params_schema = '[
  {"name":"monitored_columns","type":"string[]","required":true,"description":"Columns to monitor for anomalies"},
  {"name":"sensitivity_percent","type":"number","default":2.0,"description":"Z-score or IQR sensitivity threshold"},
  {"name":"detection_method","type":"enum","options":["z_score","iqr","mean_deviation"],"default":"z_score","description":"Statistical detection method"},
  {"name":"lookback_runs","type":"integer","default":10,"description":"Number of historical runs for baseline"},
  {"name":"gx_expectations","type":"object[]","description":"Mapped GX expectations: ExpectColumnMeanToBeBetween, ExpectColumnStdevToBeBetween, ExpectColumnZScoresToBeLessThan, ExpectTableRowCountToBeBetween"}
]'::jsonb
WHERE blueprint_key = 'AnomalyDetection';

-- 5c. FreshnessChecks -> GX ExpectColumnMaxToBeBetween
UPDATE blueprints SET params_schema = '[
  {"name":"timestamp_column","type":"string","required":true,"description":"Timestamp column to check freshness on"},
  {"name":"max_age_hours","type":"integer","default":24,"description":"Maximum allowed age in hours"},
  {"name":"max_age_minutes","type":"integer","description":"Maximum allowed age in minutes (overrides hours if set)"},
  {"name":"gx_expectation","type":"string","default":"ExpectColumnMaxToBeBetween","description":"Mapped GX expectation for freshness check"}
]'::jsonb
WHERE blueprint_key = 'FreshnessChecks';

-- 5d. ReferentialIntegrityCheck -> GX ExpectColumnDistinctValuesToContainSet
UPDATE blueprints SET params_schema = '[
  {"name":"child_key_column","type":"string","required":true,"description":"Foreign key column in child table"},
  {"name":"parent_key_column","type":"string","required":true,"description":"Primary key column in parent table"},
  {"name":"allow_nulls","type":"boolean","default":false,"description":"Whether null FK values are allowed"},
  {"name":"violation_policy","type":"enum","options":["reject","flag","log"],"default":"flag","description":"How to handle integrity violations"},
  {"name":"gx_expectation","type":"string","default":"ExpectColumnDistinctValuesToContainSet","description":"Mapped GX expectation for referential integrity"}
]'::jsonb
WHERE blueprint_key = 'ReferentialIntegrityCheck';

-- 5e. SchemaDriftDetection -> GX Schema Expectations
UPDATE blueprints SET params_schema = '[
  {"name":"expected_columns","type":"object[]","required":true,"description":"Expected column definitions: [{name, type}]"},
  {"name":"strict_order","type":"boolean","default":false,"description":"Whether column order must match exactly"},
  {"name":"allow_extra_columns","type":"boolean","default":true,"description":"Whether extra columns in incoming data are allowed"},
  {"name":"drift_policy","type":"enum","options":["block","warn","auto_adapt"],"default":"warn","description":"Action on schema drift"},
  {"name":"gx_expectations","type":"object[]","description":"Mapped GX expectations: ExpectTableColumnsToMatchOrderedList, ExpectTableColumnsToMatchSet, ExpectTableColumnCountToEqual, ExpectColumnValuesToBeOfType"}
]'::jsonb
WHERE blueprint_key = 'SchemaDriftDetection';

-- 5f. Reconciliation -> GX Multi-Source Expectations
UPDATE blueprints SET params_schema = '[
  {"name":"reconciliation_type","type":"enum","options":["row_count","column_sum","full_match"],"default":"row_count","description":"Type of reconciliation comparison"},
  {"name":"key_columns","type":"string[]","required":true,"description":"Columns to match records between source and target"},
  {"name":"value_columns","type":"string[]","description":"Columns to compare values on (for column_sum and full_match)"},
  {"name":"tolerance_percent","type":"number","default":0.01,"description":"Acceptable difference percentage"},
  {"name":"gx_expectations","type":"object[]","description":"Mapped GX expectations: ExpectTableRowCountToEqualOtherTable, custom reconciliation via paired validation"}
]'::jsonb
WHERE blueprint_key = 'Reconciliation';

-- 5g. DQScorecardPublish -> GX Data Docs + Custom Scorecard
UPDATE blueprints SET params_schema = '[
  {"name":"publish_target","type":"enum","options":["data_docs","dashboard_table","api"],"default":"data_docs","description":"Where to publish the DQ scorecard"},
  {"name":"data_docs_site_config","type":"object","description":"GX Data Docs site configuration (S3 path, GCS bucket, etc.)"},
  {"name":"scorecard_dimensions","type":"string[]","description":"Quality dimensions to include: completeness, uniqueness, accuracy, timeliness, consistency"},
  {"name":"scoring_model","type":"enum","options":["weighted","pass_fail","tiered"],"default":"weighted","description":"How to compute the overall score"},
  {"name":"publish_to_catalog","type":"boolean","default":true,"description":"Whether to publish scorecard to data catalog"}
]'::jsonb
WHERE blueprint_key = 'DQScorecardPublish';
