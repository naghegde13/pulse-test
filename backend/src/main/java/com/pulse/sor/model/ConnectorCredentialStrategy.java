package com.pulse.sor.model;

/**
 * PKT-0018: Classifies how a connector obtains its credentials.
 * <p>
 * Lifecycle-file connectors (e.g. S3-compatible Object Storage landing in
 * PULSE-managed tenant GCS lifecycle folders) inherit the tenant-level GCP
 * service account and never prompt for connector-specific credentials.
 * External connectors (databases, APIs, SFTP, etc.) require their own
 * connector-specific credential profiles.
 */
public enum ConnectorCredentialStrategy {

    /**
     * Connector inherits the tenant-level GCP service account.
     * No connector-specific credential submission is required.
     * Readiness depends on tenant GCP credential + storage scaffold status.
     */
    INHERIT_TENANT_GCP_SERVICE_ACCOUNT,

    /**
     * Connector requires its own credential profile per environment.
     * Standard validation flow applies (PKT-0016/0017).
     */
    CONNECTOR_SPECIFIC
}
