package com.pulse.git.service;

public class GitIdentityInvalidException extends RuntimeException {
    public GitIdentityInvalidException(String message) {
        super(message);
    }
}
