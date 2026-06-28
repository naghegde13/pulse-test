package com.pulse.pipeline.controller;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.OrchestrationNamespaceService;
import com.pulse.pipeline.service.OrchestrationNamespaceService.OrchestrationNamespace;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OrchestrationNamespaceController {

    private final OrchestrationNamespaceService namespaceService;
    private final PipelineRepository pipelineRepository;

    public OrchestrationNamespaceController(OrchestrationNamespaceService namespaceService,
                                             PipelineRepository pipelineRepository) {
        this.namespaceService = namespaceService;
        this.pipelineRepository = pipelineRepository;
    }

    @GetMapping("/pipelines/{pipelineId}/orchestration-namespace-preview")
    public ResponseEntity<OrchestrationNamespace> previewFromPipeline(
            @PathVariable String pipelineId) {
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", pipelineId));
        OrchestrationNamespace namespace = namespaceService.resolve(pipeline);
        return ResponseEntity.ok(namespace);
    }

    @PostMapping("/orchestration-namespace/preview")
    public ResponseEntity<OrchestrationNamespace> previewFromRequest(
            @RequestBody PreviewRequest request) {
        OrchestrationNamespace namespace = namespaceService.preview(
                request.tenantId(), request.domainName(), request.proposedPipelineName());
        return ResponseEntity.ok(namespace);
    }

    public record PreviewRequest(String tenantId, String domainName, String proposedPipelineName) {
    }
}
