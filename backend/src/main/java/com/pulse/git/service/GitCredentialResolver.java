package com.pulse.git.service;

import com.pulse.git.identity.UserGitIdentity;
import com.pulse.secret.service.GcpSecretManagerService;
import com.pulse.secret.service.SecretManagerException;
import com.pulse.secret.service.SecretReferenceService;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves a {@link UserGitIdentity} secret reference into a live JGit
 * HTTPS credentials provider.
 *
 * Validation is eager: every write path through {@code GitController} must
 * call {@link #validateReference(String)} before persisting so that the
 * column never ends up holding plaintext or a free-form secret ID.
 *
 * Resolution is lazy: the resolver only contacts Secret Manager when a git
 * operation actually needs the plaintext. When
 * {@link GcpSecretManagerService} has not yet been provided by Agent C, the
 * injected {@link ObjectProvider} returns null and every resolve call throws
 * {@link GitAuthenticationException}. App startup and credential-less paths
 * (LOCAL repos, public HTTPS clone) are unaffected.
 */
@Service
public class GitCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(GitCredentialResolver.class);
    private static final String SCHEME = "gcp-sm://";

    private final ObjectProvider<GcpSecretManagerService> secretManagerProvider;
    private final SecretReferenceService secretReferenceService;
    private final String environment;

    public GitCredentialResolver(ObjectProvider<GcpSecretManagerService> secretManagerProvider,
                                 SecretReferenceService secretReferenceService,
                                 @Value("${pulse.git.default-environment:dev}") String environment) {
        this.secretManagerProvider = secretManagerProvider;
        this.secretReferenceService = secretReferenceService;
        this.environment = environment;
    }

    /**
     * Validates the shape of a credential reference. {@code null} is allowed
     * and means "no auth configured". Any non-null value MUST start with
     * {@code gcp-sm://}; plaintext tokens, bare IDs, vault URIs, and file
     * paths are rejected.
     */
    public void validateReference(String reference) {
        if (reference == null) {
            return;
        }
        if (!reference.startsWith(SCHEME)) {
            throw new IllegalArgumentException(
                    "Invalid git credential reference; expected " + SCHEME + " URI");
        }
        if (!secretReferenceService.isSecretReference(reference)) {
            throw new IllegalArgumentException(
                    "Invalid git credential reference; not a recognized secret reference");
        }
    }

    /**
     * Resolves HTTPS credentials for a user identity. Returns an empty Optional
     * when the identity has no credential reference. Throws {@link GitAuthenticationException}
     * when the reference is malformed, Secret Manager is unavailable, or the
     * secret cannot be read.
     */
    public Optional<UsernamePasswordCredentialsProvider> resolveHttpsCredentials(UserGitIdentity identity) {
        String reference = identity == null ? null : identity.getCredentialReference();
        if (reference == null) {
            return Optional.empty();
        }
        String secret = resolveSecret(reference);
        return Optional.of(new UsernamePasswordCredentialsProvider("token", secret));
    }

    private String resolveSecret(String reference) {
        if (reference == null || !reference.startsWith(SCHEME)) {
            throw new GitAuthenticationException(
                    "Invalid git credential reference; expected " + SCHEME + " URI");
        }
        GcpSecretManagerService secretManager = secretManagerProvider.getIfAvailable();
        if (secretManager == null) {
            throw new GitAuthenticationException(
                    "Secret Manager service is not available; cannot resolve git credentials");
        }
        String secretId = extractSecretId(reference);
        try {
            // Phase 6 closeout: honor the FULL gcp-sm:// URI (project +
            // secret + version) rather than dropping back to the
            // env-resolved project. The legacy env-based path stays as
            // a fallback for malformed references — those come from
            // legacy tenant deploy keys that haven't been migrated yet.
            try {
                return secretManager.getSecretValueByReference(reference);
            } catch (IllegalArgumentException malformed) {
                log.warn("Falling back to env-based secret read for malformed reference {}: {}",
                        reference, malformed.getMessage());
                return secretManager.getSecretValue(environment, secretId);
            }
        } catch (SecretManagerException e) {
            if (isNotFound(e)) {
                throw new GitAuthenticationException(
                        "Git credential secret not found: " + secretId, e);
            }
            throw new GitAuthenticationException(
                    "Failed to resolve git credential: " + secretId, e);
        } catch (RuntimeException e) {
            throw new GitAuthenticationException(
                    "Failed to resolve git credential: " + secretId, e);
        }
    }

    private boolean isNotFound(SecretManagerException e) {
        String message = e.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("not found") || lower.contains("no such secret");
    }

    /**
     * Extracts the secret ID from {@code gcp-sm://projects/{projectId}/secrets/{secretId}/versions/{version}}.
     * Returns the 4th slash-separated segment after the scheme (the
     * {@code {secretId}} component).
     */
    static String extractSecretId(String reference) {
        String withoutScheme = reference.substring(SCHEME.length());
        String[] segments = withoutScheme.split("/");
        // segments: ["projects", "{projectId}", "secrets", "{secretId}", "versions", "{version}"]
        if (segments.length < 4 || !"projects".equals(segments[0]) || !"secrets".equals(segments[2])) {
            throw new GitAuthenticationException(
                    "Invalid git credential reference; expected " + SCHEME
                            + "projects/{projectId}/secrets/{secretId}/versions/{version}");
        }
        return segments[3];
    }
}
