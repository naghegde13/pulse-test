-- PKT-0014: Add validation_kind to runtime_bindings.
-- Tracks whether a binding was validated via STUB (static/local check)
-- or a live probe (LIVE_GCP, LIVE_HDFS). Stub validation cannot
-- claim live-GCP readiness.
ALTER TABLE runtime_bindings
    ADD COLUMN IF NOT EXISTS validation_kind VARCHAR(32) DEFAULT 'STUB' NOT NULL;

COMMENT ON COLUMN runtime_bindings.validation_kind IS
    'Kind of validation last performed: STUB (static check), LIVE_GCP, LIVE_HDFS. Stub cannot claim live readiness.';
