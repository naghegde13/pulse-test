-- V69: Decouple datasets from connectors.
-- A dataset belongs to an SOR (via sor_id) and can optionally be linked to
-- one or more connectors through a join table.

-- 1. Add sor_id to datasets (direct ownership)
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS sor_id VARCHAR(26) REFERENCES systems_of_record(id);

-- Backfill sor_id from existing connector_instance_id
UPDATE datasets d
SET sor_id = ci.sor_id
FROM connector_instances ci
WHERE d.connector_instance_id = ci.id
  AND d.sor_id IS NULL;

-- 2. Make connector_instance_id nullable (datasets can exist without a connector)
ALTER TABLE datasets ALTER COLUMN connector_instance_id DROP NOT NULL;

-- 3. Join table for many-to-many: dataset <-> connector
CREATE TABLE IF NOT EXISTS dataset_connector_map (
    id                    VARCHAR(26) PRIMARY KEY,
    dataset_id            VARCHAR(26) NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
    connector_instance_id VARCHAR(26) NOT NULL REFERENCES connector_instances(id) ON DELETE CASCADE,
    is_primary            BOOLEAN NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_dataset_connector UNIQUE (dataset_id, connector_instance_id)
);
CREATE INDEX idx_dcm_dataset ON dataset_connector_map(dataset_id);
CREATE INDEX idx_dcm_connector ON dataset_connector_map(connector_instance_id);

-- 4. Seed the join table from existing relationships
INSERT INTO dataset_connector_map (id, dataset_id, connector_instance_id, is_primary)
SELECT
    LEFT(CONCAT('01MAP', SUBSTRING(d.id FROM 6), SUBSTRING(d.connector_instance_id FROM 6)), 26),
    d.id,
    d.connector_instance_id,
    TRUE
FROM datasets d
WHERE d.connector_instance_id IS NOT NULL
ON CONFLICT (dataset_id, connector_instance_id) DO NOTHING;
