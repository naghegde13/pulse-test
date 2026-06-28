package com.pulse.secret.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * PKT-FINAL-4 (BUG-38): owns the local-stub secret directory
 * configuration. Decouples the secret-stub root from
 * {@code pulse.git.clone-base} so that changing the clone-base between
 * backend sessions does not orphan previously-written secret files.
 *
 * <p>Behavior mirrors {@code GitCloneBaseService}:
 * <ul>
 *   <li>Canonical default {@code /data/pulse/secrets} unwritable →
 *       fall back to {@code ${java.io.tmpdir}/pulse/secrets} with a
 *       loud WARN.</li>
 *   <li>Operator-supplied custom path unwritable → fail-fast
 *       {@link IllegalStateException}.</li>
 *   <li>Idempotent: re-init on an already-writable directory
 *       succeeds without WARN.</li>
 * </ul>
 *
 * <p>Also runs a best-effort migration pass on boot to copy any
 * legacy {@code .enc} files from prior clone-base-coupled locations
 * to the new resolved root. The migration helper is one-release; remove
 * the {@link #migrateLegacyStubLocation()} method after release N+1.
 */
@Service
public class SecretStubBaseService {

    private static final Logger log = LoggerFactory.getLogger(SecretStubBaseService.class);
    static final String CANONICAL_DEFAULT_BASE = "/data/pulse/secrets";

    private Path resolvedSecretStubBase;

    public SecretStubBaseService(
            @Value("${pulse.secret.local-stub-base:/data/pulse/secrets}") String secretStubBase) {
        this.resolvedSecretStubBase = Path.of(secretStubBase);
    }

    @PostConstruct
    void validateAndFallback() {
        try {
            Files.createDirectories(resolvedSecretStubBase);
        } catch (IOException configError) {
            boolean isCanonicalDefault = resolvedSecretStubBase.toString().startsWith(CANONICAL_DEFAULT_BASE);
            if (isCanonicalDefault) {
                Path fallback = Path.of(System.getProperty("java.io.tmpdir"), "pulse", "secrets");
                try {
                    Files.createDirectories(fallback);
                    log.warn("Could not create secret-stub base directory at {}: {}. "
                                    + "Falling back to {}. Override with PULSE_SECRET_LOCAL_STUB_BASE=/writable/path.",
                            resolvedSecretStubBase, configError.getMessage(), fallback);
                    this.resolvedSecretStubBase = fallback;
                } catch (IOException fallbackError) {
                    throw new IllegalStateException(
                            "Failed to create secret-stub base directory at both canonical default ("
                                    + resolvedSecretStubBase + ") and fallback (" + fallback
                                    + "). Set PULSE_SECRET_LOCAL_STUB_BASE to a writable directory.",
                            fallbackError);
                }
            } else {
                throw new IllegalStateException(
                        "Failed to create secret-stub base directory at operator-supplied path "
                                + resolvedSecretStubBase
                                + ". Set PULSE_SECRET_LOCAL_STUB_BASE to a writable directory.",
                        configError);
            }
        }
    }

    /**
     * TODO: remove in release N+1.
     *
     * <p>One-release migration helper for BUG-38: scans the well-known
     * legacy locations where the secret stub used to live (coupled to
     * {@code pulse.git.clone-base}) and copies any {@code .enc} files
     * to the new {@link #resolvedSecretStubBase}. Existing files at the
     * target are NOT overwritten — the new write path wins.
     *
     * <p>Operators who explicitly set {@code PULSE_GIT_CLONE_BASE} to a
     * custom path should manually copy their {@code .enc} files or
     * re-register the PAT; the helper only scans the canonical default
     * and the tmpdir fallback used by the clone-base service.
     */
    @PostConstruct
    void migrateLegacyStubLocation() {
        String[] legacyLocations = new String[] {
                "/data/pulse/repos/.secrets",
                Path.of(System.getProperty("java.io.tmpdir"), "pulse", "repos", ".secrets").toString()
        };
        for (String legacyLoc : legacyLocations) {
            Path legacyPath = Path.of(legacyLoc);
            if (!Files.exists(legacyPath)) {
                continue;
            }
            if (legacyPath.equals(resolvedSecretStubBase)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(legacyPath)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".enc"))
                        .forEach(encFile -> {
                            Path target = resolvedSecretStubBase.resolve(encFile.getFileName());
                            if (!Files.exists(target)) {
                                try {
                                    Files.copy(encFile, target);
                                    log.info("Migrated legacy secret-stub file from {} to {}",
                                            encFile, target);
                                } catch (IOException e) {
                                    log.warn("Failed to migrate legacy secret-stub file {}: {}",
                                            encFile, e.getMessage());
                                }
                            }
                        });
            } catch (IOException e) {
                log.warn("Could not scan legacy secret-stub location {} for migration: {}",
                        legacyLoc, e.getMessage());
            }
        }
    }

    /** Returns the post-fallback resolved secret-stub base. */
    public Path getResolvedSecretStubBase() {
        return resolvedSecretStubBase;
    }
}
