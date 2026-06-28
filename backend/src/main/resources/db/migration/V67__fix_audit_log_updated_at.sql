-- V67: Add missing updated_at column to audit log tables (required by BaseEntity).

ALTER TABLE asof_advance_log ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE domain_advance_log ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
