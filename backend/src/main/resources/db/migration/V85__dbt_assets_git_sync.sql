-- V85: Make the dbt_assets cache branch-aware so tenant git becomes the source of truth.
--
-- Owned by Agent A (redesign-plan-v2, WP2). V76 introduced dbt_assets with
-- uniqueness on (domain_id, asset_name, asset_type). Once each tenant owns a git
-- repo (Agent B), the same asset can exist on multiple branches with different
-- content. V85 scopes uniqueness to the branch and records the git SHA that last
-- populated each row.

-- 1. Add git sync columns. `git_sha` stays nullable to signal "never synced from
--    git" for legacy rows; `branch` becomes NOT NULL after backfill.
ALTER TABLE dbt_assets ADD COLUMN IF NOT EXISTS git_sha VARCHAR(40);
ALTER TABLE dbt_assets ADD COLUMN IF NOT EXISTS branch  VARCHAR(255);

-- 2. Backfill existing rows to branch='main' so the column can go NOT NULL.
UPDATE dbt_assets SET branch = 'main' WHERE branch IS NULL;

-- 3. Enforce branch NOT NULL once backfill is complete.
ALTER TABLE dbt_assets ALTER COLUMN branch SET NOT NULL;

-- 4. Replace the V76 uniqueness with a branch-aware variant. V76 created a
--    UNIQUE INDEX (not a table-level constraint), so DROP INDEX is the correct
--    form; DROP CONSTRAINT is a no-op but harmless.
DROP INDEX IF EXISTS uq_dbt_assets_domain_name_type;
ALTER TABLE dbt_assets DROP CONSTRAINT IF EXISTS uq_dbt_assets_domain_name_type;

CREATE UNIQUE INDEX IF NOT EXISTS uq_dbt_assets_branch_scoped
    ON dbt_assets(domain_id, branch, asset_name, asset_type);

-- 5. Lookup index for branch-filtered reuse-candidate searches.
CREATE INDEX IF NOT EXISTS idx_dbt_assets_branch ON dbt_assets(domain_id, branch);
