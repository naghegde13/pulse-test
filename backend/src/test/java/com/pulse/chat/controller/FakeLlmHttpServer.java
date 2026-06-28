package com.pulse.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.support.FakeLlmClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process fake LLM HTTP server used by {@link ChatControllerSseContractTest}.
 *
 * <p>{@code ChatService} calls an OpenAI-compatible chat-completions endpoint directly via
 * {@link java.net.HttpURLConnection} (see Wave 0 {@link FakeLlmClient} class javadoc). The
 * service has no injectable LLM seam, so we cannot wire {@link FakeLlmClient} into it
 * directly. Instead, this server:
 *
 * <ol>
 *   <li>Stands up a tiny {@link com.sun.net.httpserver.HttpServer} on a random port.</li>
 *   <li>On {@code POST /chat/completions} it pops the next scripted response from a queue.</li>
 *   <li>Each scripted response is either a streaming SSE body (mirroring the OpenRouter
 *       wire format) or an HTTP status (5xx) to simulate upstream failure.</li>
 * </ol>
 *
 * <p>Tests inject the server's URL into {@code pulse.llm.base-url} via
 * {@code @DynamicPropertySource}, and set {@code pulse.llm.api-key} to a non-empty value so
 * {@code ChatService} takes the LLM path rather than the local-reply fallback.
 *
 * <p>The {@link FakeLlmClient} value types ({@code Response}, {@code SseEvent}) are reused
 * where useful to keep the recording shape consistent with the shared Wave 0 fake, but the
 * server is intentionally simple — it does NOT pattern-match on the request signature.
 * Tests script responses in order via {@link #enqueueAssistantText},
 * {@link #enqueueToolCall}, and {@link #enqueueError}.
 */
public final class FakeLlmHttpServer {

    private final HttpServer server;
    private final List<ScriptedResponse> queue = new ArrayList<>();
    private final List<Map<String, Object>> receivedRequests = new ArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FakeLlmHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start fake LLM HTTP server", e);
        }
        server.createContext("/chat/completions", new ChatCompletionsHandler());
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-llm-http-server");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    public int callCount() {
        return callCount.get();
    }

    public List<Map<String, Object>> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    /**
     * Clears the scripted-response queue, the recorded-request log, and the call counter so
     * the same server instance can be reused across tests without restarting the HTTP socket.
     * Keeping one server alive avoids racing with Spring's environment binding of
     * {@code pulse.llm.base-url}.
     */
    public synchronized void resetQueue() {
        queue.clear();
        receivedRequests.clear();
        callCount.set(0);
    }

    /**
     * Queue a streaming response that produces a single plain text content delta and then
     * finishes with {@code finish_reason="stop"}. The exact text the assistant sends comes
     * back to the test via the SSE {@code chunk} events on the controller stream.
     */
    public FakeLlmHttpServer enqueueAssistantText(String text) {
        queue.add(ScriptedResponse.text(text));
        return this;
    }

    /**
     * Queue a streaming response that produces ONE tool call delta and then finishes with
     * {@code finish_reason="tool_calls"}. {@code toolName} is the OpenAI-style function name
     * (matches {@code ChatTools} definitions), {@code arguments} is the JSON-encoded argument
     * object the controller will pass to the tool executor.
     */
    public FakeLlmHttpServer enqueueToolCall(String toolName, Map<String, Object> arguments) {
        return enqueueToolCall(toolName, arguments, null);
    }

    /**
     * Queue a tool call with provider extension metadata. Vertex/Gemini's OpenAI-compatible
     * endpoint returns {@code extra_content.google.thought_signature} on function-call parts
     * and requires clients to replay it unchanged with the structured assistant tool call.
     */
    public FakeLlmHttpServer enqueueToolCall(
            String toolName,
            Map<String, Object> arguments,
            String vertexThoughtSignature) {
        try {
            String argsJson = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
            Map<String, Object> extraContent = vertexThoughtSignature == null || vertexThoughtSignature.isBlank()
                    ? Map.of()
                    : Map.of("google", Map.of("thought_signature", vertexThoughtSignature));
            queue.add(ScriptedResponse.toolCall(
                    "call_" + callCount.get() + "_" + toolName,
                    toolName,
                    argsJson,
                    extraContent));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode tool args", e);
        }
        return this;
    }

    /**
     * Queue a hard HTTP error so {@code ChatService.streamLLM} throws on
     * {@code conn.getInputStream()}. The outer try/catch in {@code sendMessage} then emits
     * an SSE {@code error} event — which is what we want to lock as the contract for
     * upstream LLM failures.
     */
    public FakeLlmHttpServer enqueueError(int httpStatus) {
        queue.add(ScriptedResponse.error(httpStatus));
        return this;
    }

    /** Two-step convenience: tool call, then a final assistant text after the tool result. */
    public FakeLlmHttpServer enqueueToolCallThenText(String toolName, Map<String, Object> arguments, String text) {
        enqueueToolCall(toolName, arguments);
        enqueueAssistantText(text);
        return this;
    }

    private final class ChatCompletionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                int idx = callCount.getAndIncrement();
                // Capture the request body so tests can introspect what the service sent.
                byte[] body = exchange.getRequestBody().readAllBytes();
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
                    receivedRequests.add(parsed);
                } catch (Exception parseError) {
                    Map<String, Object> raw = new LinkedHashMap<>();
                    raw.put("raw", new String(body, StandardCharsets.UTF_8));
                    receivedRequests.add(raw);
                }

                if (idx >= queue.size()) {
                    // Out-of-script call — return an empty-stream response so the test fails
                    // loudly with a "scripted N, called N+1" assertion rather than hanging.
                    sendEmptyStream(exchange);
                    return;
                }

                ScriptedResponse scripted = queue.get(idx);
                if (scripted.httpError > 0) {
                    exchange.sendResponseHeaders(scripted.httpError, -1);
                    exchange.close();
                    return;
                }

                exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders().add("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (String chunk : scripted.sseChunks) {
                        out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception ex) {
                exchange.close();
            }
        }

        private void sendEmptyStream(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private static final class ScriptedResponse {
        final List<String> sseChunks;
        final int httpError;

        private ScriptedResponse(List<String> sseChunks, int httpError) {
            this.sseChunks = sseChunks;
            this.httpError = httpError;
        }

        static ScriptedResponse text(String text) {
            // Mirror the OpenRouter wire format: a delta with content, then a chunk with
            // finish_reason="stop". ChatService.streamLLM forwards each content delta to the
            // frontend as an SSE `chunk` event.
            List<String> chunks = new ArrayList<>();
            // Split into two deltas so the test can see > 1 chunk arrive.
            int mid = Math.max(1, text.length() / 2);
            String first = text.substring(0, mid);
            String second = text.substring(mid);
            chunks.add(json(Map.of(
                    "choices", List.of(Map.of(
                            "index", 0,
                            "delta", Map.of("content", first))))));
            chunks.add(json(Map.of(
                    "choices", List.of(Map.of(
                            "index", 0,
                            "delta", Map.of("content", second))))));
            chunks.add(json(Map.of(
                    "choices", List.of(Map.of(
                            "index", 0,
                            "delta", Map.of(),
                            "finish_reason", "stop")))));
            return new ScriptedResponse(chunks, 0);
        }

        static ScriptedResponse toolCall(String callId, String name, String argsJson, Map<String, Object> extraContent) {
            List<String> chunks = new ArrayList<>();
            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("index", 0);
            toolCall.put("id", callId);
            toolCall.put("type", "function");
            toolCall.put("function", Map.of(
                    "name", name,
                    "arguments", argsJson));
            if (extraContent != null && !extraContent.isEmpty()) {
                toolCall.put("extra_content", extraContent);
            }
            chunks.add(json(Map.of(
                    "choices", List.of(Map.of(
                            "index", 0,
                            "delta", Map.of(
                                    "tool_calls", List.of(toolCall)))))));
            chunks.add(json(Map.of(
                    "choices", List.of(Map.of(
                            "index", 0,
                            "delta", Map.of(),
                            "finish_reason", "tool_calls")))));
            return new ScriptedResponse(chunks, 0);
        }

        static ScriptedResponse error(int httpStatus) {
            return new ScriptedResponse(List.of(), httpStatus);
        }

        private static String json(Map<String, Object> obj) {
            try {
                return new ObjectMapper().writeValueAsString(obj);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to encode SSE chunk", e);
            }
        }
    }
}
