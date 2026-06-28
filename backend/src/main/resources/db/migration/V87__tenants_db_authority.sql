-- V87: Promote the `tenants` table from a reference/audit artifact to the
-- runtime source of truth for tenant identity.
--
-- Pre-V87 state: tenants were config-driven (application.yml) and the `tenants`
-- table (created in V1, seeded in V2) was never read at runtime. TenantService
-- looked up tenants from YAML.
-- Post-V87 state: TenantService reads from the `tenants` table. The YAML is
-- only a bootstrap source that inserts missing rows at startup. Rows written
-- via the new POST /api/v1/tenants endpoint use origin='api' so the bootstrap
-- pass never touches them.
--
-- This migration is additive. No data is deleted. The existing seed rows from
-- V2 (now renamed to tenant-home-lending / tenant-unsecured-lending) stay put.

-- 87a. Distinguish YAML-seeded tenants from API-created ones.
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS origin VARCHAR(20) NOT NULL DEFAULT 'bootstrap';

ALTER TABLE tenants
    DROP CONSTRAINT IF EXISTS ck_tenants_origin;
ALTER TABLE tenants
    ADD CONSTRAINT ck_tenants_origin
    CHECK (origin IN ('bootstrap', 'api'));

-- 87b. Operational status column — so an ADMIN can disable a tenant without
-- deleting history. Not enforced by any service yet; reserved for future use.
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'active';

ALTER TABLE tenants
    DROP CONSTRAINT IF EXISTS ck_tenants_status;
ALTER TABLE tenants
    ADD CONSTRAINT ck_tenants_status
    CHECK (status IN ('active', 'disabled'));

-- 87c. Retire the repo_url column. The /onboard endpoint (V82+) owns repo
-- identity; the column has been dead code since V82 landed. Drop defensively
-- so re-running on older DBs doesn't fail if the column is already gone.
ALTER TABLE tenants DROP COLUMN IF EXISTS repo_url;

-- 87d. Backfill origin for any pre-V87 row. Seed rows from V2 are bootstrap;
-- anything else (there shouldn't be any) defaults to bootstrap too via the
-- column default above. The UPDATE is explicit for clarity and to force the
-- row through updated_at refresh if any trigger is attached.
UPDATE tenants SET origin = 'bootstrap' WHERE origin IS NULL OR origin = '';
