package com.pulse.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("live-vertex")
class VertexLlmConnectivityIT {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void gemini31ProPreviewRespondsThroughOpenAiCompatibleEndpoint() throws Exception {
        LlmEndpointService service = liveService();

        Map<String, Object> requestBody = Map.of(
                "model", service.model(LlmSurface.CHAT),
                "messages", List.of(
                        Map.of("role", "system", "content", "Reply with exactly: pulse-vertex-ok"),
                        Map.of("role", "user", "content", "Connectivity check.")),
                "max_tokens", 16,
                "temperature", 0);

        HttpResult result = postJson(service, requestBody, 60_000);

        assertThat(result.status())
                .as("Vertex response body: %s", result.body())
                .isBetween(200, 299);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(result.body(), Map.class);
        assertThat(parsed.get("choices")).isInstanceOf(List.class);
        assertThat((List<?>) parsed.get("choices")).isNotEmpty();
    }

    @Test
    void gemini31ProPreviewCompletesOpenAiCompatibleToolLoopWithThoughtSignature() throws Exception {
        LlmEndpointService service = liveService();

        Map<String, Object> listBlueprintsTool = Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "list_blueprints",
                        "description", "List available PULSE blueprint templates.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of())));
        List<Map<String, Object>> tools = List.of(listBlueprintsTool);

        Map<String, Object> firstRequest = Map.of(
                "model", service.model(LlmSurface.CHAT),
                "messages", List.of(
                        Map.of("role", "user", "content", "Call the list_blueprints tool now.")),
                "tools", tools,
                "tool_choice", Map.of(
                        "type", "function",
                        "function", Map.of("name", "list_blueprints")),
                "max_tokens", 256,
                "temperature", 0);

        HttpResult firstResult = postJson(service, firstRequest, 120_000);
        assertThat(firstResult.status())
                .as("Initial Vertex tool-call response body: %s", firstResult.body())
                .isBetween(200, 299);

        @SuppressWarnings("unchecked")
        Map<String, Object> firstParsed = objectMapper.readValue(firstResult.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) firstParsed.get("choices");
        assertThat(choices).isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> assistantMessage = (Map<String, Object>) choices.get(0).get("message");
        assertThat(assistantMessage).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) assistantMessage.get("tool_calls");
        assertThat(toolCalls).isNotEmpty();

        Map<String, Object> firstToolCall = toolCalls.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> extraContent = (Map<String, Object>) firstToolCall.get("extra_content");
        assertThat(extraContent)
                .as("Gemini 3.x function calls should include Vertex thought_signature metadata")
                .isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> google = (Map<String, Object>) extraContent.get("google");
        assertThat(google).isNotNull();
        assertThat(google.get("thought_signature")).as("thought_signature").isInstanceOf(String.class);

        Map<String, Object> replayedAssistant = new LinkedHashMap<>();
        replayedAssistant.put("role", "assistant");
        replayedAssistant.put("content", assistantMessage.get("content") == null ? "" : assistantMessage.get("content"));
        replayedAssistant.put("tool_calls", toolCalls);

        Map<String, Object> toolResult = Map.of(
                "role", "tool",
                "tool_call_id", firstToolCall.get("id"),
                "content", "[{\"id\":\"bronze_to_silver_cleaning\",\"name\":\"BronzeToSilverCleaning\"}]");

        Map<String, Object> followUpRequest = Map.of(
                "model", service.model(LlmSurface.CHAT),
                "messages", List.of(
                        Map.of("role", "user", "content", "Call the list_blueprints tool now."),
                        replayedAssistant,
                        toolResult),
                "tools", tools,
                "max_tokens", 128,
                "temperature", 0);

        HttpResult followUpResult = postJson(service, followUpRequest, 120_000);
        assertThat(followUpResult.status())
                .as("Vertex follow-up response body: %s", followUpResult.body())
                .isBetween(200, 299);
    }

    private LlmEndpointService liveService() {
        String projectId = firstNonBlank(
                System.getenv("VERTEX_PROJECT_ID"),
                System.getenv("PULSE_VERTEX_LIVE_PROJECT_ID"));
        String location = firstNonBlank(System.getenv("VERTEX_LOCATION"), "global");
        String credentialsPath = firstNonBlank(System.getenv("VERTEX_CREDENTIALS_PATH"), "");
        String impersonateServiceAccount = firstNonBlank(System.getenv("VERTEX_IMPERSONATE_SERVICE_ACCOUNT"), "");

        assumeTrue(projectId != null && !projectId.isBlank(),
                "Set VERTEX_PROJECT_ID to run live Vertex connectivity tests.");

        LlmEndpointService service = new LlmEndpointService(
                "vertex",
                "",
                "https://openrouter.ai/api/v1",
                "openai/gpt-5.2",
                "o4-mini",
                "google/gemini-2.5-flash", // openRouterCheapChatModel (ADR 0025 CHAT_CHEAP)
                "openai/gpt-5.2",
                "google/gemini-2.0-flash-001",
                "google/gemini-2.0-flash-001",
                "anthropic/claude-opus-4.6",
                projectId,
                location,
                credentialsPath,
                impersonateServiceAccount,
                "google/gemini-3.1-pro-preview",
                "google/gemini-3.1-pro-preview",
                "google/gemini-2.5-flash", // vertexCheapChatModel (ADR 0025 CHAT_CHEAP)
                "google/gemini-3.1-pro-preview",
                "google/gemini-2.5-flash",
                "google/gemini-2.5-flash",
                "google/gemini-3.1-pro-preview");
        return service;
    }

    private HttpResult postJson(LlmEndpointService service, Map<String, Object> requestBody, int readTimeoutMs)
            throws Exception {
        HttpURLConnection conn = service.openChatCompletionsConnection(LlmSurface.CHAT, "PULSE Vertex Connectivity");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(readTimeoutMs);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(objectMapper.writeValueAsBytes(requestBody));
        }

        int status = conn.getResponseCode();
        String responseBody = readBody(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        return new HttpResult(status, responseBody);
    }

    private record HttpResult(int status, String body) {}

    private static String readBody(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
