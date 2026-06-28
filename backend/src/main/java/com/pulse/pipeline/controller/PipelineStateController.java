package com.pulse.pipeline.controller;

import com.pulse.pipeline.model.PipelineState;
import com.pulse.pipeline.service.PipelineStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pipelines/{pipelineId}/instances/{instanceId}/state")
public class PipelineStateController {

    private final PipelineStateService service;

    public PipelineStateController(PipelineStateService service) {
        this.service = service;
    }

    @GetMapping("/{stateKey}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String pipelineId,
                                                   @PathVariable String instanceId,
                                                   @PathVariable String stateKey) {
        return service.get(pipelineId, instanceId, stateKey)
                .map(state -> ResponseEntity.ok(toPayload(state)))
                .orElseGet(() -> ResponseEntity.status(404).body(notFoundPayload(pipelineId, instanceId, stateKey)));
    }

    @PutMapping("/{stateKey}")
    public ResponseEntity<Map<String, Object>> upsert(@PathVariable String pipelineId,
                                                     @PathVariable String instanceId,
                                                     @PathVariable String stateKey,
                                                     @RequestBody UpsertRequest request) {
        PipelineState state = service.upsert(pipelineId, instanceId, stateKey,
                request != null ? request.stateValue() : Map.of());
        return ResponseEntity.ok(toPayload(state));
    }

    @DeleteMapping("/{stateKey}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String pipelineId,
                                                     @PathVariable String instanceId,
                                                     @PathVariable String stateKey) {
        boolean removed = service.delete(pipelineId, instanceId, stateKey);
        if (!removed) {
            return ResponseEntity.status(404).body(notFoundPayload(pipelineId, instanceId, stateKey));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@PathVariable String pipelineId,
                                                   @PathVariable String instanceId) {
        List<PipelineState> rows = service.listByInstance(pipelineId, instanceId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipelineId", pipelineId);
        payload.put("instanceId", instanceId);
        payload.put("entries", rows.stream().map(this::toEntryPayload).toList());
        return ResponseEntity.ok(payload);
    }

    private Map<String, Object> toPayload(PipelineState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipelineId", state.getPipelineId());
        payload.put("instanceId", state.getInstanceId());
        payload.put("stateKey", state.getStateKey());
        payload.put("stateValue", state.getStateValue() != null ? state.getStateValue() : Map.of());
        Instant updatedAt = state.getUpdatedAt();
        payload.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
        return payload;
    }

    private Map<String, Object> toEntryPayload(PipelineState state) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stateKey", state.getStateKey());
        entry.put("stateValue", state.getStateValue() != null ? state.getStateValue() : Map.of());
        Instant updatedAt = state.getUpdatedAt();
        entry.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
        return entry;
    }

    private Map<String, Object> notFoundPayload(String pipelineId, String instanceId, String stateKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", "state not found");
        payload.put("pipelineId", pipelineId);
        payload.put("instanceId", instanceId);
        payload.put("stateKey", stateKey);
        return payload;
    }

    public record UpsertRequest(Map<String, Object> stateValue) {}
}
