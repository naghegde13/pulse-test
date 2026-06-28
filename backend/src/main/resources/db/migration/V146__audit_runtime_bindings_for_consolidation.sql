-- PKT-FINAL-5 / BUG-39: Pre-consolidation audit for runtime_bindings.
--
-- BUG-39 closes the question PKT-FINAL-2 deferred: runtime bindings are
-- deployment-global, not per-tenant. The active runtime persona governs the
-- entire PULSE deployment, so a (tenant_id, environment, binding_kind,
-- settings_role) tuple should collapse to a single global row.
--
-- This migration is INFORMATIONAL: it asserts that all per-tenant duplicates
-- for the same (environment, binding_kind, settings_role) group carry
-- identical storage roots (i.e. consolidation is safe). If they differ,
-- the assertion HALTS the migration with an explicit error so the operator
-- can manually reconcile before V147 drops the column.
--
-- Operator decision (Option A1): consolidate first, then drop tenant_id.

DO $$
DECLARE
    variant_count INTEGER;
BEGIN
    SELECT COUNT(*)
      INTO variant_count
      FROM (
            SELECT environment,
                   binding_kind,
                   settings_role,
                   COUNT(DISTINCT (
                       COALESCE(storage_root_files,''),
                       COALESCE(storage_root_lake,''),
                       COALESCE(storage_root_ops,'')
                   )) AS distinct_root_combos
              FROM runtime_bindings
             WHERE record_state = 'ACTIVE'
             GROUP BY environment, binding_kind, settings_role
            HAVING COUNT(DISTINCT (
                       COALESCE(storage_root_files,''),
                       COALESCE(storage_root_lake,''),
                       COALESCE(storage_root_ops,'')
                   )) > 1
      ) variant_groups;

    IF variant_count > 0 THEN
        RAISE EXCEPTION
          'BUG-39 audit failed: % per-(environment, binding_kind, settings_role) '
          'group(s) contain conflicting storage roots across tenants. '
          'Resolve manually before applying V147 (drop tenant_id). '
          'Query: SELECT environment, binding_kind, settings_role, '
          'array_agg(DISTINCT (storage_root_files, storage_root_lake, storage_root_ops)) '
          'FROM runtime_bindings WHERE record_state=''ACTIVE'' '
          'GROUP BY environment, binding_kind, settings_role '
          'HAVING COUNT(DISTINCT (storage_root_files, storage_root_lake, storage_root_ops)) > 1;',
          variant_count;
    END IF;

    RAISE NOTICE 'BUG-39 audit passed: all per-tenant rows agree on storage roots '
                 'within their (environment, binding_kind, settings_role) group.';
END $$;
