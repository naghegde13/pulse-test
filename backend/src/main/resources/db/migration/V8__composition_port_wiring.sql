-- V8: Upgrade sub_pipeline_instances for blueprint catalog FK + add port_wirings table.

-- Add blueprint_key column referencing the catalog
ALTER TABLE sub_pipeline_instances ADD COLUMN blueprint_key VARCHAR(100);

-- Backfill blueprint_key from existing blueprint_id (they already match keys)
UPDATE sub_pipeline_instances SET blueprint_key = blueprint_id;

-- Port wiring table: connects output port of one instance to input port of another
CREATE TABLE port_wirings (
    id                    VARCHAR(26) PRIMARY KEY,
    version_id            VARCHAR(26) NOT NULL,
    source_instance_id    VARCHAR(26) NOT NULL REFERENCES sub_pipeline_instances(id) ON DELETE CASCADE,
    source_port_name      VARCHAR(100) NOT NULL,
    target_instance_id    VARCHAR(26) NOT NULL REFERENCES sub_pipeline_instances(id) ON DELETE CASCADE,
    target_port_name      VARCHAR(100) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wiring UNIQUE (version_id, target_instance_id, target_port_name)
);

CREATE INDEX idx_wiring_version ON port_wirings(version_id);
CREATE INDEX idx_wiring_source ON port_wirings(source_instance_id);
CREATE INDEX idx_wiring_target ON port_wirings(target_instance_id);

-- Backfill port wirings from existing seed data (sequential chain)
-- Instance 1 (ApiIngestion) output -> Instance 2 (SchemaNormalization) input
INSERT INTO port_wirings (id, version_id, source_instance_id, source_port_name, target_instance_id, target_port_name)
VALUES ('01JWIRE0SALES0001TO002001', '01JVER0SALES0REV0V100001',
        '01JSUB0SALESFORCE0ING001', 'api_output',
        '01JSUB0CURRENCY0NORM0001', 'source_data');

-- Instance 2 (SchemaNormalization) output -> Instance 3 (DQValidator) input
INSERT INTO port_wirings (id, version_id, source_instance_id, source_port_name, target_instance_id, target_port_name)
VALUES ('01JWIRE0SALES0002TO003001', '01JVER0SALES0REV0V100001',
        '01JSUB0CURRENCY0NORM0001', 'normalized_output',
        '01JSUB0FRAUD0FILTER00001', 'data_to_validate');

-- Instance 3 (DQValidator) validated_output -> Instance 4 (FactBuild) input
INSERT INTO port_wirings (id, version_id, source_instance_id, source_port_name, target_instance_id, target_port_name)
VALUES ('01JWIRE0SALES0003TO004001', '01JVER0SALES0REV0V100001',
        '01JSUB0FRAUD0FILTER00001', 'validated_output',
        '01JSUB0REVENUE0FACT00001', 'transaction_data');
