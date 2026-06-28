-- PKT-0026: Schema Discovery Command Surface
-- Adds dataset-level columns for discovery provenance and as-of column binding.

ALTER TABLE datasets ADD COLUMN IF NOT EXISTS asof_column_name VARCHAR(255);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS discovery_method VARCHAR(50);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS discovery_proof JSONB;

COMMENT ON COLUMN datasets.asof_column_name IS 'Column in the source table used as the business as-of date dimension';
COMMENT ON COLUMN datasets.discovery_method IS 'How the schema was discovered: TABLE_DISCOVERY, QUERY_DISCOVERY, SAMPLE_UPLOAD, MANUAL';
COMMENT ON COLUMN datasets.discovery_proof IS 'Provenance metadata from schema discovery: sample rows hash, query hash, table name, discovery timestamp';
