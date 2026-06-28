-- ARCH-002 / ARCH-003: user-owned Git identity and branch-scoped scaffold state.

ALTER TABLE git_repos DROP COLUMN IF EXISTS deploy_key_id;

CREATE TABLE IF NOT EXISTS tenant_repo_scaffold_items (
    id                          VARCHAR(26) PRIMARY KEY,
    tenant_id                   VARCHAR(26) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    git_repo_id                 VARCHAR(26) NOT NULL REFERENCES git_repos(id) ON DELETE CASCADE,
    branch_name                 VARCHAR(255) NOT NULL,
    item_type                   VARCHAR(32) NOT NULL,
    domain_id                   VARCHAR(26) REFERENCES domains(id) ON DELETE CASCADE,
    domain_slug                 VARCHAR(255),
    status                      VARCHAR(32) NOT NULL,
    last_scaffolded_at          TIMESTAMPTZ,
    last_scaffolded_by_user_id  VARCHAR(64),
    last_commit_sha             VARCHAR(64),
    last_error                  TEXT,
    metadata                    JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tenant_repo_scaffold_item_type CHECK (item_type IN (
        'TOP_LEVEL', 'DBT_PROJECT', 'DOMAIN'
    )),
    CONSTRAINT chk_tenant_repo_scaffold_status CHECK (status IN (
        'SCAFFOLDED', 'MISSING', 'STALE', 'ERROR'
    ))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_repo_scaffold_global_item
    ON tenant_repo_scaffold_items(git_repo_id, branch_name, item_type)
    WHERE domain_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_repo_scaffold_domain_item
    ON tenant_repo_scaffold_items(git_repo_id, branch_name, domain_id)
    WHERE domain_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_tenant_repo_scaffold_tenant_branch
    ON tenant_repo_scaffold_items(tenant_id, branch_name);

CREATE INDEX IF NOT EXISTS idx_tenant_repo_scaffold_status
    ON tenant_repo_scaffold_items(status);
