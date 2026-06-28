package com.pulse.secret;

import com.pulse.config.GcpEnvironmentConfig;
import com.pulse.secret.service.GcpSecretManagerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 closeout — {@link GcpSecretManagerService.SecretReference}
 * preserves the full project + secret + version triplet from a
 * {@code gcp-sm://} URI. The previous PAT-resolution path discarded
 * project + version and always read from "dev"; the closeout adds
 * the typed parser so every consumer (Git provider adapter, Git
 * credential resolver) can honor a tenant-specific GCP project + a
 * pinned version.
 */
class GcpSecretManagerReferenceResolutionTest {

    @Test
    @DisplayName("parse extracts project, secret, and version from a well-formed URI")
    void parseFullReference() {
        var ref = GcpSecretManagerService.SecretReference.parse(
                "gcp-sm://projects/pulse-prod-001/secrets/my-secret/versions/7");
        assertEquals("pulse-prod-001", ref.projectId());
        assertEquals("my-secret", ref.secretId());
        assertEquals("7", ref.version());
    }

    @Test
    @DisplayName("parse preserves a non-dev project — never silently rewrites to 'dev'")
    void parseDoesNotRewriteProject() {
        var prod = GcpSecretManagerService.SecretReference.parse(
                "gcp-sm://projects/pulse-prod-001/secrets/x/versions/latest");
        var integ = GcpSecretManagerService.SecretReference.parse(
                "gcp-sm://projects/pulse-integration-002/secrets/x/versions/latest");
        assertNotEquals(prod.projectId(), integ.projectId(),
                "two different gcp-sm refs must keep their distinct project ids");
        assertEquals("pulse-prod-001", prod.projectId());
        assertEquals("pulse-integration-002", integ.projectId());
    }

    @Test
    @DisplayName("parse preserves an explicit version pin — not just 'latest'")
    void parsePreservesPinnedVersion() {
        var pinned = GcpSecretManagerService.SecretReference.parse(
                "gcp-sm://projects/pulse-dev/secrets/x/versions/12");
        assertEquals("12", pinned.version());
        var latest = GcpSecretManagerService.SecretReference.parse(
                "gcp-sm://projects/pulse-dev/secrets/x/versions/latest");
        assertEquals("latest", latest.version());
    }

    @Test
    @DisplayName("parse rejects malformed references with a clear error")
    void parseRejectsMalformed() {
        for (String bad : new String[]{
                null,
                "",
                "vault://projects/p/secrets/s/versions/1",
                "gcp-sm://projects/p/secrets/s/versions/",
                "gcp-sm://projects//secrets/s/versions/1",
                "gcp-sm://projects/p/secrets//versions/1",
                "gcp-sm://wrong/p/secrets/s/versions/1",
                "gcp-sm://projects/p/wrong/s/versions/1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> GcpSecretManagerService.SecretReference.parse(bad),
                    "expected parse to reject: " + bad);
        }
    }

    @Test
    @DisplayName("disableSecretByReference (local-stub) keys by secretId regardless of project / version in the URI")
    void disableSecretByReferenceLocalStubKeysBySecretId(@TempDir Path tempDir) throws Exception {
        GcpSecretManagerService service = localStub(tempDir);
        // Write the same logical secret by id; the URI's project +
        // version are recorded but ignored at write/read/disable time
        // in local-stub mode (mirrors read-time behavior in
        // getSecretValueByReference).
        service.createOrUpdateSecret("dev", "shared-secret", "v1", Map.of());
        Path stubFile = tempDir.resolve(".secrets/shared-secret.enc");
        assertTrue(Files.exists(stubFile), "stub file should be created");

        // Disable using a non-dev project + pinned version reference.
        // Local-stub must still find the file by secret id.
        service.disableSecretByReference(
                "gcp-sm://projects/pulse-prod-001/secrets/shared-secret/versions/7");

        assertFalse(Files.exists(stubFile),
                "active stub file should be moved aside on disable");
        assertTrue(Files.exists(tempDir.resolve(".secrets/shared-secret.enc.disabled")),
                "disabled marker file should exist");
    }

    @Test
    @DisplayName("disableSecretByReference rejects malformed URI before touching the filesystem")
    void disableSecretByReferenceRejectsMalformed(@TempDir Path tempDir) {
        GcpSecretManagerService service = localStub(tempDir);
        assertThrows(IllegalArgumentException.class,
                () -> service.disableSecretByReference("not-a-gcp-sm-uri"));
        assertThrows(IllegalArgumentException.class,
                () -> service.disableSecretByReference(null));
    }

    @Test
    @DisplayName("disableSecretByReference on missing local-stub secret is a no-op (idempotent)")
    void disableSecretByReferenceIsIdempotentForMissingSecret(@TempDir Path tempDir) {
        GcpSecretManagerService service = localStub(tempDir);
        // Best-effort disable: should not throw if the secret never existed.
        service.disableSecretByReference(
                "gcp-sm://projects/pulse-prod-001/secrets/never-written/versions/latest");
    }

    private static GcpSecretManagerService localStub(Path tempDir) {
        GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
        cfg.setSecretManagerMode("local-stub");
        // createOrUpdateSecret resolves a project id to embed in the
        // returned reference even in local-stub mode; supply a dev
        // mapping so the call doesn't trip on a missing config.
        GcpEnvironmentConfig.EnvironmentProject dev = new GcpEnvironmentConfig.EnvironmentProject();
        dev.setProjectId("pulse-dev");
        cfg.getEnvironmentProjects().put("dev", dev);
        return new GcpSecretManagerService(cfg, tempDir.toString());
    }
}
