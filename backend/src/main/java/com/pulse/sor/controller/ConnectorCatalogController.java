package com.pulse.sor.controller;

import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorType;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorCatalogController {

    private final ConnectorDefinitionRepository repo;

    public ConnectorCatalogController(ConnectorDefinitionRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<List<ConnectorDefinition>> list(
            @RequestParam(required = false) ConnectorType type,
            @RequestParam(required = false) String search) {
        if (search != null && !search.isBlank()) {
            return ResponseEntity.ok(repo.findByNameContainingIgnoreCaseOrderByNameAsc(search));
        }
        if (type != null) {
            return ResponseEntity.ok(repo.findByConnectorTypeOrderByNameAsc(type));
        }
        return ResponseEntity.ok(repo.findAllByOrderByConnectorTypeAscNameAsc());
    }

    @GetMapping("/{connectorId}")
    public ResponseEntity<ConnectorDefinition> get(@PathVariable String connectorId) {
        return ResponseEntity.ok(repo.findById(connectorId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorDefinition", connectorId)));
    }
}
