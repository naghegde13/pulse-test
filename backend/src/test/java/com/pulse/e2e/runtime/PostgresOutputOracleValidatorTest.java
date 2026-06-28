package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresOutputOracleValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir Path tempDir;

    @Test
    void validatesPostgresDestinationTableThroughJdbcProbeAndOracleComparison() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:postgres-probe;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            connection.createStatement().execute("""
                    CREATE TABLE destination_delinquent (
                        loan_id VARCHAR(32) NOT NULL,
                        loan_number VARCHAR(32) NOT NULL,
                        origination_date DATE NOT NULL,
                        current_upb DECIMAL(12,2) NOT NULL,
                        investor_name VARCHAR(64) NOT NULL
                    )
                    """);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO destination_delinquent (loan_id, loan_number, origination_date, current_upb, investor_name) VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, "LN-0001");
                insert.setString(2, "LN-1001");
                insert.setObject(3, java.sql.Date.valueOf("2024-01-15"));
                insert.setBigDecimal(4, new BigDecimal("4500.00"));
                insert.setString(5, "Fannie Mae");
                insert.executeUpdate();

                insert.setString(1, "LN-0002");
                insert.setString(2, "LN-1002");
                insert.setObject(3, java.sql.Date.valueOf("2024-01-16"));
                insert.setBigDecimal(4, new BigDecimal("4890.25"));
                insert.setString(5, "Freddie Mac");
                insert.executeUpdate();
            }
        }

        Path oraclePath = tempDir.resolve("oracle.json");
        Map<String, Object> oracle = Map.of(
                "row_count", 2,
                "column_count", 5,
                "checksums", Map.of(
                        "canonical_csv_sha256", sha256("""
                                loan_id,loan_number,origination_date,current_upb,investor_name
                                LN-0001,LN-1001,2024-01-15,4500,Fannie Mae
                                LN-0002,LN-1002,2024-01-16,4890.25,Freddie Mac""")),
                "schema", Map.of(
                        "signature", schemaSignature(List.of(
                                "loan_id",
                                "loan_number",
                                "origination_date",
                                "current_upb",
                                "investor_name"
                        ), Map.of(
                                "loan_id", "string",
                                "loan_number", "string",
                                "origination_date", "date",
                                "current_upb", "decimal",
                                "investor_name", "string"
                        )),
                        "column_order_sha256", sha256("loan_id|loan_number|origination_date|current_upb|investor_name")
                ),
                "required_field_nulls", Map.of(
                        "loan_id", 0,
                        "loan_number", 0,
                        "origination_date", 0,
                        "current_upb", 0,
                        "investor_name", 0
                ),
                "business_keys", List.of("loan_id", "loan_number")
        );
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(oraclePath.toFile(), oracle);

        PostgresOutputOracleValidator validator = new PostgresOutputOracleValidator(objectMapper);
        PostgresOutputOracleValidator.ValidationResult result = validator.validate(
                new PostgresOutputOracleValidator.ProbeRequest(
                        "postgres-destination-live-runtime",
                        "run-1",
                        jdbcUrl,
                        "sa",
                        "",
                        null,
                        "destination_delinquent",
                        oraclePath,
                        tempDir.resolve("evidence-pass"),
                        Map.of()
                )
        );

        assertEquals("PASS", result.verdict());
        assertTrue(result.failureCodes().isEmpty());
        assertTrue(Files.exists(result.exportedCsv()));
        assertTrue(Files.exists(tempDir.resolve("evidence-pass/minio-output-probe.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence-pass/data-oracle-comparison.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence-pass/postgres-output-probe.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence-pass/postgres-data-oracle-comparison.json")));
        assertEquals(
                List.of("DATA_ORACLE_COMPARISON", "MINIO_OUTPUT_PROBE", "POSTGRES_DATA_ORACLE_COMPARISON", "POSTGRES_OUTPUT_PROBE"),
                result.evidenceBundle().artifacts().stream().map(a -> a.type()).sorted().toList()
        );
    }

    private String schemaSignature(List<String> headers, Map<String, String> types) {
        String payload = headers.stream()
                .map(header -> header + ":" + types.get(header))
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        return sha256(payload);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SHA-256", e);
        }
    }
}
