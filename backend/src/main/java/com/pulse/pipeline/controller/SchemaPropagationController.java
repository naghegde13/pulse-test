package com.pulse.pipeline.controller;

import com.pulse.pipeline.model.InstancePortSchema;
import com.pulse.pipeline.model.SchemaConflict;
import com.pulse.pipeline.service.SchemaPropagationService;
import com.pulse.pipeline.service.SchemaPropagationService.OverrideRequest;
import com.pulse.pipeline.service.SchemaPropagationService.ConflictResolutionPreview;
import com.pulse.pipeline.service.SchemaPropagationService.PropagationSummary;
import com.pulse.pipeline.service.SchemaPropagationService.ResolutionRequest;
import com.pulse.pipeline.service.SchemaPropagationService.SchemaGraph;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/versions/{versionId}")
public class SchemaPropagationController {

    private final SchemaPropagationService service;

    public SchemaPropagationController(SchemaPropagationService service) {
        this.service = service;
    }

    @PostMapping("/schema/recompute")
    public ResponseEntity<PropagationSummary> recompute(@PathVariable String versionId) {
        return ResponseEntity.ok(service.propagateFromVersion(versionId));
    }

    @GetMapping("/schema-graph")
    public ResponseEntity<SchemaGraph> schemaGraph(@PathVariable String versionId) {
        return ResponseEntity.ok(service.getSchemaGraph(versionId));
    }

    @GetMapping("/schema-conflicts")
    public ResponseEntity<List<SchemaConflict>> listConflicts(
            @PathVariable String versionId,
            @RequestParam(name = "includeResolved", defaultValue = "false") boolean includeResolved) {
        return ResponseEntity.ok(service.listConflicts(versionId, includeResolved));
    }

    @PostMapping("/schema-conflicts/{conflictId}/resolve")
    public ResponseEntity<SchemaConflict> resolve(
            @PathVariable String versionId,
            @PathVariable String conflictId,
            @RequestBody ResolutionRequest request) {
        return ResponseEntity.ok(service.resolveConflict(versionId, conflictId, request));
    }

    @PostMapping("/schema-conflicts/{conflictId}/preview")
    public ResponseEntity<ConflictResolutionPreview> previewResolution(
            @PathVariable String versionId,
            @PathVariable String conflictId,
            @RequestBody(required = false) ResolutionRequest request) {
        return ResponseEntity.ok(service.previewConflictResolution(versionId, conflictId, request));
    }

    @PutMapping("/schema/instances/{instanceId}/ports/{portName}/override")
    public ResponseEntity<InstancePortSchema> setOverride(
            @PathVariable String versionId,
            @PathVariable String instanceId,
            @PathVariable String portName,
            @RequestBody OverrideRequest request) {
        return ResponseEntity.ok(service.setOverride(versionId, instanceId, portName, request));
    }

    @DeleteMapping("/schema/instances/{instanceId}/ports/{portName}/override")
    public ResponseEntity<InstancePortSchema> clearOverride(
            @PathVariable String versionId,
            @PathVariable String instanceId,
            @PathVariable String portName) {
        return ResponseEntity.ok(service.clearOverride(versionId, instanceId, portName));
    }
}
