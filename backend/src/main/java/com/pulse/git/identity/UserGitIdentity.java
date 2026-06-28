package com.pulse.git.identity;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Phase 6 — per-user Git identity. Stores ONLY:
 * <ul>
 *   <li>the {@code gcp-sm://} reference for the user's PAT classic value;</li>
 *   <li>masked metadata (provider, github username, author name/email,
 *       scopes, status, validation diagnostics).</li>
 * </ul>
 *
 * <p>The PAT value itself is never persisted in this table — it lives
 * only in Google Secret Manager (or the local-stub equivalent). Read
 * APIs MUST never return the credential reference's secret value;
 * see {@code UserGitIdentityService.toMaskedView}.
 */
@Entity
@Table(name = "user_git_identities")
public class UserGitIdentity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "pulse_user_id", nullable = false)
    private String pulseUserId;

    @Column(nullable = false)
    private String provider = "GITHUB";

    @Column(name = "credential_type", nullable = false)
    private String credentialType = "PAT_CLASSIC";

    /** {@code gcp-sm://...} reference; never a raw token. */
    @Column(name = "credential_reference", nullable = false)
    private String credentialReference;

    @Column(name = "github_username")
    private String githubUsername;

    @Column(name = "author_name")
    private String authorName;

    @Column(name = "author_email")
    private String authorEmail;

    @Column
    private String scopes;

    @Column(nullable = false)
    private String status = GitHubPatValidationStatus.PENDING_VALIDATION.name();

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_validation_error", columnDefinition = "TEXT")
    private String lastValidationError;

    @Column(name = "last_rotated_at")
    private Instant lastRotatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPulseUserId() { return pulseUserId; }
    public void setPulseUserId(String pulseUserId) { this.pulseUserId = pulseUserId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getCredentialType() { return credentialType; }
    public void setCredentialType(String credentialType) { this.credentialType = credentialType; }
    public String getCredentialReference() { return credentialReference; }
    public void setCredentialReference(String credentialReference) { this.credentialReference = credentialReference; }
    public String getGithubUsername() { return githubUsername; }
    public void setGithubUsername(String githubUsername) { this.githubUsername = githubUsername; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getAuthorEmail() { return authorEmail; }
    public void setAuthorEmail(String authorEmail) { this.authorEmail = authorEmail; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getLastValidationError() { return lastValidationError; }
    public void setLastValidationError(String lastValidationError) { this.lastValidationError = lastValidationError; }
    public Instant getLastRotatedAt() { return lastRotatedAt; }
    public void setLastRotatedAt(Instant lastRotatedAt) { this.lastRotatedAt = lastRotatedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
