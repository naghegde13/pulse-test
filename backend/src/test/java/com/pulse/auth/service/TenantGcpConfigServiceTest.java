package com.pulse.auth.service;

import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.repository.TenantGcpConfigRepository;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantGcpConfigServiceTest {

    @Mock private TenantGcpConfigRepository configRepo;
    @Mock private TenantRepository tenantRepo;
    @InjectMocks private TenantGcpConfigService service;

    @Test
    void setConfig_newConfig_createsRecord() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        when(configRepo.findByTenantId("tenant-acme")).thenReturn(Optional.empty());
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TenantGcpConfig result = service.setConfig("tenant-acme", "pulse-proof-04261847", "us-central1");

        assertEquals("tenant-acme", result.getTenantId());
        assertEquals("pulse-proof-04261847", result.getControlPlaneProjectId());
        assertEquals("us-central1", result.getGcpRegion());
        assertEquals("active", result.getStatus());
    }

    @Test
    void setConfig_existingConfig_updatesRecord() {
        TenantGcpConfig existing = new TenantGcpConfig();
        existing.setTenantId("tenant-acme");
        existing.setControlPlaneProjectId("old-project");
        existing.setGcpRegion("us-east1");

        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        when(configRepo.findByTenantId("tenant-acme")).thenReturn(Optional.of(existing));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TenantGcpConfig result = service.setConfig("tenant-acme", "pulse-proof-04261847", "us-central1");

        assertEquals("pulse-proof-04261847", result.getControlPlaneProjectId());
        assertEquals("us-central1", result.getGcpRegion());
    }

    @Test
    void setConfig_defaultsRegionWhenNull() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        when(configRepo.findByTenantId("tenant-acme")).thenReturn(Optional.empty());
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TenantGcpConfig result = service.setConfig("tenant-acme", "pulse-proof-04261847", null);

        assertEquals("us-central1", result.getGcpRegion());
    }

    @Test
    void setConfig_nullTenantId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setConfig(null, "project", "region"));
    }

    @Test
    void setConfig_nullProjectId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setConfig("tenant-acme", null, "region"));
    }

    @Test
    void setConfig_tenantNotFound_throws() {
        when(tenantRepo.existsById("missing")).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> service.setConfig("missing", "project", "region"));
    }

    @Test
    void getConfig_exists_returnsConfig() {
        TenantGcpConfig config = new TenantGcpConfig();
        config.setTenantId("tenant-acme");
        config.setControlPlaneProjectId("pulse-proof-04261847");
        when(configRepo.findByTenantId("tenant-acme")).thenReturn(Optional.of(config));

        Optional<TenantGcpConfig> result = service.getConfig("tenant-acme");

        assertTrue(result.isPresent());
        assertEquals("pulse-proof-04261847", result.get().getControlPlaneProjectId());
    }

    @Test
    void getConfig_notExists_returnsEmpty() {
        when(configRepo.findByTenantId("missing")).thenReturn(Optional.empty());

        Optional<TenantGcpConfig> result = service.getConfig("missing");

        assertTrue(result.isEmpty());
    }
}
