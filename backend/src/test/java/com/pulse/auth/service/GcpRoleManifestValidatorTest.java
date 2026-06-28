package com.pulse.auth.service;

import com.pulse.auth.service.GcpRoleManifestValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GcpRoleManifestValidatorTest {

    private final GcpRoleManifestValidator validator = new GcpRoleManifestValidator();

    @Test
    void validate_leastPrivilegeRoles_valid() {
        List<String> roles = List.of(
                "roles/storage.objectAdmin",
                "roles/secretmanager.secretAccessor");

        ValidationResult result = validator.validate(roles);

        assertEquals("valid", result.status());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.warnings().isEmpty());
        assertTrue(result.hasGcsAccess());
        assertTrue(result.hasSecretManagerAccess());
    }

    @Test
    void validate_ownerRole_rejected() {
        List<String> roles = List.of("roles/owner");

        ValidationResult result = validator.validate(roles);

        assertEquals("rejected", result.status());
        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().get(0).contains("overbroad"));
    }

    @Test
    void validate_editorRole_rejected() {
        List<String> roles = List.of("roles/editor");

        ValidationResult result = validator.validate(roles);

        assertEquals("rejected", result.status());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("overbroad")));
    }

    @Test
    void validate_securityAdminRole_rejected() {
        // Input uses GCP's canonical casing; validator normalizes to lowercase
        List<String> roles = List.of("roles/iam.securityAdmin");
        ValidationResult result = validator.validate(roles);
        assertEquals("rejected", result.status());
    }

    @Test
    void validate_projectIamAdminRole_rejected() {
        List<String> roles = List.of("roles/resourcemanager.projectIamAdmin");
        ValidationResult result = validator.validate(roles);
        assertEquals("rejected", result.status());
    }

    @Test
    void validate_storageAdmin_warned() {
        List<String> roles = List.of(
                "roles/storage.admin",
                "roles/secretmanager.secretAccessor");

        ValidationResult result = validator.validate(roles);

        assertEquals("warning", result.status());
        assertTrue(result.errors().isEmpty());
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("broader")));
    }

    @Test
    void validate_secretManagerAdmin_warned() {
        List<String> roles = List.of(
                "roles/storage.objectAdmin",
                "roles/secretmanager.admin");

        ValidationResult result = validator.validate(roles);

        assertEquals("warning", result.status());
    }

    @Test
    void validate_noGcsRole_warnsAboutMissingGcs() {
        List<String> roles = List.of("roles/secretmanager.secretAccessor");

        ValidationResult result = validator.validate(roles);

        assertEquals("warning", result.status());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("GCS")));
        assertFalse(result.hasGcsAccess());
        assertTrue(result.hasSecretManagerAccess());
    }

    @Test
    void validate_noSecretManagerRole_warnsAboutMissingSm() {
        List<String> roles = List.of("roles/storage.objectAdmin");

        ValidationResult result = validator.validate(roles);

        assertEquals("warning", result.status());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Secret Manager")));
        assertTrue(result.hasGcsAccess());
        assertFalse(result.hasSecretManagerAccess());
    }

    @Test
    void validate_emptyRoles_rejected() {
        ValidationResult result = validator.validate(List.of());

        assertEquals("rejected", result.status());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void validate_nullRoles_rejected() {
        ValidationResult result = validator.validate(null);

        assertEquals("rejected", result.status());
    }

    @Test
    void validate_mixedRejectedAndValid_stillRejected() {
        List<String> roles = List.of(
                "roles/storage.objectAdmin",
                "roles/owner",
                "roles/secretmanager.secretAccessor");

        ValidationResult result = validator.validate(roles);

        assertEquals("rejected", result.status());
    }

    @Test
    void getRecommendedManifest_containsProjectAndRoles() {
        Map<String, Object> manifest = validator.getRecommendedManifest("pulse-proof-04261847");

        assertEquals("pulse-proof-04261847", manifest.get("gcpProjectId"));
        assertNotNull(manifest.get("minimumRoles"));
        assertNotNull(manifest.get("optionalRoles"));
        assertNotNull(manifest.get("rejectedRoles"));
    }

    @Test
    void getRecommendedManifest_rejectedRolesIncludeOwnerAndEditor() {
        Map<String, Object> manifest = validator.getRecommendedManifest("p");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> rejected = (List<Map<String, String>>) manifest.get("rejectedRoles");
        assertTrue(rejected.stream().anyMatch(r -> r.get("role").equals("roles/owner")));
        assertTrue(rejected.stream().anyMatch(r -> r.get("role").equals("roles/editor")));
    }
}
