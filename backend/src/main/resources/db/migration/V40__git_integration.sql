-- WS-5: Git Integration

CREATE TABLE git_repos (
    id              VARCHAR(26) PRIMARY KEY,
    tenant_id       VARCHAR(26) NOT NULL,
    pipeline_id     VARCHAR(26) NOT NULL REFERENCES pipelines(id),
    provider        VARCHAR(20) NOT NULL DEFAULT 'GITHUB',
    repo_url        VARCHAR(500) NOT NULL,
    default_branch  VARCHAR(100) NOT NULL DEFAULT 'main',
    deploy_key_id   VARCHAR(100),
    webhook_secret  VARCHAR(100),
    last_synced_at  TIMESTAMPTZ,
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pull_requests (
    id                  VARCHAR(26) PRIMARY KEY,
    git_repo_id         VARCHAR(26) NOT NULL REFERENCES git_repos(id),
    generation_run_id   VARCHAR(26) REFERENCES generation_runs(id),
    version_id          VARCHAR(26) NOT NULL REFERENCES pipeline_versions(id),
    pr_number           INTEGER NOT NULL,
    title               VARCHAR(500) NOT NULL,
    source_branch       VARCHAR(200) NOT NULL,
    target_branch       VARCHAR(200) NOT NULL DEFAULT 'main',
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    pr_url              VARCHAR(500),
    merge_commit_sha    VARCHAR(40),
    merged_at           TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,
    metadata            JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_git_repos_pipeline ON git_repos(pipeline_id);
CREATE INDEX idx_git_repos_tenant ON git_repos(tenant_id);
CREATE INDEX idx_prs_repo ON pull_requests(git_repo_id);
CREATE INDEX idx_prs_version ON pull_requests(version_id);
CREATE INDEX idx_prs_status ON pull_requests(status);

COMMENT ON TABLE git_repos IS 'Git repository links per pipeline';
COMMENT ON TABLE pull_requests IS 'PRs created from generated code';
COMMENT ON COLUMN git_repos.provider IS 'GITHUB, GITLAB, BITBUCKET, AZURE_DEVOPS';
COMMENT ON COLUMN pull_requests.status IS 'OPEN, MERGED, CLOSED, DRAFT';
