-- V2: Tenants are now config-driven (application.yml), not DB-managed.
-- Relax FK constraints that reference tenants table, since tenant_id
-- is now validated at the application layer via TenantService.

-- Drop FKs referencing tenants
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_tenant_id_fkey;
ALTER TABLE pipelines DROP CONSTRAINT IF EXISTS pipelines_tenant_id_fkey;
ALTER TABLE plans DROP CONSTRAINT IF EXISTS plans_tenant_id_fkey;
ALTER TABLE command_log DROP CONSTRAINT IF EXISTS command_log_tenant_id_fkey;

-- Also relax user FKs on pipelines/plans/command_log for now (auth is stubbed)
ALTER TABLE pipelines DROP CONSTRAINT IF EXISTS pipelines_created_by_fkey;
ALTER TABLE plans DROP CONSTRAINT IF EXISTS plans_actor_id_fkey;
ALTER TABLE command_log DROP CONSTRAINT IF EXISTS command_log_actor_id_fkey;

-- Seed config-driven tenants into tenants table for reference/audit
INSERT INTO tenants (id, name, slug, repo_url)
VALUES
    ('tenant-home-lending', 'Home Lending D&I', 'home-lending', 'https://github.com/home-lending/data-pipelines'),
    ('tenant-unsecured-lending', 'Unsecured Lending', 'unsecured-lending', 'https://github.com/unsecured-lending/data-pipelines')
ON CONFLICT (id) DO NOTHING;
