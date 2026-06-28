#!/usr/bin/env bash
# =============================================================================
# PKT-0011: GCP Bootstrap Tenant Provisioner — Admin Utility
# =============================================================================
#
# PURPOSE:
#   Emits a reviewable manifest of gcloud commands that an authorized operator
#   must run to provision a least-privilege GCP service account + IAM bindings
#   for a PULSE tenant. This script is purely a planning utility — it makes no
#   GCP API calls, creates nothing, and reads no credentials. The operator
#   reviews the emitted text, runs the gcloud commands themselves, then
#   submits the resulting service account JSON to PULSE via
#   PUT /api/v1/tenants/{tenantId}/gcp-credentials.
#
# USAGE:
#   ./scripts/gcp-bootstrap-tenant-provisioner.sh \
#       --project <gcp-project-id> \
#       --tenant  <tenant-slug> \
#       [--region <gcp-region>] \
#       [--sa-name <service-account-name>] \
#       [--output <manifest-output-path>]
#
# EXAMPLE:
#   ./scripts/gcp-bootstrap-tenant-provisioner.sh \
#       --project pulse-proof-04261847 \
#       --tenant acme-lending \
#       --region us-central1
#
# SECURITY NOTES:
#   - This script never creates keys, service accounts, or IAM bindings.
#   - This script never logs private key material.
#   - The output manifest is safe for review and version control.
#   - After running the manifest commands, submit the service account JSON
#     through: PUT /api/v1/tenants/{tenantId}/gcp-credentials
# =============================================================================

set -euo pipefail

# Defaults
REGION="us-central1"
SA_NAME=""
OUTPUT=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --project)  PROJECT="$2"; shift 2 ;;
        --tenant)   TENANT="$2"; shift 2 ;;
        --region)   REGION="$2"; shift 2 ;;
        --sa-name)  SA_NAME="$2"; shift 2 ;;
        --output)   OUTPUT="$2"; shift 2 ;;
        --help|-h)
            head -35 "$0" | tail -30
            exit 0
            ;;
        *)
            echo "ERROR: Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

# Validate required arguments
if [[ -z "${PROJECT:-}" ]]; then
    echo "ERROR: --project is required" >&2
    exit 1
fi
if [[ -z "${TENANT:-}" ]]; then
    echo "ERROR: --tenant is required" >&2
    exit 1
fi

# Derive defaults
if [[ -z "$SA_NAME" ]]; then
    SA_NAME="pulse-${TENANT}"
fi
SA_EMAIL="${SA_NAME}@${PROJECT}.iam.gserviceaccount.com"

# BUG-2026-05-26-74: tenant-scoped slug used in IAM conditions to limit
# bucket operations to PULSE-managed buckets only. Mirrors the StorageScaffold
# bucket-name convention (pulse-<tenant-slug>-*).
TENANT_SLUG="${TENANT}"
# BUG-2026-05-26-74: custom role that grants storage.buckets.create
# WITHOUT the broader delete/lifecycle/setIamPolicy surface of
# roles/storage.admin. The role is bound with a resource-name IAM
# condition restricting it to buckets matching pulse-<tenant>-*.
TENANT_BUCKET_PROVISIONER_ROLE_ID="pulse.tenantBucketProvisioner.${TENANT_SLUG//-/_}"

# ---- Role Manifest ----
# Minimum roles following least-privilege for GCS lifecycle/medallion + Secret Manager
MINIMUM_ROLES=(
    "roles/storage.objectAdmin:bucket-level:Read/write objects in tenant medallion buckets"
    "roles/secretmanager.secretAccessor:secret-level:Read secret values for credential resolution"
)

OPTIONAL_ROLES=(
    "roles/storage.objectViewer:bucket-level:Read-only access to shared reference buckets"
    "roles/secretmanager.secretVersionManager:secret-level:Create/disable secret versions during rotation"
)

REJECTED_ROLES=(
    "roles/owner:Full project control — violates least privilege"
    "roles/editor:Broad edit access — violates least privilege"
    "roles/iam.securityAdmin:IAM admin — violates least privilege"
    "roles/resourcemanager.projectIamAdmin:Project IAM admin — violates least privilege"
)

# ---- Generate Manifest ----
generate_manifest() {
    cat <<MANIFEST_EOF
# =============================================================================
# PULSE GCP Bootstrap Manifest
# Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
# =============================================================================
#
# Tenant:          ${TENANT}
# GCP Project:     ${PROJECT}
# Region:          ${REGION}
# Service Account: ${SA_EMAIL}
#
# INSTRUCTIONS:
#   1. Review this manifest for correctness.
#   2. Run the gcloud commands below in the target GCP project.
#   3. Submit the resulting service-account key JSON through the PULSE API:
#
#      # Step 1: Configure tenant GCP project
#      curl -X PUT https://<pulse-api>/api/v1/tenants/{tenantId}/gcp-config \\
#           -H 'Content-Type: application/json' \\
#           -d '{"gcpProjectId": "${PROJECT}", "gcpRegion": "${REGION}"}'
#
#      # Step 2: Submit service account JSON (secret-bearing path)
#      curl -X PUT https://<pulse-api>/api/v1/tenants/{tenantId}/gcp-credentials \\
#           -H 'Content-Type: application/json' \\
#           -d '{"serviceAccountJson": "<contents of key.json>"}'
#
#      # Step 3: Verify identity probe
#      curl https://<pulse-api>/api/v1/tenants/{tenantId}/gcp-identity-probe
#
#   4. IAM binding execution remains OPERATOR_BLOCKED until the operator
#      runs the gcloud commands below.
# =============================================================================

# ---- Step 1: Create Service Account ----
# (Skip if service account already exists)
gcloud iam service-accounts create ${SA_NAME} \\
    --project="${PROJECT}" \\
    --display-name="PULSE tenant: ${TENANT}" \\
    --description="Service account for PULSE tenant ${TENANT} pipeline operations"

# ---- Step 2: Create Service Account Key ----
# WARNING: The key file contains private key material.
#          - Do NOT commit it to version control.
#          - Submit it through the PULSE API credential endpoint only.
#          - Delete the local key file after submission.
gcloud iam service-accounts keys create /tmp/pulse-${TENANT}-sa-key.json \\
    --project="${PROJECT}" \\
    --iam-account="${SA_EMAIL}"

# ---- Step 3: IAM Role Bindings (Least-Privilege) ----

## MINIMUM ROLES (required for tenant operations):
MANIFEST_EOF

    for entry in "${MINIMUM_ROLES[@]}"; do
        IFS=':' read -r role scope purpose <<< "$entry"
        cat <<ROLE_EOF

# ${purpose} (${scope})
gcloud projects add-iam-policy-binding ${PROJECT} \\
    --member="serviceAccount:${SA_EMAIL}" \\
    --role="${role}" \\
    --condition=None
ROLE_EOF
    done

    # BUG-2026-05-26-74: Tenant bucket provisioner — custom role + conditional
    # binding restricted to pulse-<tenant>-* bucket names. This grants the
    # tenant SA the SINGLE permission storage.buckets.create that PULSE's
    # StorageScaffoldService.executeInternal needs to provision domain
    # buckets, without granting bucket.delete / lifecycle / setIamPolicy
    # that come bundled in roles/storage.admin. The IAM condition pins the
    # blast radius to PULSE-managed bucket name patterns.
    cat <<ROLE_EOF

## CUSTOM ROLE: pulse.tenantBucketProvisioner (BUG-2026-05-26-74)
## Grants storage.buckets.create on PULSE-managed buckets only.
## The conditional binding below ensures the permission is scoped to
## bucket names matching pulse-${TENANT_SLUG}-* (e.g. pulse-${TENANT_SLUG}-dev-files).

# Step 3a: Define the custom role (idempotent — describe-then-create-or-update)
cat > /tmp/pulse-${TENANT_SLUG}-bucket-provisioner-role.yaml <<YAML
title: PULSE Tenant Bucket Provisioner (${TENANT_SLUG})
description: Allows PULSE to create new GCS buckets for tenant ${TENANT_SLUG}. Scoped via IAM condition to bucket names starting with pulse-${TENANT_SLUG}-.
stage: GA
includedPermissions:
  - storage.buckets.create
  - storage.buckets.get
YAML

gcloud iam roles create ${TENANT_BUCKET_PROVISIONER_ROLE_ID} \\
    --project="${PROJECT}" \\
    --file=/tmp/pulse-${TENANT_SLUG}-bucket-provisioner-role.yaml || \\
gcloud iam roles update ${TENANT_BUCKET_PROVISIONER_ROLE_ID} \\
    --project="${PROJECT}" \\
    --file=/tmp/pulse-${TENANT_SLUG}-bucket-provisioner-role.yaml

# Step 3b: Bind the custom role to the tenant SA WITH a resource-name
# condition so it can only create PULSE-managed buckets. This binding is
# what enables BUG-70b StorageScaffoldService bucket provisioning.
gcloud projects add-iam-policy-binding "${PROJECT}" \\
    --member="serviceAccount:${SA_EMAIL}" \\
    --role="projects/${PROJECT}/roles/${TENANT_BUCKET_PROVISIONER_ROLE_ID}" \\
    --condition="expression=resource.name.startsWith('projects/_/buckets/pulse-${TENANT_SLUG}-'),title=PulseManagedBucketsOnly,description=Restrict bucket operations to PULSE-managed name patterns"

# Clean up the role definition tempfile (it contains no secret material).
rm -f /tmp/pulse-${TENANT_SLUG}-bucket-provisioner-role.yaml
ROLE_EOF

    cat <<MANIFEST_EOF

## OPTIONAL ROLES (for full lifecycle support):
MANIFEST_EOF

    for entry in "${OPTIONAL_ROLES[@]}"; do
        IFS=':' read -r role scope purpose <<< "$entry"
        cat <<ROLE_EOF

# ${purpose} (${scope})
# gcloud projects add-iam-policy-binding ${PROJECT} \\
#     --member="serviceAccount:${SA_EMAIL}" \\
#     --role="${role}" \\
#     --condition=None
ROLE_EOF
    done

    cat <<MANIFEST_EOF

# ---- REJECTED ROLES (do NOT bind these) ----
# The following roles are flagged as overbroad by PULSE role manifest validation.
# Binding these roles will cause the PULSE role validator to reject the manifest.
MANIFEST_EOF

    for entry in "${REJECTED_ROLES[@]}"; do
        IFS=':' read -r role reason <<< "$entry"
        echo "# REJECTED: ${role} — ${reason}"
    done

    cat <<MANIFEST_EOF

# ---- Summary ----
# Project:           ${PROJECT}
# Service Account:   ${SA_EMAIL}
# Region:            ${REGION}
# IAM Execution:     OPERATOR_BLOCKED (run commands above manually)
# Next PULSE Action: Submit key JSON via PUT /api/v1/tenants/{tenantId}/gcp-credentials
MANIFEST_EOF
}

# ---- Output ----
if [[ -n "$OUTPUT" ]]; then
    generate_manifest > "$OUTPUT"
    echo "Manifest written to: ${OUTPUT}"
    echo ""
    echo "Summary:"
else
    generate_manifest
    echo ""
    echo "# ---- Static Validation Summary ----"
fi

echo "# Project:           ${PROJECT}"
echo "# Service Account:   ${SA_EMAIL}"
echo "# Key ID:            (generated after operator runs gcloud commands)"
echo "# Fingerprint:       (generated after operator runs gcloud commands)"
echo "# Region:            ${REGION}"
echo "# IAM Execution:     OPERATOR_BLOCKED"
echo "# Next PULSE Action: Configure tenant via API, submit key JSON through secret-bearing endpoint"
