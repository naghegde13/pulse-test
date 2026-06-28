package com.pulse.codegen.opengine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Config-externalization (SPEC #2 §C.3, G-C2 LOCKED). Generated code reads its
 * per-env values (connection strings, paths, project IDs) from an env-var-selected
 * per-env config slice — NEVER baked into the code.
 *
 * <ul>
 *   <li>The slice is selected by the env var {@code PULSE_ENV} &isin;
 *       {@code {dev | integration | uat | prod}}.</li>
 *   <li>{@code PULSE_ENV} names the slice file {@code config/<env>.yaml}.</li>
 *   <li>The slice is loaded at job start; generated code references keys, not literals.</li>
 * </ul>
 *
 * <p>This consistently extends the existing env-var reads
 * ({@code PULSE_RUN_ID}/{@code PULSE_BUSINESS_DATE}/{@code PULSE_SOURCE_URI}) the
 * audit emit already uses.
 */
public final class ConfigExternalizer {

    public static final String ENV_VAR = "PULSE_ENV";
    public static final List<String> ENVS = List.of("dev", "integration", "uat", "prod");

    private ConfigExternalizer() {}

    /** The slice file path for an env: {@code config/<env>.yaml}. */
    public static String sliceFile(String env) {
        if (!ENVS.contains(env)) {
            throw new EmissionException(
                    "PULSE_ENV '" + env + "' is not one of " + ENVS);
        }
        return "config/" + env + ".yaml";
    }

    /**
     * The Python config-loader snippet generated code uses at job start: read
     * {@code PULSE_ENV} (default {@code dev}), load {@code config/<env>.yaml}, and
     * expose it as a dict. No connection strings / paths / project IDs are baked in.
     */
    public static String pythonLoader() {
        return ""
            + "# PULSE config externalization (SPEC #2 §C.3): per-env slice selected by PULSE_ENV.\n"
            + "import os, yaml\n"
            + "PULSE_ENV = os.environ.get('" + ENV_VAR + "', 'dev')\n"
            + "with open(f'config/{PULSE_ENV}.yaml') as _f:\n"
            + "    PULSE_CONFIG = yaml.safe_load(_f)\n";
    }

    /**
     * Render a per-env slice file body (YAML) from a key/value map. Deterministic
     * ordering (insertion order). Values are the per-env literals (e.g. project id,
     * gcs bucket, connection string) that must NOT be baked into the generated code.
     */
    public static String renderSlice(String env, Map<String, Object> values) {
        if (!ENVS.contains(env)) {
            throw new EmissionException("PULSE_ENV '" + env + "' is not one of " + ENVS);
        }
        StringBuilder yaml = new StringBuilder();
        yaml.append("# config/").append(env).append(".yaml — PULSE per-env config slice (")
            .append(env).append("). Selected by PULSE_ENV. Generated code reads these keys.\n");
        Map<String, Object> ordered = values == null ? Map.of() : new LinkedHashMap<>(values);
        for (Map.Entry<String, Object> e : ordered.entrySet()) {
            yaml.append(e.getKey()).append(": ").append(renderScalar(e.getValue())).append("\n");
        }
        return yaml.toString();
    }

    private static String renderScalar(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        String s = v.toString();
        // Quote strings that need it (deterministic, conservative).
        if (s.isEmpty() || s.matches(".*[:#{}\\[\\],&*!|>'\"%@`].*") || s.contains(" ")) {
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
        return s;
    }
}
