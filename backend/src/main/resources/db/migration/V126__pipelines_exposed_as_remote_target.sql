-- ARCH-008 phase 2: target-side exposure flag. Service owns minting catalog rows.

ALTER TABLE pipelines
    ADD COLUMN IF NOT EXISTS exposed_as_remote_target BOOLEAN NOT NULL DEFAULT FALSE;
