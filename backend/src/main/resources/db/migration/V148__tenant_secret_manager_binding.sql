-- PKT-FINAL-5 / BUG-54: Tenant Secret Manager binding columns.
--
-- The Tenant Readiness Wizard "Secret Manager" category needs a per-tenant
-- knob distinct from the global `pulse.gcp.secret-manager-mode` so each tenant
-- can independently opt into LOCAL_STUB (dev) or GCP_SECRET_MANAGER (prod)
-- without flipping the global default.
--
-- Two new columns on the existing topology table:
--   * secret_authority_mode — LOCAL_STUB | GCP_SECRET_MANAGER | BLOCKED
--   * secret_name_prefix    — optional namespace prefix for secret IDs
--
-- The GSM project id is already on this row (`secret_manager_project_id`,
-- added in V141) so we reuse it instead of adding a duplicate column.

ALTER TABLE tenant_gcp_runtime_topology
    ADD COLUMN IF NOT EXISTS secret_authority_mode VARCHAR(50) NOT NULL DEFAULT 'LOCAL_STUB';

ALTER TABLE tenant_gcp_runtime_topology
    ADD COLUMN IF NOT EXISTS secret_name_prefix VARCHAR(100);

COMMENT ON COLUMN tenant_gcp_runtime_topology.secret_authority_mode IS
    'PKT-FINAL-5 / BUG-54: Per-tenant secret authority mode. '
    'LOCAL_STUB = AES-256/GCM on disk (dev-only, non-proof). '
    'GCP_SECRET_MANAGER = tenant-controlled GSM project. '
    'BLOCKED = configuration invalid or missing.';

COMMENT ON COLUMN tenant_gcp_runtime_topology.secret_name_prefix IS
    'PKT-FINAL-5 / BUG-54: Optional namespace prefix applied to all '
    'tenant-scoped secret IDs in GSM. NULL = no prefix.';
