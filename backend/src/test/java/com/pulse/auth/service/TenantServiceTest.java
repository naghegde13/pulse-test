package com.pulse.auth.service;

import com.pulse.auth.model.Tenant;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.config.TenantConfig;
import com.pulse.config.TenantConfig.TenantDefinition;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private TenantConfig tenantConfig;
    @Mock private DomainRepository domainRepo;

    private TenantService service;

    // Simulated DB for the tenant repo so bootstrap + CRUD flows behave realistically.
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
    }

    // -----------------------------------------------------------------
    // Read-path: TenantService uses the repository, not the YAML, at runtime
    // -----------------------------------------------------------------

    @Test
    void listTenants_readsFromRepository() {
        seed("tenant-home-lending", "Home Lending D&I", "home-lending", "bootstrap");
        seed("tenant-unsecured-lending", "Unsecured Lending", "unsecured-lending", "bootstrap");

        List<TenantDefinition> result = service.listTenants();

        assertEquals(2, result.size());
        assertEquals("tenant-home-lending", result.get(0).getId());
        verify(tenantRepository).findAll();
        // YAML must not be consulted during runtime reads.
        verify(tenantConfig, never()).getDefinitions();
    }

    @Test
    void getTenant_found_returns() {
        seed("tenant-home-lending", "Home Lending D&I", "home-lending", "bootstrap");

        TenantDefinition t = service.getTenant("tenant-home-lending");

        assertNotNull(t);
        assertEquals("home-lending", t.getSlug());
        assertEquals("Home Lending D&I", t.getName());
    }

    @Test
    void getTenant_notFound_throwsResourceNotFoundException() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.getTenant("tenant-does-not-exist"));
    }

    @Test
    void tenantExists_reflectsRepositoryState() {
        seed("tenant-home-lending", "Home Lending D&I", "home-lending", "bootstrap");

        assertTrue(service.tenantExists("tenant-home-lending"));
        assertTrue(!service.tenantExists("tenant-not-there"));
        verify(tenantRepository, times(2)).existsById(anyString());
    }

    @Test
    void getDomainsForTenant_delegatesToDomainRepo() {
        Domain d1 = new Domain();
        d1.setId("dom-1");
        d1.setTenantId("tenant-home-lending");
        d1.setName("Servicing");
        when(domainRepo.findByTenantIdOrderByNameAsc("tenant-home-lending"))
                .thenReturn(List.of(d1));

        List<String> domains = service.getDomainsForTenant("tenant-home-lending");

        assertEquals(List.of("Servicing"), domains);
    }

    // -----------------------------------------------------------------
    // Bootstrap: YAML → DB on startup, idempotent, respects API-created rows
    // -----------------------------------------------------------------

    @Test
    void bootstrapFromYaml_insertsMissingTenants() {
        when(tenantConfig.getDefinitions()).thenReturn(List.of(
                yaml("tenant-home-lending", "Home Lending D&I", "home-lending"),
                yaml("tenant-unsecured-lending", "Unsecured Lending", "unsecured-lending")));

        service.bootstrapFromYaml();

        assertEquals(2, storedTenants.size());
        for (Tenant t : storedTenants) {
            assertEquals("bootstrap", t.getOrigin());
            assertEquals("active", t.getStatus());
        }
    }

    @Test
    void bootstrapFromYaml_skipsExistingTenants() {
        seed("tenant-home-lending", "Home Lending D&I", "home-lending", "bootstrap");
        when(tenantConfig.getDefinitions()).thenReturn(List.of(
                yaml("tenant-home-lending", "Home Lending D&I", "home-lending"),
                yaml("tenant-unsecured-lending", "Unsecured Lending", "unsecured-lending")));

        service.bootstrapFromYaml();

        assertEquals(2, storedTenants.size());
        // Only the missing row was saved — not the pre-existing one.
        verify(tenantRepository, times(1)).save(any(Tenant.class));
    }

    @Test
    void bootstrapFromYaml_preservesApiCreatedTenants() {
        seed("tenant-api-created", "Custom SaaS Customer", "custom-saas", "api");
        when(tenantConfig.getDefinitions()).thenReturn(List.of(
                yaml("tenant-home-lending", "Home Lending D&I", "home-lending")));

        service.bootstrapFromYaml();

        assertEquals(2, storedTenants.size());
        Tenant api = storedTenants.stream()
                .filter(t -> "tenant-api-created".equals(t.getId()))
                .findFirst().orElseThrow();
        assertEquals("api", api.getOrigin());
    }

    @Test
    void bootstrapFromYaml_idempotentOnRepeatedRuns() {
        when(tenantConfig.getDefinitions()).thenReturn(List.of(
                yaml("tenant-home-lending", "Home Lending D&I", "home-lending")));

        service.bootstrapFromYaml();
        service.bootstrapFromYaml();
        service.bootstrapFromYaml();

        assertEquals(1, storedTenants.size());
        verify(tenantRepository, times(1)).save(any(Tenant.class));
    }

    @Test
    void bootstrapFromYaml_skipsYamlTenantWithSlugConflict() {
        seed("tenant-api-other", "Some Other Tenant", "home-lending", "api");
        when(tenantConfig.getDefinitions()).thenReturn(List.of(
                yaml("tenant-home-lending", "Home Lending D&I", "home-lending")));

        service.bootstrapFromYaml();

        // Should NOT insert the YAML tenant — its slug conflicts with the API-created one.
        assertEquals(1, storedTenants.size());
        assertEquals("tenant-api-other", storedTenants.get(0).getId());
    }

    // -----------------------------------------------------------------
    // Write-path: createTenant
    // -----------------------------------------------------------------

    @Test
    void createTenant_writesRowWithOriginApi() {
        Tenant t = service.createTenant(
                "tenant-new-customer", "New Customer", "new-customer");

        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(captor.capture());
        assertEquals("api", captor.getValue().getOrigin());
        assertEquals("active", captor.getValue().getStatus());
        assertEquals("tenant-new-customer", t.getId());
        assertEquals("new-customer", t.getSlug());
    }

    @Test
    void createTenant_rejectsDuplicateId() {
        seed("tenant-home-lending", "Home Lending D&I", "home-lending", "bootstrap");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createTenant("tenant-home-lending", "Any Name", "any-slug"));
        assertTrue(e.getMessage().contains("id"));
    }

    @Test
    void createTenant_rejectsDuplicateSlug() {
        seed("tenant-existing", "Existing", "home-lending", "bootstrap");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createTenant("tenant-new", "New Name", "home-lending"));
        assertTrue(e.getMessage().contains("slug"));
    }

    @Test
    void createTenant_validatesRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createTenant(null, "Name", "slug"));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTenant("id", null, "slug"));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTenant("id", "Name", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTenant("", "Name", "slug"));
    }

    @Test
    void createTenant_rejectsNonKebabSlug() {
        for (String bad : List.of("CamelCase", "under_score", "spaces here", "Trailing-", "-Leading", "double--dash", "UPPER")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createTenant("tenant-x", "X", bad),
                    "Expected rejection for slug: " + bad);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private void seed(String id, String name, String slug, String origin) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setName(name);
        t.setSlug(slug);
        t.setOrigin(origin);
        t.setStatus("active");
        storedTenants.add(t);
    }

    private TenantDefinition yaml(String id, String name, String slug) {
        TenantDefinition d = new TenantDefinition();
        d.setId(id);
        d.setName(name);
        d.setSlug(slug);
        return d;
    }
}
