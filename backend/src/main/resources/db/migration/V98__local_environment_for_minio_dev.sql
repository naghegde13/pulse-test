-- V98: Add 'local' as a 5th environment value to the storage convention.
--
-- Background: V96 created the env enum 'dev|integration|uat|prod' and
-- pre-marked 'dev' rows as validated for laptop-MinIO development. The
-- user surfaced an ambiguity: 'dev' is overloaded to mean both
-- "laptop-MinIO" AND "shared deployed dev (real GCP/DPC project the
-- platform team provisions)". V98 disambiguates by introducing
-- 'local' as a distinct environment value:
--
--   local        — laptop docker-compose, MinIO-backed, pre-validated
--   dev          — first DEPLOYED env, real cloud project (now pending
--                  until platform team provisions, just like int/uat/prod)
--   integration  — pre-prod, real cloud project, pending until validated
--   uat          — pre-prod, real cloud project, pending until validated
--   prod         — production, real cloud project, pending until validated
--
-- Effect on the deploy gate: 'local' rows are validated by seed so
-- local design + smoke runs work out of the box. 'dev' is now treated
-- the same as integration/uat/prod — must be validated before deploy.
-- This makes the gate uniform across all REAL envs and removes the
-- "is this environment for a real cloud project or my laptop?" guess.

-- ---------------------------------------------------------------------
-- 1. Update the environment CHECK constraint to include 'local'.
-- ---------------------------------------------------------------------
ALTER TABLE storage_backends
    DROP CONSTRAINT chk_storage_backends_environment;

ALTER TABLE storage_backends
    ADD CONSTRAINT chk_storage_backends_environment
        CHECK (environment IN ('local','dev','integration','uat','prod'));

-- ---------------------------------------------------------------------
-- 2. Re-mark existing dev rows as pending. They represent deployed-dev
--    (a real cloud project) which has not been validated by the
--    platform team yet. Local-laptop development uses the new 'local'
--    rows seeded below.
-- ---------------------------------------------------------------------
UPDATE storage_backends
SET provisioning_status = 'pending',
    provisioning_validated_at = NULL,
    provisioning_error = NULL,
    updated_at = NOW()
WHERE environment = 'dev'
  AND provisioning_status = 'validated';

-- ---------------------------------------------------------------------
-- 3. Seed 'local' rows for each existing tenant — both backends,
--    validated, pointing at MinIO buckets per the local naming
--    convention. The bucket bootstrap service in docker-compose creates
--    these buckets on local startup.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t     RECORD;
    tcode VARCHAR(4);
BEGIN
    FOR t IN SELECT id, slug FROM tenants WHERE status = 'active' LOOP
        tcode := UPPER(LEFT(REPLACE(t.slug, '-', ''), 4));
        IF length(tcode) < 4 THEN tcode := RPAD(tcode, 4, '0'); END IF;

        -- GCP-emulated row (MinIO speaks S3, not GS — but the codegen
        -- substitutes gs:// URIs that Spark+MinIO can also resolve when
        -- gs.endpoint points at MinIO).
        INSERT INTO storage_backends (
            id, tenant_id, environment, backend,
            storage_root_files, storage_root_lake,
            gcp_project,
            provisioning_status, provisioning_validated_at
        ) VALUES (
            RPAD('01JSTRG_' || tcode || '_LOC_GCP', 26, '_'),
            t.id, 'local', 'GCP',
            'pulse-' || t.slug || '-local-files',
            'pulse-' || t.slug || '-local-lake',
            'local-emulated-gcp',
            'validated', NOW()
        )
        ON CONFLICT (tenant_id, environment, backend) DO NOTHING;

        -- DPC row (s3a → MinIO directly).
        INSERT INTO storage_backends (
            id, tenant_id, environment, backend,
            storage_root_files, storage_root_lake,
            dpc_scheme, dpc_cluster,
            provisioning_status, provisioning_validated_at
        ) VALUES (
            RPAD('01JSTRG_' || tcode || '_LOC_DPC', 26, '_'),
            t.id, 'local', 'DPC',
            'pulse-dpc-' || t.slug || '-local-files',
            'pulse-dpc-' || t.slug || '-local-lake',
            's3a',
            'local-minio',
            'validated', NOW()
        )
        ON CONFLICT (tenant_id, environment, backend) DO NOTHING;
    END LOOP;
END
$$;
