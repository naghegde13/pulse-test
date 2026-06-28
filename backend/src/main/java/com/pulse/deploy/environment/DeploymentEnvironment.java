package com.pulse.deploy.environment;

import java.util.Locale;
import java.util.Map;

/**
 * Canonical deployment environment model for Phase 1 of the deployment
 * productization plan.
 *
 * <p>Persisted form is always the lowercase {@link #key()}. Legacy uppercase
 * inputs ({@code DEV}, {@code INT}, {@code INTEGRATION}, {@code UAT},
 * {@code PROD}, {@code PRODUCTION}, {@code LOCAL}) normalize to the canonical
 * key at API boundaries via {@link #parse(String)} or
 * {@link #normalize(String)}. Unknown values throw
 * {@link IllegalArgumentException} with a clear message so unsupported
 * environments fail at the API surface, not deep inside a deploy lookup.
 *
 * <p>Read paths and lookups call {@link #normalize(String)} so a caller may
 * still pass either the canonical or a known legacy value; persistence and
 * outbound responses must always use the canonical {@link #key()}.
 */
public enum DeploymentEnvironment {
    LOCAL("local", "Local"),
    DEV("dev", "Dev"),
    INTEGRATION("integration", "Integration"),
    UAT("uat", "UAT"),
    PROD("prod", "Production");

    private static final Map<String, DeploymentEnvironment> ALIASES = Map.ofEntries(
            // Canonical (case-insensitive).
            Map.entry("local", LOCAL),
            Map.entry("dev", DEV),
            Map.entry("integration", INTEGRATION),
            Map.entry("uat", UAT),
            Map.entry("prod", PROD),
            // Legacy uppercase inputs accepted at API boundaries.
            Map.entry("LOCAL", LOCAL),
            Map.entry("DEV", DEV),
            Map.entry("INT", INTEGRATION),
            Map.entry("INTEGRATION", INTEGRATION),
            Map.entry("UAT", UAT),
            Map.entry("PROD", PROD),
            Map.entry("PRODUCTION", PROD)
    );

    private final String key;
    private final String label;

    DeploymentEnvironment(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Canonical persisted environment key (always lowercase). */
    public String key() {
        return key;
    }

    /** UI-facing label. Presentation only — never used for storage or lookup. */
    public String label() {
        return label;
    }

    /**
     * Parse any accepted environment string (canonical lowercase or known
     * legacy uppercase form) to the canonical enum value.
     *
     * @throws IllegalArgumentException when {@code raw} is null, blank, or
     *         not in the supported alias set.
     */
    public static DeploymentEnvironment parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    "Environment is required. Allowed values: local, dev, integration, uat, prod"
                    + " (legacy aliases DEV, INT, INTEGRATION, UAT, PROD, PRODUCTION, LOCAL)");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Environment is required. Allowed values: local, dev, integration, uat, prod"
                    + " (legacy aliases DEV, INT, INTEGRATION, UAT, PROD, PRODUCTION, LOCAL)");
        }
        DeploymentEnvironment direct = ALIASES.get(trimmed);
        if (direct != null) {
            return direct;
        }
        DeploymentEnvironment lower = ALIASES.get(trimmed.toLowerCase(Locale.ROOT));
        if (lower != null) {
            return lower;
        }
        DeploymentEnvironment upper = ALIASES.get(trimmed.toUpperCase(Locale.ROOT));
        if (upper != null) {
            return upper;
        }
        throw new IllegalArgumentException(
                "Unknown deployment environment: '" + raw + "'."
                + " Allowed values: local, dev, integration, uat, prod"
                + " (legacy aliases DEV, INT, INTEGRATION, UAT, PROD, PRODUCTION, LOCAL)");
    }

    /**
     * Convenience boundary helper: parses {@code raw} and returns its
     * canonical lowercase {@link #key()}. Throws on null/blank/unknown
     * input — callers that must accept null/blank should guard before
     * calling.
     */
    public static String normalize(String raw) {
        return parse(raw).key();
    }

    /**
     * Best-effort normalization for caller paths that historically tolerated
     * null/blank. Returns {@code null} when input is null or blank, and
     * otherwise returns the canonical key for any accepted alias. Unknown
     * non-blank values still throw {@link IllegalArgumentException} so they
     * surface at the boundary instead of corrupting persisted data.
     */
    public static String normalizeNullable(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return normalize(trimmed);
    }
}
