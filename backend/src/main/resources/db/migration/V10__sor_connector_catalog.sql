-- V10: SOR Registry, Connector Catalog, Credential Profiles, Datasets

-- Airbyte connector catalog (read-only reference data)
CREATE TABLE connector_definitions (
    id                  VARCHAR(26) PRIMARY KEY,
    airbyte_id          VARCHAR(64) NOT NULL UNIQUE,
    name                VARCHAR(255) NOT NULL,
    connector_type      VARCHAR(20) NOT NULL,
    docker_repository   VARCHAR(500) NOT NULL,
    docker_image_tag    VARCHAR(100) NOT NULL,
    icon_url            VARCHAR(500),
    connection_spec     JSONB NOT NULL,
    supported_modes     JSONB DEFAULT '[]',
    documentation_url   VARCHAR(500),
    release_stage       VARCHAR(30) NOT NULL DEFAULT 'ALPHA',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_conndef_type ON connector_definitions(connector_type);

CREATE TABLE systems_of_record (
    id              VARCHAR(26) PRIMARY KEY,
    tenant_id       VARCHAR(26) NOT NULL REFERENCES tenants(id),
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    domain_name     VARCHAR(255),
    owner_id        VARCHAR(26) NOT NULL REFERENCES users(id),
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sor_tenant ON systems_of_record(tenant_id);

CREATE TABLE connector_instances (
    id                      VARCHAR(26) PRIMARY KEY,
    sor_id                  VARCHAR(26) NOT NULL REFERENCES systems_of_record(id) ON DELETE CASCADE,
    connector_definition_id VARCHAR(26) NOT NULL REFERENCES connector_definitions(id),
    name                    VARCHAR(255) NOT NULL,
    description             TEXT,
    config_template         JSONB DEFAULT '{}',
    enabled                 BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_conninst_sor ON connector_instances(sor_id);

CREATE TABLE credential_profiles (
    id                      VARCHAR(26) PRIMARY KEY,
    connector_instance_id   VARCHAR(26) NOT NULL REFERENCES connector_instances(id) ON DELETE CASCADE,
    environment             VARCHAR(30) NOT NULL,
    connection_config       JSONB NOT NULL DEFAULT '{}',
    status                  VARCHAR(30) NOT NULL DEFAULT 'UNTESTED',
    last_tested_at          TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cred_env UNIQUE (connector_instance_id, environment)
);

CREATE TABLE datasets (
    id                      VARCHAR(26) PRIMARY KEY,
    connector_instance_id   VARCHAR(26) NOT NULL REFERENCES connector_instances(id),
    tenant_id               VARCHAR(26) NOT NULL REFERENCES tenants(id),
    name                    VARCHAR(255) NOT NULL,
    qualified_name          VARCHAR(500) NOT NULL UNIQUE,
    schema_snapshot         JSONB,
    schema_format           VARCHAR(50),
    classification          VARCHAR(50),
    freshness_sla           JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_dataset_tenant ON datasets(tenant_id);
CREATE INDEX idx_dataset_connector ON datasets(connector_instance_id);

-- =====================================================
-- SEED: Connector Definitions (representative subset)
-- =====================================================

-- SOURCES (25 most common)
INSERT INTO connector_definitions (id, airbyte_id, name, connector_type, docker_repository, docker_image_tag, connection_spec, supported_modes, release_stage, documentation_url) VALUES
('01JCONN0SRC0POSTGRES00001', 'decd7e73-8a76-4909-ae70-cdb1043d71e8', 'PostgreSQL', 'SOURCE', 'airbyte/source-postgres', '3.6.8', '{"type":"object","required":["host","port","database","username"],"properties":{"host":{"type":"string","description":"Hostname"},"port":{"type":"integer","default":5432},"database":{"type":"string"},"username":{"type":"string"},"password":{"type":"string","airbyte_secret":true},"ssl_mode":{"type":"string","enum":["disable","require","verify-ca"],"default":"require"}}}', '["full_refresh","incremental","cdc"]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/sources/postgres'),
('01JCONN0SRC0MYSQL0000001', 'b13b3e0c-7442-4500-a2fb-fe81a36e73d5', 'MySQL', 'SOURCE', 'airbyte/source-mysql', '3.5.0', '{"type":"object","required":["host","port","database","username"],"properties":{"host":{"type":"string"},"port":{"type":"integer","default":3306},"database":{"type":"string"},"username":{"type":"string"},"password":{"type":"string","airbyte_secret":true}}}', '["full_refresh","incremental","cdc"]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/sources/mysql'),
('01JCONN0SRC0ORACLE000001', '31c82ef5-3402-4b9f-8091-fcc5aab15c2e', 'Oracle DB', 'SOURCE', 'airbyte/source-oracle', '1.1.0', '{"type":"object","required":["host","port","sid","username"],"properties":{"host":{"type":"string"},"port":{"type":"integer","default":1521},"sid":{"type":"string"},"username":{"type":"string"},"password":{"type":"string","airbyte_secret":true},"schemas":{"type":"array","items":{"type":"string"}}}}', '["full_refresh","incremental"]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/sources/oracle'),
('01JCONN0SRC0MSSQL00000001', '76ab9e09-68f4-47c4-b2d6-224e1f0858e8', 'MS SQL Server', 'SOURCE', 'airbyte/source-mssql', '4.1.0', '{"type":"object","required":["host","port","database","username"],"properties":{"host":{"type":"string"},"port":{"type":"integer","default":1433},"database":{"type":"string"},"username":{"type":"string"},"password":{"type":"string","airbyte_secret":true}}}', '["full_refresh","incremental","cdc"]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/sources/mssql'),
('01JCONN0SRC0MONGODB00001', 'e0e7a917-1357-4e75-bab2-3176b1b08e6d', 'MongoDB', 'SOURCE', 'airbyte/source-mongodb-v2', '1.5.0', '{"type":"object","required":["connection_string","database"],"properties":{"connection_string":{"type":"string","airbyte_secret":true},"database":{"type":"string"}}}', '["full_refresh","incremental"]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/sources/mongodb'),
('01JCONN0SRC0S3000000001', 'b48b6cf8-3e3e-42c0-9868-eeb6e3a43e36', 'Amazon S3', 'SOURCE', 'airbyte/source-s3', '4.7.0', '{"type":"object","required":["bucket","region"],"properties":{"bucket":{"type":"string"},"region":{"type":"string","default":"us-east-1"},"aws_access_key_id":{"type":"string"},"aws_secret_access_key":{"type":"string","airbyte_secret":true},"path_prefix":{"type":"string"},"file_format":{"type":"string","enum":["csv","json","parquet","avro"],"default":"parquet"}}}', '["full_refresh"]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/sources/s3'),
('01JCONN0SRC0GCS00000001', 'a0a66cda-b5e6-4e0c-8b29-b6be96939868', 'Google Cloud Storage', 'SOURCE', 'airbyte/source-gcs', '0.5.0', '{"type":"object","required":["bucket","service_account"],"properties":{"bucket":{"type":"string"},"service_account":{"type":"string","airbyte_secret":true},"path_prefix":{"type":"string"}}}', '["full_refresh"]', 'BETA', 'https://docs.airbyte.com/integrations/sources/gcs'),
('01JCONN0SRC0KAFKA00000001', 'ab64ba05-0474-4c5e-80f5-2bdf3bd72e67', 'Kafka', 'SOURCE', 'airbyte/source-kafka', '0.4.0', '{"type":"object","required":["bootstrap_servers","topic"],"properties":{"bootstrap_servers":{"type":"string"},"topic":{"type":"string"},"group_id":{"type":"string"},"security_protocol":{"type":"string","enum":["PLAINTEXT","SASL_SSL","SSL"],"default":"PLAINTEXT"},"sasl_mechanism":{"type":"string"},"sasl_username":{"type":"string"},"sasl_password":{"type":"string","airbyte_secret":true}}}', '["full_refresh","incremental"]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/sources/kafka'),
('01JCONN0SRC0SFTP0000001', 'cde38ed0-816d-4100-921b-0c02e4e5ff93', 'SFTP', 'SOURCE', 'airbyte/source-sftp-bulk', '1.3.0', '{"type":"object","required":["host","username"],"properties":{"host":{"type":"string"},"port":{"type":"integer","default":22},"username":{"type":"string"},"password":{"type":"string","airbyte_secret":true},"private_key":{"type":"string","airbyte_secret":true},"folder_path":{"type":"string","default":"/"},"file_pattern":{"type":"string"}}}', '["full_refresh"]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/sources/sftp-bulk'),
('01JCONN0SRC0RESTAPI00001', 'e7eff203-90bf-4661-855e-0d505e6e4024', 'REST API', 'SOURCE', 'airbyte/source-declarative-manifest', '5.0.0', '{"type":"object","required":["url_base"],"properties":{"url_base":{"type":"string"},"auth_type":{"type":"string","enum":["no_auth","api_key","oauth2","basic"]},"api_key":{"type":"string","airbyte_secret":true},"headers":{"type":"object"},"pagination_type":{"type":"string","enum":["none","offset","cursor","page_number"]}}}', '["full_refresh","incremental"]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/sources/rest-api'),
('01JCONN0SRC0SALESFORCE01', '37e74cad-55f5-4c80-96c4-1c72ccc5b15b', 'Salesforce', 'SOURCE', 'airbyte/source-salesforce', '2.6.0', '{"type":"object","required":["client_id","client_secret","refresh_token"],"properties":{"client_id":{"type":"string"},"client_secret":{"type":"string","airbyte_secret":true},"refresh_token":{"type":"string","airbyte_secret":true},"is_sandbox":{"type":"boolean","default":false}}}', '["full_refresh","incremental"]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/sources/salesforce'),
('01JCONN0SRC0SNOWFLAKE001', 'c1bad520-fca8-4ac8-8607-93a8f36e45d1', 'Snowflake', 'SOURCE', 'airbyte/source-snowflake', '1.0.0', '{"type":"object","required":["host","warehouse","database","username"],"properties":{"host":{"type":"string"},"warehouse":{"type":"string"},"database":{"type":"string"},"schema":{"type":"string","default":"PUBLIC"},"username":{"type":"string"},"password":{"type":"string","airbyte_secret":true},"role":{"type":"string"}}}', '["full_refresh","incremental"]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/sources/snowflake'),
('01JCONN0SRC0JDBC0000001', '72b04957-2d38-4b66-8e51-86c0fe5d33d1', 'JDBC (Generic)', 'SOURCE', 'airbyte/source-jdbc', '1.2.0', '{"type":"object","required":["jdbc_url","username"],"properties":{"jdbc_url":{"type":"string"},"username":{"type":"string"},"password":{"type":"string","airbyte_secret":true},"driver_class":{"type":"string"}}}', '["full_refresh","incremental"]', 'BETA', 'https://docs.airbyte.com/integrations/sources/jdbc'),
('01JCONN0SRC0BIGQUERY0001', '47f0d2e0-2b9d-4a11-9c87-1e4c55d34a22', 'BigQuery', 'SOURCE', 'airbyte/source-bigquery', '0.5.0', '{"type":"object","required":["project_id","credentials_json"],"properties":{"project_id":{"type":"string"},"dataset_id":{"type":"string"},"credentials_json":{"type":"string","airbyte_secret":true}}}', '["full_refresh"]', 'BETA', 'https://docs.airbyte.com/integrations/sources/bigquery'),
('01JCONN0SRC0ELASTICSEARCH', 'a3fab1c1-c8f3-4b23-9a1e-9c8d4db52c1b', 'Elasticsearch', 'SOURCE', 'airbyte/source-elasticsearch', '0.3.0', '{"type":"object","required":["endpoint"],"properties":{"endpoint":{"type":"string"},"username":{"type":"string"},"password":{"type":"string","airbyte_secret":true}}}', '["full_refresh"]', 'BETA', 'https://docs.airbyte.com/integrations/sources/elasticsearch');

-- DESTINATIONS (12 most common)
INSERT INTO connector_definitions (id, airbyte_id, name, connector_type, docker_repository, docker_image_tag, connection_spec, supported_modes, release_stage, documentation_url) VALUES
('01JCONN0DST0SNOWFLAKE001', 'a1b2c3d4-e5f6-7890-abcd-ef1234567001', 'Snowflake', 'DESTINATION', 'airbyte/destination-snowflake', '3.11.0', '{"type":"object","required":["host","warehouse","database","username"],"properties":{"host":{"type":"string"},"warehouse":{"type":"string"},"database":{"type":"string"},"schema":{"type":"string","default":"PUBLIC"},"username":{"type":"string"},"password":{"type":"string","airbyte_secret":true},"role":{"type":"string"}}}', '[]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/destinations/snowflake'),
('01JCONN0DST0BIGQUERY0001', 'a1b2c3d4-e5f6-7890-abcd-ef1234567002', 'BigQuery', 'DESTINATION', 'airbyte/destination-bigquery', '2.8.0', '{"type":"object","required":["project_id","dataset_id","credentials_json"],"properties":{"project_id":{"type":"string"},"dataset_id":{"type":"string"},"credentials_json":{"type":"string","airbyte_secret":true},"loading_method":{"type":"string","enum":["standard","gcs_staging"],"default":"standard"}}}', '[]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/destinations/bigquery'),
('01JCONN0DST0DATABRICKS01', 'a1b2c3d4-e5f6-7890-abcd-ef1234567003', 'Databricks Lakehouse', 'DESTINATION', 'airbyte/destination-databricks', '2.1.0', '{"type":"object","required":["server_hostname","http_path","catalog"],"properties":{"server_hostname":{"type":"string"},"http_path":{"type":"string"},"access_token":{"type":"string","airbyte_secret":true},"catalog":{"type":"string"},"schema":{"type":"string","default":"default"}}}', '[]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/destinations/databricks'),
('01JCONN0DST0S3000000001', 'a1b2c3d4-e5f6-7890-abcd-ef1234567004', 'Amazon S3', 'DESTINATION', 'airbyte/destination-s3', '1.5.0', '{"type":"object","required":["bucket","region"],"properties":{"bucket":{"type":"string"},"region":{"type":"string","default":"us-east-1"},"aws_access_key_id":{"type":"string"},"aws_secret_access_key":{"type":"string","airbyte_secret":true},"path_prefix":{"type":"string"},"format":{"type":"string","enum":["parquet","csv","json","avro"],"default":"parquet"}}}', '[]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/destinations/s3'),
('01JCONN0DST0POSTGRES0001', 'a1b2c3d4-e5f6-7890-abcd-ef1234567005', 'PostgreSQL', 'DESTINATION', 'airbyte/destination-postgres', '2.3.0', '{"type":"object","required":["host","port","database","username"],"properties":{"host":{"type":"string"},"port":{"type":"integer","default":5432},"database":{"type":"string"},"username":{"type":"string"},"password":{"type":"string","airbyte_secret":true},"schema":{"type":"string","default":"public"}}}', '[]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/destinations/postgres'),
('01JCONN0DST0REDSHIFT0001', 'a1b2c3d4-e5f6-7890-abcd-ef1234567006', 'Redshift', 'DESTINATION', 'airbyte/destination-redshift', '2.5.0', '{"type":"object","required":["host","port","database","username"],"properties":{"host":{"type":"string"},"port":{"type":"integer","default":5439},"database":{"type":"string"},"username":{"type":"string"},"password":{"type":"string","airbyte_secret":true},"schema":{"type":"string","default":"public"}}}', '[]', 'GENERALLY_AVAILABLE', 'https://docs.airbyte.com/integrations/destinations/redshift'),
('01JCONN0DST0DELTA000001', 'a1b2c3d4-e5f6-7890-abcd-ef1234567007', 'Delta Lake', 'DESTINATION', 'airbyte/destination-delta-lake', '0.3.0', '{"type":"object","required":["storage_path"],"properties":{"storage_path":{"type":"string"},"aws_access_key_id":{"type":"string"},"aws_secret_access_key":{"type":"string","airbyte_secret":true}}}', '[]', 'BETA', 'https://docs.airbyte.com/integrations/destinations/delta-lake'),
('01JCONN0DST0KAFKA00000001', 'a1b2c3d4-e5f6-7890-abcd-ef1234567008', 'Kafka', 'DESTINATION', 'airbyte/destination-kafka', '0.2.0', '{"type":"object","required":["bootstrap_servers","topic"],"properties":{"bootstrap_servers":{"type":"string"},"topic":{"type":"string"},"security_protocol":{"type":"string","enum":["PLAINTEXT","SASL_SSL"],"default":"PLAINTEXT"}}}', '[]', 'BETA', 'https://docs.airbyte.com/integrations/destinations/kafka'),
('01JCONN0DST0ICEBERG00001', 'a1b2c3d4-e5f6-7890-abcd-ef1234567009', 'Apache Iceberg', 'DESTINATION', 'airbyte/destination-iceberg', '0.2.0', '{"type":"object","required":["catalog_type","warehouse"],"properties":{"catalog_type":{"type":"string","enum":["hive","glue","rest"],"default":"glue"},"warehouse":{"type":"string"},"catalog_uri":{"type":"string"}}}', '[]', 'BETA', 'https://docs.airbyte.com/integrations/destinations/iceberg');

-- =====================================================
-- SEED: Sample SORs for tenant-home-lending
-- =====================================================

INSERT INTO systems_of_record (id, tenant_id, name, description, domain_name, owner_id) VALUES
('01JSOR0LOS0000000000001', 'tenant-home-lending', 'Loan Origination System', 'Primary LOS handling mortgage applications, underwriting, and closing', 'Servicing', '01JUSER00000000000000000'),
('01JSOR0PAYGW000000000001', 'tenant-home-lending', 'Payment Gateway', 'Processes mortgage payments, escrow disbursements, and payoff quotes', 'Servicing', '01JUSER00000000000000000'),
('01JSOR0CREDIT00000000001', 'tenant-home-lending', 'Credit Bureau Service', 'Provides credit scores and reports from Experian, TransUnion, Equifax', 'Default', '01JUSER00000000000000000'),
('01JSOR0INVREPORT0000001', 'tenant-home-lending', 'Investor Reporting System', 'Generates investor disclosures and loan pool analytics for Fannie/Freddie', 'Capital Markets', '01JUSER00000000000000000');

-- Connector instances
INSERT INTO connector_instances (id, sor_id, connector_definition_id, name, description, config_template) VALUES
('01JCI0LOS0ORACLE0000001', '01JSOR0LOS0000000000001', '01JCONN0SRC0ORACLE000001', 'LOS Oracle Database', 'Direct JDBC access to LOS Oracle schema', '{"schemas": ["LOS_PROD"]}'),
('01JCI0LOS0RESTAPI000001', '01JSOR0LOS0000000000001', '01JCONN0SRC0RESTAPI00001', 'LOS REST API', 'LOS application REST endpoints for real-time lookups', '{"url_base": "https://los.internal.acme.com/api/v2"}'),
('01JCI0PAY0KAFKA00000001', '01JSOR0PAYGW000000000001', '01JCONN0SRC0KAFKA00000001', 'Payment Events (Kafka)', 'Real-time payment processing events', '{"topic": "payments.events"}'),
('01JCI0PAY0S3FILE0000001', '01JSOR0PAYGW000000000001', '01JCONN0SRC0S3000000001', 'Payment Nightly Extracts (S3)', 'Daily batch extracts of payment summaries', '{"bucket": "acme-data-lake", "path_prefix": "raw/payments/daily/"}'),
('01JCI0CRED0SFTP0000001', '01JSOR0CREDIT00000000001', '01JCONN0SRC0SFTP0000001', 'Credit Bureau SFTP Drops', 'Nightly credit file drops from bureaus', '{"folder_path": "/incoming/credit_bureau/", "file_pattern": "*.pgp"}'),
('01JCI0CRED0REST0000001', '01JSOR0CREDIT00000000001', '01JCONN0SRC0RESTAPI00001', 'Credit Bureau API', 'Real-time credit pull API', '{"url_base": "https://creditapi.vendor.com/v3"}'),
('01JCI0INV0ORACLE0000001', '01JSOR0INVREPORT0000001', '01JCONN0SRC0ORACLE000001', 'Investor Reporting Oracle DB', 'Read access to investor reporting data warehouse', '{"schemas": ["INV_RPT"]}');

-- Credential profiles (DEV only, SecretRefs)
INSERT INTO credential_profiles (id, connector_instance_id, environment, connection_config, status) VALUES
('01JCRED0LOS0ORACLE0DEV01', '01JCI0LOS0ORACLE0000001', 'DEV', '{"host":"los-dev.internal.acme.com","port":1521,"sid":"LOSDEV","username":"vault://pulse/dev/los-oracle/username","password":"vault://pulse/dev/los-oracle/password"}', 'VALID'),
('01JCRED0LOS0REST0DEV001', '01JCI0LOS0RESTAPI000001', 'DEV', '{"api_key":"vault://pulse/dev/los-api/key"}', 'VALID'),
('01JCRED0PAY0KAFKA0DEV01', '01JCI0PAY0KAFKA00000001', 'DEV', '{"bootstrap_servers":"kafka-dev.internal.acme.com:9092","security_protocol":"SASL_SSL","sasl_username":"vault://pulse/dev/kafka/username","sasl_password":"vault://pulse/dev/kafka/password"}', 'VALID'),
('01JCRED0PAY0S3000DEV001', '01JCI0PAY0S3FILE0000001', 'DEV', '{"aws_access_key_id":"vault://pulse/dev/aws/access_key","aws_secret_access_key":"vault://pulse/dev/aws/secret_key","region":"us-east-1"}', 'UNTESTED'),
('01JCRED0CRD0SFTP0DEV001', '01JCI0CRED0SFTP0000001', 'DEV', '{"host":"sftp-dev.creditvendor.com","username":"vault://pulse/dev/credit-sftp/username","private_key":"vault://pulse/dev/credit-sftp/key"}', 'UNTESTED'),
('01JCRED0CRD0REST0DEV001', '01JCI0CRED0REST0000001', 'DEV', '{"api_key":"vault://pulse/dev/credit-api/key"}', 'VALID'),
('01JCRED0INV0ORACLE0DEV1', '01JCI0INV0ORACLE0000001', 'DEV', '{"host":"inv-dev.internal.acme.com","port":1521,"sid":"INVDEV","username":"vault://pulse/dev/inv-oracle/username","password":"vault://pulse/dev/inv-oracle/password"}', 'VALID');

-- Sample datasets
INSERT INTO datasets (id, connector_instance_id, tenant_id, name, qualified_name, schema_format, classification, schema_snapshot) VALUES
('01JDS0LOS0APPLICATIONS01', '01JCI0LOS0ORACLE0000001', 'tenant-home-lending', 'loan_applications', 'tenant-home-lending.servicing.raw.loan_applications', 'DDL', 'CONFIDENTIAL', '{"fields":[{"name":"application_id","type":"NUMBER","nullable":false,"pii":false},{"name":"borrower_name","type":"VARCHAR2(200)","nullable":false,"pii":true},{"name":"loan_amount","type":"NUMBER(15,2)","pii":false},{"name":"property_address","type":"VARCHAR2(500)","pii":true},{"name":"credit_score","type":"NUMBER","pii":true},{"name":"status","type":"VARCHAR2(50)","pii":false},{"name":"created_at","type":"TIMESTAMP","pii":false}]}'),
('01JDS0LOS0UNDERWRITING01', '01JCI0LOS0ORACLE0000001', 'tenant-home-lending', 'underwriting_decisions', 'tenant-home-lending.servicing.raw.underwriting_decisions', 'DDL', 'CONFIDENTIAL', '{"fields":[{"name":"decision_id","type":"NUMBER","nullable":false},{"name":"application_id","type":"NUMBER"},{"name":"decision","type":"VARCHAR2(20)"},{"name":"conditions","type":"CLOB"},{"name":"decided_at","type":"TIMESTAMP"}]}'),
('01JDS0PAY0EVENTS00000001', '01JCI0PAY0KAFKA00000001', 'tenant-home-lending', 'payment_events', 'tenant-home-lending.servicing.raw.payment_events', 'AVRO', 'INTERNAL', '{"fields":[{"name":"event_id","type":"string"},{"name":"loan_id","type":"string"},{"name":"amount","type":"double"},{"name":"payment_type","type":"string"},{"name":"status","type":"string"},{"name":"processed_at","type":"long"}]}'),
('01JDS0PAY0DAILY0SUMMARY1', '01JCI0PAY0S3FILE0000001', 'tenant-home-lending', 'daily_payment_summary', 'tenant-home-lending.servicing.raw.daily_payment_summary', 'PARQUET_SCHEMA', 'INTERNAL', '{"fields":[{"name":"summary_date","type":"DATE"},{"name":"total_payments","type":"DECIMAL(15,2)"},{"name":"payment_count","type":"INT"},{"name":"escrow_total","type":"DECIMAL(15,2)"}]}'),
('01JDS0CRD0CREDIT0FILES01', '01JCI0CRED0SFTP0000001', 'tenant-home-lending', 'credit_bureau_files', 'tenant-home-lending.default.raw.credit_bureau_files', 'JSON_SCHEMA', 'CONFIDENTIAL', '{"fields":[{"name":"ssn","type":"string","pii":true},{"name":"first_name","type":"string","pii":true},{"name":"last_name","type":"string","pii":true},{"name":"credit_score","type":"integer","pii":false},{"name":"report_date","type":"string","pii":false}]}'),
('01JDS0INV0LOAN0POOLS0001', '01JCI0INV0ORACLE0000001', 'tenant-home-lending', 'investor_loan_pools', 'tenant-home-lending.capital-markets.raw.investor_loan_pools', 'DDL', 'CONFIDENTIAL', '{"fields":[{"name":"pool_id","type":"NUMBER"},{"name":"investor","type":"VARCHAR2(100)"},{"name":"total_balance","type":"NUMBER(15,2)"},{"name":"loan_count","type":"NUMBER"},{"name":"weighted_avg_rate","type":"NUMBER(5,4)"},{"name":"report_month","type":"DATE"}]}');
