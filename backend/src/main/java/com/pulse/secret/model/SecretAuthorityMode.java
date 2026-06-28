package com.pulse.secret.model;

/**
 * The resolved secret authority mode for a credential surface.
 * Distinguishes between local-stub (non-proof for real-world scenarios),
 * tenant GCP Secret Manager (provable authority), and blocked states.
 */
public enum SecretAuthorityMode {

    /**
     * Secrets managed by local-stub encryption-at-rest.
     * Non-proof for any real-world GCP-backed scenario.
     */
    LOCAL_STUB,

    /**
     * Secrets managed by tenant-configured GCP Secret Manager.
     * Provable authority with redacted readback.
     */
    TENANT_GCP_SECRET_MANAGER,

    /**
     * Secret authority cannot be determined or is explicitly blocked.
     * Occurs when configuration is missing or inconsistent.
     */
    BLOCKED
}
