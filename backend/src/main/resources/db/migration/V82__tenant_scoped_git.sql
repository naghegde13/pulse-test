-- V82: Tenant-scoped git consolidation (Phase 1, Agent B).
--
-- Moves git repository ownership from domain/pipeline level to the tenant
-- level. One TENANT-scoped repo per tenant. Existing domain/pipeline rows are
-- preserved as LEGACY for backward-compatible read paths.
--
-- Additive: no existing rows are deleted. V77's unique constraints remain in
-- place. A new partial unique index enforces "at most one TENANT-scoped repo
-- per tenant".

-- =============================================================================
-- 7a. New columns.
--   scope          : 'TENANT' or 'LEGACY'. Default LEGACY so existing rows
--                    keep their current semantics without an explicit UPDATE.
--   repo_type      : 'LOCAL' or 'REMOTE'. Defaults to REMOTE because all
--                    existing rows are URL references.
--   local_path     : Filesystem path to the local working copy (REMOTE clones
--                    land here; LOCAL repos live here).
--   current_branch : Active branch. Populated during 7b for promoted rows.
-- =============================================================================

ALTER TABLE git_repos ADD COLUMN IF NOT EXISTS scope VARCHAR(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE git_repos ADD COLUMN IF NOT EXISTS repo_type VARCHAR(20) NOT NULL DEFAULT 'REMOTE';
ALTER TABLE git_repos ADD COLUMN IF NOT EXISTS local_path VARCHAR(1000);
ALTER TABLE git_repos ADD COLUMN IF NOT EXISTS current_branch VARCHAR(255);

-- =============================================================================
-- 7b. Consolidation: promote one repo per tenant to TENANT scope.
-- Picks the most recently updated domain-scoped repo per tenant, flips it to
-- TENANT scope, and sets current_branch from default_branch. Non-promoted
-- repos remain LEGACY and are still queryable via the existing domain/pipeline
-- endpoints. Pull requests are not redirected (V77 already handled that).
-- =============================================================================

WITH ranked AS (
    SELECT id, tenant_id, domain_id,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS rn
    FROM git_repos
    WHERE domain_id IS NOT NULL
)
UPDATE git_repos gr
SET scope = 'TENANT',
    current_branch = gr.default_branch,
    metadata = COALESCE(gr.metadata, '{}'::jsonb)
        || jsonb_build_object('scope', 'TENANT', 'promoted_from_domain_id', r.domain_id)
FROM ranked r
WHERE gr.id = r.id AND r.rn = 1;

-- =============================================================================
-- 7c. Partial unique index: at most one TENANT-scoped repo per tenant.
-- V77's uq_git_repos_domain and uq_git_repos_pipeline_legacy stay in place for
-- LEGACY rows.
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq_git_repos_tenant_scope
    ON git_repos(tenant_id)
    WHERE scope = 'TENANT';
