package com.pulse.codegen.opengine;

/**
 * Generates the canonical Airflow-Dataset URI for data-aware edges
 * (SPEC #2 §C.7, G-C4 LOCKED): {@code pulse://<tenant>/<domain>/<dataset>}.
 *
 * <p>PULSE generates this URI from the registered dataset the Customer selected
 * (tenant + domain + dataset identifiers); the Customer never sees or authors it.
 * The producer emits {@code outlets=[Dataset("pulse://t/d/ds")]} and the consumer
 * emits {@code schedule=[Dataset("pulse://t/d/ds")]} — the SAME string, so Airflow
 * wires the cross-pipeline dependency. Cross-Airflow dependencies use
 * {@code invoke-remote} instead (handled by the DAG-only handler).
 */
public final class DataAwareUri {

    public static final String SCHEME = "pulse";

    private DataAwareUri() {}

    /** Build {@code pulse://<tenant>/<domain>/<dataset>} from the registered identifiers. */
    public static String of(String tenant, String domain, String dataset) {
        return SCHEME + "://" + slug(tenant) + "/" + slug(domain) + "/" + slug(dataset);
    }

    /** Emit a producer outlet: {@code outlets=[Dataset("<uri>")]}. */
    public static String outlets(String uri) {
        return "outlets=[Dataset(\"" + uri + "\")]";
    }

    /** Emit a consumer schedule: {@code schedule=[Dataset("<uri>")]}. */
    public static String scheduleOn(String uri) {
        return "schedule=[Dataset(\"" + uri + "\")]";
    }

    private static String slug(String s) {
        if (s == null || s.isBlank()) {
            throw new EmissionException("data-aware URI segment must not be blank");
        }
        // Canonical, deterministic: lowercase, non-alphanumerics -> '-', collapse repeats.
        String out = s.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        out = out.replaceAll("(^-+)|(-+$)", "");
        return out;
    }
}
