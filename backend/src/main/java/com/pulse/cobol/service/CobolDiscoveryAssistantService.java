package com.pulse.cobol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.cobol.model.CobolDiscoveryArtifact;
import com.pulse.cobol.model.CobolDiscoveryMessage;
import com.pulse.cobol.model.CobolDiscoveryRun;
import com.pulse.cobol.model.CobolParsingProfile;
import com.pulse.llm.LlmEndpointService;
import com.pulse.llm.LlmSurface;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CobolDiscoveryAssistantService {

    private static final Logger log = LoggerFactory.getLogger(CobolDiscoveryAssistantService.class);
    private static final Pattern JSON_FENCE_PATTERN = Pattern.compile("(?s)```(?:json)?\\s*(\\{.*?})\\s*```");
    private static final Pattern COBOL_FENCE_PATTERN = Pattern.compile("(?s)```(?:cobol|cob|cpy)\\s*(.*?)\\s*```");

    private final String knowledgeSummary;
    private final String exampleSummary;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String discoveryModel;
    private final LlmEndpointService llmEndpointService;

    @Autowired
    public CobolDiscoveryAssistantService(
            ObjectMapper objectMapper,
            @Value("${pulse.cobol-discovery.api-key:}") String discoveryApiKey,
            @Value("${pulse.llm.api-key:}") String llmApiKey,
            @Value("${pulse.llm.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${pulse.cobol-discovery.model:anthropic/claude-opus-4.6}") String discoveryModel,
            LlmEndpointService llmEndpointService) throws IOException {
        this(objectMapper, discoveryApiKey, llmApiKey, baseUrl, discoveryModel, llmEndpointService, true);
    }

    private CobolDiscoveryAssistantService(
            ObjectMapper objectMapper,
            String discoveryApiKey,
            String llmApiKey,
            String baseUrl,
            String discoveryModel,
            LlmEndpointService llmEndpointService,
            boolean ignored) throws IOException {
        this.knowledgeSummary = new ClassPathResource("cobol-discovery/patterns.md")
                .getContentAsString(StandardCharsets.UTF_8);
        this.exampleSummary = loadExampleSummary();
        this.objectMapper = objectMapper;
        this.apiKey = (discoveryApiKey != null && !discoveryApiKey.isBlank()) ? discoveryApiKey : llmApiKey;
        this.baseUrl = baseUrl;
        this.discoveryModel = discoveryModel;
        this.llmEndpointService = llmEndpointService;
    }

    // Backward-compat for ~29 existing test call-sites that pre-date the
    // discoveryApiKey parameter split. Tests pass "" or a sentinel as apiKey;
    // delegates with discoveryApiKey="" so the fallback in the canonical
    // constructor lands on the supplied apiKey value as llmApiKey.
    public CobolDiscoveryAssistantService(
            ObjectMapper objectMapper,
            String apiKey,
            String baseUrl,
            String discoveryModel) throws IOException {
        this(objectMapper, "", apiKey, baseUrl, discoveryModel, null, true);
    }

    public String initialGreeting() {
        return """
                I’m the EBCDIC Discovery Agent. Upload a COBOL copybook and an EBCDIC data file, then I’ll help you converge on a Cobrix configuration and a reusable COBOL parsing profile.

                I’ll validate the flattened output preview you care about, not just the hierarchical decode.
                """;
    }

    public ActionPlan planActions(
            String userMessage,
            Map<String, Object> currentOptionOverrides,
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryMessage> recentMessages,
            boolean hasCopybook,
            boolean hasDataFile) {
        String lower = userMessage == null ? "" : userMessage.toLowerCase();
        Map<String, Object> recommendedConfig = extractMostRecentRecommendedConfig(recentMessages);
        String recommendedCopybookText = extractMostRecentRecommendedCopybookText(recentMessages);
        String recommendedRunType = extractMostRecentRecommendedRunType(recentMessages);
        boolean referencesPriorRecommendation = containsAny(lower, "the way you described", "as you described", "the way you suggested");
        boolean wantsConfigUpdate = containsAny(lower,
                "update the config",
                "update config",
                "apply the config",
                "apply this config",
                "use this config",
                "use that config",
                "set the config",
                "change the config")
                || (referencesPriorRecommendation && !recommendedConfig.isEmpty());
        boolean wantsCopybookUpdate = containsAny(lower,
                "update the copybook",
                "update copybook",
                "fix the copybook",
                "fix copybook",
                "correct the copybook",
                "correct copybook",
                "apply the copybook",
                "apply that copybook",
                "replace the copybook",
                "revise the copybook",
                "revise copybook")
                || (referencesPriorRecommendation && !recommendedCopybookText.isBlank());
        boolean wantsPreviewRun = mentionsRun(lower) && (
                lower.contains("preview")
                        || lower.contains("discovery")
                        || lower.contains("rerun")
                        || lower.contains("re-run")
                        || lower.contains("kick off")
                        || lower.contains("run it")
                        || lower.contains("do it for me")
        );
        boolean wantsProfileRun = mentionsRun(lower) && (
                lower.contains("full profile")
                        || lower.contains("run profile")
                        || lower.contains("profiling")
                        || lower.contains("profile it")
        );
        boolean wantsPreviewResults = containsAny(lower,
                "retrieve preview results",
                "show preview results",
                "get preview results",
                "what are the preview results",
                "what do the preview results show",
                "current preview results",
                "see the preview results",
                "review the preview");

        if (!wantsPreviewRun && !wantsProfileRun && referencesPriorRecommendation) {
            wantsPreviewRun = "profile".equals(recommendedRunType) ? false : !recommendedConfig.isEmpty();
            wantsProfileRun = "profile".equals(recommendedRunType);
        }

        if (!wantsConfigUpdate && !wantsCopybookUpdate && !wantsPreviewRun && !wantsProfileRun && !wantsPreviewResults) {
            return ActionPlan.none();
        }

        Map<String, Object> extractedConfig = firstNonEmptyMap(
                extractConfigFromText(userMessage),
                recommendedConfig,
                extractMostRecentConfig(recentMessages),
                latestRun == null ? Map.of() : latestRun.getConfigSnapshot(),
                currentOptionOverrides
        );
        String extractedCopybookText = firstNonBlankString(
                extractCopybookTextFromText(userMessage),
                recommendedCopybookText
        );
        Map<String, Object> appliedConfig = extractedConfig.isEmpty()
                ? Map.of()
                : new LinkedHashMap<>(extractedConfig);

        List<ToolActionRequest> toolRequests = new ArrayList<>();
        if (wantsCopybookUpdate && !extractedCopybookText.isBlank()) {
            toolRequests.add(new ToolActionRequest("update_copybook_text", Map.of("copybookText", extractedCopybookText)));
        }
        if (wantsConfigUpdate) {
            toolRequests.add(new ToolActionRequest("update_config", appliedConfig));
        }
        if (wantsPreviewResults) {
            toolRequests.add(new ToolActionRequest("retrieve_preview_results", Map.of()));
        }
        if (wantsProfileRun) {
            toolRequests.add(new ToolActionRequest("run_full_profile", Map.of(
                    "optionOverrides", appliedConfig,
                    "sampleRows", 50
            )));
        } else if (wantsPreviewRun) {
            toolRequests.add(new ToolActionRequest("run_preview", Map.of(
                    "optionOverrides", appliedConfig,
                    "sampleRows", 20
            )));
        }

        if ((wantsPreviewRun || wantsProfileRun) && (!hasCopybook || !hasDataFile)) {
            return new ActionPlan(
                    """
                    I’m ready to run this for you, but the discovery session still needs both required artifacts.

                    Please make sure the copybook and the EBCDIC data file are both uploaded first, then I can apply the config and kick off the run directly from chat.
                    """,
                    appliedConfig,
                    extractedCopybookText,
                    null,
                    0,
                    wantsPreviewResults,
                    toolRequests
            );
        }

        if (wantsCopybookUpdate && extractedCopybookText.isBlank()) {
            return new ActionPlan(
                    """
                    I couldn’t find a full revised copybook text to apply automatically.

                    Ask me to generate the corrected raw copybook text first, or paste the revised copybook in a ```cobol``` block, and then I can replace the active copybook and rerun discovery for you.
                    """,
                    appliedConfig,
                    "",
                    null,
                    0,
                    wantsPreviewResults,
                    toolRequests
            );
        }

        if (wantsConfigUpdate && appliedConfig.isEmpty()) {
            return new ActionPlan(
                    """
                    I couldn’t find a valid JSON Cobrix config in the recent conversation to apply automatically.

                    Paste the config directly into chat, or ask me to generate a new one first, and then I can update the overrides and launch the run for you.
                    """,
                    Map.of(),
                    extractedCopybookText,
                    null,
                    0,
                    wantsPreviewResults,
                    toolRequests
            );
        }

        String runType = wantsProfileRun ? "profile" : (wantsPreviewRun ? "preview" : null);
        int sampleRows = wantsProfileRun ? 50 : (wantsPreviewRun ? 20 : 0);

        List<String> confirmations = new ArrayList<>();
        if (wantsCopybookUpdate && !extractedCopybookText.isBlank()) {
            confirmations.add("I replaced the active copybook text with the revised layout.");
        }
        if (wantsConfigUpdate && !appliedConfig.isEmpty()) {
            confirmations.add("I applied the Cobrix config to the option overrides.");
        }
        if (wantsPreviewRun) {
            confirmations.add("I queued a new preview run with that config.");
        }
        if (wantsProfileRun) {
            confirmations.add("I queued a new full profile run with that config.");
        }
        if (wantsPreviewResults) {
            confirmations.add("I also pulled the latest preview summary so we can compare the next result against it.");
        }

        String assistantMessage = String.join(" ", confirmations);
        if (assistantMessage.isBlank()) {
            assistantMessage = "I updated the Discovery state from chat.";
        }
        assistantMessage += "\n\nWatch the Event Log for progress. When the run finishes, I’ll use the new flattened preview to guide the next config adjustment.";

        return new ActionPlan(
                assistantMessage,
                appliedConfig,
                extractedCopybookText,
                runType,
                sampleRows,
                wantsPreviewResults,
                toolRequests
        );
    }

    public String respond(
            String userMessage,
            boolean hasCopybook,
            boolean hasDataFile,
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryMessage> recentMessages,
            List<CobolDiscoveryArtifact> artifacts,
            List<CobolParsingProfile> profiles) {
        return respondStructured(userMessage, hasCopybook, hasDataFile, latestRun, copybookContent, recentMessages, artifacts, profiles).content();
    }

    public AssistantReply respondStructured(
            String userMessage,
            boolean hasCopybook,
            boolean hasDataFile,
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryMessage> recentMessages,
            List<CobolDiscoveryArtifact> artifacts,
            List<CobolParsingProfile> profiles) {
        if (isLlmConfigured()) {
            try {
                return llmStructuredAssistantReply(
                        userMessage,
                        hasCopybook,
                        hasDataFile,
                        latestRun,
                        copybookContent,
                        recentMessages,
                        artifacts,
                        profiles
                );
            } catch (Exception e) {
                log.warn("Discovery assistant LLM call failed, falling back to local guidance: {}", e.getMessage());
            }
        }
        String content = fallbackResponse(userMessage, hasCopybook, hasDataFile, latestRun, profiles);
        return buildStructuredReply(content, latestRun, copybookContent, recentMessages);
    }

    public LoopDecision reviewRunForLoop(
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryRun> recentRuns,
            List<CobolDiscoveryArtifact> artifacts,
            List<CobolDiscoveryMessage> recentMessages,
            List<CobolParsingProfile> profiles,
            int loopIteration,
            int loopMaxIterations) {
        if (isLlmConfigured()) {
            try {
                return llmLoopReviewDecision(latestRun, copybookContent, recentRuns, recentMessages, artifacts, profiles, loopIteration, loopMaxIterations);
            } catch (Exception e) {
                log.warn("Discovery loop review LLM call failed, falling back to local guidance: {}", e.getMessage());
            }
        }
        return fallbackLoopDecision(latestRun, copybookContent, recentRuns, loopIteration, loopMaxIterations);
    }

    private String fallbackResponse(
            String userMessage,
            boolean hasCopybook,
            boolean hasDataFile,
            CobolDiscoveryRun latestRun,
            List<CobolParsingProfile> profiles) {
        String lower = userMessage == null ? "" : userMessage.toLowerCase();
        if (!hasCopybook) {
            return "I need a copybook first. Upload the COBOL copybook so I can analyze REDEFINES, OCCURS, numeric formats, and candidate Cobrix options.";
        }
        if (!hasDataFile) {
            return "The copybook is in. Upload the EBCDIC data file next so I can run discovery against real bytes through Spark/Cobrix.";
        }
        if (latestRun == null) {
            return """
                    I recommend starting with a preview run now. I’ll generate Cobrix candidates from the copybook, try the most plausible framing options first, and show you the flattened output preview.
                    """;
        }
        if (lower.contains("wrong") || lower.contains("incorrect") || lower.contains("shift") || lower.contains("misaligned")) {
            return """
                    If the flattened preview looks shifted or wrong, the most likely causes are record framing, code page, or variable-size OCCURS handling.

                    I’d try one of these next:
                    - switch `record_format` between `F`, `V`, or `VB`
                    - flip `is_rdw_big_endian`
                    - set `variable_size_occurs=true`
                    - enable `debug=true`
                    """;
        }
        if (latestRun != null && latestRun.getProfilingSummary() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> copybookSummary = (Map<String, Object>) latestRun.getProfilingSummary().getOrDefault("copybookSummary", Map.of());
            @SuppressWarnings("unchecked")
            List<String> warnings = (List<String>) latestRun.getAnomalySummary().getOrDefault("warnings", List.of());
            if ((Boolean.TRUE.equals(copybookSummary.get("hasOccursDependingOn")) || Boolean.TRUE.equals(copybookSummary.get("hasOccurs")))
                    && !lower.contains("occurs")) {
                return """
                        The copybook uses repeated groups, so I’d verify the OCCURS behavior next. If the flattened preview drifts after the first repeated section, try `variable_size_occurs=true` and rerun preview.
                        """;
            }
            if (Boolean.TRUE.equals(copybookSummary.get("hasRedefines")) && !lower.contains("redefines")) {
                return """
                        This copybook uses REDEFINES, so I’d verify segment or redefine selection next. If only some rows decode correctly, set `segment_field` plus `redefine_segment_id_map` for the redefine branches you expect.
                        """;
            }
            if (warnings.stream().anyMatch(warning -> warning.contains("mostly null")) && !lower.contains("null")) {
                return """
                        The preview still has mostly-null columns, which usually points to the wrong framing mode or the wrong redefine branch. I’d change framing or segment mapping before trusting the current output.
                        """;
            }
            if (warnings.stream().anyMatch(warning -> warning.contains("declared type")) && !lower.contains("type")) {
                return """
                        The current parse is structurally close, but some values do not match the declared types. I’d inspect packed decimal / COMP handling and the selected code page next.
                        """;
            }
        }
        if (lower.contains("save")) {
            return "If the preview looks correct, save the run as a COBOL parsing profile. That will persist the copybook content, chosen Cobrix options, flatten spec, schema snapshot, and quality summary.";
        }
        if (lower.contains("profile") && !profiles.isEmpty()) {
            return "You already have " + profiles.size() + " saved COBOL profile(s) for this tenant. If one is close, reprofile it with a fresh file instead of starting from scratch.";
        }
        String confidence = latestRun.getConfidenceScore() == null
                ? "unknown"
                : String.format("%.1f", latestRun.getConfidenceScore());
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) latestRun.getAnomalySummary().getOrDefault("warnings", List.of());
        return """
                Current Discovery status:
                - latest run status: %s
                - confidence score: %s
                - warnings: %s

                I’m keeping Cobrix-specific guidance separate from the main PULSE assistant. If you want, update the option overrides and run preview again until the flattened output looks right.
                """.formatted(latestRun.getStatus(), confidence, warnings.isEmpty() ? "none" : warnings);
    }

    public String knowledgeSummary() {
        return CobolDiscoveryPrompt.SYSTEM_IDENTITY
                + "\n\n"
                + CobolDiscoveryPrompt.RULES
                + "\n\n"
                + knowledgeSummary
                + "\n\n"
                + exampleSummary;
    }

    String buildUserPrompt(
            String userMessage,
            boolean hasCopybook,
            boolean hasDataFile,
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryMessage> recentMessages,
            List<CobolDiscoveryArtifact> artifacts,
            List<CobolParsingProfile> profiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("User request:\n").append(userMessage == null ? "" : userMessage).append("\n\n");
        sb.append("Discovery state:\n");
        sb.append("- hasCopybook: ").append(hasCopybook).append("\n");
        sb.append("- hasDataFile: ").append(hasDataFile).append("\n");
        sb.append("- activeArtifacts: ").append(artifactSummary(artifacts)).append("\n");
        sb.append("- savedProfiles: ").append(profileSummary(profiles)).append("\n\n");

        if (copybookContent != null && !copybookContent.isBlank()) {
            sb.append("Copybook text:\n```cobol\n")
                    .append(copybookContent)
                    .append("\n```\n\n");
        }

        if (latestRun != null) {
            sb.append("Latest run summary:\n");
            sb.append("- status: ").append(latestRun.getStatus()).append("\n");
            sb.append("- confidenceScore: ").append(latestRun.getConfidenceScore()).append("\n");
            sb.append("- chosenConfig: ").append(safeJson(latestRun.getConfigSnapshot())).append("\n");
            sb.append("- profilingSummary: ").append(safeJson(latestRun.getProfilingSummary())).append("\n");
            sb.append("- anomalySummary: ").append(safeJson(latestRun.getAnomalySummary())).append("\n");
            sb.append("- schemaSnapshot: ").append(safeJson(latestRun.getResultSchemaSnapshot())).append("\n");
            sb.append("- mappingSpec: ").append(safeJson(latestRun.getMappingSpec())).append("\n");
            sb.append("\n");
        }

        if (recentMessages != null && !recentMessages.isEmpty()) {
            sb.append("Recent Discovery conversation:\n");
            for (CobolDiscoveryMessage message : recentMessages.subList(Math.max(0, recentMessages.size() - 6), recentMessages.size())) {
                sb.append("- ").append(message.getRole()).append(": ").append(message.getContent()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Important safety reminder:\n");
        sb.append("- No raw EBCDIC bytes or preview row payloads are provided here.\n");
        sb.append("- Reason only from copybook text and safe summaries.\n");
        sb.append("- If Cobrix options cannot fix the issue because the copybook layout itself is wrong, you may return a full revised copybook text for the system to apply before the next run.\n");
        sb.append("- Respond with the next best Cobrix-focused recommendation.\n");
        return sb.toString();
    }

    private String llmResponse(
            String userMessage,
            boolean hasCopybook,
            boolean hasDataFile,
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryMessage> recentMessages,
            List<CobolDiscoveryArtifact> artifacts,
            List<CobolParsingProfile> profiles) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelFor());
        requestBody.put("messages", List.of(
                Map.of("role", "developer", "content", knowledgeSummary()),
                Map.of("role", "user", "content", buildUserPrompt(
                        userMessage, hasCopybook, hasDataFile, latestRun, copybookContent, recentMessages, artifacts, profiles))
        ));

        HttpURLConnection conn = openLlmConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(45_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(objectMapper.writeValueAsBytes(requestBody));
        }

        String responseBody;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            responseBody = sb.toString();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
        if (choices != null && !choices.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            Object content = message.get("content");
            if (content != null) {
                return String.valueOf(content).trim();
            }
        }
        return fallbackResponse(userMessage, hasCopybook, hasDataFile, latestRun, profiles);
    }

    private String artifactSummary(List<CobolDiscoveryArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return "none";
        List<String> rows = new ArrayList<>();
        for (CobolDiscoveryArtifact artifact : artifacts) {
            rows.add(artifact.getArtifactType() + ":" + artifact.getOriginalFilename() + ":" + artifact.getCleanupStatus());
        }
        return String.join(", ", rows);
    }

    private String profileSummary(List<CobolParsingProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) return "none";
        List<String> rows = new ArrayList<>();
        for (CobolParsingProfile profile : profiles.stream().limit(5).toList()) {
            rows.add(profile.getName());
        }
        return String.join(", ", rows);
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public String safeJsonForUi(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private boolean isLlmConfigured() {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.COBOL_DISCOVERY)) {
            return llmEndpointService.isConfigured(LlmSurface.COBOL_DISCOVERY);
        }
        return apiKey != null && !apiKey.isBlank();
    }

    private String modelFor() {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.COBOL_DISCOVERY)) {
            return llmEndpointService.model(LlmSurface.COBOL_DISCOVERY);
        }
        return discoveryModel;
    }

    private HttpURLConnection openLlmConnection() throws IOException {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.COBOL_DISCOVERY)) {
            return llmEndpointService.openChatCompletionsConnection(LlmSurface.COBOL_DISCOVERY, "PULSE COBOL Discovery");
        }
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/chat/completions").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        return conn;
    }

    private String loadExampleSummary() throws IOException {
        StringBuilder sb = new StringBuilder("Curated example patterns:\n");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(getClass().getClassLoader());
        Resource[] resources = resolver.getResources("classpath*:cobol-discovery/examples/*.md");
        for (Resource resource : resources) {
            String name = resource.getFilename();
            if (name == null) continue;
            String content = resource.getContentAsString(StandardCharsets.UTF_8).trim();
            String firstLine = content.lines().findFirst().orElse(name);
            sb.append("- ").append(firstLine.replace("# ", "")).append("\n");
        }
        return sb.toString();
    }

    private LoopDecision llmLoopReviewDecision(
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryRun> recentRuns,
            List<CobolDiscoveryMessage> recentMessages,
            List<CobolDiscoveryArtifact> artifacts,
            List<CobolParsingProfile> profiles,
            int loopIteration,
            int loopMaxIterations) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelFor());
        requestBody.put("messages", List.of(
                Map.of("role", "developer", "content", knowledgeSummary() + "\n\n" + CobolDiscoveryPrompt.LOOP_CONTROLLER_RULES),
                Map.of("role", "user", "content", buildLoopReviewPrompt(
                        latestRun, copybookContent, recentRuns, recentMessages, artifacts, profiles, loopIteration, loopMaxIterations))
        ));

        HttpURLConnection conn = openLlmConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(45_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(objectMapper.writeValueAsBytes(requestBody));
        }

        String responseBody;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            responseBody = sb.toString();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
        if (choices == null || choices.isEmpty()) {
            return fallbackLoopDecision(latestRun, copybookContent, recentRuns, loopIteration, loopMaxIterations);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        Object content = message.get("content");
        if (content == null) {
            return fallbackLoopDecision(latestRun, copybookContent, recentRuns, loopIteration, loopMaxIterations);
        }
        String raw = String.valueOf(content).trim();
        Map<String, Object> decisionJson = parseJsonObject(raw);
        if (decisionJson.isEmpty()) {
            return fallbackLoopDecision(latestRun, copybookContent, recentRuns, loopIteration, loopMaxIterations);
        }

        boolean satisfied = Boolean.TRUE.equals(decisionJson.get("satisfied"));
        boolean shouldRerun = Boolean.TRUE.equals(decisionJson.get("should_rerun")) && !satisfied;
        @SuppressWarnings("unchecked")
        Map<String, Object> recommendedConfig = decisionJson.get("recommended_config") instanceof Map<?, ?> map
                ? unwrapRecommendedConfig(new LinkedHashMap<>((Map<String, Object>) map))
                : Map.of();
        String recommendedCopybookText = decisionJson.get("recommended_copybook_text") == null
                ? ""
                : String.valueOf(decisionJson.get("recommended_copybook_text")).trim();
        String assistantMessage = decisionJson.get("assistant_message") == null
                ? raw
                : String.valueOf(decisionJson.get("assistant_message"));
        return new LoopDecision(assistantMessage, recommendedConfig, recommendedCopybookText, shouldRerun, satisfied);
    }

    private AssistantReply llmStructuredAssistantReply(
            String userMessage,
            boolean hasCopybook,
            boolean hasDataFile,
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryMessage> recentMessages,
            List<CobolDiscoveryArtifact> artifacts,
            List<CobolParsingProfile> profiles) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelFor());
        requestBody.put("messages", List.of(
                Map.of("role", "developer", "content", knowledgeSummary() + "\n\n" + CobolDiscoveryPrompt.STRUCTURED_REPLY_RULES),
                Map.of("role", "user", "content", buildAssistantReplyPrompt(
                        userMessage, hasCopybook, hasDataFile, latestRun, copybookContent, recentMessages, artifacts, profiles))
        ));

        HttpURLConnection conn = openLlmConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(45_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(objectMapper.writeValueAsBytes(requestBody));
        }

        String responseBody;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            responseBody = sb.toString();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
        if (choices == null || choices.isEmpty()) {
            return buildStructuredReply(
                    fallbackResponse(userMessage, hasCopybook, hasDataFile, latestRun, profiles),
                    latestRun,
                    copybookContent,
                    recentMessages
            );
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        Object content = message.get("content");
        if (content == null) {
            return buildStructuredReply(
                    fallbackResponse(userMessage, hasCopybook, hasDataFile, latestRun, profiles),
                    latestRun,
                    copybookContent,
                    recentMessages
            );
        }

        Map<String, Object> replyJson = parseJsonObject(String.valueOf(content).trim());
        if (replyJson.isEmpty()) {
            return buildStructuredReply(String.valueOf(content).trim(), latestRun, copybookContent, recentMessages);
        }
        String assistantMessage = String.valueOf(replyJson.getOrDefault("assistant_message", ""));
        String recommendedRunType = String.valueOf(replyJson.getOrDefault("recommended_run_type", "none"));
        @SuppressWarnings("unchecked")
        Map<String, Object> recommendedConfig = replyJson.get("recommended_config") instanceof Map<?, ?> map
                ? unwrapRecommendedConfig(new LinkedHashMap<>((Map<String, Object>) map))
                : Map.of();
        String recommendedCopybookText = replyJson.get("recommended_copybook_text") == null
                ? ""
                : String.valueOf(replyJson.get("recommended_copybook_text")).trim();
        String contentText = assistantMessage.isBlank() ? fallbackResponse(userMessage, hasCopybook, hasDataFile, latestRun, profiles) : assistantMessage;
        if (!recommendedConfig.isEmpty()) {
            contentText = contentText + "\n\nRecommended Cobrix config:\n```json\n" + safeJson(recommendedConfig) + "\n```";
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!recommendedConfig.isEmpty()) {
            metadata.put("recommendedConfig", recommendedConfig);
            metadata.put("recommendedRunType", recommendedRunType == null || recommendedRunType.isBlank() ? "preview" : recommendedRunType);
        }
        if (!recommendedCopybookText.isBlank()) {
            metadata.put("recommendedCopybookText", recommendedCopybookText);
        }
        return new AssistantReply(contentText, metadata);
    }

    private LoopDecision fallbackLoopDecision(
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryRun> recentRuns,
            int loopIteration,
            int loopMaxIterations) {
        if ("COMPLETED".equals(latestRun.getStatus())) {
            return new LoopDecision(
                    """
                    I cannot autonomously declare this preview correct without the structured loop-review model response.

                    The latest preview finished, but automatic satisfaction and next-step branching are unavailable in fallback mode.
                    """,
                    Map.of(),
                    "",
                    false,
                    false
            );
        }

        return new LoopDecision(
                """
                I cannot autonomously choose the next Cobrix config without the structured loop-review model response.

                Automatic refinement is unavailable in fallback mode, so I’m stopping rather than guessing.
                """,
                Map.of(),
                "",
                false,
                false
        );
    }

    private String buildLoopReviewPrompt(
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryRun> recentRuns,
            List<CobolDiscoveryMessage> recentMessages,
            List<CobolDiscoveryArtifact> artifacts,
            List<CobolParsingProfile> profiles,
            int loopIteration,
            int loopMaxIterations) {
        StringBuilder sb = new StringBuilder();
        sb.append("Automatic refinement loop review.\n");
        sb.append("Current iteration: ").append(loopIteration).append(" of ").append(loopMaxIterations).append("\n\n");
        sb.append(buildUserPrompt(
                "Please inspect the latest preview and decide whether to stop or queue another preview run automatically.",
                true,
                true,
                latestRun,
                copybookContent,
                recentMessages,
                artifacts,
                profiles
        ));
        sb.append("\nPreview rows sample:\n");
        sb.append(safeJson(latestRun == null ? List.of() : latestRun.getPreviewRows().stream().limit(10).toList()));
        String derivedSegmentField = deriveSegmentField(copybookContent);
        Map<String, Object> derivedSegmentMap = deriveSegmentMap(copybookContent);
        Integer inferredFixedLength = inferFixedRecordLengthFromCopybook(copybookContent);
        Long dataFileSize = latestDataFileSize(artifacts);
        Map<String, Integer> branchWidths = deriveRedefinesBranchWidths(copybookContent);
        boolean branchWidthMismatch = branchWidths.size() > 1 &&
                branchWidths.values().stream().distinct().count() > 1;
        sb.append("\n\nCopybook-derived evidence:\n");
        sb.append("- derivedSegmentField: ").append(derivedSegmentField).append("\n");
        sb.append("- derivedSegmentMapCandidate: ").append(safeJson(derivedSegmentMap)).append("\n");
        sb.append("- inferredFixedRecordLength: ").append(inferredFixedLength).append("\n");
        sb.append("- dataFileSize: ").append(dataFileSize).append("\n");
        sb.append("- fixedLengthDividesFileExactly: ")
                .append(inferredFixedLength != null && dataFileSize != null && inferredFixedLength > 0 && dataFileSize % inferredFixedLength == 0)
                .append("\n");
        if (!branchWidths.isEmpty()) {
            sb.append("- redefinesBranchWidths: ").append(safeJson(branchWidths)).append("\n");
            if (branchWidthMismatch) {
                sb.append("- WARNING: REDEFINES branches have UNEQUAL widths. Cobrix decodes all branches using the LARGEST branch width. Shorter branches will have their fields padded, but if the copybook declares field sizes that don't sum to the largest branch width, fields near the end of shorter branches WILL BE TRUNCATED. If any preview field appears cut off, this mismatch is the likely cause. Fix by revising the copybook so all REDEFINES branches have equal total width (pad shorter branches with FILLER).\n");
            }
        }
        // Previous run comparison for regression detection
        CobolDiscoveryRun previousRun = null;
        if (recentRuns != null && recentRuns.size() >= 2) {
            previousRun = recentRuns.get(recentRuns.size() - 2);
        }
        if (previousRun != null && latestRun != null) {
            sb.append("\n\nREGRESSION COMPARISON (previous run vs current run):\n");
            sb.append("- previousConfidence: ").append(previousRun.getConfidenceScore()).append("\n");
            sb.append("- currentConfidence: ").append(latestRun.getConfidenceScore()).append("\n");
            if (latestRun.getConfidenceScore() != null && previousRun.getConfidenceScore() != null
                    && latestRun.getConfidenceScore() < previousRun.getConfidenceScore()) {
                sb.append("- *** REGRESSION DETECTED: confidence dropped from ")
                        .append(previousRun.getConfidenceScore()).append(" to ")
                        .append(latestRun.getConfidenceScore())
                        .append(". Your last change made things WORSE. Revert to the previous config or copybook before trying something new. ***\n");
            }
            sb.append("- previousConfig: ").append(safeJson(previousRun.getConfigSnapshot())).append("\n");
            sb.append("- previousPreviewSample (first 3 rows):\n");
            sb.append(safeJson(previousRun.getPreviewRows().stream().limit(3).toList())).append("\n");
            sb.append("- currentPreviewSample (first 3 rows):\n");
            sb.append(safeJson(latestRun.getPreviewRows().stream().limit(3).toList())).append("\n");
            sb.append("- INSTRUCTION: Compare the two samples above. If fields that were correct in the previous run are now wrong (e.g., data shifted between columns, names appearing in address fields), your copybook revision was incorrect. REVERT the copybook to the version used in the previous run and try a different approach.\n");
        }

        sb.append("\n\nRecent run history:\n");
        if (recentRuns == null || recentRuns.isEmpty()) {
            sb.append("- none\n");
        } else {
            int start = Math.max(0, recentRuns.size() - 8);
            for (CobolDiscoveryRun run : recentRuns.subList(start, recentRuns.size())) {
                sb.append("- runId=").append(run.getId())
                        .append(", status=").append(run.getStatus())
                        .append(", confidence=").append(run.getConfidenceScore())
                        .append(", config=").append(safeJson(run.getConfigSnapshot()))
                        .append(", warnings=").append(safeJson(run.getAnomalySummary().getOrDefault("warnings", List.of())))
                        .append("\n");
            }
        }
        sb.append("\n\nRespond with strict JSON only:\n");
        sb.append("""
                {
                  "satisfied": true|false,
                  "should_rerun": true|false,
                  "assistant_message": "short operator-facing explanation",
                  "recommended_copybook_text": "full revised copybook text when needed",
                  "recommended_config": {
                    "complete": "full next-run config"
                  }
                }
                """);
        sb.append("\nIf the preview is not correct, recommend at least one meaningful next change through `recommended_config` or `recommended_copybook_text`.");
        return sb.toString();
    }

    private String buildAssistantReplyPrompt(
            String userMessage,
            boolean hasCopybook,
            boolean hasDataFile,
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryMessage> recentMessages,
            List<CobolDiscoveryArtifact> artifacts,
            List<CobolParsingProfile> profiles) {
        return buildUserPrompt(
                userMessage,
                hasCopybook,
                hasDataFile,
                latestRun,
                copybookContent,
                recentMessages,
                artifacts,
                profiles
        ) + "\n\nReturn strict JSON only using the required structured reply shape.";
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private boolean mentionsRun(String value) {
        return containsAny(value,
                "run ",
                "run the ",
                "rerun",
                "re-run",
                "kick off",
                "queue",
                "launch",
                "start ");
    }

    private AssistantReply buildStructuredReply(
            String rawContent,
            CobolDiscoveryRun latestRun,
            String copybookContent,
            List<CobolDiscoveryMessage> recentMessages) {
        Map<String, Object> recommendedConfig = firstNonEmptyMap(
                extractConfigFromText(rawContent),
                extractMostRecentRecommendedConfig(recentMessages)
        );
        String recommendedCopybookText = firstNonBlankString(
                extractCopybookTextFromText(rawContent),
                extractMostRecentRecommendedCopybookText(recentMessages)
        );
        String content = rawContent == null ? "" : rawContent.trim();
        if (!recommendedConfig.isEmpty() && !JSON_FENCE_PATTERN.matcher(content).find()) {
            content = content + "\n\nRecommended Cobrix config:\n```json\n"
                    + safeJson(recommendedConfig)
                    + "\n```";
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!recommendedConfig.isEmpty()) {
            metadata.put("recommendedConfig", recommendedConfig);
            metadata.put("recommendedRunType", "preview");
        }
        if (!recommendedCopybookText.isBlank()) {
            metadata.put("recommendedCopybookText", recommendedCopybookText);
        }
        return new AssistantReply(content, metadata);
    }

    private Map<String, Object> extractMostRecentConfig(List<CobolDiscoveryMessage> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) return Map.of();
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            CobolDiscoveryMessage message = recentMessages.get(i);
            Map<String, Object> fromMetadata = extractRecommendedConfigFromMessage(message);
            if (!fromMetadata.isEmpty()) {
                return fromMetadata;
            }
            Map<String, Object> parsed = extractConfigFromText(message.getContent());
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        return Map.of();
    }

    private Map<String, Object> extractMostRecentRecommendedConfig(List<CobolDiscoveryMessage> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) return Map.of();
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            Map<String, Object> metadataConfig = extractRecommendedConfigFromMessage(recentMessages.get(i));
            if (!metadataConfig.isEmpty()) {
                return metadataConfig;
            }
        }
        return Map.of();
    }

    private String extractMostRecentRecommendedRunType(List<CobolDiscoveryMessage> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) return null;
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            Map<String, Object> metadata = recentMessages.get(i).getMetadata();
            if (metadata == null) continue;
            Object value = metadata.get("recommendedRunType");
            if (value instanceof String str && !str.isBlank()) {
                return str;
            }
        }
        return null;
    }

    private String extractMostRecentRecommendedCopybookText(List<CobolDiscoveryMessage> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) return "";
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            String text = extractRecommendedCopybookTextFromMessage(recentMessages.get(i));
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractConfigFromText(String text) {
        if (text == null || text.isBlank()) return Map.of();
        Matcher matcher = JSON_FENCE_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                Object parsed = objectMapper.readValue(matcher.group(1), Map.class);
                if (parsed instanceof Map<?, ?> map) {
                    return sanitizeConfigMap(new LinkedHashMap<>((Map<String, Object>) map));
                }
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> looseConfig = extractLooseKeyValueConfig(text);
        if (!looseConfig.isEmpty()) {
            return looseConfig;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                Object parsed = objectMapper.readValue(text.substring(start, end + 1), Map.class);
                if (parsed instanceof Map<?, ?> map) {
                    return sanitizeConfigMap(new LinkedHashMap<>((Map<String, Object>) map));
                }
            } catch (Exception ignored) {
            }
        }
        return Map.of();
    }

    private String extractCopybookTextFromText(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher matcher = COBOL_FENCE_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String text) {
        if (text == null || text.isBlank()) return Map.of();
        String candidate = text.trim();
        Matcher matcher = JSON_FENCE_PATTERN.matcher(candidate);
        if (matcher.find()) {
            candidate = matcher.group(1).trim();
        }
        try {
            Object parsed = objectMapper.readValue(candidate, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
        } catch (Exception ignored) {
        }
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                Object parsed = objectMapper.readValue(candidate.substring(start, end + 1), Map.class);
                if (parsed instanceof Map<?, ?> map) {
                    return new LinkedHashMap<>((Map<String, Object>) map);
                }
            } catch (Exception ignored) {
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRecommendedConfigFromMessage(CobolDiscoveryMessage message) {
        if (message == null || message.getMetadata() == null) return Map.of();
        Object value = message.getMetadata().get("recommendedConfig");
        if (value instanceof Map<?, ?> map) {
            return sanitizeConfigMap(new LinkedHashMap<>((Map<String, Object>) map));
        }
        return Map.of();
    }

    private String extractRecommendedCopybookTextFromMessage(CobolDiscoveryMessage message) {
        if (message == null || message.getMetadata() == null) return "";
        Object value = message.getMetadata().get("recommendedCopybookText");
        if (value instanceof String str && !str.isBlank()) {
            return str.trim();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractLooseKeyValueConfig(String text) {
        Map<String, Object> config = new LinkedHashMap<>();
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            line = line.replaceFirst("^[•*\\-]+\\s*", "");
            Matcher scalar = Pattern.compile("^([A-Za-z0-9_\\-]+)\\s*:\\s*\"([^\"]+)\"\\s*$").matcher(line);
            if (scalar.find()) {
                config.put(scalar.group(1), scalar.group(2));
                continue;
            }
            Matcher scalarUnquoted = Pattern.compile("^([A-Za-z0-9_\\-]+)\\s*[:=]\\s*([A-Za-z0-9_./-]+)\\s*$").matcher(line);
            if (scalarUnquoted.find()) {
                config.put(scalarUnquoted.group(1), scalarUnquoted.group(2));
                continue;
            }
            Matcher mapStart = Pattern.compile("^([A-Za-z0-9_\\-]+)\\s*:\\s*$").matcher(line);
            if (mapStart.find() && i + 1 < lines.length && lines[i + 1].contains("{")) {
                String key = mapStart.group(1);
                StringBuilder block = new StringBuilder();
                int braceDepth = 0;
                for (int j = i + 1; j < lines.length; j++) {
                    String blockLine = lines[j];
                    block.append(blockLine).append("\n");
                    for (char ch : blockLine.toCharArray()) {
                        if (ch == '{') braceDepth++;
                        else if (ch == '}') braceDepth--;
                    }
                    if (braceDepth == 0 && block.toString().contains("{")) {
                        try {
                            Object parsed = objectMapper.readValue(block.toString(), Map.class);
                            if (parsed instanceof Map<?, ?> map) {
                                config.put(key, new LinkedHashMap<>((Map<String, Object>) map));
                            }
                        } catch (Exception ignored) {
                        }
                        i = j;
                        break;
                    }
                }
            }
        }
        return sanitizeConfigMap(config);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeConfigMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (isUnsupportedLoopKey(entry.getKey())) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof String str) {
                String trimmed = str.trim();
                if (trimmed.isBlank() || "null".equalsIgnoreCase(trimmed)) {
                    continue;
                }
                sanitized.put(entry.getKey(), trimmed);
                continue;
            }
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> nestedSanitized = sanitizeConfigMap(new LinkedHashMap<>((Map<String, Object>) nested));
                if (!nestedSanitized.isEmpty()) {
                    sanitized.put(entry.getKey(), nestedSanitized);
                }
                continue;
            }
            sanitized.put(entry.getKey(), value);
        }
        return sanitized;
    }

    private boolean isUnsupportedLoopKey(String key) {
        String normalized = key.trim();
        return "segment_children".equals(normalized)
                || "segment_id_level0".equals(normalized)
                || "segment_id_level1".equals(normalized)
                || "segment_id_level2".equals(normalized);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapRecommendedConfig(Map<String, Object> raw) {
        Map<String, Object> sanitized = sanitizeConfigMap(raw);
        Object complete = sanitized.get("complete");
        if (sanitized.size() == 1 && complete instanceof Map<?, ?> nested) {
            return sanitizeConfigMap(new LinkedHashMap<>((Map<String, Object>) nested));
        }
        return sanitized;
    }

    private boolean wasPreviouslyTried(Map<String, Object> candidate, List<CobolDiscoveryRun> recentRuns) {
        if (candidate == null || candidate.isEmpty() || recentRuns == null || recentRuns.isEmpty()) {
            return false;
        }
        String canonicalCandidate = canonicalConfig(candidate);
        for (CobolDiscoveryRun run : recentRuns) {
            if (canonicalCandidate.equals(canonicalConfig(run.getConfigSnapshot()))) {
                return true;
            }
        }
        return false;
    }

    private Integer inferFixedRecordLengthFromCopybook(String copybookContent) {
        if (copybookContent == null || copybookContent.isBlank()) return null;
        java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile("(?im)^\\s*(\\d{2})\\s+([A-Z0-9-]+)(.*)$");
        java.util.regex.Matcher matcher = linePattern.matcher(copybookContent);
        String detectedSegmentField = deriveSegmentField(copybookContent);
        int segmentFieldLength = 0;
        int currentGroupTotal = 0;
        int maxGroupTotal = 0;
        boolean inTopLevelGroup = false;
        while (matcher.find()) {
            int level = Integer.parseInt(matcher.group(1));
            String name = matcher.group(2);
            String tail = matcher.group(3) == null ? "" : matcher.group(3);
            if (level == 5 && tail.toUpperCase().contains("PIC")) {
                Integer len = picLength(tail);
                if (len != null && detectedSegmentField != null && detectedSegmentField.equalsIgnoreCase(name)) {
                    segmentFieldLength = len;
                    continue;
                }
            }
            if (level == 5 && !tail.toUpperCase().contains("PIC")) {
                if (inTopLevelGroup) {
                    maxGroupTotal = Math.max(maxGroupTotal, currentGroupTotal);
                }
                inTopLevelGroup = true;
                currentGroupTotal = 0;
                continue;
            }
            if (inTopLevelGroup && level >= 10 && tail.toUpperCase().contains("PIC")) {
                Integer len = picLength(tail);
                if (len != null) {
                    currentGroupTotal += len;
                }
            }
        }
        if (inTopLevelGroup) {
            maxGroupTotal = Math.max(maxGroupTotal, currentGroupTotal);
        }
        if (segmentFieldLength > 0 && maxGroupTotal > 0) {
            return segmentFieldLength + maxGroupTotal;
        }
        return null;
    }

    private Long latestDataFileSize(List<CobolDiscoveryArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return null;
        for (int i = artifacts.size() - 1; i >= 0; i--) {
            CobolDiscoveryArtifact artifact = artifacts.get(i);
            if ("data_file".equals(artifact.getArtifactType()) && "ACTIVE".equals(artifact.getCleanupStatus())) {
                return artifact.getSizeBytes();
            }
        }
        return null;
    }

    private Integer picLength(String tail) {
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("PIC\\s+X\\((\\d+)\\)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(tail);
        if (x.find()) {
            return Integer.parseInt(x.group(1));
        }
        java.util.regex.Matcher nine = java.util.regex.Pattern.compile("PIC\\s+9\\((\\d+)\\)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(tail);
        if (nine.find()) {
            return Integer.parseInt(nine.group(1));
        }
        return null;
    }

    private String canonicalConfig(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(sanitizeConfigMap(new LinkedHashMap<>(config == null ? Map.of() : config)));
        } catch (Exception e) {
            return String.valueOf(config);
        }
    }

    private String deriveSegmentField(String copybookContent) {
        if (copybookContent == null || copybookContent.isBlank()) return null;
        Matcher preferred = Pattern.compile("(?im)^\\s*05\\s+([A-Z0-9-]+)\\s+PIC\\s+X\\(1\\).*?(SEGMENT|TYPE)").matcher(copybookContent);
        if (preferred.find()) {
            return preferred.group(1);
        }
        Matcher fallback = Pattern.compile("(?im)^\\s*05\\s+([A-Z0-9-]+)\\s+PIC\\s+X\\(1\\)").matcher(copybookContent);
        if (fallback.find()) {
            return fallback.group(1);
        }
        return null;
    }

    private List<String> deriveTopLevelGroups(String copybookContent) {
        List<String> groups = new ArrayList<>();
        if (copybookContent == null || copybookContent.isBlank()) return groups;
        Matcher matcher = Pattern.compile("(?im)^\\s*05\\s+([A-Z0-9-]+)(?:\\s+REDEFINES\\s+[A-Z0-9-]+)?\\s*(?:\\.|$)").matcher(copybookContent);
        while (matcher.find()) {
            String name = matcher.group(1);
            String line = matcher.group(0);
            if (name.equalsIgnoreCase("FILLER")) continue;
            if (line.toUpperCase().contains(" PIC ")) continue;
            groups.add(name);
        }
        return groups;
    }

    private Map<String, Integer> deriveRedefinesBranchWidths(String copybookContent) {
        Map<String, Integer> widths = new LinkedHashMap<>();
        if (copybookContent == null || copybookContent.isBlank()) return widths;
        List<String> groups = deriveTopLevelGroups(copybookContent);
        if (groups.isEmpty()) return widths;

        // Parse all lines to compute byte width per group
        String[] lines = copybookContent.split("\\r?\\n");
        String currentGroup = null;
        int currentTotal = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*")) continue;
            java.util.regex.Matcher groupMatcher = Pattern.compile("(?i)^\\d{2}\\s+([A-Z0-9-]+)").matcher(trimmed);
            if (!groupMatcher.find()) continue;
            int level;
            try { level = Integer.parseInt(trimmed.substring(0, 2).trim()); } catch (NumberFormatException e) { continue; }
            String name = groupMatcher.group(1);
            if (level == 5 && groups.contains(name) && !trimmed.toUpperCase().contains(" PIC ")) {
                if (currentGroup != null) {
                    widths.put(currentGroup, currentTotal);
                }
                currentGroup = name;
                currentTotal = 0;
                continue;
            }
            if (currentGroup != null && level >= 10 && trimmed.toUpperCase().contains("PIC")) {
                Integer len = picLength(trimmed);
                if (len != null) {
                    currentTotal += len;
                }
            }
        }
        if (currentGroup != null) {
            widths.put(currentGroup, currentTotal);
        }
        return widths;
    }

    private Map<String, Object> deriveSegmentMap(String copybookContent) {
        List<String> groups = deriveTopLevelGroups(copybookContent);
        if (groups.isEmpty()) return Map.of();
        Map<String, Object> redefineMap = new LinkedHashMap<>();
        List<String> usedCodes = new ArrayList<>();
        for (String group : groups) {
            String code = deriveSegmentCode(group, usedCodes);
            usedCodes.add(code);
            redefineMap.put(code, group);
        }
        return redefineMap;
    }

    private String deriveSegmentCode(String group, List<String> usedCodes) {
        String upper = group.toUpperCase(Locale.ROOT);
        for (String part : upper.split("[^A-Z0-9]+")) {
            if (part.isBlank()) continue;
            String candidate = String.valueOf(part.charAt(0));
            if (!usedCodes.contains(candidate)) {
                return candidate;
            }
        }
        String normalized = upper.replaceAll("[^A-Z0-9]", "");
        for (char ch : normalized.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                String candidate = String.valueOf(ch);
                if (!usedCodes.contains(candidate)) {
                    return candidate;
                }
            }
        }
        for (char ch = 'A'; ch <= 'Z'; ch++) {
            String candidate = String.valueOf(ch);
            if (!usedCodes.contains(candidate)) {
                return candidate;
            }
        }
        return normalized.substring(0, 1);
    }


    @SafeVarargs
    private final Map<String, Object> firstNonEmptyMap(Map<String, Object>... candidates) {
        for (Map<String, Object> candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return Map.of();
    }

    private String firstNonBlankString(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    public record ActionPlan(
            String assistantMessage,
            Map<String, Object> optionOverrides,
            String copybookText,
            String runType,
            int sampleRows,
            boolean retrievePreviewResults,
            List<ToolActionRequest> toolRequests) {
        static ActionPlan none() {
            return new ActionPlan("", Map.of(), "", null, 0, false, List.of());
        }

        public boolean hasActions() {
            return !toolRequests.isEmpty();
        }
    }

    public record ToolActionRequest(String name, Map<String, Object> arguments) {}
    public record AssistantReply(String content, Map<String, Object> metadata) {}
    public record LoopDecision(
            String assistantMessage,
            Map<String, Object> recommendedConfig,
            String recommendedCopybookText,
            boolean shouldRerun,
            boolean satisfied) {}
}
