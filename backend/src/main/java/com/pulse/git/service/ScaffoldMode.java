package com.pulse.git.service;

import java.util.Locale;

public enum ScaffoldMode {
    SELECTED,
    MISSING_ONLY,
    ALL;

    public static ScaffoldMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        return ScaffoldMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
