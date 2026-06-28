package com.pulse.sor.service;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.CredentialStatus;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * PKT-0026: Schema Discovery Service.
 * <p>
 * Provides deterministic schema discovery from three sources:
 * <ol>
 *   <li>JDBC table name — retrieves column metadata from DB catalog</li>
 *   <li>JDBC SQL query — parses query result set metadata</li>
 *   <li>Sample upload — infers schema from CSV/JSON sample data</li>
 * </ol>
 * <p>
 * All discovery methods gate on credential readiness: discovery is blocked
 * when credentials are missing, UNTESTED, INVALID, BLOCKED, or FAILED
 * (for JDBC methods). Sample upload does not require credentials.
 * <p>
 * This service does NOT execute live JDBC connections. In dev/test modes,
 * it returns deterministic static schemas based on well-known table names
 * (e.g., Loan_Master). Production JDBC execution is out of scope for
 * PKT-0026 and will be addressed in a future packet.
 */
@Service
public class SchemaDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(SchemaDiscoveryService.class);

    private final ConnectorInstanceRepository ciRepo;
    private final CredentialProfileRepository credRepo;
    private final SystemOfRecordRepository sorRepo;
    private final PiiClassificationService piiClassifier;

    public SchemaDiscoveryService(ConnectorInstanceRepository ciRepo,
                                  CredentialProfileRepository credRepo,
                                  SystemOfRecordRepository sorRepo,
                                  PiiClassificationService piiClassifier) {
        this.ciRepo = ciRepo;
        this.credRepo = credRepo;
        this.sorRepo = sorRepo;
        this.piiClassifier = piiClassifier;
    }

    /**
     * Discovery result containing schema fields, classification, and provenance.
     */
    public record DiscoveryResult(
            List<Map<String, Object>> fields,
            String classification,
            String discoveryMethod,
            Map<String, Object> discoveryProof,
            List<Map<String, Object>> previewRows
    ) {}

    /**
     * Discover schema from a JDBC table name.
     *
     * @param ciId      connector instance ID
     * @param tableName the fully qualified or simple table name
     * @param env       environment for credential lookup (default "dev")
     * @return discovery result with fields, classification, and proof
     */
    public DiscoveryResult discoverFromTable(String ciId, String tableName, String env) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is required");
        }
        String canonicalEnv = env != null ? env.toLowerCase() : "dev";
        enforceCredentialReadiness(ciId, canonicalEnv);

        List<Map<String, Object>> fields = resolveTableSchema(tableName);
        String classification = piiClassifier.classifyFields(fields);

        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("method", "TABLE_DISCOVERY");
        proof.put("tableName", tableName);
        proof.put("columnCount", fields.size());
        proof.put("discoveredAt", Instant.now().toString());
        proof.put("connectorInstanceId", ciId);
        proof.put("environment", canonicalEnv);

        return new DiscoveryResult(fields, classification, "TABLE_DISCOVERY", proof, List.of());
    }

    /**
     * Discover schema from a JDBC SQL query.
     *
     * @param ciId  connector instance ID
     * @param query the SQL query to analyze
     * @param env   environment for credential lookup (default "dev")
     * @return discovery result with fields, classification, and proof
     */
    public DiscoveryResult discoverFromQuery(String ciId, String query, String env) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        String canonicalEnv = env != null ? env.toLowerCase() : "dev";
        enforceCredentialReadiness(ciId, canonicalEnv);

        // Extract table name from simple SELECT queries for static resolution
        String tableName = extractTableName(query);
        List<Map<String, Object>> fields;
        if (tableName != null) {
            fields = resolveTableSchema(tableName);
        } else {
            fields = List.of();
        }
        String classification = piiClassifier.classifyFields(fields);

        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("method", "QUERY_DISCOVERY");
        proof.put("queryHash", sha256(query));
        // queryPreview intentionally omitted from proof to avoid leaking
        // SQL that may contain credential fragments or sensitive filters.
        proof.put("columnCount", fields.size());
        proof.put("discoveredAt", Instant.now().toString());
        proof.put("connectorInstanceId", ciId);
        proof.put("environment", canonicalEnv);

        return new DiscoveryResult(fields, classification, "QUERY_DISCOVERY", proof, List.of());
    }

    /**
     * Discover schema from an uploaded CSV/JSON sample.
     * Does NOT require credentials — sample is self-contained.
     *
     * @param ciId        connector instance ID (for provenance binding)
     * @param sampleData  the sample content as a string
     * @param format      "CSV" or "JSON"
     * @return discovery result with fields, classification, and proof
     */
    private static final int MAX_SAMPLE_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final int MAX_PREVIEW_ROWS = 10;

    public DiscoveryResult discoverFromSample(String ciId, String sampleData, String format) {
        if (sampleData == null || sampleData.isBlank()) {
            throw new IllegalArgumentException("sampleData is required");
        }
        // Validate connector instance exists (but do not require credentials)
        ciRepo.findById(ciId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorInstance", ciId));

        DiscoveryResult base = inferSample(sampleData, format);
        Map<String, Object> proof = new LinkedHashMap<>(base.discoveryProof());
        proof.put("connectorInstanceId", ciId);
        return new DiscoveryResult(base.fields(), base.classification(),
                base.discoveryMethod(), proof, base.previewRows());
    }

    /**
     * Deterministically infer a typed schema, PII/classification, and preview
     * rows from a raw CSV/JSON sample, with no connector or credential binding.
     * <p>
     * This is the credential-free core used by the first-class
     * "Upload Sample File" dataset-definition workflow. Inference is 100%
     * deterministic (rule-based type detection over the sampled values) with
     * no LLM involvement (ADR 0011).
     */
    public DiscoveryResult inferSample(String sampleData, String format) {
        if (sampleData == null || sampleData.isBlank()) {
            throw new IllegalArgumentException("sampleData is required");
        }
        if (sampleData.length() > MAX_SAMPLE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "Sample data exceeds maximum size of 5MB. Provide a smaller sample.");
        }

        List<Map<String, Object>> fields;
        List<Map<String, Object>> previewRows;
        String canonicalFormat;
        if ("CSV".equalsIgnoreCase(format) || "TSV".equalsIgnoreCase(format)) {
            canonicalFormat = "TSV".equalsIgnoreCase(format) ? "TSV" : "CSV";
            char delimiter = "TSV".equalsIgnoreCase(format) ? '\t' : ',';
            previewRows = parseDelimitedRows(sampleData, delimiter);
            fields = inferSchemaFromRows(previewRows, orderedHeaders(sampleData, delimiter));
        } else if ("JSON".equalsIgnoreCase(format)) {
            canonicalFormat = "JSON";
            previewRows = parseJsonRows(sampleData);
            fields = inferSchemaFromRows(previewRows, orderedJsonKeys(previewRows));
        } else {
            throw new IllegalArgumentException(
                    "Unsupported sample format: " + format + ". Supported: CSV, TSV, JSON");
        }

        String classification = piiClassifier.classifyFields(fields);

        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("method", "SAMPLE_UPLOAD");
        proof.put("sampleHash", sha256(sampleData));
        proof.put("sampleFormat", canonicalFormat);
        proof.put("columnCount", fields.size());
        proof.put("rowsSampled", previewRows.size());
        proof.put("piiColumns", fields.stream()
                .filter(f -> "PII".equals(f.get("classification")))
                .map(f -> f.get("name")).toList());
        proof.put("confidentialColumns", fields.stream()
                .filter(f -> "CONFIDENTIAL".equals(f.get("classification")))
                .map(f -> f.get("name")).toList());
        proof.put("discoveredAt", Instant.now().toString());

        return new DiscoveryResult(fields, classification, "SAMPLE_UPLOAD", proof, previewRows);
    }

    /**
     * Enforce that the connector instance has valid credentials for the given environment.
     * Throws an IllegalStateException with a secret-safe message if credentials are
     * missing, not validated, or in a blocked/failed state.
     */
    void enforceCredentialReadiness(String ciId, String env) {
        ciRepo.findById(ciId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorInstance", ciId));

        Optional<CredentialProfile> credOpt = credRepo.findByConnectorInstanceIdAndEnvironment(ciId, env);
        if (credOpt.isEmpty()) {
            throw new IllegalStateException(
                    "Schema discovery requires validated credentials. " +
                    "No credential profile found for connector instance '" + ciId +
                    "' in environment '" + env + "'. " +
                    "Please configure and validate credentials before attempting schema discovery.");
        }

        CredentialProfile cred = credOpt.get();
        CredentialStatus status = cred.getStatus();
        if (status != CredentialStatus.VALID) {
            String message = switch (status) {
                case UNTESTED -> "Credentials exist but have not been validated. " +
                        "Please validate credentials before attempting schema discovery.";
                case INVALID -> "Credentials have been validated and are INVALID. " +
                        "Please update and re-validate credentials.";
                case BLOCKED -> "Credential access is BLOCKED (secret authority check failed). " +
                        "Resolve secret authority configuration before discovery.";
                case FAILED -> "Credential validation FAILED due to a secret access error. " +
                        "Check secret store configuration and retry validation.";
                case SKIPPED -> "Credentials are SKIPPED for this connector. " +
                        "Schema discovery requires active, validated credentials.";
                default -> "Credential status is " + status + ". " +
                        "Discovery requires VALID credentials.";
            };
            throw new IllegalStateException(message);
        }
    }

    // --- Static schema resolution for known tables ---

    private List<Map<String, Object>> resolveTableSchema(String tableName) {
        // Normalize table name for matching
        String normalized = tableName.replaceAll("[\"'`\\[\\]]", "")
                .replaceAll(".*\\.", "") // strip schema prefix
                .toLowerCase();

        return switch (normalized) {
            case "loan_master" -> loanMasterSchema();
            default -> {
                log.info("No static schema registered for table '{}'; returning empty. " +
                        "Live JDBC metadata lookup is not available in this mode.", tableName);
                yield List.of();
            }
        };
    }

    private List<Map<String, Object>> loanMasterSchema() {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("loan_id", "VARCHAR", false));
        fields.add(field("borrower_name", "VARCHAR", true));
        fields.add(field("ssn", "VARCHAR", true));
        fields.add(field("email", "VARCHAR", true));
        fields.add(field("phone", "VARCHAR", true));
        fields.add(field("property_address", "VARCHAR", true));
        fields.add(field("loan_amount", "DECIMAL", true));
        fields.add(field("interest_rate", "DECIMAL", true));
        fields.add(field("loan_term_months", "INTEGER", true));
        fields.add(field("origination_date", "DATE", true));
        fields.add(field("maturity_date", "DATE", true));
        fields.add(field("current_balance", "DECIMAL", true));
        fields.add(field("monthly_payment", "DECIMAL", true));
        fields.add(field("escrow_balance", "DECIMAL", true));
        fields.add(field("ltv", "DECIMAL", true));
        fields.add(field("dti", "DECIMAL", true));
        fields.add(field("credit_score", "INTEGER", true));
        fields.add(field("loan_status", "VARCHAR", true));
        fields.add(field("last_payment_date", "DATE", true));
        fields.add(field("next_payment_date", "DATE", true));
        fields.add(field("as_of_date", "DATE", true));
        return fields;
    }

    private Map<String, Object> field(String name, String type, boolean nullable) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("type", type);
        f.put("nullable", nullable);
        return f;
    }

    private String extractTableName(String query) {
        if (query == null) return null;
        // Simple pattern: SELECT ... FROM <table> ...
        String upper = query.trim().toUpperCase();
        int fromIdx = upper.indexOf("FROM ");
        if (fromIdx < 0) return null;
        String afterFrom = query.substring(fromIdx + 5).trim();
        // Take the first token (table name)
        String[] parts = afterFrom.split("\\s+", 2);
        if (parts.length == 0) return null;
        return parts[0].replaceAll("[;,]", "");
    }

    // --- Sample inference ---

    /**
     * Parse a delimited (CSV/TSV) sample into ordered preview rows
     * (header -> cell value), capped at {@link #MAX_PREVIEW_ROWS}.
     */
    private List<Map<String, Object>> parseDelimitedRows(String content, char delimiter) {
        String[] lines = content.split("\\r?\\n");
        if (lines.length == 0 || lines[0].isBlank()) return List.of();

        List<String> headers = orderedHeaders(content, delimiter);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i < lines.length && rows.size() < MAX_PREVIEW_ROWS; i++) {
            if (lines[i].isBlank()) continue;
            String[] cells = splitDelimited(lines[i], delimiter);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String value = c < cells.length ? unquote(cells[c]) : "";
                row.put(headers.get(c), value);
            }
            rows.add(row);
        }
        return rows;
    }

    private List<String> orderedHeaders(String content, char delimiter) {
        String[] lines = content.split("\\r?\\n");
        if (lines.length == 0 || lines[0].isBlank()) return List.of();
        String[] rawHeaders = splitDelimited(lines[0], delimiter);
        List<String> headers = new ArrayList<>();
        for (String h : rawHeaders) {
            headers.add(unquote(h));
        }
        return headers;
    }

    private String[] splitDelimited(String line, char delimiter) {
        return line.split(java.util.regex.Pattern.quote(String.valueOf(delimiter)), -1);
    }

    private String unquote(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("^\"|\"$", "");
    }

    /**
     * Parse a flat JSON object or array-of-objects into ordered preview rows,
     * capped at {@link #MAX_PREVIEW_ROWS}.
     */
    private List<Map<String, Object>> parseJsonRows(String json) {
        String trimmed = json.trim();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (trimmed.startsWith("[")) {
            int pos = 1;
            while (pos < trimmed.length() && rows.size() < MAX_PREVIEW_ROWS) {
                int objStart = trimmed.indexOf('{', pos);
                if (objStart < 0) break;
                int objEnd = findMatchingBrace(trimmed, objStart + 1, '{', '}');
                if (objEnd < 0) break;
                Map<String, Object> row = parseFlatJsonObject(trimmed.substring(objStart, objEnd + 1));
                if (!row.isEmpty()) rows.add(row);
                pos = objEnd + 1;
            }
        } else if (trimmed.startsWith("{")) {
            Map<String, Object> row = parseFlatJsonObject(trimmed);
            if (!row.isEmpty()) rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> parseFlatJsonObject(String obj) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (!obj.startsWith("{") || obj.lastIndexOf('}') < 0) return row;
        String inner = obj.substring(1, obj.lastIndexOf('}'));
        if (inner.isBlank()) return row;
        String[] pairs = inner.split(",(?=\\s*\")");
        for (String pair : pairs) {
            int colonIdx = pair.indexOf(':');
            if (colonIdx < 0) continue;
            String key = pair.substring(0, colonIdx).trim().replaceAll("^\"|\"$", "");
            String value = pair.substring(colonIdx + 1).trim();
            row.put(key, normalizeJsonValue(value));
        }
        return row;
    }

    private String normalizeJsonValue(String value) {
        if (value == null) return "";
        String v = value.trim();
        if ("null".equals(v)) return "";
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private List<String> orderedJsonKeys(List<Map<String, Object>> rows) {
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            for (String k : row.keySet()) {
                if (!keys.contains(k)) keys.add(k);
            }
        }
        return keys;
    }

    /**
     * Infer a typed column for each header by scanning every sampled value:
     * the column type is the narrowest type that fits all non-empty values,
     * and the column is nullable iff any sampled value is empty/missing.
     */
    private List<Map<String, Object>> inferSchemaFromRows(List<Map<String, Object>> rows, List<String> headers) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String header : headers) {
            String widestType = null;
            boolean nullable = rows.isEmpty();
            for (Map<String, Object> row : rows) {
                Object raw = row.get(header);
                String value = raw == null ? "" : raw.toString();
                if (value.isEmpty()) {
                    nullable = true;
                    continue;
                }
                String t = inferTypeFromValue(value);
                widestType = widenType(widestType, t);
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", header);
            f.put("type", widestType != null ? widestType : "VARCHAR");
            f.put("nullable", nullable);
            fields.add(f);
        }
        return fields;
    }

    /**
     * Deterministically combine two observed types into the narrowest type
     * that can represent both. Mixed numeric widens INTEGER -> DECIMAL; any
     * incompatible mix falls back to VARCHAR.
     */
    private String widenType(String current, String next) {
        if (current == null) return next;
        if (current.equals(next)) return current;
        boolean curNum = current.equals("INTEGER") || current.equals("DECIMAL");
        boolean nextNum = next.equals("INTEGER") || next.equals("DECIMAL");
        if (curNum && nextNum) return "DECIMAL";
        return "VARCHAR";
    }

    private int findMatchingBrace(String s, int startIdx, char open, char close) {
        int depth = 0;
        for (int i = startIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) depth++;
            else if (c == close) {
                if (depth == 0) return i;
                depth--;
            }
        }
        return -1;
    }

    private String inferTypeFromValue(String value) {
        if (value == null || value.isEmpty()) return "VARCHAR";
        if (value.matches("-?\\d+")) return "INTEGER";
        if (value.matches("-?\\d+\\.\\d+")) return "DECIMAL";
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) return "DATE";
        if (value.matches("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}.*")) return "TIMESTAMP";
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return "BOOLEAN";
        return "VARCHAR";
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
