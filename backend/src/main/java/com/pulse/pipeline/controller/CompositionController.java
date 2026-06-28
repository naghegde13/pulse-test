package com.pulse.pipeline.controller;

import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.service.BlueprintInstanceConfigurationService;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.pipeline.service.CompositionService.AddInstanceResult;
import com.pulse.pipeline.service.CompositionService.CompositionView;
import com.pulse.pipeline.service.CompositionService.UpdateInstanceResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/versions/{versionId}/composition")
public class CompositionController {

    private final CompositionService compositionService;

    public CompositionController(CompositionService compositionService) {
        this.compositionService = compositionService;
    }

    @GetMapping
    public ResponseEntity<CompositionView> getComposition(@PathVariable String versionId) {
        return ResponseEntity.ok(compositionService.getComposition(versionId));
    }

    /**
     * Canonical add (ARCH-010). Accepts top-level {@code storageBackend},
     * {@code lakeLayer}, {@code lakeFormat}; mirrored {@code params} keys are
     * stripped server-side and surfaced as deprecations on the response.
     */
    @PostMapping("/instances")
    public ResponseEntity<SubPipelineInstanceResponse> addInstance(
            @PathVariable String versionId,
            @RequestBody AddInstanceRequest request) {
        AddInstanceResult result = compositionService.addInstance(
                request.pipelineId(), versionId,
                request.blueprintKey(), request.name(), request.params(),
                request.storageBackend(), request.lakeLayer(), request.lakeFormat());
        return ResponseEntity.ok(SubPipelineInstanceResponse.from(result.instance(), result.resolution()));
    }

    @DeleteMapping("/instances/{instanceId}")
    public ResponseEntity<Void> removeInstance(
            @PathVariable String versionId,
            @PathVariable String instanceId) {
        compositionService.removeInstance(versionId, instanceId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/instances/reorder")
    public ResponseEntity<List<SubPipelineInstance>> reorder(
            @PathVariable String versionId,
            @RequestBody ReorderRequest request) {
        return ResponseEntity.ok(compositionService.reorder(versionId, request.orderedInstanceIds()));
    }

    /**
     * Transition-only legacy endpoint (ARCH-010). Accepts a raw params map,
     * extracts mirrored canonical keys server-side, and surfaces the list of
     * extracted keys via {@code X-Pulse-Deprecated-Params-Fields} for client
     * migration tracking. Prefer the canonical PUT below for new clients.
     */
    @PutMapping("/instances/{instanceId}/params")
    public ResponseEntity<SubPipelineInstance> updateParams(
            @PathVariable String versionId,
            @PathVariable String instanceId,
            @RequestBody Map<String, Object> params) {
        UpdateInstanceResult result = compositionService.updateInstance(
                versionId, instanceId, params, null, null, null);
        HttpHeaders headers = new HttpHeaders();
        List<String> deprecated = result.resolution().deprecations();
        if (!deprecated.isEmpty()) {
            headers.set("X-Pulse-Deprecated-Params-Fields", String.join(",", deprecated));
        }
        return new ResponseEntity<>(result.instance(), headers, org.springframework.http.HttpStatus.OK);
    }

    /**
     * Canonical update (ARCH-010). Accepts top-level canonical fields along
     * with the generic params map. Omitted top-level fields preserve the
     * persisted value.
     */
    @PutMapping("/instances/{instanceId}")
    public ResponseEntity<SubPipelineInstanceResponse> updateInstance(
            @PathVariable String versionId,
            @PathVariable String instanceId,
            @RequestBody UpdateInstanceRequest request) {
        UpdateInstanceResult result = compositionService.updateInstance(
                versionId, instanceId, request.params(),
                request.storageBackend(), request.lakeLayer(), request.lakeFormat());
        return ResponseEntity.ok(SubPipelineInstanceResponse.from(result.instance(), result.resolution()));
    }

    @PostMapping("/wirings")
    public ResponseEntity<PortWiring> wirePort(
            @PathVariable String versionId,
            @RequestBody WireRequest request) {
        PortWiring wiring = compositionService.wirePort(versionId,
                request.sourceInstanceId(), request.sourcePortName(),
                request.targetInstanceId(), request.targetPortName());
        return ResponseEntity.ok(wiring);
    }

    @DeleteMapping("/wirings/{wiringId}")
    public ResponseEntity<Void> unwire(
            @PathVariable String versionId,
            @PathVariable String wiringId) {
        compositionService.unwire(versionId, wiringId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/instances/{instanceId}/schema")
    public ResponseEntity<SubPipelineInstance> updateSchema(
            @PathVariable String versionId,
            @PathVariable String instanceId,
            @RequestBody Map<String, Object> outputSchema) {
        return ResponseEntity.ok(compositionService.updateInstanceSchema(versionId, instanceId, outputSchema));
    }

    @GetMapping("/instances/{instanceId}/upstream-schema")
    public ResponseEntity<Map<String, Object>> getUpstreamSchema(
            @PathVariable String versionId,
            @PathVariable String instanceId) {
        return ResponseEntity.ok(compositionService.getUpstreamSchema(versionId, instanceId));
    }

    public record AddInstanceRequest(
            String pipelineId,
            String blueprintKey,
            String name,
            Map<String, Object> params,
            // ARCH-010 canonical fields. Optional on the wire; service falls back
            // to pipeline default for storageBackend when omitted.
            String storageBackend,
            String lakeLayer,
            String lakeFormat) {}

    public record UpdateInstanceRequest(
            Map<String, Object> params,
            String storageBackend,
            String lakeLayer,
            String lakeFormat) {}

    public record ReorderRequest(List<String> orderedInstanceIds) {}

    public record WireRequest(String sourceInstanceId, String sourcePortName,
                              String targetInstanceId, String targetPortName) {}

    /**
     * Canonical add/update response (ARCH-010). Includes the persisted
     * instance plus deprecation metadata for clients still sending mirrored
     * params keys.
     */
    public record SubPipelineInstanceResponse(
            SubPipelineInstance instance,
            Map<String, Object> canonicalFields,
            List<String> deprecations,
            List<String> warnings) {

        public static SubPipelineInstanceResponse from(
                SubPipelineInstance instance,
                BlueprintInstanceConfigurationService.Resolution resolution) {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("storageBackend", resolution.storageBackend());
            canonical.put("lakeLayer", resolution.lakeLayer());
            canonical.put("lakeFormat", resolution.lakeFormat());
            return new SubPipelineInstanceResponse(
                    instance, canonical, resolution.deprecations(), resolution.warnings());
        }
    }
}
