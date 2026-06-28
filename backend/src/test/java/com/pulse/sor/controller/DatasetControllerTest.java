package com.pulse.sor.controller;

import com.pulse.auth.filter.JwtPrincipal;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.sor.service.SchemaDiscoveryService;
import com.pulse.sor.service.TimeDimensionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasetControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createDatasetPersistsTimeDimensionFields() {
        DatasetRepository datasetRepo = mock(DatasetRepository.class);
        ConnectorInstanceRepository ciRepo = mock(ConnectorInstanceRepository.class);
        SystemOfRecordRepository sorRepo = mock(SystemOfRecordRepository.class);
        ConnectorDefinitionRepository connDefRepo = mock(ConnectorDefinitionRepository.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        SchemaDiscoveryService schemaDiscoveryService = mock(SchemaDiscoveryService.class);

        ConnectorInstance connector = new ConnectorInstance();
        connector.setId("connector-1");
        connector.setSorId("sor-1");
        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-1");
        sor.setTenantId("tenant-1");
        sor.setDomainName("Servicing");

        when(ciRepo.findById("connector-1")).thenReturn(Optional.of(connector));
        when(sorRepo.findById("sor-1")).thenReturn(Optional.of(sor));
        when(datasetRepo.save(any(Dataset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DatasetController controller = new DatasetController(
                datasetRepo, ciRepo, sorRepo, connDefRepo, timeDimensionService, schemaDiscoveryService);
        Instant currentAsof = Instant.parse("2026-03-02T05:00:00Z");

        Dataset saved = (Dataset) controller.createDataset("connector-1", new DatasetController.CreateDatasetRequest(
                "loan_master",
                "Advanceable proof dataset",
                "OBJECT_SELECTION",
                "INTERNAL",
                "JSON_SCHEMA",
                Map.of("columns", List.of(Map.of("name", "loan_id", "type", "string"))),
                Map.of(),
                null,
                List.of("loan_master.csv"),
                null,
                "DAILY",
                currentAsof,
                "America/New_York",
                Map.of("calendar", "servicing"),
                null, null, null, null,
                null, null
        )).getBody();

        assertEquals("DAILY", saved.getTimeGrain());
        assertEquals(currentAsof, saved.getCurrentAsof());
        assertEquals("America/New_York", saved.getAsofTimezone());
        assertEquals(Map.of("calendar", "servicing"), saved.getTimeGrainConfig());
        assertEquals("tenant-1.servicing.raw.loan_master", saved.getQualifiedName());
    }

    @Test
    void listByConnectorRejectsForeignTenantConnector() {
        DatasetRepository datasetRepo = mock(DatasetRepository.class);
        ConnectorInstanceRepository ciRepo = mock(ConnectorInstanceRepository.class);
        SystemOfRecordRepository sorRepo = mock(SystemOfRecordRepository.class);
        ConnectorDefinitionRepository connDefRepo = mock(ConnectorDefinitionRepository.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        SchemaDiscoveryService schemaDiscoveryService = mock(SchemaDiscoveryService.class);

        ConnectorInstance connector = new ConnectorInstance();
        connector.setId("connector-foreign");
        connector.setSorId("sor-foreign");
        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-foreign");
        sor.setTenantId("tenant-foreign");

        when(ciRepo.findById("connector-foreign")).thenReturn(Optional.of(connector));
        when(sorRepo.findById("sor-foreign")).thenReturn(Optional.of(sor));

        DatasetController controller = new DatasetController(
                datasetRepo, ciRepo, sorRepo, connDefRepo, timeDimensionService, schemaDiscoveryService);

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.list("tenant-1", "connector-foreign", null));

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void createDatasetOnSorRejectsForeignTenantSor() {
        DatasetRepository datasetRepo = mock(DatasetRepository.class);
        ConnectorInstanceRepository ciRepo = mock(ConnectorInstanceRepository.class);
        SystemOfRecordRepository sorRepo = mock(SystemOfRecordRepository.class);
        ConnectorDefinitionRepository connDefRepo = mock(ConnectorDefinitionRepository.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        SchemaDiscoveryService schemaDiscoveryService = mock(SchemaDiscoveryService.class);

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-foreign");
        sor.setTenantId("tenant-foreign");
        when(sorRepo.findById("sor-foreign")).thenReturn(Optional.of(sor));

        DatasetController controller = new DatasetController(
                datasetRepo, ciRepo, sorRepo, connDefRepo, timeDimensionService, schemaDiscoveryService);

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.createDatasetOnSor("tenant-1", "sor-foreign",
                        new DatasetController.CreateDatasetRequest(
                                "loan_master", null, "OBJECT_SELECTION", "INTERNAL", "JSON_SCHEMA",
                                null, Map.of(), null, List.of("loan_master.csv"), null,
                                null, null, null, null,
                                null, null, null, null,
                                null, null)));

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void advanceHistoryRejectsMismatchedDatasetPath() {
        DatasetRepository datasetRepo = mock(DatasetRepository.class);
        ConnectorInstanceRepository ciRepo = mock(ConnectorInstanceRepository.class);
        SystemOfRecordRepository sorRepo = mock(SystemOfRecordRepository.class);
        ConnectorDefinitionRepository connDefRepo = mock(ConnectorDefinitionRepository.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        SchemaDiscoveryService schemaDiscoveryService = mock(SchemaDiscoveryService.class);

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-1");
        sor.setTenantId("tenant-1");
        Dataset dataset = new Dataset();
        dataset.setId("dataset-1");
        dataset.setTenantId("tenant-1");
        dataset.setSorId("sor-1");
        dataset.setConnectorInstanceId("connector-actual");

        when(sorRepo.findById("sor-1")).thenReturn(Optional.of(sor));
        when(datasetRepo.findById("dataset-1")).thenReturn(Optional.of(dataset));

        DatasetController controller = new DatasetController(
                datasetRepo, ciRepo, sorRepo, connDefRepo, timeDimensionService, schemaDiscoveryService);

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.getAdvanceHistory("tenant-1", "sor-1", "connector-path", "dataset-1"));

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void getRejectsForeignTenantPrincipal() {
        DatasetRepository datasetRepo = mock(DatasetRepository.class);
        ConnectorInstanceRepository ciRepo = mock(ConnectorInstanceRepository.class);
        SystemOfRecordRepository sorRepo = mock(SystemOfRecordRepository.class);
        ConnectorDefinitionRepository connDefRepo = mock(ConnectorDefinitionRepository.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        SchemaDiscoveryService schemaDiscoveryService = mock(SchemaDiscoveryService.class);
        Dataset dataset = dataset("dataset-1", "tenant-1", "sor-1", "connector-1");
        when(datasetRepo.findById("dataset-1")).thenReturn(Optional.of(dataset));
        setAuthenticated("foreign-user", "foreign@pulse.dev", "tenant-foreign", "DATA_ENGINEER");

        DatasetController controller = new DatasetController(
                datasetRepo, ciRepo, sorRepo, connDefRepo, timeDimensionService, schemaDiscoveryService);

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.get("dataset-1"));

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void updateRejectsForeignTenantPrincipal() {
        DatasetRepository datasetRepo = mock(DatasetRepository.class);
        ConnectorInstanceRepository ciRepo = mock(ConnectorInstanceRepository.class);
        SystemOfRecordRepository sorRepo = mock(SystemOfRecordRepository.class);
        ConnectorDefinitionRepository connDefRepo = mock(ConnectorDefinitionRepository.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        SchemaDiscoveryService schemaDiscoveryService = mock(SchemaDiscoveryService.class);
        Dataset dataset = dataset("dataset-1", "tenant-1", "sor-1", "connector-1");
        when(datasetRepo.findById("dataset-1")).thenReturn(Optional.of(dataset));
        setAuthenticated("foreign-user", "foreign@pulse.dev", "tenant-foreign", "DATA_ENGINEER");

        DatasetController controller = new DatasetController(
                datasetRepo, ciRepo, sorRepo, connDefRepo, timeDimensionService, schemaDiscoveryService);

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.updateDataset("dataset-1", new DatasetController.UpdateDatasetRequest(
                        "new-name", null, null, null, null, null, null, null,
                        null, null, null, null, null,
                        null, null, null, null)));

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void deleteRejectsForeignTenantPrincipal() {
        DatasetRepository datasetRepo = mock(DatasetRepository.class);
        ConnectorInstanceRepository ciRepo = mock(ConnectorInstanceRepository.class);
        SystemOfRecordRepository sorRepo = mock(SystemOfRecordRepository.class);
        ConnectorDefinitionRepository connDefRepo = mock(ConnectorDefinitionRepository.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        SchemaDiscoveryService schemaDiscoveryService = mock(SchemaDiscoveryService.class);
        Dataset dataset = dataset("dataset-1", "tenant-1", "sor-1", "connector-1");
        when(datasetRepo.findById("dataset-1")).thenReturn(Optional.of(dataset));
        setAuthenticated("foreign-user", "foreign@pulse.dev", "tenant-foreign", "DATA_ENGINEER");

        DatasetController controller = new DatasetController(
                datasetRepo, ciRepo, sorRepo, connDefRepo, timeDimensionService, schemaDiscoveryService);

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.deleteDataset("dataset-1"));

        assertEquals(403, ex.getStatusCode().value());
    }

    private Dataset dataset(String id, String tenantId, String sorId, String connectorId) {
        Dataset dataset = new Dataset();
        dataset.setId(id);
        dataset.setTenantId(tenantId);
        dataset.setSorId(sorId);
        dataset.setConnectorInstanceId(connectorId);
        return dataset;
    }

    private void setAuthenticated(String userId, String email, String tenantId, String role) {
        JwtPrincipal principal = new JwtPrincipal(userId, email, email, tenantId, role, List.of());
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
