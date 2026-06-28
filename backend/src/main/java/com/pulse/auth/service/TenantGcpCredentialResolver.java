package com.pulse.auth.service;

import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.model.TenantGcpCredential;
import com.pulse.auth.model.TenantGcpCredential.CredentialMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves GCP credentials from tenant-configured Postgres state. Backend GCP
 * clients (identity probe, GCS lifecycle, Secret Manager) must use this resolver
 * rather than relying on ambient gcloud auth or hardcoded project/principal.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li>{@link CredentialMode#STATIC_KEY STATIC_KEY} (legacy / DEPRECATED) —
 *       decrypts the stored SA JSON and returns a
 *       {@link ServiceAccountCredentials} instance.</li>
 *   <li>{@link CredentialMode#IMPERSONATION IMPERSONATION} (recommended) —
 *       loads Application Default Credentials (Cloud Run SA in prod / ADC user
 *       in local-dev) and wraps them in
 *       {@link ImpersonatedCredentials} targeting the tenant SA email. No key
 *       material is stored or returned.</li>
 * </ul>
 *
 * <p>The resolver fails closed: if the tenant has no configured credential or
 * config, the probe returns a failure result rather than falling through to
 * ambient auth. For IMPERSONATION mode, ADC absence in local-dev surfaces a
 * specific actionable blocker ("Run gcloud auth application-default login
 * first") instead of silently falling back.
 */
@Service
public class TenantGcpCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantGcpCredentialResolver.class);

    /** Scopes requested for impersonation. Covers GCS + Secret Manager + BigQuery + general cloud-platform. */
    private static final List<String> DEFAULT_SCOPES = List.of(
            "https://www.googleapis.com/auth/cloud-platform");

    /** Lifetime of the impersonated token (Google caps to 3600s; 900s = 15min). */
    private static final int IMPERSONATION_LIFETIME_SECONDS = 900;

    /** Operator-visible blocker message when ADC is unavailable in local-dev. */
    static final String ADC_NOT_AVAILABLE_MESSAGE =
            "ADC not available — run 'gcloud auth application-default login' first";

    private final TenantGcpConfigService configService;
    private final TenantGcpCredentialService credentialService;

    public TenantGcpCredentialResolver(TenantGcpConfigService configService,
                                       TenantGcpCredentialService credentialService) {
        this.configService = configService;
        this.credentialService = credentialService;
    }

    /**
     * Probe the GCP identity for a tenant using only tenant-configured state.
     * Returns a result map with status, project, principal, credential source,
     * mode, and any error details.
     *
     * <p>This probe does NOT make live GCP API calls. It validates that the
     * tenant has properly configured GCP config and credentials in Postgres,
     * and that the credential's project/principal match the config. For
     * IMPERSONATION mode it ALSO verifies that ADC is available (since
     * impersonation cannot work without a source identity).
     *
     * @param tenantId the tenant to probe
     * @return probe result map
     */
    public Map<String, Object> probe(String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("credentialSource", "tenant_postgres");

        // Step 1: Check tenant GCP config exists
        Optional<TenantGcpConfig> configOpt = configService.getConfig(tenantId);
        if (configOpt.isEmpty()) {
            result.put("status", "failed");
            result.put("error", "No GCP config found for tenant. "
                    + "Configure via PUT /api/v1/tenants/{tenantId}/gcp-config");
            result.put("ambientAuthUsed", false);
            return result;
        }

        TenantGcpConfig config = configOpt.get();
        result.put("configuredProjectId", config.getControlPlaneProjectId());
        result.put("controlPlaneProjectId", config.getControlPlaneProjectId());
        result.put("configuredRegion", config.getGcpRegion());

        // Step 2: Check tenant GCP credential exists
        Optional<TenantGcpCredential> credOpt = credentialService.getCredentialEntity(tenantId);
        if (credOpt.isEmpty()) {
            result.put("status", "failed");
            result.put("error", "No GCP credential found for tenant. "
                    + "Submit via PUT /api/v1/tenants/{tenantId}/gcp-credentials");
            result.put("ambientAuthUsed", false);
            return result;
        }

        TenantGcpCredential cred = credOpt.get();
        result.put("credentialMode", cred.getCredentialMode().name());

        // Step 3: Validate project consistency between config and credential
        if (!config.getControlPlaneProjectId().equals(cred.getControlPlaneProjectId())) {
            result.put("status", "failed");
            result.put("error", "Project mismatch: config has control-plane project '"
                    + config.getControlPlaneProjectId() + "' but credential has control-plane project '"
                    + cred.getControlPlaneProjectId() + "'. Reconfigure to match.");
            result.put("credentialProjectId", cred.getControlPlaneProjectId());
            result.put("ambientAuthUsed", false);
            return result;
        }

        // Step 4: Check credential status
        if (!"active".equals(cred.getStatus())) {
            result.put("status", "failed");
            result.put("error", "Credential status is '" + cred.getStatus()
                    + "'; expected 'active'");
            result.put("ambientAuthUsed", false);
            return result;
        }

        // Step 5: IMPERSONATION mode — verify ADC source is available
        if (cred.getCredentialMode() == CredentialMode.IMPERSONATION) {
            try {
                GoogleCredentials.getApplicationDefault();
            } catch (IOException e) {
                result.put("status", "failed");
                result.put("error", ADC_NOT_AVAILABLE_MESSAGE);
                result.put("adcResolutionError", e.getMessage());
                result.put("ambientAuthUsed", false);
                return result;
            }
        }

        // All checks pass
        result.put("status", "ready");
        result.put("serviceAccountEmail", cred.getServiceAccountEmail());
        result.put("keyId", cred.getKeyId()); // null for IMPERSONATION — caller can render as "n/a"
        result.put("controlPlaneProjectId", cred.getControlPlaneProjectId());
        result.put("ambientAuthUsed", false);
        result.put("privateKeyRedacted", true);
        if (cred.getCredentialMode() == CredentialMode.STATIC_KEY) {
            result.put("deprecationNotice",
                    "STATIC_KEY credential mode is DEPRECATED; migrate to IMPERSONATION.");
        }

        log.info("Tenant {} GCP identity probe: ready (mode={}, controlPlaneProjectId={}, email={})",
                tenantId, cred.getCredentialMode(), cred.getControlPlaneProjectId(),
                cred.getServiceAccountEmail());
        return result;
    }

    /**
     * Resolve {@link Credentials} suitable for constructing a Google Cloud
     * Java SDK client (Storage, SecretManager, BigQuery, …) for this tenant.
     *
     * <p>STATIC_KEY mode → {@link ServiceAccountCredentials#fromStream}
     * over the decrypted SA JSON. IMPERSONATION mode →
     * {@link ImpersonatedCredentials} wrapping ADC.
     *
     * @param tenantId the tenant to resolve credentials for
     * @return ready-to-use {@link Credentials}
     * @throws IllegalStateException if no credential is configured
     * @throws IOException if ADC resolution fails (IMPERSONATION) or JSON
     *         parsing fails (STATIC_KEY)
     */
    public Credentials resolveCredentials(String tenantId) throws IOException {
        TenantGcpCredential cred = credentialService.getCredentialEntity(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "No GCP credential configured for tenant " + tenantId
                                + ". Submit via PUT /api/v1/tenants/{tenantId}/gcp-credentials"));

        if (cred.getCredentialMode() == CredentialMode.IMPERSONATION) {
            GoogleCredentials source;
            try {
                source = GoogleCredentials.getApplicationDefault();
            } catch (IOException e) {
                throw new IOException(ADC_NOT_AVAILABLE_MESSAGE, e);
            }
            return ImpersonatedCredentials.create(
                    source,
                    cred.getTenantServiceAccountEmail(),
                    /* delegates */ null,
                    DEFAULT_SCOPES,
                    IMPERSONATION_LIFETIME_SECONDS);
        }

        // STATIC_KEY (legacy)
        String json = credentialService.getDecryptedCredential(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "STATIC_KEY credential row for tenant " + tenantId
                                + " is missing encrypted material — schema invariant violated"));
        return ServiceAccountCredentials.fromStream(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Returns the decrypted service-account JSON for a tenant, to be used by
     * backend GCP clients that have not yet migrated to the
     * {@link #resolveCredentials(String)} contract.
     *
     * <p>Only meaningful for {@link CredentialMode#STATIC_KEY STATIC_KEY}
     * mode; throws for IMPERSONATION mode (no key material exists). New code
     * should prefer {@link #resolveCredentials(String)}.
     *
     * @param tenantId the tenant to resolve credentials for
     * @return the decrypted service-account JSON
     * @throws IllegalStateException if no credential is configured or the
     *         credential is in IMPERSONATION mode
     */
    public String resolveCredentialJson(String tenantId) {
        TenantGcpCredential cred = credentialService.getCredentialEntity(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "No GCP credential configured for tenant " + tenantId
                                + ". Submit via PUT /api/v1/tenants/{tenantId}/gcp-credentials"));
        if (cred.getCredentialMode() == CredentialMode.IMPERSONATION) {
            throw new IllegalStateException(
                    "Tenant " + tenantId + " uses IMPERSONATION credential mode — no SA JSON exists. "
                            + "Use TenantGcpCredentialResolver.resolveCredentials(tenantId) instead.");
        }
        return credentialService.getDecryptedCredential(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "STATIC_KEY credential row for tenant " + tenantId
                                + " is missing encrypted material — schema invariant violated"));
    }
}
