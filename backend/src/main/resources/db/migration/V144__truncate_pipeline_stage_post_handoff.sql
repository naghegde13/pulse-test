-- V144: Truncate PipelineStage to PULSE-observable states only.
--
-- Architectural correction (PKT-FINAL-2 / BUG-2026-05-25-02, 2026-05-25):
-- PULSE is dev-only. PULSE deploys to dev only. Promotion to integration,
-- UAT, and production is handled by enterprise CI/CD after a pipeline's
-- artifact is published; PULSE has no legitimate visibility into those
-- downstream states.
--
-- The lifecycle stages INTEGRATION_QUALIFIED, UAT_DEPLOYED, and PRODUCTION
-- implied PULSE-side observability of post-handoff state and must therefore
-- be removed. Surviving stages: DRAFT, ENGINEERING, DEV_DEPLOYED,
-- DEV_VALIDATED, PUBLISHED. PUBLISHED is the terminal PULSE-managed state
-- ("handed off to enterprise CD").

-- 1. Backfill any existing rows on removed stages → PUBLISHED.
UPDATE pipeline_versions
   SET lifecycle_stage = 'PUBLISHED'
 WHERE lifecycle_stage IN ('INTEGRATION_QUALIFIED', 'UAT_DEPLOYED', 'PRODUCTION');

-- 2. Lock the column to the surviving values so future writes cannot
--    reintroduce removed stages. Older draft / generated / configured
--    rows pre-dating the formal enum are also allowed so seeded historical
--    data continues to load on a fresh DB.
ALTER TABLE pipeline_versions
    ADD CONSTRAINT chk_lifecycle_stage_pulse_observable
    CHECK (lifecycle_stage IN (
        'DRAFT',
        'ENGINEERING',
        'DEV_DEPLOYED',
        'DEV_VALIDATED',
        'PUBLISHED'
    ));
