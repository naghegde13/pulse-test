-- V6: Collapse DRAFT+CONFIGURED+GENERATED into ENGINEERING stage.
-- Replace SemVer 'version' column with integer 'revision' + 'commit_hash' placeholder.

-- 1. Rename stages
UPDATE pipeline_versions SET lifecycle_stage = 'ENGINEERING'
WHERE lifecycle_stage IN ('DRAFT', 'CONFIGURED', 'GENERATED');

-- 2. Add revision + commit_hash columns
ALTER TABLE pipeline_versions ADD COLUMN revision INTEGER;
ALTER TABLE pipeline_versions ADD COLUMN commit_hash VARCHAR(40);

-- 3. Backfill revision numbers per pipeline (ordered by created_at)
WITH numbered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY pipeline_id ORDER BY created_at) AS rn
    FROM pipeline_versions
)
UPDATE pipeline_versions SET revision = numbered.rn
FROM numbered WHERE pipeline_versions.id = numbered.id;

ALTER TABLE pipeline_versions ALTER COLUMN revision SET NOT NULL;

-- 4. Drop old version column and unique constraint
ALTER TABLE pipeline_versions DROP CONSTRAINT IF EXISTS pipeline_versions_pipeline_id_version_key;
ALTER TABLE pipeline_versions DROP COLUMN IF EXISTS version;

-- 5. Add new unique constraint on pipeline_id + revision
ALTER TABLE pipeline_versions ADD CONSTRAINT uq_pv_pipeline_revision UNIQUE (pipeline_id, revision);
