package com.pulse.pipeline.controller;

import com.pulse.pipeline.model.DqValidationResult;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.DqValidationResultRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.pipeline.service.DqExpectationService;
import com.pulse.pipeline.service.DqReadinessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class DqController {

    private final DqReadinessService dqReadinessService;
    private final DqValidationResultRepository dqResultRepo;
    private final DqExpectationService dqExpectationService;
    private final SubPipelineInstanceRepository instanceRepo;

    public DqController(DqReadinessService dqReadinessService,
                        DqValidationResultRepository dqResultRepo,
                        DqExpectationService dqExpectationService,
                        SubPipelineInstanceRepository instanceRepo) {
        this.dqReadinessService = dqReadinessService;
        this.dqResultRepo = dqResultRepo;
        this.dqExpectationService = dqExpectationService;
        this.instanceRepo = instanceRepo;
    }

    /**
     * Triggers DQ readiness evaluation via LLM. Returns score + recommendations.
     */
    @PostMapping("/api/v1/versions/{versionId}/dq/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@PathVariable String versionId) {
        Map<String, Object> result = dqReadinessService.evaluate(versionId);
        return ResponseEntity.ok(result);
    }

    /**
     * Returns the current DQ readiness score for a pipeline version.
     */
    @GetMapping("/api/v1/versions/{versionId}/dq/score")
    public ResponseEntity<Map<String, Object>> getScore(@PathVariable String versionId) {
        Map<String, Object> result = dqReadinessService.getScore(versionId);
        return ResponseEntity.ok(result);
    }

    /**
     * Saves configured DQ expectations for a specific sub-pipeline instance.
     * ARCH-013 parity: routes through {@link DqExpectationService}, which is
     * also the authority used by the chat {@code apply_dq_expectations} tool,
     * so panel and chat persist to the same canonical
     * {@code SubPipelineInstance.dqExpectations} column.
     */
    @PutMapping("/api/v1/versions/{versionId}/instances/{instanceId}/dq-expectations")
    public ResponseEntity<SubPipelineInstance> saveDqExpectations(
            @PathVariable String versionId,
            @PathVariable String instanceId,
            @RequestBody DqExpectationsRequest request) {
        SubPipelineInstance saved = dqExpectationService.save(
                versionId, instanceId, request.expectations());
        return ResponseEntity.ok(saved);
    }

    /**
     * Lists validation results for all instances in a pipeline version.
     */
    @GetMapping("/api/v1/versions/{versionId}/dq/results")
    public ResponseEntity<List<DqValidationResult>> getResults(@PathVariable String versionId) {
        List<DqValidationResult> results = dqResultRepo.findByVersionId(versionId);
        return ResponseEntity.ok(results);
    }

    /**
     * LCT-035: Returns AI-generated DQ suggestions for a specific sub-pipeline
     * instance. The same reasoning path the chat {@code suggest_dq_expectations}
     * tool uses, exposed as a REST endpoint for the panel UI's "Suggest DQ rules"
     * button. Returns structured suggestions ({@code type}, {@code kwargs},
     * {@code severity}, {@code reason}) ready to merge into the expectation list.
     */
    @GetMapping("/api/v1/versions/{versionId}/instances/{instanceId}/dq/suggestions")
    public ResponseEntity<Map<String, Object>> suggestDq(
            @PathVariable String versionId,
            @PathVariable String instanceId) {
        Map<String, Object> result = dqReadinessService.evaluate(versionId);
        // Extract recommendations for the specific instance.
        Object recommendations = result.get("recommendations");
        List<Map<String, Object>> suggestions = new java.util.ArrayList<>();
        if (recommendations instanceof List<?> recList) {
            SubPipelineInstance target = instanceRepo.findById(instanceId).orElse(null);
            String targetName = target != null ? target.getName() : instanceId;
            for (Object rec : recList) {
                if (rec instanceof Map<?, ?> recMap) {
                    String recName = String.valueOf(recMap.get("instance_name"));
                    if (recName.equals(targetName) || recName.equals(instanceId)) {
                        Object exps = recMap.get("expectations");
                        if (exps instanceof List<?> expList) {
                            for (Object exp : expList) {
                                if (exp instanceof Map<?, ?> expMap) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> typed = (Map<String, Object>) expMap;
                                    suggestions.add(typed);
                                }
                            }
                        }
                    }
                }
            }
        }
        return ResponseEntity.ok(Map.of("suggestions", suggestions));
    }

    record DqExpectationsRequest(List<Map<String, Object>> expectations) {}
}
