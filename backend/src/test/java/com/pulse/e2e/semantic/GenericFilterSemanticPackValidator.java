package com.pulse.e2e.semantic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class GenericFilterSemanticPackValidator {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final Path FIXTURE_ROOT = Path.of("src/test/resources/e2e/fixtures/blueprint_semantic/GenericFilter");
    private static final Path ORACLE_ROOT = Path.of("src/test/resources/e2e/oracle/blueprint_semantic/GenericFilter");
    private static final String EXPECTED_SQL = "employment_status = 'ACTIVE' AND worker_type = 'FULL_TIME' AND hire_date <= DATE '2024-01-15' AND (termination_date IS NULL OR termination_date > DATE '2024-01-15')";

    private final ObjectMapper objectMapper;
    private final ObjectMapper plainObjectMapper = new ObjectMapper();

    public GenericFilterSemanticPackValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationReport validate() throws IOException {
        List<String> failures = new ArrayList<>();

        Map<String, Object> manifest = readJson(FIXTURE_ROOT.resolve("fixture-manifest.json"));
        Map<String, Object> oracle = readJson(ORACLE_ROOT.resolve("oracle.json"));
        Map<String, Object> comparatorConfig = readJson(ORACLE_ROOT.resolve("comparator-config.json"));
        Map<String, Object> filterConfig = readJson(FIXTURE_ROOT.resolve("input/expectations/filter-config.json"));
        Map<String, Object> invalidConfig = readJson(FIXTURE_ROOT.resolve("input/expectations/invalid-filter-params.json"));
        Map<String, Object> expectedOutputJson = readJson(FIXTURE_ROOT.resolve("expected/expected-output.json"));
        Map<String, Object> expectedMetrics = readJson(FIXTURE_ROOT.resolve("expected/expected-metrics.json"));

        compareValue("blueprintKey", "GenericFilter", manifest.get("blueprintKey"), failures);
        compareValue("oracle blueprintKey", "GenericFilter", oracle.get("blueprintKey"), failures);
        compareValue("semanticPackVersion", "1.0.0", manifest.get("semanticPackVersion"), failures);
        compareValue("oracle semanticPackVersion", "1.0.0", oracle.get("semanticPackVersion"), failures);
        Map<String, Object> comparator = mapValue(manifest.get("comparator"));
        compareValue("comparator ownerClass", getClass().getName(), comparator.get("ownerClass"), failures);
        compareValue("comparator configPath", "comparator-config.json", comparator.get("configPath"), failures);
        compareValue("comparator type", "row_exact", comparatorConfig.get("type"), failures);
        compareValue("filter raw_sql", EXPECTED_SQL, filterConfig.get("raw_sql"), failures);
        compareValue("negative expectedFailureCode", "INVALID_FILTER_OPERATOR", invalidConfig.get("expectedFailureCode"), failures);

        verifyManifestArtifacts(manifest, failures);
        verifyOracleChecksums(oracle, failures);

        List<Map<String, String>> actualRows = readCsv(FIXTURE_ROOT.resolve("input/positive/employees.csv")).stream()
                .filter(row -> isEligible(row, filterConfig))
                .sorted(Comparator.comparing(row -> row.get("employee_id")))
                .toList();
        List<Map<String, String>> expectedRows = readCsv(FIXTURE_ROOT.resolve("expected/expected-output.csv"));
        if (!actualRows.equals(expectedRows)) {
            failures.add("expected output csv rows do not match derived eligible rows");
        }

        List<String> selectedIds = actualRows.stream().map(row -> row.get("employee_id")).toList();
        List<String> excludedIds = readCsv(FIXTURE_ROOT.resolve("input/positive/employees.csv")).stream()
                .map(row -> row.get("employee_id"))
                .filter(id -> !selectedIds.contains(id))
                .toList();

        compareList("expected-output.json selectedEmployeeIds", selectedIds, stringList(expectedOutputJson.get("selectedEmployeeIds")), failures);
        compareList("expected-output.json excludedEmployeeIds", excludedIds, stringList(expectedOutputJson.get("excludedEmployeeIds")), failures);
        compareList("oracle expectedIncludedEmployeeIds", selectedIds, stringList(oracle.get("expectedIncludedEmployeeIds")), failures);
        compareList("oracle expectedExcludedEmployeeIds", excludedIds, stringList(oracle.get("expectedExcludedEmployeeIds")), failures);

        compareValue("eligibleRowCount", actualRows.size(), ((Number) expectedMetrics.get("eligibleRowCount")).intValue(), failures);
        compareValue("boundaryHireDateIncluded", "E105", expectedMetrics.get("boundaryHireDateIncluded"), failures);
        compareValue("nullStatusExcluded", "E104", expectedMetrics.get("nullStatusExcluded"), failures);
        compareList("metrics selectedEmployeeIds", selectedIds, stringList(expectedMetrics.get("selectedEmployeeIds")), failures);
        compareList("metrics excludedEmployeeIds", excludedIds, stringList(expectedMetrics.get("excludedEmployeeIds")), failures);

        List<Map<String, Object>> conditions = listOfMaps(invalidConfig.get("conditions"));
        if (conditions == null || conditions.isEmpty()) {
            failures.add("invalid filter config must include an unsupported condition");
        } else {
            compareValue("negative operator", "unsupported", conditions.getFirst().get("operator"), failures);
        }

        return new ValidationReport(List.copyOf(failures), selectedIds, excludedIds);
    }

    private void verifyManifestArtifacts(Map<String, Object> manifest, List<String> failures) throws IOException {
        List<Map<String, Object>> inputFiles = listOfMaps(manifest.get("inputFiles"));
        for (Map<String, Object> inputFile : inputFiles) {
            verifyRelativeArtifact(FIXTURE_ROOT, inputFile, failures);
        }

        List<Map<String, Object>> expectedOutputs = listOfMaps(manifest.get("expectedOutputs"));
        for (Map<String, Object> expectedOutput : expectedOutputs) {
            verifyRelativeArtifact(FIXTURE_ROOT, expectedOutput, failures);
        }

        List<Map<String, Object>> expectedMetrics = listOfMaps(manifest.get("expectedMetrics"));
        for (Map<String, Object> expectedMetric : expectedMetrics) {
            verifyRelativeArtifact(FIXTURE_ROOT, expectedMetric, failures);
        }
    }

    private void verifyOracleChecksums(Map<String, Object> oracle, List<String> failures) throws IOException {
        Map<String, Object> checksums = mapValue(oracle.get("checksums"));
        verifyChecksum("oracle input checksum", FIXTURE_ROOT.resolve(String.valueOf(oracle.get("inputPath"))), String.valueOf(checksums.get("inputSha256")), failures);
        verifyChecksum("oracle filter config checksum", FIXTURE_ROOT.resolve(String.valueOf(oracle.get("filterConfigPath"))), String.valueOf(checksums.get("filterConfigSha256")), failures);
        verifyChecksum("oracle expected output checksum", FIXTURE_ROOT.resolve(String.valueOf(oracle.get("expectedOutputPath"))), String.valueOf(checksums.get("expectedOutputSha256")), failures);
        verifyChecksum("oracle expected metrics checksum", FIXTURE_ROOT.resolve(String.valueOf(oracle.get("expectedMetricsPath"))), String.valueOf(checksums.get("expectedMetricsSha256")), failures);
        verifyChecksum("oracle comparator config checksum", ORACLE_ROOT.resolve("comparator-config.json"), String.valueOf(checksums.get("comparatorConfigSha256")), failures);
    }

    private void verifyRelativeArtifact(Path root, Map<String, Object> artifact, List<String> failures) throws IOException {
        Path path = root.resolve(String.valueOf(artifact.get("path")));
        if (!Files.exists(path)) {
            failures.add("missing artifact: " + path);
            return;
        }
        if (artifact.containsKey("sha256")) {
            verifyChecksum(path.toString(), path, String.valueOf(artifact.get("sha256")), failures);
        }
    }

    private boolean isEligible(Map<String, String> row, Map<String, Object> filterConfig) {
        Map<String, Object> semantics = mapValue(filterConfig.get("semantics"));
        LocalDate cutoff = LocalDate.parse(String.valueOf(semantics.get("hireDateOnOrBefore")));
        String status = row.get("employment_status");
        String workerType = row.get("worker_type");
        LocalDate hireDate = LocalDate.parse(row.get("hire_date"));
        String terminationDateRaw = row.get("termination_date");
        LocalDate terminationDate = terminationDateRaw == null || terminationDateRaw.isBlank() ? null : LocalDate.parse(terminationDateRaw);
        return Objects.equals(status, semantics.get("employmentStatusEquals"))
                && Objects.equals(workerType, semantics.get("workerTypeEquals"))
                && !hireDate.isAfter(cutoff)
                && (terminationDate == null || terminationDate.isAfter(LocalDate.parse(String.valueOf(semantics.get("terminationAfter")))));
    }

    private Map<String, Object> readJson(Path path) throws IOException {
        return plainObjectMapper.readValue(path.toFile(), MAP_TYPE);
    }

    private List<Map<String, String>> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
        List<String> headers = List.of(lines.getFirst().split(",", -1));
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] values = line.split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                row.put(headers.get(i), i < values.length ? values[i] : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private void verifyChecksum(String label, Path path, String expectedSha, List<String> failures) throws IOException {
        String actualSha = sha256(path);
        if (!Objects.equals(expectedSha, actualSha)) {
            failures.add(label + " checksum mismatch");
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("Unable to hash " + path, e);
        }
    }

    private void compareValue(String label, Object expected, Object actual, List<String> failures) {
        if (!Objects.equals(expected, actual)) {
            failures.add(label + " mismatch: expected=" + expected + " actual=" + actual);
        }
    }

    private void compareList(String label, List<String> expected, List<String> actual, List<String> failures) {
        if (!expected.equals(actual)) {
            failures.add(label + " mismatch: expected=" + expected + " actual=" + actual);
        }
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        return objectMapper.convertValue(value, STRING_LIST_TYPE);
    }

    private Map<String, Object> mapValue(Object value) {
        if (value == null) {
            return Map.of();
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (value == null) {
            return List.of();
        }
        return objectMapper.convertValue(value, LIST_OF_MAPS_TYPE);
    }

    public record ValidationReport(List<String> failures, List<String> selectedEmployeeIds, List<String> excludedEmployeeIds) {
    }
}
