package com.pulse.chat.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.chat.ChatStreamingException;
import com.pulse.chat.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ChatController}'s SSE-friendly exception handlers.
 *
 * Covers BUG-2026-05-25-57: when an exception escapes the chat-streaming
 * endpoint, the response MUST be a structured SSE {@code event: error\ndata:
 * {...}} envelope on a {@code text/event-stream} response — NOT a JSON
 * ProblemDetail (application/problem+json), which Spring cannot write to an
 * already-committed SSE stream and which the chat panel cannot parse.
 */
class ChatControllerTest {

    private ChatController controller;
    private ObjectMapper objectMapper;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        chatService = mock(ChatService.class);
        controller = new ChatController(chatService, objectMapper);
    }

    // ---- Plan-Preview decision endpoint (ADR 0025 / IMPL-ui-composition P4) ----

    @Test
    void decision_approve_returnsAppliedStatus() {
        when(chatService.decidePlan(eq("s1"), eq("plan-1"), eq("approve"), eq(null)))
                .thenReturn(Map.of("planId", "plan-1", "decision", "approve",
                        "status", "APPLIED", "mutationApplied", true));

        var resp = controller.decidePlan("s1", "plan-1", Map.of("decision", "approve"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("APPLIED", resp.getBody().get("status"));
        assertEquals(true, resp.getBody().get("mutationApplied"));
    }

    @Test
    void decision_reject_returnsCancelled() {
        when(chatService.decidePlan(eq("s1"), eq("plan-1"), eq("reject"), eq(null)))
                .thenReturn(Map.of("planId", "plan-1", "decision", "reject",
                        "status", "CANCELLED", "mutationApplied", false));

        var resp = controller.decidePlan("s1", "plan-1", Map.of("decision", "reject"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("CANCELLED", resp.getBody().get("status"));
    }

    @Test
    void decision_modify_marksRebuild() {
        when(chatService.decidePlan(eq("s1"), eq("plan-1"), eq("modify"), eq("use silver")))
                .thenReturn(Map.of("planId", "plan-1", "decision", "modify",
                        "status", "CANCELLED", "rebuild", true, "feedback", "use silver"));

        var resp = controller.decidePlan("s1", "plan-1",
                Map.of("decision", "modify", "feedback", "use silver"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("rebuild"));
    }

    @Test
    void decision_unknown_returnsBadRequest() {
        when(chatService.decidePlan(eq("s1"), eq("plan-1"), eq("frobnicate"), eq(null)))
                .thenThrow(new IllegalArgumentException("Unknown plan decision 'frobnicate'"));

        var resp = controller.decidePlan("s1", "plan-1", Map.of("decision", "frobnicate"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().containsKey("error"));
    }

    /**
     * Simulates an OpenRouter 400 escaping to the controller synchronously
     * (e.g. raised before the SSE stream is committed). The response MUST be a
     * proper SSE error envelope, not a ProblemDetail JSON document.
     */
    @Test
    void streamingError_emitsSseErrorEventNotProblemDetail() throws IOException {
        // Given: an OpenRouter 400 with the real error body PULSE captured live
        String upstreamBody = "{\"error\":{\"message\":\"Invalid request: tool_calls must be an array\",\"type\":\"invalid_request_error\"}}";
        ChatStreamingException ex = new ChatStreamingException(
                ChatStreamingException.CODE_UPSTREAM_LLM_ERROR,
                "LLM provider returned HTTP 400",
                upstreamBody,
                null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        controller.handleChatStreamingException(ex, response);

        // Then: response content-type is text/event-stream, NOT
        // application/problem+json. This is the load-bearing assertion for
        // BUG-2026-05-25-57.
        assertEquals(HttpStatus.OK.value(), response.getStatus(),
                "SSE responses use 200 OK with structured error frames, not 4xx/5xx");
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, response.getContentType(),
                "must be text/event-stream — NOT application/problem+json");
        assertNotEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, response.getContentType(),
                "must NOT be ProblemDetail (the BUG-57 broken behavior)");

        String body = response.getContentAsString();
        assertTrue(body.startsWith("event: error\n"),
                "body must start with SSE `event: error` line, was: " + body);
        assertTrue(body.contains("\ndata: "),
                "body must contain SSE `data:` line, was: " + body);
        assertTrue(body.endsWith("\n\n"),
                "SSE frame must terminate with blank line, was: " + body);

        // Parse the JSON payload inside the `data: ` line and verify the
        // structured shape the chat panel relies on.
        int dataStart = body.indexOf("data: ") + "data: ".length();
        int dataEnd = body.indexOf("\n\n", dataStart);
        String json = body.substring(dataStart, dataEnd);
        JsonNode payload = objectMapper.readTree(json);

        assertEquals(ChatStreamingException.CODE_UPSTREAM_LLM_ERROR, payload.get("code").asText());
        assertEquals("LLM provider returned HTTP 400", payload.get("message").asText());
        assertTrue(payload.has("cause"), "payload must include cause chain");
        assertEquals(upstreamBody, payload.get("upstream").asText(),
                "upstream OpenRouter error body must be surfaced verbatim so the operator sees the real cause");
    }

    /**
     * Simulates a streaming IOException escaping to the controller. Must emit
     * a structured SSE error envelope using the CODE_STREAM_INTERRUPTED code.
     */
    @Test
    void streamingIoException_emitsSseErrorWithInterruptedCode() throws IOException {
        // Given
        IOException ex = new IOException("Server returned HTTP response code: 400 for URL: https://openrouter.ai/api/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        controller.handleStreamingIoException(ex, response);

        // Then
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.startsWith("event: error\n"));

        int dataStart = body.indexOf("data: ") + "data: ".length();
        int dataEnd = body.indexOf("\n\n", dataStart);
        String json = body.substring(dataStart, dataEnd);
        JsonNode payload = objectMapper.readTree(json);
        assertEquals(ChatStreamingException.CODE_STREAM_INTERRUPTED, payload.get("code").asText());
        assertTrue(payload.get("message").asText().contains("400"));
    }

    /**
     * If the response is ALREADY committed (e.g. the SSE stream was already
     * mid-flight on the background thread), the controller must NOT try to
     * re-write headers — {@link ChatService#sendMessage} owns that error frame
     * via {@code writeSseError}. This guards against double-writes corrupting
     * the stream.
     */
    @Test
    void streamingError_skipsRewriteIfResponseAlreadyCommitted() throws IOException {
        // Given
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        ChatStreamingException ex = new ChatStreamingException(
                ChatStreamingException.CODE_STREAM_ERROR, "boom", null);

        // When
        controller.handleChatStreamingException(ex, response);

        // Then: no body written, no content-type override
        assertEquals("", response.getContentAsString(),
                "must not write to a response that's already committed mid-stream");
    }
}
