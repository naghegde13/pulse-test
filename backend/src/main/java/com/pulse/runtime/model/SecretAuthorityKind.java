package com.pulse.runtime.model;

import java.util.Locale;

public enum SecretAuthorityKind {

    GCP_SECRET_MANAGER,
    VAULT;

    public static SecretAuthorityKind parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Secret authority kind must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return SecretAuthorityKind.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown secret authority kind '" + value + "'; expected one of GCP_SECRET_MANAGER, VAULT");
        }
    }
}
