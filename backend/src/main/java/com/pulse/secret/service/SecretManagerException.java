package com.pulse.secret.service;

public class SecretManagerException extends RuntimeException {

    public SecretManagerException(String message) {
        super(message);
    }

    public SecretManagerException(String message, Throwable cause) {
        super(message, cause);
    }
}
