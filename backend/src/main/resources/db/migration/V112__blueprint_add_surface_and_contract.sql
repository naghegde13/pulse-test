-- V112: Active blueprint contract (ARCH-011 / ARCH-012 / ARCH-014).
--
-- Adds the canonical `add_surface` column that governs whether a blueprint may
-- be instantiated in a composition, configured as version-level orchestration
-- policy, or is read-only / non-addable.
--
-- Surfaces:
--   * composition           - addable as a SubPipelineInstance node.
--   * orchestration_policy  - editable only through PipelineService.updateOrchestration.
--   * none                  - never addable through generic mutation paths
--                             (deprecated, deferred, or internal-only rows).
--
-- Also performs the orchestration persistence split (ARCH-012):
--   * AdvanceTimeDimension is reclassified from orchestration_policy to
--     orchestration_action and joins the composition surface.
--   * Derive is deferred (add_surface=none) until first-class codegen/tests
--     land (ARCH-011 active blueprint contract).
--   * Any persisted GenericRouter instance whose params still use the legacy
--     `route_predicates` key is migrated to canonical `routes`.

-- --------------------------------------------------------------------------
-- 1. Add column with a safe default; backfill in deterministic steps below.
-- --------------------------------------------------------------------------
ALTER TABLE blueprints
    ADD COLUMN add_surface VARCHAR(32) NOT NULL DEFAULT 'composition';

-- --------------------------------------------------------------------------
-- 2. Classify by current state.
--    Order matters: policy first, then deprecated/deferred overrides.
-- --------------------------------------------------------------------------

-- Policy blueprints (pipelineConfig=true) -> orchestration_policy.
UPDATE blueprints
   SET add_surface = 'orchestration_policy'
 WHERE pipeline_config = TRUE;

-- Deprecated or deferred rows are not addable via generic paths.
UPDATE blueprints
   SET add_surface = 'none'
 WHERE deferred = TRUE
    OR status = 'deprecated';

-- --------------------------------------------------------------------------
-- 3. Orchestration persistence split (ARCH-012).
--    AdvanceTimeDimension is a composition-visible orchestration action.
-- --------------------------------------------------------------------------
UPDATE blueprints
   SET composition_role = 'orchestration_action',
       artifact_types   = '["airflow_action"]'::jsonb,
       pipeline_config  = FALSE,
       add_surface      = 'composition',
       emit_strategy    = 'generate'
 WHERE blueprint_key = 'AdvanceTimeDimension';

-- --------------------------------------------------------------------------
-- 4. Active blueprint contract (ARCH-011) - Derive is deferred-first until
--    first-class SQL codegen and tests for derived/dropped columns land.
-- --------------------------------------------------------------------------
UPDATE blueprints
   SET deferred    = TRUE,
       add_surface = 'none'
 WHERE blueprint_key = 'Derive';

-- --------------------------------------------------------------------------
-- 5. GenericRouter canonical params (route_predicates -> routes).
--    Catalog already uses `routes`; this fixes any persisted instances.
-- --------------------------------------------------------------------------
UPDATE sub_pipeline_instances
   SET params = (params - 'route_predicates')
                || jsonb_build_object('routes', params -> 'route_predicates')
 WHERE blueprint_key = 'GenericRouter'
   AND params ? 'route_predicates'
   AND NOT (params ? 'routes');

-- --------------------------------------------------------------------------
-- 6. Constraint + index. Constraint added last so backfill cannot violate it.
-- --------------------------------------------------------------------------
ALTER TABLE blueprints
    ADD CONSTRAINT blueprints_add_surface_check
    CHECK (add_surface IN ('composition','orchestration_policy','none'));

CREATE INDEX idx_blueprints_add_surface ON blueprints(add_surface);
