package com.pulse.sor.controller;

import com.pulse.auth.filter.TenantIsolationEnforcer;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.sor.model.*;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.sor.service.SchemaDiscoveryService;
import com.pulse.sor.service.SchemaDiscoveryService.DiscoveryResult;
import com.pulse.sor.service.TimeDimensionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class DatasetController {

    private final DatasetRepository datasetRepo;
    private final ConnectorInstanceRepository ciRepo;
    private final SystemOfRecordRepository sorRepo;
    private final ConnectorDefinitionRepository connDefRepo;
    private final TimeDimensionService timeDimensionService;
    private final SchemaDiscoveryService schemaDiscoveryService;

    public DatasetController(DatasetRepository datasetRepo,
                             ConnectorInstanceRepository ciRepo,
                             SystemOfRecordRepository sorRepo,
                             ConnectorDefinitionRepository connDefRepo,
                             TimeDimensionService timeDimensionService,
                             SchemaDiscoveryService schemaDiscoveryService) {
        this.datasetRepo = datasetRepo;
        this.ciRepo = ciRepo;
        this.sorRepo = sorRepo;
        this.connDefRepo = connDefRepo;
        this.timeDimensionService = timeDimensionService;
        this.schemaDiscoveryService = schemaDiscoveryService;
    }

    @GetMapping("/api/v1/tenants/{tenantId}/datasets")
    public ResponseEntity<List<Dataset>> list(
            @PathVariable String tenantId,
            @RequestParam(required = false) String connectorInstanceId,
            @RequestParam(required = false) String sorId) {
        if (connectorInstanceId != null) {
            enforceConnectorBelongsToTenant(connectorInstanceId, tenantId);
            return ResponseEntity.ok(datasetRepo.findByConnectorInstanceIdOrderByNameAsc(connectorInstanceId));
        }
        if (sorId != null) {
            enforceSorBelongsToTenant(sorId, tenantId);
            return ResponseEntity.ok(datasetRepo.findBySorIdOrderByNameAsc(sorId));
        }
        TenantIsolationEnforcer.enforce(tenantId);
        return ResponseEntity.ok(datasetRepo.findByTenantIdOrderByQualifiedNameAsc(tenantId));
    }

    @GetMapping("/api/v1/datasets/{datasetId}")
    public ResponseEntity<Dataset> get(@PathVariable String datasetId) {
        Dataset ds = datasetRepo.findById(datasetId)
                .orElseThrow(() -> new ResourceNotFoundException("Dataset", datasetId));
        TenantIsolationEnforcer.enforce(ds.getTenantId());
        return ResponseEntity.ok(ds);
    }

    @PostMapping("/api/v1/tenants/{tenantId}/sors/{sorId}/datasets")
    public ResponseEntity<?> createDatasetOnSor(
            @PathVariable String tenantId,
            @PathVariable String sorId,
            @RequestBody CreateDatasetRequest request) {
        SystemOfRecord sor = sorRepo.findById(sorId)
                .orElseThrow(() -> new ResourceNotFoundException("SOR", sorId));
        enforceSorTenant(sor, tenantId);

        Dataset ds = buildDataset(request, sor.getTenantId(), sorId, null, sor);
        return saveNewDataset(ds);
    }

    @PostMapping("/api/v1/connector-instances/{ciId}/datasets")
    public ResponseEntity<?> createDataset(
            @PathVariable String ciId,
            @RequestBody CreateDatasetRequest request) {
        ConnectorInstance ci = ciRepo.findById(ciId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorInstance", ciId));
        SystemOfRecord sor = sorRepo.findById(ci.getSorId())
                .orElseThrow(() -> new ResourceNotFoundException("SOR", ci.getSorId()));
        TenantIsolationEnforcer.enforce(sor.getTenantId());

        Dataset ds = buildDataset(request, sor.getTenantId(), ci.getSorId(), ciId, sor);
        return saveNewDataset(ds);
    }

    /**
     * LCT-018: Deterministic, zero-LLM sample-file ingestion for the
     * "Upload Sample File" dataset-definition method. Parses the raw sample
     * content server-side, infers a typed schema with PII/classification
     * evidence, and returns preview rows so the dialog can show the operator
     * exactly what will be persisted (ADR 0011: no LLM in schema inference).
     */
    @PostMapping("/api/v1/tenants/{tenantId}/datasets/infer-sample")
    public ResponseEntity<?> inferSample(
            @PathVariable String tenantId,
            @RequestBody InferSampleRequest request) {
        TenantIsolationEnforcer.enforce(tenantId);
        try {
            DiscoveryResult result = schemaDiscoveryService.inferSample(
                    request.sampleData(), request.format());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fields", result.fields());
            body.put("classification", result.classification());
            body.put("discoveryMethod", result.discoveryMethod());
            body.put("discoveryProof", result.discoveryProof());
            body.put("previewRows", result.previewRows());
            body.put("fieldCount", result.fields().size());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_sample", "detail", e.getMessage()));
        }
    }

    /**
     * LCT-019: Persist a new dataset, converting a duplicate qualified-name
     * collision into a clean HTTP 409 instead of an opaque 500. A pre-check
     * gives the clearest message; the DataIntegrityViolationException catch is
     * a race-condition safety net for the DB unique constraint.
     */
    private ResponseEntity<?> saveNewDataset(Dataset ds) {
        if (datasetRepo.existsByQualifiedName(ds.getQualifiedName())) {
            return duplicateQualifiedNameResponse(ds.getName(), ds.getQualifiedName());
        }
        try {
            return ResponseEntity.ok(datasetRepo.save(ds));
        } catch (DataIntegrityViolationException e) {
            return duplicateQualifiedNameResponse(ds.getName(), ds.getQualifiedName());
        }
    }

    private ResponseEntity<Map<String, Object>> duplicateQualifiedNameResponse(String name, String qualifiedName) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "duplicate_qualified_name",
                "message", "A dataset named \"" + name + "\" already exists in this domain "
                        + "(qualified name \"" + qualifiedName + "\"). Choose a different name.",
                "qualifiedName", qualifiedName));
    }

    private Dataset buildDataset(CreateDatasetRequest request, String tenantId, String sorId,
                                  String connectorInstanceId, SystemOfRecord sor) {
        Dataset ds = new Dataset();
        ds.setConnectorInstanceId(connectorInstanceId);
        ds.setSorId(sorId);
        ds.setTenantId(tenantId);
        ds.setName(request.name());
        ds.setDescription(request.description());
        ds.setDefinitionType(request.definitionType());
        ds.setClassification(request.classification());
        ds.setTimeGrain(request.timeGrain());
        ds.setCurrentAsof(request.currentAsof());
        ds.setAsofTimezone(request.asofTimezone());
        ds.setTimeGrainConfig(request.timeGrainConfig());

        String domain = sor.getDomainName() != null ? sor.getDomainName().toLowerCase().replace(" ", "-") : "default";
        ds.setQualifiedName(tenantId + "." + domain + ".raw." + request.name());

        Map<String, Object> defConfig = request.definitionConfig() != null ? request.definitionConfig() : new HashMap<>();
        String schemaFmt = request.schemaFormat() != null ? request.schemaFormat() : "JSON_SCHEMA";

        switch (request.definitionType() != null ? request.definitionType() : "MANUAL_DEFINITION") {
            case "TABLE_SELECTION" -> {
                ds.setSourceTables(request.sourceTables());
                ds.setSchemaFormat("DDL");
                ds.setDefinitionConfig(defConfig);
            }
            case "CUSTOM_SQL" -> {
                ds.setCustomSql(request.customSql());
                ds.setSchemaFormat("DDL");
                ds.setDefinitionConfig(defConfig);
            }
            case "API_SPEC_IMPORT" -> {
                ds.setApiSpec(request.apiSpec());
                ds.setSchemaFormat("JSON_SCHEMA");
                ds.setDefinitionConfig(defConfig);
            }
            case "OBJECT_SELECTION" -> {
                ds.setSourceTables(request.sourceTables());
                ds.setSchemaFormat(schemaFmt);
                ds.setDefinitionConfig(defConfig);
            }
            case "SCHEMA_REGISTRY" -> {
                ds.setSchemaFormat(request.schemaFormat() != null ? request.schemaFormat() : "AVRO");
                ds.setDefinitionConfig(defConfig);
            }
            case "SAMPLE_UPLOAD" -> {
                // LCT-018: schema came from a server-side deterministic sample
                // inference; persist its provenance alongside the snapshot.
                ds.setSchemaFormat("JSON_SCHEMA");
                ds.setDefinitionConfig(defConfig);
                ds.setDiscoveryMethod(request.discoveryMethod() != null ? request.discoveryMethod() : "SAMPLE_UPLOAD");
                if (request.discoveryProof() != null) ds.setDiscoveryProof(request.discoveryProof());
            }
            default -> {
                ds.setSchemaFormat(schemaFmt);
                ds.setDefinitionConfig(defConfig);
            }
        }

        if (request.schemaSnapshot() != null) {
            ds.setSchemaSnapshot(request.schemaSnapshot());
            ds.setStatus("SCHEMA_DEFINED");
        } else {
            ds.setStatus("DRAFT");
        }

        // PKT-0023: Physical design fields
        if (request.partitionStrategy() != null) ds.setPartitionStrategy(request.partitionStrategy());
        if (request.clusterStrategy() != null) ds.setClusterStrategy(request.clusterStrategy());
        if (request.writeMode() != null) ds.setWriteMode(request.writeMode());
        if (request.tableFormatHint() != null) ds.setTableFormatHint(request.tableFormatHint());

        // Generate slugs for landing contract
        String datasetSlug = request.name().toLowerCase().replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        ds.setDatasetSlug(datasetSlug);
        ds.setDomainSlug(domain);

        if (request.partitionStrategy() != null || request.clusterStrategy() != null
                || request.writeMode() != null || request.tableFormatHint() != null) {
            ds.setPhysicalDesignVersion(1);
        }

        return ds;
    }

    @PutMapping("/api/v1/datasets/{datasetId}")
    public ResponseEntity<?> updateDataset(
            @PathVariable String datasetId,
            @RequestBody UpdateDatasetRequest request) {
        Dataset ds = datasetRepo.findById(datasetId)
                .orElseThrow(() -> new ResourceNotFoundException("Dataset", datasetId));
        TenantIsolationEnforcer.enforce(ds.getTenantId());

        if (request.name() != null) ds.setName(request.name());
        if (request.description() != null) ds.setDescription(request.description());
        if (request.classification() != null) ds.setClassification(request.classification());
        if (request.schemaSnapshot() != null) {
            ds.setSchemaSnapshot(request.schemaSnapshot());
            ds.setStatus("SCHEMA_DEFINED");
        }
        if (request.schemaFormat() != null) ds.setSchemaFormat(request.schemaFormat());
        if (request.customSql() != null) ds.setCustomSql(request.customSql());
        if (request.sourceTables() != null) ds.setSourceTables(request.sourceTables());
        if (request.apiSpec() != null) ds.setApiSpec(request.apiSpec());
        if (request.definitionConfig() != null) ds.setDefinitionConfig(request.definitionConfig());
        if (request.timeGrain() != null) ds.setTimeGrain(request.timeGrain());
        if (request.currentAsof() != null) ds.setCurrentAsof(request.currentAsof());
        if (request.asofTimezone() != null) ds.setAsofTimezone(request.asofTimezone());
        if (request.timeGrainConfig() != null) ds.setTimeGrainConfig(request.timeGrainConfig());

        // PKT-0023: Physical design fields on update
        if (request.partitionStrategy() != null) ds.setPartitionStrategy(request.partitionStrategy());
        if (request.clusterStrategy() != null) ds.setClusterStrategy(request.clusterStrategy());
        if (request.writeMode() != null) ds.setWriteMode(request.writeMode());
        if (request.tableFormatHint() != null) ds.setTableFormatHint(request.tableFormatHint());
        if (request.partitionStrategy() != null || request.clusterStrategy() != null
                || request.writeMode() != null || request.tableFormatHint() != null) {
            ds.setPhysicalDesignVersion(ds.getPhysicalDesignVersion() + 1);
        }

        // LCT-019 audit: updateDataset never recomputes qualifiedName (the
        // unique key), so a rename cannot collide on the constraint. The
        // DataIntegrityViolationException catch remains as a defensive net so
        // any future qualifiedName mutation surfaces a clean 409 rather than a
        // raw 500.
        try {
            return ResponseEntity.ok(datasetRepo.save(ds));
        } catch (DataIntegrityViolationException e) {
            return duplicateQualifiedNameResponse(ds.getName(), ds.getQualifiedName());
        }
    }

    @DeleteMapping("/api/v1/datasets/{datasetId}")
    public ResponseEntity<Void> deleteDataset(@PathVariable String datasetId) {
        Dataset ds = datasetRepo.findById(datasetId)
                .orElseThrow(() -> new ResourceNotFoundException("Dataset", datasetId));
        TenantIsolationEnforcer.enforce(ds.getTenantId());
        datasetRepo.deleteById(datasetId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/connectors/{connectorDefId}/definition-methods")
    public ResponseEntity<List<Map<String, Object>>> getDefinitionMethods(
            @PathVariable String connectorDefId) {
        ConnectorDefinition def = connDefRepo.findById(connectorDefId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorDefinition", connectorDefId));
        return ResponseEntity.ok(DatasetDefinitionMethods.getMethodsForConnector(def.getDockerRepository()));
    }

    record CreateDatasetRequest(
            String name,
            String description,
            String definitionType,
            String classification,
            String schemaFormat,
            Map<String, Object> schemaSnapshot,
            Map<String, Object> definitionConfig,
            String customSql,
            List<String> sourceTables,
            Map<String, Object> apiSpec,
            String timeGrain,
            Instant currentAsof,
            String asofTimezone,
            Map<String, Object> timeGrainConfig,
            // PKT-0023: Physical design fields
            Map<String, Object> partitionStrategy,
            Map<String, Object> clusterStrategy,
            String writeMode,
            String tableFormatHint,
            // LCT-018: sample-inference provenance
            String discoveryMethod,
            Map<String, Object> discoveryProof
    ) {}

    record InferSampleRequest(String sampleData, String format) {}

    record UpdateDatasetRequest(
            String name,
            String description,
            String classification,
            String schemaFormat,
            Map<String, Object> schemaSnapshot,
            Map<String, Object> definitionConfig,
            String customSql,
            List<String> sourceTables,
            Map<String, Object> apiSpec,
            String timeGrain,
            Instant currentAsof,
            String asofTimezone,
            Map<String, Object> timeGrainConfig,
            // PKT-0023: Physical design fields
            Map<String, Object> partitionStrategy,
            Map<String, Object> clusterStrategy,
            String writeMode,
            String tableFormatHint
    ) {}

    // --- Time Dimension Advance Endpoints ---

    @PostMapping("/api/v1/tenants/{tenantId}/sors/{sorId}/connectors/{connectorId}/datasets/{datasetId}/advance")
    public ResponseEntity<Map<String, Object>> advanceDataset(
            @PathVariable String tenantId,
            @PathVariable String sorId,
            @PathVariable String connectorId,
            @PathVariable String datasetId,
            @RequestBody AdvanceRequest request) {
        enforceDatasetPath(tenantId, sorId, connectorId, datasetId);
        Dataset ds = timeDimensionService.advanceDataset(
                datasetId, request.advancedBy(), request.source(), request.notes(), request.requestedAsof());
        AsofAdvanceLog latestLog = timeDimensionService.getDatasetAdvanceHistory(datasetId).stream()
                .findFirst()
                .orElse(null);
        Map<String, Object> result = new HashMap<>();
        result.put("datasetId", ds.getId());
        result.put("name", ds.getName());
        result.put("previousAsof", latestLog != null ? latestLog.getPreviousAsof() : null);
        result.put("currentAsof", ds.getCurrentAsof());
        result.put("advanceStatus", latestLog != null ? latestLog.getAdvanceStatus() : null);
        result.put("timeGrain", ds.getTimeGrain());
        result.put("nextExpectedAsof", timeDimensionService.computeNextAsof(ds));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/v1/tenants/{tenantId}/sors/{sorId}/connectors/{connectorId}/datasets/{datasetId}/advance-history")
    public ResponseEntity<List<AsofAdvanceLog>> getAdvanceHistory(
            @PathVariable String tenantId,
            @PathVariable String sorId,
            @PathVariable String connectorId,
            @PathVariable String datasetId) {
        enforceDatasetPath(tenantId, sorId, connectorId, datasetId);
        return ResponseEntity.ok(timeDimensionService.getDatasetAdvanceHistory(datasetId));
    }

    private SystemOfRecord enforceSorBelongsToTenant(String sorId, String tenantId) {
        SystemOfRecord sor = sorRepo.findById(sorId)
                .orElseThrow(() -> new ResourceNotFoundException("SOR", sorId));
        enforceSorTenant(sor, tenantId);
        return sor;
    }

    private ConnectorInstance enforceConnectorBelongsToTenant(String ciId, String tenantId) {
        ConnectorInstance ci = ciRepo.findById(ciId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorInstance", ciId));
        SystemOfRecord sor = enforceSorBelongsToTenant(ci.getSorId(), tenantId);
        if (!sor.getId().equals(ci.getSorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connector instance does not belong to SOR");
        }
        return ci;
    }

    private void enforceDatasetPath(String tenantId, String sorId, String connectorId, String datasetId) {
        Dataset ds = datasetRepo.findById(datasetId)
                .orElseThrow(() -> new ResourceNotFoundException("Dataset", datasetId));
        if (!tenantId.equals(ds.getTenantId())
                || !sorId.equals(ds.getSorId())
                || !connectorId.equals(ds.getConnectorInstanceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dataset path does not match dataset ownership");
        }
        TenantIsolationEnforcer.enforce(ds.getTenantId());
    }

    private void enforceSorTenant(SystemOfRecord sor, String tenantId) {
        if (!tenantId.equals(sor.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SOR does not belong to tenant");
        }
        TenantIsolationEnforcer.enforce(sor.getTenantId());
    }

    record AdvanceRequest(String advancedBy, String source, String notes, String requestedAsof) {}
}
