package com.pulse.git.service;

public class UnsupportedGitAuthModeException extends RuntimeException {
    public UnsupportedGitAuthModeException(String message) {
        super(message);
    }
}
