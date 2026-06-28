package com.pulse.pipeline.controller;

import com.pulse.pipeline.service.SchemaInferenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/schema-inference")
public class SchemaInferenceController {

    private final SchemaInferenceService schemaInferenceService;

    public SchemaInferenceController(SchemaInferenceService schemaInferenceService) {
        this.schemaInferenceService = schemaInferenceService;
    }

    @PostMapping("/infer")
    public ResponseEntity<Map<String, Object>> infer(@RequestBody InferRequest request) {
        Map<String, Object> result = schemaInferenceService.inferOutputSchema(
                request.blueprintKey(),
                request.inputSchema(),
                request.secondarySchema(),
                request.params());
        if (result == null) {
            return ResponseEntity.unprocessableEntity().build();
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/invalidate-cache")
    public ResponseEntity<Void> invalidateCache() {
        schemaInferenceService.invalidateCache();
        return ResponseEntity.noContent().build();
    }

    record InferRequest(
            String blueprintKey,
            Map<String, Object> inputSchema,
            Map<String, Object> secondarySchema,
            Map<String, Object> params) {}
}
