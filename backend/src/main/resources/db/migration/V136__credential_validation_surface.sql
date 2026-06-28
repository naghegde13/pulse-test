-- PKT-0016: Credential Validation Surface
-- Adds validation metadata columns and expands credential status CHECK constraint
-- to support BLOCKED and FAILED states from the validation API.

ALTER TABLE credential_profiles
    ADD COLUMN IF NOT EXISTS last_validated_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS validation_category VARCHAR(100),
    ADD COLUMN IF NOT EXISTS validation_reason VARCHAR(1000);

-- Drop and recreate the status CHECK constraint to include new values.
-- The original constraint was created inline; PostgreSQL names it automatically.
-- We use a named constraint for clarity going forward.
DO $$
BEGIN
    -- Drop existing check constraint on status column if it exists.
    -- PostgreSQL auto-generates constraint names; search for any check on this column.
    PERFORM 1 FROM information_schema.check_constraints
        WHERE constraint_schema = 'public'
          AND constraint_name IN (
              SELECT constraint_name FROM information_schema.constraint_column_usage
              WHERE table_name = 'credential_profiles' AND column_name = 'status'
          );
    IF FOUND THEN
        EXECUTE (
            SELECT 'ALTER TABLE credential_profiles DROP CONSTRAINT ' || constraint_name
            FROM information_schema.constraint_column_usage
            WHERE table_name = 'credential_profiles' AND column_name = 'status'
            LIMIT 1
        );
    END IF;
END $$;

ALTER TABLE credential_profiles
    ADD CONSTRAINT chk_credential_status
    CHECK (status IN ('UNTESTED', 'VALID', 'INVALID', 'EXPIRED', 'SKIPPED', 'BLOCKED', 'FAILED'));
