-- ARCH-018 follow-up: plan-scoped draft reference aliasing.
--
-- Draft refs are audit labels that live only inside one plan.  The plan row
-- stores declared refs and the apply-time bindings from draft refs to real
-- aggregate ids; command_log.result_payload stores handler output separately
-- from command input payload so binding never depends on payload._result.

ALTER TABLE plans
    ADD COLUMN IF NOT EXISTS draft_ref_declarations JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE plans
    ADD COLUMN IF NOT EXISTS draft_ref_bindings JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE command_log
    ADD COLUMN IF NOT EXISTS result_payload JSONB;
