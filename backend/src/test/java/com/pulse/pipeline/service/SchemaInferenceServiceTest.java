package com.pulse.pipeline.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.support.FakeLlmClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SchemaInferenceService} covering the LLM-backed
 * inference fallback path.
 *
 * <p><b>Architecture note.</b> {@code SchemaInferenceService} talks to the
 * LLM via raw {@code HttpURLConnection}; it does not accept an injectable
 * client. We therefore stand up a local {@link HttpServer} that plays the
 * role of OpenRouter and replays canned bodies sourced from the shared
 * {@link FakeLlmClient} recording fixtures
 * ({@code test-fixtures/llm-responses/schema-inference-*.json}). The service's
 * {@code baseUrl} / {@code apiKey} / {@code model} fields are pointed at the
 * local server via reflection. No live network calls are made.
 *
 * <h2>Empty-response contract (LOCKED here)</h2>
 * <p>The current behavior of {@code SchemaInferenceService.inferOutputSchema}
 * for the following degenerate LLM responses is:
 * <ul>
 *   <li><b>Empty {@code content} string</b> &mdash; {@code parseSchemaResponse}
 *       short-circuits on {@code response.isBlank()} and returns {@code null}.
 *       {@code inferOutputSchema} returns that {@code null} to the caller.</li>
 *   <li><b>Whitespace-only {@code content}</b> &mdash; same path: {@code isBlank()}
 *       is true, returns {@code null}.</li>
 *   <li><b>Malformed (non-JSON) {@code content}</b> &mdash; Jackson throws inside
 *       {@code parseSchemaResponse}, which logs and returns {@code null}. The
 *       outer {@code inferOutputSchema} forwards {@code null} to the caller.</li>
 *   <li><b>Truncated JSON</b> &mdash; same as malformed: {@code null}.</li>
 *   <li><b>Valid JSON missing the {@code columns} key</b> &mdash; logged as a
 *       warning and {@code null} returned.</li>
 *   <li><b>LLM HTTP 5xx</b> &mdash; {@code callLLM} throws, caught by
 *       {@code inferOutputSchema}'s catch-all, which returns {@code null}.</li>
 *   <li><b>OpenAI envelope with empty {@code choices}</b> &mdash; {@code callLLM}
 *       throws {@code "No choices in LLM response"}; outer catch returns
 *       {@code null}.</li>
 * </ul>
 *
 * <p><b>Contract summary:</b> every failure / degenerate path collapses to a
 * {@code null} return value. The service never throws, never returns a partial
 * schema, and never returns a sentinel object. Callers (e.g.
 * {@code SchemaPropagationService}) interpret {@code null} as "inference
 * unavailable; fall back to the static rule engine or surface a conflict".
 *
 * <p>If a future refactor introduces a richer result type (e.g.
 * {@code InferenceResult.empty()} vs {@code InferenceResult.parseError()}),
 * this test class will fail and force an explicit migration of all callers.
 */
class SchemaInferenceServiceTest {

    private static final String MODEL = "test/schema-infer-model";
    private static final String API_KEY = "test-api-key";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SchemaInferenceService service;
    private HttpServer server;
    private RecordingHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        service = new SchemaInferenceService(objectMapper);

        FakeLlmClient knownFixtures = new FakeLlmClient(objectMapper)
                .loadFromClasspath("test-fixtures/llm-responses/schema-inference-known.json");
        FakeLlmClient malformedFixtures = new FakeLlmClient(objectMapper)
                .loadFromClasspath("test-fixtures/llm-responses/schema-inference-malformed.json");

        handler = new RecordingHandler(objectMapper, List.of(knownFixtures, malformedFixtures));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();

        int port = server.getAddress().getPort();
        setField(service, "apiKey", API_KEY);
        setField(service, "baseUrl", "http://127.0.0.1:" + port);
        setField(service, "model", MODEL);

        // Ensure a clean cache between tests.
        service.invalidateCache();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------------
    // TC_schema_inference_happy_returns_parseable_schema
    // ---------------------------------------------------------------------

    @Test
    void happyPath_returnsParseableSchemaInOrder() {
        Map<String, Object> inputSchema = schema(
                col("loan_id", "long"),
                col("customer_name", "string"),
                col("payment_amount", "double"));
        Map<String, Object> params = Map.of("conditions", List.of(Map.of(
                "column", "customer_name", "operator", "=", "value", "John Doe")));

        Map<String, Object> result = service.inferOutputSchema(
                "GenericFilter", inputSchema, null, params);

        assertNotNull(result, "happy-path LLM response must yield a non-null schema");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) result.get("columns");
        assertEquals(3, columns.size());

        assertEquals("loan_id", columns.get(0).get("name"));
        assertEquals("long", columns.get(0).get("type"));

        // Mixed-casing column name in the LLM response is preserved verbatim,
        // proving the parser does not lowercase / normalize.
        assertEquals("Customer_Name", columns.get(1).get("name"));
        assertEquals("string", columns.get(1).get("type"));

        assertEquals("payment_amount", columns.get(2).get("name"));
        assertEquals("double", columns.get(2).get("type"));
    }

    @Test
    void happyPath_stripsMarkdownFencesAndPreservesMixedCaseColumns() {
        // The fixture wraps the JSON in ```json ... ``` fences; the service's
        // parseSchemaResponse must strip them. Column names also vary in case.
        Map<String, Object> inputSchema = schema(col("Order_ID", "long"));
        Map<String, Object> params = Map.of("MIXED_CASE_BLUEPRINT_marker", true);

        Map<String, Object> result = service.inferOutputSchema(
                "MIXED_CASE_BLUEPRINT", inputSchema, null, params);

        assertNotNull(result, "fenced response must be parsed after fence stripping");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) result.get("columns");
        assertEquals(3, columns.size());
        assertEquals("Order_ID", columns.get(0).get("name"));
        assertEquals("customerName", columns.get(1).get("name"));
        assertEquals("TOTAL_AMOUNT", columns.get(2).get("name"));
    }

    // ---------------------------------------------------------------------
    // TC_schema_inference_timeout_surfaces_controlled_error
    // ---------------------------------------------------------------------

    @Test
    void llmFailure_returnsNullWithoutPropagatingException() {
        // The handler treats a request with this marker as an HTTP 502 (Bad
        // Gateway), simulating a transient LLM unavailability. The service must
        // swallow the exception and return null so callers can render a
        // controlled fallback.
        Map<String, Object> inputSchema = schema(col("id", "long"));
        Map<String, Object> params = Map.of("FORCE_5XX", true);

        Map<String, Object> result = service.inferOutputSchema(
                "GenericFilter", inputSchema, null, params);

        assertNull(result, "5xx from LLM must collapse to null, not throw");
    }

    @Test
    void llmEmptyChoices_returnsNullWithoutPropagatingException() {
        // OpenAI-compatible envelope with an empty `choices` array makes the
        // service throw internally ("No choices in LLM response"). The outer
        // catch-all must swallow it and return null.
        Map<String, Object> inputSchema = schema(col("id", "long"));
        Map<String, Object> params = Map.of("FORCE_EMPTY_CHOICES", true);

        Map<String, Object> result = service.inferOutputSchema(
                "GenericFilter", inputSchema, null, params);

        assertNull(result, "missing choices in envelope must collapse to null");
    }

    // ---------------------------------------------------------------------
    // TC_schema_inference_malformed_json_parse_error
    // ---------------------------------------------------------------------

    @Test
    void malformedJson_returnsNullParseError() {
        Map<String, Object> inputSchema = schema(col("id", "long"));
        Map<String, Object> params = Map.of("MALFORMED_NON_JSON_marker", true);

        Map<String, Object> result = service.inferOutputSchema(
                "GenericFilter", inputSchema, null, params);

        assertNull(result, "non-JSON LLM content must collapse to null (parse_error contract)");
    }

    @Test
    void truncatedJson_returnsNullParseError() {
        Map<String, Object> inputSchema = schema(col("id", "long"));
        Map<String, Object> params = Map.of("MALFORMED_TRUNCATED_marker", true);

        Map<String, Object> result = service.inferOutputSchema(
                "GenericFilter", inputSchema, null, params);

        assertNull(result, "truncated JSON LLM content must collapse to null");
    }

    @Test
    void validJsonMissingColumnsKey_returnsNull() {
        Map<String, Object> inputSchema = schema(col("id", "long"));
        Map<String, Object> params = Map.of("MALFORMED_MISSING_COLUMNS_marker", true);

        Map<String, Object> result = service.inferOutputSchema(
                "GenericFilter", inputSchema, null, params);

        assertNull(result, "valid JSON without 'columns' key must collapse to null");
    }

    // ---------------------------------------------------------------------
    // TC_schema_inference_empty_response_controlled
    // ---------------------------------------------------------------------

    @Test
    void emptyResponseBody_returnsNullPerCurrentContract() {
        // Locks the current contract: empty LLM content -> null. This is the
        // "controlled empty result" mentioned in the task spec — the service
        // never lets callers mistake silence for success.
        Map<String, Object> inputSchema = schema(col("id", "long"));
        Map<String, Object> params = Map.of("EMPTY_BODY_BLUEPRINT_marker", true);

        Map<String, Object> result = service.inferOutputSchema(
                "GenericFilter", inputSchema, null, params);

        assertNull(result, "empty LLM content must collapse to null per current contract");
    }

    @Test
    void whitespaceOnlyResponseBody_returnsNullPerCurrentContract() {
        // Whitespace-only is treated the same as empty.
        Map<String, Object> inputSchema = schema(col("id", "long"));
        Map<String, Object> params = Map.of("WHITESPACE_BODY_BLUEPRINT_marker", true);

        Map<String, Object> result = service.inferOutputSchema(
                "GenericFilter", inputSchema, null, params);

        assertNull(result, "whitespace-only LLM content must collapse to null");
    }

    // ---------------------------------------------------------------------
    // TC_schema_inference_request_includes_blueprint_context
    // ---------------------------------------------------------------------

    @Test
    void requestPayload_includesBlueprintKeyInputSchemaAndParams() throws Exception {
        Map<String, Object> inputSchema = schema(
                col("loan_id", "long"),
                col("customer_name", "string"),
                col("payment_amount", "double"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", "visual");
        params.put("conditions", List.of(Map.of(
                "column", "customer_name", "operator", "=", "value", "John Doe")));

        Map<String, Object> result = service.inferOutputSchema(
                "GenericFilter", inputSchema, null, params);
        assertNotNull(result);

        // Inspect the request body the service actually sent to the LLM.
        RecordedRequest captured = handler.lastRequest();
        assertNotNull(captured, "service must have made one HTTP call");

        // Model the service was configured with.
        assertEquals(MODEL, captured.body.get("model"));
        assertEquals("Bearer " + API_KEY, captured.authorization,
                "authorization header must carry the configured api key");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) captured.body.get("messages");
        assertNotNull(messages);
        assertEquals(2, messages.size(), "expected system + user messages");
        String userPrompt = (String) messages.get(1).get("content");
        assertNotNull(userPrompt, "user prompt must be present");

        // blueprintKey context — appears verbatim in the prompt header.
        assertTrue(userPrompt.contains("GenericFilter"),
                "request prompt must include the blueprintKey, got: " + userPrompt);

        // inputSchema context — column names must be present.
        assertTrue(userPrompt.contains("loan_id"), "prompt must include input schema column 'loan_id'");
        assertTrue(userPrompt.contains("customer_name"), "prompt must include input schema column 'customer_name'");
        assertTrue(userPrompt.contains("payment_amount"), "prompt must include input schema column 'payment_amount'");

        // params context — the operator/value chosen must be present.
        assertTrue(userPrompt.contains("John Doe"),
                "prompt must include params context, got: " + userPrompt);
        assertTrue(userPrompt.contains("visual"),
                "prompt must include params context, got: " + userPrompt);

        // Section labels — locks the buildPrompt contract that the
        // SchemaPropagationService caller relies on.
        assertTrue(userPrompt.contains("## Transform:"),
                "prompt must include the '## Transform:' section header");
        assertTrue(userPrompt.contains("## Input Schema"),
                "prompt must include the '## Input Schema' section header");
        assertTrue(userPrompt.contains("## Transform Parameters"),
                "prompt must include the '## Transform Parameters' section header");
        // Secondary schema was null — that section must be omitted.
        assertFalse(userPrompt.contains("## Secondary Input Schema"),
                "secondary schema section must be omitted when null");
    }

    @Test
    void buildPrompt_includesSecondarySchemaSectionWhenProvided() {
        Map<String, Object> left = schema(col("id", "long"));
        Map<String, Object> right = schema(col("ref_id", "long"));
        Map<String, Object> params = Map.of("join_type", "inner", "keys", List.of("id"));

        String prompt = service.buildPrompt("GenericJoin", left, right, params);

        assertTrue(prompt.contains("GenericJoin"));
        assertTrue(prompt.contains("## Secondary Input Schema"),
                "secondary schema section must be present when a right input is supplied");
        assertTrue(prompt.contains("ref_id"),
                "secondary schema column names must be embedded in the prompt");
    }

    @Test
    void missingPrecondition_noApiKey_returnsNullWithoutHttpCall() throws Exception {
        // Precondition: no API key configured. The service must short-circuit
        // before any HTTP call and return null.
        setField(service, "apiKey", "");

        int callsBefore = handler.callCount();
        Map<String, Object> result = service.inferOutputSchema(
                "GenericFilter", schema(col("id", "long")), null, Map.of());

        assertNull(result, "missing api key must short-circuit to null");
        assertEquals(callsBefore, handler.callCount(),
                "no HTTP call may be made when api key is blank");
    }

    @Test
    void missingPrecondition_emptyInputSchema_returnsNullWithoutHttpCall() {
        // Precondition: no input schema. The service must short-circuit before
        // any HTTP call and return null.
        int callsBefore = handler.callCount();
        Map<String, Object> result = service.inferOutputSchema(
                "GenericFilter", Map.of(), null, Map.of());

        assertNull(result, "empty input schema must short-circuit to null");
        assertEquals(callsBefore, handler.callCount(),
                "no HTTP call may be made when input schema is empty");
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    @SafeVarargs
    private static Map<String, Object> schema(Map<String, Object>... columns) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("columns", List.of(columns));
        return s;
    }

    private static Map<String, Object> col(String name, String type) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("type", type);
        return c;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * In-memory record of a single request the service sent to the stub LLM.
     */
    private static final class RecordedRequest {
        final Map<String, Object> body;
        final String authorization;

        RecordedRequest(Map<String, Object> body, String authorization) {
            this.body = body;
            this.authorization = authorization;
        }
    }

    /**
     * Mini HTTP handler that plays the OpenRouter chat-completions endpoint by
     * pulling response bodies from FakeLlmClient recordings.
     */
    private static final class RecordingHandler implements HttpHandler {
        private final ObjectMapper objectMapper;
        private final List<FakeLlmClient> fixtures;
        private final CopyOnWriteArrayList<RecordedRequest> requests = new CopyOnWriteArrayList<>();

        RecordingHandler(ObjectMapper objectMapper, List<FakeLlmClient> fixtures) {
            this.objectMapper = objectMapper;
            this.fixtures = fixtures;
        }

        RecordedRequest lastRequest() {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }

        int callCount() {
            return requests.size();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            Map<String, Object> body = objectMapper.readValue(raw, new TypeReference<>() {});
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(new RecordedRequest(body, auth));

            String userPrompt = extractUserPrompt(body);

            // Force-failure markers bypass the FakeLlmClient lookup so we can
            // exercise the service's error-handling paths deterministically.
            if (userPrompt.contains("FORCE_5XX")) {
                respond(exchange, 502, "{\"error\":{\"message\":\"upstream LLM unavailable\"}}");
                return;
            }
            if (userPrompt.contains("FORCE_EMPTY_CHOICES")) {
                respond(exchange, 200, "{\"choices\":[]}");
                return;
            }

            String content = findRecordedContent(userPrompt);
            if (content == null) {
                respond(exchange, 500, "{\"error\":\"no fixture for prompt\"}");
                return;
            }

            Map<String, Object> envelope = new HashMap<>();
            envelope.put("choices", List.of(Map.of(
                    "message", Map.of("role", "assistant", "content", content))));
            respond(exchange, 200, objectMapper.writeValueAsString(envelope));
        }

        @SuppressWarnings("unchecked")
        private static String extractUserPrompt(Map<String, Object> body) {
            List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
            if (messages == null) return "";
            for (Map<String, Object> m : messages) {
                if ("user".equals(m.get("role"))) {
                    Object c = m.get("content");
                    return c == null ? "" : c.toString();
                }
            }
            return "";
        }

        private String findRecordedContent(String userPrompt) {
            // Routing: pick the recording whose userPromptContains needle is
            // the LONGEST substring present in the prompt. This makes the
            // test-specific markers (e.g. "MALFORMED_NON_JSON_marker",
            // "EMPTY_BODY_BLUEPRINT_marker") deterministically beat generic
            // blueprint-key needles ("GenericFilter") that happen to also
            // appear in every prompt header.
            FakeLlmClient.Recording best = null;
            FakeLlmClient bestFixture = null;
            int bestLen = -1;
            for (FakeLlmClient fx : fixtures) {
                for (Map.Entry<String, FakeLlmClient.Recording> entry : recordings(fx).entrySet()) {
                    FakeLlmClient.Recording rec = entry.getValue();
                    String needle = rec.match().userPromptContains();
                    if (needle != null && userPrompt.contains(needle) && needle.length() > bestLen) {
                        best = rec;
                        bestFixture = fx;
                        bestLen = needle.length();
                    }
                }
            }
            if (best == null) {
                return null;
            }
            FakeLlmClient.Request req = FakeLlmClient.Request.of(
                    best.requestSignature(), MODEL, "system", userPrompt);
            FakeLlmClient.Response resp = bestFixture.respond(req);
            return resp.content();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, FakeLlmClient.Recording> recordings(FakeLlmClient fx) {
            try {
                Field f = FakeLlmClient.class.getDeclaredField("bySignature");
                f.setAccessible(true);
                return (Map<String, FakeLlmClient.Recording>) f.get(fx);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot introspect FakeLlmClient recordings", e);
            }
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
