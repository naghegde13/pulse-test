-- V105: Phase 6 — user_git_identities.
--
-- One row per (tenant, pulse_user_id, provider). Stores ONLY:
--   * the gcp-sm:// reference for the user's PAT classic value
--   * non-secret metadata (provider, github username, author name/email,
--     scopes, status, validation diagnostics)
--
-- The PAT value itself is never persisted in this table — it lives only
-- in Google Secret Manager (or the local-stub equivalent).
--
-- Status enum (mirrors GitHubPatValidationStatus.java):
--   PENDING_VALIDATION, VALID, INVALID_TOKEN, INSUFFICIENT_SCOPE,
--   REPO_ACCESS_DENIED, REVOKED, PROVIDER_UNAVAILABLE, EXPIRED.

CREATE TABLE user_git_identities (
    id                          VARCHAR(26) PRIMARY KEY,
    tenant_id                   VARCHAR(26) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    pulse_user_id               VARCHAR(64) NOT NULL,
    provider                    VARCHAR(32) NOT NULL DEFAULT 'GITHUB',
    credential_type             VARCHAR(32) NOT NULL DEFAULT 'PAT_CLASSIC',
    -- Always a gcp-sm:// reference; never a raw token.
    credential_reference        VARCHAR(512) NOT NULL,
    github_username             VARCHAR(128),
    author_name                 VARCHAR(255),
    author_email                VARCHAR(255),
    scopes                      VARCHAR(255),
    status                      VARCHAR(32) NOT NULL DEFAULT 'PENDING_VALIDATION',
    verified_at                 TIMESTAMPTZ,
    expires_at                  TIMESTAMPTZ,
    last_validation_error       TEXT,
    last_rotated_at             TIMESTAMPTZ,
    revoked_at                  TIMESTAMPTZ,
    metadata                    JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Only one ACTIVE identity per (tenant, user, provider). Rotation
    -- mints a new GSM secret and updates the credential_reference in
    -- place; revocation flips status to REVOKED but keeps the row for
    -- audit.
    CONSTRAINT uq_user_git_identity UNIQUE (tenant_id, pulse_user_id, provider),
    CONSTRAINT chk_user_git_identity_status CHECK (status IN (
        'PENDING_VALIDATION','VALID','INVALID_TOKEN','INSUFFICIENT_SCOPE',
        'REPO_ACCESS_DENIED','REVOKED','PROVIDER_UNAVAILABLE','EXPIRED')),
    CONSTRAINT chk_user_git_identity_credential_reference
        CHECK (credential_reference LIKE 'gcp-sm://%')
);

CREATE INDEX idx_user_git_identity_tenant ON user_git_identities(tenant_id);
CREATE INDEX idx_user_git_identity_user ON user_git_identities(pulse_user_id);
CREATE INDEX idx_user_git_identity_status ON user_git_identities(status);

COMMENT ON COLUMN user_git_identities.credential_reference IS
    'Pulse-managed gcp-sm:// reference. The actual PAT value lives in GSM.';
COMMENT ON COLUMN user_git_identities.status IS
    'PAT validation lifecycle. See GitHubPatValidationStatus enum.';
