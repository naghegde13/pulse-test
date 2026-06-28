-- Runtime projection JSON payload columns must be JSONB for package/projection
-- evidence and deploy preflight inspection. This migration replaces the prior
-- broker-edge runtime migration after the architecture correction removed
-- Pulse-mediated runtime edge tables.

ALTER TABLE runtime_projections
    ALTER COLUMN readiness_blockers TYPE JSONB USING COALESCE(NULLIF(readiness_blockers, ''), '[]')::jsonb,
    ALTER COLUMN resolved_storage_roots TYPE JSONB USING COALESCE(NULLIF(resolved_storage_roots, ''), '{}')::jsonb,
    ALTER COLUMN resolved_catalogs TYPE JSONB USING COALESCE(NULLIF(resolved_catalogs, ''), '{}')::jsonb,
    ALTER COLUMN resolved_entrypoints TYPE JSONB USING COALESCE(NULLIF(resolved_entrypoints, ''), '{}')::jsonb,
    ALTER COLUMN adapter_plan TYPE JSONB USING COALESCE(NULLIF(adapter_plan, ''), '{}')::jsonb,
    ALTER COLUMN orchestration_block TYPE JSONB USING COALESCE(NULLIF(orchestration_block, ''), '{}')::jsonb;
