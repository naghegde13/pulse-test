-- PKT-FINAL-6 (BUG-2026-05-25-48): IMPERSONATION credential mode discriminator.
--
-- PULSE's tenant GCP credential surface previously required a static SA JSON
-- key (STATIC_KEY mode). Security-conscious GCP orgs enforce
-- constraints/iam.disableServiceAccountKeyCreation, which structurally blocks
-- that flow. This migration adds an IMPERSONATION mode that stores the tenant
-- SA email only (no key material); PULSE mints short-lived tokens at use time
-- via google-auth ImpersonatedCredentials backed by the Cloud Run / ADC
-- identity.
--
-- Columns:
--   credential_mode               — 'STATIC_KEY' | 'IMPERSONATION' (default
--                                   STATIC_KEY for back-compat on existing rows)
--   tenant_service_account_email  — required when mode=IMPERSONATION; nullable
--                                   otherwise (STATIC_KEY tenants store SA email
--                                   in the existing service_account_email column
--                                   extracted from the JSON)
--
-- Invariant (CHECK ck_tgcred_mode_consistency):
--   IMPERSONATION → tenant_service_account_email NOT NULL AND
--                   encrypted_credential IS NULL
--   STATIC_KEY    → encrypted_credential NOT NULL

ALTER TABLE tenant_gcp_credentials
    ADD COLUMN credential_mode VARCHAR(32) NOT NULL DEFAULT 'STATIC_KEY';

ALTER TABLE tenant_gcp_credentials
    ADD COLUMN tenant_service_account_email VARCHAR(320);

-- The new IMPERSONATION mode stores no key material, so encrypted_credential
-- and key_id must become nullable. Existing STATIC_KEY rows already have
-- non-null values so the constraint relaxation is safe.
ALTER TABLE tenant_gcp_credentials
    ALTER COLUMN encrypted_credential DROP NOT NULL;

ALTER TABLE tenant_gcp_credentials
    ALTER COLUMN key_id DROP NOT NULL;

ALTER TABLE tenant_gcp_credentials
    ADD CONSTRAINT ck_tgcred_mode CHECK (credential_mode IN ('STATIC_KEY', 'IMPERSONATION'));

ALTER TABLE tenant_gcp_credentials
    ADD CONSTRAINT ck_tgcred_mode_consistency CHECK (
        (credential_mode = 'IMPERSONATION'
            AND tenant_service_account_email IS NOT NULL
            AND encrypted_credential IS NULL)
        OR
        (credential_mode = 'STATIC_KEY'
            AND encrypted_credential IS NOT NULL)
    );
