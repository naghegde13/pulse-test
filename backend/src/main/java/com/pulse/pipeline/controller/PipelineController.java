package com.pulse.pipeline.controller;

import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineStage;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.service.PipelineService;
import com.pulse.pipeline.service.StoryGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;
    private final StoryGenerationService storyGenerationService;

    public PipelineController(PipelineService pipelineService,
                              StoryGenerationService storyGenerationService) {
        this.pipelineService = pipelineService;
        this.storyGenerationService = storyGenerationService;
    }

    @GetMapping
    public ResponseEntity<List<Pipeline>> listPipelines(
            @PathVariable String tenantId,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String domainId) {
        List<Pipeline> pipelines = (domainId != null && !domainId.isBlank())
                ? pipelineService.listByDomainId(tenantId, domainId)
                : (domain != null && !domain.isBlank())
                ? pipelineService.listByDomain(tenantId, domain)
                : pipelineService.listByTenant(tenantId);
        return ResponseEntity.ok(pipelines);
    }

    @GetMapping("/{pipelineId}")
    public ResponseEntity<Pipeline> getPipeline(
            @PathVariable String tenantId,
            @PathVariable String pipelineId) {
        return ResponseEntity.ok(pipelineService.get(tenantId, pipelineId));
    }

    @PostMapping
    public ResponseEntity<Pipeline> createPipeline(
            @PathVariable String tenantId,
            @Valid @RequestBody CreatePipelineRequest request) {
        Pipeline pipeline = pipelineService.create(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(pipeline);
    }

    @PutMapping("/{pipelineId}")
    public ResponseEntity<Pipeline> updatePipeline(
            @PathVariable String tenantId,
            @PathVariable String pipelineId,
            @Valid @RequestBody UpdatePipelineRequest request) {
        return ResponseEntity.ok(pipelineService.update(tenantId, pipelineId, request));
    }

    // --- Version endpoints ---

    @GetMapping("/{pipelineId}/versions")
    public ResponseEntity<List<PipelineVersion>> listVersions(
            @PathVariable String tenantId,
            @PathVariable String pipelineId) {
        pipelineService.get(tenantId, pipelineId);
        return ResponseEntity.ok(pipelineService.listVersions(pipelineId));
    }

    @GetMapping("/{pipelineId}/versions/{versionId}")
    public ResponseEntity<PipelineVersion> getVersion(
            @PathVariable String tenantId,
            @PathVariable String pipelineId,
            @PathVariable String versionId) {
        pipelineService.get(tenantId, pipelineId);
        return ResponseEntity.ok(pipelineService.getVersion(versionId));
    }

    @PostMapping("/{pipelineId}/versions")
    public ResponseEntity<PipelineVersion> createRevision(
            @PathVariable String tenantId,
            @PathVariable String pipelineId,
            @Valid @RequestBody CreateRevisionRequest request) {
        PipelineVersion version = pipelineService.createNewRevision(tenantId, pipelineId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(version);
    }

    @PostMapping("/{pipelineId}/versions/{versionId}/transition")
    public ResponseEntity<PipelineVersion> transitionStage(
            @PathVariable String tenantId,
            @PathVariable String pipelineId,
            @PathVariable String versionId,
            @Valid @RequestBody TransitionRequest request) {
        PipelineVersion version = pipelineService.transitionStage(
                tenantId, pipelineId, versionId, request.targetStage());
        return ResponseEntity.ok(version);
    }

    @PutMapping("/{pipelineId}/versions/{versionId}/orchestration")
    public ResponseEntity<PipelineVersion> updateOrchestration(
            @PathVariable String tenantId,
            @PathVariable String pipelineId,
            @PathVariable String versionId,
            @RequestBody UpdateOrchestrationRequest request) {
        return ResponseEntity.ok(
                pipelineService.updateOrchestration(tenantId, pipelineId, versionId, request)
        );
    }

    @PostMapping("/{pipelineId}/story")
    public ResponseEntity<Map<String, Object>> generateStory(
            @PathVariable String tenantId,
            @PathVariable String pipelineId,
            @RequestParam(required = false, defaultValue = "false") boolean regenerate) {
        Pipeline pipeline = pipelineService.get(tenantId, pipelineId);
        try {
            Map<String, Object> story = regenerate
                    ? storyGenerationService.regenerateStory(pipeline)
                    : storyGenerationService.generateStory(pipeline);
            return ResponseEntity.ok(story);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Story generation failed"));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getStats(@PathVariable String tenantId) {
        return ResponseEntity.ok(pipelineService.getStats(tenantId));
    }

    @DeleteMapping("/{pipelineId}")
    public ResponseEntity<Void> deletePipeline(
            @PathVariable String tenantId,
            @PathVariable String pipelineId) {
        pipelineService.delete(tenantId, pipelineId);
        return ResponseEntity.noContent().build();
    }

    public record CreatePipelineRequest(
            String name,
            String description,
            String domainName,
            String domainId,
            /** ARCH-010: required on create. Must be 'DPC' or 'GCP'. */
            String defaultStorageBackend) {}

    public record UpdatePipelineRequest(
            String name,
            String description,
            /**
             * ARCH-010: optional on update. Omit (null) to preserve existing
             * value; explicit empty string is rejected by the service. Must be
             * 'DPC' or 'GCP' when present. Does not rebase existing instances.
             */
            String defaultStorageBackend) {}

    public record TransitionRequest(PipelineStage targetStage) {}

    public record CreateRevisionRequest(
            String changeSummary) {}

    public record UpdateOrchestrationRequest(
            String scheduleCron,
            Boolean catchupEnabled,
            Integer maxActiveRuns,
            Boolean dependsOnPast,
            Map<String, Object> policyConfigs) {}
}
