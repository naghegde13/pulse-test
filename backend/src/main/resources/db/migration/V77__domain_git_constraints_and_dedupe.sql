-- Follow-on migration for domain identity hardening and git repo dedupe/constraints.
-- Kept separate from V74 to avoid Flyway checksum drift on already-applied databases.

ALTER TABLE pipelines
    ADD CONSTRAINT fk_pipelines_domain
        FOREIGN KEY (domain_id) REFERENCES domains(id) ON DELETE SET NULL;

WITH ranked_domain_repos AS (
    SELECT id,
           domain_id,
           FIRST_VALUE(id) OVER (
               PARTITION BY domain_id
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS keeper_id,
           ROW_NUMBER() OVER (
               PARTITION BY domain_id
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS rn
    FROM git_repos
    WHERE domain_id IS NOT NULL
),
duplicate_domain_repos AS (
    SELECT id, keeper_id
    FROM ranked_domain_repos
    WHERE rn > 1
)
UPDATE pull_requests pr
SET git_repo_id = dupes.keeper_id
FROM duplicate_domain_repos dupes
WHERE pr.git_repo_id = dupes.id;

WITH ranked_domain_repos AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY domain_id
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS rn
    FROM git_repos
    WHERE domain_id IS NOT NULL
)
DELETE FROM git_repos gr
USING ranked_domain_repos dupes
WHERE gr.id = dupes.id
  AND dupes.rn > 1;

WITH ranked_pipeline_repos AS (
    SELECT id,
           pipeline_id,
           FIRST_VALUE(id) OVER (
               PARTITION BY pipeline_id
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS keeper_id,
           ROW_NUMBER() OVER (
               PARTITION BY pipeline_id
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS rn
    FROM git_repos
    WHERE pipeline_id IS NOT NULL
      AND domain_id IS NULL
),
duplicate_pipeline_repos AS (
    SELECT id, keeper_id
    FROM ranked_pipeline_repos
    WHERE rn > 1
)
UPDATE pull_requests pr
SET git_repo_id = dupes.keeper_id
FROM duplicate_pipeline_repos dupes
WHERE pr.git_repo_id = dupes.id;

WITH ranked_pipeline_repos AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY pipeline_id
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS rn
    FROM git_repos
    WHERE pipeline_id IS NOT NULL
      AND domain_id IS NULL
)
DELETE FROM git_repos gr
USING ranked_pipeline_repos dupes
WHERE gr.id = dupes.id
  AND dupes.rn > 1;

ALTER TABLE git_repos
    ADD CONSTRAINT fk_git_repos_domain
        FOREIGN KEY (domain_id) REFERENCES domains(id) ON DELETE CASCADE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_git_repos_domain ON git_repos(domain_id) WHERE domain_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_git_repos_pipeline_legacy
    ON git_repos(pipeline_id)
    WHERE pipeline_id IS NOT NULL AND domain_id IS NULL;
