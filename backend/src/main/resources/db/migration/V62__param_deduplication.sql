-- V62: Deduplicate blueprint params that overlap with connector configuration.
--
-- These 11 parameters are redundant because the same information is already
-- stored in connector_instances.config_template and/or connector_definitions.connection_spec.
-- After this migration, connection-level details are inherited from the linked
-- connector instance, and only pipeline-specific tuning parameters remain editable
-- in blueprint params_schema.
--
-- Affected blueprints and removed params:
--   ApiIngestion          : api_url, auth_type, pagination_type          (3)
--   StreamIngestion       : stream_type, topic, consumer_group           (3)
--   FileIngestion         : source_path, file_format                     (2)
--   CDCIngestion          : source_type                                  (1)
--   SnapshotIngestion     : source_table                                 (1)
--   EncryptedSourceIngest : encryption_type                              (1)
--                                                                  Total: 11

-- 1. ApiIngestion: remove api_url, auth_type, pagination_type
--    Kept: rate_limit_rpm, incremental_field
UPDATE blueprints SET params_schema = '[
  {"name":"_inherited_from_connector","type":"metadata","description":"Connection parameters (api_url, auth_type, pagination_type) are inherited from the linked connector instance and do not need to be configured here.","inherited_fields":["api_url","auth_type","pagination_type"]},
  {"name":"rate_limit_rpm","type":"integer","default":60,"description":"Maximum API requests per minute"},
  {"name":"incremental_field","type":"string","description":"Field to use for incremental extraction"}
]'::jsonb
WHERE blueprint_key = 'ApiIngestion';

-- 2. StreamIngestion: remove stream_type, topic, consumer_group
--    Kept: batch_window_seconds
UPDATE blueprints SET params_schema = '[
  {"name":"_inherited_from_connector","type":"metadata","description":"Connection parameters (stream_type, topic, consumer_group) are inherited from the linked connector instance and do not need to be configured here.","inherited_fields":["stream_type","topic","consumer_group"]},
  {"name":"batch_window_seconds","type":"integer","default":60,"description":"Micro-batch window size in seconds for stream processing"}
]'::jsonb
WHERE blueprint_key = 'StreamIngestion';

-- 3. FileIngestion: remove source_path, file_format
--    Kept: delimiter, has_header, partition_by
UPDATE blueprints SET params_schema = '[
  {"name":"_inherited_from_connector","type":"metadata","description":"Connection parameters (source_path, file_format) are inherited from the linked connector instance and do not need to be configured here.","inherited_fields":["source_path","file_format"]},
  {"name":"delimiter","type":"string","default":",","description":"Column delimiter for CSV files"},
  {"name":"has_header","type":"boolean","default":true,"description":"Whether the file has a header row"},
  {"name":"partition_by","type":"string","default":"ingestion_date","description":"Column to partition the output data by"}
]'::jsonb
WHERE blueprint_key = 'FileIngestion';

-- 4. CDCIngestion: remove source_type
--    Kept: tables, initial_snapshot
UPDATE blueprints SET params_schema = '[
  {"name":"_inherited_from_connector","type":"metadata","description":"Connection parameters (source_type) are inherited from the linked connector instance and do not need to be configured here.","inherited_fields":["source_type"]},
  {"name":"tables","type":"string[]","required":true,"description":"Tables to capture changes from"},
  {"name":"initial_snapshot","type":"boolean","default":true,"description":"Whether to take an initial full snapshot before streaming changes"}
]'::jsonb
WHERE blueprint_key = 'CDCIngestion';

-- 5. SnapshotIngestion: remove source_table
--    Kept: snapshot_frequency, compare_key
UPDATE blueprints SET params_schema = '[
  {"name":"_inherited_from_connector","type":"metadata","description":"Connection parameters (source_table) are inherited from the linked connector instance (resolved via dataset qualified name) and do not need to be configured here.","inherited_fields":["source_table"]},
  {"name":"snapshot_frequency","type":"enum","options":["daily","weekly","monthly"],"default":"daily","description":"How often to take a snapshot of the source data"},
  {"name":"compare_key","type":"string","description":"Column(s) to detect changes between snapshots"}
]'::jsonb
WHERE blueprint_key = 'SnapshotIngestion';

-- 6. EncryptedSourceIngest: remove encryption_type
--    Kept: key_ref
UPDATE blueprints SET params_schema = '[
  {"name":"_inherited_from_connector","type":"metadata","description":"Connection parameters (encryption_type) are inherited from the linked connector instance and do not need to be configured here.","inherited_fields":["encryption_type"]},
  {"name":"key_ref","type":"string","required":true,"description":"Reference to decryption key in secrets vault"}
]'::jsonb
WHERE blueprint_key = 'EncryptedSourceIngest';
