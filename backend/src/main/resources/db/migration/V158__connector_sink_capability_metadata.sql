-- V158 (Wave G / LCT-045, LCT-048): Connector sink capability metadata.
--
-- BACKGROUND
-- ----------
-- The Add-Sink dialog derived a sink blueprint by substring-matching the
-- connector name (mapConnectorToBlueprint) and fell through to LakeWriter for
-- anything unmatched, so a MongoDB DESTINATION connector was rendered as a lake
-- writer asking for an S3 path (LCT-045). It also hardcoded a generic
-- ["append","overwrite","merge"] write-mode list that matched no blueprint's
-- actual write_mode enum and ignored connector-family semantics (LCT-048).
--
-- This migration adds DECLARED capability metadata to connector_definitions so
-- the UI is driven by connector authority instead of name heuristics:
--   * sink_writer_kind   — LAKE | WAREHOUSE | RELATIONAL | DOCUMENT | STREAM
--   * write_dispositions — ordered list of {value,label}; `value` is the
--                          blueprint-legal write-mode token (see V95), `label`
--                          is connector-family-appropriate copy. Default first.
--
-- Only DESTINATION definitions are classified. SOURCE definitions keep NULL
-- (no sink semantics). Values are kept blueprint-legal so saved sub-pipeline
-- instances remain valid for codegen:
--   LakeWriter      write_mode ∈ {overwrite, append_partition, merge_on_pk}
--   WarehouseWriter write_mode ∈ {overwrite_partition, append, merge_on_pk}
--   DatabaseWriter  write_mode ∈ {overwrite_partition, append, merge_on_pk}
--   StreamWriter    publish_mode ∈ {batch_publish, streaming_publish}

ALTER TABLE connector_definitions
    ADD COLUMN sink_writer_kind VARCHAR(40);

ALTER TABLE connector_definitions
    ADD COLUMN write_dispositions JSONB;

-- ---------------------------------------------------------------------
-- LAKE — S3-compatible Object Storage (destination). LakeWriter blueprint.
-- ---------------------------------------------------------------------
UPDATE connector_definitions
SET sink_writer_kind = 'LAKE',
    write_dispositions = '[
        {"value":"merge_on_pk","label":"Merge on keys (upsert)"},
        {"value":"append_partition","label":"Append partition"},
        {"value":"overwrite","label":"Overwrite"}
    ]'::jsonb
WHERE id = '01JCONN0DST0S3000000001';

-- ---------------------------------------------------------------------
-- WAREHOUSE — Snowflake, BigQuery. WarehouseWriter blueprint.
-- ---------------------------------------------------------------------
UPDATE connector_definitions
SET sink_writer_kind = 'WAREHOUSE',
    write_dispositions = '[
        {"value":"overwrite_partition","label":"Overwrite partition"},
        {"value":"append","label":"Append"},
        {"value":"merge_on_pk","label":"Merge on keys (upsert)"}
    ]'::jsonb
WHERE id IN ('01JCONN0DST0SNOWFLAKE001', '01JCONN0DST0BIGQUERY0001');

-- ---------------------------------------------------------------------
-- RELATIONAL — Postgres, MySQL, Oracle, MS SQL Server. DatabaseWriter blueprint.
-- ---------------------------------------------------------------------
UPDATE connector_definitions
SET sink_writer_kind = 'RELATIONAL',
    write_dispositions = '[
        {"value":"append","label":"Append"},
        {"value":"merge_on_pk","label":"Upsert / merge on keys"},
        {"value":"overwrite_partition","label":"Truncate & reload"}
    ]'::jsonb
WHERE id IN (
    '01JCONN0DST0POSTGRES0001',
    '01JCONN0DST0MYSQL0000001',
    '01JCONN0DST0ORACLE000001',
    '01JCONN0DST0MSSQL0000001'
);

-- ---------------------------------------------------------------------
-- DOCUMENT — MongoDB. DatabaseWriter blueprint with a database/collection
-- target (NOT a lake path). Mongo semantics mapped onto blueprint-legal
-- write_mode tokens: append=insert, merge_on_pk=upsert by key,
-- overwrite_partition=replace collection.
-- ---------------------------------------------------------------------
UPDATE connector_definitions
SET sink_writer_kind = 'DOCUMENT',
    write_dispositions = '[
        {"value":"append","label":"Insert documents"},
        {"value":"merge_on_pk","label":"Upsert by key"},
        {"value":"overwrite_partition","label":"Replace collection"}
    ]'::jsonb
WHERE id = '01JCONN0DST0MONGODB00001';

-- ---------------------------------------------------------------------
-- STREAM — Kafka. StreamWriter blueprint (publish_mode, not write_mode).
-- ---------------------------------------------------------------------
UPDATE connector_definitions
SET sink_writer_kind = 'STREAM',
    write_dispositions = '[
        {"value":"batch_publish","label":"Batch publish"},
        {"value":"streaming_publish","label":"Streaming publish"}
    ]'::jsonb
WHERE id = '01JCONN0DST0KAFKA00000001';

-- ---------------------------------------------------------------------
-- Defensive backfill: any remaining DESTINATION definition without a declared
-- writer kind defaults to RELATIONAL (DatabaseWriter) so the sink UI never
-- silently falls back to lake-path semantics for an unknown destination.
-- ---------------------------------------------------------------------
UPDATE connector_definitions
SET sink_writer_kind = 'RELATIONAL',
    write_dispositions = '[
        {"value":"append","label":"Append"},
        {"value":"merge_on_pk","label":"Upsert / merge on keys"},
        {"value":"overwrite_partition","label":"Truncate & reload"}
    ]'::jsonb
WHERE connector_type = 'DESTINATION' AND sink_writer_kind IS NULL;
