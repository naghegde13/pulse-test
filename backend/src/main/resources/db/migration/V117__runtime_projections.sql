-- =============================================================
-- V114: Runtime Projections and DDL Statements
-- ARCH-006 — Package Runtime Projection persistence
-- =============================================================

-- -----------------------------------------------
-- runtime_projections
-- -----------------------------------------------
CREATE TABLE IF NOT EXISTS runtime_projections (
    id                         VARCHAR(26)  NOT NULL PRIMARY KEY,
    package_id                 VARCHAR(26)  NOT NULL,
    target_id                  VARCHAR(26)  NOT NULL,
    environment                VARCHAR(30)  NOT NULL,
    runtime_persona            VARCHAR(30)  NOT NULL,
    runtime_authority_version  VARCHAR(20),
    projection_hash            VARCHAR(64)  NOT NULL,
    status                     VARCHAR(20)  NOT NULL DEFAULT 'active',
    readiness_blockers         TEXT,
    resolved_storage_roots     TEXT,
    resolved_catalogs          TEXT,
    resolved_entrypoints       TEXT,
    adapter_plan               TEXT,
    orchestration_block        TEXT,
    projected_at               TIMESTAMP,
    created_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rp_pkg_target_env ON runtime_projections (package_id, target_id, environment, status);
CREATE INDEX idx_rp_package ON runtime_projections (package_id);

-- -----------------------------------------------
-- runtime_projection_ddl_statements
-- -----------------------------------------------
CREATE TABLE IF NOT EXISTS runtime_projection_ddl_statements (
    id                       VARCHAR(26)  NOT NULL PRIMARY KEY,
    projection_id            VARCHAR(26)  NOT NULL,
    statement_id             VARCHAR(60)  NOT NULL,
    phase                    INT          NOT NULL,
    executor                 VARCHAR(30)  NOT NULL,
    dialect                  VARCHAR(30),
    dependency_statement_ids TEXT,
    table_contract_id        VARCHAR(26),
    table_contract_version   INT,
    idempotency_mode         VARCHAR(40),
    body                     TEXT         NOT NULL,
    sha256                   VARCHAR(64)  NOT NULL,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rpddl_projection ON runtime_projection_ddl_statements (projection_id);
