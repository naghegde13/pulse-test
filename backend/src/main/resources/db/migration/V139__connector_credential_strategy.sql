-- PKT-0018: Connector Credential Strategy
--
-- Classifies each connector definition's credential acquisition strategy:
--   CONNECTOR_SPECIFIC                  — requires its own credential profile per env (default)
--   INHERIT_TENANT_GCP_SERVICE_ACCOUNT  — inherits tenant GCP SA; no connector-specific creds needed
--
-- S3-compatible Object Storage connectors (source + destination) are lifecycle-file
-- connectors that land in PULSE-managed tenant GCS lifecycle folders. They inherit
-- the tenant-level GCP service account and storage scaffold configuration.
-- All other connectors (databases, APIs, SFTP, etc.) remain CONNECTOR_SPECIFIC.

ALTER TABLE connector_definitions
    ADD COLUMN credential_strategy VARCHAR(50) NOT NULL DEFAULT 'CONNECTOR_SPECIFIC';

-- Classify lifecycle-file connectors as inheriting tenant GCP service account
UPDATE connector_definitions
SET credential_strategy = 'INHERIT_TENANT_GCP_SERVICE_ACCOUNT'
WHERE id IN ('01JCONN0SRC0S3000000001', '01JCONN0DST0S3000000001');
