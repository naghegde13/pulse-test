-- WS-6: Packaging & Deployment

CREATE TABLE packages (
    id                  VARCHAR(26) PRIMARY KEY,
    pipeline_id         VARCHAR(26) NOT NULL REFERENCES pipelines(id),
    version_id          VARCHAR(26) NOT NULL REFERENCES pipeline_versions(id),
    tenant_id           VARCHAR(26) NOT NULL,
    package_type        VARCHAR(30) NOT NULL DEFAULT 'DOCKER',
    artifact_url        VARCHAR(500),
    artifact_hash       VARCHAR(64),
    build_status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    built_by            VARCHAR(26) NOT NULL,
    build_log           TEXT,
    built_at            TIMESTAMPTZ,
    metadata            JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE deployment_targets (
    id              VARCHAR(26) PRIMARY KEY,
    tenant_id       VARCHAR(26) NOT NULL,
    name            VARCHAR(100) NOT NULL,
    environment     VARCHAR(20) NOT NULL,
    target_type     VARCHAR(30) NOT NULL DEFAULT 'KUBERNETES',
    endpoint_url    VARCHAR(500),
    config          JSONB DEFAULT '{}',
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE deployments (
    id                  VARCHAR(26) PRIMARY KEY,
    package_id          VARCHAR(26) NOT NULL REFERENCES packages(id),
    target_id           VARCHAR(26) NOT NULL REFERENCES deployment_targets(id),
    pipeline_id         VARCHAR(26) NOT NULL REFERENCES pipelines(id),
    version_id          VARCHAR(26) NOT NULL REFERENCES pipeline_versions(id),
    tenant_id           VARCHAR(26) NOT NULL,
    deployed_by         VARCHAR(26) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    deploy_log          TEXT,
    deployed_at         TIMESTAMPTZ,
    rolled_back_at      TIMESTAMPTZ,
    metadata            JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE approval_requests (
    id              VARCHAR(26) PRIMARY KEY,
    deployment_id   VARCHAR(26) NOT NULL REFERENCES deployments(id),
    tenant_id       VARCHAR(26) NOT NULL,
    requested_by    VARCHAR(26) NOT NULL,
    approved_by     VARCHAR(26),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reason          TEXT,
    decided_at      TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_packages_pipeline ON packages(pipeline_id);
CREATE INDEX idx_packages_version ON packages(version_id);
CREATE INDEX idx_deploy_targets_tenant ON deployment_targets(tenant_id);
CREATE INDEX idx_deployments_package ON deployments(package_id);
CREATE INDEX idx_deployments_pipeline ON deployments(pipeline_id);
CREATE INDEX idx_deployments_tenant ON deployments(tenant_id);
CREATE INDEX idx_approvals_deployment ON approval_requests(deployment_id);
CREATE INDEX idx_approvals_status ON approval_requests(status);

COMMENT ON COLUMN packages.package_type IS 'DOCKER, TARBALL, WHEEL, ARTIFACT_BUNDLE';
COMMENT ON COLUMN packages.build_status IS 'PENDING, BUILDING, COMPLETED, FAILED';
COMMENT ON COLUMN deployment_targets.environment IS 'DEV, INTEGRATION, UAT, PRODUCTION';
COMMENT ON COLUMN deployment_targets.target_type IS 'KUBERNETES, AIRFLOW, DATABRICKS, EMR, DATAPROC';
COMMENT ON COLUMN deployments.status IS 'PENDING, DEPLOYING, DEPLOYED, FAILED, ROLLED_BACK';
COMMENT ON COLUMN approval_requests.status IS 'PENDING, APPROVED, REJECTED, EXPIRED';
