-- V111: Align deployment_evidence with BaseEntity.
-- V106 introduced deployment_evidence without updated_at, but
-- DeploymentEvidence extends BaseEntity and Hibernate validates the
-- inherited column.
ALTER TABLE deployment_evidence
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
