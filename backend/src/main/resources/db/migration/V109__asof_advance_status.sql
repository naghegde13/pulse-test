ALTER TABLE asof_advance_log
    ADD COLUMN IF NOT EXISTS requested_asof TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS advance_status VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED';

ALTER TABLE asof_advance_log
    DROP CONSTRAINT IF EXISTS ck_asof_advance_log_status;

ALTER TABLE asof_advance_log
    ADD CONSTRAINT ck_asof_advance_log_status
        CHECK (advance_status IN ('ACCEPTED', 'REJECTED'));

COMMENT ON COLUMN asof_advance_log.requested_asof IS
    'Requested target as-of for accepted or rejected control-plane advance attempts.';

COMMENT ON COLUMN asof_advance_log.advance_status IS
    'ACCEPTED when state advanced; REJECTED when validation failed without mutating dataset state.';
