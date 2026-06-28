package com.pulse.pipeline.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.chat.model.ChatMessage;
import com.pulse.chat.model.ChatSession;
import com.pulse.chat.repository.ChatMessageRepository;
import com.pulse.chat.repository.ChatSessionRepository;
import com.pulse.llm.LlmEndpointService;
import com.pulse.llm.LlmSurface;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
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

@Service
public class StoryGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StoryGenerationService.class);
    private static final int MAX_TRANSCRIPT_CHARS = 32_000;

    private final PipelineVersionRepository versionRepo;
    private final SubPipelineInstanceRepository instanceRepo;
    private final PortWiringRepository wiringRepo;
    private final BlueprintRepository blueprintRepo;
    private final ChatSessionRepository chatSessionRepo;
    private final ChatMessageRepository chatMessageRepo;
    private final ObjectMapper objectMapper;

    @Value("${pulse.llm.api-key:}")
    private String apiKey;

    @Value("${pulse.llm.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${pulse.llm.model:openai/gpt-5.2}")
    private String model;

    @Autowired(required = false)
    private LlmEndpointService llmEndpointService;

    public StoryGenerationService(PipelineVersionRepository versionRepo,
                                  SubPipelineInstanceRepository instanceRepo,
                                  PortWiringRepository wiringRepo,
                                  BlueprintRepository blueprintRepo,
                                  ChatSessionRepository chatSessionRepo,
                                  ChatMessageRepository chatMessageRepo,
                                  ObjectMapper objectMapper) {
        this.versionRepo = versionRepo;
        this.instanceRepo = instanceRepo;
        this.wiringRepo = wiringRepo;
        this.blueprintRepo = blueprintRepo;
        this.chatSessionRepo = chatSessionRepo;
        this.chatMessageRepo = chatMessageRepo;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateStory(Pipeline pipeline) throws Exception {
        PipelineVersion version = null;
        if (pipeline.getActiveVersionId() != null) {
            version = versionRepo.findById(pipeline.getActiveVersionId()).orElse(null);
        }
        if (version == null) {
            List<PipelineVersion> versions = versionRepo.findByPipelineIdOrderByCreatedAtDesc(pipeline.getId());
            if (!versions.isEmpty()) version = versions.get(0);
        }

        // Check cache on version metadata
        if (version != null && version.getMetadata() != null) {
            Object cached = version.getMetadata().get("generatedStory");
            if (cached instanceof Map) {
                log.info("Returning cached story for pipeline {}", pipeline.getId());
                return (Map<String, Object>) cached;
            }
        }

        String prompt = buildPrompt(pipeline, version);
        String llmResponse = callLLM(prompt);
        Map<String, Object> story = parseResponse(llmResponse);

        // Cache on version metadata
        if (version != null) {
            Map<String, Object> metadata = version.getMetadata() != null
                    ? new HashMap<>(version.getMetadata()) : new HashMap<>();
            metadata.put("generatedStory", story);
            version.setMetadata(metadata);
            versionRepo.save(version);
        }

        return story;
    }

    public Map<String, Object> regenerateStory(Pipeline pipeline) throws Exception {
        // Clear cache first
        PipelineVersion version = null;
        if (pipeline.getActiveVersionId() != null) {
            version = versionRepo.findById(pipeline.getActiveVersionId()).orElse(null);
        }
        if (version == null) {
            List<PipelineVersion> versions = versionRepo.findByPipelineIdOrderByCreatedAtDesc(pipeline.getId());
            if (!versions.isEmpty()) version = versions.get(0);
        }
        if (version != null && version.getMetadata() != null) {
            Map<String, Object> metadata = new HashMap<>(version.getMetadata());
            metadata.remove("generatedStory");
            version.setMetadata(metadata);
            versionRepo.save(version);
        }
        return generateStory(pipeline);
    }

    private String buildPrompt(Pipeline pipeline, PipelineVersion version) {
        StringBuilder sb = new StringBuilder();

        // Pipeline metadata section
        sb.append("## Ground Truth — Pipeline Metadata (what is actually configured)\n\n");
        sb.append("**Pipeline Name:** ").append(pipeline.getName()).append("\n");
        sb.append("**Domain:** ").append(pipeline.getDomainName()).append("\n");
        if (pipeline.getDescription() != null) {
            sb.append("**Description:** ").append(pipeline.getDescription()).append("\n");
        }

        if (version != null) {
            sb.append("**Revision:** #").append(version.getRevision()).append("\n");
            sb.append("**Lifecycle Stage:** ").append(version.getLifecycleStage()).append("\n");
            if (version.getScheduleCron() != null) {
                sb.append("**Schedule:** ").append(version.getScheduleCron()).append("\n");
            }
            if (version.getSlaConfig() != null && !version.getSlaConfig().isEmpty()) {
                try {
                    sb.append("**SLA Config:** ").append(objectMapper.writeValueAsString(version.getSlaConfig())).append("\n");
                } catch (Exception ignored) {}
            }
            if (version.getChangeSummary() != null) {
                sb.append("**Change Summary:** ").append(version.getChangeSummary()).append("\n");
            }

            // Composition details
            List<SubPipelineInstance> instances = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(version.getId());
            List<PortWiring> wirings = wiringRepo.findByVersionIdOrderByCreatedAtAsc(version.getId());

            if (!instances.isEmpty()) {
                sb.append("\n### Pipeline Composition (Blueprint Steps)\n\n");
                sb.append("| # | Step Name | Blueprint | Parameters |\n");
                sb.append("|---|-----------|-----------|------------|\n");
                for (SubPipelineInstance inst : instances) {
                    String bpName = inst.getBlueprintKey() != null ? inst.getBlueprintKey() : inst.getBlueprintId();
                    Blueprint bp = blueprintRepo.findByBlueprintKey(bpName).orElse(null);
                    String bpDesc = bp != null ? bp.getDescription() : "";
                    String paramsStr = "";
                    try {
                        if (inst.getParams() != null && !inst.getParams().isEmpty()) {
                            paramsStr = objectMapper.writeValueAsString(inst.getParams());
                            if (paramsStr.length() > 300) paramsStr = paramsStr.substring(0, 300) + "...";
                        }
                    } catch (Exception ignored) {}
                    sb.append("| ").append(inst.getExecutionOrder())
                      .append(" | ").append(inst.getName())
                      .append(" | ").append(bpName)
                      .append(" | `").append(paramsStr.replace("|", "\\|")).append("` |\n");

                    if (bp != null && bpDesc != null && !bpDesc.isEmpty()) {
                        sb.append("\n> **").append(bpName).append("**: ").append(bpDesc).append("\n\n");
                    }

                    // Input/output datasets
                    if (inst.getInputDatasets() != null && !inst.getInputDatasets().isEmpty()) {
                        try {
                            sb.append("  - Input datasets: ").append(objectMapper.writeValueAsString(inst.getInputDatasets())).append("\n");
                        } catch (Exception ignored) {}
                    }
                    if (inst.getOutputDatasets() != null && !inst.getOutputDatasets().isEmpty()) {
                        try {
                            sb.append("  - Output datasets: ").append(objectMapper.writeValueAsString(inst.getOutputDatasets())).append("\n");
                        } catch (Exception ignored) {}
                    }
                    if (inst.getOutputSchema() != null && !inst.getOutputSchema().isEmpty()) {
                        try {
                            sb.append("  - Output schema: ").append(objectMapper.writeValueAsString(inst.getOutputSchema())).append("\n");
                        } catch (Exception ignored) {}
                    }
                    if (inst.getDqExpectations() != null && !inst.getDqExpectations().isEmpty()) {
                        try {
                            sb.append("  - DQ expectations: ").append(objectMapper.writeValueAsString(inst.getDqExpectations())).append("\n");
                        } catch (Exception ignored) {}
                    }
                }

                if (!wirings.isEmpty()) {
                    sb.append("\n### Data Flow (Port Wirings)\n\n");
                    sb.append("| Source Step | Source Port | Target Step | Target Port |\n");
                    sb.append("|------------|------------|-------------|-------------|\n");
                    Map<String, String> instanceNames = new HashMap<>();
                    for (SubPipelineInstance inst : instances) {
                        instanceNames.put(inst.getId(), inst.getName());
                    }
                    for (PortWiring w : wirings) {
                        sb.append("| ").append(instanceNames.getOrDefault(w.getSourceInstanceId(), w.getSourceInstanceId()))
                          .append(" | ").append(w.getSourcePortName())
                          .append(" | ").append(instanceNames.getOrDefault(w.getTargetInstanceId(), w.getTargetInstanceId()))
                          .append(" | ").append(w.getTargetPortName()).append(" |\n");
                    }
                }
            }
        }

        // Chat transcript section
        List<ChatSession> sessions = chatSessionRepo.findByPipelineIdOrderByUpdatedAtDesc(pipeline.getId());
        Collections.reverse(sessions); // oldest first
        if (!sessions.isEmpty()) {
            sb.append("\n\n## Conversation Context — Chat Transcript(s)\n\n");
            sb.append("NOTE: If requirements conflict between sessions, the LATER session supersedes earlier ones.\n\n");

            int totalChars = 0;
            for (int i = 0; i < sessions.size(); i++) {
                ChatSession cs = sessions.get(i);
                sb.append("--- Session ").append(i + 1).append(" (").append(cs.getCreatedAt()).append(") ---\n\n");

                List<ChatMessage> messages = chatMessageRepo.findBySessionIdOrderByCreatedAtAsc(cs.getId());
                for (ChatMessage msg : messages) {
                    String role = msg.getRole();
                    if ("TOOL".equalsIgnoreCase(role)) continue; // skip tool noise

                    String content = msg.getContent();
                    if (content == null || content.isBlank()) continue;

                    if (totalChars + content.length() > MAX_TRANSCRIPT_CHARS) {
                        sb.append("[...transcript truncated for length...]\n");
                        break;
                    }
                    sb.append("**").append(role).append("**: ").append(content).append("\n\n");
                    totalChars += content.length();
                }
                if (totalChars >= MAX_TRANSCRIPT_CHARS) break;
            }
        }

        return SYSTEM_PROMPT + "\n\n" + sb;
    }

    @SuppressWarnings("unchecked")
    private String callLLM(String prompt) throws Exception {
        if (!isLlmConfigured()) {
            throw new IllegalStateException("LLM API key not configured");
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelFor());
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", 0.3);

        HttpURLConnection conn = openLlmConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(objectMapper.writeValueAsBytes(requestBody));
        }

        int status = conn.getResponseCode();
        InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();

        String responseBody;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            responseBody = sb.toString();
        }

        if (status >= 400) {
            throw new RuntimeException("LLM API returned " + status + ": " + responseBody);
        }

        var parsed = objectMapper.readValue(responseBody, Map.class);
        var choices = (List<Map<String, Object>>) parsed.get("choices");
        if (choices != null && !choices.isEmpty()) {
            var message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        }
        throw new RuntimeException("No response from LLM");
    }

    private boolean isLlmConfigured() {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.STORY_GENERATION)) {
            return llmEndpointService.isConfigured(LlmSurface.STORY_GENERATION);
        }
        return apiKey != null && !apiKey.isBlank();
    }

    private String modelFor() {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.STORY_GENERATION)) {
            return llmEndpointService.model(LlmSurface.STORY_GENERATION);
        }
        return model;
    }

    private HttpURLConnection openLlmConnection() throws IOException {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.STORY_GENERATION)) {
            return llmEndpointService.openChatCompletionsConnection(LlmSurface.STORY_GENERATION, "PULSE Story Generation");
        }
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/chat/completions").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        return conn;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponse(String response) throws Exception {
        if (response == null || response.isBlank()) {
            throw new RuntimeException("Empty LLM response");
        }

        String cleaned = response.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```\\s*$", "");
        }

        return objectMapper.readValue(cleaned, new TypeReference<>() {});
    }

    static final String SYSTEM_PROMPT = """
You are a senior business analyst generating a comprehensive JIRA user story from a data pipeline that was built using an AI-assisted pipeline builder called PULSE.

Your output must be a single valid JSON object with these exact keys:

{
  "title": "Short JIRA summary line",
  "storyMarkdown": "Full user story in rich markdown (see format below)",
  "tasks": [
    {
      "title": "Task title",
      "description": "Detailed task description",
      "estimate": "S|M|L"
    }
  ]
}

## Story Markdown Format

The "storyMarkdown" field must contain a SINGLE well-formatted markdown document combining the user story, detailed requirements, and acceptance criteria. Be EXTREMELY VERBOSE and DETAILED. Capture EVERY element from the metadata and chat transcript. Use rich markdown formatting:

- Use **tables** for structured data (schemas, parameters, field mappings, file naming conventions, column lists)
- Use **headers** (##, ###) to organize sections clearly
- Use **bullet lists** for requirements
- Use **code blocks** for technical values (cron expressions, SQL, file patterns)
- Use **bold** for emphasis on key terms

The storyMarkdown must include ALL of these sections:

### 1. User Story Statement
"As a [role] I want [goal] So that [benefit]"

### 2. Overview
A paragraph summarizing the pipeline purpose, business context, and data flow end-to-end.

### 3. Data Sources
Table of every source dataset with: name, format, schema (all columns with types), classification, temporal metadata, file naming conventions, arrival schedule. Include EVERY field and column — do not summarize or abbreviate.

### 4. Pipeline Steps (Detailed)
For EACH blueprint step in the composition, a subsection with:
- Step name, blueprint type, execution order
- ALL configuration parameters in a table
- Business logic explanation (what this step does and WHY)
- Input/output schema changes (if schema info is available)
- Data quality expectations applied to this step

### 5. Data Flow
Description of how data flows from source through each step to destination, referencing the port wirings.

### 6. Output / Destination
Target destination details, output format, partitioning, file naming conventions.

### 7. Scheduling & SLA
Cron schedule, SLA requirements, catchup policy, dependency configuration — in a table.

### 8. Data Quality Requirements
All DQ expectations across all steps, organized by step, in a table with: expectation type, parameters, severity.

### 9. Acceptance Criteria
Numbered list in Given/When/Then format. Cover:
- Each data source is correctly ingested
- Each transformation produces expected output
- Data quality checks pass
- Output is delivered to destination in correct format
- Schedule executes as configured
- Error handling and retry behavior

## Task Generation Rules

IMPORTANT: The pipeline code is AUTO-GENERATED by PULSE. Do NOT create engineering/coding tasks. Instead create tasks for:

- **Configuration validation** — verify each step's parameters are correct for the business requirement
- **Testing per blueprint step** — validate each transform step produces expected output with sample data
- **End-to-end integration testing** — test the full pipeline flow from source to destination
- **Data quality validation** — verify DQ rules catch expected issues
- **UAT / business validation** — stakeholder sign-off on output data
- **Deployment verification** — confirm pipeline runs successfully in target environment
- **Documentation** — update runbook / data dictionary if needed
- **Monitoring setup** — alerts, dashboards for pipeline health

Each task should have a descriptive title, a detailed description, and an estimate (S = small/1-2 days, M = medium/3-5 days, L = large/1-2 weeks).

## Critical Rules
- Return ONLY the JSON object. No markdown fences around the JSON. No explanation outside the JSON.
- Be VERBOSE in storyMarkdown. More detail is always better. Capture every column, every parameter, every file pattern.
- If information is available in the metadata or transcript, it MUST appear in the story.
- The storyMarkdown must be valid markdown that renders beautifully with tables, headers, and formatting.
""";
}
