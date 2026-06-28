package com.pulse.git.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PKT-FINAL-4 (BUG-36): owns the clone-base directory configuration for
 * tenant Git working trees. Mirrors the fallback semantics that
 * PKT-FINAL-3 introduced on the secret-stub root (
 * {@code GcpSecretManagerService}) so both surfaces behave the same way
 * on macOS dev where {@code /data} is unwritable.
 *
 * <p>Behavior:
 * <ul>
 *   <li>If the configured base is the canonical default and creating it
 *       fails, fall back to {@code ${java.io.tmpdir}/pulse/repos} and
 *       emit a loud WARN explaining how to override.</li>
 *   <li>If the operator supplied an explicit path (anything other than
 *       the canonical default), fail fast on creation error — silent
 *       fallback would mask the operator's intent.</li>
 *   <li>Re-init (e.g. DevTools restart) is idempotent: an already
 *       writable directory simply succeeds.</li>
 * </ul>
 *
 * <p>The legacy env-var alias {@code PULSE_GIT_LOCAL_BASE} is honored
 * for one release; remove in N+1.
 */
@Service
public class GitCloneBaseService {

    private static final Logger log = LoggerFactory.getLogger(GitCloneBaseService.class);
    /** Canonical default base path; auto-fallback only fires when this exact prefix fails. */
    static final String CANONICAL_DEFAULT_BASE = "/data/pulse/repos";

    private Path resolvedCloneBase;

    public GitCloneBaseService(
            @Value("${pulse.git.clone-base:${pulse.git.local-repo-base:/data/pulse/repos}}") String cloneBase) {
        this.resolvedCloneBase = Path.of(cloneBase);
    }

    @PostConstruct
    void validateAndFallback() {
        try {
            Files.createDirectories(resolvedCloneBase);
        } catch (IOException configError) {
            boolean isCanonicalDefault = resolvedCloneBase.toString().startsWith(CANONICAL_DEFAULT_BASE);
            if (isCanonicalDefault) {
                Path fallback = Path.of(System.getProperty("java.io.tmpdir"), "pulse", "repos");
                try {
                    Files.createDirectories(fallback);
                    log.warn("Could not create git clone-base directory at {}: {}. "
                                    + "Falling back to {}. Override with PULSE_GIT_CLONE_BASE=/writable/path "
                                    + "(legacy PULSE_GIT_LOCAL_BASE still accepted for one release).",
                            resolvedCloneBase, configError.getMessage(), fallback);
                    this.resolvedCloneBase = fallback;
                } catch (IOException fallbackError) {
                    throw new IllegalStateException(
                            "Failed to create git clone-base directory at both canonical default ("
                                    + resolvedCloneBase + ") and fallback (" + fallback
                                    + "). Set PULSE_GIT_CLONE_BASE to a writable directory.",
                            fallbackError);
                }
            } else {
                throw new IllegalStateException(
                        "Failed to create git clone-base directory at operator-supplied path "
                                + resolvedCloneBase
                                + ". Set PULSE_GIT_CLONE_BASE (or legacy PULSE_GIT_LOCAL_BASE) to a writable directory.",
                        configError);
            }
        }
    }

    /** Returns the post-fallback resolved clone-base path. */
    public Path getResolvedCloneBase() {
        return resolvedCloneBase;
    }
}
