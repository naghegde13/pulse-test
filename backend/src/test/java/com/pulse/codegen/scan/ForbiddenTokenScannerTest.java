package com.pulse.codegen.scan;

import com.pulse.codegen.model.GeneratedArtifact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PKT-0027: Tests for the ForbiddenTokenScanner that validates generated
 * artifacts do not contain raw secrets, internal PULSE tokens, or
 * unresolved placeholders.
 */
class ForbiddenTokenScannerTest {

    // -----------------------------------------------------------------------
    //  Raw secret detection
    // -----------------------------------------------------------------------

    @Test
    void scan_detectsPemPrivateKey() {
        GeneratedArtifact artifact = buildArtifact("PYSPARK_JOB",
                "sftp_key = '-----BEGIN RSA PRIVATE KEY-----\\nMIIE...'");
        Map<String, List<String>> violations = ForbiddenTokenScanner.scan(List.of(artifact));
        assertFalse(violations.isEmpty(), "Should detect PEM private key");
        assertTrue(violations.values().stream().flatMap(List::stream)
                .anyMatch(v -> v.contains("PEM private key")));
    }

    @Test
    void scan_detectsGcpServiceAccountJson() {
        GeneratedArtifact artifact = buildArtifact("CONFIG",
                "{ \"type\" : \"service_account\", \"project_id\": \"my-project\" }");
        Map<String, List<String>> violations = ForbiddenTokenScanner.scan(List.of(artifact));
        assertFalse(violations.isEmpty(), "Should detect GCP service account JSON");
        assertTrue(violations.values().stream().flatMap(List::stream)
                .anyMatch(v -> v.contains("GCP service account")));
    }

    @Test
    void scan_detectsGitHubPat() {
        GeneratedArtifact artifact = buildArtifact("AIRFLOW_DAG",
                "token = 'ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij'");
        Map<String, List<String>> violations = ForbiddenTokenScanner.scan(List.of(artifact));
        assertFalse(violations.isEmpty(), "Should detect GitHub PAT");
        assertTrue(violations.values().stream().flatMap(List::stream)
                .anyMatch(v -> v.contains("GitHub personal access token")));
    }

    // -----------------------------------------------------------------------
    //  Internal token detection
    // -----------------------------------------------------------------------

    @Test
    void scan_detectsInternalPulseTokens() {
        GeneratedArtifact artifact = buildArtifact("AIRFLOW_DAG",
                "callback_url = 'PULSE_AIRFLOW_CALLBACK_URL'");
        Map<String, List<String>> violations = ForbiddenTokenScanner.scan(List.of(artifact));
        assertFalse(violations.isEmpty());
        assertTrue(violations.values().stream().flatMap(List::stream)
                .anyMatch(v -> v.contains("PULSE_AIRFLOW_CALLBACK_URL")));
    }

    @Test
    void scan_detectsLocalhostUrl() {
        GeneratedArtifact artifact = buildArtifact("PYSPARK_JOB",
                "api_url = 'http://localhost:8080/api'");
        Map<String, List<String>> violations = ForbiddenTokenScanner.scan(List.of(artifact));
        assertFalse(violations.isEmpty());
    }

    @Test
    void scan_detectsBrokerInternalToken() {
        GeneratedArtifact artifact = buildArtifact("BROKER_OPERATOR",
                "token = os.environ['PULSE_BROKER_INTERNAL_TOKEN']");
        Map<String, List<String>> violations = ForbiddenTokenScanner.scan(List.of(artifact));
        assertFalse(violations.isEmpty());
    }

    // -----------------------------------------------------------------------
    //  Clean artifacts pass scan
    // -----------------------------------------------------------------------

    @Test
    void scan_cleanArtifactReturnsNoViolations() {
        GeneratedArtifact artifact = buildArtifact("PYSPARK_JOB",
                "jdbc_password = os.environ['POSTGRES_PASSWORD']\n"
                        + "df = spark.read.format('jdbc').option('url', jdbc_url).load()");
        Map<String, List<String>> violations = ForbiddenTokenScanner.scan(List.of(artifact));
        assertTrue(violations.isEmpty(), "Clean artifact should have no violations");
    }

    @Test
    void scan_secretReferencesAreNotFlagged() {
        GeneratedArtifact artifact = buildArtifact("PYSPARK_JOB",
                "# password <- gcp-sm://projects/pulse-dev/secrets/pg-password/versions/active [ENV]\n"
                        + "jdbc_password = os.environ['PG_PASSWORD']");
        Map<String, List<String>> violations = ForbiddenTokenScanner.scan(List.of(artifact));
        assertTrue(violations.isEmpty(),
                "gcp-sm:// references in comments should not trigger violations");
    }

    @Test
    void scan_skipsNonScannableFileTypes() {
        GeneratedArtifact compileArtifact = buildArtifact("COMPILE_PLAN",
                "PULSE_BROKER_INTERNAL_TOKEN");  // would be a violation if scanned
        Map<String, List<String>> violations = ForbiddenTokenScanner.scan(List.of(compileArtifact));
        assertTrue(violations.isEmpty(), "COMPILE_PLAN should not be scanned");
    }

    // -----------------------------------------------------------------------
    //  Placeholder detection
    // -----------------------------------------------------------------------

    @Test
    void scanForPlaceholders_detectsJdbcPlaceholders() {
        String content = "jdbc_url = '${JDBC_URL}'\njdbc_password = '${JDBC_PASSWORD}'";
        List<String> placeholders = ForbiddenTokenScanner.scanForPlaceholders(content);
        assertTrue(placeholders.contains("${JDBC_URL}"));
        assertTrue(placeholders.contains("${JDBC_PASSWORD}"));
    }

    @Test
    void scanForPlaceholders_detectsSourceTablePlaceholder() {
        String content = ".option('dbtable', '${SOURCE_TABLE}')";
        List<String> placeholders = ForbiddenTokenScanner.scanForPlaceholders(content);
        assertTrue(placeholders.contains("${SOURCE_TABLE}"));
    }

    @Test
    void scanForPlaceholders_ignoresAirflowTemplateVariables() {
        String content = "business_date = '{{ ds }}'\nprocessing_ts = '{{ ts }}'";
        List<String> placeholders = ForbiddenTokenScanner.scanForPlaceholders(content);
        assertTrue(placeholders.isEmpty(), "Airflow template variables should not be flagged");
    }

    @Test
    void scanForPlaceholders_cleanResolvedCodeHasNoPlaceholders() {
        String content = "jdbc_url = 'jdbc:postgresql://pg-host:5432/mydb'\n"
                + "jdbc_password = os.environ['PG_PASSWORD']";
        List<String> placeholders = ForbiddenTokenScanner.scanForPlaceholders(content);
        assertTrue(placeholders.isEmpty(), "Fully resolved code should have no placeholders");
    }

    // -----------------------------------------------------------------------
    //  isSecretShapedKey
    // -----------------------------------------------------------------------

    @Test
    void isSecretShapedKey_identifiesCommonSecretKeys() {
        assertTrue(ForbiddenTokenScanner.isSecretShapedKey("password"));
        assertTrue(ForbiddenTokenScanner.isSecretShapedKey("jdbc_password"));
        assertTrue(ForbiddenTokenScanner.isSecretShapedKey("api_key"));
        assertTrue(ForbiddenTokenScanner.isSecretShapedKey("private_key"));
        assertTrue(ForbiddenTokenScanner.isSecretShapedKey("client_secret"));
        assertTrue(ForbiddenTokenScanner.isSecretShapedKey("sasl_password"));
        assertTrue(ForbiddenTokenScanner.isSecretShapedKey("credentials_json"));
        assertTrue(ForbiddenTokenScanner.isSecretShapedKey("service_account"));
    }

    @Test
    void isSecretShapedKey_doesNotFlagNonSecretKeys() {
        assertFalse(ForbiddenTokenScanner.isSecretShapedKey("host"));
        assertFalse(ForbiddenTokenScanner.isSecretShapedKey("port"));
        assertFalse(ForbiddenTokenScanner.isSecretShapedKey("database"));
        assertFalse(ForbiddenTokenScanner.isSecretShapedKey("username"));
        assertFalse(ForbiddenTokenScanner.isSecretShapedKey("table_name"));
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private GeneratedArtifact buildArtifact(String fileType, String content) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setFilePath("test/" + fileType.toLowerCase() + ".py");
        artifact.setFileType(fileType);
        artifact.setContent(content);
        return artifact;
    }
}
