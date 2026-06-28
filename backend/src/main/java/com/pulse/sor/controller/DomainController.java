package com.pulse.sor.controller;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.DomainAdvanceLog;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.service.TimeDimensionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/domains")
public class DomainController {

    private final DomainRepository domainRepo;
    private final TimeDimensionService timeDimensionService;

    public DomainController(DomainRepository domainRepo, TimeDimensionService timeDimensionService) {
        this.domainRepo = domainRepo;
        this.timeDimensionService = timeDimensionService;
    }

    @GetMapping
    public ResponseEntity<List<Domain>> list(@PathVariable String tenantId) {
        return ResponseEntity.ok(domainRepo.findByTenantIdOrderByNameAsc(tenantId));
    }

    @GetMapping("/{domainId}")
    public ResponseEntity<Domain> get(@PathVariable String tenantId, @PathVariable String domainId) {
        Domain domain = domainRepo.findById(domainId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Domain", domainId));
        return ResponseEntity.ok(domain);
    }

    @PostMapping
    public ResponseEntity<Domain> create(@PathVariable String tenantId, @RequestBody CreateDomainRequest request) {
        Domain domain = new Domain();
        domain.setTenantId(tenantId);
        domain.setName(request.name());
        domain.setSlug(com.pulse.common.text.Slugify.slugify(request.name()));
        domain.setDescription(request.description());
        return ResponseEntity.ok(domainRepo.save(domain));
    }

    @PutMapping("/{domainId}")
    public ResponseEntity<Domain> update(@PathVariable String tenantId, @PathVariable String domainId,
                                         @RequestBody CreateDomainRequest request) {
        Domain domain = domainRepo.findById(domainId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Domain", domainId));
        domain.setName(request.name());
        // Slug-on-rename policy (D1): slug is a stable identifier and is NOT re-derived on
        // name change. Use a separate endpoint if explicit slug rename is needed.
        domain.setDescription(request.description());
        return ResponseEntity.ok(domainRepo.save(domain));
    }

    @DeleteMapping("/{domainId}")
    public ResponseEntity<Void> delete(@PathVariable String tenantId, @PathVariable String domainId) {
        Domain domain = domainRepo.findById(domainId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Domain", domainId));
        domainRepo.delete(domain);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{domainId}/advance")
    public ResponseEntity<Map<String, Object>> advanceDomain(
            @PathVariable String tenantId, @PathVariable String domainId,
            @RequestBody AdvanceRequest request) {
        Domain domain = timeDimensionService.advanceDomain(
                domainId, request.advancedBy(), request.source(), request.notes());
        Map<String, Object> result = new HashMap<>();
        result.put("domainId", domain.getId());
        result.put("name", domain.getName());
        result.put("currentBusinessDate", domain.getCurrentBusinessDate());
        result.put("grain", domain.getBusinessDateGrain());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{domainId}/advance-history")
    public ResponseEntity<List<DomainAdvanceLog>> getAdvanceHistory(
            @PathVariable String tenantId, @PathVariable String domainId) {
        return ResponseEntity.ok(timeDimensionService.getDomainAdvanceHistory(domainId));
    }

    record CreateDomainRequest(String name, String description) {}
    record AdvanceRequest(String advancedBy, String source, String notes) {}
}
