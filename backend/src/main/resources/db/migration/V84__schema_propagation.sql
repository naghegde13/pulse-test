-- V84: Schema propagation — per-port schema persistence, conflict tracking, instance status.
--
-- Owned by Agent E (redesign-plan-v2, WP6). Extends the pre-existing
-- sub_pipeline_instances.output_schema (V33) from a single node-level blob to a
-- per-port authoritative model, adds a schema_status column to track derivation
-- state, and introduces a conflicts table for user-visible mismatches.

-- =============================================================================
-- 8a. sub_pipeline_instances.schema_status
-- =============================================================================
ALTER TABLE sub_pipeline_instances
    ADD COLUMN IF NOT EXISTS schema_status VARCHAR(30) NOT NULL DEFAULT 'unknown';

ALTER TABLE sub_pipeline_instances
    DROP CONSTRAINT IF EXISTS ck_sub_pipeline_instances_schema_status;
ALTER TABLE sub_pipeline_instances
    ADD CONSTRAINT ck_sub_pipeline_instances_schema_status
    CHECK (schema_status IN ('unknown','clean','dirty','conflict','pending'));

-- =============================================================================
-- 8b. instance_port_schemas
-- =============================================================================
CREATE TABLE IF NOT EXISTS instance_port_schemas (
    id            VARCHAR(26)  PRIMARY KEY,
    instance_id   VARCHAR(26)  NOT NULL REFERENCES sub_pipeline_instances(id) ON DELETE CASCADE,
    port_name     VARCHAR(255) NOT NULL,
    direction     VARCHAR(10)  NOT NULL,
    schema_json   JSONB        NOT NULL DEFAULT '{"columns":[]}',
    schema_hash   VARCHAR(64)  NOT NULL DEFAULT '',
    source        VARCHAR(30)  NOT NULL DEFAULT 'propagated',
    override      JSONB,
    notes         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ips_instance_port_dir UNIQUE (instance_id, port_name, direction),
    CONSTRAINT ck_ips_direction CHECK (direction IN ('input','output')),
    CONSTRAINT ck_ips_source CHECK (source IN ('propagated','inferred','manual','backfill','override'))
);

CREATE INDEX IF NOT EXISTS idx_ips_instance ON instance_port_schemas(instance_id);

-- =============================================================================
-- 8c. schema_conflicts
-- =============================================================================
CREATE TABLE IF NOT EXISTS schema_conflicts (
    id                 VARCHAR(26)  PRIMARY KEY,
    version_id         VARCHAR(26)  NOT NULL REFERENCES pipeline_versions(id) ON DELETE CASCADE,
    instance_id        VARCHAR(26)  NOT NULL REFERENCES sub_pipeline_instances(id) ON DELETE CASCADE,
    port_name          VARCHAR(255),
    conflict_type      VARCHAR(50)  NOT NULL,
    details            JSONB        NOT NULL DEFAULT '{}',
    resolution_status  VARCHAR(30)  NOT NULL DEFAULT 'open',
    resolution_type    VARCHAR(50),
    resolution_notes   TEXT,
    resolved_by        VARCHAR(26),
    resolved_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_sc_conflict_type CHECK (conflict_type IN
        ('TYPE_MISMATCH','MISSING_COLUMN','INCOMPATIBLE_SCHEMA','CYCLE_DETECTED','MISSING_UPSTREAM')),
    CONSTRAINT ck_sc_resolution_status CHECK (resolution_status IN ('open','resolved','overridden','ignored')),
    CONSTRAINT ck_sc_resolution_type CHECK (
        resolution_type IS NULL OR resolution_type IN ('accept_upstream','override','flag_for_review'))
);

CREATE INDEX IF NOT EXISTS idx_sc_version_status ON schema_conflicts(version_id, resolution_status);
CREATE INDEX IF NOT EXISTS idx_sc_instance ON schema_conflicts(instance_id);

-- =============================================================================
-- 8d. Backfill instance_port_schemas from the existing sub_pipeline_instances.output_schema blob
-- =============================================================================
-- Use a deterministic id derived from the instance id + literal suffix so the
-- insert is idempotent if the migration is re-run after a partial rollback.
INSERT INTO instance_port_schemas (
    id, instance_id, port_name, direction, schema_json, schema_hash, source, created_at, updated_at
)
SELECT
    ('01J' || SUBSTRING(spi.id FROM 4 FOR 20) || 'BF0'),
    spi.id,
    'output',
    'output',
    spi.output_schema,
    '',
    'backfill',
    now(),
    now()
FROM sub_pipeline_instances spi
WHERE spi.output_schema IS NOT NULL
  AND spi.output_schema::text <> '{}'
  AND spi.output_schema::text <> 'null'
ON CONFLICT (instance_id, port_name, direction) DO NOTHING;

-- Schema status backfill: existing rows with data become 'clean', others stay 'unknown'.
UPDATE sub_pipeline_instances
SET schema_status = 'clean'
WHERE schema_status = 'unknown'
  AND output_schema IS NOT NULL
  AND output_schema::text <> '{}'
  AND output_schema::text <> 'null';
