-- V95: Add target / connector_instance binding fields to the 4 DESTINATION
-- blueprints (#47).
--
-- Background: the sink-add-dialog already enforces a 3-step flow
-- (target → connector_instance → configure) and writes
-- connector_instance_id + connector_name to SubPipelineInstance.params.
-- But the destination blueprints' params_schema didn't declare those
-- fields, so the agent / chat path had NO schema signal that a
-- Target + ConnectorInstance was required to configure a sink. This
-- migration brings the schema in line with what the wizard already
-- writes, and adds an explicit target_credential_ref parallel to V93's
-- auth_credential_ref on ApiIngestion.
--
-- Affected blueprints:
--   - DatabaseWriter   6 → 10 fields
--   - LakeWriter       6 → 10 fields
--   - StreamWriter     6 → 10 fields
--   - WarehouseWriter  5 →  9 fields
--
-- After V95, the codegen substitution layer can resolve the
-- __DEST_NAME__ token used in the sink codegen examples
-- (sinks/database_postgres.py, sinks/lake_*.py, sinks/stream_kafka.py,
-- sinks/warehouse_bigquery.py) from the bound connector_instance's
-- credential prefix.

-- ---------------------------------------------------------------------
-- DatabaseWriter — gold → operational Postgres / MySQL / MSSQL.
-- ---------------------------------------------------------------------
UPDATE blueprints
SET params_schema = '[
    {
        "name": "target_id",
        "type": "string",
        "required": true,
        "description": "Sink target SoR id. Reference to SystemOfRecord with metadata.registry_type=''TARGET'', listed at /api/v1/tenants/{tenantId}/targets."
    },
    {
        "name": "connector_instance_id",
        "type": "string",
        "required": true,
        "description": "ConnectorInstance attached to the chosen target. Listed at /api/v1/sors/{target_id}/connectors. Drives the __DEST_NAME__ credential prefix at codegen time."
    },
    {
        "name": "connector_name",
        "type": "string",
        "required": false,
        "description": "Display name of the chosen connector instance. Auto-populated by the sink-add wizard; agent path may leave blank."
    },
    {
        "name": "target_credential_ref",
        "type": "string",
        "required": false,
        "description": "Optional gcp-sm:// reference to override the connector instance''s default credential binding. Resolved at runtime by the secret manager."
    },
    {"name": "target_table", "type": "string", "required": true,
     "description": "Fully-qualified destination table, e.g. analytics.fct_orders."},
    {"name": "write_mode", "type": "enum", "default": "append",
     "options": ["overwrite_partition", "append", "merge_on_pk"], "required": false,
     "description": "overwrite_partition deletes today''s partition then inserts (idempotent on re-run). merge_on_pk uses INSERT ... ON CONFLICT keyed on upsert_keys."},
    {"name": "upsert_keys", "type": "string[]", "required": false,
     "description": "Required when write_mode=merge_on_pk."},
    {"name": "batch_size", "type": "integer", "default": 5000, "required": false,
     "description": "JDBC batchsize. 5000 is the practical lower bound for Postgres; smaller and round-trip overhead dominates."},
    {"name": "connection_pool_size", "type": "integer", "default": 5, "required": false},
    {"name": "partition_by", "type": "string[]", "required": false,
     "description": "Destination partition columns (must already exist on the destination table)."}
]'::jsonb,
updated_at = NOW()
WHERE blueprint_key = 'DatabaseWriter';

-- ---------------------------------------------------------------------
-- LakeWriter — gold → Delta / Iceberg cross-bucket publish.
-- ---------------------------------------------------------------------
UPDATE blueprints
SET params_schema = '[
    {
        "name": "target_id",
        "type": "string",
        "required": true,
        "description": "Sink target SoR id (the consuming bucket / lake registry)."
    },
    {
        "name": "connector_instance_id",
        "type": "string",
        "required": true,
        "description": "ConnectorInstance attached to the chosen target. Drives __DEST_NAME__ credential prefix and S3/GCS/Iceberg-catalog wiring."
    },
    {
        "name": "connector_name",
        "type": "string",
        "required": false,
        "description": "Display name of the chosen connector instance."
    },
    {
        "name": "target_credential_ref",
        "type": "string",
        "required": false,
        "description": "Optional gcp-sm:// override for the connector instance''s default credentials."
    },
    {"name": "lake_format", "type": "enum", "default": "delta",
     "options": ["delta", "iceberg"], "required": true,
     "description": "Selects which codegen variant emits — sinks/lake_delta.py vs sinks/lake_iceberg.py."},
    {"name": "output_path", "type": "string", "required": true,
     "description": "Destination path (s3a://... for Delta, catalog.namespace.table for Iceberg)."},
    {"name": "write_mode", "type": "enum", "default": "merge_on_pk",
     "options": ["overwrite", "append_partition", "merge_on_pk"], "required": false,
     "description": "merge_on_pk uses DeltaTable.merge / Iceberg MERGE INTO. append_partition uses replaceWhere on ds (idempotent)."},
    {"name": "merge_keys", "type": "string[]", "required": false,
     "description": "Required when write_mode=merge_on_pk."},
    {"name": "optimize_after_write", "type": "boolean", "default": false, "required": false,
     "description": "Run OPTIMIZE + Z-ORDER after the write. Adds 30s-2m runtime; bounds query latency as files accumulate."},
    {"name": "z_order_columns", "type": "string[]", "required": false,
     "description": "High-cardinality filter columns for Delta z-ordering. Required when optimize_after_write=true."}
]'::jsonb,
updated_at = NOW()
WHERE blueprint_key = 'LakeWriter';

-- ---------------------------------------------------------------------
-- StreamWriter — gold → Kafka publisher.
-- ---------------------------------------------------------------------
UPDATE blueprints
SET params_schema = '[
    {
        "name": "target_id",
        "type": "string",
        "required": true,
        "description": "Sink target SoR id (the Kafka cluster registry entry)."
    },
    {
        "name": "connector_instance_id",
        "type": "string",
        "required": true,
        "description": "ConnectorInstance attached to the chosen target. Drives __DEST_NAME__ credential prefix (BOOTSTRAP_SERVERS, SCHEMA_REGISTRY_URL, KAFKA_USERNAME, KAFKA_PASSWORD)."
    },
    {
        "name": "connector_name",
        "type": "string",
        "required": false,
        "description": "Display name of the chosen connector instance."
    },
    {
        "name": "target_credential_ref",
        "type": "string",
        "required": false,
        "description": "Optional gcp-sm:// override for the connector instance''s default credentials."
    },
    {"name": "topic", "type": "string", "required": true,
     "description": "Destination Kafka topic. Topic ACLs must be configured at the target connector level."},
    {"name": "publish_mode", "type": "enum", "default": "batch_publish",
     "options": ["batch_publish", "streaming_publish"], "required": false,
     "description": "batch_publish reads today''s gold partition once. streaming_publish reads Delta CDF and forwards continuously."},
    {"name": "schema_strategy", "type": "enum", "default": "json_envelope",
     "options": ["json_envelope", "avro_schema_registry", "protobuf"], "required": false,
     "description": "Wire format. avro_schema_registry requires SCHEMA_REGISTRY_URL on the connector instance."},
    {"name": "key_columns", "type": "string[]", "required": false,
     "description": "Business-key columns hashed (SHA256) into the Kafka key, guaranteeing same-key rows land on same partition for ordering."},
    {"name": "delivery_guarantee", "type": "enum", "default": "at_least_once",
     "options": ["at_least_once", "exactly_once"], "required": false,
     "description": "exactly_once requires producer transactions + idempotent downstream consumer."},
    {"name": "checkpoint_location", "type": "string", "required": false,
     "description": "Required for streaming_publish. s3a://... path; MUST be unique per pipeline (sharing checkpoints silently corrupts state)."}
]'::jsonb,
updated_at = NOW()
WHERE blueprint_key = 'StreamWriter';

-- ---------------------------------------------------------------------
-- WarehouseWriter — gold → BigQuery / Snowflake / Redshift.
-- ---------------------------------------------------------------------
UPDATE blueprints
SET params_schema = '[
    {
        "name": "target_id",
        "type": "string",
        "required": true,
        "description": "Sink target SoR id (the warehouse registry entry)."
    },
    {
        "name": "connector_instance_id",
        "type": "string",
        "required": true,
        "description": "ConnectorInstance attached to the chosen target. Drives __DEST_NAME__ credential prefix and warehouse-specific options (TEMP_GCS_BUCKET for BigQuery, etc.)."
    },
    {
        "name": "connector_name",
        "type": "string",
        "required": false,
        "description": "Display name of the chosen connector instance."
    },
    {
        "name": "target_credential_ref",
        "type": "string",
        "required": false,
        "description": "Optional gcp-sm:// override (e.g. service-account JSON). Required when connector instance does not have a workload-identity binding."
    },
    {"name": "target_table", "type": "string", "required": true,
     "description": "Fully-qualified destination, e.g. project.dataset.table for BigQuery, database.schema.table for Snowflake."},
    {"name": "write_mode", "type": "enum", "default": "overwrite_partition",
     "options": ["overwrite_partition", "append", "merge_on_pk"], "required": false,
     "description": "overwrite_partition replaces the matched partition (datePartition for BQ). merge_on_pk stages then issues MERGE."},
    {"name": "merge_keys", "type": "string[]", "required": false,
     "description": "Required when write_mode=merge_on_pk."},
    {"name": "batch_size", "type": "integer", "default": 10000, "required": false,
     "description": "Avro batch size for the indirect write method."},
    {"name": "clustering_columns", "type": "string[]", "required": false,
     "description": "Destination clustering columns (must already exist on the destination table). Critical for merge_on_pk runtime."}
]'::jsonb,
updated_at = NOW()
WHERE blueprint_key = 'WarehouseWriter';
