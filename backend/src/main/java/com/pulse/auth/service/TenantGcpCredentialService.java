package com.pulse.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.auth.model.TenantGcpCredential;
import com.pulse.auth.model.TenantGcpCredential.CredentialMode;
import com.pulse.auth.repository.TenantGcpCredentialRepository;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Manages per-tenant GCP service-account credentials. Supports two modes:
 *
 * <ul>
 *   <li>{@link CredentialMode#STATIC_KEY STATIC_KEY} (legacy) — accepts full
 *       service-account JSON through the secret-bearing API, extracts
 *       non-secret metadata (email, key ID, project), encrypts the full JSON,
 *       and persists to Postgres.</li>
 *   <li>{@link CredentialMode#IMPERSONATION IMPERSONATION} (recommended) —
 *       accepts only the tenant SA email; stores no key material. The
 *       credential resolver mints short-lived tokens at use time via
 *       {@code ImpersonatedCredentials} backed by ADC.</li>
 * </ul>
 *
 * <p>Readback never returns private key material — only mode, status, email,
 * key ID (STATIC_KEY only), control-plane project, and validation category.
 *
 * <p>STATIC_KEY mode is marked DEPRECATED in operator-facing logs; PULSE will
 * remove it in a future release once existing tenants have migrated.
 */
@Service
public class TenantGcpCredentialService {

    private static final Logger log = LoggerFactory.getLogger(TenantGcpCredentialService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * GCP service-account email format —
     * {@code <name>@<project-id>.iam.gserviceaccount.com}. Used to validate
     * impersonation-mode submissions and to extract the implicit control-plane
     * project ID when only the email is provided.
     */
    private static final Pattern SA_EMAIL_PATTERN = Pattern.compile(
            "^[a-z][a-z0-9-]{4,28}[a-z0-9]@([a-z][a-z0-9-]{4,28}[a-z0-9])\\.iam\\.gserviceaccount\\.com$");

    private final TenantGcpCredentialRepository credentialRepo;
    private final TenantRepository tenantRepo;
    private final TenantCredentialEncryptor encryptor;

    public TenantGcpCredentialService(TenantGcpCredentialRepository credentialRepo,
                                      TenantRepository tenantRepo,
                                      TenantCredentialEncryptor encryptor) {
        this.credentialRepo = credentialRepo;
        this.tenantRepo = tenantRepo;
        this.encryptor = encryptor;
    }

    /**
     * Submit service-account JSON for a tenant (STATIC_KEY mode). Extracts
     * metadata, encrypts the full JSON, and upserts the credential record.
     * Validates that the JSON contains required fields: client_email,
     * private_key_id, project_id, private_key.
     *
     * @param tenantId the tenant to attach credentials to
     * @param serviceAccountJson the full GCP service-account JSON
     * @return the redacted credential readback
     */
    @Transactional
    public Map<String, Object> submitCredential(String tenantId, String serviceAccountJson) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            throw new IllegalArgumentException("serviceAccountJson is required");
        }
        if (!tenantRepo.existsById(tenantId)) {
            throw new ResourceNotFoundException("Tenant", tenantId);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(serviceAccountJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON in service account credential");
        }

        String clientEmail = extractRequired(root, "client_email");
        String privateKeyId = extractRequired(root, "private_key_id");
        String projectId = extractRequired(root, "project_id");

        // Validate that private_key exists (we need it for auth) but never store/return it in cleartext
        if (!root.has("private_key") || root.get("private_key").asText("").isBlank()) {
            throw new IllegalArgumentException("service account JSON must contain private_key");
        }

        String encrypted = encryptor.encrypt(serviceAccountJson);

        TenantGcpCredential credential = credentialRepo.findByTenantId(tenantId).orElseGet(() -> {
            TenantGcpCredential c = new TenantGcpCredential();
            c.setTenantId(tenantId);
            return c;
        });
        credential.setCredentialMode(CredentialMode.STATIC_KEY);
        credential.setControlPlaneProjectId(projectId);
        credential.setServiceAccountEmail(clientEmail);
        credential.setKeyId(privateKeyId);
        credential.setTenantServiceAccountEmail(null);
        credential.setEncryptedCredential(encrypted);
        credential.setStatus("active");

        credentialRepo.save(credential);
        log.info("Tenant {} GCP credential stored (mode=STATIC_KEY, DEPRECATED — prefer IMPERSONATION): "
                + "email={}, keyId={}, controlPlaneProjectId={}",
                tenantId, clientEmail, privateKeyId, projectId);

        return buildRedactedReadback(credential);
    }

    /**
     * Submit a tenant service-account email for IMPERSONATION mode. PULSE
     * stores only the email; no key material is captured anywhere. At
     * resolve-time the runtime identity (Cloud Run SA / local-dev ADC)
     * impersonates this SA to mint short-lived tokens.
     *
     * <p>The control-plane project ID is derived from the email's project
     * suffix (the part between {@code @} and {@code .iam.gserviceaccount.com}).
     *
     * @param tenantId the tenant to attach impersonation credentials to
     * @param tenantServiceAccountEmail the tenant SA email
     * @return the redacted credential readback
     */
    @Transactional
    public Map<String, Object> submitImpersonationCredential(String tenantId,
                                                              String tenantServiceAccountEmail) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (tenantServiceAccountEmail == null || tenantServiceAccountEmail.isBlank()) {
            throw new IllegalArgumentException("tenantServiceAccountEmail is required");
        }
        if (!tenantRepo.existsById(tenantId)) {
            throw new ResourceNotFoundException("Tenant", tenantId);
        }
        String normalized = tenantServiceAccountEmail.strip().toLowerCase();
        var matcher = SA_EMAIL_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "tenantServiceAccountEmail must match "
                            + "<name>@<project-id>.iam.gserviceaccount.com — got: "
                            + tenantServiceAccountEmail);
        }
        String inferredProject = matcher.group(1);

        TenantGcpCredential credential = credentialRepo.findByTenantId(tenantId).orElseGet(() -> {
            TenantGcpCredential c = new TenantGcpCredential();
            c.setTenantId(tenantId);
            return c;
        });
        credential.setCredentialMode(CredentialMode.IMPERSONATION);
        credential.setControlPlaneProjectId(inferredProject);
        credential.setServiceAccountEmail(normalized);
        credential.setTenantServiceAccountEmail(normalized);
        credential.setKeyId(null);
        credential.setEncryptedCredential(null);
        credential.setStatus("active");

        credentialRepo.save(credential);
        log.info("Tenant {} GCP credential stored (mode=IMPERSONATION): "
                        + "tenantSaEmail={}, controlPlaneProjectId={} (inferred)",
                tenantId, normalized, inferredProject);

        return buildRedactedReadback(credential);
    }

    /**
     * Returns redacted credential status for a tenant. Never includes private key material.
     */
    public Optional<Map<String, Object>> getRedactedCredential(String tenantId) {
        return credentialRepo.findByTenantId(tenantId)
                .map(this::buildRedactedReadback);
    }

    /**
     * Retrieves the decrypted service-account JSON for internal backend use only
     * (credential resolver, identity probe). Only meaningful for STATIC_KEY
     * mode rows; returns empty for IMPERSONATION rows (which carry no encrypted
     * material).
     */
    public Optional<String> getDecryptedCredential(String tenantId) {
        return credentialRepo.findByTenantId(tenantId)
                .filter(c -> c.getEncryptedCredential() != null)
                .map(c -> encryptor.decrypt(c.getEncryptedCredential()));
    }

    /**
     * Returns the credential entity for a tenant (without decrypting).
     */
    public Optional<TenantGcpCredential> getCredentialEntity(String tenantId) {
        return credentialRepo.findByTenantId(tenantId);
    }

    private Map<String, Object> buildRedactedReadback(TenantGcpCredential cred) {
        Map<String, Object> readback = new LinkedHashMap<>();
        readback.put("tenantId", cred.getTenantId());
        readback.put("credentialMode", cred.getCredentialMode().name());
        readback.put("serviceAccountEmail", cred.getServiceAccountEmail());
        readback.put("keyId", cred.getKeyId());
        readback.put("controlPlaneProjectId", cred.getControlPlaneProjectId());
        readback.put("status", cred.getStatus());
        readback.put("credentialSource", "tenant_postgres");
        readback.put("privateKeyRedacted", true);
        if (cred.getCredentialMode() == CredentialMode.STATIC_KEY) {
            readback.put("deprecationNotice",
                    "STATIC_KEY credential mode is DEPRECATED; migrate to IMPERSONATION by "
                            + "PUT /api/v1/tenants/{tenantId}/gcp-credentials with "
                            + "{\"tenantServiceAccountEmail\": \"<sa>@<project>.iam.gserviceaccount.com\"}");
        }
        return readback;
    }

    private String extractRequired(JsonNode root, String field) {
        if (!root.has(field) || root.get(field).asText("").isBlank()) {
            throw new IllegalArgumentException("service account JSON must contain " + field);
        }
        return root.get(field).asText();
    }
}
