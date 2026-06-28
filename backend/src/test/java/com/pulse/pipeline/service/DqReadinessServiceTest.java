package com.pulse.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.support.FakeLlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DqReadinessService}.
 *
 * <p>The service speaks to an OpenAI-compatible chat endpoint via {@link java.net.HttpURLConnection}
 * directly — it does not go through a swappable {@code LlmClient} bean. Tests therefore stand up an
 * in-process {@link HttpServer} bound to {@code 127.0.0.1:0} (random free port) and override
 * the service's {@code @Value}-wired {@code baseUrl} via {@link ReflectionTestUtils#setField}.
 * The stubbed endpoint serves recorded JSON payloads loaded from the shared
 * {@link FakeLlmClient} fixture at
 * {@code backend/src/test/resources/test-fixtures/llm-responses/dq-readiness-known.json}.
 *
 * <p><strong>How to refresh the fixture:</strong> edit {@code dq-readiness-known.json} directly —
 * each top-level recording is keyed by a human-readable {@code requestSignature} and a
 * {@code match.userPromptContains} substring. The {@link FakeLlmClient} loader is the canonical
 * way these recordings are read; this test calls
 * {@link FakeLlmClient#loadFromClasspath(String)} and then routes each HTTP request through
 * {@link FakeLlmClient#respond(FakeLlmClient.Request)} so the fixture format stays unified across
 * chat / schema-inference / DQ tests.
 *
 * <p>Test cases (per TASK_P0_dq_controller_and_readiness):
 * <ul>
 *   <li>{@code TC_dq_readiness_llm_timeout_handled} — LLM timeout / 5xx / malformed-JSON paths
 *       are swallowed and surfaced as a structured {@code score:0 + reasoning} payload rather
 *       than thrown.</li>
 *   <li>Deterministic scoring against the known fixture composition.</li>
 *   <li>{@code parseResponse} unit coverage (happy, markdown-fenced, malformed, empty).</li>
 *   <li>{@code getScore} reads the persisted score and never invokes the LLM.</li>
 *   <li>{@code evaluate} on an unknown version raises {@link ResourceNotFoundException}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DqReadinessServiceTest {

    private static final String FIXTURE_PATH = "test-fixtures/llm-responses/dq-readiness-known.json";

    @Mock private PipelineVersionRepository versionRepo;
    @Mock private SubPipelineInstanceRepository instanceRepo;
    @Mock private PortWiringRepository wiringRepo;
    @Mock private DatasetRepository datasetRepo;

    private ObjectMapper objectMapper;
    private DqReadinessService service;
    private FakeLlmClient fakeLlmClient;
    private HttpServer httpServer;
    /** Per-request status code the embedded server should return next. */
    private volatile int nextStatus = 200;
    /** When non-null, raw body to return regardless of fixture matching. */
    private volatile String nextRawBody = null;
    /** Counter so getScore tests can prove no HTTP call was issued. */
    private AtomicInteger httpHits;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        service = new DqReadinessService(objectMapper, versionRepo, instanceRepo, wiringRepo, datasetRepo);
        fakeLlmClient = new FakeLlmClient(objectMapper).loadFromClasspath(FIXTURE_PATH);
        httpHits = new AtomicInteger(0);

        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/chat/completions", exchange -> {
            httpHits.incrementAndGet();
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            int status = nextStatus;
            byte[] response;
            if (nextRawBody != null) {
                response = nextRawBody.getBytes(StandardCharsets.UTF_8);
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = objectMapper.readValue(requestBody, Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> messages = (List<Map<String, Object>>) req.get("messages");
                String userPrompt = "";
                String systemPrompt = "";
                for (Map<String, Object> m : messages) {
                    if ("user".equals(m.get("role"))) userPrompt = String.valueOf(m.get("content"));
                    if ("system".equals(m.get("role"))) systemPrompt = String.valueOf(m.get("content"));
                }
                String signature = pickSignatureForPrompt(userPrompt);
                FakeLlmClient.Request fakeReq = new FakeLlmClient.Request(
                        signature,
                        String.valueOf(req.getOrDefault("model", "")),
                        systemPrompt,
                        userPrompt,
                        Map.of());
                FakeLlmClient.Response fakeResp;
                try {
                    fakeResp = fakeLlmClient.respond(fakeReq);
                } catch (FakeLlmClient.UnrecordedRequestException e) {
                    // No matching fixture - fall back to a benign default so the test
                    // failure surfaces from the test body rather than from the server.
                    fakeResp = FakeLlmClient.Response.message(
                            "{\"score\":0,\"recommendations\":[],\"reasoning\":\"no recorded fixture for prompt\"}");
                }
                Map<String, Object> openAiShape = Map.of(
                        "choices", List.of(Map.of(
                                "message", Map.of(
                                        "role", "assistant",
                                        "content", fakeResp.content() == null ? "" : fakeResp.content()))));
                response = objectMapper.writeValueAsBytes(openAiShape);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        httpServer.start();

        // Wire the service to talk to the embedded server. Use a non-blank api-key so the
        // "no key configured" short-circuit does not fire.
        ReflectionTestUtils.setField(service, "apiKey", "test-key-not-real");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + httpServer.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", "test-model");
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        nextStatus = 200;
        nextRawBody = null;
    }

    /**
     * Map the outbound user prompt to one of the recorded {@code requestSignature} entries in
     * {@code dq-readiness-known.json}. We use simple markers on the prompt text (which embeds
     * each instance's blueprint key and name) so a refresh of the fixture only needs to keep
     * these markers stable.
     */
    private String pickSignatureForPrompt(String userPrompt) {
        if (userPrompt.contains("MALFORMED_PAYLOAD_MARKER")) {
            return "dq_readiness:malformed_json_payload";
        }
        if (userPrompt.contains("SOURCE_ONLY_MARKER")) {
            return "dq_readiness:source_only_no_transform";
        }
        if (userPrompt.contains("KNOWN_COMPOSITION_MARKER")) {
            return "dq_readiness:known_composition_score_72";
        }
        return "dq_readiness:empty_composition_score_0";
    }

    // ---------- evaluate: happy path against the recorded fixture ----------

    @Test
    void evaluate_knownComposition_returnsDeterministicScoreAndPersists() {
        PipelineVersion version = newVersion("v-known-1");
        when(versionRepo.findById("v-known-1")).thenReturn(Optional.of(version));
        when(versionRepo.save(any(PipelineVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        SubPipelineInstance ingestion = newInstance(
                "inst-1", "v-known-1", "FileIngestion",
                "KNOWN_COMPOSITION_MARKER Loan Master Ingestion", 1);
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("v-known-1"))
                .thenReturn(List.of(ingestion));
        when(wiringRepo.findByVersionIdOrderByCreatedAtAsc("v-known-1"))
                .thenReturn(Collections.<PortWiring>emptyList());

        Map<String, Object> result = service.evaluate("v-known-1");

        assertEquals(72, ((Number) result.get("score")).intValue(),
                "score must come from the recorded fixture and stay deterministic");
        assertNotNull(result.get("recommendations"), "recommendations field is required");
        assertNotNull(result.get("reasoning"), "reasoning field is required");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recs = (List<Map<String, Object>>) result.get("recommendations");
        assertFalse(recs.isEmpty(), "fixture supplies at least one recommendation");

        // Score must be clamped + persisted on the version row.
        ArgumentCaptor<PipelineVersion> saved = ArgumentCaptor.forClass(PipelineVersion.class);
        verify(versionRepo).save(saved.capture());
        assertEquals(Integer.valueOf(72), saved.getValue().getDqReadinessScore(),
                "persisted score must equal the clamped fixture score");
    }

    @Test
    void evaluate_emptyComposition_returnsZeroScoreWithReasoning() {
        PipelineVersion version = newVersion("v-empty-1");
        when(versionRepo.findById("v-empty-1")).thenReturn(Optional.of(version));
        when(versionRepo.save(any(PipelineVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("v-empty-1"))
                .thenReturn(Collections.<SubPipelineInstance>emptyList());
        when(wiringRepo.findByVersionIdOrderByCreatedAtAsc("v-empty-1"))
                .thenReturn(Collections.<PortWiring>emptyList());

        Map<String, Object> result = service.evaluate("v-empty-1");

        assertEquals(0, ((Number) result.get("score")).intValue());
        assertTrue(String.valueOf(result.get("reasoning")).length() > 0,
                "even at score 0 the user must see a reason");
    }

    @Test
    void evaluate_sourceOnlyComposition_returnsGuidance() {
        PipelineVersion version = newVersion("v-src-only");
        when(versionRepo.findById("v-src-only")).thenReturn(Optional.of(version));
        when(versionRepo.save(any(PipelineVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        SubPipelineInstance src = newInstance(
                "inst-src", "v-src-only", "FileIngestion",
                "SOURCE_ONLY_MARKER Source", 1);
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("v-src-only"))
                .thenReturn(List.of(src));
        when(wiringRepo.findByVersionIdOrderByCreatedAtAsc("v-src-only"))
                .thenReturn(Collections.<PortWiring>emptyList());

        Map<String, Object> result = service.evaluate("v-src-only");

        assertEquals(15, ((Number) result.get("score")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recs = (List<Map<String, Object>>) result.get("recommendations");
        assertEquals(1, recs.size(), "source-only path emits explicit guidance");
        assertTrue(String.valueOf(result.get("reasoning")).toLowerCase()
                        .contains("ingestion"),
                "reasoning explains why score is low");
    }

    // ---------- evaluate: LLM error / timeout / malformed-JSON handling ----------

    @Test
    void evaluate_llmReturns5xx_returnsControlledErrorNotException() {
        PipelineVersion version = newVersion("v-5xx");
        when(versionRepo.findById("v-5xx")).thenReturn(Optional.of(version));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("v-5xx"))
                .thenReturn(List.of(newInstance("i", "v-5xx", "FileIngestion",
                        "KNOWN_COMPOSITION_MARKER step", 1)));
        when(wiringRepo.findByVersionIdOrderByCreatedAtAsc("v-5xx"))
                .thenReturn(Collections.<PortWiring>emptyList());
        nextStatus = 503;
        nextRawBody = "{\"error\":{\"message\":\"upstream unavailable\"}}";

        Map<String, Object> result = service.evaluate("v-5xx");

        assertEquals(0, ((Number) result.get("score")).intValue(),
                "5xx must yield score=0, not a thrown exception");
        String reasoning = String.valueOf(result.get("reasoning"));
        assertTrue(reasoning.toLowerCase().contains("evaluation failed")
                        || reasoning.contains("503"),
                "reasoning must surface the upstream failure, got: " + reasoning);
        verify(versionRepo, never()).save(any(PipelineVersion.class));
    }

    @Test
    void evaluate_llmTimeout_returnsControlledErrorNotException() {
        PipelineVersion version = newVersion("v-timeout");
        when(versionRepo.findById("v-timeout")).thenReturn(Optional.of(version));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("v-timeout"))
                .thenReturn(List.of(newInstance("i", "v-timeout", "FileIngestion",
                        "KNOWN_COMPOSITION_MARKER step", 1)));
        when(wiringRepo.findByVersionIdOrderByCreatedAtAsc("v-timeout"))
                .thenReturn(Collections.<PortWiring>emptyList());
        // Point the service at an unrouteable port to simulate connect-time failure.
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:1");

        Map<String, Object> result = service.evaluate("v-timeout");

        assertEquals(0, ((Number) result.get("score")).intValue(),
                "timeout must yield score=0, not a thrown exception");
        assertTrue(String.valueOf(result.get("reasoning")).toLowerCase()
                        .contains("evaluation failed"),
                "reasoning must surface a controlled error message");
    }

    @Test
    void evaluate_llmReturnsMalformedJson_returnsParseErrorNotException() {
        PipelineVersion version = newVersion("v-malformed");
        when(versionRepo.findById("v-malformed")).thenReturn(Optional.of(version));
        when(versionRepo.save(any(PipelineVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("v-malformed"))
                .thenReturn(List.of(newInstance("i", "v-malformed", "FileIngestion",
                        "MALFORMED_PAYLOAD_MARKER step", 1)));
        when(wiringRepo.findByVersionIdOrderByCreatedAtAsc("v-malformed"))
                .thenReturn(Collections.<PortWiring>emptyList());

        Map<String, Object> result = service.evaluate("v-malformed");

        // Even though the LLM returned junk inside choices[0].message.content, parseResponse
        // catches the JSON-parse failure and yields a controlled score:0 result.
        assertEquals(0, ((Number) result.get("score")).intValue());
        String reasoning = String.valueOf(result.get("reasoning"));
        assertTrue(reasoning.toLowerCase().contains("parse") || reasoning.toLowerCase().contains("failed"),
                "reasoning must surface a parse_error / failure cue, got: " + reasoning);
    }

    // ---------- evaluate: missing version / unknown id ----------

    @Test
    void evaluate_unknownVersion_throwsResourceNotFound() {
        when(versionRepo.findById("nope")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.evaluate("nope"));
    }

    @Test
    void evaluate_blankApiKey_returnsStubResultWithoutNetworkCall() {
        ReflectionTestUtils.setField(service, "apiKey", "   ");
        PipelineVersion version = newVersion("v-nokey");
        when(versionRepo.findById("v-nokey")).thenReturn(Optional.of(version));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("v-nokey"))
                .thenReturn(Collections.<SubPipelineInstance>emptyList());

        int hitsBefore = httpHits.get();
        Map<String, Object> result = service.evaluate("v-nokey");
        int hitsAfter = httpHits.get();

        assertEquals(0, ((Number) result.get("score")).intValue());
        assertTrue(String.valueOf(result.get("reasoning")).toLowerCase()
                        .contains("api key"),
                "missing-key path must surface a clear reason");
        assertEquals(hitsBefore, hitsAfter,
                "no HTTP request should be issued when the API key is blank");
    }

    // ---------- getScore ----------

    @Test
    void getScore_returnsPersistedScore_andDoesNotCallLlm() {
        PipelineVersion version = newVersion("v-score-1");
        version.setDqReadinessScore(42);
        when(versionRepo.findById("v-score-1")).thenReturn(Optional.of(version));

        int hitsBefore = httpHits.get();
        Map<String, Object> result = service.getScore("v-score-1");
        int hitsAfter = httpHits.get();

        assertEquals(42, ((Number) result.get("score")).intValue());
        assertEquals(Boolean.TRUE, result.get("evaluated"));
        assertEquals("v-score-1", result.get("versionId"));
        assertEquals(hitsBefore, hitsAfter,
                "getScore must never invoke the LLM endpoint");
    }

    @Test
    void getScore_neverEvaluated_returnsSentinelMinusOne() {
        PipelineVersion version = newVersion("v-fresh");
        // dqReadinessScore left null
        when(versionRepo.findById("v-fresh")).thenReturn(Optional.of(version));

        Map<String, Object> result = service.getScore("v-fresh");

        assertEquals(-1, ((Number) result.get("score")).intValue(),
                "service contract: never-evaluated versions surface score=-1 / evaluated=false");
        assertEquals(Boolean.FALSE, result.get("evaluated"));
    }

    @Test
    void getScore_unknownVersion_throwsResourceNotFound() {
        when(versionRepo.findById("nope")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getScore("nope"));
    }

    // ---------- parseResponse: package-private unit coverage ----------

    @Test
    void parseResponse_strippedMarkdownFenceIsAccepted() {
        String fenced = "```json\n{\"score\": 88, \"recommendations\": [], \"reasoning\": \"ok\"}\n```";
        Map<String, Object> r = service.parseResponse(fenced);
        assertEquals(88, ((Number) r.get("score")).intValue());
        assertEquals("ok", r.get("reasoning"));
    }

    @Test
    void parseResponse_emptyContent_returnsZeroScore() {
        Map<String, Object> r = service.parseResponse("");
        assertEquals(0, ((Number) r.get("score")).intValue());
        assertTrue(String.valueOf(r.get("reasoning")).toLowerCase().contains("empty"));
    }

    @Test
    void parseResponse_malformedJson_returnsZeroScoreWithReason() {
        Map<String, Object> r = service.parseResponse("{this is not json");
        assertEquals(0, ((Number) r.get("score")).intValue());
        assertTrue(String.valueOf(r.get("reasoning")).toLowerCase().contains("parse")
                        || String.valueOf(r.get("reasoning")).toLowerCase().contains("failed"));
    }

    @Test
    void parseResponse_missingFieldsAreFilledWithDefaults() {
        Map<String, Object> r = service.parseResponse("{\"score\": 50}");
        assertEquals(50, ((Number) r.get("score")).intValue());
        assertNotNull(r.get("recommendations"), "recommendations defaulted to empty list");
        assertNotNull(r.get("reasoning"), "reasoning defaulted to empty string");
    }

    // ---------- buildPrompt smoke ----------

    @Test
    void buildPrompt_includesInstancesWiringAndClassifications() {
        SubPipelineInstance a = newInstance("a", "v1", "FileIngestion", "Load CSV", 1);
        a.setOutputSchema(Map.of("columns", List.of(Map.of("name", "id", "type", "string"))));
        SubPipelineInstance b = newInstance("b", "v1", "GenericFilter", "Clean", 2);
        PortWiring w = new PortWiring();
        w.setVersionId("v1");
        w.setSourceInstanceId("a");
        w.setSourcePortName("output");
        w.setTargetInstanceId("b");
        w.setTargetPortName("input");

        String prompt = service.buildPrompt(List.of(a, b), List.of(w), Map.of("acme.loans", "PII"));

        assertTrue(prompt.contains("Load CSV"), "prompt must include instance name");
        assertTrue(prompt.contains("Clean"), "prompt must include downstream instance name");
        assertTrue(prompt.contains("FileIngestion"), "blueprintKey must surface so the LLM can reason");
        assertTrue(prompt.contains("Pipeline Wiring"), "wiring section header must be present");
        assertTrue(prompt.contains("Data Classifications"), "classifications section must be present");
        assertTrue(prompt.contains("PII"), "the actual classification must be present");
    }

    // ---------- helpers ----------

    private static PipelineVersion newVersion(String id) {
        PipelineVersion v = new PipelineVersion();
        v.setId(id);
        v.setPipelineId("pipe-" + id);
        v.setRevision(1);
        return v;
    }

    private static SubPipelineInstance newInstance(String id, String versionId,
                                                   String blueprintKey, String name, int order) {
        SubPipelineInstance inst = new SubPipelineInstance();
        inst.setId(id);
        inst.setPipelineId("pipe-" + versionId);
        inst.setVersionId(versionId);
        inst.setBlueprintId("bp-" + blueprintKey);
        inst.setBlueprintKey(blueprintKey);
        inst.setBlueprintVersion("1.0.0");
        inst.setName(name);
        inst.setExecutionOrder(order);
        inst.setParams(new LinkedHashMap<>());
        inst.setDqExpectations(new ArrayList<>());
        return inst;
    }

    /** Helper used by future contributors to round-trip the fixture for sanity. */
    @SuppressWarnings("unused")
    private static URI fixtureUri() {
        return URI.create("classpath:" + FIXTURE_PATH);
    }
}
