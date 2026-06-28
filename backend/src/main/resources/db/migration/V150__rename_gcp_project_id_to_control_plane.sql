-- PKT-FINAL-6 (BUG-2026-05-25-49): HARD RENAME — gcp_project_id → control_plane_project_id.
--
-- The tenant-level GCP project field stored in tenant_gcp_configs (and
-- mirrored in tenant_gcp_credentials when STATIC_KEY mode extracts project_id
-- from the SA JSON) semantically identifies the CONTROL-PLANE project: where
-- the tenant SA lives, where Secret Manager refs resolve, where PULSE's
-- impersonation token is minted.
--
-- This is conceptually distinct from DATA-PLANE projects — per-environment
-- where the actual GCS buckets, Composer envs, Dataproc clusters, and
-- BigQuery datasets live. Those are tracked in storage_backends.gcp_project
-- (per env, per backend) and tenant_gcp_runtime_topology.<resource>_project_id
-- (per resource). Those columns are NOT renamed.
--
-- Operator decision (2026-05-25): HARD RENAME, NO back-compat shim. PULSE is
-- not yet live in production; we can take a single atomic migration rather
-- than carry a dual-name alias for a release.

ALTER TABLE tenant_gcp_configs
    RENAME COLUMN gcp_project_id TO control_plane_project_id;

ALTER TABLE tenant_gcp_credentials
    RENAME COLUMN gcp_project_id TO control_plane_project_id;
