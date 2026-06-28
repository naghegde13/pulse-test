-- PKT-0011: Tenant GCP configuration — stores per-tenant GCP project/region settings.
-- Each tenant can configure exactly one GCP project binding at a time.

CREATE TABLE IF NOT EXISTS tenant_gcp_configs (
    id              VARCHAR(26)   NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(26)   NOT NULL,
    gcp_project_id  VARCHAR(255)  NOT NULL,
    gcp_region      VARCHAR(100)  NOT NULL DEFAULT 'us-central1',
    status          VARCHAR(20)   NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_tgc_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT uq_tgc_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_tgc_status CHECK (status IN ('active', 'disabled'))
);
