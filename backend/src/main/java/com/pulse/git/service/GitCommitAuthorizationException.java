package com.pulse.git.service;

/**
 * Phase 3: thrown by {@link GitCommitService#commitGeneratedCode} when
 * the resolved actor is not authorized for {@code PulseAction.COMMIT}
 * in the target tenant. Distinct from {@link GitAuthenticationException}
 * (transport-level auth) so callers + audit can tell the difference
 * between "your token is bad" and "your role is not allowed".
 */
public class GitCommitAuthorizationException extends RuntimeException {
    public GitCommitAuthorizationException(String reason) {
        super("Git commit authorization denied: " + reason);
    }
}
