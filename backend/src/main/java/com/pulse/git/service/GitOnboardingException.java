package com.pulse.git.service;

/**
 * PKT-FINAL-4 (BUG-36): typed exception for tenant Git onboarding
 * failures that are NOT credential problems — e.g. the clone-target
 * directory cannot be created. Distinct from
 * {@link GitAuthenticationException} (which carries a 502 envelope) so
 * the global handler can render a more actionable envelope without
 * collapsing every internal error to "An unexpected error occurred".
 */
public class GitOnboardingException extends RuntimeException {

    public GitOnboardingException(String message) {
        super(message);
    }

    public GitOnboardingException(String message, Throwable cause) {
        super(message, cause);
    }
}
