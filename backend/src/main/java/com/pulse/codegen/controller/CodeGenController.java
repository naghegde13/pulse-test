package com.pulse.codegen.controller;

import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.service.CodeGenerationService;
import com.pulse.common.exception.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class CodeGenController {

    private final CodeGenerationService codeGenService;
    private final GeneratedArtifactRepository artifactRepo;

    public CodeGenController(CodeGenerationService codeGenService,
                              GeneratedArtifactRepository artifactRepo) {
        this.codeGenService = codeGenService;
        this.artifactRepo = artifactRepo;
    }

    @PostMapping("/api/v1/versions/{versionId}/generate")
    public ResponseEntity<GenerationRun> generate(
            @PathVariable String versionId,
            @RequestBody GenerateRequest request) {
        GenerationRun run = codeGenService.generate(
                request.pipelineId(), versionId, request.tenantId(), request.userId());
        return ResponseEntity.ok(run);
    }

    @GetMapping("/api/v1/versions/{versionId}/generations")
    public ResponseEntity<List<GenerationRun>> listRuns(@PathVariable String versionId) {
        return ResponseEntity.ok(codeGenService.listRuns(versionId));
    }

    @GetMapping("/api/v1/generations/{runId}/artifacts")
    public ResponseEntity<List<GeneratedArtifact>> getArtifacts(@PathVariable String runId) {
        return ResponseEntity.ok(codeGenService.getArtifacts(runId));
    }

    @GetMapping("/api/v1/artifacts/{artifactId}")
    public ResponseEntity<GeneratedArtifact> getArtifact(@PathVariable String artifactId) {
        return ResponseEntity.ok(artifactRepo.findById(artifactId)
                .orElseThrow(() -> new ResourceNotFoundException("GeneratedArtifact", artifactId)));
    }

    @PutMapping("/api/v1/artifacts/{artifactId}/content")
    public ResponseEntity<GeneratedArtifact> updateContent(
            @PathVariable String artifactId,
            @RequestBody Map<String, String> body) {
        GeneratedArtifact artifact = artifactRepo.findById(artifactId)
                .orElseThrow(() -> new ResourceNotFoundException("GeneratedArtifact", artifactId));
        artifact.setContent(body.get("content"));
        artifact.setManuallyModified(true);
        return ResponseEntity.ok(artifactRepo.save(artifact));
    }

    record GenerateRequest(String pipelineId, String tenantId, String userId) {}
}
