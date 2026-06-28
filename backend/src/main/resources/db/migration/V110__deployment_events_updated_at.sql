-- V110: Align deployment_events with BaseEntity.
-- V106 introduced deployment_events as an append-only audit table, but
-- DeploymentEvent extends BaseEntity and Hibernate validates updated_at.
ALTER TABLE deployment_events
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
