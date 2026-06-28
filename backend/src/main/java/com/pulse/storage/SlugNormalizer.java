package com.pulse.storage;

/**
 * Slug normalization helper for the storage convention boundary.
 *
 * <p>PULSE's path convention is kebab-case throughout
 * ({@code tenant-slug/domain-slug/sor-slug/pipeline-slug/}), but BigQuery
 * dataset and table identifiers cannot contain hyphens — they require
 * snake_case. {@link #toBqIdentifier(String)} is the boundary translator
 * applied only when constructing a BQ catalog identifier (gcp_project,
 * dataset, table). Path slugs are not touched.
 *
 * <p>Idempotent: applying it twice yields the same result.
 */
public final class SlugNormalizer {

    private SlugNormalizer() {}

    /** Convert kebab-case to snake_case for use as a BigQuery identifier.
     * Lowercases, replaces dashes with underscores, collapses runs of
     * non-alphanumerics to a single underscore, strips leading/trailing
     * underscores. */
    public static String toBqIdentifier(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must be non-blank");
        }
        String lower = slug.toLowerCase();
        StringBuilder sb = new StringBuilder(lower.length());
        boolean prevUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                prevUnderscore = false;
            } else {
                if (!prevUnderscore && sb.length() > 0) {
                    sb.append('_');
                    prevUnderscore = true;
                }
            }
        }
        // Strip trailing underscore.
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.deleteCharAt(sb.length() - 1);
        }
        if (sb.length() == 0) {
            throw new IllegalArgumentException(
                    "slug normalized to empty BQ identifier: " + slug);
        }
        return sb.toString();
    }

    /** Validate that a string is already a usable PULSE path-slug
     * (kebab-case: lowercase + digits + hyphens, no leading/trailing
     * dash, no double-dashes). Throws if not. Used as a guard at the
     * PathConventionService boundary so we don't accidentally embed a
     * display name (with spaces or capitals) into a path. */
    public static String validatePathSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("path slug must be non-blank");
        }
        if (slug.startsWith("-") || slug.endsWith("-")) {
            throw new IllegalArgumentException(
                    "path slug must not start or end with '-': " + slug);
        }
        if (slug.contains("--")) {
            throw new IllegalArgumentException(
                    "path slug must not contain '--': " + slug);
        }
        for (int i = 0; i < slug.length(); i++) {
            char c = slug.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
            if (!ok) {
                throw new IllegalArgumentException(
                        "path slug contains illegal char '" + c + "': " + slug);
            }
        }
        return slug;
    }
}
