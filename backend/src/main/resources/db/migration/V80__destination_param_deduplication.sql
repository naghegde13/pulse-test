-- V77: Deduplicate destination blueprint params that are now inherited from
-- target connector instances selected in the sink registry UI.
--
-- Connection-level/platform fields stay on the connector instance, while
-- pipeline-specific write behavior remains editable on the blueprint instance.

-- 1. WarehouseWriter: remove warehouse_type, target_database, target_schema
--    Kept: target_table, write_mode, merge_keys, batch_size
UPDATE blueprints SET params_schema = '[
  {"name":"_inherited_from_connector","type":"metadata","description":"Platform and default warehouse location fields (warehouse_type, target_database, target_schema) are inherited from the linked destination connector instance.","inherited_fields":["warehouse_type","target_database","target_schema"]},
  {"name":"target_table","type":"string","required":true,"description":"Table name to write to"},
  {"name":"write_mode","type":"enum","options":["overwrite","append","merge"],"default":"append","description":"How to handle existing data"},
  {"name":"merge_keys","type":"string[]","description":"Columns to use as merge keys (required if write_mode is merge)"},
  {"name":"batch_size","type":"integer","default":10000,"description":"Number of rows per batch write"}
]'::jsonb
WHERE blueprint_key = 'WarehouseWriter';

-- 2. LakeWriter: remove lake_format
--    Kept: output_path, write_mode, partition_by, merge_keys, schema_evolution, compact_files
UPDATE blueprints SET params_schema = '[
  {"name":"_inherited_from_connector","type":"metadata","description":"Storage platform fields (lake_format) are inherited from the linked destination connector instance.","inherited_fields":["lake_format"]},
  {"name":"output_path","type":"string","required":true,"description":"Target path or URI in the data lake"},
  {"name":"write_mode","type":"enum","options":["overwrite","append","merge"],"default":"append","description":"How to handle existing data"},
  {"name":"partition_by","type":"string[]","description":"Columns to partition the output by"},
  {"name":"merge_keys","type":"string[]","description":"Columns to use as merge keys (required if write_mode is merge)"},
  {"name":"schema_evolution","type":"enum","options":["strict","add_columns","overwrite_schema"],"default":"add_columns","description":"How to handle schema changes"},
  {"name":"compact_files","type":"boolean","default":false,"description":"Run compaction after write to reduce small files"}
]'::jsonb
WHERE blueprint_key = 'LakeWriter';

-- 3. StreamWriter: remove bootstrap_servers
--    Kept: topic, serialization_format, key_column, delivery_guarantee, checkpoint_location
UPDATE blueprints SET params_schema = '[
  {"name":"_inherited_from_connector","type":"metadata","description":"Connection fields (bootstrap_servers) are inherited from the linked destination connector instance.","inherited_fields":["bootstrap_servers"]},
  {"name":"topic","type":"string","required":true,"description":"Kafka topic to write to"},
  {"name":"serialization_format","type":"enum","options":["json","avro","protobuf"],"default":"json","description":"Message serialization format"},
  {"name":"key_column","type":"string","description":"Column to use as the Kafka message key"},
  {"name":"delivery_guarantee","type":"enum","options":["at_least_once","exactly_once"],"default":"at_least_once","description":"Message delivery guarantee level"},
  {"name":"checkpoint_location","type":"string","description":"Checkpoint path for streaming write recovery"}
]'::jsonb
WHERE blueprint_key = 'StreamWriter';

-- 4. DatabaseWriter: remove database_type, jdbc_url
--    Kept: target_table, write_mode, upsert_keys, batch_size, connection_pool_size
UPDATE blueprints SET params_schema = '[
  {"name":"_inherited_from_connector","type":"metadata","description":"Platform and connection fields (database_type, jdbc_url) are inherited from the linked destination connector instance.","inherited_fields":["database_type","jdbc_url"]},
  {"name":"target_table","type":"string","required":true,"description":"Table name to write to"},
  {"name":"write_mode","type":"enum","options":["overwrite","append","upsert"],"default":"append","description":"How to handle existing data"},
  {"name":"upsert_keys","type":"string[]","description":"Columns to use as upsert keys (required if write_mode is upsert)"},
  {"name":"batch_size","type":"integer","default":5000,"description":"Number of rows per batch insert"},
  {"name":"connection_pool_size","type":"integer","default":5,"description":"JDBC connection pool size"}
]'::jsonb
WHERE blueprint_key = 'DatabaseWriter';

-- Seed a minimal target registry for the sample tenant so sink selection can use
-- connector instances instead of raw connector definitions.
INSERT INTO systems_of_record (id, tenant_id, name, description, domain_name, owner_id, metadata)
VALUES (
  '01JSOR0TARGETREG0000001',
  'tenant-home-lending',
  'Published Data Targets',
  'Registered warehouse, lake, stream, and database publish targets for tenant-home-lending',
  'Capital Markets',
  '01JUSER00000000000000000',
  '{"registry_type":"TARGET"}'::jsonb
);

INSERT INTO connector_instances (id, sor_id, connector_definition_id, name, description, config_template) VALUES
('01JCI0TGT0SNOWFLAKE0001', '01JSOR0TARGETREG0000001', '01JCONN0DST0SNOWFLAKE001', 'Snowflake Analytics', 'Tenant analytics warehouse publish target', '{"database":"ANALYTICS","schema":"PUBLISHED","warehouse":"COMPUTE_WH"}'),
('01JCI0TGT0S3000000000001', '01JSOR0TARGETREG0000001', '01JCONN0DST0S3000000001', 'Published S3 Lake', 'Published parquet export target', '{"bucket":"acme-published-data","path_prefix":"gold/published/"}'),
('01JCI0TGT0KAFKA000000001', '01JSOR0TARGETREG0000001', '01JCONN0DST0KAFKA00000001', 'Published Events Kafka', 'Published event fan-out target', '{"topic":"published.events"}'),
('01JCI0TGT0POSTGRES000001', '01JSOR0TARGETREG0000001', '01JCONN0DST0POSTGRES0001', 'Operational PostgreSQL Serving', 'Serving database publish target', '{"database":"serving","schema":"public"}');
