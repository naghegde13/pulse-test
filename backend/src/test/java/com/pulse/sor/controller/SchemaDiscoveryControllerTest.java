package com.pulse.sor.controller;

import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.sor.service.SchemaDiscoveryService;
import com.pulse.sor.service.SchemaDiscoveryService.DiscoveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * PKT-0026: Schema Discovery Controller tests.
 * Covers REST endpoint contracts, credential blocking, and create-from-discovery.
 */
@ExtendWith(MockitoExtension.class)
class SchemaDiscoveryControllerTest {

    @Mock private SchemaDiscoveryService discoveryService;
    @Mock private ConnectorInstanceRepository ciRepo;
    @Mock private SystemOfRecordRepository sorRepo;
    @Mock private DatasetRepository datasetRepo;

    private SchemaDiscoveryController controller;

    private static final String CI_ID = "ci-jdbc-001";
    private static final String SOR_ID = "sor-001";
    private static final String TENANT_ID = "acme";

    @BeforeEach
    void setUp() {
        controller = new SchemaDiscoveryController(discoveryService, ciRepo, sorRepo, datasetRepo);
        // Default: connector instance and SOR exist with proper tenant
        mockConnectorInstance();
    }

    private void mockConnectorInstance() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(SOR_ID);
        when(ciRepo.findById(CI_ID)).thenReturn(Optional.of(ci));

        SystemOfRecord sor = new SystemOfRecord();
        sor.setTenantId(TENANT_ID);
        when(sorRepo.findById(SOR_ID)).thenReturn(Optional.of(sor));
    }

    // --- Table Discovery Endpoint ---

    @Test
    void discoverFromTable_success_returns200WithFields() {
        List<Map<String, Object>> fields = List.of(
                Map.of("name", "loan_id", "type", "VARCHAR", "nullable", false),
                Map.of("name", "ssn", "type", "VARCHAR", "nullable", true, "pii", true)
        );
        var result = new DiscoveryResult(fields, "PII", "TABLE_DISCOVERY",
                Map.of("method", "TABLE_DISCOVERY", "tableName", "Loan_Master"), List.of());

        when(discoveryService.discoverFromTable(CI_ID, "Loan_Master", "dev"))
                .thenReturn(result);

        var request = new SchemaDiscoveryController.TableDiscoveryRequest("Loan_Master", "dev");
        ResponseEntity<?> response = controller.discoverFromTable(CI_ID, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("PII", body.get("classification"));
        assertEquals("TABLE_DISCOVERY", body.get("discoveryMethod"));
        assertEquals(2, body.get("fieldCount"));
    }

    @Test
    void discoverFromTable_credentialNotReady_returns412() {
        when(discoveryService.discoverFromTable(CI_ID, "Loan_Master", "dev"))
                .thenThrow(new IllegalStateException("Credentials not validated"));

        var request = new SchemaDiscoveryController.TableDiscoveryRequest("Loan_Master", "dev");
        ResponseEntity<?> response = controller.discoverFromTable(CI_ID, request);

        assertEquals(HttpStatus.PRECONDITION_FAILED, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("credential_not_ready", body.get("error"));
    }

    @Test
    void discoverFromTable_invalidRequest_returns400() {
        when(discoveryService.discoverFromTable(CI_ID, null, "dev"))
                .thenThrow(new IllegalArgumentException("tableName is required"));

        var request = new SchemaDiscoveryController.TableDiscoveryRequest(null, "dev");
        ResponseEntity<?> response = controller.discoverFromTable(CI_ID, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // --- Query Discovery Endpoint ---

    @Test
    void discoverFromQuery_success_returns200() {
        List<Map<String, Object>> fields = List.of(
                Map.of("name", "loan_id", "type", "VARCHAR")
        );
        var result = new DiscoveryResult(fields, "INTERNAL", "QUERY_DISCOVERY",
                Map.of("method", "QUERY_DISCOVERY", "queryHash", "abc123"), List.of());

        when(discoveryService.discoverFromQuery(CI_ID, "SELECT * FROM Loan_Master", "dev"))
                .thenReturn(result);

        var request = new SchemaDiscoveryController.QueryDiscoveryRequest("SELECT * FROM Loan_Master", "dev");
        ResponseEntity<?> response = controller.discoverFromQuery(CI_ID, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // --- Sample Discovery Endpoint ---

    @Test
    void discoverFromSample_success_returns200() {
        List<Map<String, Object>> fields = List.of(
                Map.of("name", "id", "type", "INTEGER"),
                Map.of("name", "name", "type", "VARCHAR")
        );
        var result = new DiscoveryResult(fields, "INTERNAL", "SAMPLE_UPLOAD",
                Map.of("method", "SAMPLE_UPLOAD", "sampleHash", "def456"), List.of());

        when(discoveryService.discoverFromSample(CI_ID, "id,name\n1,test", "CSV"))
                .thenReturn(result);

        var request = new SchemaDiscoveryController.SampleDiscoveryRequest("id,name\n1,test", "CSV");
        ResponseEntity<?> response = controller.discoverFromSample(CI_ID, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // --- Create from Discovery ---

    @Test
    void createFromDiscovery_success_returnsDatasetWithProvenance() {
        SystemOfRecord sor = new SystemOfRecord();
        sor.setTenantId(TENANT_ID);
        sor.setDomainName("Lending");
        when(sorRepo.findById(SOR_ID)).thenReturn(Optional.of(sor));

        when(datasetRepo.save(any(Dataset.class))).thenAnswer(inv -> {
            Dataset ds = inv.getArgument(0);
            ds.setId("ds-001"); // Simulate ID assignment
            return ds;
        });

        List<Map<String, Object>> fields = List.of(
                Map.of("name", "loan_id", "type", "VARCHAR"),
                Map.of("name", "ssn", "type", "VARCHAR", "pii", true)
        );
        Map<String, Object> proof = Map.of("method", "TABLE_DISCOVERY", "tableName", "Loan_Master");

        var request = new SchemaDiscoveryController.CreateFromDiscoveryRequest(
                "Loan_Master", "Loan master dataset", fields, "PII",
                "TABLE_DISCOVERY", proof,
                "DAILY", "as_of_date", "2026-05-24", "America/New_York",
                null, List.of("Loan_Master")
        );

        ResponseEntity<?> response = controller.createFromDiscovery(CI_ID, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Dataset ds = (Dataset) response.getBody();
        assertNotNull(ds);
        assertEquals("Loan_Master", ds.getName());
        assertEquals("PII", ds.getClassification());
        assertEquals("TABLE_SELECTION", ds.getDefinitionType());
        assertEquals("TABLE_DISCOVERY", ds.getDiscoveryMethod());
        assertNotNull(ds.getDiscoveryProof());
        assertEquals("as_of_date", ds.getAsofColumnName());
        assertEquals("DAILY", ds.getTimeGrain());
        assertEquals("SCHEMA_DEFINED", ds.getStatus());
        assertEquals("acme.lending.raw.Loan_Master", ds.getQualifiedName());

        // As-of date bound
        assertNotNull(ds.getCurrentAsof());
        assertEquals("2026-05-24",
                ds.getCurrentAsof().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString());
    }

    @Test
    void createFromDiscovery_missingName_returns400() {
        var request = new SchemaDiscoveryController.CreateFromDiscoveryRequest(
                null, null, List.of(Map.of("name", "id")), null,
                null, null, null, null, null, null, null, null
        );

        ResponseEntity<?> response = controller.createFromDiscovery(CI_ID, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createFromDiscovery_missingFields_returns400() {
        var request = new SchemaDiscoveryController.CreateFromDiscoveryRequest(
                "test", null, null, null,
                null, null, null, null, null, null, null, null
        );

        ResponseEntity<?> response = controller.createFromDiscovery(CI_ID, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createFromDiscovery_emptyFields_returns400() {
        var request = new SchemaDiscoveryController.CreateFromDiscoveryRequest(
                "test", null, List.of(), null,
                null, null, null, null, null, null, null, null
        );

        ResponseEntity<?> response = controller.createFromDiscovery(CI_ID, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createFromDiscovery_queryDiscovery_setsCustomSqlDefinitionType() {
        SystemOfRecord sor = new SystemOfRecord();
        sor.setTenantId(TENANT_ID);
        when(sorRepo.findById(SOR_ID)).thenReturn(Optional.of(sor));

        when(datasetRepo.save(any(Dataset.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new SchemaDiscoveryController.CreateFromDiscoveryRequest(
                "loan_query", null, List.of(Map.of("name", "id")), "INTERNAL",
                "QUERY_DISCOVERY", Map.of(), null, null, null, null,
                "SELECT id FROM loans", null
        );

        ResponseEntity<?> response = controller.createFromDiscovery(CI_ID, request);
        Dataset ds = (Dataset) response.getBody();
        assertEquals("CUSTOM_SQL", ds.getDefinitionType());
        assertEquals("SELECT id FROM loans", ds.getCustomSql());
    }
}
