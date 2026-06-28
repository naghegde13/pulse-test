package com.pulse.e2e.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoanMasterFixtureOracleSupportTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final Path REPO_ROOT = LoanMasterFixtureOracleSupport.repoRoot();
    private static final Path BACKEND_ROOT = REPO_ROOT.resolve("backend");
    private static final Path SOURCE_CSV = LoanMasterFixtureOracleSupport.canonicalCsvPath();
    private static final Path FIXTURE_MANIFEST = BACKEND_ROOT.resolve("src/test/resources/e2e/fixtures/loan_master/fixture-manifest.json");
    private static final Path DATA_ORACLE = BACKEND_ROOT.resolve("src/test/resources/e2e/oracle/loan_master/data-oracle.json");

    @Test
    void fixtureManifestMatchesCommittedResource() throws Exception {
        Map<String, Object> generated = LoanMasterFixtureOracleSupport.buildFixtureManifest(SOURCE_CSV);
        maybeRewrite(FIXTURE_MANIFEST, generated);

        JsonNode expected = OBJECT_MAPPER.readTree(Files.readString(FIXTURE_MANIFEST));
        JsonNode actual = OBJECT_MAPPER.valueToTree(generated);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void dataOracleMatchesCommittedResource() throws Exception {
        Map<String, Object> generated = LoanMasterFixtureOracleSupport.buildDataOracle(SOURCE_CSV);
        maybeRewrite(DATA_ORACLE, generated);

        JsonNode expected = OBJECT_MAPPER.readTree(Files.readString(DATA_ORACLE));
        JsonNode actual = OBJECT_MAPPER.valueToTree(generated);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void canonicalLoanMasterOracleCapturesExpectedCoreSignals() throws Exception {
        Map<String, Object> oracle = LoanMasterFixtureOracleSupport.buildDataOracle(SOURCE_CSV);

        assertThat(oracle.get("row_count")).isEqualTo(500);
        assertThat(oracle.get("column_count")).isEqualTo(78);

        @SuppressWarnings("unchecked")
        Map<String, Object> dqCounts = (Map<String, Object>) oracle.get("dq_counts");
        assertThat(dqCounts.get("delinquent_row_count")).isEqualTo(210);
        assertThat(dqCounts.get("masked_ssn_pattern_failures")).isEqualTo(0);
        assertThat(dqCounts.get("raw_ssn_leak_count")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> partitionExpectations = (Map<String, Object>) oracle.get("partition_expectations");
        @SuppressWarnings("unchecked")
        Map<String, Integer> investorCounts = (Map<String, Integer>) partitionExpectations.get("investor_name");
        assertThat(investorCounts).containsEntry("Portfolio", 110);
        assertThat(investorCounts).containsEntry("Fannie Mae", 106);
    }

    @Test
    void derivativeManifestCarriesSelfContainedOracleSignals() throws Exception {
        Map<String, Object> manifest = LoanMasterFixtureOracleSupport.buildFixtureManifest(SOURCE_CSV);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> derivatives = (List<Map<String, Object>>) manifest.get("derivatives");
        Map<String, Object> currentLoans = derivatives.stream()
                .filter(derivative -> "current_loans".equals(derivative.get("derivative_id")))
                .findFirst()
                .orElseThrow();

        assertThat(currentLoans).containsKeys(
                "schema",
                "business_keys",
                "aggregates",
                "dq_counts",
                "partition_expectations",
                "masked_pii_assertions"
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> dqCounts = (Map<String, Object>) currentLoans.get("dq_counts");
        assertThat(dqCounts.get("delinquent_row_count")).isEqualTo(0);
    }

    private static void maybeRewrite(Path target, Map<String, Object> generated) throws IOException {
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("PULSE_REWRITE_LOAN_MASTER_ORACLE", "false"))) {
            return;
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, OBJECT_MAPPER.writeValueAsString(generated) + System.lineSeparator());
    }
}
