-- V99: PULSE-native connector_definitions catalog rewrite
--
-- BACKGROUND
-- ----------
-- V10 seeded the connector_definitions catalog using Airbyte's published source
-- and destination spec shapes as a research reference (Airbyte was consulted
-- by the original author for "what fields each connector type needs"). PULSE
-- never used Airbyte at runtime — codegen has always emitted PySpark + dbt-spark
-- + Great Expectations + Airflow directly. The Airbyte vocabulary therefore
-- accumulated as vestigial seed metadata: `airbyte_id` UUIDs, `airbyte_secret`
-- spec annotations, `airbyte/source-X` / `airbyte/destination-X` docker repos,
-- and `docs.airbyte.com` documentation URLs.
--
-- Worse, the V10 specs imported AWS-specific field names for S3 (e.g.
-- `aws_access_key_id`) which contradict PULSE's actual model: in PULSE,
-- object-storage connectors point at the tenant's storage_backend (DPC or GCP),
-- and bucket/path/region/endpoint/credentials all resolve from the
-- `storage_backends` row + naming convention. The agent should never elicit
-- those values from the user.
--
-- THIS MIGRATION
-- --------------
-- 1. Drops the four cruft destination rows (Databricks, Redshift, Delta Lake,
--    Apache Iceberg) and the duplicate GCS source. Object storage is now
--    represented by a single "S3-compatible Object Storage" row per direction.
-- 2. Drops the `airbyte_id` column (NOT NULL UNIQUE in V10; vestigial — no
--    runtime code reads it).
-- 3. Nulls `documentation_url` values pointing at docs.airbyte.com.
-- 4. Rewrites every surviving row's `connection_spec` JSONB with
--    PULSE-native vocabulary:
--      * `airbyte_secret` annotation replaced with `pulse_role` + `secret`
--      * Each property is tagged with `pulse_role`:
--          - `credential`        : user-entered, lands in CredentialProfile
--                                  via the credential dialog. `secret: true`
--                                  marks the value as secret-shaped.
--          - `env_metadata`      : per-env non-secret metadata.
--          - `platform_resolved` : platform derives from storage_backends.
--                                  (Used implicitly by S3 connectors which
--                                  have empty connection_specs.)
--      * AWS-specific S3 field names removed entirely.
-- 5. Renames "Amazon S3" → "S3-compatible Object Storage".
-- 6. Adds 4 new destination rows symmetric to existing sources: MySQL,
--    Oracle DB, MS SQL Server, MongoDB destinations.
-- 7. Rewrites `docker_repository` values from `airbyte/X` → `pulse/X` so the
--    existing DatasetDefinitionMethods.getMethodsForConnector() substring
--    dispatch (matches "postgres", "mysql", "kafka", etc.) keeps working.
--
-- Final catalog: 14 sources + 9 destinations = 23 rows.
--
-- ROLLBACK
-- --------
-- This migration is destructive (DELETE + DROP COLUMN). Take a `pg_dump` of
-- the connector_definitions, connector_instances, and credential_profiles
-- tables before applying. To roll back, re-run V10 INSERT statements,
-- ALTER TABLE ADD COLUMN airbyte_id with the old UNIQUE constraint, and
-- restore the dropped tables from dump.

-- ============================================================================
-- 1. Remove connector_instances + credential_profiles that reference rows
--    we're about to drop. The V10 seed and V80 target seed don't reference
--    these IDs, but we run the deletes defensively in case a tenant has
--    created instances against them.
-- ============================================================================

DELETE FROM credential_profiles
WHERE connector_instance_id IN (
    SELECT id FROM connector_instances
    WHERE connector_definition_id IN (
        '01JCONN0SRC0GCS00000001',
        '01JCONN0DST0DATABRICKS01',
        '01JCONN0DST0REDSHIFT0001',
        '01JCONN0DST0DELTA000001',
        '01JCONN0DST0ICEBERG00001'
    )
);

DELETE FROM connector_instances
WHERE connector_definition_id IN (
    '01JCONN0SRC0GCS00000001',
    '01JCONN0DST0DATABRICKS01',
    '01JCONN0DST0REDSHIFT0001',
    '01JCONN0DST0DELTA000001',
    '01JCONN0DST0ICEBERG00001'
);

DELETE FROM connector_definitions
WHERE id IN (
    '01JCONN0SRC0GCS00000001',
    '01JCONN0DST0DATABRICKS01',
    '01JCONN0DST0REDSHIFT0001',
    '01JCONN0DST0DELTA000001',
    '01JCONN0DST0ICEBERG00001'
);

-- ============================================================================
-- 2. Drop vestigial `airbyte_id` column (NOT NULL UNIQUE in V10).
-- ============================================================================

ALTER TABLE connector_definitions DROP COLUMN airbyte_id;

-- ============================================================================
-- 3. Null documentation_url values. (Column kept; null pending PULSE-internal
--    docs URL convention. The Airbyte links are wrong for runtime usage.)
-- ============================================================================

UPDATE connector_definitions SET documentation_url = NULL;

-- ============================================================================
-- 4a. Object-storage connectors — empty connection_spec; everything
--     resolves from storage_backends + naming convention via the new
--     get_storage_paths chat tool.
-- ============================================================================

UPDATE connector_definitions SET
    name = 'S3-compatible Object Storage',
    docker_repository = 'pulse/source-s3',
    connection_spec = '{
        "type":"object",
        "title":"S3-compatible Object Storage (Source)",
        "description":"Reads from the tenant storage_backend (DPC or GCP). Bucket, path, region, endpoint, and auth resolve from the storage_backends row + naming convention via get_storage_paths. The agent surfaces resolved values to the user before configuring this connector. No user input is collected here.",
        "required":[],
        "properties":{}
    }'::jsonb
WHERE id = '01JCONN0SRC0S3000000001';

UPDATE connector_definitions SET
    name = 'S3-compatible Object Storage',
    docker_repository = 'pulse/destination-s3',
    connection_spec = '{
        "type":"object",
        "title":"S3-compatible Object Storage (Destination)",
        "description":"Writes to the tenant storage_backend (DPC or GCP). Bucket and path resolve to the outgoing_extracts folder under storage_root_files via get_storage_paths. The agent surfaces resolved values to the user before configuring this connector. No user input is collected here.",
        "required":[],
        "properties":{}
    }'::jsonb
WHERE id = '01JCONN0DST0S3000000001';

-- ============================================================================
-- 4b. JDBC family — PostgreSQL, MySQL, Oracle, MS SQL Server, JDBC Generic,
--     Snowflake. Each external SOR; user-input fields with pulse_role tags.
-- ============================================================================

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-postgres',
    connection_spec = '{
        "type":"object","title":"PostgreSQL",
        "description":"Connects to a PostgreSQL database outside the PULSE system boundary.",
        "required":["host","port","database","username","password"],
        "properties":{
            "host":{"type":"string","pulse_role":"env_metadata","description":"Hostname or IP"},
            "port":{"type":"integer","pulse_role":"env_metadata","default":5432},
            "database":{"type":"string","pulse_role":"env_metadata"},
            "username":{"type":"string","pulse_role":"credential"},
            "password":{"type":"string","pulse_role":"credential","secret":true},
            "ssl_mode":{"type":"string","pulse_role":"env_metadata","enum":["disable","require","verify-ca"],"default":"require"},
            "schema":{"type":"string","pulse_role":"env_metadata","description":"Schema to read from"}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0POSTGRES00001';

UPDATE connector_definitions SET
    docker_repository = 'pulse/destination-postgres',
    connection_spec = '{
        "type":"object","title":"PostgreSQL",
        "description":"Writes to a PostgreSQL database outside the PULSE system boundary.",
        "required":["host","port","database","username","password"],
        "properties":{
            "host":{"type":"string","pulse_role":"env_metadata","description":"Hostname or IP"},
            "port":{"type":"integer","pulse_role":"env_metadata","default":5432},
            "database":{"type":"string","pulse_role":"env_metadata"},
            "username":{"type":"string","pulse_role":"credential"},
            "password":{"type":"string","pulse_role":"credential","secret":true},
            "schema":{"type":"string","pulse_role":"env_metadata","default":"public","description":"Schema to write into"}
        }
    }'::jsonb
WHERE id = '01JCONN0DST0POSTGRES0001';

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-mysql',
    connection_spec = '{
        "type":"object","title":"MySQL",
        "description":"Connects to a MySQL database outside the PULSE system boundary.",
        "required":["host","port","database","username","password"],
        "properties":{
            "host":{"type":"string","pulse_role":"env_metadata"},
            "port":{"type":"integer","pulse_role":"env_metadata","default":3306},
            "database":{"type":"string","pulse_role":"env_metadata"},
            "username":{"type":"string","pulse_role":"credential"},
            "password":{"type":"string","pulse_role":"credential","secret":true}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0MYSQL0000001';

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-oracle',
    connection_spec = '{
        "type":"object","title":"Oracle DB",
        "description":"Connects to an Oracle database outside the PULSE system boundary.",
        "required":["host","port","sid","username","password"],
        "properties":{
            "host":{"type":"string","pulse_role":"env_metadata"},
            "port":{"type":"integer","pulse_role":"env_metadata","default":1521},
            "sid":{"type":"string","pulse_role":"env_metadata","description":"Oracle SID or service name"},
            "username":{"type":"string","pulse_role":"credential"},
            "password":{"type":"string","pulse_role":"credential","secret":true},
            "schemas":{"type":"array","items":{"type":"string"},"pulse_role":"env_metadata","description":"Schema names to read from"}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0ORACLE000001';

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-mssql',
    connection_spec = '{
        "type":"object","title":"MS SQL Server",
        "description":"Connects to a Microsoft SQL Server database outside the PULSE system boundary.",
        "required":["host","port","database","username","password"],
        "properties":{
            "host":{"type":"string","pulse_role":"env_metadata"},
            "port":{"type":"integer","pulse_role":"env_metadata","default":1433},
            "database":{"type":"string","pulse_role":"env_metadata"},
            "username":{"type":"string","pulse_role":"credential"},
            "password":{"type":"string","pulse_role":"credential","secret":true}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0MSSQL00000001';

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-mongodb',
    connection_spec = '{
        "type":"object","title":"MongoDB",
        "description":"Connects to a MongoDB cluster outside the PULSE system boundary.",
        "required":["connection_string","database"],
        "properties":{
            "connection_string":{"type":"string","pulse_role":"credential","secret":true,"description":"MongoDB connection URI (mongodb://...)"},
            "database":{"type":"string","pulse_role":"env_metadata"}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0MONGODB00001';

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-jdbc',
    connection_spec = '{
        "type":"object","title":"JDBC (Generic)",
        "description":"Connects to a generic JDBC database outside the PULSE system boundary. Use when the specific DB type is not in the catalog.",
        "required":["jdbc_url","username"],
        "properties":{
            "jdbc_url":{"type":"string","pulse_role":"env_metadata","description":"Full JDBC connection URL"},
            "username":{"type":"string","pulse_role":"credential"},
            "password":{"type":"string","pulse_role":"credential","secret":true},
            "driver_class":{"type":"string","pulse_role":"env_metadata","description":"JDBC driver class name (auto-detected if omitted for known DBs)"}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0JDBC0000001';

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-snowflake',
    connection_spec = '{
        "type":"object","title":"Snowflake",
        "description":"Connects to a Snowflake account outside the PULSE system boundary.",
        "required":["host","warehouse","database","username","password"],
        "properties":{
            "host":{"type":"string","pulse_role":"env_metadata","description":"Snowflake account host"},
            "warehouse":{"type":"string","pulse_role":"env_metadata"},
            "database":{"type":"string","pulse_role":"env_metadata"},
            "schema":{"type":"string","pulse_role":"env_metadata","default":"PUBLIC"},
            "username":{"type":"string","pulse_role":"credential"},
            "password":{"type":"string","pulse_role":"credential","secret":true},
            "role":{"type":"string","pulse_role":"env_metadata"}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0SNOWFLAKE001';

UPDATE connector_definitions SET
    docker_repository = 'pulse/destination-snowflake',
    connection_spec = '{
        "type":"object","title":"Snowflake",
        "description":"Writes to a Snowflake account outside the PULSE system boundary.",
        "required":["host","warehouse","database","username","password"],
        "properties":{
            "host":{"type":"string","pulse_role":"env_metadata"},
            "warehouse":{"type":"string","pulse_role":"env_metadata"},
            "database":{"type":"string","pulse_role":"env_metadata"},
            "schema":{"type":"string","pulse_role":"env_metadata","default":"PUBLIC"},
            "username":{"type":"string","pulse_role":"credential"},
            "password":{"type":"string","pulse_role":"credential","secret":true},
            "role":{"type":"string","pulse_role":"env_metadata"}
        }
    }'::jsonb
WHERE id = '01JCONN0DST0SNOWFLAKE001';

-- ============================================================================
-- 4c. Kafka — source + destination
-- ============================================================================

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-kafka',
    connection_spec = '{
        "type":"object","title":"Kafka",
        "description":"Connects to a Kafka cluster outside the PULSE system boundary.",
        "required":["bootstrap_servers","topic"],
        "properties":{
            "bootstrap_servers":{"type":"string","pulse_role":"env_metadata","description":"Comma-separated broker host:port list"},
            "topic":{"type":"string","pulse_role":"env_metadata"},
            "group_id":{"type":"string","pulse_role":"env_metadata","description":"Consumer group ID"},
            "security_protocol":{"type":"string","pulse_role":"env_metadata","enum":["PLAINTEXT","SASL_SSL","SSL"],"default":"PLAINTEXT"},
            "sasl_mechanism":{"type":"string","pulse_role":"env_metadata"},
            "sasl_username":{"type":"string","pulse_role":"credential"},
            "sasl_password":{"type":"string","pulse_role":"credential","secret":true}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0KAFKA00000001';

UPDATE connector_definitions SET
    docker_repository = 'pulse/destination-kafka',
    connection_spec = '{
        "type":"object","title":"Kafka",
        "description":"Publishes to a Kafka cluster outside the PULSE system boundary.",
        "required":["bootstrap_servers","topic"],
        "properties":{
            "bootstrap_servers":{"type":"string","pulse_role":"env_metadata"},
            "topic":{"type":"string","pulse_role":"env_metadata"},
            "security_protocol":{"type":"string","pulse_role":"env_metadata","enum":["PLAINTEXT","SASL_SSL","SSL"],"default":"PLAINTEXT"},
            "sasl_mechanism":{"type":"string","pulse_role":"env_metadata"},
            "sasl_username":{"type":"string","pulse_role":"credential"},
            "sasl_password":{"type":"string","pulse_role":"credential","secret":true}
        }
    }'::jsonb
WHERE id = '01JCONN0DST0KAFKA00000001';

-- ============================================================================
-- 4d. SFTP — source only (sink semantics not added per scope)
-- ============================================================================

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-sftp',
    connection_spec = '{
        "type":"object","title":"SFTP",
        "description":"Reads files from an SFTP server outside the PULSE system boundary.",
        "required":["host","username"],
        "properties":{
            "host":{"type":"string","pulse_role":"env_metadata"},
            "port":{"type":"integer","pulse_role":"env_metadata","default":22},
            "username":{"type":"string","pulse_role":"credential"},
            "password":{"type":"string","pulse_role":"credential","secret":true,"description":"Either password OR private_key (not both)"},
            "private_key":{"type":"string","pulse_role":"credential","secret":true,"description":"PEM-formatted private key. Use this OR password, not both."},
            "folder_path":{"type":"string","pulse_role":"env_metadata","default":"/"},
            "file_pattern":{"type":"string","pulse_role":"env_metadata","description":"Glob pattern for files to read"}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0SFTP0000001';

-- ============================================================================
-- 4e. REST API — source only
-- ============================================================================

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-rest-api',
    connection_spec = '{
        "type":"object","title":"REST API",
        "description":"Connects to a REST API outside the PULSE system boundary.",
        "required":["url_base"],
        "properties":{
            "url_base":{"type":"string","pulse_role":"env_metadata","description":"Base URL (https://...)"},
            "auth_type":{"type":"string","pulse_role":"env_metadata","enum":["no_auth","api_key","oauth2","basic"]},
            "api_key":{"type":"string","pulse_role":"credential","secret":true},
            "headers":{"type":"object","pulse_role":"env_metadata","description":"Static headers to include with each request"},
            "pagination_type":{"type":"string","pulse_role":"env_metadata","enum":["none","offset","cursor","page_number"]}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0RESTAPI00001';

-- ============================================================================
-- 4f. Salesforce — source only
-- ============================================================================

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-salesforce',
    connection_spec = '{
        "type":"object","title":"Salesforce",
        "description":"Connects to a Salesforce org outside the PULSE system boundary.",
        "required":["client_id","client_secret","refresh_token"],
        "properties":{
            "client_id":{"type":"string","pulse_role":"credential"},
            "client_secret":{"type":"string","pulse_role":"credential","secret":true},
            "refresh_token":{"type":"string","pulse_role":"credential","secret":true},
            "is_sandbox":{"type":"boolean","pulse_role":"env_metadata","default":false}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0SALESFORCE01';

-- ============================================================================
-- 4g. BigQuery — source + destination
-- ============================================================================

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-bigquery',
    connection_spec = '{
        "type":"object","title":"BigQuery",
        "description":"Reads from a BigQuery project outside the PULSE system boundary. Note: gold-on-GCP writes go via the storage_backend, not via this connector.",
        "required":["project_id"],
        "properties":{
            "project_id":{"type":"string","pulse_role":"env_metadata"},
            "dataset_id":{"type":"string","pulse_role":"env_metadata"},
            "credentials_json":{"type":"string","pulse_role":"credential","secret":true,"description":"Service account JSON key (full JSON content)"}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0BIGQUERY0001';

UPDATE connector_definitions SET
    docker_repository = 'pulse/destination-bigquery',
    connection_spec = '{
        "type":"object","title":"BigQuery",
        "description":"Writes to a BigQuery project outside the PULSE system boundary. Note: gold-on-GCP writes typically go via the storage_backend (bq_native lake_format), not via this connector.",
        "required":["project_id","dataset_id"],
        "properties":{
            "project_id":{"type":"string","pulse_role":"env_metadata"},
            "dataset_id":{"type":"string","pulse_role":"env_metadata"},
            "credentials_json":{"type":"string","pulse_role":"credential","secret":true,"description":"Service account JSON key"}
        }
    }'::jsonb
WHERE id = '01JCONN0DST0BIGQUERY0001';

-- ============================================================================
-- 4h. Elasticsearch — source only
-- ============================================================================

UPDATE connector_definitions SET
    docker_repository = 'pulse/source-elasticsearch',
    connection_spec = '{
        "type":"object","title":"Elasticsearch",
        "description":"Reads from an Elasticsearch cluster outside the PULSE system boundary.",
        "required":["endpoint"],
        "properties":{
            "endpoint":{"type":"string","pulse_role":"env_metadata","description":"Cluster endpoint URL"},
            "username":{"type":"string","pulse_role":"credential"},
            "password":{"type":"string","pulse_role":"credential","secret":true}
        }
    }'::jsonb
WHERE id = '01JCONN0SRC0ELASTICSEARCH';

-- ============================================================================
-- 5. INSERT new destination rows symmetric to existing sources.
-- ============================================================================

INSERT INTO connector_definitions (id, name, connector_type, docker_repository, docker_image_tag, connection_spec, supported_modes, release_stage, documentation_url) VALUES
('01JCONN0DST0MYSQL0000001', 'MySQL', 'DESTINATION', 'pulse/destination-mysql', '1.0.0',
 '{
    "type":"object","title":"MySQL",
    "description":"Writes to a MySQL database outside the PULSE system boundary.",
    "required":["host","port","database","username","password"],
    "properties":{
        "host":{"type":"string","pulse_role":"env_metadata"},
        "port":{"type":"integer","pulse_role":"env_metadata","default":3306},
        "database":{"type":"string","pulse_role":"env_metadata"},
        "username":{"type":"string","pulse_role":"credential"},
        "password":{"type":"string","pulse_role":"credential","secret":true}
    }
 }'::jsonb,
 '[]'::jsonb, 'GENERALLY_AVAILABLE', NULL),

('01JCONN0DST0ORACLE000001', 'Oracle DB', 'DESTINATION', 'pulse/destination-oracle', '1.0.0',
 '{
    "type":"object","title":"Oracle DB",
    "description":"Writes to an Oracle database outside the PULSE system boundary.",
    "required":["host","port","sid","username","password"],
    "properties":{
        "host":{"type":"string","pulse_role":"env_metadata"},
        "port":{"type":"integer","pulse_role":"env_metadata","default":1521},
        "sid":{"type":"string","pulse_role":"env_metadata","description":"Oracle SID or service name"},
        "username":{"type":"string","pulse_role":"credential"},
        "password":{"type":"string","pulse_role":"credential","secret":true},
        "schema":{"type":"string","pulse_role":"env_metadata","description":"Schema to write into"}
    }
 }'::jsonb,
 '[]'::jsonb, 'GENERALLY_AVAILABLE', NULL),

('01JCONN0DST0MSSQL0000001', 'MS SQL Server', 'DESTINATION', 'pulse/destination-mssql', '1.0.0',
 '{
    "type":"object","title":"MS SQL Server",
    "description":"Writes to a Microsoft SQL Server database outside the PULSE system boundary.",
    "required":["host","port","database","username","password"],
    "properties":{
        "host":{"type":"string","pulse_role":"env_metadata"},
        "port":{"type":"integer","pulse_role":"env_metadata","default":1433},
        "database":{"type":"string","pulse_role":"env_metadata"},
        "username":{"type":"string","pulse_role":"credential"},
        "password":{"type":"string","pulse_role":"credential","secret":true}
    }
 }'::jsonb,
 '[]'::jsonb, 'GENERALLY_AVAILABLE', NULL),

('01JCONN0DST0MONGODB00001', 'MongoDB', 'DESTINATION', 'pulse/destination-mongodb', '1.0.0',
 '{
    "type":"object","title":"MongoDB",
    "description":"Writes to a MongoDB cluster outside the PULSE system boundary.",
    "required":["connection_string","database"],
    "properties":{
        "connection_string":{"type":"string","pulse_role":"credential","secret":true,"description":"MongoDB connection URI (mongodb://...)"},
        "database":{"type":"string","pulse_role":"env_metadata"}
    }
 }'::jsonb,
 '[]'::jsonb, 'GENERALLY_AVAILABLE', NULL);

-- ============================================================================
-- 6. Validation queries (manual verification post-migration; should each
--    return 0 rows on success):
--
--   -- No airbyte vocabulary remains in connection_spec content:
--   SELECT id, name FROM connector_definitions
--   WHERE connection_spec::text LIKE '%airbyte%';
--
--   -- No airbyte/X docker_repository values remain:
--   SELECT id, name, docker_repository FROM connector_definitions
--   WHERE docker_repository LIKE 'airbyte/%';
--
--   -- No documentation_url pointing at airbyte docs:
--   SELECT id, name FROM connector_definitions
--   WHERE documentation_url LIKE '%airbyte%';
--
--   -- airbyte_id column is gone:
--   SELECT column_name FROM information_schema.columns
--   WHERE table_name = 'connector_definitions' AND column_name = 'airbyte_id';
--
--   -- Final catalog count: 14 sources + 9 destinations = 23.
--   SELECT connector_type, count(*) FROM connector_definitions GROUP BY connector_type;
--   -- Expected: SOURCE=14, DESTINATION=9
-- ============================================================================
