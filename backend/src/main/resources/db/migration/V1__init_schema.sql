-- PULSE Platform Schema v1
-- Milestone 0: Core tables for tenants, users, pipelines, command bus

-- Tenants
CREATE TABLE tenants (
    id          VARCHAR(26) PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    slug        VARCHAR(100) NOT NULL UNIQUE,
    repo_url    VARCHAR(500),
    metadata    JSONB DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Users
CREATE TABLE users (
    id           VARCHAR(26) PRIMARY KEY,
    email        VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    role         VARCHAR(50) NOT NULL,
    tenant_id    VARCHAR(26) NOT NULL REFERENCES tenants(id),
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_tenant ON users(tenant_id);
CREATE INDEX idx_users_email ON users(email);

-- Pipelines (Level 1: Business Pipeline)
CREATE TABLE pipelines (
    id              VARCHAR(26) PRIMARY KEY,
    tenant_id       VARCHAR(26) NOT NULL REFERENCES tenants(id),
    domain_name     VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    lifecycle_stage VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    created_by      VARCHAR(26) NOT NULL REFERENCES users(id),
    current_version VARCHAR(20),
    sla_config      JSONB DEFAULT '{}',
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pipelines_tenant ON pipelines(tenant_id);
CREATE INDEX idx_pipelines_domain ON pipelines(tenant_id, domain_name);
CREATE INDEX idx_pipelines_stage ON pipelines(lifecycle_stage);

-- Sub-pipeline instances (Level 2: Blueprint instances)
CREATE TABLE sub_pipeline_instances (
    id                VARCHAR(26) PRIMARY KEY,
    pipeline_id       VARCHAR(26) NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    blueprint_id      VARCHAR(255) NOT NULL,
    blueprint_version VARCHAR(20) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    execution_order   INTEGER NOT NULL,
    params            JSONB DEFAULT '{}',
    input_datasets    JSONB DEFAULT '[]',
    output_datasets   JSONB DEFAULT '[]',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sub_pipelines_pipeline ON sub_pipeline_instances(pipeline_id);

-- Plans (ordered list of commands; requires preview + apply)
CREATE TABLE plans (
    id            VARCHAR(26) PRIMARY KEY,
    tenant_id     VARCHAR(26) NOT NULL REFERENCES tenants(id),
    pipeline_id   VARCHAR(26) REFERENCES pipelines(id),
    actor_id      VARCHAR(26) NOT NULL REFERENCES users(id),
    description   TEXT NOT NULL,
    status        VARCHAR(30) NOT NULL DEFAULT 'PREVIEW',
    preview_data  JSONB DEFAULT '{}',
    command_ids   JSONB DEFAULT '[]',
    applied_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_plans_pipeline ON plans(pipeline_id);
CREATE INDEX idx_plans_status ON plans(tenant_id, status);

-- Command log (append-only audit trail)
CREATE TABLE command_log (
    id              VARCHAR(26) PRIMARY KEY,
    plan_id         VARCHAR(26) REFERENCES plans(id),
    command_type    VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(26) NOT NULL,
    tenant_id       VARCHAR(26) NOT NULL REFERENCES tenants(id),
    actor_id        VARCHAR(26) NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    payload         JSONB DEFAULT '{}',
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    error_message   TEXT,
    executed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cmd_log_plan ON command_log(plan_id);
CREATE INDEX idx_cmd_log_aggregate ON command_log(aggregate_id);
CREATE INDEX idx_cmd_log_tenant ON command_log(tenant_id);
CREATE INDEX idx_cmd_log_idempotency ON command_log(idempotency_key);
CREATE INDEX idx_cmd_log_type ON command_log(command_type);

-- Seed default tenant
INSERT INTO tenants (id, name, slug) VALUES ('01JDEFAULT000000000000000', 'Default Tenant', 'default');

-- Seed stub user for dev
INSERT INTO users (id, email, display_name, role, tenant_id) 
VALUES ('01JUSER00000000000000000', 'builder@pulse.dev', 'Dev Builder', 'DATA_ENGINEER', '01JDEFAULT000000000000000');
