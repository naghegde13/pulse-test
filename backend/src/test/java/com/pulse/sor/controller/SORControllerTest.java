package com.pulse.sor.controller;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SORControllerTest {

    @Mock private SystemOfRecordRepository sorRepo;
    @Mock private ConnectorInstanceRepository ciRepo;
    @Mock private DatasetRepository dsRepo;
    @Mock private DomainRepository domainRepo;

    @InjectMocks
    private SORController controller;

    // -----------------------------------------------------------------------
    //  list tests
    // -----------------------------------------------------------------------

    @Test
    void list_returnsSORsWithConnectorCounts() {
        // Given
        SystemOfRecord sor = buildSOR("sor-1", "tenant-1", "Oracle LOS");
        when(sorRepo.findByTenantIdOrderByNameAsc("tenant-1")).thenReturn(List.of(sor));
        when(ciRepo.countBySorId("sor-1")).thenReturn(3L);
        when(ciRepo.findBySorIdOrderByNameAsc("sor-1")).thenReturn(List.of());

        // When
        ResponseEntity<List<Map<String, Object>>> response = controller.list("tenant-1");

        // Then
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());

        Map<String, Object> result = response.getBody().get(0);
        assertEquals("sor-1", result.get("id"));
        assertEquals("Oracle LOS", result.get("name"));
        assertEquals(3L, result.get("connectorCount"));
        verify(sorRepo).findByTenantIdOrderByNameAsc("tenant-1");
    }

    @Test
    void list_multipleSORsWithDatasetCounts() {
        // Given
        SystemOfRecord sor1 = buildSOR("sor-1", "tenant-1", "Oracle");
        SystemOfRecord sor2 = buildSOR("sor-2", "tenant-1", "Kafka");
        when(sorRepo.findByTenantIdOrderByNameAsc("tenant-1")).thenReturn(List.of(sor1, sor2));
        when(ciRepo.countBySorId(anyString())).thenReturn(1L);

        ConnectorInstance ci1 = new ConnectorInstance();
        ci1.setId("ci-1");
        when(ciRepo.findBySorIdOrderByNameAsc("sor-1")).thenReturn(List.of(ci1));
        when(ciRepo.findBySorIdOrderByNameAsc("sor-2")).thenReturn(List.of());
        when(dsRepo.countByConnectorInstanceId("ci-1")).thenReturn(5L);

        // When
        ResponseEntity<List<Map<String, Object>>> response = controller.list("tenant-1");

        // Then
        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        assertEquals(5L, response.getBody().get(0).get("datasetCount"));
        assertEquals(0L, response.getBody().get(1).get("datasetCount"));
    }

    // -----------------------------------------------------------------------
    //  get tests
    // -----------------------------------------------------------------------

    @Test
    void get_returnsSingleSORWithFullDetails() {
        // Given
        SystemOfRecord sor = buildSOR("sor-1", "tenant-1", "Oracle LOS");
        when(sorRepo.findById("sor-1")).thenReturn(Optional.of(sor));

        // When
        ResponseEntity<SystemOfRecord> response = controller.get("tenant-1", "sor-1");

        // Then
        assertEquals(200, response.getStatusCode().value());
        assertEquals("sor-1", response.getBody().getId());
        assertEquals("Oracle LOS", response.getBody().getName());
    }

    @Test
    void get_notFound_throwsException() {
        // Given
        when(sorRepo.findById("nonexistent")).thenReturn(Optional.empty());

        // When/Then
        assertThrows(ResourceNotFoundException.class,
                () -> controller.get("tenant-1", "nonexistent"));
    }

    // -----------------------------------------------------------------------
    //  create tests
    // -----------------------------------------------------------------------

    @Test
    void create_createsNewSOR() {
        // Given
        SORController.CreateSORRequest request = new SORController.CreateSORRequest(
                "New SOR", "A new system of record", null, "domain-1", Map.of("team", "Platform"));
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-1");
        domain.setName("Servicing");
        when(domainRepo.findById("domain-1")).thenReturn(Optional.of(domain));

        when(sorRepo.save(any(SystemOfRecord.class))).thenAnswer(inv -> {
            SystemOfRecord saved = inv.getArgument(0);
            saved.setId("sor-new");
            return saved;
        });

        // When
        ResponseEntity<SystemOfRecord> response = controller.create("tenant-1", request);

        // Then
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("tenant-1", response.getBody().getTenantId());
        assertEquals("New SOR", response.getBody().getName());
        assertEquals("Servicing", response.getBody().getDomainName());
        assertEquals("domain-1", response.getBody().getDomainId());
        assertEquals(Map.of("team", "Platform"), response.getBody().getMetadata());
        verify(sorRepo).save(any(SystemOfRecord.class));
    }

    @Test
    void create_withoutCanonicalDomainId_throwsException() {
        SORController.CreateSORRequest request = new SORController.CreateSORRequest(
                "New SOR", "A new system of record", "Servicing", null, Map.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> controller.create("tenant-1", request)
        );

        assertEquals("A canonical domainId is required to create a system of record.", error.getMessage());
        verify(domainRepo, never()).findByTenantIdAndName(anyString(), anyString());
        verify(sorRepo, never()).save(any(SystemOfRecord.class));
    }

    // -----------------------------------------------------------------------
    //  delete tests
    // -----------------------------------------------------------------------

    @Test
    void delete_deletesSOR() {
        SystemOfRecord sor = buildSOR("sor-1", "tenant-1", "Existing SOR");
        when(sorRepo.findById("sor-1")).thenReturn(Optional.of(sor));

        // When
        ResponseEntity<Void> response = controller.delete("tenant-1", "sor-1");

        // Then
        assertEquals(204, response.getStatusCode().value());
        verify(sorRepo).deleteById("sor-1");
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private SystemOfRecord buildSOR(String id, String tenantId, String name) {
        SystemOfRecord sor = new SystemOfRecord();
        sor.setId(id);
        sor.setTenantId(tenantId);
        sor.setName(name);
        sor.setDescription("Test SOR " + name);
        sor.setDomainName("Servicing");
        sor.setDomainId("domain-1");
        sor.setOwnerId("user-1");
        sor.setMetadata(Map.of());
        return sor;
    }
}
