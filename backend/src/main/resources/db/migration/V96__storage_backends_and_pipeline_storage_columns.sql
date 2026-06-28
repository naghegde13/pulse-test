-- V96: Storage backends data model — first migration of #30 (P1).
--
-- Introduces a tenant-scoped storage_backends table (one row per
-- (tenant, env, backend) combination), plus storage-binding columns
-- on pipelines and sub_pipeline_instances with the gold-on-GCP
-- constraint enforced at the database layer.
--
-- Distinct from connector_instances: connector_instances are
-- SoR-scoped technical endpoints (a Postgres JDBC connector for a
-- specific Source SoR, a Kafka cluster connector for a Target SoR).
-- storage_backends are tenant-scoped pipeline-WORKING-STORAGE locations
-- — where the bronze/silver/gold tables and the file-flow lifecycle
-- folders physically live. Sinks (V95) still use connector_instances;
-- ingestion/transform/modeling now use storage_backends.
--
-- Bucket naming convention: per (tenant, env, backend), with two
-- buckets per row (files + lake). Domain becomes a path prefix INSIDE
-- the bucket. Path-prefix ACLs handle per-domain access boundaries.
--
-- Provisioning lifecycle (per the locked spec): pending → validated
-- (after a probe confirms the project/cluster exists and PULSE has
-- access). Local-dev seed rows are pre-marked validated for the dev
-- env only; integration/uat/prod start pending and require the
-- platform team to provision the target project before deploy.

-- ---------------------------------------------------------------------
-- 1. storage_backends — the new table.
-- ---------------------------------------------------------------------
CREATE TABLE storage_backends (
    id                          VARCHAR(26)  PRIMARY KEY,
    tenant_id                   VARCHAR(26)  NOT NULL
                                REFERENCES tenants(id) ON DELETE CASCADE,
    environment                 VARCHAR(16)  NOT NULL,
    backend                     VARCHAR(16)  NOT NULL,
    storage_root_files          VARCHAR(255) NOT NULL,
    storage_root_lake           VARCHAR(255) NOT NULL,
    -- GCP-only.
    gcp_project                 VARCHAR(64),
    -- DPC-only.
    dpc_scheme                  VARCHAR(16),
    dpc_cluster                 VARCHAR(128),
    -- Provisioning lifecycle.
    provisioning_status         VARCHAR(32)  NOT NULL DEFAULT 'pending',
    provisioning_validated_at   TIMESTAMPTZ,
    provisioning_error          TEXT,
    -- Disable toggle for tenants that opt out of one backend entirely.
    disabled                    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    UNIQUE (tenant_id, environment, backend),

    CONSTRAINT chk_storage_backends_environment
        CHECK (environment IN ('dev','integration','uat','prod')),
    CONSTRAINT chk_storage_backends_backend
        CHECK (backend IN ('DPC','GCP')),
    CONSTRAINT chk_storage_backends_provisioning_status
        CHECK (provisioning_status IN ('pending','validated','failed','disabled')),
    -- Backend-specific column population: GCP rows MUST have gcp_project
    -- and MUST NOT have DPC fields; vice versa for DPC.
    CONSTRAINT chk_storage_backends_backend_fields
        CHECK (
            (backend = 'GCP'
                AND gcp_project IS NOT NULL
                AND dpc_scheme IS NULL
                AND dpc_cluster IS NULL)
            OR
            (backend = 'DPC'
                AND gcp_project IS NULL
                AND dpc_scheme IN ('s3a','hdfs')
                AND dpc_cluster IS NOT NULL)
        )
);

CREATE INDEX idx_storage_backends_tenant ON storage_backends(tenant_id);
CREATE INDEX idx_storage_backends_lookup
    ON storage_backends(tenant_id, environment, backend);

-- ---------------------------------------------------------------------
-- 2. pipelines — pipeline-level default storage backend.
-- ---------------------------------------------------------------------
ALTER TABLE pipelines
    ADD COLUMN default_storage_backend VARCHAR(16);

ALTER TABLE pipelines
    ADD CONSTRAINT chk_pipelines_default_storage_backend
        CHECK (default_storage_backend IS NULL
               OR default_storage_backend IN ('DPC','GCP'));

-- ---------------------------------------------------------------------
-- 3. sub_pipeline_instances — per-instance backend + lake config.
--
-- storage_backend defaults to 'DPC' on existing rows so the migration
-- is non-destructive; the configure-transform wizard will require an
-- explicit choice on new instances.
--
-- The gold-on-GCP constraint is locked at the database layer per
-- #30 spec section 5.
-- ---------------------------------------------------------------------
ALTER TABLE sub_pipeline_instances
    ADD COLUMN storage_backend VARCHAR(16) NOT NULL DEFAULT 'DPC',
    ADD COLUMN lake_layer      VARCHAR(16),
    ADD COLUMN lake_format     VARCHAR(32);

ALTER TABLE sub_pipeline_instances
    ADD CONSTRAINT chk_sub_pipeline_instances_storage_backend
        CHECK (storage_backend IN ('DPC','GCP'));

ALTER TABLE sub_pipeline_instances
    ADD CONSTRAINT chk_sub_pipeline_instances_lake_layer
        CHECK (lake_layer IS NULL OR lake_layer IN ('bronze','silver','gold'));

ALTER TABLE sub_pipeline_instances
    ADD CONSTRAINT chk_sub_pipeline_instances_lake_format
        CHECK (lake_format IS NULL
               OR lake_format IN ('delta','iceberg_external',
                                  'iceberg_bq_managed','bq_native','parquet'));

-- Gold-on-GCP rule (LOCKED): when storage_backend=GCP and lake_layer=gold,
-- lake_format MUST be bq_native. Other backend × layer combinations are
-- enforced at the application layer by SubPipelineInstanceService since
-- they depend on the legal-format matrix which is more nuanced than a
-- single CHECK can express cleanly.
ALTER TABLE sub_pipeline_instances
    ADD CONSTRAINT chk_sub_pipeline_instances_gold_gcp_must_be_bq_native
        CHECK (NOT (storage_backend = 'GCP'
                    AND lake_layer = 'gold'
                    AND lake_format <> 'bq_native'));

-- ---------------------------------------------------------------------
-- 4. Seed: 8 storage_backends rows per existing tenant.
--
-- Per the spec: dev environment is pre-marked 'validated' so local-dev
-- (MinIO-backed) works out of the box. integration/uat/prod start
-- 'pending' and require the platform team to provision the target
-- project before deploy is allowed.
--
-- IDs follow the connector_instances seed convention (01JCI0...) →
-- here 01JSTRG_{TENANT_CODE}_{ENV_CODE}_{BACKEND}_ padded to 26 chars.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t        RECORD;
    tcode    VARCHAR(4);
    e        VARCHAR(16);
    ecode    VARCHAR(3);
    is_dev   BOOLEAN;
BEGIN
    FOR t IN SELECT id, slug FROM tenants WHERE status = 'active' LOOP
        -- Compress slug to a 4-char tenant code for readable seed IDs.
        tcode := UPPER(LEFT(REPLACE(t.slug, '-', ''), 4));
        IF length(tcode) < 4 THEN
            tcode := RPAD(tcode, 4, '0');
        END IF;

        FOR e IN SELECT unnest(ARRAY['dev','integration','uat','prod']::VARCHAR[]) LOOP
            ecode := UPPER(LEFT(e, 3));
            IF e = 'integration' THEN ecode := 'INT'; END IF;
            IF e = 'prod'        THEN ecode := 'PRD'; END IF;
            is_dev := (e = 'dev');

            -- GCP row.
            INSERT INTO storage_backends (
                id, tenant_id, environment, backend,
                storage_root_files, storage_root_lake,
                gcp_project,
                provisioning_status, provisioning_validated_at
            )
            VALUES (
                RPAD('01JSTRG_' || tcode || '_' || ecode || '_GCP', 26, '_'),
                t.id, e, 'GCP',
                'pulse-' || t.slug || '-' || e || '-files',
                'pulse-' || t.slug || '-' || e || '-lake',
                'pulse-' || t.slug || '-' || e,
                CASE WHEN is_dev THEN 'validated' ELSE 'pending' END,
                CASE WHEN is_dev THEN NOW()       ELSE NULL      END
            );

            -- DPC row (s3a as default scheme; can be edited per-tenant).
            INSERT INTO storage_backends (
                id, tenant_id, environment, backend,
                storage_root_files, storage_root_lake,
                dpc_scheme, dpc_cluster,
                provisioning_status, provisioning_validated_at
            )
            VALUES (
                RPAD('01JSTRG_' || tcode || '_' || ecode || '_DPC', 26, '_'),
                t.id, e, 'DPC',
                'pulse-dpc-' || t.slug || '-' || e || '-files',
                'pulse-dpc-' || t.slug || '-' || e || '-lake',
                's3a',
                'pulse-dpc-' || t.slug || '-' || e,
                CASE WHEN is_dev THEN 'validated' ELSE 'pending' END,
                CASE WHEN is_dev THEN NOW()       ELSE NULL      END
            );
        END LOOP;
    END LOOP;
END
$$;
