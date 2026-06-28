package com.pulse.sor.controller;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/targets")
public class TargetRegistryController {

    private static final String STUB_OWNER_ID = "01JUSER00000000000000000";

    private final SystemOfRecordRepository sorRepo;
    private final ConnectorInstanceRepository ciRepo;
    private final DatasetRepository datasetRepo;
    private final DomainRepository domainRepo;

    public TargetRegistryController(SystemOfRecordRepository sorRepo,
                                    ConnectorInstanceRepository ciRepo,
                                    DatasetRepository datasetRepo,
                                    DomainRepository domainRepo) {
        this.sorRepo = sorRepo;
        this.ciRepo = ciRepo;
        this.datasetRepo = datasetRepo;
        this.domainRepo = domainRepo;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable String tenantId) {
        List<SystemOfRecord> targets = sorRepo.findTargetsByTenantId(tenantId);
        List<Map<String, Object>> result = targets.stream().map(this::enrich).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{targetId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String tenantId, @PathVariable String targetId) {
        SystemOfRecord sor = sorRepo.findById(targetId)
                .filter(s -> tenantId.equals(s.getTenantId()))
                .filter(this::isTarget)
                .orElseThrow(() -> new ResourceNotFoundException("SinkTarget", targetId));
        return ResponseEntity.ok(enrich(sor));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String tenantId,
            @RequestBody CreateTargetRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Target name is required");
        }
        Domain domain = resolveDomain(tenantId, request.domainId());

        SystemOfRecord sor = new SystemOfRecord();
        sor.setTenantId(tenantId);
        sor.setName(request.name());
        sor.setDescription(request.description());
        sor.setDomainId(domain.getId());
        sor.setDomainName(domain.getName());
        sor.setOwnerId(STUB_OWNER_ID);
        sor.setMetadata(Map.of("registry_type", "TARGET"));

        SystemOfRecord saved = sorRepo.save(sor);
        return ResponseEntity.ok(enrich(saved));
    }

    private boolean isTarget(SystemOfRecord sor) {
        Map<String, Object> metadata = sor.getMetadata();
        if (metadata == null) {
            return false;
        }
        Object registryType = metadata.get("registry_type");
        return registryType instanceof String registryTypeStr
                && "TARGET".equalsIgnoreCase(registryTypeStr);
    }

    private Domain resolveDomain(String tenantId, String domainId) {
        if (domainId == null || domainId.isBlank()) {
            throw new IllegalArgumentException("A canonical domainId is required to create a sink target.");
        }
        return domainRepo.findById(domainId)
                .filter(domain -> tenantId.equals(domain.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Domain id '" + domainId + "' was not found for tenant '" + tenantId + "'."));
    }

    private Map<String, Object> enrich(SystemOfRecord sor) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", sor.getId());
        m.put("tenantId", sor.getTenantId());
        m.put("name", sor.getName());
        m.put("description", sor.getDescription());
        m.put("domainName", sor.getDomainName());
        m.put("domainId", sor.getDomainId());
        m.put("ownerId", sor.getOwnerId());
        m.put("metadata", sor.getMetadata());
        m.put("connectorCount", ciRepo.countBySorId(sor.getId()));
        long datasetCount = ciRepo.findBySorIdOrderByNameAsc(sor.getId()).stream()
                .mapToLong(ci -> datasetRepo.countByConnectorInstanceId(ci.getId())).sum();
        m.put("datasetCount", datasetCount);
        m.put("createdAt", sor.getCreatedAt());
        m.put("updatedAt", sor.getUpdatedAt());
        return m;
    }

    record CreateTargetRequest(String name, String description, String domainId) {}
}
