-- V100: Local-E2E shortcut — flip seeded test tenants' dev+DPC and dev+GCP
--       storage_backend rows to provisioning_status='validated'.
--
-- BACKGROUND
-- ----------
-- V98 (correctly) marked all dev rows as 'pending' because in the real-world
-- model, dev = deployed shared dev (real cloud project), and provisioning is
-- a platform-team responsibility — they validate by seeding cloud project
-- IAM, bucket creation, etc., before flipping the row to 'validated'.
--
-- For local E2E testing on the seeded reference tenants we don't go through
-- the real platform-team workflow. The user explicitly chose to operate the
-- chat agent against dev (matching real-world UX) rather than against local
-- (which would surface "you're in test mode" framing). Spark-cluster-side
-- transparency (docker/spark-defaults.conf) routes s3a:// URIs against the
-- dev bucket names to the local MinIO instance, so the runtime works without
-- PULSE ever knowing about MinIO.
--
-- This migration codifies that local-E2E shortcut as data so a fresh DB reset
-- comes up E2E-ready. It is SCOPED to the two seeded reference tenants
-- (tenant-home-lending, tenant-unsecured-lending) — real tenants onboarded
-- in production are unaffected and continue to follow V98's pending-by-default
-- rule until the platform team flips them.
--
-- ROLLBACK
-- --------
-- To restore V98's pure intent (dev=pending for all tenants):
--   UPDATE storage_backends
--   SET provisioning_status='pending',
--       provisioning_validated_at=NULL
--   WHERE tenant_id IN ('tenant-home-lending','tenant-unsecured-lending')
--     AND environment='dev';

UPDATE storage_backends
SET provisioning_status = 'validated',
    provisioning_validated_at = NOW(),
    provisioning_error = NULL,
    updated_at = NOW()
WHERE tenant_id IN ('tenant-home-lending', 'tenant-unsecured-lending')
  AND environment = 'dev'
  AND backend IN ('DPC', 'GCP')
  AND provisioning_status = 'pending';

-- ---------------------------------------------------------------------
-- Validation queries (post-apply):
--   SELECT tenant_id, environment, backend, provisioning_status
--   FROM storage_backends
--   WHERE tenant_id IN ('tenant-home-lending','tenant-unsecured-lending')
--     AND environment = 'dev';
--   -- Expected: 4 rows, all status='validated'
-- ---------------------------------------------------------------------
