-- V106: Phase 7 of the deployment productization plan — canonicalize
-- deployment_targets.target_type to the Phase 7 adapter set.
--
-- Canonical persisted target_type values are:
--   LOCAL_MATERIALIZATION     -- dev / proof-only target
--   GCP_COMPOSER_DATAPROC     -- production GCP target
--   DPC_AIRFLOW_OPENSHIFT_SPARK -- production DPC target
--
-- Legacy seeded values (KUBERNETES, AIRFLOW, DATABRICKS, EMR, DATAPROC)
-- existed before Phase 7's adapter contract. The DeployController
-- boundary normalizer continues to accept KUBERNETES as an alias for
-- LOCAL_MATERIALIZATION; this migration rewrites existing rows so
-- downstream lookups (DeploymentRunOrchestrator dispatch) can rely on
-- the canonical set.
--
-- Other legacy values (AIRFLOW, DATABRICKS, EMR, DATAPROC) are NOT
-- automatically remapped — they predate Phase 7 and require explicit
-- platform/operator review before being mapped to GCP_COMPOSER_DATAPROC
-- or DPC_AIRFLOW_OPENSHIFT_SPARK. We leave them in place and the new
-- check constraint blocks future inserts with those values.

-- ---------------------------------------------------------------------
-- 1. Migrate KUBERNETES rows -> LOCAL_MATERIALIZATION (the prior
--    default value for new dev targets).
-- ---------------------------------------------------------------------
UPDATE deployment_targets
SET target_type = 'LOCAL_MATERIALIZATION',
    updated_at  = NOW()
WHERE target_type = 'KUBERNETES';

-- ---------------------------------------------------------------------
-- 2. Add a CHECK constraint pinning the canonical set.
--
--    Use NOT VALID so this migration succeeds even when a deployment
--    has legacy non-KUBERNETES target types (AIRFLOW / DATABRICKS /
--    EMR / DATAPROC) that we intentionally do NOT auto-remap — those
--    require operator review before being mapped onto a canonical
--    Phase 7 adapter key.
--
--    Effect of NOT VALID: existing rows are not re-validated, so the
--    migration applies cleanly. Every subsequent INSERT / UPDATE is
--    constrained — so new rows can only land in the canonical set.
--    A future migration can VALIDATE CONSTRAINT once operators have
--    cleaned up any legacy rows.
-- ---------------------------------------------------------------------
ALTER TABLE deployment_targets
    DROP CONSTRAINT IF EXISTS chk_deployment_targets_target_type;

ALTER TABLE deployment_targets
    ADD CONSTRAINT chk_deployment_targets_target_type
    CHECK (target_type IN (
        'LOCAL_MATERIALIZATION',
        'GCP_COMPOSER_DATAPROC',
        'DPC_AIRFLOW_OPENSHIFT_SPARK'
    )) NOT VALID;

-- Refresh the column comment so the schema documents the canonical
-- form. Older comments may still list legacy values.
COMMENT ON COLUMN deployment_targets.target_type
    IS 'Canonical Phase 7 adapter key: LOCAL_MATERIALIZATION, GCP_COMPOSER_DATAPROC, DPC_AIRFLOW_OPENSHIFT_SPARK. Legacy KUBERNETES rows were rewritten to LOCAL_MATERIALIZATION; legacy AIRFLOW/DATABRICKS/EMR/DATAPROC rows require manual review.';
