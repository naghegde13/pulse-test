package com.pulse.common.text;

/**
 * Canonical slug derivation used across the backend. Mirrors the regex applied in
 * {@code V83__gcp_secret_manager_support.sql}'s domain-slug backfill so existing seeded
 * rows and freshly-created rows produce identical slugs for the same display name.
 *
 * <p>Rules:
 * <ul>
 *   <li>Lowercase the input.</li>
 *   <li>Replace any run of non-alphanumeric characters with a single dash.</li>
 *   <li>Strip leading and trailing dashes.</li>
 *   <li>Empty / null / whitespace-only / non-alphanumeric-only inputs return an empty string.
 *       Callers must reject empty slugs at their own validation layer.</li>
 * </ul>
 *
 * <p>Examples:
 * <pre>
 *   slugify("Servicing")            → "servicing"
 *   slugify("Loss & Mitigation")    → "loss-mitigation"
 *   slugify("  Capital  Markets  ") → "capital-markets"
 *   slugify("Home Lending D&I")     → "home-lending-d-i"
 *   slugify("---")                  → ""
 *   slugify(null)                   → ""
 * </pre>
 */
public final class Slugify {

    private Slugify() {}

    public static String slugify(String input) {
        if (input == null) return "";
        String lowered = input.toLowerCase();
        String dashed = lowered.replaceAll("[^a-z0-9]+", "-");
        return dashed.replaceAll("^-|-$", "");
    }
}
