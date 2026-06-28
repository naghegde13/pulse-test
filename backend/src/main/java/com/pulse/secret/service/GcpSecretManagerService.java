package com.pulse.secret.service;

import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.ProjectName;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersion;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import com.google.protobuf.ByteString;
import com.pulse.config.GcpEnvironmentConfig;
import com.pulse.deploy.environment.DeploymentEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;

@Service
public class GcpSecretManagerService {

    private static final Logger log = LoggerFactory.getLogger(GcpSecretManagerService.class);

    private static final int MAX_SECRET_ID_LENGTH = 255;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final GcpEnvironmentConfig gcpConfig;
    /**
     * PKT-FINAL-4 (BUG-38): resolved local-stub root, owned by
     * {@link SecretStubBaseService} so the path is no longer derived
     * from {@code pulse.git.clone-base}. Changes to the clone-base
     * between sessions no longer orphan secrets.
     */
    private final Path localStubRoot;

    @org.springframework.beans.factory.annotation.Autowired
    public GcpSecretManagerService(GcpEnvironmentConfig gcpConfig,
                                   SecretStubBaseService secretStubBaseService) {
        this.gcpConfig = gcpConfig;
        this.localStubRoot = secretStubBaseService.getResolvedSecretStubBase();
    }

    /**
     * Test-only convenience constructor that lets tests build the
     * service with an explicit local-stub directory without spinning up
     * {@link SecretStubBaseService}. Production code paths always go
     * through the primary constructor.
     *
     * <p>Historically this accepted a clone-base directory string and
     * appended {@code .secrets} to derive the stub root; BUG-38 severed
     * that coupling in the production path. Tests that still call with
     * a clone-base-like string get the legacy {@code clone-base/.secrets}
     * behavior here so existing read/write tests do not need to change.
     */
    public GcpSecretManagerService(GcpEnvironmentConfig gcpConfig, String localStubBaseOrLegacyCloneBase) {
        this.gcpConfig = gcpConfig;
        Path candidate = Path.of(localStubBaseOrLegacyCloneBase);
        // Legacy test contract: pass clone-base, derive .secrets/. Modern
        // callers should hand in a path that is already the secrets root.
        // Heuristic: if the path's final segment is `.secrets` or the
        // path does not look like a repo base, use it as-is.
        if (".secrets".equals(candidate.getFileName() == null ? null : candidate.getFileName().toString())) {
            this.localStubRoot = candidate;
        } else {
            this.localStubRoot = candidate.resolve(".secrets");
        }
    }

    /** Test/Actuator visibility into the resolved path. */
    public Path resolvedLocalStubRoot() {
        return localStubRoot;
    }

    public String createOrUpdateSecret(String environment, String secretId, String value, Map<String, String> labels) {
        if (value == null) {
            throw new IllegalArgumentException("Secret value is required");
        }
        if (isLocalStub()) {
            writeLocalStub(secretId, value);
            return buildSecretReference(environment, secretId);
        }
        String projectId = gcpConfig.resolveProjectId(environment);
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            SecretName secretName = SecretName.of(projectId, secretId);
            boolean exists;
            try {
                client.getSecret(secretName);
                exists = true;
            } catch (Exception notFound) {
                exists = false;
            }
            if (!exists) {
                Secret.Builder builder = Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.newBuilder().build())
                                .build());
                if (labels != null) {
                    for (var entry : labels.entrySet()) {
                        builder.putLabels(entry.getKey(), entry.getValue());
                    }
                }
                CreateSecretRequest createReq = CreateSecretRequest.newBuilder()
                        .setParent(ProjectName.of(projectId).toString())
                        .setSecretId(secretId)
                        .setSecret(builder.build())
                        .build();
                client.createSecret(createReq);
            }
            SecretPayload payload = SecretPayload.newBuilder()
                    .setData(ByteString.copyFrom(value, StandardCharsets.UTF_8))
                    .build();
            AddSecretVersionRequest addReq = AddSecretVersionRequest.newBuilder()
                    .setParent(secretName.toString())
                    .setPayload(payload)
                    .build();
            SecretVersion version = client.addSecretVersion(addReq);
            log.debug("Wrote secret {} version {}", secretId, version.getName());
            return buildSecretReference(environment, secretId);
        } catch (IOException e) {
            throw new SecretManagerException("Failed to write secret " + secretId + " to project " + projectId, e);
        } catch (RuntimeException e) {
            throw new SecretManagerException("Secret Manager operation failed for " + secretId + ": " + e.getMessage(), e);
        }
    }

    public String getSecretValue(String environment, String secretId) {
        if (isLocalStub()) {
            return readLocalStub(secretId);
        }
        String projectId = gcpConfig.resolveProjectId(environment);
        return readVersion(projectId, secretId, "latest");
    }

    /**
     * Phase 6 closeout — read a secret value using the full
     * {@code gcp-sm://projects/<projectId>/secrets/<secretId>/versions/<version>}
     * reference. Honors the supplied projectId + version literally
     * instead of re-resolving them from {@code environment}, so a
     * tenant whose PAT lives in a different GCP project (or pinned
     * version) works correctly.
     */
    public String getSecretValueByReference(String reference) {
        SecretReference ref = SecretReference.parse(reference);
        if (isLocalStub()) {
            // Local-stub mode keeps secrets keyed by secret id only;
            // project + version are recorded but ignored at read time.
            return readLocalStub(ref.secretId());
        }
        return readVersion(ref.projectId(), ref.secretId(), ref.version());
    }

    private String readVersion(String projectId, String secretId, String version) {
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            SecretVersionName versionName = SecretVersionName.of(projectId, secretId,
                    version == null || version.isBlank() ? "latest" : version);
            AccessSecretVersionResponse response = client.accessSecretVersion(versionName);
            return response.getPayload().getData().toStringUtf8();
        } catch (IOException e) {
            throw new SecretManagerException("Failed to read secret " + secretId
                    + " from project " + projectId + " version " + version, e);
        } catch (RuntimeException e) {
            throw new SecretManagerException("Secret Manager access failed for " + secretId
                    + " (project=" + projectId + ", version=" + version + "): " + e.getMessage(), e);
        }
    }

    /**
     * Phase 6 closeout — typed parser for the {@code gcp-sm://} URI
     * shape used everywhere a credential reference is stored. Public
     * so callers (Git provider adapter, credential resolver, …) share
     * one source of truth and can assert on (projectId, secretId,
     * version) rather than re-parsing strings.
     */
    public record SecretReference(String projectId, String secretId, String version) {
        public static final String SCHEME = "gcp-sm://";

        public static SecretReference parse(String reference) {
            if (reference == null || !reference.startsWith(SCHEME)) {
                throw new IllegalArgumentException(
                        "Invalid gcp-sm reference (missing scheme): " + reference);
            }
            String tail = reference.substring(SCHEME.length());
            String[] parts = tail.split("/");
            // Expected: projects/<projectId>/secrets/<secretId>/versions/<version>
            if (parts.length < 6
                    || !"projects".equals(parts[0])
                    || !"secrets".equals(parts[2])
                    || !"versions".equals(parts[4])
                    || parts[1].isBlank() || parts[3].isBlank() || parts[5].isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid gcp-sm reference (expected "
                                + SCHEME + "projects/<p>/secrets/<s>/versions/<v>): " + reference);
            }
            return new SecretReference(parts[1], parts[3], parts[5]);
        }
    }

    public boolean secretExists(String environment, String secretId) {
        if (isLocalStub()) {
            return Files.exists(localStubPath(secretId));
        }
        String projectId = gcpConfig.resolveProjectId(environment);
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            client.getSecret(SecretName.of(projectId, secretId));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void disableSecret(String environment, String secretId) {
        if (isLocalStub()) {
            disableLocalStub(secretId);
            return;
        }
        String projectId = gcpConfig.resolveProjectId(environment);
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            disableAllEnabledVersions(client, projectId, secretId);
        } catch (IOException e) {
            throw new SecretManagerException("Failed to disable secret " + secretId + " in project " + projectId, e);
        } catch (RuntimeException e) {
            throw new SecretManagerException("Secret Manager disable failed for " + secretId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Phase 6 closeout — disable a secret using the full
     * {@code gcp-sm://projects/<projectId>/secrets/<secretId>/versions/<version>}
     * reference. Honors the supplied projectId literally instead of
     * re-resolving it from a hardcoded environment, so a tenant whose
     * PAT lives in a non-dev GCP project is disabled in the right project.
     *
     * <p>Behavior by version:
     * <ul>
     *   <li>{@code latest} — disables every {@code ENABLED} version of the
     *       secret. This matches the legacy
     *       {@link #disableSecret(String, String)} contract, which is what
     *       rotation has always done.</li>
     *   <li>An explicit version (e.g. {@code "7"}) — disables ONLY that
     *       version, so other consumers that pin a different version of
     *       the same secret are not collaterally killed.</li>
     * </ul>
     *
     * <p>Local-stub mode keys files by secret id only; the parsed
     * project + version are recorded but ignored at disable time, matching
     * read-time behavior.
     */
    public void disableSecretByReference(String reference) {
        SecretReference ref = SecretReference.parse(reference);
        if (isLocalStub()) {
            disableLocalStub(ref.secretId());
            return;
        }
        String projectId = ref.projectId();
        String secretId = ref.secretId();
        String version = ref.version();
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            if ("latest".equalsIgnoreCase(version)) {
                disableAllEnabledVersions(client, projectId, secretId);
            } else {
                SecretVersionName versionName = SecretVersionName.of(projectId, secretId, version);
                client.disableSecretVersion(versionName.toString());
            }
        } catch (IOException e) {
            throw new SecretManagerException("Failed to disable secret " + secretId
                    + " in project " + projectId + " (version=" + version + ")", e);
        } catch (RuntimeException e) {
            throw new SecretManagerException("Secret Manager disable failed for " + secretId
                    + " (project=" + projectId + ", version=" + version + "): " + e.getMessage(), e);
        }
    }

    private void disableAllEnabledVersions(SecretManagerServiceClient client,
                                           String projectId, String secretId) {
        SecretName parent = SecretName.of(projectId, secretId);
        client.listSecretVersions(parent.toString()).iterateAll().forEach(version -> {
            if (version.getState() == SecretVersion.State.ENABLED) {
                client.disableSecretVersion(version.getName());
            }
        });
    }

    private void disableLocalStub(String secretId) {
        Path src = localStubPath(secretId);
        if (!Files.exists(src)) {
            return;
        }
        Path dst = localStubRoot.resolve(secretId + ".enc.disabled");
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SecretManagerException("Failed to disable local-stub secret " + secretId, e);
        }
    }

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

        // GSM path drops env from the secret ID — the project-id partitions per env, and the
        // `environment` label is attached at write-time. Local-stub keeps env in the filename
        // so multiple envs can coexist on one disk during dev.
        // Phase 1: env in the local-stub filename always uses the canonical
        // lowercase key (local|dev|integration|uat|prod) so a context built
        // with a legacy alias (DEV / PRODUCTION / INT) still yields a stable,
        // canonical local-stub path.
        String env;
        if (!isLocalStub()) {
            env = null;
        } else {
            String rawEnv = context.environment();
            if (rawEnv == null || rawEnv.isBlank()) {
                env = "unknown";
            } else {
                try {
                    env = DeploymentEnvironment.normalize(rawEnv);
                } catch (IllegalArgumentException unknownEnv) {
                    // Tolerate unknown context envs in local-stub mode by
                    // lowercasing as a last resort, matching the prior
                    // forgiving behavior. Real GSM never sees this branch.
                    env = rawEnv.toLowerCase(Locale.ROOT);
                }
            }
        }

        String candidate = joinSecretIdParts(env, tenant, domain, kind, resource, field, id);
        if (candidate.length() <= MAX_SECRET_ID_LENGTH) {
            return candidate;
        }

        int overflow = candidate.length() - MAX_SECRET_ID_LENGTH;
        int newResourceLen = Math.max(1, resource.length() - overflow);
        String truncatedResource = resource.substring(0, newResourceLen);
        candidate = joinSecretIdParts(env, tenant, domain, kind, truncatedResource, field, id);
        if (candidate.length() > MAX_SECRET_ID_LENGTH) {
            candidate = candidate.substring(0, MAX_SECRET_ID_LENGTH);
        }
        return candidate;
    }

    private String joinSecretIdParts(String env, String tenant, String domain, String kind,
                                     String resource, String field, String id) {
        if (env == null) {
            return String.join("-", "pulse", tenant, domain, kind, resource, field, id);
        }
        return String.join("-", "pulse", env, tenant, domain, kind, resource, field, id);
    }

    public String buildSecretReference(String environment, String secretId) {
        String projectId = gcpConfig.resolveProjectId(environment);
        return "gcp-sm://projects/" + projectId + "/secrets/" + secretId + "/versions/latest";
    }

    public String getSecretManagerMode() {
        return gcpConfig.getSecretManagerMode();
    }

    private boolean isLocalStub() {
        return "local-stub".equalsIgnoreCase(gcpConfig.getSecretManagerMode());
    }

    private Path localStubPath(String secretId) {
        return localStubRoot.resolve(secretId + ".enc");
    }

    private void writeLocalStub(String secretId, String value) {
        try {
            Files.createDirectories(localStubRoot);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(gcpConfig.getLocalStubKey()), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] bundle = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, bundle, 0, iv.length);
            System.arraycopy(encrypted, 0, bundle, iv.length, encrypted.length);
            Files.write(localStubPath(secretId), bundle);
        } catch (Exception e) {
            throw new SecretManagerException("Failed to write local-stub secret " + secretId + ": " + e.getMessage(), e);
        }
    }

    private String readLocalStub(String secretId) {
        Path path = localStubPath(secretId);
        if (!Files.exists(path)) {
            throw new SecretManagerException("Local-stub secret not found: " + secretId);
        }
        try {
            byte[] bundle = Files.readAllBytes(path);
            if (bundle.length < GCM_IV_LENGTH) {
                throw new SecretManagerException("Local-stub secret is malformed: " + secretId);
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(bundle, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[bundle.length - GCM_IV_LENGTH];
            System.arraycopy(bundle, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(gcpConfig.getLocalStubKey()), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (SecretManagerException e) {
            throw e;
        } catch (Exception e) {
            throw new SecretManagerException("Failed to read local-stub secret " + secretId + ": " + e.getMessage(), e);
        }
    }

    private SecretKeySpec deriveKey(String seed) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] key = sha.digest((seed == null ? "" : seed).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    private String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        String lower = input.toLowerCase(Locale.ROOT);
        String cleaned = lower.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }
}
