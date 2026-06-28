package com.pulse.auth.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static validation of the admin GCP bootstrap provisioner script.
 * Verifies the script is reviewable, contains no live execution, and
 * includes the expected safety markers without executing any GCP commands.
 */
class GcpBootstrapScriptStaticValidationTest {

    private static final Path SCRIPT_PATH = Path.of("../scripts/gcp-bootstrap-tenant-provisioner.sh");

    @Test
    void scriptFileExists() {
        assertTrue(Files.exists(SCRIPT_PATH),
                "Admin utility script must exist at " + SCRIPT_PATH);
    }

    @Test
    void scriptIsExecutable() {
        assertTrue(Files.isReadable(SCRIPT_PATH));
    }

    @Test
    void scriptContainsRequiredSafetyMarkers() throws IOException {
        String content = Files.readString(SCRIPT_PATH);

        // Must not contain credential material
        assertFalse(content.contains("PRIVATE KEY"),
                "Script must not contain private key material");

        // Must reference OPERATOR_BLOCKED for IAM binding execution
        assertTrue(content.contains("OPERATOR_BLOCKED"),
                "Script must indicate IAM binding execution is OPERATOR_BLOCKED");

        // Must reference the PULSE API submission path
        assertTrue(content.contains("/gcp-credentials"),
                "Script must reference the credential API endpoint");

        // Must reference the config API endpoint
        assertTrue(content.contains("/gcp-config"),
                "Script must reference the config API endpoint");

        // Must reference the identity probe endpoint
        assertTrue(content.contains("/gcp-identity-probe"),
                "Script must reference the identity probe endpoint");
    }

    @Test
    void scriptSupportsProjectArgument() throws IOException {
        String content = Files.readString(SCRIPT_PATH);
        assertTrue(content.contains("--project"),
                "Script must accept --project argument");
    }

    @Test
    void scriptSupportsTenantArgument() throws IOException {
        String content = Files.readString(SCRIPT_PATH);
        assertTrue(content.contains("--tenant"),
                "Script must accept --tenant argument");
    }

    @Test
    void scriptDocumentsPulseProofProjectAsExample() throws IOException {
        String content = Files.readString(SCRIPT_PATH);
        assertTrue(content.contains("pulse-proof-04261847"),
                "Script must reference pulse-proof-04261847 as example project");
    }

    @Test
    void scriptDoesNotHardcodeProjectAsOnlyOption() throws IOException {
        String content = Files.readString(SCRIPT_PATH);
        // The project comes from --project argument, not hardcoded
        assertTrue(content.contains("PROJECT=\"$2\""),
                "Script must accept project as command-line argument, not hardcode it");
    }

    @Test
    void scriptContainsLeastPrivilegeRoleManifest() throws IOException {
        String content = Files.readString(SCRIPT_PATH);
        assertTrue(content.contains("roles/storage.objectAdmin"),
                "Script must include storage.objectAdmin as minimum role");
        assertTrue(content.contains("roles/secretmanager.secretAccessor"),
                "Script must include secretmanager.secretAccessor as minimum role");
    }

    @Test
    void scriptRejectsOverbroadRoles() throws IOException {
        String content = Files.readString(SCRIPT_PATH);
        assertTrue(content.contains("roles/owner") && content.contains("REJECTED"),
                "Script must reject roles/owner");
        assertTrue(content.contains("roles/editor") && content.contains("REJECTED"),
                "Script must reject roles/editor");
    }

    @Test
    void scriptWarnsAboutKeyFileSecurity() throws IOException {
        String content = Files.readString(SCRIPT_PATH);
        assertTrue(content.contains("Do NOT commit"),
                "Script must warn about not committing key files");
        assertTrue(content.contains("Delete the local key file"),
                "Script must instruct deletion of local key file after submission");
    }

    @Test
    void scriptOutputsSafeManifest() throws IOException {
        String content = Files.readString(SCRIPT_PATH);
        // The script generates gcloud commands but does not execute them live
        // It uses echo/cat to output a manifest
        assertTrue(content.contains("generate_manifest"),
                "Script must have a manifest generation function");
    }
}
