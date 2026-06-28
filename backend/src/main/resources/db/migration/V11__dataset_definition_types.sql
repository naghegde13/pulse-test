-- V11: Extend datasets with definition type and configuration

ALTER TABLE datasets ADD COLUMN IF NOT EXISTS definition_type VARCHAR(50);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS definition_config JSONB DEFAULT '{}';
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS custom_sql TEXT;
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS source_tables JSONB DEFAULT '[]';
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS api_spec JSONB;
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS status VARCHAR(30) DEFAULT 'DRAFT';

COMMENT ON COLUMN datasets.definition_type IS 'TABLE_SELECTION, CUSTOM_SQL, FILE_INFERENCE, SCHEMA_UPLOAD, SCHEMA_REGISTRY, API_SPEC_IMPORT, OBJECT_SELECTION, SAMPLE_INFERENCE, MANUAL_DEFINITION';
COMMENT ON COLUMN datasets.definition_config IS 'Type-specific config: file patterns, registry URLs, table filters, etc.';
COMMENT ON COLUMN datasets.custom_sql IS 'User-written SQL query for CUSTOM_SQL definition type';
COMMENT ON COLUMN datasets.source_tables IS 'Array of selected table/object names for TABLE_SELECTION/OBJECT_SELECTION';
COMMENT ON COLUMN datasets.api_spec IS 'OpenAPI spec or endpoint definition for API_SPEC_IMPORT';
COMMENT ON COLUMN datasets.status IS 'DRAFT, SCHEMA_DEFINED, VALIDATED';

-- Backfill existing datasets
UPDATE datasets SET definition_type = 'TABLE_SELECTION', status = 'SCHEMA_DEFINED'
WHERE definition_type IS NULL AND schema_format IN ('DDL');

UPDATE datasets SET definition_type = 'SCHEMA_UPLOAD', status = 'SCHEMA_DEFINED'
WHERE definition_type IS NULL AND schema_format IN ('AVRO', 'JSON_SCHEMA', 'PARQUET_SCHEMA');
