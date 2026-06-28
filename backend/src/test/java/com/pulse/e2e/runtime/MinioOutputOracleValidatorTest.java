package com.pulse.e2e.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.LoanMasterFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinioOutputOracleValidatorTest {

    private static final Path ORACLE_PATH = Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json");
    private static final Path FIXTURE_MANIFEST_PATH = Path.of("src/test/resources/e2e/fixtures/loan_master/fixture-manifest.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    void validatesCanonicalLoanMasterCsvThroughMinioStyleUri() throws Exception {
        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        Path objectRoot = tempDir.resolve("minio-root");
        Path materialized = objectRoot
                .resolve("pulse-dpc-tenant-home-lending-dev-files")
                .resolve("servicing/loan-source/loan-master/outgoing_extracts/loan_master.csv");
        Files.createDirectories(materialized.getParent());
        Files.copy(fixture.path(), materialized);

        MinioOutputOracleValidator validator = new MinioOutputOracleValidator(objectMapper);
        MinioOutputOracleValidator.ValidationResult result = validator.validate(
                new MinioOutputOracleValidator.ProbeRequest(
                        "loan-master-live-runtime",
                        "run-1",
                        ORACLE_PATH,
                        "s3a://pulse-dpc-tenant-home-lending-dev-files/servicing/loan-source/loan-master/outgoing_extracts/loan_master.csv",
                        objectRoot,
                        tempDir.resolve("evidence-pass"),
                        java.util.Map.of()
                )
        );

        assertEquals("PASS", result.verdict());
        assertTrue(result.failureCodes().isEmpty());
        assertEquals(materialized.normalize(), result.resolvedOutputPath());
        assertTrue(Files.exists(tempDir.resolve("evidence-pass/minio-output-probe.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence-pass/data-oracle-comparison.json")));
        assertEquals(
                List.of("DATA_ORACLE_COMPARISON", "MINIO_OUTPUT_PROBE"),
                result.evidenceBundle().artifacts().stream().map(a -> a.type()).sorted().toList()
        );
    }

    @Test
    void reportsMissingOutputWhenObjectPathDoesNotExist() throws Exception {
        Path objectRoot = tempDir.resolve("missing-root");
        MinioOutputOracleValidator validator = new MinioOutputOracleValidator(objectMapper);

        MinioOutputOracleValidator.ValidationResult result = validator.validate(
                new MinioOutputOracleValidator.ProbeRequest(
                        "loan-master-live-runtime",
                        "run-missing",
                        ORACLE_PATH,
                        "s3a://pulse-dpc-tenant-home-lending-dev-files/servicing/loan-source/loan-master/outgoing_extracts/missing.csv",
                        objectRoot,
                        tempDir.resolve("evidence-missing"),
                        java.util.Map.of()
                )
        );

        assertEquals("FAIL", result.verdict());
        assertTrue(result.failureCodes().contains("output_missing"));
    }

    @Test
    void reportsDeterministicMismatchCodesForFilteredDerivativeAgainstSourceOracle() throws Exception {
        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        List<String> lines = Files.readAllLines(fixture.path()).stream().toList();
        String header = lines.get(0);
        int loanStatusIndex = java.util.Arrays.asList(header.split(",", -1)).indexOf("loan_status");
        List<String> filtered = lines.stream()
                .skip(1)
                .filter(line -> line.split(",", -1)[loanStatusIndex].equals("Current"))
                .collect(Collectors.toList());

        Path objectRoot = tempDir.resolve("mismatch-root");
        Path materialized = objectRoot
                .resolve("pulse-dpc-tenant-home-lending-dev-files")
                .resolve("servicing/loan-source/loan-master/outgoing_extracts/loan_master_current.csv");
        Files.createDirectories(materialized.getParent());
        Files.writeString(materialized, header + System.lineSeparator() + String.join(System.lineSeparator(), filtered));

        MinioOutputOracleValidator validator = new MinioOutputOracleValidator(objectMapper);
        MinioOutputOracleValidator.ValidationResult result = validator.validate(
                new MinioOutputOracleValidator.ProbeRequest(
                        "loan-master-live-runtime",
                        "run-mismatch",
                        ORACLE_PATH,
                        "s3a://pulse-dpc-tenant-home-lending-dev-files/servicing/loan-source/loan-master/outgoing_extracts/loan_master_current.csv",
                        objectRoot,
                        tempDir.resolve("evidence-mismatch"),
                        java.util.Map.of()
                )
        );

        assertEquals("FAIL", result.verdict());
        assertTrue(result.failureCodes().contains("row_count_mismatch"));
        assertTrue(result.failureCodes().contains("canonical_csv_checksum_mismatch"));
        assertTrue(result.failureCodes().contains("aggregate_profile_mismatch"));
        assertTrue(result.failureCodes().contains("dq_counts_mismatch"));
        assertTrue(result.failureCodes().contains("partition_expectations_mismatch"));
    }

    @Test
    void validatesDerivativeCsvWhenFixtureManifestProvidesDerivativeOracleOverrides() throws Exception {
        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        List<String> lines = Files.readAllLines(fixture.path()).stream().toList();
        String header = lines.get(0);
        int loanStatusIndex = java.util.Arrays.asList(header.split(",", -1)).indexOf("loan_status");
        List<String> filtered = lines.stream()
                .skip(1)
                .filter(line -> line.split(",", -1)[loanStatusIndex].equals("Current"))
                .collect(Collectors.toList());

        Path objectRoot = tempDir.resolve("derivative-root");
        Path materialized = objectRoot
                .resolve("pulse-dpc-tenant-home-lending-dev-files")
                .resolve("servicing/loan-source/loan-master/outgoing_extracts/loan_master_current.csv");
        Files.createDirectories(materialized.getParent());
        Files.writeString(materialized, header + System.lineSeparator() + String.join(System.lineSeparator(), filtered));

        MinioOutputOracleValidator validator = new MinioOutputOracleValidator(objectMapper);
        MinioOutputOracleValidator.ValidationResult result = validator.validate(
                new MinioOutputOracleValidator.ProbeRequest(
                        "loan-master-current-derivative",
                        "run-derivative",
                        ORACLE_PATH,
                        "s3a://pulse-dpc-tenant-home-lending-dev-files/servicing/loan-source/loan-master/outgoing_extracts/loan_master_current.csv",
                        objectRoot,
                        tempDir.resolve("evidence-derivative"),
                        derivativeOverride("current_loans")
                )
        );

        assertEquals("PASS", result.verdict());
        assertTrue(result.failureCodes().isEmpty());
    }

    @Test
    void csvTableLoad_handlesQuotedJsonStringsContainingCommas() throws Exception {
        Path csv = tempDir.resolve("quoted.json.csv");
        Files.writeString(csv, """
                loan_id,loan_number,borrower
                LN-101,L-1001,"{""first_name"":""Ada"",""last_name"":""Lovelace"",""risk_band"":""A""}"
                """);

        MinioOutputOracleValidator.CsvTable table = MinioOutputOracleValidator.CsvTable.load(List.of(csv));

        assertEquals(List.of("loan_id", "loan_number", "borrower"), table.headers());
        assertEquals(1, table.rows().size());
        assertEquals("{\"first_name\":\"Ada\",\"last_name\":\"Lovelace\",\"risk_band\":\"A\"}",
                table.rows().getFirst().get("borrower"));
        assertTrue(table.toCanonicalCsv().contains("\"{\"\"first_name\"\":\"\"Ada\"\""));
    }

    private Map<String, Object> derivativeOverride(String derivativeId) throws Exception {
        Map<String, Object> manifest = objectMapper.readValue(FIXTURE_MANIFEST_PATH.toFile(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> derivatives = (List<Map<String, Object>>) manifest.get("derivatives");
        return derivatives.stream()
                .filter(derivative -> derivativeId.equals(derivative.get("derivative_id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing derivative " + derivativeId));
    }
}
