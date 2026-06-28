package com.pulse.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmEndpointServiceTest {

    @Test
    void openRouterKeepsLegacyModelsBySurface() {
        LlmEndpointService service = service(
                "openrouter",
                "sk-test",
                "https://openrouter.ai/api/v1/",
                "openai/gpt-5.2",
                "o4-mini",
                "openai/gpt-5.2",
                "google/gemini-2.0-flash-001",
                "google/gemini-2.0-flash-001",
                "anthropic/claude-opus-4.6",
                "",
                "global",
                "");

        assertThat(service.isConfigured(LlmSurface.CHAT)).isTrue();
        assertThat(service.model(LlmSurface.CHAT)).isEqualTo("openai/gpt-5.2");
        assertThat(service.model(LlmSurface.SCHEMA_INFERENCE)).isEqualTo("google/gemini-2.0-flash-001");
        assertThat(service.model(LlmSurface.COBOL_DISCOVERY)).isEqualTo("anthropic/claude-opus-4.6");
        assertThat(service.chatCompletionsUrl()).isEqualTo("https://openrouter.ai/api/v1/chat/completions");
    }

    @Test
    void vertexUsesGeminiDefaultsAndGlobalEndpoint() {
        LlmEndpointService service = service(
                "vertex",
                "",
                "https://openrouter.ai/api/v1",
                "openai/gpt-5.2",
                "o4-mini",
                "openai/gpt-5.2",
                "google/gemini-2.0-flash-001",
                "google/gemini-2.0-flash-001",
                "anthropic/claude-opus-4.6",
                "wf-pulse-agentic-dev2",
                "global",
                "");

        assertThat(service.isConfigured(LlmSurface.CHAT)).isTrue();
        assertThat(service.model(LlmSurface.CHAT)).isEqualTo("google/gemini-3.1-pro-preview");
        assertThat(service.model(LlmSurface.STORY_GENERATION)).isEqualTo("google/gemini-3.1-pro-preview");
        assertThat(service.model(LlmSurface.SCHEMA_INFERENCE)).isEqualTo("google/gemini-2.5-flash");
        assertThat(service.model(LlmSurface.DQ_READINESS)).isEqualTo("google/gemini-2.5-flash");
        assertThat(service.model(LlmSurface.COBOL_DISCOVERY)).isEqualTo("google/gemini-3.1-pro-preview");
        assertThat(service.chatCompletionsUrl())
                .isEqualTo("https://aiplatform.googleapis.com/v1/projects/wf-pulse-agentic-dev2/locations/global/endpoints/openapi/chat/completions");
    }

    @Test
    void vertexUsesRegionalEndpointWhenLocationIsRegional() {
        LlmEndpointService service = service(
                "gcp-vertex",
                "",
                "https://openrouter.ai/api/v1",
                "openai/gpt-5.2",
                "o4-mini",
                "openai/gpt-5.2",
                "google/gemini-2.0-flash-001",
                "google/gemini-2.0-flash-001",
                "anthropic/claude-opus-4.6",
                "project-x",
                "us-central1",
                "");

        assertThat(service.chatCompletionsUrl())
                .isEqualTo("https://us-central1-aiplatform.googleapis.com/v1/projects/project-x/locations/us-central1/endpoints/openapi/chat/completions");
    }

    private static LlmEndpointService service(
            String provider,
            String apiKey,
            String baseUrl,
            String chatModel,
            String reasoningModel,
            String storyModel,
            String schemaModel,
            String dqModel,
            String cobolModel,
            String vertexProjectId,
            String vertexLocation,
            String vertexCredentialsPath) {
        return new LlmEndpointService(
                provider,
                apiKey,
                baseUrl,
                chatModel,
                reasoningModel,
                "google/gemini-2.5-flash", // openRouterCheapChatModel (ADR 0025 CHAT_CHEAP)
                storyModel,
                schemaModel,
                dqModel,
                cobolModel,
                vertexProjectId,
                vertexLocation,
                vertexCredentialsPath,
                "",
                "google/gemini-3.1-pro-preview",
                "google/gemini-3.1-pro-preview",
                "google/gemini-2.5-flash", // vertexCheapChatModel (ADR 0025 CHAT_CHEAP)
                "google/gemini-3.1-pro-preview",
                "google/gemini-2.5-flash",
                "google/gemini-2.5-flash",
                "google/gemini-3.1-pro-preview");
    }
}
