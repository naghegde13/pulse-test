-- PKT-FINAL-5 / BUG-39: Consolidate runtime_bindings to deployment-global.
--
-- BUG-39 closes PKT-FINAL-2's deferred question: a PULSE deployment is
-- mono-persona (pulse.runtime.active-persona is global), so runtime bindings
-- cannot meaningfully vary per-tenant. This migration:
--
--   1. Deletes duplicate per-tenant rows, keeping one per
--      (environment, binding_kind, settings_role) group (MIN(id) wins).
--   2. Drops the foreign-key constraint to tenants(id).
--   3. Drops the tenant_id column entirely (Option A1 from the packet).
--   4. Drops the now-stale per-tenant index idx_rb_tenant_env.
--   5. Adds a UNIQUE index enforcing exactly one ACTIVE PRIMARY binding per
--      (environment, binding_kind, settings_role) for the entire deployment.
--
-- V146 ran first and confirmed every per-group duplicate carries identical
-- storage roots, so the row consolidation here is non-destructive.

-- Step 1: Drop duplicates per (environment, binding_kind, settings_role) group.
-- The row with the lowest id within a group is kept; the rest are removed.
DELETE FROM runtime_bindings rb
 WHERE rb.id NOT IN (
    SELECT MIN(id)
      FROM runtime_bindings
     GROUP BY environment, binding_kind, settings_role
 );

-- Step 2: Drop the FK to tenants(id) before dropping the column.
DO $$
DECLARE
    fk_name TEXT;
BEGIN
    SELECT tc.constraint_name
      INTO fk_name
      FROM information_schema.table_constraints tc
      JOIN information_schema.key_column_usage kcu
        ON tc.constraint_name = kcu.constraint_name
       AND tc.table_schema = kcu.table_schema
     WHERE tc.table_name = 'runtime_bindings'
       AND tc.constraint_type = 'FOREIGN KEY'
       AND kcu.column_name = 'tenant_id'
     LIMIT 1;
    IF fk_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE runtime_bindings DROP CONSTRAINT ' || quote_ident(fk_name);
    END IF;
END $$;

-- Step 3: Drop the per-tenant index.
DROP INDEX IF EXISTS idx_rb_tenant_env;

-- Step 4: Drop the column.
ALTER TABLE runtime_bindings DROP COLUMN tenant_id;

-- Step 5: Enforce the global uniqueness invariant.
-- Only one ACTIVE PRIMARY binding may exist per (environment, binding_kind,
-- settings_role) for the entire deployment. DIAGNOSTIC rows are excluded
-- (they're snapshots/troubleshooting artifacts and may co-exist).
CREATE UNIQUE INDEX IF NOT EXISTS uq_rb_global
    ON runtime_bindings (environment, binding_kind, settings_role)
 WHERE record_state = 'ACTIVE' AND settings_role = 'PRIMARY';
