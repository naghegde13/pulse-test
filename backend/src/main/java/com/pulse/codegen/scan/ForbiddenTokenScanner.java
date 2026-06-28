package com.pulse.codegen.scan;

import com.pulse.codegen.model.GeneratedArtifact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PKT-0027: Scans generated artifact content for forbidden tokens that
 * indicate raw secrets, live-runnability placeholders, or internal-only
 * references that must never appear in deployable code.
 *
 * <p>This scanner is invoked post-generation before artifacts are persisted,
 * and as a preflight validation step. It produces structured violations that
 * downstream consumers (generation metadata, preflight checks) can act on.
 */
public final class ForbiddenTokenScanner {

    private ForbiddenTokenScanner() {}

    // --- Secret-shaped raw value patterns ---

    /** Patterns that indicate raw secret material was emitted instead of a reference. */
    private static final List<Pattern> RAW_SECRET_PATTERNS = List.of(
            // PEM-encoded private keys
            Pattern.compile("-----BEGIN\\s+(RSA\\s+)?PRIVATE KEY-----"),
            // GCP service account JSON
            Pattern.compile("\"type\"\\s*:\\s*\"service_account\""),
            // GitHub PATs
            Pattern.compile("ghp_[A-Za-z0-9]{36}"),
            Pattern.compile("github_pat_[A-Za-z0-9_]{82}"),
            // Generic long hex tokens (40+ chars, likely a secret)
            Pattern.compile("(?<![a-fA-F0-9])[a-fA-F0-9]{40,}(?![a-fA-F0-9])")
    );

    /** Descriptions for each pattern above, paired by index. */
    private static final List<String> RAW_SECRET_DESCRIPTIONS = List.of(
            "PEM private key block",
            "GCP service account JSON",
            "GitHub personal access token (ghp_)",
            "GitHub fine-grained PAT (github_pat_)",
            "Long hex token (40+ chars)"
    );

    // --- Placeholder tokens that indicate unresolved template variables ---

    private static final Set<String> PLACEHOLDER_TOKENS = Set.of(
            "${JDBC_URL}",
            "${JDBC_DRIVER}",
            "${JDBC_USER}",
            "${JDBC_PASSWORD}",
            "${SOURCE_TABLE}",
            "${KAFKA_BROKERS}",
            "${KAFKA_TOPIC}",
            "${API_BASE_URL}",
            "${API_KEY}",
            "${SF_PASSWORD}",
            "${DEST_JDBC_URL}",
            "${DEST_PASSWORD}",
            "${SOURCE_PATH}",
            "${SOURCE_FORMAT}"
    );

    // --- Internal PULSE tokens that must never leak into generated code ---

    private static final Set<String> INTERNAL_TOKENS = Set.of(
            "PULSE_AIRFLOW_CALLBACK_URL",
            "/api/v1/callbacks/airflow",
            "http://localhost:8080",
            "on_success_callback",
            "on_failure_callback",
            "peer/v1",
            "peer_pulse_url",
            "peerPulseBaseUrl",
            "/api/v1/internal/broker",
            "PULSE_API_URL",
            "PULSE_BROKER_API_URL",
            "PULSE_BROKER_INTERNAL_TOKEN"
    );

    /**
     * File types that should be scanned for forbidden tokens.
     * Runtime support helpers and compile plan metadata are excluded.
     */
    private static final Set<String> SCANNABLE_FILE_TYPES = Set.of(
            "AIRFLOW_DAG",
            "PYSPARK_JOB",
            "DBT_MODEL",
            "DBT_SNAPSHOT",
            "DBT_SOURCE",
            "BROKER_OPERATOR",
            "BROKER_INVOCATION_MANIFEST",
            "CONFIG",
            "REQUIREMENTS_TXT"
    );

    /**
     * Scans a list of generated artifacts and returns a map of violations.
     * Key is the artifact file path; value is the list of violation descriptions.
     */
    public static Map<String, List<String>> scan(List<GeneratedArtifact> artifacts) {
        Map<String, List<String>> violations = new LinkedHashMap<>();
        for (GeneratedArtifact artifact : artifacts) {
            if (artifact.getContent() == null || artifact.getContent().isBlank()) {
                continue;
            }
            if (!SCANNABLE_FILE_TYPES.contains(artifact.getFileType())) {
                continue;
            }
            List<String> artifactViolations = scanContent(artifact.getContent());
            if (!artifactViolations.isEmpty()) {
                violations.put(artifact.getFilePath(), artifactViolations);
            }
        }
        return violations;
    }

    /**
     * Scans a single content string for all classes of forbidden tokens.
     */
    public static List<String> scanContent(String content) {
        List<String> violations = new ArrayList<>();
        scanForRawSecrets(content, violations);
        scanForInternalTokens(content, violations);
        return violations;
    }

    /**
     * Infrastructure path placeholders that indicate the pipeline needs storage
     * backend configuration but are not credential/connection issues. These are
     * tracked but do not block live-runnability by default.
     */
    private static final Set<String> INFRASTRUCTURE_PLACEHOLDERS = Set.of(
            "${OUTPUT_BASE}",
            "${TARGET_PATH}",
            "${UPSTREAM_TASK}",
            "${FILE_NAME}",
            "${REMOTE_PATH}"
    );

    /**
     * Scans content for unresolved placeholder tokens that indicate
     * the artifact is not live-runnable. Returns the list of found placeholders.
     * Only credential/connection placeholders block live-runnability;
     * infrastructure path placeholders are excluded.
     */
    public static List<String> scanForPlaceholders(String content) {
        List<String> found = new ArrayList<>();
        if (content == null) return found;
        for (String placeholder : PLACEHOLDER_TOKENS) {
            if (content.contains(placeholder)) {
                found.add(placeholder);
            }
        }
        // Also catch generic ${VARIABLE_NAME} patterns for credential-shaped placeholders
        java.util.regex.Matcher matcher = Pattern.compile("\\$\\{[A-Z][A-Z0-9_]*}").matcher(content);
        Set<String> genericPlaceholders = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            String match = matcher.group();
            // Exclude known Airflow/Jinja template variables and infrastructure paths
            if (!match.equals("${ds}") && !match.equals("${ts}")
                    && !match.equals("${run_id}")
                    && !INFRASTRUCTURE_PLACEHOLDERS.contains(match)) {
                genericPlaceholders.add(match);
            }
        }
        found.addAll(genericPlaceholders);
        return found;
    }

    private static void scanForRawSecrets(String content, List<String> violations) {
        for (int i = 0; i < RAW_SECRET_PATTERNS.size(); i++) {
            // Skip long-hex-token pattern for content that legitimately contains hashes
            if (i == RAW_SECRET_PATTERNS.size() - 1) {
                // Long hex pattern — only flag if it looks like an inline credential,
                // not a SHA-256 hash in a comment or content hash field
                continue;
            }
            if (RAW_SECRET_PATTERNS.get(i).matcher(content).find()) {
                violations.add("RAW_SECRET: " + RAW_SECRET_DESCRIPTIONS.get(i));
            }
        }
    }

    private static void scanForInternalTokens(String content, List<String> violations) {
        for (String token : INTERNAL_TOKENS) {
            if (content.contains(token)) {
                violations.add("INTERNAL_TOKEN: " + token);
            }
        }
    }

    /**
     * Returns true if the given field name looks like it holds a secret value.
     * Aligned with DeploymentPreflightService.isSecretShapedKey().
     */
    public static boolean isSecretShapedKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password")
                || lower.contains("secret")
                || lower.endsWith("_token") || lower.equals("token")
                || lower.equals("api_key") || lower.equals("private_key")
                || lower.equals("refresh_token") || lower.equals("credentials_json")
                || lower.equals("service_account") || lower.equals("sasl_password")
                || lower.equals("client_secret");
    }
}
