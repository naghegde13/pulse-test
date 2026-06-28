package com.pulse.auth.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Per-tenant GCP service-account credential record.
 *
 * <p>PULSE supports two credential modes:
 * <ul>
 *   <li>{@link CredentialMode#STATIC_KEY STATIC_KEY} — legacy mode. The full
 *       service-account JSON is AES-encrypted at rest in
 *       {@code encrypted_credential}; only non-secret metadata (email, key ID,
 *       project) is stored in cleartext columns for readback. Submitted
 *       through {@code PUT /api/v1/tenants/{tenantId}/gcp-credentials} with a
 *       {@code serviceAccountJson} body.</li>
 *   <li>{@link CredentialMode#IMPERSONATION IMPERSONATION} — keyless mode.
 *       Only the tenant SA email is stored in
 *       {@code tenant_service_account_email}; no key material lives anywhere
 *       in PULSE. At use time, PULSE's runtime identity (Cloud Run SA in
 *       prod / ADC user in local-dev) impersonates the tenant SA via
 *       {@code google-auth} {@code ImpersonatedCredentials} to mint
 *       short-lived tokens. Submitted with a
 *       {@code tenantServiceAccountEmail} body.</li>
 * </ul>
 *
 * <p>The {@code project_id} extracted from the SA JSON (STATIC_KEY) or
 * inferred from the SA email's project suffix (IMPERSONATION) is the
 * <em>control-plane</em> project — same scope as
 * {@link TenantGcpConfig#getControlPlaneProjectId()}. The credential resolver
 * enforces consistency between the two.
 *
 * <p>Readback never returns private key material — only mode, status, email,
 * key ID (STATIC_KEY only), and control-plane project ID.
 */
@Entity
@Table(name = "tenant_gcp_credentials")
public class TenantGcpCredential extends BaseEntity {

    /**
     * Discriminator for which credential mode applies to this row. Persisted
     * as a VARCHAR(32). See class javadoc for semantics.
     */
    public enum CredentialMode {
        STATIC_KEY,
        IMPERSONATION
    }

    @Column(name = "tenant_id", nullable = false, unique = true)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_mode", nullable = false)
    private CredentialMode credentialMode = CredentialMode.STATIC_KEY;

    @Column(name = "control_plane_project_id", nullable = false)
    private String controlPlaneProjectId;

    @Column(name = "service_account_email", nullable = false)
    private String serviceAccountEmail;

    /**
     * Only populated when {@link #credentialMode} is
     * {@link CredentialMode#IMPERSONATION IMPERSONATION}. For STATIC_KEY the
     * SA email lives in {@link #serviceAccountEmail} (extracted from the JSON
     * via {@code client_email}); IMPERSONATION duplicates it here because the
     * CHECK constraint enforces non-null for impersonation rows. Callers
     * should read {@link #getServiceAccountEmail()} for the canonical principal.
     */
    @Column(name = "tenant_service_account_email")
    private String tenantServiceAccountEmail;

    /**
     * Only populated when {@link #credentialMode} is
     * {@link CredentialMode#STATIC_KEY STATIC_KEY}. IMPERSONATION rows have a
     * null {@code keyId} because no key exists.
     */
    @Column(name = "key_id")
    private String keyId;

    /**
     * Nullable when {@link #credentialMode} is
     * {@link CredentialMode#IMPERSONATION IMPERSONATION} (no key material is
     * ever stored for impersonation mode). Required when STATIC_KEY (CHECK
     * constraint enforces).
     */
    @Column(name = "encrypted_credential")
    private String encryptedCredential;

    @Column(nullable = false)
    private String status = "active";

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public CredentialMode getCredentialMode() { return credentialMode; }
    public void setCredentialMode(CredentialMode credentialMode) {
        this.credentialMode = credentialMode;
    }

    public String getControlPlaneProjectId() { return controlPlaneProjectId; }
    public void setControlPlaneProjectId(String controlPlaneProjectId) {
        this.controlPlaneProjectId = controlPlaneProjectId;
    }

    public String getServiceAccountEmail() { return serviceAccountEmail; }
    public void setServiceAccountEmail(String serviceAccountEmail) { this.serviceAccountEmail = serviceAccountEmail; }

    public String getTenantServiceAccountEmail() { return tenantServiceAccountEmail; }
    public void setTenantServiceAccountEmail(String tenantServiceAccountEmail) {
        this.tenantServiceAccountEmail = tenantServiceAccountEmail;
    }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getEncryptedCredential() { return encryptedCredential; }
    public void setEncryptedCredential(String encryptedCredential) { this.encryptedCredential = encryptedCredential; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
