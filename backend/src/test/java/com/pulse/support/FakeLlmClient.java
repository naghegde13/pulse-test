package com.pulse.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic fake LLM client used by Wave 1 chat / schema-inference / DQ readiness tests.
 *
 * <p>Why this exists: PULSE backend services call OpenAI-compatible chat completions endpoints
 * directly via {@code HttpURLConnection} rather than through a shared {@code LlmClient}
 * interface (see {@code ChatService}, {@code SchemaInferenceService}, {@code DqReadinessService}).
 * Unit tests therefore need a swap-in fake that replays recorded fixtures keyed by request
 * signature, not a Mockito-style mock. {@code FakeLlmClient} is that fake.
 *
 * <p>Recording format (LOCKED in this Wave 0 task):
 * <pre>
 * {
 *   "requestSignature": "schema_infer_csv_loan_master",   // human-readable label
 *   "match": {                                            // optional, all fields must match
 *     "model": "openai/gpt-5.2",
 *     "systemPromptStartsWith": "You are PULSE",
 *     "userPromptContains": "loan_master.csv"
 *   },
 *   "responses": [
 *     {                                                    // first call returns this
 *       "kind": "message",                                 // "message" or "tool_call"
 *       "content": "{\"columns\": [...]}",
 *       "events": [                                        // optional SSE event sequence
 *         {"event": "delta", "data": {"content": "{"}, "afterMillis": 0},
 *         {"event": "delta", "data": {"content": "\"columns\""}, "afterMillis": 5},
 *         {"event": "done",  "data": {}, "afterMillis": 10}
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>Multiple recordings live in a single JSON file, an array at the top level. The default
 * fixtures path is {@code test-fixtures/llm-responses/} on the test classpath. Tests may also
 * call {@link #record} programmatically, which avoids touching disk for ephemeral cases.
 *
 * <p>Determinism rules:
 * <ul>
 *   <li>Same {@link Request} returns the {@code i}-th element of {@code responses}, where
 *       {@code i} is the call counter for that signature. Calling more times than recorded
 *       throws.</li>
 *   <li>Unrecorded inputs throw {@link UnrecordedRequestException} naming the missing
 *       signature so downstream tests can record it.</li>
 *   <li>Set {@code PULSE_LLM_RECORDING_MODE=record} to capture real LLM calls — left as a
 *       seam for future fixtures, NOT implemented in this Wave 0 task.</li>
 * </ul>
 */
public final class FakeLlmClient {

    /** Env flag reserved for future record mode; currently informational only. */
    public static final String RECORDING_MODE_ENV = "PULSE_LLM_RECORDING_MODE";

    private final ObjectMapper objectMapper;
    private final Map<String, Recording> bySignature = new HashMap<>();
    private final Map<String, Integer> callCounts = new HashMap<>();
    private final List<Recording> matchAll = new ArrayList<>();

    public FakeLlmClient() {
        this(new ObjectMapper());
    }

    public FakeLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Loads recordings from a classpath JSON resource. The file is an array of recording
     * objects (see class javadoc). Returns {@code this} for chaining.
     */
    public FakeLlmClient loadFromClasspath(String resourcePath) {
        try (InputStream in = FakeLlmClient.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Recording fixture not found on classpath: " + resourcePath);
            }
            List<Map<String, Object>> raw = objectMapper.readValue(in, new TypeReference<>() {});
            for (Map<String, Object> obj : raw) {
                record(Recording.fromMap(obj));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load LLM fixtures from " + resourcePath, e);
        }
        return this;
    }

    /** Records a single fixture programmatically. */
    public FakeLlmClient record(Recording recording) {
        Objects.requireNonNull(recording, "recording");
        bySignature.put(recording.requestSignature(), recording);
        return this;
    }

    /** Convenience: record by signature with a single plain-message response. */
    public FakeLlmClient recordMessage(String signature, String content) {
        return record(new Recording(
                signature,
                MatchSpec.matchAll(),
                List.of(Response.message(content))));
    }

    /** Convenience: record by signature with a single tool-call response. */
    public FakeLlmClient recordToolCall(String signature, String toolName, Map<String, Object> arguments) {
        return record(new Recording(
                signature,
                MatchSpec.matchAll(),
                List.of(Response.toolCall(toolName, arguments))));
    }

    /**
     * Returns the next recorded response for a request. Throws {@link UnrecordedRequestException}
     * with the request signature in the message when nothing matches.
     */
    public Response respond(Request request) {
        Recording recording = findMatch(request);
        if (recording == null) {
            throw new UnrecordedRequestException(
                    "No FakeLlmClient recording matches request signature='"
                            + request.signature()
                            + "', model='" + request.model()
                            + "'. Add a recording via FakeLlmClient.record(...) or test-fixtures/llm-responses/*.json");
        }
        int idx = callCounts.merge(recording.requestSignature(), 1, Integer::sum) - 1;
        if (idx >= recording.responses().size()) {
            throw new UnrecordedRequestException(
                    "Recording '" + recording.requestSignature() + "' exhausted after "
                            + recording.responses().size() + " responses; got call #" + (idx + 1));
        }
        return recording.responses().get(idx);
    }

    /** Reset the call counter so the same recording can be replayed in a fresh test. */
    public void reset() {
        callCounts.clear();
    }

    /** Whether record mode is requested via env var. Provided for future record-and-replay loops. */
    public static boolean isRecordingMode() {
        String v = System.getenv(RECORDING_MODE_ENV);
        return v != null && "record".equalsIgnoreCase(v);
    }

    private Recording findMatch(Request request) {
        Recording bySig = bySignature.get(request.signature());
        if (bySig != null && bySig.match().matches(request)) {
            return bySig;
        }
        for (Recording r : matchAll) {
            if (r.match().matches(request)) {
                return r;
            }
        }
        // Final fallback — exact signature match without match-spec gating, for bare
        // recordMessage / recordToolCall recordings.
        return bySignature.get(request.signature());
    }

    // ---------- value types ----------

    /**
     * Request shape passed to {@link #respond}. Tests build one and call {@code respond(req)}.
     * The {@code signature} is the recording lookup key — it is the responsibility of the
     * service-under-test to choose a stable signature (e.g. {@code "chat:plan_apply:loan_master"}).
     */
    public record Request(String signature,
                          String model,
                          String systemPrompt,
                          String userPrompt,
                          Map<String, Object> extra) {

        public Request {
            Objects.requireNonNull(signature, "signature");
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }

        public static Request of(String signature, String model, String systemPrompt, String userPrompt) {
            return new Request(signature, model == null ? "" : model,
                    systemPrompt == null ? "" : systemPrompt,
                    userPrompt == null ? "" : userPrompt,
                    Map.of());
        }

        /**
         * Stable hash of (model, systemPrompt, userPrompt). Useful for seeing whether two
         * requests collapse to the same recording.
         */
        public String contentHash() {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update((model + "\n").getBytes(StandardCharsets.UTF_8));
                md.update((systemPrompt + "\n").getBytes(StandardCharsets.UTF_8));
                md.update((userPrompt + "\n").getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
                return sb.substring(0, 12);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }

    /**
     * Optional gating spec on a recording. All non-null fields must match the request.
     * {@code matchAll()} accepts everything.
     */
    public record MatchSpec(String model,
                            String systemPromptStartsWith,
                            String userPromptContains) {

        public static MatchSpec matchAll() {
            return new MatchSpec(null, null, null);
        }

        public boolean matches(Request request) {
            if (model != null && !model.equals(request.model())) return false;
            if (systemPromptStartsWith != null
                    && !request.systemPrompt().startsWith(systemPromptStartsWith)) return false;
            if (userPromptContains != null
                    && !request.userPrompt().contains(userPromptContains)) return false;
            return true;
        }
    }

    /**
     * One LLM response. Either a plain message ({@code content}) or a tool call
     * ({@code toolName} + {@code arguments}). Optional {@code events} carry a deterministic
     * SSE event sequence — the chat-controller test will iterate over these.
     */
    public record Response(Kind kind,
                           String content,
                           String toolName,
                           Map<String, Object> arguments,
                           List<SseEvent> events) {

        public enum Kind { MESSAGE, TOOL_CALL }

        public static Response message(String content) {
            return new Response(Kind.MESSAGE, content, null, null,
                    List.of(new SseEvent("delta", Map.of("content", content), 0L),
                            new SseEvent("done", Map.of(), 0L)));
        }

        public static Response toolCall(String toolName, Map<String, Object> arguments) {
            Map<String, Object> args = arguments == null ? Map.of() : Map.copyOf(arguments);
            return new Response(Kind.TOOL_CALL, null, toolName, args,
                    List.of(new SseEvent("tool_call",
                            Map.of("name", toolName, "arguments", args), 0L),
                            new SseEvent("done", Map.of(), 0L)));
        }

        public Deque<SseEvent> eventQueue() {
            return new ArrayDeque<>(events == null ? List.of() : events);
        }
    }

    /** A single SSE event in the recorded sequence. {@code afterMillis} simulates time-to-event. */
    public record SseEvent(String event, Map<String, Object> data, long afterMillis) { }

    /** A complete recording for one request signature. */
    public record Recording(String requestSignature, MatchSpec match, List<Response> responses) {

        public Recording {
            Objects.requireNonNull(requestSignature, "requestSignature");
            Objects.requireNonNull(responses, "responses");
            if (responses.isEmpty()) {
                throw new IllegalArgumentException(
                        "Recording '" + requestSignature + "' must have at least one response");
            }
            match = match == null ? MatchSpec.matchAll() : match;
        }

        @SuppressWarnings("unchecked")
        static Recording fromMap(Map<String, Object> obj) {
            String sig = (String) obj.get("requestSignature");
            Map<String, Object> matchObj = (Map<String, Object>) obj.get("match");
            MatchSpec spec = matchObj == null
                    ? MatchSpec.matchAll()
                    : new MatchSpec(
                            (String) matchObj.get("model"),
                            (String) matchObj.get("systemPromptStartsWith"),
                            (String) matchObj.get("userPromptContains"));
            List<Map<String, Object>> rawResponses = (List<Map<String, Object>>) obj.get("responses");
            List<Response> responses = new ArrayList<>(rawResponses.size());
            for (Map<String, Object> r : rawResponses) {
                String kindStr = ((String) r.getOrDefault("kind", "message")).toUpperCase(Locale.ROOT);
                Response.Kind kind = Response.Kind.valueOf(kindStr);
                List<Map<String, Object>> rawEvents = (List<Map<String, Object>>) r.get("events");
                List<SseEvent> events = new ArrayList<>();
                if (rawEvents != null) {
                    for (Map<String, Object> e : rawEvents) {
                        Map<String, Object> data = (Map<String, Object>) e.getOrDefault("data", Map.of());
                        // Use TreeMap so JSON-derived event payloads have deterministic ordering.
                        events.add(new SseEvent(
                                (String) e.get("event"),
                                new TreeMap<>(data),
                                ((Number) e.getOrDefault("afterMillis", 0)).longValue()));
                    }
                }
                if (kind == Response.Kind.TOOL_CALL) {
                    String toolName = (String) r.get("toolName");
                    Map<String, Object> args = (Map<String, Object>) r.getOrDefault("arguments", Map.of());
                    responses.add(new Response(kind, null, toolName, Map.copyOf(args),
                            events.isEmpty() ? Response.toolCall(toolName, args).events() : events));
                } else {
                    String content = (String) r.getOrDefault("content", "");
                    responses.add(new Response(kind, content, null, null,
                            events.isEmpty() ? Response.message(content).events() : events));
                }
            }
            return new Recording(sig, spec, List.copyOf(responses));
        }
    }

    /** Thrown when the test calls {@link #respond} with a request that has no recording. */
    public static class UnrecordedRequestException extends NoSuchElementException {
        public UnrecordedRequestException(String message) {
            super(message);
        }
    }
}
