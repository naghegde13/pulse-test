-- V9: Blueprint catalog cleanup per PULSE vision audit.
-- Drop 8 blueprints that don't belong as composable patterns.
-- Merge 7 blueprints' capabilities into parent blueprints as params.
-- Reclassify 4 orchestration blueprints as pipeline-level config (not data-flow steps).

-- =====================================================
-- 1. DROP blueprints that are not composable data-flow patterns
-- =====================================================

-- Fan-in is a DAG topology handled by port wiring, not a blueprint
DELETE FROM blueprints WHERE blueprint_key = 'MultiSourceFanIn';

-- Infrastructure concern, not a pipeline step
DELETE FROM blueprints WHERE blueprint_key = 'CrossRegionReplicationIngest';

-- These are PULSE platform features, already implemented as lifecycle stages
DELETE FROM blueprints WHERE blueprint_key IN (
    'CIValidate', 'GenerateArtifacts', 'DeployToEnv', 'PromoteAcrossEnvs'
);

-- Don't translate to data pipelines (already deferred)
DELETE FROM blueprints WHERE blueprint_key IN ('BlueGreenDeployment', 'CanaryReleasePattern');

-- =====================================================
-- 2. MERGE: absorb capabilities into parent blueprints, then delete
-- =====================================================

-- SchemaEvolutionAwareIngest -> add evolution_policy param to all ingestion blueprints
UPDATE blueprints SET params_schema = params_schema || '[{"name":"evolution_policy","type":"enum","options":["add_columns","strict","log_and_skip"],"default":"add_columns","description":"How to handle source schema changes"}]'::jsonb
WHERE category = 'INGESTION' AND blueprint_key NOT IN ('SchemaEvolutionAwareIngest', 'EncryptedSourceIngest');
DELETE FROM blueprints WHERE blueprint_key = 'SchemaEvolutionAwareIngest';

-- EncryptedSourceIngest -> add encryption params to FileIngestion, ApiIngestion, CDCIngestion
UPDATE blueprints SET params_schema = params_schema || '[{"name":"encryption_type","type":"enum","options":["none","pgp","aes256","tls","kms"],"default":"none","description":"Source encryption type (none if unencrypted)"},{"name":"key_ref","type":"string","description":"Reference to decryption key in secrets vault (required if encrypted)"}]'::jsonb
WHERE blueprint_key IN ('FileIngestion', 'ApiIngestion', 'CDCIngestion');
DELETE FROM blueprints WHERE blueprint_key = 'EncryptedSourceIngest';

-- StandardizeDimensions -> merge into ConformanceToEnterpriseModel
UPDATE blueprints SET params_schema = params_schema || '[{"name":"dimension_lookups","type":"object[]","description":"Optional dimension standardization lookup tables to apply during conformance"}]'::jsonb
WHERE blueprint_key = 'ConformanceToEnterpriseModel';
UPDATE blueprints SET description = 'Reshapes your data to fit your company official data model -- the single agreed-upon way data should be structured. Can also standardize dimension values (regions, products, cost centers) using lookup tables so everything is consistent across reports.'
WHERE blueprint_key = 'ConformanceToEnterpriseModel';
DELETE FROM blueprints WHERE blueprint_key = 'StandardizeDimensions';

-- LateArrivingDataHandler -> merge into IncrementalMerge and FactBuild
UPDATE blueprints SET params_schema = params_schema || '[{"name":"late_data_policy","type":"enum","options":["recompute","flag_only","quarantine","ignore"],"default":"recompute","description":"How to handle records arriving after their expected time window"},{"name":"late_threshold_hours","type":"integer","default":72,"description":"Hours after which data is considered late-arriving"}]'::jsonb
WHERE blueprint_key IN ('IncrementalMerge', 'FactBuild');
DELETE FROM blueprints WHERE blueprint_key = 'LateArrivingDataHandler';

-- QuarantineBadRecords -> already handled by DQValidator (quarantine_output port + failure_policy)
DELETE FROM blueprints WHERE blueprint_key = 'QuarantineBadRecords';

-- VolumeSpikeDetection -> merge into AnomalyDetection
UPDATE blueprints SET params_schema = params_schema || '[{"name":"volume_monitoring","type":"object","description":"Optional volume spike/drop detection: {baseline_window_days, spike_threshold_percent, drop_threshold_percent}"}]'::jsonb
WHERE blueprint_key = 'AnomalyDetection';
UPDATE blueprints SET description = 'Uses statistical methods to spot unusual patterns in your data -- a sudden spike in transactions, an unexpected drop in records, or values way outside normal ranges. Can also monitor data volumes to catch unexpected spikes or drops that signal upstream problems.'
WHERE blueprint_key = 'AnomalyDetection';
DELETE FROM blueprints WHERE blueprint_key = 'VolumeSpikeDetection';

-- DuplicateThresholdMonitor -> merge into DQValidator as a rule type
UPDATE blueprints SET params_schema = (
    SELECT jsonb_agg(
        CASE WHEN elem->>'name' = 'rules'
        THEN elem || '{"description":"List of validation rules. Supports built-in types: null_check, range_check, format_check, duplicate_threshold (monitors duplicate rate against a threshold and alerts when exceeded)"}'::jsonb
        ELSE elem END
    )
    FROM jsonb_array_elements(params_schema) elem
)
WHERE blueprint_key = 'DQValidator';
DELETE FROM blueprints WHERE blueprint_key = 'DuplicateThresholdMonitor';

-- =====================================================
-- 3. RECLASSIFY: mark orchestration blueprints as pipeline-level config
-- =====================================================

ALTER TABLE blueprints ADD COLUMN pipeline_config BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE blueprints SET pipeline_config = TRUE
WHERE blueprint_key IN ('ScheduleAndTriggers', 'BackfillAndReplay', 'RollbackOnFailure', 'CostMonitoringHook');
