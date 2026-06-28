package com.pulse.auth.service;

import com.pulse.auth.service.GcpRoleManifestValidator.RoleBinding;
import com.pulse.auth.service.GcpRoleManifestValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-2026-05-26-74 — verifies that
 * {@link GcpRoleManifestValidator#validateBindings(List)} enforces the
 * extended manifest invariants emitted by the BUG-74-updated bootstrap
 * script:
 *
 * <ol>
 *   <li>The manifest MUST include a binding that grants
 *       {@code storage.buckets.create} (else BUG-70b
 *       StorageScaffoldService bucket provisioning fails).</li>
 *   <li>Any binding granting {@code storage.buckets.create} MUST carry a
 *       non-blank IAM resource-name condition expression — granting
 *       project-wide bucket-create authority is unconditionally rejected.</li>
 * </ol>
 */
class GcpRoleManifestRequiredEntriesTest {

    private final GcpRoleManifestValidator validator = new GcpRoleManifestValidator();

    /**
     * Happy path: the bootstrap script's emitted manifest binds
     * roles/storage.objectAdmin (unconditional) + roles/secretmanager.secretAccessor
     * (unconditional) + the pulse.tenantBucketProvisioner.* custom role with
     * storage.buckets.create gated by a CEL condition pinning the role to
     * pulse-<tenant>-* bucket names. The validator MUST accept this shape.
     */
    @Test
    void validateBindings_bootstrapManifestShape_accepted() {
        List<RoleBinding> bindings = List.of(
                RoleBinding.unconditional("roles/storage.objectAdmin"),
                RoleBinding.unconditional("roles/secretmanager.secretAccessor"),
                RoleBinding.conditional(
                        "projects/proj/roles/pulse.tenantBucketProvisioner.acme_lending",
                        List.of("storage.buckets.create", "storage.buckets.get"),
                        "resource.name.startsWith('projects/_/buckets/pulse-acme-lending-')")
        );

        ValidationResult result = validator.validateBindings(bindings);

        assertEquals("valid", result.status(),
                "expected valid, got errors=" + result.errors()
                        + " warnings=" + result.warnings());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.hasGcsAccess());
        assertTrue(result.hasSecretManagerAccess());
    }

    /**
     * Missing storage.buckets.create entirely (legacy pre-BUG-74 manifest)
     * MUST be rejected so the operator notices that BUG-70b scaffolding
     * cannot succeed until the bootstrap script is re-run.
     */
    @Test
    void validateBindings_missingBucketCreate_rejected() {
        List<RoleBinding> bindings = List.of(
                RoleBinding.unconditional("roles/storage.objectAdmin"),
                RoleBinding.unconditional("roles/secretmanager.secretAccessor")
        );

        ValidationResult result = validator.validateBindings(bindings);

        assertEquals("rejected", result.status());
        assertTrue(result.errors().stream().anyMatch(e ->
                e.contains("storage.buckets.create")
                        && e.contains("BUG-2026-05-26-70b")),
                "expected guidance about BUG-70b dependency, got "
                        + result.errors());
        assertTrue(result.errors().stream().anyMatch(e ->
                e.contains("gcp-bootstrap-tenant-provisioner.sh")),
                "expected pointer to bootstrap script, got "
                        + result.errors());
    }

    /**
     * Granting storage.buckets.create UNCONDITIONALLY (no IAM condition) MUST
     * be rejected — that would let the tenant SA provision arbitrary buckets
     * in the project, bypassing PULSE's tenant-scoped naming convention.
     */
    @Test
    void validateBindings_bucketCreateWithoutCondition_rejected() {
        List<RoleBinding> bindings = List.of(
                RoleBinding.unconditional("roles/storage.objectAdmin"),
                RoleBinding.unconditional("roles/secretmanager.secretAccessor"),
                // Custom role with the right permission but NO condition →
                // must be rejected. This is the "operator forgot the
                // --condition flag" failure mode.
                new RoleBinding(
                        "projects/proj/roles/pulse.tenantBucketProvisioner.acme_lending",
                        List.of("storage.buckets.create"),
                        null)
        );

        ValidationResult result = validator.validateBindings(bindings);

        assertEquals("rejected", result.status());
        assertTrue(result.errors().stream().anyMatch(e ->
                e.contains("without an IAM resource condition")),
                "expected condition-missing error, got " + result.errors());
        assertTrue(result.errors().stream().anyMatch(e ->
                e.contains("resource.name.startsWith")),
                "expected guidance citing the CEL expression shape, got "
                        + result.errors());
    }

    /**
     * Blank condition expression (operator passed --condition= with nothing)
     * is just as bad as missing — must be rejected with the same diagnostic.
     */
    @Test
    void validateBindings_bucketCreateWithBlankCondition_rejected() {
        List<RoleBinding> bindings = List.of(
                RoleBinding.unconditional("roles/storage.objectAdmin"),
                RoleBinding.unconditional("roles/secretmanager.secretAccessor"),
                new RoleBinding(
                        "projects/proj/roles/pulse.tenantBucketProvisioner.acme_lending",
                        List.of("storage.buckets.create"),
                        "   ")
        );

        ValidationResult result = validator.validateBindings(bindings);

        assertEquals("rejected", result.status());
        assertTrue(result.errors().stream().anyMatch(e ->
                e.contains("without an IAM resource condition")));
    }

    /**
     * Binding roles/storage.admin (which includes storage.buckets.create
     * implicitly) WITHOUT a resource condition must also be rejected — the
     * validator should see the permission in the included list and require
     * a condition regardless of whether the role is custom or built-in.
     */
    @Test
    void validateBindings_storageAdminWithoutCondition_rejected() {
        List<RoleBinding> bindings = List.of(
                // includedPermissions captured here so the validator can
                // see that storage.admin actually grants bucket-create.
                new RoleBinding(
                        "roles/storage.admin",
                        List.of("storage.buckets.create",
                                "storage.buckets.delete",
                                "storage.buckets.update"),
                        null),
                RoleBinding.unconditional("roles/secretmanager.secretAccessor")
        );

        ValidationResult result = validator.validateBindings(bindings);

        assertEquals("rejected", result.status());
        assertTrue(result.errors().stream().anyMatch(e ->
                e.contains("without an IAM resource condition")),
                "expected condition rejection for unconditional storage.admin,"
                        + " got " + result.errors());
    }

    /**
     * Empty/null binding lists are rejected (same contract as the legacy
     * validate(List&lt;String&gt;) entrypoint).
     */
    @Test
    void validateBindings_empty_rejected() {
        ValidationResult result = validator.validateBindings(List.of());
        assertEquals("rejected", result.status());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void validateBindings_null_rejected() {
        ValidationResult result = validator.validateBindings(null);
        assertEquals("rejected", result.status());
    }

    /**
     * Legacy {@link GcpRoleManifestValidator#validate(List)} entrypoint
     * MUST remain backward-compatible — older callers that only have
     * role names (and don't carry condition/permission metadata) should
     * still receive the original verdicts for least-privilege role names.
     * This guards the existing GcpRoleManifestValidatorTest contract.
     */
    @Test
    void legacyValidate_stillAcceptsLeastPrivilegeRoles() {
        ValidationResult result = validator.validate(List.of(
                "roles/storage.objectAdmin",
                "roles/secretmanager.secretAccessor"));
        assertEquals("valid", result.status());
    }
}
