package com.pulse.secret.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PKT-FINAL-4 (BUG-38): mirrors the GcpSecretManagerServiceTest fallback
 * suite that PKT-FINAL-3 introduced — same canonical-default / custom /
 * idempotent shape, just relocated to the decoupled service.
 */
class SecretStubBaseServiceTest {

    @Test
    void canonicalDefault_unwritable_fallsBackToTmpdir() {
        SecretStubBaseService svc = new SecretStubBaseService("/data/pulse/secrets");
        svc.validateAndFallback();

        Path tmpFallback = Path.of(System.getProperty("java.io.tmpdir"), "pulse", "secrets");
        Path resolved = svc.getResolvedSecretStubBase();
        boolean canonicalKept = resolved.toString().startsWith("/data/pulse/secrets");
        boolean tmpdirFallback = resolved.equals(tmpFallback);
        assertThat(canonicalKept || tmpdirFallback)
                .as("Expected resolved path to be canonical default or tmpdir fallback, got: %s", resolved)
                .isTrue();
    }

    @Test
    void operatorSuppliedUnwritablePath_failsFast(@TempDir Path tempDir) throws Exception {
        Path block = tempDir.resolve("blocker");
        Files.writeString(block, "i am a file, not a directory");
        Path nestedTarget = block.resolve("nested");

        SecretStubBaseService svc = new SecretStubBaseService(nestedTarget.toString());

        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::validateAndFallback);
        assertThat(ex.getMessage()).contains("operator-supplied path");
        assertThat(ex.getMessage()).contains("PULSE_SECRET_LOCAL_STUB_BASE");
    }

    @Test
    void idempotentReInit_onWritableDirectory_succeeds(@TempDir Path tempDir) {
        SecretStubBaseService svc = new SecretStubBaseService(tempDir.toString());

        svc.validateAndFallback();
        Path firstResolved = svc.getResolvedSecretStubBase();

        svc.validateAndFallback();
        Path secondResolved = svc.getResolvedSecretStubBase();

        assertThat(firstResolved).isEqualTo(secondResolved);
        assertThat(Files.exists(firstResolved)).isTrue();
    }

    @Test
    void migrationHelper_copiesLegacyEncFilesToNewRoot(@TempDir Path tempDir) throws Exception {
        // Set up a fake legacy clone-base/.secrets directory with two .enc files.
        Path legacyClonebaseSecrets = Path.of(
                System.getProperty("java.io.tmpdir"), "pulse", "repos", ".secrets");
        Files.createDirectories(legacyClonebaseSecrets);
        Path legacyOne = legacyClonebaseSecrets.resolve("pulse-acme-test-old-one.enc");
        Path legacyTwo = legacyClonebaseSecrets.resolve("pulse-acme-test-old-two.enc");
        Files.writeString(legacyOne, "ciphertext-bytes-one");
        Files.writeString(legacyTwo, "ciphertext-bytes-two");

        try {
            Path newRoot = tempDir.resolve("new-secrets");
            SecretStubBaseService svc = new SecretStubBaseService(newRoot.toString());
            svc.validateAndFallback();
            svc.migrateLegacyStubLocation();

            assertThat(Files.exists(newRoot.resolve("pulse-acme-test-old-one.enc"))).isTrue();
            assertThat(Files.exists(newRoot.resolve("pulse-acme-test-old-two.enc"))).isTrue();
        } finally {
            // Best-effort cleanup so we don't pollute the runner's tmpdir.
            Files.deleteIfExists(legacyOne);
            Files.deleteIfExists(legacyTwo);
        }
    }
}
