package com.pulse.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.chat.controller.FakeLlmHttpServer;
import com.pulse.chat.repository.ChatMessageRepository;
import com.pulse.chat.repository.ChatSessionRepository;
import com.pulse.chat.service.ChatService;
import com.pulse.command.model.PlanStatus;
import com.pulse.command.repository.PlanRepository;
import com.pulse.command.service.PlanService;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.support.FakeLlmClient;
import com.pulse.support.SeedFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the default chat entrypoint through the REAL graph path (ADR 0025).
 * The test intentionally does not set {@code pulse.chat.orchestration}; a chat turn is driven by
 * {@link GraphDriver} through the {@link CompositionGraph} StateGraph. Asserts a
 * composition-mutating turn stages an op (candidate_graph), opens the
 * Plan-Preview gate (plan event + PREVIEW plan, nothing applied yet), and that
 * the decision endpoint approve applies it to canonical.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GraphDriverTurnTest {

    private static FakeLlmHttpServer fakeServer;

    @DynamicPropertySource
    static void registerLlmServer(DynamicPropertyRegistry registry) {
        if (fakeServer == null) {
            fakeServer = new FakeLlmHttpServer();
        }
        registry.add("pulse.llm.base-url", () -> fakeServer.baseUrl());
        registry.add("pulse.llm.api-key", () -> "test-llm-key");
        registry.add("pulse.llm.model", () -> "test/fake-model");
    }

    @AfterAll
    static void shutdownServer() {
        if (fakeServer != null) {
            fakeServer.stop();
            fakeServer = null;
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private ChatSessionRepository chatSessionRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ChatService chatService;
    @Autowired private PlanRepository planRepository;
    @Autowired private PlanService planService;
    @Autowired private CompositionService compositionService;

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DomainRepository domainRepository;
    @Autowired private SystemOfRecordRepository sorRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired private ConnectorInstanceRepository connectorInstanceRepository;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private SubPipelineInstanceRepository subPipelineInstanceRepository;
    @Autowired private PortWiringRepository portWiringRepository;

    private SeedFixtures seed;

    @BeforeEach
    void setUp() {
        seed = new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                subPipelineInstanceRepository, portWiringRepository);
        new FakeLlmClient().loadFromClasspath("test-fixtures/llm-responses/chat-tool-loop-happy.json");
    }

    @AfterEach
    void resetQueue() {
        if (fakeServer != null) fakeServer.resetQueue();
    }

    @Test
    void defaultChatServicePathStagesMutationOpensPlanGateAndApplies() throws Exception {
        assertTrue(chatService.graphMode(),
                "default ChatService path must use LangGraph4j graph mode");

        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        // A composition-addable blueprint the mutation tool can stage.
        seed.seedBlueprint("GraphSource", BlueprintCategory.INGESTION,
                List.of(), List.of(Map.of("name", "out")));

        var session = new com.pulse.chat.model.ChatSession();
        session.setTenantId(ctx.tenantId());
        session.setUserId(ctx.userId());
        session.setPipelineId(ctx.pipelineId());
        String sessionId = chatSessionRepository.save(session).getId();

        fakeServer.resetQueue();
        // Composer stages one add op via the op-emitting mutation tool, then ends with text.
        fakeServer
                .enqueueToolCall("add_blueprint_instance", Map.of(
                        "instance_name", "ingest",
                        "blueprint_key", "GraphSource",
                        "storage_backend", "DPC",
                        "reasoning", "we need a source"))
                .enqueueAssistantText("Staged a source step; review the Plan Preview.");

        SseStream stream = sendMessage(sessionId, ctx.tenantId(), "Add a source step.");
        List<String> names = stream.eventNames();

        // The graph path emits the SAME core events + the new staging gate events.
        assertTrue(names.contains("tool_call"), "tool_call emitted: " + names);
        assertTrue(names.contains("candidate_graph"),
                "process_operations drained -> candidate_graph emitted: " + names);
        assertTrue(names.contains("plan"), "Plan-Preview gate -> plan event emitted: " + names);
        assertEquals("done", names.get(names.size() - 1), "done is last: " + names);
        Map<String, Object> planEvent = stream.jsonEvent("plan");
        assertEquals("PREVIEW", planEvent.get("status"));
        assertEquals(1, ((Number) planEvent.get("commandCount")).intValue());
        assertTrue(String.valueOf(planEvent.get("summary")).contains("Review 1 staged composition change"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) planEvent.get("steps");
        assertEquals(1, steps.size(), "plan event carries previewable command steps");
        assertEquals("composition.addInstance", steps.get(0).get("op"));
        assertEquals("ingest", steps.get(0).get("instanceRef"));
        assertEquals("GraphSource", steps.get(0).get("blueprintKey"));

        // A PREVIEW plan exists for the session; canonical is still empty (gated).
        var previews = planService.listByPipeline(ctx.pipelineId()).stream()
                .filter(p -> p.getStatus() == PlanStatus.PREVIEW)
                .toList();
        assertEquals(1, previews.size(), "exactly one PREVIEW plan staged");
        assertTrue(compositionService.getComposition(ctx.versionId()).instances().isEmpty(),
                "nothing written to canonical until the decision endpoint approves");

        // Approve via the decision endpoint -> canonical write.
        String planId = previews.get(0).getId();
        Map<String, Object> decisionResp = postDecision(sessionId, planId, "approve");
        assertEquals("APPLIED", decisionResp.get("status"), "approve applies the plan");
        var names2 = compositionService.getComposition(ctx.versionId()).instances().stream()
                .map(com.pulse.pipeline.model.SubPipelineInstance::getName).toList();
        assertTrue(names2.contains("ingest"), "approved op wrote canonical: " + names2);
    }

    // ---- helpers (SSE reader + decision POST) ----

    private SseStream sendMessage(String sessionId, String tenantId, String content) throws Exception {
        String url = "http://127.0.0.1:" + port + "/api/v1/chat/sessions/" + sessionId + "/messages";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setDoOutput(true);
        conn.setReadTimeout(15_000);
        conn.setConnectTimeout(5_000);
        String body = objectMapper.writeValueAsString(Map.of("content", content, "tenantId", tenantId));
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        List<String[]> events = new ArrayList<>();
        if (conn.getResponseCode() >= 400) return new SseStream(events);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line; String ev = null; StringBuilder data = new StringBuilder();
            try {
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (ev != null) events.add(new String[]{ev, data.toString()});
                        ev = null; data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        ev = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append("\n");
                        data.append(line.substring("data:".length()).trim());
                    }
                }
            } catch (java.io.IOException eof) { /* end of stream */ }
            if (ev != null) events.add(new String[]{ev, data.toString()});
        }
        return new SseStream(events);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postDecision(String sessionId, String planId, String decision) throws Exception {
        String url = "http://127.0.0.1:" + port + "/api/v1/chat/sessions/" + sessionId
                + "/plans/" + planId + "/decision";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setReadTimeout(15_000);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(objectMapper.writeValueAsString(Map.of("decision", decision))
                    .getBytes(StandardCharsets.UTF_8));
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return objectMapper.readValue(sb.toString(), Map.class);
        }
    }

    private record SseStream(List<String[]> events) {
        List<String> eventNames() {
            List<String> n = new ArrayList<>();
            for (String[] e : events) n.add(e[0]);
            return n;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> jsonEvent(String name) throws Exception {
            for (String[] event : events) {
                if (name.equals(event[0])) {
                    return new ObjectMapper().readValue(event[1], Map.class);
                }
            }
            return Map.of();
        }
    }
}
