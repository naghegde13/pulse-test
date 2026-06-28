-- V101: Capture the user's "where does processing_datetime come from?" decision
--       on the dataset itself, rather than only as a chat-history acknowledgement.
--
-- BACKGROUND
-- ----------
-- V66 added `datasets.file_naming_metadata` JSONB which encodes time-dim segments
-- IN the filename (business_date, processing_datetime). Two states existed:
--   1. processing_datetime segment present in filename → metadata key populated
--   2. processing_datetime segment absent → metadata key absent
--
-- Missing third state: "the file has no processing_datetime segment, but the
-- file's arrival timestamp on object storage IS the canonical processing_datetime."
-- During #11 E2E, the agent legitimately asked the user this question per
-- WORKFLOW_PACKET Phase 2g, the user answered "use file arrival timestamp" — but
-- there was nowhere to persist that answer. It lived only in chat history.
-- Codegen continued to use Airflow's `{{ ts }}` (DAG-run trigger time), NOT the
-- file's arrival time, silently producing wrong audit data at runtime.
--
-- THIS MIGRATION
-- --------------
-- Adds a top-level enum column `processing_datetime_source` on `datasets` with
-- three values:
--   * `filename_segment`  — processing_datetime is encoded in the filename
--                           (per file_naming_metadata.time_dimensions.processing_datetime).
--   * `file_arrival_time` — use the file's last-modified timestamp on object
--                           storage as the processing_datetime. Codegen emits
--                           Spark's F.input_file_modification_time() per row.
--   * `airflow_run_time`  — fall back to the Airflow DAG-run timestamp ({{ ts }}).
--                           Default for backward compatibility with all existing
--                           datasets that don't have processing_datetime in the
--                           filename.

ALTER TABLE datasets ADD COLUMN IF NOT EXISTS processing_datetime_source VARCHAR(32)
    NOT NULL DEFAULT 'airflow_run_time';

ALTER TABLE datasets ADD CONSTRAINT chk_datasets_processing_datetime_source
    CHECK (processing_datetime_source IN ('filename_segment', 'file_arrival_time', 'airflow_run_time'));

COMMENT ON COLUMN datasets.processing_datetime_source IS
    'Source of the per-row processing_datetime audit value. filename_segment: parsed from the filename per file_naming_metadata. file_arrival_time: file last-modified timestamp on object storage (Spark F.input_file_modification_time). airflow_run_time: Airflow DAG-run logical timestamp (default).';

-- Backfill: any existing dataset whose file_naming_metadata.time_dimensions
-- has a processing_datetime key encoded in the filename is upgraded to
-- 'filename_segment'. Everything else remains 'airflow_run_time'.
UPDATE datasets
SET processing_datetime_source = 'filename_segment'
WHERE file_naming_metadata IS NOT NULL
  AND file_naming_metadata -> 'time_dimensions' ? 'processing_datetime';

-- Validation:
-- SELECT processing_datetime_source, count(*) FROM datasets GROUP BY 1;
-- Expected: airflow_run_time for most rows, filename_segment for any row whose
--           file_naming_metadata declares processing_datetime in time_dimensions.
