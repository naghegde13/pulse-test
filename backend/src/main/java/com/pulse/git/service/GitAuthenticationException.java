package com.pulse.git.service;

/**
 * Signals that a git operation cannot proceed because authentication against
 * the remote (or secret resolution feeding that authentication) failed.
 *
 * Mapped to HTTP 401 by {@code GitController}'s exception handler. The
 * message is surfaced to the client; underlying causes (SecretManager
 * internals, JGit stack frames) are NOT surfaced.
 */
public class GitAuthenticationException extends RuntimeException {

    public GitAuthenticationException(String message) {
        super(message);
    }

    public GitAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
