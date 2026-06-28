package com.pulse.support;

import com.pulse.secret.service.GcpSecretManagerService;
import com.pulse.secret.service.SecretManagerException;
import com.pulse.secret.service.SecretNamingContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory replacement for {@link GcpSecretManagerService} for use in unit tests.
 *
 * <p>Mirrors the public surface of the production class:
 * {@code createOrUpdateSecret}, {@code getSecretValue}, {@code getSecretValueByReference},
 * {@code secretExists}, {@code disableSecret}, {@code disableSecretByReference},
 * {@code buildSecretId}, {@code buildSecretReference}, {@code getSecretManagerMode}.
 *
 * <p>Behavioral parity:
 * <ul>
 *   <li>Reads of unset secrets throw {@link SecretManagerException} — the SAME exception
 *       type the production class throws for a missing local-stub secret. Tests that catch
 *       a "NotFound" should catch {@code SecretManagerException}, not introduce a new type.</li>
 *   <li>Versioned writes are supported. Each write to {@code (projectId, secretId)} appends
 *       a new version; reads with {@code "latest"} return the most recent enabled version,
 *       and reads with an explicit version return that version (or throw if disabled).</li>
 *   <li>{@code disableSecret} disables ALL enabled versions of the secret (matches production
 *       legacy contract). {@code disableSecretByReference} with an explicit version disables
 *       only that version; {@code "latest"} disables all enabled versions.</li>
 *   <li>{@link #buildSecretId} reproduces the production {@code joinSecretIdParts} +
 *       slugify rules; mode is taken from {@link #getSecretManagerMode()}.</li>
 *   <li>{@link #buildSecretReference} produces the same {@code gcp-sm://} URI shape, using
 *       the projectId resolved by the in-memory project map (defaults to {@code pulse-test}).</li>
 * </ul>
 *
 * <p>This fake is NOT a Spring bean and does not extend {@link GcpSecretManagerService}; the
 * production class hits real GCP / file system in its constructor and is not safely
 * extensible. Tests that need to swap it in should pass {@code InMemoryGcpSecretManager} where
 * a delegate / interface seam exists, or use Mockito to wrap it.
 */
public final class InMemoryGcpSecretManager {

    private static final int MAX_SECRET_ID_LENGTH = 255;
    private static final String DEFAULT_PROJECT_ID = "pulse-test";

    /** Mode label returned by {@link #getSecretManagerMode()}. Default is "in-memory". */
    private final String mode;
    /** environment -> projectId, used by {@link #buildSecretReference} and resolution. */
    private final Map<String, String> environmentProjects = new HashMap<>();
    /** (projectId, secretId) -> ordered list of versions. */
    private final Map<Key, List<Version>> versionsByKey = new HashMap<>();

    public InMemoryGcpSecretManager() {
        this("in-memory");
    }

    public InMemoryGcpSecretManager(String mode) {
        this.mode = mode == null ? "in-memory" : mode;
        // Sensible defaults for canonical environments so tests don't need to wire each one.
        environmentProjects.put("local", DEFAULT_PROJECT_ID);
        environmentProjects.put("dev", DEFAULT_PROJECT_ID);
        environmentProjects.put("integration", DEFAULT_PROJECT_ID);
        environmentProjects.put("uat", DEFAULT_PROJECT_ID);
        environmentProjects.put("prod", DEFAULT_PROJECT_ID);
    }

    /** Override the projectId resolved for an environment. */
    public void setProjectId(String environment, String projectId) {
        if (environment == null || projectId == null) {
            throw new IllegalArgumentException("environment and projectId must be non-null");
        }
        environmentProjects.put(environment.toLowerCase(Locale.ROOT), projectId);
    }

    public String getSecretManagerMode() {
        return mode;
    }

    /** Mirrors {@link GcpSecretManagerService#createOrUpdateSecret}. */
    public String createOrUpdateSecret(String environment, String secretId, String value, Map<String, String> labels) {
        if (value == null) {
            throw new IllegalArgumentException("Secret value is required");
        }
        String projectId = resolveProjectId(environment);
        Key key = new Key(projectId, secretId);
        List<Version> versions = versionsByKey.computeIfAbsent(key, k -> new ArrayList<>());
        int versionNumber = versions.size() + 1;
        Map<String, String> labelCopy = labels == null ? Map.of() : Map.copyOf(labels);
        versions.add(new Version(Integer.toString(versionNumber), value, true, labelCopy));
        return buildSecretReference(environment, secretId);
    }

    /** Mirrors {@link GcpSecretManagerService#getSecretValue}. */
    public String getSecretValue(String environment, String secretId) {
        return readVersion(resolveProjectId(environment), secretId, "latest");
    }

    /** Mirrors {@link GcpSecretManagerService#getSecretValueByReference}. */
    public String getSecretValueByReference(String reference) {
        GcpSecretManagerService.SecretReference ref = GcpSecretManagerService.SecretReference.parse(reference);
        return readVersion(ref.projectId(), ref.secretId(), ref.version());
    }

    /** Mirrors {@link GcpSecretManagerService#secretExists}. */
    public boolean secretExists(String environment, String secretId) {
        Key key = new Key(resolveProjectId(environment), secretId);
        List<Version> versions = versionsByKey.get(key);
        return versions != null && !versions.isEmpty();
    }

    /** Mirrors {@link GcpSecretManagerService#disableSecret}. */
    public void disableSecret(String environment, String secretId) {
        Key key = new Key(resolveProjectId(environment), secretId);
        List<Version> versions = versionsByKey.get(key);
        if (versions == null) return;
        for (int i = 0; i < versions.size(); i++) {
            Version v = versions.get(i);
            if (v.enabled) {
                versions.set(i, new Version(v.versionId, v.value, false, v.labels));
            }
        }
    }

    /** Mirrors {@link GcpSecretManagerService#disableSecretByReference}. */
    public void disableSecretByReference(String reference) {
        GcpSecretManagerService.SecretReference ref = GcpSecretManagerService.SecretReference.parse(reference);
        Key key = new Key(ref.projectId(), ref.secretId());
        List<Version> versions = versionsByKey.get(key);
        if (versions == null) return;
        if ("latest".equalsIgnoreCase(ref.version())) {
            for (int i = 0; i < versions.size(); i++) {
                Version v = versions.get(i);
                if (v.enabled) {
                    versions.set(i, new Version(v.versionId, v.value, false, v.labels));
                }
            }
        } else {
            for (int i = 0; i < versions.size(); i++) {
                Version v = versions.get(i);
                if (v.versionId.equals(ref.version())) {
                    versions.set(i, new Version(v.versionId, v.value, false, v.labels));
                }
            }
        }
    }

    /** Mirrors {@link GcpSecretManagerService#buildSecretReference}. */
    public String buildSecretReference(String environment, String secretId) {
        return "gcp-sm://projects/" + resolveProjectId(environment) + "/secrets/" + secretId + "/versions/latest";
    }

    /**
     * Mirrors {@link GcpSecretManagerService#buildSecretId} naming logic without the
     * production environment-canonicalization dependency. In "in-memory" / non-stub mode,
     * env is dropped from the secret id (as in production GSM mode) so tests that pin a
     * secret id can rely on the same shape.
     */
    public String buildSecretId(SecretNamingContext context) {
        if (context.tenantSlug() == null || context.tenantSlug().isBlank()) {
            throw new IllegalArgumentException("Tenant slug is required for secret naming");
        }
        String tenant = context.tenantSlug().toLowerCase(Locale.ROOT);
        String domain = (context.domainSlug() == null || context.domainSlug().isBlank())
                ? "default"
                : context.domainSlug().toLowerCase(Locale.ROOT);
        String kind = context.resourceKind() == null ? "source" : context.resourceKind().toLowerCase(Locale.ROOT);
        String resource = slugify(context.resourceSlug());
        String field = slugify(context.fieldSlug());
        String id = context.resourceId() == null ? "" : context.resourceId();

        // For test-mode parity with production GSM mode: drop env from the id.
        String candidate = String.join("-", "pulse", tenant, domain, kind, resource, field, id);
        if (candidate.length() > MAX_SECRET_ID_LENGTH) {
            int overflow = candidate.length() - MAX_SECRET_ID_LENGTH;
            int newResourceLen = Math.max(1, resource.length() - overflow);
            String truncatedResource = resource.substring(0, newResourceLen);
            candidate = String.join("-", "pulse", tenant, domain, kind, truncatedResource, field, id);
            if (candidate.length() > MAX_SECRET_ID_LENGTH) {
                candidate = candidate.substring(0, MAX_SECRET_ID_LENGTH);
            }
        }
        return candidate;
    }

    /**
     * List the secret ids known to the fake, in lexicographic order. Convenience for tests —
     * production exposes listing via the SecretManagerServiceClient directly.
     */
    public List<String> listSecretIds(String environment) {
        String projectId = resolveProjectId(environment);
        Map<String, Boolean> sorted = new TreeMap<>();
        for (Key key : versionsByKey.keySet()) {
            if (key.projectId().equals(projectId)) {
                sorted.put(key.secretId(), Boolean.TRUE);
            }
        }
        return new ArrayList<>(sorted.keySet());
    }

    /** Number of versions written for a secret (incl. disabled). */
    public int versionCount(String environment, String secretId) {
        List<Version> v = versionsByKey.get(new Key(resolveProjectId(environment), secretId));
        return v == null ? 0 : v.size();
    }

    /** Snapshot the labels for the latest enabled version (defensive copy). */
    public Map<String, String> getLatestLabels(String environment, String secretId) {
        List<Version> versions = versionsByKey.get(new Key(resolveProjectId(environment), secretId));
        if (versions == null) return Map.of();
        for (int i = versions.size() - 1; i >= 0; i--) {
            Version v = versions.get(i);
            if (v.enabled) {
                return new LinkedHashMap<>(v.labels);
            }
        }
        return Map.of();
    }

    /** Wipe all state. */
    public void clear() {
        versionsByKey.clear();
    }

    // ---- internals ----

    private String resolveProjectId(String environment) {
        if (environment == null) {
            throw new IllegalArgumentException("Environment is required to resolve project id");
        }
        String key = environment.toLowerCase(Locale.ROOT);
        return environmentProjects.getOrDefault(key, DEFAULT_PROJECT_ID);
    }

    private String readVersion(String projectId, String secretId, String version) {
        Key key = new Key(projectId, secretId);
        List<Version> versions = versionsByKey.get(key);
        if (versions == null || versions.isEmpty()) {
            throw new SecretManagerException("Secret not found: " + secretId
                    + " (project=" + projectId + ", version=" + version + ")");
        }
        if (version == null || version.isBlank() || "latest".equalsIgnoreCase(version)) {
            for (int i = versions.size() - 1; i >= 0; i--) {
                Version v = versions.get(i);
                if (v.enabled) return v.value;
            }
            throw new SecretManagerException("No enabled version for secret: " + secretId
                    + " (project=" + projectId + ")");
        }
        for (Version v : versions) {
            if (v.versionId.equals(version)) {
                if (!v.enabled) {
                    throw new SecretManagerException("Secret version is disabled: " + secretId
                            + " version " + version);
                }
                return v.value;
            }
        }
        throw new SecretManagerException("Secret version not found: " + secretId + " version " + version);
    }

    private static String slugify(String input) {
        if (input == null || input.isBlank()) return "unknown";
        String lower = input.toLowerCase(Locale.ROOT);
        String cleaned = lower.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    private record Key(String projectId, String secretId) { }

    private record Version(String versionId, String value, boolean enabled, Map<String, String> labels) {
        Version {
            labels = labels == null ? Collections.emptyMap() : labels;
        }
    }
}
