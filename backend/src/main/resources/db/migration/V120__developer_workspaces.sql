-- ARCH-016: developer Git workspaces, workspace file authority, and version acceptance provenance.

CREATE TABLE IF NOT EXISTS developer_workspaces (
    id                          VARCHAR(26) PRIMARY KEY,
    tenant_id                   VARCHAR(26) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    pipeline_id                 VARCHAR(26) NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    version_id                  VARCHAR(26) NOT NULL REFERENCES pipeline_versions(id) ON DELETE CASCADE,
    git_repo_id                 VARCHAR(26) NOT NULL REFERENCES git_repos(id) ON DELETE CASCADE,
    actor_user_id               VARCHAR(64) NOT NULL,
    branch_name                 VARCHAR(255) NOT NULL,
    base_branch                 VARCHAR(255) NOT NULL,
    base_sha                    VARCHAR(64),
    checkout_path               VARCHAR(1024) NOT NULL,
    legacy_seed                 BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle_status            VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    working_tree_status         VARCHAR(32) NOT NULL DEFAULT 'unknown',
    remote_sync_status          VARCHAR(32) NOT NULL DEFAULT 'unknown',
    pr_status                   VARCHAR(32) NOT NULL DEFAULT 'none',
    head_sha                    VARCHAR(64),
    head_tree_sha               VARCHAR(64),
    dirty_file_count            INTEGER NOT NULL DEFAULT 0,
    last_package_id             VARCHAR(26),
    last_dev_deployment_run_id  VARCHAR(26),
    last_commit_sha             VARCHAR(64),
    last_push_sha               VARCHAR(64),
    pull_request_id             VARCHAR(26),
    lock_version                INTEGER NOT NULL DEFAULT 0,
    lease_owner                 VARCHAR(128),
    lease_expires_at            TIMESTAMPTZ,
    metadata                    JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_developer_workspace_lifecycle CHECK (lifecycle_status IN (
        'ACTIVE', 'ARCHIVED'
    )),
    CONSTRAINT chk_developer_workspace_tree_status CHECK (working_tree_status IN (
        'clean', 'dirty', 'unborn', 'conflict', 'missing', 'unknown'
    )),
    CONSTRAINT chk_developer_workspace_remote_status CHECK (remote_sync_status IN (
        'not_pushed', 'pushed', 'behind', 'diverged', 'unknown'
    )),
    CONSTRAINT chk_developer_workspace_pr_status CHECK (pr_status IN (
        'none', 'open', 'merged', 'closed', 'draft'
    )),
    CONSTRAINT chk_developer_workspace_dirty_nonnegative CHECK (dirty_file_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_developer_workspace_active_actor_version
    ON developer_workspaces(tenant_id, version_id, actor_user_id)
    WHERE lifecycle_status = 'ACTIVE';

CREATE UNIQUE INDEX IF NOT EXISTS uq_developer_workspace_active_branch
    ON developer_workspaces(git_repo_id, branch_name)
    WHERE lifecycle_status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_developer_workspace_pipeline
    ON developer_workspaces(pipeline_id);

CREATE INDEX IF NOT EXISTS idx_developer_workspace_version
    ON developer_workspaces(version_id);

CREATE TABLE IF NOT EXISTS workspace_file_manifests (
    id                          VARCHAR(26) PRIMARY KEY,
    workspace_id                VARCHAR(26) NOT NULL REFERENCES developer_workspaces(id) ON DELETE CASCADE,
    path                        VARCHAR(1024) NOT NULL,
    source_artifact_id          VARCHAR(26) REFERENCES generated_artifacts(id) ON DELETE SET NULL,
    last_materialized_sha256    VARCHAR(64),
    current_workspace_sha256    VARCHAR(64),
    last_committed_sha256       VARCHAR(64),
    managed_by_pulse            BOOLEAN NOT NULL DEFAULT TRUE,
    path_scope                  VARCHAR(32) NOT NULL DEFAULT 'PIPELINE',
    ownership_key               VARCHAR(255),
    last_materialized_at        TIMESTAMPTZ,
    metadata                    JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_workspace_file_manifest_scope CHECK (path_scope IN (
        'PIPELINE', 'TENANT_SHARED'
    )),
    CONSTRAINT chk_workspace_file_manifest_relative_path CHECK (
        path NOT LIKE '/%' AND path NOT LIKE '\\%' AND path NOT LIKE '%..%'
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_workspace_file_manifest_path
    ON workspace_file_manifests(workspace_id, path);

CREATE INDEX IF NOT EXISTS idx_workspace_file_manifest_workspace
    ON workspace_file_manifests(workspace_id);

CREATE TABLE IF NOT EXISTS version_acceptances (
    id                          VARCHAR(26) PRIMARY KEY,
    tenant_id                   VARCHAR(26) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    pipeline_id                 VARCHAR(26) NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    version_id                  VARCHAR(26) NOT NULL REFERENCES pipeline_versions(id) ON DELETE CASCADE,
    workspace_id                VARCHAR(26) REFERENCES developer_workspaces(id) ON DELETE SET NULL,
    pull_request_id             VARCHAR(26) REFERENCES pull_requests(id) ON DELETE SET NULL,
    accepted_package_id         VARCHAR(26) REFERENCES packages(id) ON DELETE SET NULL,
    accepted_commit_sha         VARCHAR(64) NOT NULL,
    accepted_tree_sha           VARCHAR(64) NOT NULL,
    acceptance_kind             VARCHAR(64) NOT NULL DEFAULT 'MERGED_PR_EXACT_HEAD',
    acceptance_status           VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    accepted_at                 TIMESTAMPTZ,
    accepted_by                 VARCHAR(64),
    acceptance_evidence         JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_version_acceptance_kind CHECK (acceptance_kind IN (
        'MERGED_PR_EXACT_HEAD'
    )),
    CONSTRAINT chk_version_acceptance_status CHECK (acceptance_status IN (
        'ACTIVE', 'SUPERSEDED', 'REVOKED'
    ))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_version_acceptance_active_version
    ON version_acceptances(version_id)
    WHERE acceptance_status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_version_acceptance_pipeline
    ON version_acceptances(pipeline_id);

ALTER TABLE packages
    ADD COLUMN IF NOT EXISTS source_kind VARCHAR(64),
    ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(26),
    ADD COLUMN IF NOT EXISTS commit_sha VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tree_sha VARCHAR(64),
    ADD COLUMN IF NOT EXISTS package_artifact_uri VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS package_artifact_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS package_manifest_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS promotable BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_packages_workspace
    ON packages(workspace_id);

ALTER TABLE pull_requests
    ADD COLUMN IF NOT EXISTS provider_pr_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS provider_node_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS provider_repository_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS head_sha VARCHAR(64),
    ADD COLUMN IF NOT EXISTS head_tree_sha VARCHAR(64),
    ADD COLUMN IF NOT EXISTS base_sha VARCHAR(64),
    ADD COLUMN IF NOT EXISTS package_artifact_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS provider_synced_at TIMESTAMPTZ;
