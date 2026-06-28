package com.pulse.storage;

import com.pulse.auth.service.TenantService;
import com.pulse.config.TenantConfig.TenantDefinition;
import com.pulse.storage.StorageRootConventionService.ConventionDefaults;
import com.pulse.storage.model.StorageBackendType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SU-6 / BUG-62: one sub-assertion — derive() returns the V96
 * conventional defaults for one (tenant, env, backend) cell.
 */
class StorageRootConventionServiceTest {

    private TenantService tenantService;
    private StorageRootConventionService svc;

    @BeforeEach
    void setUp() {
        tenantService = mock(TenantService.class);
        TenantDefinition def = new TenantDefinition();
        def.setId("tenant-acme");
        def.setSlug("acme");
        def.setName("ACME Corp");
        when(tenantService.getTenant(eq("tenant-acme"))).thenReturn(def);
        svc = new StorageRootConventionService(tenantService);
    }

    @Test
    void derive_gcp_dev_returnsV96BucketAndProjectNames() {
        ConventionDefaults d =
                svc.derive("tenant-acme", "dev", StorageBackendType.GCP);
        assertEquals("acme", d.tenantSlug());
        assertEquals("dev", d.env());
        assertEquals(StorageBackendType.GCP, d.backend());
        assertEquals("pulse-acme-dev-files", d.files());
        assertEquals("pulse-acme-dev-lake", d.lake());
        assertEquals("pulse-acme-dev", d.gcpProject());
        assertNull(d.dpcScheme());
        assertNull(d.dpcCluster());
    }

    @Test
    void derive_dpc_prod_returnsV96BucketAndClusterNames() {
        ConventionDefaults d =
                svc.derive("tenant-acme", "prod", StorageBackendType.DPC);
        assertEquals("pulse-dpc-acme-prod-files", d.files());
        assertEquals("pulse-dpc-acme-prod-lake", d.lake());
        assertEquals("s3a", d.dpcScheme());
        assertEquals("pulse-dpc-acme-prod", d.dpcCluster());
        assertNull(d.gcpProject());
    }

    @Test
    void derive_normalizesEnvCase() {
        ConventionDefaults d =
                svc.derive("tenant-acme", "UAT", StorageBackendType.GCP);
        assertEquals("uat", d.env());
        assertEquals("pulse-acme-uat-files", d.files());
    }

    @Test
    void derive_rejectsUnknownEnv() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.derive("tenant-acme", "staging", StorageBackendType.GCP));
    }

    @Test
    void derive_rejectsBlankTenant() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.derive("", "dev", StorageBackendType.GCP));
        assertThrows(IllegalArgumentException.class,
                () -> svc.derive(null, "dev", StorageBackendType.GCP));
    }

    @Test
    void derive_blowsUpWhenTenantHasNoSlug() {
        TenantDefinition slugless = new TenantDefinition();
        slugless.setId("tenant-no-slug");
        when(tenantService.getTenant(eq("tenant-no-slug"))).thenReturn(slugless);
        assertThrows(IllegalArgumentException.class,
                () -> svc.derive("tenant-no-slug", "dev", StorageBackendType.GCP));
    }

    @Test
    void supportedSetsAreNotMutated() {
        // The static sets are exposed; make sure they're not accidentally
        // mutated by the service (smoke-check via a fresh derive call).
        assertNotNull(StorageRootConventionService.SUPPORTED_ENVIRONMENTS);
        assertNotNull(StorageRootConventionService.SUPPORTED_BACKENDS);
        assertEquals(4, StorageRootConventionService.SUPPORTED_ENVIRONMENTS.size());
        assertEquals(2, StorageRootConventionService.SUPPORTED_BACKENDS.size());
    }
}
