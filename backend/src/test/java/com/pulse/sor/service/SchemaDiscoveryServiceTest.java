package com.pulse.sor.service;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.CredentialStatus;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * PKT-0026: Schema Discovery Service tests.
 * Covers table discovery, query discovery, sample upload, credential gate,
 * PII classification, and provenance metadata.
 */
@ExtendWith(MockitoExtension.class)
class SchemaDiscoveryServiceTest {

    @Mock private ConnectorInstanceRepository ciRepo;
    @Mock private CredentialProfileRepository credRepo;
    @Mock private SystemOfRecordRepository sorRepo;

    private PiiClassificationService piiClassifier;
    private SchemaDiscoveryService service;

    private static final String CI_ID = "ci-jdbc-001";
    private static final String SOR_ID = "sor-001";

    @BeforeEach
    void setUp() {
        piiClassifier = new PiiClassificationService();
        service = new SchemaDiscoveryService(ciRepo, credRepo, sorRepo, piiClassifier);
    }

    private void mockValidCredentials() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));

        CredentialProfile cred = new CredentialProfile();
        cred.setStatus(CredentialStatus.VALID);
        when(credRepo.findByConnectorInstanceIdAndEnvironment(CI_ID, "dev"))
                .thenReturn(Optional.of(cred));
    }

    // --- Table Discovery ---

    @Test
    void discoverFromTable_loanMaster_returnsColumnsWithPiiClassification() {
        mockValidCredentials();

        var result = service.discoverFromTable(CI_ID, "Loan_Master", "dev");

        assertEquals("TABLE_DISCOVERY", result.discoveryMethod());
        assertEquals("PII", result.classification());
        assertFalse(result.fields().isEmpty());
        assertTrue(result.fields().size() >= 20, "Loan_Master should have at least 20 columns");

        // Verify PII fields are flagged
        var ssnField = result.fields().stream()
                .filter(f -> "ssn".equals(f.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals(true, ssnField.get("pii"));

        // Verify non-PII fields
        var loanIdField = result.fields().stream()
                .filter(f -> "loan_id".equals(f.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals(false, loanIdField.get("pii"));

        // Verify provenance metadata
        assertNotNull(result.discoveryProof());
        assertEquals("TABLE_DISCOVERY", result.discoveryProof().get("method"));
        assertEquals("Loan_Master", result.discoveryProof().get("tableName"));
        assertEquals(CI_ID, result.discoveryProof().get("connectorInstanceId"));
    }

    @Test
    void discoverFromTable_caseInsensitiveTableName() {
        mockValidCredentials();

        var result = service.discoverFromTable(CI_ID, "loan_master", "dev");
        assertFalse(result.fields().isEmpty());
    }

    @Test
    void discoverFromTable_unknownTable_returnsEmptyFields() {
        mockValidCredentials();

        var result = service.discoverFromTable(CI_ID, "nonexistent_table", "dev");
        assertTrue(result.fields().isEmpty());
        assertEquals("INTERNAL", result.classification());
    }

    @Test
    void discoverFromTable_nullTableName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.discoverFromTable(CI_ID, null, "dev"));
    }

    @Test
    void discoverFromTable_blankTableName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.discoverFromTable(CI_ID, "  ", "dev"));
    }

    // --- Credential Gate ---

    @Test
    void discoverFromTable_noCredentials_blocked() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));
        when(credRepo.findByConnectorInstanceIdAndEnvironment(CI_ID, "dev"))
                .thenReturn(Optional.empty());

        var ex = assertThrows(IllegalStateException.class,
                () -> service.discoverFromTable(CI_ID, "Loan_Master", "dev"));
        assertTrue(ex.getMessage().contains("No credential profile found"));
        // Verify secret-safe: no password/secret references in message
        assertFalse(ex.getMessage().contains("password"));
        assertFalse(ex.getMessage().contains("secret"));
    }

    @Test
    void discoverFromTable_untestedCredentials_blocked() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));

        CredentialProfile cred = new CredentialProfile();
        cred.setStatus(CredentialStatus.UNTESTED);
        when(credRepo.findByConnectorInstanceIdAndEnvironment(CI_ID, "dev"))
                .thenReturn(Optional.of(cred));

        var ex = assertThrows(IllegalStateException.class,
                () -> service.discoverFromTable(CI_ID, "Loan_Master", "dev"));
        assertTrue(ex.getMessage().contains("not been validated"));
    }

    @Test
    void discoverFromTable_invalidCredentials_blocked() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));

        CredentialProfile cred = new CredentialProfile();
        cred.setStatus(CredentialStatus.INVALID);
        when(credRepo.findByConnectorInstanceIdAndEnvironment(CI_ID, "dev"))
                .thenReturn(Optional.of(cred));

        var ex = assertThrows(IllegalStateException.class,
                () -> service.discoverFromTable(CI_ID, "Loan_Master", "dev"));
        assertTrue(ex.getMessage().contains("INVALID"));
    }

    @Test
    void discoverFromTable_blockedCredentials_blocked() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));

        CredentialProfile cred = new CredentialProfile();
        cred.setStatus(CredentialStatus.BLOCKED);
        when(credRepo.findByConnectorInstanceIdAndEnvironment(CI_ID, "dev"))
                .thenReturn(Optional.of(cred));

        var ex = assertThrows(IllegalStateException.class,
                () -> service.discoverFromTable(CI_ID, "Loan_Master", "dev"));
        assertTrue(ex.getMessage().contains("BLOCKED"));
    }

    @Test
    void discoverFromTable_skippedCredentials_blocked() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));

        CredentialProfile cred = new CredentialProfile();
        cred.setStatus(CredentialStatus.SKIPPED);
        when(credRepo.findByConnectorInstanceIdAndEnvironment(CI_ID, "dev"))
                .thenReturn(Optional.of(cred));

        var ex = assertThrows(IllegalStateException.class,
                () -> service.discoverFromTable(CI_ID, "Loan_Master", "dev"));
        assertTrue(ex.getMessage().contains("SKIPPED"));
    }

    @Test
    void discoverFromTable_connectorNotFound_throws() {
        when(ciRepo.findById("bad-id")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.discoverFromTable("bad-id", "Loan_Master", "dev"));
    }

    // --- Query Discovery ---

    @Test
    void discoverFromQuery_selectFromLoanMaster_returnsSchema() {
        mockValidCredentials();

        var result = service.discoverFromQuery(CI_ID,
                "SELECT * FROM Loan_Master WHERE as_of_date >= '2024-01-01'", "dev");

        assertEquals("QUERY_DISCOVERY", result.discoveryMethod());
        assertFalse(result.fields().isEmpty());

        // Verify proof includes query hash but NOT full query (secret-safe)
        assertNotNull(result.discoveryProof().get("queryHash"));
        assertNull(result.discoveryProof().get("queryPreview"),
                "queryPreview must not appear in proof to avoid leaking SQL with potential secrets");
    }

    @Test
    void discoverFromQuery_nullQuery_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.discoverFromQuery(CI_ID, null, "dev"));
    }

    @Test
    void discoverFromQuery_requiresCredentials() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));
        when(credRepo.findByConnectorInstanceIdAndEnvironment(CI_ID, "dev"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.discoverFromQuery(CI_ID, "SELECT * FROM Loan_Master", "dev"));
    }

    // --- Sample Upload ---

    @Test
    void discoverFromSample_csv_infersSchema() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));

        String csv = "loan_id,borrower_name,ssn,loan_amount,origination_date\n" +
                     "L001,John Doe,123-45-6789,250000.00,2024-01-15";

        var result = service.discoverFromSample(CI_ID, csv, "CSV");

        assertEquals("SAMPLE_UPLOAD", result.discoveryMethod());
        assertEquals(5, result.fields().size());
        assertEquals("PII", result.classification()); // ssn and borrower_name

        // Verify field types inferred from sample values
        var loanAmount = result.fields().stream()
                .filter(f -> "loan_amount".equals(f.get("name")))
                .findFirst().orElseThrow();
        assertEquals("DECIMAL", loanAmount.get("type"));

        var origDate = result.fields().stream()
                .filter(f -> "origination_date".equals(f.get("name")))
                .findFirst().orElseThrow();
        assertEquals("DATE", origDate.get("type"));

        // Verify proof
        assertNotNull(result.discoveryProof().get("sampleHash"));
    }

    @Test
    void discoverFromSample_json_infersSchema() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));

        String json = "{\"loan_id\": \"L001\", \"borrower_name\": \"John Doe\", " +
                      "\"credit_score\": 750, \"balance\": 125000.50, \"active\": true}";

        var result = service.discoverFromSample(CI_ID, json, "JSON");

        assertEquals("SAMPLE_UPLOAD", result.discoveryMethod());
        assertEquals(5, result.fields().size());

        var creditScore = result.fields().stream()
                .filter(f -> "credit_score".equals(f.get("name")))
                .findFirst().orElseThrow();
        assertEquals("INTEGER", creditScore.get("type"));
    }

    @Test
    void discoverFromSample_doesNotRequireCredentials() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));
        // No credential setup — should still work

        String csv = "id,value\n1,test";
        var result = service.discoverFromSample(CI_ID, csv, "CSV");
        assertEquals(2, result.fields().size());
    }

    @Test
    void discoverFromSample_unsupportedFormat_throws() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));

        assertThrows(IllegalArgumentException.class,
                () -> service.discoverFromSample(CI_ID, "data", "XML"));
    }

    @Test
    void discoverFromSample_nullData_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.discoverFromSample(CI_ID, null, "CSV"));
    }

    // --- Schema provenance ---

    @Test
    void discoveryProof_neverContainsSecrets() {
        mockValidCredentials();

        var result = service.discoverFromTable(CI_ID, "Loan_Master", "dev");
        String proofStr = result.discoveryProof().toString();

        assertFalse(proofStr.contains("password"));
        assertFalse(proofStr.contains("secret"));
        assertFalse(proofStr.contains("vault://"));
        assertFalse(proofStr.contains("gcp-sm://"));
    }

    // --- SHA-256 hash ---

    @Test
    void sha256_deterministic() {
        String hash1 = SchemaDiscoveryService.sha256("test input");
        String hash2 = SchemaDiscoveryService.sha256("test input");
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length()); // SHA-256 = 64 hex chars
    }

    @Test
    void sha256_differentInputs_differentHashes() {
        assertNotEquals(
                SchemaDiscoveryService.sha256("SELECT * FROM a"),
                SchemaDiscoveryService.sha256("SELECT * FROM b"));
    }

    // --- Default environment ---

    @Test
    void discoverFromTable_nullEnv_defaultsToDev() {
        mockValidCredentials();

        var result = service.discoverFromTable(CI_ID, "Loan_Master", null);
        assertEquals("dev", result.discoveryProof().get("environment"));
    }

    // --- LoanMaster field validation ---

    @Test
    void loanMasterSchema_hasAsOfDateColumn() {
        mockValidCredentials();

        var result = service.discoverFromTable(CI_ID, "Loan_Master", "dev");
        var asOfDate = result.fields().stream()
                .filter(f -> "as_of_date".equals(f.get("name")))
                .findFirst();
        assertTrue(asOfDate.isPresent(), "Loan_Master must have as_of_date column");
        assertEquals("DATE", asOfDate.get().get("type"));
    }

    @Test
    void loanMasterSchema_piiFieldsIdentified() {
        mockValidCredentials();

        var result = service.discoverFromTable(CI_ID, "Loan_Master", "dev");
        var piiFields = result.fields().stream()
                .filter(f -> Boolean.TRUE.equals(f.get("pii")))
                .toList();

        // ssn, borrower_name, email, phone, property_address should be PII
        assertTrue(piiFields.size() >= 5,
                "Expected at least 5 PII fields, got " + piiFields.size());
    }

    @Test
    void loanMasterSchema_confidentialFieldsIdentified() {
        mockValidCredentials();

        var result = service.discoverFromTable(CI_ID, "Loan_Master", "dev");
        var confFields = result.fields().stream()
                .filter(f -> "CONFIDENTIAL".equals(f.get("classification")))
                .toList();

        // loan_amount, interest_rate, credit_score, ltv, dti, monthly_payment, balance, escrow, principal
        assertTrue(confFields.size() >= 5,
                "Expected at least 5 CONFIDENTIAL fields, got " + confFields.size());
    }
}
