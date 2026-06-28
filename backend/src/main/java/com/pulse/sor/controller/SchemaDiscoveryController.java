package com.pulse.sor.controller;

import com.pulse.auth.filter.TenantIsolationEnforcer;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.sor.service.SchemaDiscoveryService;
import com.pulse.sor.service.SchemaDiscoveryService.DiscoveryResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PKT-0026: Schema Discovery REST endpoints.
 * <p>
 * Provides three discovery methods (table, query, sample) and a
 * create-from-discovery endpoint that registers a dataset with the
 * discovered schema, PII classification, and provenance metadata.
 */
@RestController
public class SchemaDiscoveryController {

    private final SchemaDiscoveryService discoveryService;
    private final ConnectorInstanceRepository ciRepo;
    private final SystemOfRecordRepository sorRepo;
    private final DatasetRepository datasetRepo;

    public SchemaDiscoveryController(SchemaDiscoveryService discoveryService,
                                     ConnectorInstanceRepository ciRepo,
                                     SystemOfRecordRepository sorRepo,
                                     DatasetRepository datasetRepo) {
        this.discoveryService = discoveryService;
        this.ciRepo = ciRepo;
        this.sorRepo = sorRepo;
        this.datasetRepo = datasetRepo;
    }

    @PostMapping("/api/v1/connector-instances/{ciId}/schema-discovery/table")
    public ResponseEntity<?> discoverFromTable(
            @PathVariable String ciId,
            @RequestBody TableDiscoveryRequest request) {
        ConnectorInstance ci = enforceTenantForConnectorInstance(ciId);
        try {
            DiscoveryResult result = discoveryService.discoverFromTable(
                    ciId, request.tableName(), request.environment());
            return ResponseEntity.ok(toResponse(result));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .body(Map.of("error", "credential_not_ready", "detail", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_request", "detail", e.getMessage()));
        }
    }

    @PostMapping("/api/v1/connector-instances/{ciId}/schema-discovery/query")
    public ResponseEntity<?> discoverFromQuery(
            @PathVariable String ciId,
            @RequestBody QueryDiscoveryRequest request) {
        ConnectorInstance ci = enforceTenantForConnectorInstance(ciId);
        try {
            DiscoveryResult result = discoveryService.discoverFromQuery(
                    ciId, request.query(), request.environment());
            return ResponseEntity.ok(toResponse(result));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .body(Map.of("error", "credential_not_ready", "detail", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_request", "detail", e.getMessage()));
        }
    }

    @PostMapping("/api/v1/connector-instances/{ciId}/schema-discovery/sample")
    public ResponseEntity<?> discoverFromSample(
            @PathVariable String ciId,
            @RequestBody SampleDiscoveryRequest request) {
        enforceTenantForConnectorInstance(ciId);
        try {
            DiscoveryResult result = discoveryService.discoverFromSample(
                    ciId, request.sampleData(), request.format());
            return ResponseEntity.ok(toResponse(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_request", "detail", e.getMessage()));
        }
    }

    @PostMapping("/api/v1/connector-instances/{ciId}/datasets/from-discovery")
    public ResponseEntity<?> createFromDiscovery(
            @PathVariable String ciId,
            @RequestBody CreateFromDiscoveryRequest request) {
        ConnectorInstance ci = enforceTenantForConnectorInstance(ciId);
        SystemOfRecord sor = sorRepo.findById(ci.getSorId())
                .orElseThrow(() -> new ResourceNotFoundException("SystemOfRecord", ci.getSorId()));

        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_request", "detail", "Dataset name is required"));
        }
        if (!request.name().matches("[A-Za-z0-9_\\-. ]+")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_request",
                            "detail", "Dataset name must contain only alphanumeric characters, underscores, hyphens, dots, and spaces"));
        }

        if (request.fields() == null || request.fields().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_request", "detail", "Schema fields are required (use a discovery endpoint first)"));
        }

        Dataset ds = new Dataset();
        ds.setConnectorInstanceId(ciId);
        ds.setSorId(sor.getId());
        ds.setTenantId(sor.getTenantId());
        ds.setName(request.name());
        ds.setDescription(request.description());

        String domain = sor.getDomainName() != null ? sor.getDomainName().toLowerCase().replace(" ", "-") : "default";
        ds.setQualifiedName(sor.getTenantId() + "." + domain + ".raw." + request.name());

        // Schema
        Map<String, Object> schemaSnapshot = new LinkedHashMap<>();
        schemaSnapshot.put("fields", request.fields());
        ds.setSchemaSnapshot(schemaSnapshot);
        ds.setSchemaFormat("JSON_SCHEMA");
        ds.setStatus("SCHEMA_DEFINED");

        // Classification
        ds.setClassification(request.classification());

        // Definition type from discovery method
        String defType = switch (request.discoveryMethod() != null ? request.discoveryMethod() : "MANUAL") {
            case "TABLE_DISCOVERY" -> "TABLE_SELECTION";
            case "QUERY_DISCOVERY" -> "CUSTOM_SQL";
            case "SAMPLE_UPLOAD" -> "MANUAL_DEFINITION";
            default -> "MANUAL_DEFINITION";
        };
        ds.setDefinitionType(defType);

        // Discovery provenance
        ds.setDiscoveryMethod(request.discoveryMethod());
        ds.setDiscoveryProof(request.discoveryProof());

        // Temporal metadata
        if (request.timeGrain() != null) ds.setTimeGrain(request.timeGrain());
        if (request.asofColumnName() != null) ds.setAsofColumnName(request.asofColumnName());
        if (request.currentAsof() != null) {
            ds.setCurrentAsof(LocalDate.parse(request.currentAsof())
                    .atStartOfDay(ZoneOffset.UTC).toInstant());
        }
        if (request.asofTimezone() != null) ds.setAsofTimezone(request.asofTimezone());

        // Custom SQL for query-based discovery
        if (request.customSql() != null) ds.setCustomSql(request.customSql());
        // Source tables for table-based discovery
        if (request.sourceTables() != null) ds.setSourceTables(request.sourceTables());

        ds = datasetRepo.save(ds);
        return ResponseEntity.ok(ds);
    }

    private ConnectorInstance enforceTenantForConnectorInstance(String ciId) {
        ConnectorInstance ci = ciRepo.findById(ciId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorInstance", ciId));
        SystemOfRecord sor = sorRepo.findById(ci.getSorId())
                .orElseThrow(() -> new ResourceNotFoundException("SystemOfRecord", ci.getSorId()));
        TenantIsolationEnforcer.enforce(sor.getTenantId());
        return ci;
    }

    private Map<String, Object> toResponse(DiscoveryResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fields", result.fields());
        response.put("classification", result.classification());
        response.put("discoveryMethod", result.discoveryMethod());
        response.put("discoveryProof", result.discoveryProof());
        response.put("fieldCount", result.fields().size());
        return response;
    }

    record TableDiscoveryRequest(String tableName, String environment) {}
    record QueryDiscoveryRequest(String query, String environment) {}
    record SampleDiscoveryRequest(String sampleData, String format) {}
    record CreateFromDiscoveryRequest(
            String name,
            String description,
            List<Map<String, Object>> fields,
            String classification,
            String discoveryMethod,
            Map<String, Object> discoveryProof,
            String timeGrain,
            String asofColumnName,
            String currentAsof,
            String asofTimezone,
            String customSql,
            List<String> sourceTables
    ) {}
}
