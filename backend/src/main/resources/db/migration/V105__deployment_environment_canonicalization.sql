-- V103: Phase 1 of the deployment productization plan — canonicalize
-- persisted environment values across the deploy / credential surface.
--
-- Canonical persisted environment keys are lowercase:
--   local | dev | integration | uat | prod
--
-- Legacy uppercase / shorthand inputs continue to be accepted at API
-- boundaries (DeploymentEnvironment.normalize), but persisted columns
-- are normalized here so downstream lookups can rely on canonical form.
--
-- Tables touched (all are nullable-tolerant; we only update non-canonical
-- rows):
--   * deployment_targets.environment        (V50 column comment listed
--     'DEV, INTEGRATION, UAT, PRODUCTION' — uppercase)
--   * credential_profiles.environment       (V10 seed inserted 'DEV')
--   * deployments.metadata->>targetEnvironment (deploy controller used to
--     copy the legacy uppercase value into metadata; rewrite to canonical
--     form so API responses surface the canonical key)
--
-- Tables intentionally NOT touched:
--   * storage_backends.environment          — already canonical lowercase,
--     constrained by chk_storage_backends_environment to
--     ('local','dev','integration','uat','prod') since V96 + V98.
--   * domains.slug, tenants.slug            — not environment columns.

-- ---------------------------------------------------------------------
-- 1. deployment_targets.environment → canonical lowercase.
-- ---------------------------------------------------------------------
UPDATE deployment_targets
SET environment = CASE LOWER(environment)
                    WHEN 'local'        THEN 'local'
                    WHEN 'dev'          THEN 'dev'
                    WHEN 'int'          THEN 'integration'
                    WHEN 'integration'  THEN 'integration'
                    WHEN 'uat'          THEN 'uat'
                    WHEN 'prod'         THEN 'prod'
                    WHEN 'production'   THEN 'prod'
                    ELSE environment       -- preserve unknown values for human review
                  END,
    updated_at = NOW()
WHERE environment IS NOT NULL
  AND environment <> CASE LOWER(environment)
                       WHEN 'local'        THEN 'local'
                       WHEN 'dev'          THEN 'dev'
                       WHEN 'int'          THEN 'integration'
                       WHEN 'integration'  THEN 'integration'
                       WHEN 'uat'          THEN 'uat'
                       WHEN 'prod'         THEN 'prod'
                       WHEN 'production'   THEN 'prod'
                       ELSE environment
                     END;

-- Refresh the human-readable column comment so the schema documents the
-- canonical form. (Older comment from V50 still listed uppercase enum.)
COMMENT ON COLUMN deployment_targets.environment
    IS 'Canonical lowercase deployment environment key: local, dev, integration, uat, prod';

-- ---------------------------------------------------------------------
-- 2. credential_profiles.environment → canonical lowercase.
-- ---------------------------------------------------------------------
UPDATE credential_profiles
SET environment = CASE LOWER(environment)
                    WHEN 'local'        THEN 'local'
                    WHEN 'dev'          THEN 'dev'
                    WHEN 'int'          THEN 'integration'
                    WHEN 'integration'  THEN 'integration'
                    WHEN 'uat'          THEN 'uat'
                    WHEN 'prod'         THEN 'prod'
                    WHEN 'production'   THEN 'prod'
                    ELSE environment
                  END,
    updated_at = NOW()
WHERE environment IS NOT NULL
  AND environment <> CASE LOWER(environment)
                       WHEN 'local'        THEN 'local'
                       WHEN 'dev'          THEN 'dev'
                       WHEN 'int'          THEN 'integration'
                       WHEN 'integration'  THEN 'integration'
                       WHEN 'uat'          THEN 'uat'
                       WHEN 'prod'         THEN 'prod'
                       WHEN 'production'   THEN 'prod'
                       ELSE environment
                     END;

COMMENT ON COLUMN credential_profiles.environment
    IS 'Canonical lowercase environment key: local, dev, integration, uat, prod';

-- ---------------------------------------------------------------------
-- 3. deployments.metadata.targetEnvironment → canonical lowercase.
--
-- Pre-Phase-1 the deploy controller copied the persisted (then-uppercase)
-- target.environment into the deployment's JSONB metadata under
-- "targetEnvironment". Rewrite legacy values so any UI or client that
-- already reads deployment.metadata.targetEnvironment sees the canonical
-- form after this migration runs. We only touch rows that need it.
-- ---------------------------------------------------------------------
UPDATE deployments
SET metadata = jsonb_set(
                  COALESCE(metadata, '{}'::jsonb),
                  '{targetEnvironment}',
                  to_jsonb(
                      CASE LOWER(metadata->>'targetEnvironment')
                          WHEN 'local'        THEN 'local'
                          WHEN 'dev'          THEN 'dev'
                          WHEN 'int'          THEN 'integration'
                          WHEN 'integration'  THEN 'integration'
                          WHEN 'uat'          THEN 'uat'
                          WHEN 'prod'         THEN 'prod'
                          WHEN 'production'   THEN 'prod'
                          ELSE metadata->>'targetEnvironment'
                      END
                  ),
                  false
              ),
    updated_at = NOW()
WHERE metadata ? 'targetEnvironment'
  AND metadata->>'targetEnvironment' IS NOT NULL
  AND metadata->>'targetEnvironment' <> CASE LOWER(metadata->>'targetEnvironment')
                                          WHEN 'local'        THEN 'local'
                                          WHEN 'dev'          THEN 'dev'
                                          WHEN 'int'          THEN 'integration'
                                          WHEN 'integration'  THEN 'integration'
                                          WHEN 'uat'          THEN 'uat'
                                          WHEN 'prod'         THEN 'prod'
                                          WHEN 'production'   THEN 'prod'
                                          ELSE metadata->>'targetEnvironment'
                                        END;
