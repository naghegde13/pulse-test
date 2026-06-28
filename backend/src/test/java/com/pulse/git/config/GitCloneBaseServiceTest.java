package com.pulse.git.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitCloneBaseServiceTest {

    @Test
    void canonicalDefault_unwritable_fallsBackToTmpdir() {
        // Canonical default `/data/pulse/repos` is unwritable on macOS dev.
        // The @PostConstruct hook must swap to ${java.io.tmpdir}/pulse/repos.
        GitCloneBaseService svc = new GitCloneBaseService("/data/pulse/repos");
        svc.validateAndFallback();

        Path tmpFallback = Path.of(System.getProperty("java.io.tmpdir"), "pulse", "repos");
        Path resolved = svc.getResolvedCloneBase();
        boolean canonicalKept = resolved.toString().startsWith("/data/pulse/repos");
        boolean tmpdirFallback = resolved.equals(tmpFallback);
        assertThat(canonicalKept || tmpdirFallback)
                .as("Expected resolved to be canonical path (CI box) or tmpdir fallback (dev box), got: %s",
                        resolved)
                .isTrue();
    }

    @Test
    void operatorSuppliedUnwritablePath_failsFast(@TempDir Path tempDir) throws Exception {
        // Point at a path whose parent is a regular file so createDirectories throws.
        Path block = tempDir.resolve("blocker");
        Files.writeString(block, "i am a file, not a directory");
        Path nestedTarget = block.resolve("nested");

        GitCloneBaseService svc = new GitCloneBaseService(nestedTarget.toString());

        IllegalStateException ex = assertThrows(IllegalStateException.class, svc::validateAndFallback);
        assertThat(ex.getMessage()).contains("operator-supplied path");
        assertThat(ex.getMessage()).contains("PULSE_GIT_CLONE_BASE");
    }

    @Test
    void idempotentReInit_onWritableDirectory_succeeds(@TempDir Path tempDir) {
        GitCloneBaseService svc = new GitCloneBaseService(tempDir.toString());

        // First invocation creates / validates.
        svc.validateAndFallback();
        Path firstResolved = svc.getResolvedCloneBase();

        // Second invocation must be a no-op success.
        svc.validateAndFallback();
        Path secondResolved = svc.getResolvedCloneBase();

        assertThat(firstResolved).isEqualTo(secondResolved);
        assertThat(Files.exists(firstResolved)).isTrue();
    }
}
