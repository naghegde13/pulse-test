package com.pulse.secret.model;

import java.util.Map;

/**
 * Immutable readiness result for a secret authority surface.
 * Contains mode, proof status, credential source metadata (redacted),
 * and validation category — never secret material.
 */
public record SecretAuthorityReadiness(
        SecretAuthorityMode mode,
        ProofStatus proofStatus,
        String credentialSource,
        String validationCategory,
        Map<String, Object> redactedContext
) {

    /**
     * Whether this authority mode constitutes proof for real-world
     * GCP-backed deployment scenarios.
     */
    public enum ProofStatus {
        /** Authority is proven through tenant-configured GCP Secret Manager. */
        PROVEN,
        /** Authority is not proof for real-world scenarios (local-stub). */
        NON_PROOF,
        /** Authority is blocked — missing config or unresolvable state. */
        BLOCKED
    }

    public boolean isReady() {
        return proofStatus == ProofStatus.PROVEN;
    }
}
