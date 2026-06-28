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
@RequestMapping("/api/v1/tenants/{tenantId}/sors")
public class SORController {

    private final SystemOfRecordRepository sorRepo;
    private final ConnectorInstanceRepository ciRepo;
    private final DatasetRepository dsRepo;
    private final DomainRepository domainRepo;

    public SORController(SystemOfRecordRepository sorRepo,
                         ConnectorInstanceRepository ciRepo,
                         DatasetRepository dsRepo,
                         DomainRepository domainRepo) {
        this.sorRepo = sorRepo;
        this.ciRepo = ciRepo;
        this.dsRepo = dsRepo;
        this.domainRepo = domainRepo;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable String tenantId) {
        List<SystemOfRecord> sors = sorRepo.findByTenantIdOrderByNameAsc(tenantId);
        List<Map<String, Object>> result = sors.stream().map(sor -> {
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
            m.put("datasetCount", ciRepo.findBySorIdOrderByNameAsc(sor.getId()).stream()
                    .mapToLong(ci -> dsRepo.countByConnectorInstanceId(ci.getId())).sum());
            m.put("createdAt", sor.getCreatedAt());
            m.put("updatedAt", sor.getUpdatedAt());
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<SystemOfRecord> create(
            @PathVariable String tenantId,
            @RequestBody CreateSORRequest request) {
        Domain domain = resolveDomain(tenantId, request.domainId());
        SystemOfRecord sor = new SystemOfRecord();
        sor.setTenantId(tenantId);
        sor.setName(request.name());
        sor.setDescription(request.description());
        sor.setDomainName(domain.getName());
        sor.setDomainId(domain.getId());
        sor.setOwnerId("01JUSER00000000000000000");
        sor.setMetadata(request.metadata() != null ? request.metadata() : new HashMap<>());
        return ResponseEntity.ok(sorRepo.save(sor));
    }

    @GetMapping("/{sorId}")
    public ResponseEntity<SystemOfRecord> get(
            @PathVariable String tenantId,
            @PathVariable String sorId) {
        SystemOfRecord sor = sorRepo.findById(sorId)
                .orElseThrow(() -> new ResourceNotFoundException("SystemOfRecord", sorId));
        if (!tenantId.equals(sor.getTenantId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "SOR does not belong to tenant");
        }
        return ResponseEntity.ok(sor);
    }

    @DeleteMapping("/{sorId}")
    public ResponseEntity<Void> delete(
            @PathVariable String tenantId,
            @PathVariable String sorId) {
        SystemOfRecord sor = sorRepo.findById(sorId)
                .orElseThrow(() -> new ResourceNotFoundException("SystemOfRecord", sorId));
        if (!tenantId.equals(sor.getTenantId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "SOR does not belong to tenant");
        }
        sorRepo.deleteById(sorId);
        return ResponseEntity.noContent().build();
    }

    private Domain resolveDomain(String tenantId, String domainId) {
        if (domainId == null || domainId.isBlank()) {
            throw new IllegalArgumentException("A canonical domainId is required to create a system of record.");
        }

        return domainRepo.findById(domainId)
                .filter(domain -> tenantId.equals(domain.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Domain id '" + domainId + "' was not found for tenant '" + tenantId + "'."));
    }

    record CreateSORRequest(String name, String description, String domainName, String domainId, Map<String, Object> metadata) {}
}
