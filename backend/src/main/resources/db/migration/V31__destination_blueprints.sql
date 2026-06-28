-- V31: Destination (sink) blueprints for pipeline output targets.
-- 4 blueprint patterns covering all 9 destination connectors:
--   WarehouseWriter  -> Snowflake, BigQuery, Redshift
--   LakeWriter       -> S3, Delta Lake, Iceberg
--   StreamWriter     -> Kafka
--   DatabaseWriter   -> PostgreSQL, Databricks

-- =====================================================
-- DESTINATION (4)
-- =====================================================

INSERT INTO blueprints (id, blueprint_key, name, description, category, version, params_schema, input_ports, output_ports, deferred, pipeline_config) VALUES

('01JBP0DEST0WAREHOUSE00001', 'WarehouseWriter', 'Warehouse Writer',
 'Writes data to a cloud data warehouse like Snowflake, BigQuery, or Redshift. Supports full-load and incremental write modes with options for merge keys and write disposition.',
 'DESTINATION', '1.0.0',
 '[{"name":"warehouse_type","type":"enum","options":["snowflake","bigquery","redshift"],"required":true,"description":"Target warehouse platform"},{"name":"target_database","type":"string","required":true,"description":"Database name to write to"},{"name":"target_schema","type":"string","required":true,"description":"Schema name to write to"},{"name":"target_table","type":"string","required":true,"description":"Table name to write to"},{"name":"write_mode","type":"enum","options":["overwrite","append","merge"],"default":"append","description":"How to handle existing data"},{"name":"merge_keys","type":"string[]","description":"Columns to use as merge keys (required if write_mode is merge)"},{"name":"batch_size","type":"integer","default":10000,"description":"Number of rows per batch write"}]',
 '[{"name":"data_input","description":"Data to write to the warehouse"}]',
 '[]',
 false, false),

('01JBP0DEST0LAKE0000000001', 'LakeWriter', 'Lake Writer',
 'Writes data to a data lake in formats like Delta Lake, Apache Iceberg, or Parquet on S3 and other object stores. Supports partitioning, schema evolution, and compaction options.',
 'DESTINATION', '1.0.0',
 '[{"name":"lake_format","type":"enum","options":["delta","iceberg","parquet"],"required":true,"description":"Output storage format"},{"name":"output_path","type":"string","required":true,"description":"Target path or URI in the data lake"},{"name":"write_mode","type":"enum","options":["overwrite","append","merge"],"default":"append","description":"How to handle existing data"},{"name":"partition_by","type":"string[]","description":"Columns to partition the output by"},{"name":"merge_keys","type":"string[]","description":"Columns to use as merge keys (required if write_mode is merge)"},{"name":"schema_evolution","type":"enum","options":["strict","add_columns","overwrite_schema"],"default":"add_columns","description":"How to handle schema changes"},{"name":"compact_files","type":"boolean","default":false,"description":"Run compaction after write to reduce small files"}]',
 '[{"name":"data_input","description":"Data to write to the data lake"}]',
 '[]',
 false, false),

('01JBP0DEST0STREAM00000001', 'StreamWriter', 'Stream Writer',
 'Publishes data to a streaming platform like Apache Kafka. Supports configurable serialization, partitioning, and delivery guarantees for real-time data pipelines.',
 'DESTINATION', '1.0.0',
 '[{"name":"bootstrap_servers","type":"string","required":true,"description":"Kafka bootstrap server addresses"},{"name":"topic","type":"string","required":true,"description":"Kafka topic to write to"},{"name":"serialization_format","type":"enum","options":["json","avro","protobuf"],"default":"json","description":"Message serialization format"},{"name":"key_column","type":"string","description":"Column to use as the Kafka message key"},{"name":"delivery_guarantee","type":"enum","options":["at_least_once","exactly_once"],"default":"at_least_once","description":"Message delivery guarantee level"},{"name":"checkpoint_location","type":"string","description":"Checkpoint path for streaming write recovery"}]',
 '[{"name":"data_input","description":"Data to publish to the stream"}]',
 '[]',
 false, false),

('01JBP0DEST0DATABASE000001', 'DatabaseWriter', 'Database Writer',
 'Writes data to a relational database like PostgreSQL or Databricks via JDBC. Supports batch inserts, upserts, and configurable connection pooling for reliable delivery.',
 'DESTINATION', '1.0.0',
 '[{"name":"database_type","type":"enum","options":["postgresql","databricks"],"required":true,"description":"Target database platform"},{"name":"jdbc_url","type":"string","required":true,"description":"JDBC connection URL"},{"name":"target_table","type":"string","required":true,"description":"Table name to write to"},{"name":"write_mode","type":"enum","options":["overwrite","append","upsert"],"default":"append","description":"How to handle existing data"},{"name":"upsert_keys","type":"string[]","description":"Columns to use as upsert keys (required if write_mode is upsert)"},{"name":"batch_size","type":"integer","default":5000,"description":"Number of rows per batch insert"},{"name":"connection_pool_size","type":"integer","default":5,"description":"JDBC connection pool size"}]',
 '[{"name":"data_input","description":"Data to write to the database"}]',
 '[]',
 false, false);
