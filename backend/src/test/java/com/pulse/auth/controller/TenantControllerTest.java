package com.pulse.auth.controller;

import com.pulse.auth.controller.TenantController.CreateTenantRequest;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.config.TenantConfig.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantControllerTest {

    @Mock private TenantService tenantService;

    @InjectMocks
    private TenantController controller;

    @Test
    void listTenants_returnsAll() {
        when(tenantService.listTenants()).thenReturn(List.of(
                def("tenant-home-lending", "Home Lending D&I", "home-lending"),
                def("tenant-unsecured-lending", "Unsecured Lending", "unsecured-lending")));
        when(tenantService.getDomainsForTenant(anyString()))
                .thenReturn(List.of("Servicing"));

        ResponseEntity<List<Map<String, Object>>> response = controller.listTenants();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("tenant-home-lending", response.getBody().get(0).get("id"));
        assertEquals(List.of("Servicing"), response.getBody().get(0).get("domains"));
    }

    @Test
    void getTenant_found_returns200() {
        when(tenantService.getTenant("tenant-home-lending")).thenReturn(
                def("tenant-home-lending", "Home Lending D&I", "home-lending"));
        when(tenantService.getDomainsForTenant("tenant-home-lending"))
                .thenReturn(List.of("Servicing"));

        ResponseEntity<Map<String, Object>> response = controller.getTenant("tenant-home-lending");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("home-lending", response.getBody().get("slug"));
    }

    @Test
    void getTenant_notFound_returns404() {
        when(tenantService.getTenant("tenant-missing"))
                .thenThrow(new ResourceNotFoundException("Tenant", "tenant-missing"));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.getTenant("tenant-missing"));
        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
    }

    @Test
    void createTenant_validReturns201() {
        Tenant saved = new Tenant();
        saved.setId("tenant-new");
        saved.setName("New Customer");
        saved.setSlug("new-customer");
        saved.setOrigin("api");
        saved.setStatus("active");
        when(tenantService.createTenant("tenant-new", "New Customer", "new-customer"))
                .thenReturn(saved);

        ResponseEntity<Map<String, Object>> response = controller.createTenant(
                new CreateTenantRequest("tenant-new", "New Customer", "new-customer"));

        assertEquals(201, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("api", response.getBody().get("origin"));
        assertEquals("tenant-new", response.getBody().get("id"));
    }

    @Test
    void createTenant_duplicateIdReturns409() {
        when(tenantService.createTenant(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Tenant id already exists: tenant-dup"));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.createTenant(
                        new CreateTenantRequest("tenant-dup", "Dup", "dup")));
        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
    }

    @Test
    void createTenant_invalidSlugReturns400() {
        when(tenantService.createTenant(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("slug must be lowercase kebab-case"));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.createTenant(
                        new CreateTenantRequest("tenant-x", "X", "BadSlug")));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }

    @Test
    void createTenant_nullBodyReturns400() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.createTenant(null));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }

    private TenantDefinition def(String id, String name, String slug) {
        TenantDefinition d = new TenantDefinition();
        d.setId(id);
        d.setName(name);
        d.setSlug(slug);
        return d;
    }
}
