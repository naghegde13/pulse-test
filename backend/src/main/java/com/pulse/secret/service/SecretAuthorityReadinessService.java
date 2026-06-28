package com.pulse.secret.service;

import com.pulse.auth.model.TenantGcpCredential;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.config.GcpEnvironmentConfig;
import com.pulse.secret.model.SecretAuthorityMode;
import com.pulse.secret.model.SecretAuthorityReadiness;
import com.pulse.secret.model.SecretAuthorityReadiness.ProofStatus;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Computes secret authority readiness for credential surfaces.
 * <p>
 * Determines whether the current secret authority mode constitutes
 * proof for real-world GCP-backed deployment scenarios:
 * <ul>
 *   <li>{@code local-stub} → NON_PROOF — cannot satisfy GCP-backed readiness</li>
 *   <li>{@code tenant_gcp_secret_manager} → PROVEN if tenant credentials are configured</li>
 *   <li>Anything else or missing config → BLOCKED</li>
 * </ul>
 * <p>
 * Readbacks are always redacted: project ID, service account email,
 * key ID fingerprint, and status — never private key or token material.
 * <p>
 * Ambient gcloud/env auth is explicitly rejected as a proof source.
 */
@Service
public class SecretAuthorityReadinessService {

    private static final Logger log = LoggerFactory.getLogger(SecretAuthorityReadinessService.class);

    private final GcpEnvironmentConfig gcpConfig;
    private final TenantGcpCredentialService tenantCredentialService;
    private final TenantGcpRuntimeTopologyRepository topologyRepository;

    public SecretAuthorityReadinessService(GcpEnvironmentConfig gcpConfig,
                                           TenantGcpCredentialService tenantCredentialService,
                                           TenantGcpRuntimeTopologyRepository topologyRepository) {
        this.gcpConfig = gcpConfig;
        this.tenantCredentialService = tenantCredentialService;
        this.topologyRepository = topologyRepository;
    }

    /**
     * Compute secret authority readiness for a connector instance's credential surface.
     *
     * @param tenantId the tenant whose secret authority to evaluate
     * @return readiness result with mode, proof status, and redacted context
     */
    public SecretAuthorityReadiness computeForTenant(String tenantId) {
        SecretAuthorityMode mode = resolveMode(tenantId);

        return switch (mode) {
            case LOCAL_STUB -> buildLocalStubReadiness();
            case TENANT_GCP_SECRET_MANAGER -> buildTenantGcpSmReadiness(tenantId);
            case BLOCKED -> buildBlockedReadiness("Secret authority mode could not be resolved");
        };
    }

    /**
     * Compute readiness specifically for a connector instance credential surface,
     * enriched with connector-specific context.
     */
    public SecretAuthorityReadiness computeForConnectorInstance(String tenantId, String connectorInstanceId, String environment) {
        SecretAuthorityReadiness base = computeForTenant(tenantId);

        // Enrich context with connector-specific fields
        Map<String, Object> enriched = new LinkedHashMap<>(base.redactedContext());
        enriched.put("connectorInstanceId", connectorInstanceId);
        enriched.put("environment", environment);

        return new SecretAuthorityReadiness(
                base.mode(),
                base.proofStatus(),
                base.credentialSource(),
                base.validationCategory(),
                enriched
        );
    }

    /**
     * Returns the resolved secret authority mode without computing full readiness.
     * Falls back to the global mode for callers without a tenant context.
     */
    public SecretAuthorityMode resolveMode() {
        return resolveMode(null);
    }

    /**
     * PKT-FINAL-5 / BUG-54: Resolve the secret authority mode for a tenant.
     * Per-tenant {@code secret_authority_mode} (set via
     * {@code PUT /api/v1/tenants/{id}/secret-manager}) takes precedence over
     * the global {@code pulse.gcp.secret-manager-mode} default. Tenants that
     * have not opted in fall back to the global default for back-compat.
     */
    public SecretAuthorityMode resolveMode(String tenantId) {
        if (tenantId != null && !tenantId.isBlank() && topologyRepository != null) {
            Optional<TenantGcpRuntimeTopology> topology = topologyRepository.findByTenantId(tenantId);
            if (topology.isPresent()) {
                String tenantMode = topology.get().getSecretAuthorityMode();
                if (tenantMode != null && !tenantMode.isBlank()) {
                    return parseMode(tenantMode);
                }
            }
        }
        return parseGlobalMode();
    }

    private SecretAuthorityMode parseGlobalMode() {
        String smMode = gcpConfig.getSecretManagerMode();
        if (smMode == null || smMode.isBlank()) {
            return SecretAuthorityMode.BLOCKED;
        }
        if ("local-stub".equalsIgnoreCase(smMode)) {
            return SecretAuthorityMode.LOCAL_STUB;
        }
        if ("gcp".equalsIgnoreCase(smMode) || "gcp-secret-manager".equalsIgnoreCase(smMode)) {
            return SecretAuthorityMode.TENANT_GCP_SECRET_MANAGER;
        }
        log.warn("Unknown secret manager mode '{}'; treating as BLOCKED", smMode);
        return SecretAuthorityMode.BLOCKED;
    }

    private SecretAuthorityMode parseMode(String raw) {
        String upper = raw.trim().toUpperCase();
        return switch (upper) {
            case "LOCAL_STUB", "LOCAL-STUB", "LOCALSTUB" -> SecretAuthorityMode.LOCAL_STUB;
            case "GCP_SECRET_MANAGER", "GCP-SECRET-MANAGER", "TENANT_GCP_SECRET_MANAGER",
                 "GCP" -> SecretAuthorityMode.TENANT_GCP_SECRET_MANAGER;
            case "BLOCKED" -> SecretAuthorityMode.BLOCKED;
            default -> {
                log.warn("Unknown per-tenant secret authority mode '{}'; treating as BLOCKED", raw);
                yield SecretAuthorityMode.BLOCKED;
            }
        };
    }

    /**
     * Explicitly checks whether ambient gcloud/environment auth satisfies
     * secret authority proof. Always returns false — ambient auth is never
     * acceptable as connector secret authority proof.
     */
    public boolean isAmbientAuthSufficientForProof() {
        return false;
    }

    /**
     * Checks whether a local-stub GCP-SM reference can satisfy GCP-backed readiness.
     * Always returns false — local-stub references are non-proof for real-world scenarios.
     */
    public boolean isLocalStubReferenceSufficientForGcpReadiness(String secretReference) {
        if (secretReference == null) {
            return false;
        }
        // Even if the reference looks like gcp-sm://, if the mode is local-stub,
        // the reference is backed by local encryption, not real GCP SM.
        if (resolveMode(null) == SecretAuthorityMode.LOCAL_STUB) {
            return false;
        }
        return false;
    }

    private SecretAuthorityReadiness buildLocalStubReadiness() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("secretManagerMode", "local-stub");
        context.put("proofExplanation", "Local-stub mode uses AES-256/GCM encryption to disk. "
                + "This does not constitute proof for real-world GCP-backed deployment scenarios.");
        context.put("ambientAuthAccepted", false);
        context.put("gcpBackedProof", false);

        return new SecretAuthorityReadiness(
                SecretAuthorityMode.LOCAL_STUB,
                ProofStatus.NON_PROOF,
                "local_stub_encrypted_disk",
                "NON_PROOF_LOCAL",
                context
        );
    }

    private SecretAuthorityReadiness buildTenantGcpSmReadiness(String tenantId) {
        Optional<TenantGcpCredential> credOpt = tenantCredentialService.getCredentialEntity(tenantId);

        if (credOpt.isEmpty()) {
            return buildBlockedReadiness(
                    "Tenant GCP Secret Manager mode is configured but no tenant credential has been submitted. "
                            + "Submit credentials via PUT /api/v1/tenants/{tenantId}/gcp-credentials.");
        }

        TenantGcpCredential cred = credOpt.get();
        if (!"active".equalsIgnoreCase(cred.getStatus())) {
            return buildBlockedReadiness(
                    "Tenant GCP credential exists but status is '" + cred.getStatus()
                            + "'; must be 'active' for proof.");
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("secretManagerMode", "tenant_gcp_secret_manager");
        context.put("gcpProjectId", cred.getControlPlaneProjectId());
        context.put("serviceAccountEmail", cred.getServiceAccountEmail());
        context.put("keyId", cred.getKeyId());
        context.put("credentialStatus", cred.getStatus());
        context.put("privateKeyRedacted", true);
        context.put("ambientAuthAccepted", false);
        context.put("gcpBackedProof", true);

        return new SecretAuthorityReadiness(
                SecretAuthorityMode.TENANT_GCP_SECRET_MANAGER,
                ProofStatus.PROVEN,
                "tenant_gcp_secret_manager",
                "GCP_SM_TENANT_CREDENTIAL",
                context
        );
    }

    private SecretAuthorityReadiness buildBlockedReadiness(String reason) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("secretManagerMode", gcpConfig.getSecretManagerMode());
        context.put("blockerReason", reason);
        context.put("ambientAuthAccepted", false);
        context.put("gcpBackedProof", false);

        return new SecretAuthorityReadiness(
                SecretAuthorityMode.BLOCKED,
                ProofStatus.BLOCKED,
                "none",
                "BLOCKED",
                context
        );
    }
}
