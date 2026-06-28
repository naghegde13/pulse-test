package com.pulse.runtime.model;

import java.util.Locale;

public enum RuntimePersona {

    GCP_PULSE("GCP Pulse"),
    DPC_PULSE("DPC Pulse");

    private final String displayName;

    RuntimePersona(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static RuntimePersona parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Runtime persona must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return RuntimePersona.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown runtime persona '" + value + "'; expected one of GCP_PULSE, DPC_PULSE");
        }
    }
}
