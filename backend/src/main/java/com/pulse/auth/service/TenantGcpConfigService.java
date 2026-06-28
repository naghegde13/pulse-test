package com.pulse.auth.service;

import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.repository.TenantGcpConfigRepository;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Manages per-tenant GCP control-plane project configuration (project ID,
 * region). This is the authoritative source for which control-plane GCP
 * project a tenant targets; the credential resolver and identity probe derive
 * the control-plane project from this record.
 *
 * <p>See {@link TenantGcpConfig} for the control-plane vs data-plane
 * distinction.
 */
@Service
public class TenantGcpConfigService {

    private static final Logger log = LoggerFactory.getLogger(TenantGcpConfigService.class);

    private final TenantGcpConfigRepository configRepo;
    private final TenantRepository tenantRepo;

    public TenantGcpConfigService(TenantGcpConfigRepository configRepo,
                                  TenantRepository tenantRepo) {
        this.configRepo = configRepo;
        this.tenantRepo = tenantRepo;
    }

    public Optional<TenantGcpConfig> getConfig(String tenantId) {
        return configRepo.findByTenantId(tenantId);
    }

    @Transactional
    public TenantGcpConfig setConfig(String tenantId, String controlPlaneProjectId, String gcpRegion) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (controlPlaneProjectId == null || controlPlaneProjectId.isBlank()) {
            throw new IllegalArgumentException("controlPlaneProjectId is required");
        }
        if (!tenantRepo.existsById(tenantId)) {
            throw new ResourceNotFoundException("Tenant", tenantId);
        }

        String region = (gcpRegion == null || gcpRegion.isBlank()) ? "us-central1" : gcpRegion.strip();

        TenantGcpConfig config = configRepo.findByTenantId(tenantId).orElseGet(() -> {
            TenantGcpConfig c = new TenantGcpConfig();
            c.setTenantId(tenantId);
            return c;
        });
        config.setControlPlaneProjectId(controlPlaneProjectId.strip());
        config.setGcpRegion(region);
        config.setStatus("active");

        TenantGcpConfig saved = configRepo.save(config);
        log.info("Tenant {} GCP config set: controlPlaneProjectId={}, region={}",
                tenantId, controlPlaneProjectId, region);
        return saved;
    }
}
