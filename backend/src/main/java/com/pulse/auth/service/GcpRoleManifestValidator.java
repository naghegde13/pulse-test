package com.pulse.auth.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Static validator for GCP IAM role manifests. Ensures least-privilege principles:
 * rejects or warns on Owner, Editor, and broad project-admin roles. Validates that
 * roles cover the minimum needed for GCS lifecycle/medallion and Secret Manager setup.
 * <p>
 * This validator operates on role binding manifests (lists of role strings) without
 * making live GCP API calls.
 */
@Component
public class GcpRoleManifestValidator {

    /** Roles that are unconditionally rejected as overbroad. */
    private static final Set<String> REJECTED_ROLES = Set.of(
            "roles/owner",
            "roles/editor",
            "roles/iam.securityadmin",
            "roles/resourcemanager.projectiamadmin"
    );

    /** Roles that trigger a warning — allowed but flagged as potentially overbroad. */
    private static final Set<String> WARNED_ROLES = Set.of(
            "roles/storage.admin",
            "roles/secretmanager.admin"
    );

    /**
     * BUG-2026-05-26-74: Permissions that MUST carry a resource-name IAM
     * condition when bound. {@code storage.buckets.create} is the only
     * entry today — granting it project-wide would let the tenant SA
     * provision arbitrary buckets in the project, defeating PULSE's
     * tenant-scoped bucket naming convention.
     */
    static final Set<String> PERMISSIONS_REQUIRING_RESOURCE_CONDITION = Set.of(
            "storage.buckets.create"
    );

    /**
     * BUG-2026-05-26-74: Permission token added by the {@code
     * pulse.tenantBucketProvisioner.*} custom role emitted by
     * {@code scripts/gcp-bootstrap-tenant-provisioner.sh}. The role is
     * conditionally bound to the tenant SA so PULSE's
     * {@code StorageScaffoldService} bucket-provisioning loop can succeed
     * without granting broader bucket admin access.
     */
    static final String TENANT_BUCKET_PROVISIONER_PERMISSION = "storage.buckets.create";

    /** Minimum recommended roles for GCS lifecycle/medallion + Secret Manager setup. */
    static final Set<String> RECOMMENDED_ROLES = Set.of(
            "roles/storage.objectAdmin",
            "roles/secretmanager.secretAccessor"
    );

    /** Optional roles useful for full lifecycle support. */
    static final Set<String> OPTIONAL_LIFECYCLE_ROLES = Set.of(
            "roles/storage.objectViewer",
            "roles/secretmanager.secretVersionManager"
    );

    /**
     * Validates a list of IAM role bindings. Returns a result with status,
     * rejected roles, warned roles, and coverage assessment.
     *
     * @param roles the IAM role strings to validate
     * @return validation result with detailed findings
     */
    public ValidationResult validate(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return new ValidationResult("rejected",
                    List.of("No roles provided — at least one role binding is required"),
                    List.of(), false, false);
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean hasGcsAccess = false;
        boolean hasSecretManagerAccess = false;

        for (String role : roles) {
            String normalized = role.strip().toLowerCase();

            if (REJECTED_ROLES.contains(normalized)) {
                errors.add("Role '" + role + "' is rejected: overbroad project-level privilege. "
                        + "Use narrowly scoped roles (e.g., roles/storage.objectAdmin, "
                        + "roles/secretmanager.secretAccessor) instead.");
            }

            if (WARNED_ROLES.contains(normalized)) {
                warnings.add("Role '" + role + "' grants broader access than typically needed. "
                        + "Consider using a more specific role.");
            }

            if (normalized.startsWith("roles/storage.")) {
                hasGcsAccess = true;
            }
            if (normalized.startsWith("roles/secretmanager.")) {
                hasSecretManagerAccess = true;
            }
        }

        if (!hasGcsAccess) {
            warnings.add("No GCS storage role detected — required for lifecycle/medallion file operations.");
        }
        if (!hasSecretManagerAccess) {
            warnings.add("No Secret Manager role detected — required for credential resolution.");
        }

        String status;
        if (!errors.isEmpty()) {
            status = "rejected";
        } else if (!warnings.isEmpty()) {
            status = "warning";
        } else {
            status = "valid";
        }

        return new ValidationResult(status, errors, warnings, hasGcsAccess, hasSecretManagerAccess);
    }

    /**
     * Returns a recommended least-privilege role manifest for GCS lifecycle/medallion
     * and Secret Manager setup.
     */
    public Map<String, Object> getRecommendedManifest(String gcpProjectId) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("gcpProjectId", gcpProjectId);
        manifest.put("minimumRoles", List.of(
                Map.of("role", "roles/storage.objectAdmin",
                        "scope", "bucket-level",
                        "purpose", "Read/write objects in tenant medallion buckets (bronze/silver/gold)"),
                Map.of("role", "roles/secretmanager.secretAccessor",
                        "scope", "secret-level",
                        "purpose", "Read secret values for credential resolution")
        ));
        manifest.put("optionalRoles", List.of(
                Map.of("role", "roles/storage.objectViewer",
                        "scope", "bucket-level",
                        "purpose", "Read-only access to shared reference buckets"),
                Map.of("role", "roles/secretmanager.secretVersionManager",
                        "scope", "secret-level",
                        "purpose", "Create/disable secret versions during rotation")
        ));
        manifest.put("rejectedRoles", List.of(
                Map.of("role", "roles/owner", "reason", "Full project control — violates least privilege"),
                Map.of("role", "roles/editor", "reason", "Broad edit access — violates least privilege"),
                Map.of("role", "roles/iam.securityadmin", "reason", "IAM admin — violates least privilege"),
                Map.of("role", "roles/resourcemanager.projectiamadmin", "reason", "Project IAM admin — violates least privilege")
        ));
        return manifest;
    }

    public record ValidationResult(
            String status,
            List<String> errors,
            List<String> warnings,
            boolean hasGcsAccess,
            boolean hasSecretManagerAccess
    ) {}

    /**
     * BUG-2026-05-26-74: Validate a list of IAM role bindings (each binding
     * carrying its included permissions and optional IAM condition expression).
     * This is a strict superset of {@link #validate(List)}: it runs the
     * legacy role-name validation AND enforces two BUG-74 invariants:
     * <ol>
     *   <li>The manifest must include at least one binding that grants
     *       {@code storage.buckets.create} (else PULSE
     *       {@code StorageScaffoldService.executeInternal} cannot provision
     *       tenant buckets — BUG-70b regression).</li>
     *   <li>Every binding whose included permissions contain
     *       {@code storage.buckets.create} (or any other permission listed in
     *       {@link #PERMISSIONS_REQUIRING_RESOURCE_CONDITION}) MUST carry a
     *       non-blank IAM condition expression — otherwise the SA would gain
     *       project-wide bucket-create authority. See
     *       {@code scripts/gcp-bootstrap-tenant-provisioner.sh}.</li>
     * </ol>
     *
     * @param bindings the role bindings to validate
     * @return a ValidationResult; status is "rejected" if either invariant
     *         fails or any role-name check rejects.
     */
    public ValidationResult validateBindings(List<RoleBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return new ValidationResult("rejected",
                    List.of("No role bindings provided — at least one binding is required"),
                    List.of(), false, false);
        }

        // Run the legacy role-name validator on the union of role strings.
        List<String> roleNames = new ArrayList<>(bindings.size());
        for (RoleBinding b : bindings) {
            roleNames.add(b.role());
        }
        ValidationResult legacy = validate(roleNames);

        List<String> errors = new ArrayList<>(legacy.errors());
        List<String> warnings = new ArrayList<>(legacy.warnings());

        // BUG-74 invariant 1: storage.buckets.create must be granted.
        boolean grantsBucketCreate = bindings.stream().anyMatch(b ->
                b.includedPermissions() != null
                        && b.includedPermissions().contains(TENANT_BUCKET_PROVISIONER_PERMISSION));
        if (!grantsBucketCreate) {
            errors.add("Manifest does not grant '" + TENANT_BUCKET_PROVISIONER_PERMISSION
                    + "'. Required by StorageScaffoldService.executeInternal"
                    + " (BUG-2026-05-26-70b). Bind the pulse.tenantBucketProvisioner"
                    + " custom role emitted by"
                    + " scripts/gcp-bootstrap-tenant-provisioner.sh.");
        }

        // BUG-74 invariant 2: any binding granting a resource-conditional
        // permission MUST carry a non-blank condition expression.
        for (RoleBinding b : bindings) {
            if (b.includedPermissions() == null) continue;
            boolean needsCondition = b.includedPermissions().stream()
                    .anyMatch(PERMISSIONS_REQUIRING_RESOURCE_CONDITION::contains);
            if (needsCondition
                    && (b.conditionExpression() == null
                        || b.conditionExpression().isBlank())) {
                errors.add("Binding for role '" + b.role() + "' grants"
                        + " bucket-create without an IAM resource condition."
                        + " Add a condition restricting it to PULSE-managed"
                        + " bucket names (e.g."
                        + " resource.name.startsWith('projects/_/buckets/pulse-<tenant>-'))."
                        + " See scripts/gcp-bootstrap-tenant-provisioner.sh.");
            }
        }

        String status;
        if (!errors.isEmpty()) {
            status = "rejected";
        } else if (!warnings.isEmpty()) {
            status = "warning";
        } else {
            status = "valid";
        }
        return new ValidationResult(status, errors, warnings,
                legacy.hasGcsAccess(), legacy.hasSecretManagerAccess());
    }

    /**
     * BUG-2026-05-26-74: A single IAM role binding in the bootstrap manifest.
     * {@code includedPermissions} captures the role's permission surface so
     * custom roles (e.g. {@code pulse.tenantBucketProvisioner.*}) can be
     * validated by permission, not just by role name.
     * {@code conditionExpression} captures the IAM condition CEL expression
     * (e.g. {@code resource.name.startsWith('projects/_/buckets/pulse-acme-lending-')})
     * — null/blank means the binding is unconditional.
     */
    public record RoleBinding(
            String role,
            List<String> includedPermissions,
            String conditionExpression
    ) {
        public RoleBinding {
            Objects.requireNonNull(role, "role");
        }

        /** Convenience constructor for a built-in role with no condition. */
        public static RoleBinding unconditional(String role) {
            return new RoleBinding(role, List.of(), null);
        }

        /** Convenience constructor for a custom role + permissions + condition. */
        public static RoleBinding conditional(String role, List<String> perms, String expr) {
            return new RoleBinding(role, List.copyOf(perms), expr);
        }
    }
}
