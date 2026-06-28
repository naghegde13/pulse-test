package com.pulse.e2e.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceArtifact;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceBundle;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Test-harness validator for Phase 3 live-runtime evidence.
 *
 * <p>Resolves local object-store URIs (s3a/s3/minio/file/plain paths), probes for a materialized CSV,
 * and compares the observed output against the canonical loan_master data oracle.
 */
public class MinioOutputOracleValidator {

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("-?\\d+\\.\\d+");
    private static final Pattern BOOLEAN_PATTERN = Pattern.compile("(?i:true|false)");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\(\\d{3}\\) \\d{3}-\\d{4}$");
    private static final Pattern MASKED_SSN_PATTERN = Pattern.compile("^XXX-XX-\\d{4}$");
    private static final Pattern RAW_SSN_PATTERN = Pattern.compile("^\\d{3}-?\\d{2}-?\\d{4}$");
    private static final List<String> REQUIRED_COLUMNS = List.of("loan_id", "loan_number", "origination_date", "current_upb", "investor_name");
    private static final List<String> PII_COLUMNS = List.of(
            "borrower_first_name",
            "borrower_last_name",
            "borrower_ssn_masked",
            "borrower_dob",
            "borrower_email",
            "borrower_phone",
            "property_address_line1",
            "coborrower_first_name",
            "coborrower_last_name"
    );
    private static final List<String> AGGREGATE_COLUMNS = List.of(
            "original_loan_amount",
            "current_upb",
            "interest_rate",
            "pi_payment",
            "escrow_payment_monthly",
            "total_monthly_payment",
            "months_delinquent",
            "ltv_ratio",
            "cltv_ratio",
            "appraised_value",
            "borrower_annual_income",
            "borrower_credit_score",
            "remaining_term_months",
            "late_charges_due",
            "suspense_balance",
            "partial_payment_balance"
    );
    private static final List<String> PROFILE_COLUMNS = List.of(
            "loan_status", "investor_name", "property_state", "loan_type", "loan_purpose", "occupancy_type"
    );

    private final ObjectMapper objectMapper;

    public MinioOutputOracleValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(ProbeRequest request) throws IOException {
        Files.createDirectories(request.evidenceRoot());
        Map<String, Object> oracle = objectMapper.readValue(request.oraclePath().toFile(), MAP_TYPE);
        Map<String, Object> expected = expectedMetrics(oracle, request.expectedOverrides());

        List<String> failureCodes = new ArrayList<>();
        Path resolvedOutputPath = resolveOutputPath(request.outputUri(), request.objectRoot());
        List<Path> materializedCsvFiles = resolveCsvFiles(resolvedOutputPath);

        Map<String, Object> probePayload = new LinkedHashMap<>();
        probePayload.put("scenarioId", request.scenarioId());
        probePayload.put("generationRunId", request.generationRunId());
        probePayload.put("probedAt", Instant.now().toString());
        probePayload.put("outputUri", request.outputUri());
        probePayload.put("resolvedOutputPath", resolvedOutputPath.toString());
        probePayload.put("exists", Files.exists(resolvedOutputPath));
        probePayload.put("isDirectory", Files.isDirectory(resolvedOutputPath));
        probePayload.put("materializedCsvFiles", materializedCsvFiles.stream().map(Path::toString).toList());

        Map<String, Object> actual = null;
        if (!Files.exists(resolvedOutputPath)) {
            failureCodes.add("output_missing");
        } else if (materializedCsvFiles.isEmpty()) {
            failureCodes.add("materialized_csv_missing");
        } else {
            CsvTable table = CsvTable.load(materializedCsvFiles);
            actual = actualMetrics(table, materializedCsvFiles);
            compare(expected, actual, failureCodes);
            probePayload.put("sampleHeaders", table.headers());
        }
        probePayload.put("actual", actual);

        String verdict = failureCodes.isEmpty() ? "PASS" : "FAIL";
        Map<String, Object> comparisonPayload = new LinkedHashMap<>();
        comparisonPayload.put("scenarioId", request.scenarioId());
        comparisonPayload.put("generationRunId", request.generationRunId());
        comparisonPayload.put("verdict", verdict);
        comparisonPayload.put("failureCodes", failureCodes);
        comparisonPayload.put("expected", expected);
        comparisonPayload.put("actual", actual);

        Path probePath = request.evidenceRoot().resolve("minio-output-probe.json");
        Path comparisonPath = request.evidenceRoot().resolve("data-oracle-comparison.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(probePath.toFile(), probePayload);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(comparisonPath.toFile(), comparisonPayload);

        List<EvidenceArtifact> artifacts = new ArrayList<>();
        artifacts.add(evidenceArtifact("minio-output-probe", "MINIO_OUTPUT_PROBE", probePath, Map.of(
                "outputUri", request.outputUri(),
                "resolvedOutputPath", resolvedOutputPath.toString())));
        artifacts.add(evidenceArtifact("data-oracle-comparison", "DATA_ORACLE_COMPARISON", comparisonPath, Map.of(
                "verdict", verdict,
                "failureCount", failureCodes.size())));

        EvidenceBundle bundle = new EvidenceBundle(
                request.scenarioId(),
                request.generationRunId(),
                request.evidenceRoot(),
                List.copyOf(artifacts),
                Map.of(
                        "verdict", verdict,
                        "failureCodes", List.copyOf(failureCodes),
                        "artifactCount", artifacts.size()
                )
        );
        return new ValidationResult(verdict, List.copyOf(failureCodes), resolvedOutputPath, List.copyOf(materializedCsvFiles), bundle);
    }

    private Map<String, Object> expectedMetrics(Map<String, Object> oracle, Map<String, Object> overrides) {
        boolean useOracleDefaultsForExtendedChecks = overrides == null || overrides.isEmpty();
        Map<String, Object> oracleChecksums = asJavaMap(oracle.getOrDefault("checksums", Map.of()));
        Map<String, Object> oracleSchema = asJavaMap(oracle.getOrDefault("schema", Map.of()));
        Map<String, Object> oracleDqCounts = asJavaMap(oracle.getOrDefault("dq_counts", Map.of()));
        Map<String, Object> requiredFieldNulls = asJavaMap(oracleDqCounts.getOrDefault("required_field_nulls", Map.of()));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("row_count", valueOrOverride(overrides, "row_count", oracle.get("row_count")));
        expected.put("column_count", valueOrOverride(overrides, "column_count", oracle.get("column_count")));
        expected.put("canonical_csv_sha256", valueOrOverride(overrides, "canonical_csv_sha256", oracleChecksums.get("canonical_csv_sha256")));
        expected.put("schema_signature", schemaValueOrOverride(overrides, "signature", oracleSchema.get("signature")));
        expected.put("column_order_sha256", schemaValueOrOverride(overrides, "column_order_sha256", oracleSchema.get("column_order_sha256")));
        expected.put("required_field_nulls", valueOrOverride(overrides, "required_field_nulls", requiredFieldNulls));
        expected.put("business_keys", valueOrOverride(overrides, "business_keys", oracle.getOrDefault("business_keys", List.of())));
        putOptionalExpected(expected, "aggregates", oracle, overrides, useOracleDefaultsForExtendedChecks);
        putOptionalExpected(expected, "dq_counts", oracle, overrides, useOracleDefaultsForExtendedChecks);
        putOptionalExpected(expected, "partition_expectations", oracle, overrides, useOracleDefaultsForExtendedChecks);
        putOptionalExpected(expected, "masked_pii_assertions", oracle, overrides, useOracleDefaultsForExtendedChecks);
        return expected;
    }

    private Object valueOrOverride(Map<String, Object> overrides, String key, Object fallback) {
        return overrides != null && overrides.containsKey(key) ? overrides.get(key) : fallback;
    }

    private Object schemaValueOrOverride(Map<String, Object> overrides, String schemaKey, Object fallback) {
        if (overrides != null) {
            String topLevelKey = "signature".equals(schemaKey) ? "schema_signature" : schemaKey;
            if (overrides.containsKey(topLevelKey)) {
                return overrides.get(topLevelKey);
            }
            Object schema = overrides.get("schema");
            if (schema instanceof Map<?, ?> schemaMap && schemaMap.containsKey(schemaKey)) {
                return schemaMap.get(schemaKey);
            }
        }
        return fallback;
    }

    private void putOptionalExpected(Map<String, Object> expected,
                                     String key,
                                     Map<String, Object> oracle,
                                     Map<String, Object> overrides,
                                     boolean useOracleDefaults) {
        if (overrides != null && overrides.containsKey(key)) {
            expected.put(key, overrides.get(key));
            return;
        }
        if (useOracleDefaults && oracle.containsKey(key)) {
            expected.put(key, oracle.get(key));
        }
    }

    private void compare(Map<String, Object> expected, Map<String, Object> actual, List<String> failureCodes) {
        compareInt(expected.get("row_count"), actual.get("row_count"), "row_count_mismatch", failureCodes);
        compareInt(expected.get("column_count"), actual.get("column_count"), "column_count_mismatch", failureCodes);
        compareString(expected.get("canonical_csv_sha256"), actual.get("canonical_csv_sha256"), "canonical_csv_checksum_mismatch", failureCodes);
        compareString(expected.get("schema_signature"), actual.get("schema_signature"), "schema_signature_mismatch", failureCodes);
        compareString(expected.get("column_order_sha256"), actual.get("column_order_sha256"), "column_order_mismatch", failureCodes);

        Map<String, Object> expectedNulls = asJavaMap(expected.getOrDefault("required_field_nulls", Map.of()));
        Map<String, Object> actualNulls = asJavaMap(actual.getOrDefault("required_field_nulls", Map.of()));
        for (Map.Entry<String, Object> entry : expectedNulls.entrySet()) {
            Object actualValue = actualNulls.get(entry.getKey());
            if (!Objects.equals(asInt(entry.getValue()), asInt(actualValue))) {
                failureCodes.add("required_field_nulls_mismatch:" + entry.getKey());
            }
        }

        List<String> businessKeys = asStringList(expected.getOrDefault("business_keys", List.of()));
        List<String> actualHeaders = asStringList(actual.getOrDefault("headers", List.of()));
        for (String businessKey : businessKeys) {
            if (!actualHeaders.contains(businessKey)) {
                failureCodes.add("missing_business_key:" + businessKey);
            }
        }

        compareStructured(expected.get("aggregates"), actual.get("aggregates"), "aggregate_profile_mismatch", failureCodes);
        compareStructured(expected.get("dq_counts"), actual.get("dq_counts"), "dq_counts_mismatch", failureCodes);
        compareStructured(expected.get("partition_expectations"), actual.get("partition_expectations"), "partition_expectations_mismatch", failureCodes);
        compareStructured(expected.get("masked_pii_assertions"), actual.get("masked_pii_assertions"), "masked_pii_assertions_mismatch", failureCodes);
    }

    private void compareInt(Object expected, Object actual, String code, List<String> failureCodes) {
        if (!Objects.equals(asInt(expected), asInt(actual))) {
            failureCodes.add(code);
        }
    }

    private void compareString(Object expected, Object actual, String code, List<String> failureCodes) {
        String left = expected == null ? null : String.valueOf(expected);
        String right = actual == null ? null : String.valueOf(actual);
        if (!Objects.equals(left, right)) {
            failureCodes.add(code);
        }
    }

    private void compareStructured(Object expected, Object actual, String code, List<String> failureCodes) {
        if (expected == null) {
            return;
        }
        if (!Objects.equals(normalizeStructured(expected), normalizeStructured(actual))) {
            failureCodes.add(code);
        }
    }

    private Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Map<String, Object> actualMetrics(CsvTable table, List<Path> materializedCsvFiles) throws IOException {
        Map<String, String> inferredTypes = inferColumnTypes(table);
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("row_count", table.rows().size());
        actual.put("column_count", table.headers().size());
        actual.put("headers", table.headers());
        actual.put("canonical_csv_sha256", sha256(table.toCanonicalCsv().getBytes(StandardCharsets.UTF_8)));
        actual.put("schema_signature", schemaSignature(table.headers(), inferredTypes));
        actual.put("column_order_sha256", sha256(String.join("|", table.headers()).getBytes(StandardCharsets.UTF_8)));
        actual.put("required_field_nulls", requiredFieldNulls(table));
        actual.put("aggregates", aggregateProfile(table));
        actual.put("dq_counts", dqProfile(table));
        actual.put("partition_expectations", partitionProfile(table));
        actual.put("masked_pii_assertions", piiProfile(table));
        actual.put("materialized_file_count", materializedCsvFiles.size());
        actual.put("materialized_file_sha256", materializedCsvFiles.stream().collect(Collectors.toMap(
                path -> path.getFileName().toString(),
                this::sha256File,
                (a, b) -> a,
                LinkedHashMap::new
        )));
        actual.put("inferred_types", inferredTypes);
        return actual;
    }

    private Map<String, Object> requiredFieldNulls(CsvTable table) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String column : REQUIRED_COLUMNS) {
            out.put(column, countWhere(table.rows(), row -> row.get(column) == null || row.get(column).isBlank()));
        }
        return out;
    }

    private Map<String, Object> aggregateProfile(CsvTable table) {
        Map<String, Object> aggregates = new LinkedHashMap<>();
        for (String column : AGGREGATE_COLUMNS) {
            List<BigDecimal> values = table.rows().stream()
                    .map(row -> decimalValue(row, column))
                    .filter(Objects::nonNull)
                    .toList();
            Map<String, Object> stats = new LinkedHashMap<>();
            BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal average = values.isEmpty()
                    ? BigDecimal.ZERO
                    : sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
            stats.put("sum", sum.stripTrailingZeros().toPlainString());
            stats.put("min", values.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
            stats.put("max", values.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
            stats.put("avg", average.toPlainString());
            aggregates.put(column, stats);
        }
        Map<String, Object> dimensions = new LinkedHashMap<>();
        for (String column : PROFILE_COLUMNS) {
            dimensions.put(column, counts(table.rows(), column));
        }
        aggregates.put("dimension_counts", dimensions);
        return aggregates;
    }

    private Map<String, Object> dqProfile(CsvTable table) {
        Map<String, Object> dq = new LinkedHashMap<>();
        dq.put("required_field_nulls", requiredFieldNulls(table));
        dq.put("duplicate_key_counts", Map.of(
                "loan_id", duplicateCount(table, "loan_id"),
                "loan_number", duplicateCount(table, "loan_number"),
                "investor_loan_id", duplicateCount(table, "investor_loan_id")
        ));
        dq.put("email_format_failures", countWhere(table.rows(), row -> !matches(EMAIL_PATTERN, row.get("borrower_email"))));
        dq.put("phone_format_failures", countWhere(table.rows(), row -> !matches(PHONE_PATTERN, row.get("borrower_phone"))));
        dq.put("masked_ssn_pattern_failures", countWhere(table.rows(), row -> !matches(MASKED_SSN_PATTERN, row.get("borrower_ssn_masked"))));
        dq.put("raw_ssn_leak_count", countWhere(table.rows(), row -> matches(RAW_SSN_PATTERN, row.get("borrower_ssn_masked"))
                && !matches(MASKED_SSN_PATTERN, row.get("borrower_ssn_masked"))));
        dq.put("current_balance_gt_original_count", countWhere(table.rows(), row -> {
            BigDecimal currentUpb = decimalValue(row, "current_upb");
            BigDecimal originalAmount = decimalValue(row, "original_loan_amount");
            return currentUpb != null && originalAmount != null && currentUpb.compareTo(originalAmount) > 0;
        }));
        dq.put("delinquent_row_count", countWhere(table.rows(), row -> intValue(row, "months_delinquent") > 0));
        dq.put("current_status_with_delinquency_count", countWhere(table.rows(), row -> "Current".equals(row.get("loan_status")) && intValue(row, "months_delinquent") > 0));
        return dq;
    }

    private Map<String, Object> partitionProfile(CsvTable table) {
        Map<String, Object> partitions = new LinkedHashMap<>();
        partitions.put("investor_name", counts(table.rows(), "investor_name"));
        partitions.put("loan_status", counts(table.rows(), "loan_status"));
        partitions.put("property_state", counts(table.rows(), "property_state"));
        partitions.put("investor_property_state", countsByComposite(table, "investor_name", "property_state"));
        return partitions;
    }

    private Map<String, Object> piiProfile(CsvTable table) {
        Map<String, Object> pii = new LinkedHashMap<>();
        pii.put("pii_columns", PII_COLUMNS);
        pii.put("masked_columns", List.of("borrower_ssn_masked"));
        pii.put("ssn_mask", Map.of(
                "pattern", "XXX-XX-####",
                "valid_row_count", countWhere(table.rows(), row -> matches(MASKED_SSN_PATTERN, row.get("borrower_ssn_masked"))),
                "invalid_row_count", countWhere(table.rows(), row -> !matches(MASKED_SSN_PATTERN, row.get("borrower_ssn_masked"))),
                "raw_leak_count", countWhere(table.rows(), row -> matches(RAW_SSN_PATTERN, row.get("borrower_ssn_masked"))
                        && !matches(MASKED_SSN_PATTERN, row.get("borrower_ssn_masked")))
        ));
        pii.put("email_assertions", Map.of(
                "format_valid_count", countWhere(table.rows(), row -> matches(EMAIL_PATTERN, row.get("borrower_email"))),
                "null_count", countWhere(table.rows(), row -> row.get("borrower_email") == null || row.get("borrower_email").isBlank())
        ));
        pii.put("phone_assertions", Map.of(
                "format_valid_count", countWhere(table.rows(), row -> matches(PHONE_PATTERN, row.get("borrower_phone"))),
                "null_count", countWhere(table.rows(), row -> row.get("borrower_phone") == null || row.get("borrower_phone").isBlank())
        ));
        return pii;
    }

    private boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private int duplicateCount(CsvTable table, String column) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, String> row : table.rows()) {
            counts.merge(String.valueOf(row.get(column)), 1, Integer::sum);
        }
        return counts.values().stream().mapToInt(v -> Math.max(0, v - 1)).sum();
    }

    private Map<String, Object> counts(List<Map<String, String>> rows, String column) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            counts.merge(String.valueOf(row.get(column)), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private Map<String, Object> countsByComposite(CsvTable table, String first, String second) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, String> row : table.rows()) {
            String key = String.valueOf(row.get(first)) + "|" + String.valueOf(row.get(second));
            counts.merge(key, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private BigDecimal decimalValue(Map<String, String> row, String column) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value);
    }

    private int intValue(Map<String, String> row, String column) {
        String value = row.get(column);
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private Object normalizeStructured(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .collect(Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()),
                            entry -> normalizeStructured(entry.getValue()),
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::normalizeStructured).toList();
        }
        if (value instanceof Set<?> set) {
            return set.stream().map(this::normalizeStructured).toList();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        return value;
    }

    private Map<String, Object> asJavaMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        return objectMapper.convertValue(value, STRING_LIST);
    }

    private int countWhere(List<Map<String, String>> rows, java.util.function.Predicate<Map<String, String>> predicate) {
        int count = 0;
        for (Map<String, String> row : rows) {
            if (predicate.test(row)) {
                count++;
            }
        }
        return count;
    }

    private Map<String, String> inferColumnTypes(CsvTable table) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String header : table.headers()) {
            boolean allBoolean = true;
            boolean allInteger = true;
            boolean allDecimalOrInteger = true;
            boolean allDate = true;
            boolean hasLeadingZeroInteger = false;
            for (Map<String, String> row : table.rows()) {
                String value = row.get(header);
                if (value == null || value.isBlank()) {
                    continue;
                }
                hasLeadingZeroInteger |= value.matches("^0[0-9]+$");
                allBoolean &= BOOLEAN_PATTERN.matcher(value).matches();
                allInteger &= INTEGER_PATTERN.matcher(value).matches();
                allDecimalOrInteger &= INTEGER_PATTERN.matcher(value).matches() || DECIMAL_PATTERN.matcher(value).matches();
                allDate &= DATE_PATTERN.matcher(value).matches();
            }
            if (hasLeadingZeroInteger) {
                out.put(header, "string");
            } else if (allBoolean) {
                out.put(header, "boolean");
            } else if (allInteger) {
                out.put(header, "integer");
            } else if (allDecimalOrInteger) {
                out.put(header, "decimal");
            } else if (allDate) {
                out.put(header, "date");
            } else {
                out.put(header, "string");
            }
        }
        return out;
    }

    private String schemaSignature(List<String> headers, Map<String, String> types) {
        String payload = headers.stream()
                .map(header -> header + ":" + types.get(header))
                .collect(Collectors.joining("|"));
        return sha256(payload.getBytes(StandardCharsets.UTF_8));
    }

    private List<Path> resolveCsvFiles(Path resolvedOutputPath) throws IOException {
        if (!Files.exists(resolvedOutputPath)) {
            return List.of();
        }
        if (Files.isRegularFile(resolvedOutputPath)) {
            return resolvedOutputPath.toString().endsWith(".csv") ? List.of(resolvedOutputPath) : List.of();
        }
        try (Stream<Path> stream = Files.walk(resolvedOutputPath)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .toList();
        }
    }

    private Path resolveOutputPath(String outputUri, Path objectRoot) {
        if (outputUri == null || outputUri.isBlank()) {
            throw new IllegalArgumentException("outputUri is required");
        }
        if (outputUri.startsWith("s3a://") || outputUri.startsWith("s3://") || outputUri.startsWith("minio://")) {
            if (objectRoot == null) {
                throw new IllegalArgumentException("objectRoot is required for object-store URIs: " + outputUri);
            }
            URI uri = URI.create(outputUri.replace("s3a://", "s3://").replace("minio://", "s3://"));
            String bucket = uri.getHost();
            String key = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
            return objectRoot.resolve(bucket).resolve(key).normalize();
        }
        if (outputUri.startsWith("file://")) {
            return Path.of(URI.create(outputUri)).normalize();
        }
        return Path.of(outputUri).normalize();
    }

    private EvidenceArtifact evidenceArtifact(String artifactId, String type, Path path, Map<String, Object> metadata) throws IOException {
        return new EvidenceArtifact(
                artifactId,
                type,
                path,
                sha256(Files.readAllBytes(path)),
                "minio-output-oracle-validator",
                "test-run",
                metadata
        );
    }

    private String sha256File(Path path) {
        try {
            return sha256(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to hash " + path, e);
        }
    }

    private String sha256(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SHA-256", e);
        }
    }

    public record ProbeRequest(
            String scenarioId,
            String generationRunId,
            Path oraclePath,
            String outputUri,
            Path objectRoot,
            Path evidenceRoot,
            Map<String, Object> expectedOverrides
    ) {
        public ProbeRequest {
            if (scenarioId == null || scenarioId.isBlank()) {
                throw new IllegalArgumentException("scenarioId is required");
            }
            if (oraclePath == null || evidenceRoot == null) {
                throw new IllegalArgumentException("oraclePath and evidenceRoot are required");
            }
        }
    }

    public record ValidationResult(
            String verdict,
            List<String> failureCodes,
            Path resolvedOutputPath,
            List<Path> materializedCsvFiles,
            EvidenceBundle evidenceBundle
    ) {
    }

    static final class CsvTable {
        private final List<String> headers;
        private final List<Map<String, String>> rows;

        CsvTable(List<String> headers, List<Map<String, String>> rows) {
            this.headers = List.copyOf(headers);
            this.rows = rows.stream()
                    .map(row -> Map.copyOf(new LinkedHashMap<>(row)))
                    .toList();
        }

        static CsvTable load(List<Path> csvFiles) throws IOException {
            if (csvFiles.isEmpty()) {
                throw new IllegalArgumentException("At least one CSV file is required");
            }
            List<String> headers = null;
            List<Map<String, String>> rows = new ArrayList<>();
            for (Path csvFile : csvFiles) {
                List<String> lines = Files.readAllLines(csvFile, StandardCharsets.UTF_8);
                if (lines.isEmpty()) {
                    continue;
                }
                List<String> currentHeaders = parseCsvLine(lines.get(0));
                if (headers == null) {
                    headers = currentHeaders;
                } else if (!headers.equals(currentHeaders)) {
                    throw new IllegalStateException("Mismatched CSV headers across materialized files: " + csvFile);
                }
                for (int i = 1; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.isBlank()) {
                        continue;
                    }
                    List<String> values = parseCsvLine(line);
                    if (values.size() != headers.size()) {
                        throw new IllegalStateException("Row " + i + " expected " + headers.size() + " columns but found " + values.size() + " in " + csvFile);
                    }
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int j = 0; j < headers.size(); j++) {
                        row.put(headers.get(j), values.get(j));
                    }
                    rows.add(row);
                }
            }
            if (headers == null) {
                throw new IllegalStateException("Materialized CSV files were empty");
            }
            return new CsvTable(headers, rows);
        }

        List<String> headers() {
            return headers;
        }

        List<Map<String, String>> rows() {
            return rows;
        }

        String toCanonicalCsv() {
            StringBuilder out = new StringBuilder();
            out.append(headers.stream().map(CsvTable::escapeCsvValue).collect(Collectors.joining(",")));
            for (Map<String, String> row : rows) {
                out.append('\n');
                out.append(headers.stream()
                        .map(header -> escapeCsvValue(row.getOrDefault(header, "")))
                        .collect(Collectors.joining(",")));
            }
            return out.toString();
        }

        private static List<String> parseCsvLine(String line) {
            List<String> values = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '"') {
                    if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = !inQuotes;
                    }
                } else if (ch == ',' && !inQuotes) {
                    values.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
            values.add(current.toString());
            return values;
        }

        private static String escapeCsvValue(String value) {
            if (value == null) {
                return "";
            }
            boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
            String escaped = value.replace("\"", "\"\"");
            return needsQuoting ? "\"" + escaped + "\"" : escaped;
        }
    }
}
