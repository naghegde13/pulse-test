package com.pulse.pipeline.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.llm.LlmEndpointService;
import com.pulse.llm.LlmSurface;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.repository.DatasetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * AI-powered Data Quality readiness evaluation service.
 * <p>
 * Takes a pipeline version, sends all instances with their schemas,
 * configured expectations, and data classifications to an LLM,
 * and receives a DQ readiness score (0-100) with recommended expectations.
 */
@Service
public class DqReadinessService {

    private static final Logger log = LoggerFactory.getLogger(DqReadinessService.class);

    @Value("${pulse.schema-inference.api-key:${pulse.llm.api-key:}}")
    private String apiKey;

    @Value("${pulse.schema-inference.base-url:${OPENROUTER_BASE_URL:https://openrouter.ai/api/v1}}")
    private String baseUrl;

    @Value("${pulse.schema-inference.model:${SCHEMA_INFERENCE_MODEL:google/gemini-2.0-flash-001}}")
    private String model;

    private final ObjectMapper objectMapper;
    private final PipelineVersionRepository versionRepo;
    private final SubPipelineInstanceRepository instanceRepo;
    private final PortWiringRepository wiringRepo;
    private final DatasetRepository datasetRepo;

    @Autowired(required = false)
    private LlmEndpointService llmEndpointService;

    public DqReadinessService(ObjectMapper objectMapper,
                              PipelineVersionRepository versionRepo,
                              SubPipelineInstanceRepository instanceRepo,
                              PortWiringRepository wiringRepo,
                              DatasetRepository datasetRepo) {
        this.objectMapper = objectMapper;
        this.versionRepo = versionRepo;
        this.instanceRepo = instanceRepo;
        this.wiringRepo = wiringRepo;
        this.datasetRepo = datasetRepo;
    }

    /**
     * Evaluates DQ readiness for a pipeline version.
     *
     * @param versionId The pipeline version ID
     * @return A map containing "score" (Integer 0-100), "recommendations" (List), and "reasoning" (String)
     */
    public Map<String, Object> evaluate(String versionId) {
        PipelineVersion version = versionRepo.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineVersion", versionId));

        List<SubPipelineInstance> instances = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId);

        if (!isLlmConfigured()) {
            log.warn("DQ readiness evaluation skipped: no API key configured");
            return Map.of(
                    "score", 0,
                    "recommendations", List.of(),
                    "reasoning", "LLM API key not configured. Cannot evaluate DQ readiness."
            );
        }

        try {
            List<PortWiring> wirings = wiringRepo.findByVersionIdOrderByCreatedAtAsc(versionId);
            // Collect dataset classifications referenced by ingestion steps
            Map<String, String> datasetClassifications = collectDatasetClassifications(instances);
            String prompt = buildPrompt(instances, wirings, datasetClassifications);
            String response = callLLM(prompt);
            Map<String, Object> result = parseResponse(response);

            // Persist the score on the pipeline version
            Object scoreObj = result.get("score");
            if (scoreObj instanceof Number) {
                int score = ((Number) scoreObj).intValue();
                score = Math.max(0, Math.min(100, score));
                version.setDqReadinessScore(score);
                versionRepo.save(version);
                result.put("score", score);
            }

            return result;
        } catch (Exception e) {
            log.error("DQ readiness evaluation failed for version {}: {}", versionId, e.getMessage());
            return Map.of(
                    "score", 0,
                    "recommendations", List.of(),
                    "reasoning", "Evaluation failed: " + e.getMessage()
            );
        }
    }

    /**
     * Returns the current DQ readiness score for a pipeline version (from the database).
     */
    public Map<String, Object> getScore(String versionId) {
        PipelineVersion version = versionRepo.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineVersion", versionId));
        Integer score = version.getDqReadinessScore();
        return Map.of(
                "versionId", versionId,
                "score", score != null ? score : -1,
                "evaluated", score != null
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> collectDatasetClassifications(List<SubPipelineInstance> instances) {
        Map<String, String> classifications = new HashMap<>();
        for (var inst : instances) {
            if (inst.getParams() != null && inst.getParams().containsKey("dataset_ids")) {
                Object dsIds = inst.getParams().get("dataset_ids");
                List<String> ids = dsIds instanceof List ? (List<String>) dsIds : List.of(dsIds.toString());
                for (String dsId : ids) {
                    try {
                        Dataset ds = datasetRepo.findById(dsId).orElse(null);
                        if (ds != null && ds.getClassification() != null) {
                            classifications.put(ds.getQualifiedName(), ds.getClassification());
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return classifications;
    }

    String buildPrompt(List<SubPipelineInstance> instances, List<PortWiring> wirings,
                       Map<String, String> datasetClassifications) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(SYSTEM_PROMPT);

            // DAG topology
            if (!wirings.isEmpty()) {
                sb.append("\n\n## Pipeline Wiring (DAG topology):\n");
                Map<String, String> instanceNames = new HashMap<>();
                for (var inst : instances) instanceNames.put(inst.getId(), inst.getName());
                for (var w : wirings) {
                    String src = instanceNames.getOrDefault(w.getSourceInstanceId(), w.getSourceInstanceId());
                    String tgt = instanceNames.getOrDefault(w.getTargetInstanceId(), w.getTargetInstanceId());
                    sb.append("- ").append(src).append(" (").append(w.getSourcePortName())
                      .append(") → ").append(tgt).append(" (").append(w.getTargetPortName()).append(")\n");
                }
            }

            // Dataset classifications
            if (!datasetClassifications.isEmpty()) {
                sb.append("\n\n## Data Classifications:\n");
                for (var entry : datasetClassifications.entrySet()) {
                    sb.append("- ").append(entry.getKey()).append(": **").append(entry.getValue()).append("**\n");
                }
            }

            sb.append("\n\n## Pipeline Steps:\n\n");
            for (var inst : instances) {
                sb.append("### Step ").append(inst.getExecutionOrder()).append(": ").append(inst.getName());
                sb.append(" [").append(inst.getBlueprintKey()).append("]\n");

                if (inst.getOutputSchema() != null && !inst.getOutputSchema().isEmpty()) {
                    sb.append("- Output Schema: ```json\n");
                    sb.append(objectMapper.writeValueAsString(inst.getOutputSchema()));
                    sb.append("\n```\n");
                }

                if (inst.getParams() != null && !inst.getParams().isEmpty()) {
                    sb.append("- Params: ```json\n");
                    sb.append(objectMapper.writeValueAsString(inst.getParams()));
                    sb.append("\n```\n");
                }

                if (inst.getDqExpectations() != null && !inst.getDqExpectations().isEmpty()) {
                    sb.append("- Existing DQ Expectations: ```json\n");
                    sb.append(objectMapper.writeValueAsString(inst.getDqExpectations()));
                    sb.append("\n```\n");
                } else {
                    sb.append("- DQ Expectations: **NONE CONFIGURED**\n");
                }

                sb.append("\n");
            }

            sb.append("\n## Output:\n");
            sb.append("Return ONLY valid JSON in the required format. No explanation, no markdown fences.");
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build DQ readiness prompt", e);
        }
    }

    private String callLLM(String userPrompt) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelFor());
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", "You are a data quality assessment engine. You ONLY output valid JSON."),
                Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", 4000);

        HttpURLConnection conn = openLlmConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(objectMapper.writeValueAsBytes(requestBody));
        }

        int status = conn.getResponseCode();
        InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            String raw = sb.toString();

            if (status >= 400) {
                throw new RuntimeException("LLM API returned " + status + ": " + raw);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> response = objectMapper.readValue(raw, Map.class);
            @SuppressWarnings("unchecked")
            var choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("No choices in LLM response");
            }
            @SuppressWarnings("unchecked")
            var message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        }
    }

    private boolean isLlmConfigured() {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.DQ_READINESS)) {
            return llmEndpointService.isConfigured(LlmSurface.DQ_READINESS);
        }
        return apiKey != null && !apiKey.isBlank();
    }

    private String modelFor() {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.DQ_READINESS)) {
            return llmEndpointService.model(LlmSurface.DQ_READINESS);
        }
        return model;
    }

    private HttpURLConnection openLlmConnection() throws IOException {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.DQ_READINESS)) {
            return llmEndpointService.openChatCompletionsConnection(LlmSurface.DQ_READINESS, "PULSE DQ Readiness");
        }
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/chat/completions").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("HTTP-Referer", "https://pulse.app");
        conn.setRequestProperty("X-Title", "PULSE DQ Readiness");
        conn.setDoOutput(true);
        return conn;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parseResponse(String response) {
        if (response == null || response.isBlank()) {
            return Map.of("score", 0, "recommendations", List.of(), "reasoning", "Empty LLM response");
        }

        String cleaned = response.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```\\s*$", "");
        }

        try {
            Map<String, Object> result = objectMapper.readValue(cleaned, new TypeReference<>() {});

            // Ensure required keys exist
            if (!result.containsKey("score")) result.put("score", 0);
            if (!result.containsKey("recommendations")) result.put("recommendations", List.of());
            if (!result.containsKey("reasoning")) result.put("reasoning", "");

            return result;
        } catch (Exception e) {
            log.error("Failed to parse DQ readiness response: {} -- raw: {}", e.getMessage(), cleaned);
            return Map.of("score", 0, "recommendations", List.of(), "reasoning", "Failed to parse LLM response: " + e.getMessage());
        }
    }

    static final String SYSTEM_PROMPT = """
You are a Data Quality readiness assessment engine for PULSE, a data pipeline builder.

Given a pipeline's steps (with their blueprint types, output schemas, configured parameters, and any existing DQ expectations), evaluate the pipeline's Data Quality readiness.

## Scoring Criteria (0-100):

1. **Coverage (0-30 points):** What percentage of output columns across all steps have at least one DQ expectation?
   - 0% coverage = 0 points, 100% coverage = 30 points

2. **Criticality (0-25 points):** Are high-risk columns covered?
   - ID/key columns should have not-null + uniqueness checks
   - Columns with names like "email", "phone", "ssn", "amount", "price" should have format/range checks
   - PII-tagged columns should have masking verification
   - Financial columns should have mandatory range + not-null

3. **Completeness (0-25 points):** Are there checks at multiple pipeline stages?
   - Ingestion step checks (schema validation, freshness)
   - Mid-pipeline checks (row count sanity after transforms)
   - Pre-sink checks (final data quality gate)

4. **Domain Fit (0-20 points):** Do the expectations match the data domain?
   - Email columns -> regex pattern validation
   - Amount/price columns -> range validation (min >= 0)
   - Date/timestamp columns -> freshness/recency checks
   - After aggregations -> row count sanity checks
   - After joins -> referential integrity checks

## Recommendations:
For each pipeline step, suggest GX expectations that would improve the score. Use actual GX 1.x expectation class names.

## Output Format (JSON only):
```json
{
  "score": 75,
  "recommendations": [
    {
      "instance_name": "Step Name",
      "expectations": [
        {
          "type": "ExpectColumnValuesToNotBeNull",
          "kwargs": {"column": "customer_id"},
          "severity": "critical",
          "reason": "Primary key column should never be null"
        }
      ]
    }
  ],
  "reasoning": "Brief explanation of the score and key gaps."
}
```

Do NOT include any explanation outside the JSON. ONLY the JSON object.
""";
}
