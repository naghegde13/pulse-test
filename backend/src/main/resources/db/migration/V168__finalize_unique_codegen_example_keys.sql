-- V168: finalize unique codegen example-key ownership for integration regression guard.
--
-- Ensures every example_keys entry is owned by at most one blueprint.
-- This closes the remaining collisions reported by
-- CodegenExampleSharingRegressionIT:
--   - jdbc_snapshot_oracle (SnapshotIngestion + BulkBackfill)
--   - stg_cleaning_basic   (BronzeToSilverCleaning + DedupeAndMerge)

-- BulkBackfill should use its dedicated historical replay example.
UPDATE blueprints
SET codegen_hints = jsonb_set(
        codegen_hints,
        '{example_keys}',
        '["bulk_backfill_date_range"]'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE blueprint_key = 'BulkBackfill';

-- DedupeAndMerge should use its dedicated survivorship SQL example.
UPDATE blueprints
SET codegen_hints = jsonb_set(
        codegen_hints,
        '{example_keys}',
        '["stg_dedupe_merge"]'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE blueprint_key = 'DedupeAndMerge';
