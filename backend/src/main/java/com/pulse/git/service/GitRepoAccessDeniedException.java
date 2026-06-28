package com.pulse.git.service;

public class GitRepoAccessDeniedException extends RuntimeException {
    public GitRepoAccessDeniedException(String message) {
        super(message);
    }

    public GitRepoAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
