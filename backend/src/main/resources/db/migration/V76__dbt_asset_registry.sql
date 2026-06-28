-- Gate 5 foundation: dbt asset registry for reuse planning/compilation

CREATE TABLE IF NOT EXISTS dbt_assets (
    id                VARCHAR(26) PRIMARY KEY,
    domain_id         VARCHAR(26) NOT NULL REFERENCES domains(id) ON DELETE CASCADE,
    project_name      VARCHAR(255) NOT NULL,
    asset_name        VARCHAR(255) NOT NULL,
    asset_type        VARCHAR(50) NOT NULL,
    path              VARCHAR(1000) NOT NULL,
    tags              JSONB NOT NULL DEFAULT '[]'::jsonb,
    group_name        VARCHAR(255),
    access_level      VARCHAR(30),
    grain             VARCHAR(255),
    business_concept  VARCHAR(255),
    schema_signature  VARCHAR(255),
    description       TEXT,
    metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dbt_assets_domain ON dbt_assets(domain_id);
CREATE INDEX IF NOT EXISTS idx_dbt_assets_type ON dbt_assets(asset_type);
CREATE INDEX IF NOT EXISTS idx_dbt_assets_concept ON dbt_assets(domain_id, business_concept);
CREATE UNIQUE INDEX IF NOT EXISTS uq_dbt_assets_domain_name_type
    ON dbt_assets(domain_id, asset_name, asset_type);
