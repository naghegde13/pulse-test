-- PKT-0011: Tenant GCP credentials — stores encrypted service-account JSON per tenant.
-- The private_key material is AES-encrypted at rest; readback surfaces expose only
-- service_account_email, key_id, gcp_project_id, and status — never the private key.

CREATE TABLE IF NOT EXISTS tenant_gcp_credentials (
    id                      VARCHAR(26)   NOT NULL PRIMARY KEY,
    tenant_id               VARCHAR(26)   NOT NULL,
    gcp_project_id          VARCHAR(255)  NOT NULL,
    service_account_email   VARCHAR(320)  NOT NULL,
    key_id                  VARCHAR(255)  NOT NULL,
    encrypted_credential    TEXT          NOT NULL,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'active',
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_tgcred_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT uq_tgcred_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_tgcred_status CHECK (status IN ('active', 'revoked', 'expired'))
);
