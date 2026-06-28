package com.pulse.llm;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;

@Service
public class LlmEndpointService {

    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final String provider;
    private final String openRouterApiKey;
    private final String openRouterBaseUrl;
    private final String openRouterChatModel;
    private final String openRouterReasoningModel;
    private final String openRouterCheapChatModel;
    private final String openRouterStoryModel;
    private final String openRouterSchemaModel;
    private final String openRouterDqModel;
    private final String openRouterCobolModel;
    private final String vertexProjectId;
    private final String vertexLocation;
    private final String vertexCredentialsPath;
    private final String vertexImpersonateServiceAccount;
    private final String vertexChatModel;
    private final String vertexReasoningModel;
    private final String vertexCheapChatModel;
    private final String vertexStoryModel;
    private final String vertexSchemaModel;
    private final String vertexDqModel;
    private final String vertexCobolModel;

    public LlmEndpointService(
            @Value("${pulse.llm.provider:openrouter}") String provider,
            @Value("${pulse.llm.api-key:}") String openRouterApiKey,
            @Value("${pulse.llm.base-url:https://openrouter.ai/api/v1}") String openRouterBaseUrl,
            @Value("${pulse.llm.model:openai/gpt-5.2}") String openRouterChatModel,
            @Value("${pulse.llm.reasoning-model:o4-mini}") String openRouterReasoningModel,
            @Value("${pulse.llm.cheap-model:google/gemini-2.5-flash}") String openRouterCheapChatModel,
            @Value("${pulse.llm.story-model:${pulse.llm.model:openai/gpt-5.2}}") String openRouterStoryModel,
            @Value("${pulse.schema-inference.model:google/gemini-2.0-flash-001}") String openRouterSchemaModel,
            @Value("${pulse.dq-readiness.model:${pulse.schema-inference.model:google/gemini-2.0-flash-001}}") String openRouterDqModel,
            @Value("${pulse.cobol-discovery.model:anthropic/claude-opus-4.6}") String openRouterCobolModel,
            @Value("${pulse.llm.vertex.project-id:}") String vertexProjectId,
            @Value("${pulse.llm.vertex.location:global}") String vertexLocation,
            @Value("${pulse.llm.vertex.credentials-path:}") String vertexCredentialsPath,
            @Value("${pulse.llm.vertex.impersonate-service-account:}") String vertexImpersonateServiceAccount,
            @Value("${pulse.llm.vertex.chat-model:google/gemini-3.1-pro-preview}") String vertexChatModel,
            @Value("${pulse.llm.vertex.reasoning-model:google/gemini-3.1-pro-preview}") String vertexReasoningModel,
            @Value("${pulse.llm.vertex.cheap-chat-model:google/gemini-2.5-flash}") String vertexCheapChatModel,
            @Value("${pulse.llm.vertex.story-model:google/gemini-3.1-pro-preview}") String vertexStoryModel,
            @Value("${pulse.llm.vertex.schema-model:google/gemini-2.5-flash}") String vertexSchemaModel,
            @Value("${pulse.llm.vertex.dq-model:google/gemini-2.5-flash}") String vertexDqModel,
            @Value("${pulse.llm.vertex.cobol-model:google/gemini-3.1-pro-preview}") String vertexCobolModel) {
        this.provider = normalizeProvider(provider);
        this.openRouterApiKey = trim(openRouterApiKey);
        this.openRouterBaseUrl = trimTrailingSlash(openRouterBaseUrl);
        this.openRouterChatModel = trim(openRouterChatModel);
        this.openRouterReasoningModel = trim(openRouterReasoningModel);
        this.openRouterCheapChatModel = trim(openRouterCheapChatModel);
        this.openRouterStoryModel = trim(openRouterStoryModel);
        this.openRouterSchemaModel = trim(openRouterSchemaModel);
        this.openRouterDqModel = trim(openRouterDqModel);
        this.openRouterCobolModel = trim(openRouterCobolModel);
        this.vertexProjectId = trim(vertexProjectId);
        this.vertexLocation = trim(vertexLocation);
        this.vertexCredentialsPath = trim(vertexCredentialsPath);
        this.vertexImpersonateServiceAccount = trim(vertexImpersonateServiceAccount);
        this.vertexChatModel = trim(vertexChatModel);
        this.vertexReasoningModel = trim(vertexReasoningModel);
        this.vertexCheapChatModel = trim(vertexCheapChatModel);
        this.vertexStoryModel = trim(vertexStoryModel);
        this.vertexSchemaModel = trim(vertexSchemaModel);
        this.vertexDqModel = trim(vertexDqModel);
        this.vertexCobolModel = trim(vertexCobolModel);
    }

    public boolean isConfigured(LlmSurface surface) {
        if (isVertex()) {
            return !vertexProjectId.isBlank() && !model(surface).isBlank();
        }
        return !openRouterApiKey.isBlank() && !model(surface).isBlank();
    }

    public String model(LlmSurface surface) {
        if (isVertex()) {
            return switch (surface) {
                case CHAT -> vertexChatModel;
                case CHAT_REASONING -> vertexReasoningModel;
                case CHAT_CHEAP -> vertexCheapChatModel;
                case STORY_GENERATION -> vertexStoryModel;
                case SCHEMA_INFERENCE -> vertexSchemaModel;
                case DQ_READINESS -> vertexDqModel;
                case COBOL_DISCOVERY -> vertexCobolModel;
            };
        }
        return switch (surface) {
            case CHAT -> openRouterChatModel;
            case CHAT_REASONING -> openRouterReasoningModel;
            case CHAT_CHEAP -> openRouterCheapChatModel;
            case STORY_GENERATION -> openRouterStoryModel;
            case SCHEMA_INFERENCE -> openRouterSchemaModel;
            case DQ_READINESS -> openRouterDqModel;
            case COBOL_DISCOVERY -> openRouterCobolModel;
        };
    }

    public HttpURLConnection openChatCompletionsConnection(LlmSurface surface, String title) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(chatCompletionsUrl()).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (isVertex()) {
            conn.setRequestProperty("Authorization", "Bearer " + vertexAccessToken());
        } else {
            conn.setRequestProperty("Authorization", "Bearer " + openRouterApiKey);
            conn.setRequestProperty("HTTP-Referer", "https://pulse.app");
            if (title != null && !title.isBlank()) {
                conn.setRequestProperty("X-Title", title);
            }
        }
        conn.setDoOutput(true);
        return conn;
    }

    public String provider() {
        return provider;
    }

    public boolean isVertexProvider() {
        return isVertexProvider(provider);
    }

    String chatCompletionsUrl() {
        if (!isVertex()) {
            return openRouterBaseUrl + "/chat/completions";
        }
        String host = "global".equals(vertexLocation)
                ? "aiplatform.googleapis.com"
                : vertexLocation + "-aiplatform.googleapis.com";
        return "https://" + host
                + "/v1/projects/" + vertexProjectId
                + "/locations/" + vertexLocation
                + "/endpoints/openapi/chat/completions";
    }

    private boolean isVertex() {
        return isVertexProvider(provider);
    }

    public static boolean isVertexProvider(String provider) {
        return "vertex".equals(provider) || "gcp-vertex".equals(provider);
    }

    private String vertexAccessToken() throws IOException {
        GoogleCredentials credentials;
        if (!vertexCredentialsPath.isBlank()) {
            try (FileInputStream input = new FileInputStream(vertexCredentialsPath)) {
                credentials = GoogleCredentials.fromStream(input);
            }
        } else {
            credentials = GoogleCredentials.getApplicationDefault();
        }
        credentials = credentials.createScoped(CLOUD_PLATFORM_SCOPE);
        if (!vertexImpersonateServiceAccount.isBlank()) {
            credentials = ImpersonatedCredentials.create(
                    credentials,
                    vertexImpersonateServiceAccount,
                    null,
                    java.util.List.of(CLOUD_PLATFORM_SCOPE),
                    3600);
        }
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    private static String normalizeProvider(String value) {
        String normalized = trim(value).toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "openrouter" : normalized;
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = trim(value);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
