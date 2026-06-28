package com.pulse.git.identity;

/**
 * Phase 6 — locked status set for a {@code UserGitIdentity}.
 *
 * <p>Mirrors the table at
 * {@code docs/architecture/deployment-productization-plan.md} Phase 6
 * "Validation states". Adding a status here is a contract change;
 * downstream UI / audit / preflight all switch on these names.
 */
public enum GitHubPatValidationStatus {
    /** Identity row created; validation has not yet completed. */
    PENDING_VALIDATION,
    /** Validation succeeded and the credential is currently active. */
    VALID,
    /** Provider reported the token is unauthorized (HTTP 401). */
    INVALID_TOKEN,
    /** Token authenticates but lacks the scopes Pulse needs. */
    INSUFFICIENT_SCOPE,
    /** Token authenticates but cannot reach the configured repo. */
    REPO_ACCESS_DENIED,
    /** Pulse explicitly revoked the credential or GitHub returned revoked. */
    REVOKED,
    /** Provider unreachable / 5xx during validation. */
    PROVIDER_UNAVAILABLE,
    /** Provider reported the token has expired. */
    EXPIRED;

    /** True for terminal-deny states; the credential cannot be used until rotated. */
    public boolean isDeny() {
        return this == INVALID_TOKEN
                || this == INSUFFICIENT_SCOPE
                || this == REPO_ACCESS_DENIED
                || this == REVOKED
                || this == EXPIRED;
    }
}
