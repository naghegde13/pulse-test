-- V113: Blueprint Instance Field Authority (ARCH-010).
--
-- Establishes the canonical, single source of truth for per-instance storage
-- binding fields on sub_pipeline_instances. Historically the fields
-- (storage_backend, lake_layer, lake_format) could live EITHER as canonical
-- columns (V96) OR as overrides in the free-form params jsonb blob. That
-- ambiguity produced silent drift: agents writing into params would not
-- update the column the codegen/deploy path reads, and vice versa.
--
-- ARCH-010 collapses the authority: the canonical columns are the only
-- source of truth. params jsonb MUST NOT carry storage_backend / lake_layer
-- / lake_format keys after this migration. A cutover marker
-- (storage_authority_cutover) is introduced so a later migration can flip
-- to TRUE once zero unresolved conflicts remain and application code has
-- been hardened to reject writes that try to set these keys in params.
--
-- Backfill rules (single forward sweep):
--   1. For every sub_pipeline_instance whose params jsonb contains at
--      least one of {storage_backend, lake_layer, lake_format}:
--        a. If the canonical column would still be at its V96 baked
--           default (storage_backend='DPC', lake_layer IS NULL,
--           lake_format IS NULL) AND the params value is non-null and
--           non-empty AND the resulting (backend,layer,format) triple
--           is internally consistent with the storage matrix, COPY the
--           params value into the canonical column.
--        b. Then STRIP all three keys from params unconditionally.
--           (Even in conflict cases — leaving them around perpetuates
--           the ambiguity ARCH-010 is removing.)
--        c. If the triple is internally inconsistent (layer set but
--           format null, format set but layer null, gold-on-GCP without
--           bq_native, or unparseable enum values), the canonical column
--           is LEFT UNCHANGED and a row is inserted into
--           storage_authority_conflicts with reason='invalid_combination'
--           (or another specific reason). The instance is intentionally
--           preserved as-is so callers can review and fix it; the
--           cutover marker stays FALSE until conflicts.resolved=TRUE
--           for all of them.
--   2. Pipeline-level default_storage_backend gets a column-level
--      DEFAULT 'DPC' and NOT NULL — matching the V96 baked default on
--      instances so existing rows remain semantically unchanged.
--
-- Cutover marker:
--   storage_authority_cutover is a single-row sentinel table. id=1,
--   completed=FALSE on insert. A later migration (after application
--   code rejects params-side writes of the three keys and after every
--   row in storage_authority_conflicts has resolved=TRUE) will UPDATE
--   it to completed=TRUE, completed_at=NOW(). Application code can read
--   this row to decide whether to enforce strict authority at write
--   time.

-- --------------------------------------------------------------------------
-- 1. pipelines.default_storage_backend — NOT NULL DEFAULT 'DPC'.
--
-- The column was added (nullable, no default) by V96. ARCH-010 promotes
-- it to a required field. Existing NULL rows inherit 'DPC' (matching the
-- V96 baked instance default) so semantics do not change for any row.
-- The CHECK constraint is dropped-and-recreated to ensure NOT NULL is
-- the only enforced state going forward.
-- --------------------------------------------------------------------------
ALTER TABLE pipelines
    ADD COLUMN IF NOT EXISTS default_storage_backend VARCHAR(8);

UPDATE pipelines
   SET default_storage_backend = 'DPC'
 WHERE default_storage_backend IS NULL;

ALTER TABLE pipelines
    ALTER COLUMN default_storage_backend SET DEFAULT 'DPC';

ALTER TABLE pipelines
    ALTER COLUMN default_storage_backend SET NOT NULL;

ALTER TABLE pipelines
    DROP CONSTRAINT IF EXISTS chk_pipelines_default_storage_backend;

ALTER TABLE pipelines
    ADD CONSTRAINT chk_pipelines_default_storage_backend
        CHECK (default_storage_backend IN ('DPC','GCP'));

-- --------------------------------------------------------------------------
-- 2. storage_authority_conflicts — append-only audit of backfill rows
--    that could not be safely promoted to the canonical columns. Each
--    row identifies the offending instance, the params-side values that
--    triggered the conflict, and a human-readable reason/detail.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS storage_authority_conflicts (
    id                          VARCHAR(26)   PRIMARY KEY,
    instance_id                 VARCHAR(26)   NOT NULL
                                REFERENCES sub_pipeline_instances(id) ON DELETE CASCADE,
    pipeline_id                 VARCHAR(26)   NOT NULL,
    conflicting_storage_backend VARCHAR(32),
    conflicting_lake_layer      VARCHAR(32),
    conflicting_lake_format     VARCHAR(32),
    reason                      VARCHAR(64)   NOT NULL,
    detail                      VARCHAR(1000),
    resolved                    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_storage_authority_conflicts_pipeline
    ON storage_authority_conflicts(pipeline_id);
CREATE INDEX IF NOT EXISTS idx_storage_authority_conflicts_instance
    ON storage_authority_conflicts(instance_id);
CREATE INDEX IF NOT EXISTS idx_storage_authority_conflicts_resolved
    ON storage_authority_conflicts(resolved);

-- --------------------------------------------------------------------------
-- 3. Backfill: legacy params overrides -> canonical columns.
--
-- Step 3a: INSERT conflict rows for any instance whose params-side triple
-- is internally inconsistent. We do this BEFORE the UPDATE so we can use
-- the original params jsonb to derive the offending values.
--
-- "Conflict" predicates (any of):
--   * layer present (non-null, non-empty) but format absent/null/empty.
--   * format present but layer absent/null/empty.
--   * backend value not in ('DPC','GCP').
--   * layer  value not in ('bronze','silver','gold').
--   * format value not in ('delta','iceberg_external','iceberg_bq_managed',
--                          'bq_native','parquet').
--   * Gold-on-GCP with format != bq_native.
--
-- ULID note: real ULIDs are minted in application code; for SQL-only
-- backfill rows we synthesize a deterministic-ish placeholder ID prefixed
-- with 'C' so they are distinguishable in logs. Format: 'C' + 25-char
-- md5 slice of (random() || clock_timestamp()). Collision risk is
-- negligible at the volume of legacy conflicts expected (single digits).
-- --------------------------------------------------------------------------
INSERT INTO storage_authority_conflicts (
    id, instance_id, pipeline_id,
    conflicting_storage_backend, conflicting_lake_layer, conflicting_lake_format,
    reason, detail
)
SELECT
    'C' || substr(md5(random()::text || clock_timestamp()::text), 1, 25),
    spi.id,
    spi.pipeline_id,
    NULLIF(spi.params->>'storage_backend', ''),
    NULLIF(spi.params->>'lake_layer', ''),
    NULLIF(spi.params->>'lake_format', ''),
    'invalid_combination',
    'params override failed ARCH-010 promotion: backend='
        || COALESCE(NULLIF(spi.params->>'storage_backend',''),'<null>')
        || ', layer='
        || COALESCE(NULLIF(spi.params->>'lake_layer',''),'<null>')
        || ', format='
        || COALESCE(NULLIF(spi.params->>'lake_format',''),'<null>')
  FROM sub_pipeline_instances spi
 WHERE (spi.params ? 'storage_backend'
        OR spi.params ? 'lake_layer'
        OR spi.params ? 'lake_format')
   AND (
        -- Unparseable enum values.
        (NULLIF(spi.params->>'storage_backend','') IS NOT NULL
            AND spi.params->>'storage_backend' NOT IN ('DPC','GCP'))
        OR
        (NULLIF(spi.params->>'lake_layer','') IS NOT NULL
            AND spi.params->>'lake_layer' NOT IN ('bronze','silver','gold'))
        OR
        (NULLIF(spi.params->>'lake_format','') IS NOT NULL
            AND spi.params->>'lake_format'
                NOT IN ('delta','iceberg_external','iceberg_bq_managed',
                        'bq_native','parquet'))
        OR
        -- Layer set without format, or format set without layer.
        (NULLIF(spi.params->>'lake_layer','') IS NOT NULL
            AND NULLIF(spi.params->>'lake_format','') IS NULL)
        OR
        (NULLIF(spi.params->>'lake_format','') IS NOT NULL
            AND NULLIF(spi.params->>'lake_layer','') IS NULL)
        OR
        -- Gold-on-GCP must be bq_native (locked rule, V96-enforced on the
        -- canonical column; we mirror it here so we never PROMOTE a value
        -- that would then fail the CHECK constraint).
        (NULLIF(spi.params->>'storage_backend','') = 'GCP'
            AND NULLIF(spi.params->>'lake_layer','') = 'gold'
            AND NULLIF(spi.params->>'lake_format','') IS DISTINCT FROM 'bq_native')
   );

-- Step 3b: Promote VALID overrides into canonical columns. Only writes
-- when the canonical column is still at its V96 baked default AND the
-- params value is non-null/non-empty AND the resulting triple is
-- internally consistent. We re-derive the same triple as in the conflict
-- INSERT so the two are mutually exclusive: an instance is either fully
-- promoted (column updated) or flagged in storage_authority_conflicts,
-- never both.
UPDATE sub_pipeline_instances spi
   SET storage_backend = COALESCE(
            CASE
                WHEN spi.storage_backend = 'DPC'
                 AND NULLIF(spi.params->>'storage_backend','') IN ('DPC','GCP')
                THEN spi.params->>'storage_backend'
                ELSE spi.storage_backend
            END,
            spi.storage_backend
       ),
       lake_layer = CASE
            WHEN spi.lake_layer IS NULL
             AND NULLIF(spi.params->>'lake_layer','') IN ('bronze','silver','gold')
             AND NULLIF(spi.params->>'lake_format','')
                    IN ('delta','iceberg_external','iceberg_bq_managed',
                        'bq_native','parquet')
            THEN spi.params->>'lake_layer'
            ELSE spi.lake_layer
       END,
       lake_format = CASE
            WHEN spi.lake_format IS NULL
             AND NULLIF(spi.params->>'lake_layer','') IN ('bronze','silver','gold')
             AND NULLIF(spi.params->>'lake_format','')
                    IN ('delta','iceberg_external','iceberg_bq_managed',
                        'bq_native','parquet')
            THEN spi.params->>'lake_format'
            ELSE spi.lake_format
       END
 WHERE (spi.params ? 'storage_backend'
        OR spi.params ? 'lake_layer'
        OR spi.params ? 'lake_format')
   -- Same exclusion list as the conflict INSERT — promote ONLY when the
   -- params triple is internally consistent.
   AND NOT (
        (NULLIF(spi.params->>'storage_backend','') IS NOT NULL
            AND spi.params->>'storage_backend' NOT IN ('DPC','GCP'))
        OR
        (NULLIF(spi.params->>'lake_layer','') IS NOT NULL
            AND spi.params->>'lake_layer' NOT IN ('bronze','silver','gold'))
        OR
        (NULLIF(spi.params->>'lake_format','') IS NOT NULL
            AND spi.params->>'lake_format'
                NOT IN ('delta','iceberg_external','iceberg_bq_managed',
                        'bq_native','parquet'))
        OR
        (NULLIF(spi.params->>'lake_layer','') IS NOT NULL
            AND NULLIF(spi.params->>'lake_format','') IS NULL)
        OR
        (NULLIF(spi.params->>'lake_format','') IS NOT NULL
            AND NULLIF(spi.params->>'lake_layer','') IS NULL)
        OR
        (NULLIF(spi.params->>'storage_backend','') = 'GCP'
            AND NULLIF(spi.params->>'lake_layer','') = 'gold'
            AND NULLIF(spi.params->>'lake_format','') IS DISTINCT FROM 'bq_native')
   );

-- Step 3c: Strip the three keys from params on every instance that had
-- any of them, regardless of conflict status. params is no longer
-- authoritative for these fields; leaving them around perpetuates the
-- ambiguity ARCH-010 removes. Conflict rows preserved in
-- storage_authority_conflicts retain the offending values for triage.
UPDATE sub_pipeline_instances
   SET params = params - 'storage_backend' - 'lake_layer' - 'lake_format'
 WHERE params ? 'storage_backend'
    OR params ? 'lake_layer'
    OR params ? 'lake_format';

-- --------------------------------------------------------------------------
-- 4. storage_authority_cutover — single-row sentinel marker.
--
-- Flipped to completed=TRUE in a later migration once:
--   * Application code rejects writes that include
--     storage_backend/lake_layer/lake_format keys inside params.
--   * Every row in storage_authority_conflicts has resolved=TRUE.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS storage_authority_cutover (
    id           INT          PRIMARY KEY,
    completed    BOOLEAN      NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_storage_authority_cutover_singleton CHECK (id = 1)
);

INSERT INTO storage_authority_cutover (id, completed)
VALUES (1, FALSE)
ON CONFLICT (id) DO NOTHING;
