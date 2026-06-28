package com.pulse.tenant.service;

import com.pulse.auth.model.Tenant;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.config.TenantConfig;
import com.pulse.sor.repository.DomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * PKT-0010 — contract tests for tenant update and foundation identity
 * contracts: create, update, idempotent foundation behavior.
 */
@ExtendWith(MockitoExtension.class)
class TenantUpdateAndFoundationContractTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private TenantConfig tenantConfig;
    @Mock private DomainRepository domainRepo;

    private TenantService service;
    private final List<Tenant> storedTenants = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new TenantService(tenantRepository, tenantConfig, domainRepo);
        storedTenants.clear();

        lenient().when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            storedTenants.removeIf(s -> s.getId().equals(t.getId()));
            storedTenants.add(t);
            return t;
        });
        lenient().when(tenantRepository.existsById(anyString()))
                .thenAnswer(inv -> storedTenants.stream()
                        .anyMatch(t -> t.getId().equals(inv.getArgument(0))));
        lenient().when(tenantRepository.existsBySlug(anyString()))
                .thenAnswer(inv -> storedTenants.stream()
                        .anyMatch(t -> inv.getArgument(0).equals(t.getSlug())));
        lenient().when(tenantRepository.findById(anyString()))
                .thenAnswer(inv -> storedTenants.stream()
                        .filter(t -> t.getId().equals(inv.getArgument(0)))
                        .findFirst());
        lenient().when(tenantRepository.findAll()).thenReturn(storedTenants);
        lenient().when(tenantRepository.findByName(anyString()))
                .thenAnswer(inv -> storedTenants.stream()
                        .filter(t -> inv.getArgument(0).equals(t.getName()))
                        .findFirst());
    }

    // ---- Create + Update Identity for acme-lending ----

    @Test
    void createTenant_acmeLending_succeeds() {
        Tenant t = service.createTenant(
                "tenant-acme-lending", "Acme Lending", "acme-lending");

        assertEquals("tenant-acme-lending", t.getId());
        assertEquals("Acme Lending", t.getName());
        assertEquals("acme-lending", t.getSlug());
        assertEquals("api", t.getOrigin());
        assertEquals("active", t.getStatus());
    }

    @Test
    void updateTenant_changesName_preservesSlugAndOrigin() {
        seed("tenant-acme-lending", "Acme Lending", "acme-lending", "api");

        Tenant updated = service.updateTenant("tenant-acme-lending", "Acme Home Lending");

        assertEquals("Acme Home Lending", updated.getName());
        assertEquals("acme-lending", updated.getSlug());
        assertEquals("api", updated.getOrigin());
    }

    @Test
    void updateTenant_idempotentWithSameName() {
        seed("tenant-acme-lending", "Acme Lending", "acme-lending", "api");

        Tenant first = service.updateTenant("tenant-acme-lending", "Acme Lending");
        Tenant second = service.updateTenant("tenant-acme-lending", "Acme Lending");

        assertEquals(first.getName(), second.getName());
    }

    @Test
    void updateTenant_notFound_throws() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.updateTenant("tenant-missing", "Name"));
    }

    @Test
    void updateTenant_nullName_noChange() {
        seed("tenant-acme-lending", "Acme Lending", "acme-lending", "api");

        Tenant updated = service.updateTenant("tenant-acme-lending", null);

        assertEquals("Acme Lending", updated.getName());
    }

    @Test
    void updateTenant_blankName_noChange() {
        seed("tenant-acme-lending", "Acme Lending", "acme-lending", "api");

        Tenant updated = service.updateTenant("tenant-acme-lending", "  ");

        assertEquals("Acme Lending", updated.getName());
    }

    @Test
    void updateTenant_duplicateNameConflict_throws() {
        seed("tenant-acme-lending", "Acme Lending", "acme-lending", "api");
        seed("tenant-other", "Other Corp", "other", "api");

        assertThrows(IllegalStateException.class,
                () -> service.updateTenant("tenant-acme-lending", "Other Corp"));
    }

    // ---- getTenantEntity ----

    @Test
    void getTenantEntity_returnsFullEntity() {
        seed("tenant-acme-lending", "Acme Lending", "acme-lending", "api");

        Tenant t = service.getTenantEntity("tenant-acme-lending");

        assertNotNull(t);
        assertEquals("api", t.getOrigin());
        assertEquals("active", t.getStatus());
    }

    @Test
    void getTenantEntity_notFound_throws() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.getTenantEntity("tenant-missing"));
    }

    // ---- Fixtures ----

    private void seed(String id, String name, String slug, String origin) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setName(name);
        t.setSlug(slug);
        t.setOrigin(origin);
        t.setStatus("active");
        storedTenants.add(t);
    }
}
