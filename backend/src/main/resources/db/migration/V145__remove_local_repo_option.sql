-- =============================================================================
-- V145 — Remove the LOCAL tenant-git repo option (PKT-FINAL-3 / BUG-05).
-- =============================================================================
-- Architectural decision: PULSE supports only REMOTE GitHub tenant repos.
-- The LOCAL tenant-config option is gone; the *local working clone* directory
-- on the backend host (the path under PULSE_GIT_CLONE_BASE) survives because
-- every REMOTE repo still needs a local working tree for codegen + commits.
-- That distinction is preserved in `git_repos.local_path`, which stays.
--
-- Behavior:
--   1. Backfill any existing LOCAL rows to REMOTE with a placeholder URL the
--      operator must replace via `PUT /api/v1/tenants/{tenantId}/git-repo`
--      before code generation can succeed.
--   2. Lock future writes via CHECK constraints so the dead LOCAL path can
--      never be reintroduced by accident from the API, from chat tools, or
--      from direct SQL.
-- =============================================================================

UPDATE git_repos
   SET repo_type = 'REMOTE',
       repo_url  = COALESCE(
           NULLIF(repo_url, ''),
           'https://github.com/placeholder/pulse-' || tenant_id || '.git'),
       provider  = 'GITHUB',
       metadata  = COALESCE(metadata, '{}'::jsonb)
                   || jsonb_build_object(
                          'migrationNote',
                          'backfilled from LOCAL to REMOTE — operator must update repo_url')
 WHERE repo_type = 'LOCAL';

-- Drop any prior constraints with the same names (idempotent in case of a re-run).
ALTER TABLE git_repos DROP CONSTRAINT IF EXISTS ck_repo_type_remote;
ALTER TABLE git_repos DROP CONSTRAINT IF EXISTS ck_provider_github;

-- The CHECK on provider is scoped to TENANT-scoped rows so any future legacy
-- DOMAIN/PIPELINE-scoped rows are not locked out; today the table is GitHub-only
-- in practice, so this is defensive rather than restrictive.
ALTER TABLE git_repos
  ADD CONSTRAINT ck_repo_type_remote CHECK (repo_type = 'REMOTE'),
  ADD CONSTRAINT ck_provider_github  CHECK (provider IS NULL OR provider = 'GITHUB');
