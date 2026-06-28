package com.pulse.git.service;

public class GitIdentityRequiredException extends RuntimeException {
    public GitIdentityRequiredException(String message) {
        super(message);
    }
}
