package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceArtifact;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceBundle;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Test-harness validator for local Postgres destination output.
 *
 * <p>Probes a JDBC table, exports it to a canonical CSV snapshot, and then reuses the existing
 * loan_master oracle comparator to perform the actual data comparison.
 */
public class PostgresOutputOracleValidator {

    private final ObjectMapper objectMapper;
    private final MinioOutputOracleValidator csvValidator;

    public PostgresOutputOracleValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.csvValidator = new MinioOutputOracleValidator(objectMapper);
    }

    public ValidationResult validate(ProbeRequest request) throws IOException, SQLException {
        Files.createDirectories(request.evidenceRoot());

        ExportResult exportResult = exportTable(request);
        Path exportedCsv = request.evidenceRoot().resolve("postgres-output-export.csv");
        writeCanonicalCsv(exportedCsv, exportResult.headers(), exportResult.rows());

        Map<String, Object> probePayload = new LinkedHashMap<>();
        probePayload.put("scenarioId", request.scenarioId());
        probePayload.put("generationRunId", request.generationRunId());
        probePayload.put("probedAt", Instant.now().toString());
        probePayload.put("jdbcUrl", request.jdbcUrl());
        probePayload.put("schemaName", request.schemaName());
        probePayload.put("tableName", request.tableName());
        probePayload.put("orderedByColumns", exportResult.orderByColumns());
        probePayload.put("comparisonColumns", request.comparisonColumns());
        probePayload.put("columnNames", exportResult.headers());
        probePayload.put("rowCount", exportResult.rows().size());
        probePayload.put("exportedCsv", exportedCsv.toString());

        Path probePath = request.evidenceRoot().resolve("postgres-output-probe.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(probePath.toFile(), probePayload);

        Path delegatedEvidenceRoot = request.evidenceRoot().resolve("delegated-csv-comparison");
        MinioOutputOracleValidator.ValidationResult delegatedResult = csvValidator.validate(
                new MinioOutputOracleValidator.ProbeRequest(
                        request.scenarioId(),
                        request.generationRunId(),
                        request.oraclePath(),
                        exportedCsv.toUri().toString(),
                        null,
                        delegatedEvidenceRoot,
                        request.expectedOverrides()
                )
        );
        Path canonicalProbePath = request.evidenceRoot().resolve("minio-output-probe.json");
        Path canonicalComparisonPath = request.evidenceRoot().resolve("data-oracle-comparison.json");
        Files.copy(delegatedEvidenceRoot.resolve("minio-output-probe.json"),
                canonicalProbePath,
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(delegatedEvidenceRoot.resolve("data-oracle-comparison.json"),
                canonicalComparisonPath,
                StandardCopyOption.REPLACE_EXISTING);

        Map<String, Object> comparisonPayload = new LinkedHashMap<>();
        comparisonPayload.put("scenarioId", request.scenarioId());
        comparisonPayload.put("generationRunId", request.generationRunId());
        comparisonPayload.put("verdict", delegatedResult.verdict());
        comparisonPayload.put("failureCodes", delegatedResult.failureCodes());
        comparisonPayload.put("exportedCsv", exportedCsv.toString());
        comparisonPayload.put("delegatedEvidenceRoot", delegatedEvidenceRoot.toString());

        Path comparisonPath = request.evidenceRoot().resolve("postgres-data-oracle-comparison.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(comparisonPath.toFile(), comparisonPayload);

        List<EvidenceArtifact> artifacts = new ArrayList<>();
        artifacts.add(evidenceArtifact("minio-output-probe", "MINIO_OUTPUT_PROBE", canonicalProbePath, Map.of(
                "source", "postgres-jdbc-export",
                "exportedCsv", exportedCsv.toString())));
        artifacts.add(evidenceArtifact("data-oracle-comparison", "DATA_ORACLE_COMPARISON", canonicalComparisonPath, Map.of(
                "source", "postgres-jdbc-export",
                "verdict", delegatedResult.verdict(),
                "failureCount", delegatedResult.failureCodes().size())));
        artifacts.add(evidenceArtifact("postgres-output-probe", "POSTGRES_OUTPUT_PROBE", probePath, Map.of(
                "jdbcUrl", request.jdbcUrl(),
                "tableName", request.tableName())));
        artifacts.add(evidenceArtifact("postgres-data-oracle-comparison", "POSTGRES_DATA_ORACLE_COMPARISON", comparisonPath, Map.of(
                "verdict", delegatedResult.verdict(),
                "failureCount", delegatedResult.failureCodes().size())));

        EvidenceBundle bundle = new EvidenceBundle(
                request.scenarioId(),
                request.generationRunId(),
                request.evidenceRoot(),
                List.copyOf(artifacts),
                Map.of(
                        "verdict", delegatedResult.verdict(),
                        "failureCodes", List.copyOf(delegatedResult.failureCodes()),
                        "artifactCount", artifacts.size()
                )
        );
        return new ValidationResult(
                delegatedResult.verdict(),
                List.copyOf(delegatedResult.failureCodes()),
                exportedCsv,
                List.of(exportedCsv),
                bundle
        );
    }

    private ExportResult exportTable(ProbeRequest request) throws SQLException {
        List<String> orderByColumns = determineOrderColumns(request);
        String query = buildQuery(request, orderByColumns);

        try (Connection connection = DriverManager.getConnection(request.jdbcUrl(), request.username(), request.password());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            List<String> headers = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                headers.add(metaData.getColumnLabel(i));
            }

            List<Map<String, String>> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(headers.get(i - 1), formatValue(resultSet.getObject(i)));
                }
                rows.add(row);
            }
            return new ExportResult(List.copyOf(headers), List.copyOf(rows), List.copyOf(orderByColumns));
        }
    }

    private List<String> determineOrderColumns(ProbeRequest request) {
        List<String> orderByColumns = new ArrayList<>();
        Object businessKeys = request.expectedOverrides().get("business_keys");
        if (businessKeys instanceof List<?> list) {
            for (Object key : list) {
                if (key != null && !String.valueOf(key).isBlank()) {
                    orderByColumns.add(String.valueOf(key));
                }
            }
        }
        return orderByColumns;
    }

    private String buildQuery(ProbeRequest request, List<String> orderByColumns) {
        StringBuilder query = new StringBuilder("SELECT ");
        if (request.comparisonColumns().isEmpty()) {
            query.append("*");
        } else {
            query.append(request.comparisonColumns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")));
        }
        query.append(" FROM ");
        if (request.schemaName() != null && !request.schemaName().isBlank()) {
            query.append(quoteIdentifier(request.schemaName())).append('.');
        }
        query.append(quoteIdentifier(request.tableName()));
        if (!orderByColumns.isEmpty()) {
            query.append(" ORDER BY ")
                    .append(orderByColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")));
        }
        return query.toString();
    }

    private void writeCanonicalCsv(Path output, List<String> headers, List<Map<String, String>> rows) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", headers));
        for (Map<String, String> row : rows) {
            csv.append('\n');
            csv.append(headers.stream()
                    .map(header -> row.getOrDefault(header, ""))
                    .map(this::escapeCsvCell)
                    .collect(Collectors.joining(",")));
        }
        Files.writeString(output, csv.toString(), StandardCharsets.UTF_8);
    }

    private String escapeCsvCell(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return String.valueOf(value);
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private EvidenceArtifact evidenceArtifact(String artifactId, String type, Path path, Map<String, Object> metadata) throws IOException {
        return new EvidenceArtifact(
                artifactId,
                type,
                path,
                sha256(Files.readAllBytes(path)),
                "postgres-output-oracle-validator",
                "test-run",
                metadata
        );
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
            String jdbcUrl,
            String username,
            String password,
            String schemaName,
            String tableName,
            Path oraclePath,
            Path evidenceRoot,
            Map<String, Object> expectedOverrides,
            List<String> comparisonColumns
    ) {
        public ProbeRequest(
                String scenarioId,
                String generationRunId,
                String jdbcUrl,
                String username,
                String password,
                String schemaName,
                String tableName,
                Path oraclePath,
                Path evidenceRoot,
                Map<String, Object> expectedOverrides
        ) {
            this(
                    scenarioId,
                    generationRunId,
                    jdbcUrl,
                    username,
                    password,
                    schemaName,
                    tableName,
                    oraclePath,
                    evidenceRoot,
                    expectedOverrides,
                    List.of()
            );
        }

        public ProbeRequest {
            Objects.requireNonNull(scenarioId, "scenarioId");
            Objects.requireNonNull(generationRunId, "generationRunId");
            Objects.requireNonNull(jdbcUrl, "jdbcUrl");
            Objects.requireNonNull(tableName, "tableName");
            Objects.requireNonNull(oraclePath, "oraclePath");
            Objects.requireNonNull(evidenceRoot, "evidenceRoot");
            expectedOverrides = expectedOverrides == null ? Map.of() : Map.copyOf(expectedOverrides);
            comparisonColumns = comparisonColumns == null ? List.of() : List.copyOf(comparisonColumns);
        }
    }

    public record ValidationResult(
            String verdict,
            List<String> failureCodes,
            Path exportedCsv,
            List<Path> exportedArtifacts,
            EvidenceBundle evidenceBundle
    ) {
    }

    private record ExportResult(
            List<String> headers,
            List<Map<String, String>> rows,
            List<String> orderByColumns
    ) {
    }
}
