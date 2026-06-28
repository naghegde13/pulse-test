package com.pulse.git.identity;

import java.time.Instant;

/**
 * Phase 6 — read-API view of a {@link UserGitIdentity}. NEVER includes
 * the PAT value, never includes the GSM secret id beyond the masked
 * reference. Returned by every {@code GET /api/v1/users/me/git-identity}
 * surface.
 */
public record MaskedGitIdentity(
        String id,
        String tenantId,
        String pulseUserId,
        String provider,
        String credentialType,
        String credentialReferenceMasked,
        String githubUsername,
        String authorName,
        String authorEmail,
        String scopes,
        String status,
        Instant verifiedAt,
        Instant expiresAt,
        Instant lastRotatedAt,
        Instant revokedAt,
        String lastValidationError,
        // Phase 6 closeout — failed-rotation diagnostic. Surfaces what
        // the new (rejected) token would have landed at, without
        // changing the row's active status. NULL when no rotation has
        // been attempted (or the most recent rotation succeeded).
        String lastRotationAttemptStatus,
        String lastRotationAttemptError,
        String lastRotationAttemptAt
) {
    /** Mask the {@code gcp-sm://projects/.../secrets/<id>/versions/...} reference for display. */
    public static String maskReference(String reference) {
        if (reference == null || reference.isBlank()) return null;
        // Show the project + secret-id stub but never the full secret id.
        // gcp-sm://projects/<project>/secrets/<secretId>/versions/latest
        // → gcp-sm://projects/<project>/secrets/<secretId-prefix>...
        String[] parts = reference.split("/");
        if (parts.length < 6) return "gcp-sm://****";
        String secretId = parts[5];
        String stub = secretId.length() > 12 ? secretId.substring(0, 12) + "…" : secretId;
        return "gcp-sm://projects/" + parts[3] + "/secrets/" + stub;
    }
}
