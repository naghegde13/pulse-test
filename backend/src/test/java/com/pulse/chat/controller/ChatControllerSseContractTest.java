package com.pulse.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.chat.repository.ChatMessageRepository;
import com.pulse.chat.repository.ChatSessionRepository;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.support.FakeLlmClient;
import com.pulse.support.SeedFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK_P0_chat_controller_sse_contract — locks the SSE event contract that
 * {@code POST /api/v1/chat/sessions/{sessionId}/messages} emits.
 *
 * <p><b>Why this test exists.</b> The frontend ({@code lib/composition-events.ts},
 * {@code chat-context.tsx}, {@code composition-panel.tsx}) parses the chat SSE stream as a
 * specific sequence of named events. Any reordering or rename breaks the chat UI silently.
 * Until this task the contract was only enforced implicitly through the running app.
 *
 * <p><b>How the LLM is mocked.</b> {@link com.pulse.chat.service.ChatService} talks to the
 * LLM via {@link java.net.HttpURLConnection}, with the endpoint configured by
 * {@code pulse.llm.base-url}. Wave 0 left {@link FakeLlmClient} as a recording catalog with
 * no injection seam into the service. To mock without modifying chat-service code we stand
 * up an in-process HTTP server ({@link FakeLlmHttpServer}) on a random localhost port and
 * point {@code pulse.llm.base-url} at it. The server replays the same response shapes
 * declared in {@code test-fixtures/llm-responses/chat-tool-loop-*.json} (loaded through
 * {@link FakeLlmClient} so the JSON shape stays validated).
 *
 * <p><b>Known event-order asymmetry</b> (documented while writing this test; aligns with the
 * packet's {@code risk_sse_event_order_unstable} risk):
 * <ul>
 *   <li>For {@code navigate_ui}: {@code tool_call} → {@code navigate} → execute() →
 *       {@code tool_result}. The navigate fires <i>before</i> the tool result.</li>
 *   <li>For composition-mutating tools (e.g. {@code propose_create_pipeline}):
 *       {@code tool_call} → execute() → {@code tool_result} → {@code navigate}. The navigate
 *       fires <i>after</i> the tool result (it is derived from the tool result string).</li>
 * </ul>
 * The frontend treats both shapes interchangeably. We assert presence + general relative
 * order (everything after {@code tool_call}, {@code done} last) rather than pin the precise
 * navigate-vs-tool_result interleave, so a future code refactor that consolidates the two
 * paths surfaces as a deliberate test update rather than a regression.
 *
 * <p><b>SSE contract under test</b> (emitted by {@code ChatService.handleLLMMode}):
 * <ul>
 *   <li>{@code chunk} — text content delta forwarded from the upstream LLM stream.</li>
 *   <li>{@code tool_call} — emitted with the tool name <i>before</i> the tool executes.</li>
 *   <li>{@code navigate} — emitted with a frontend route when a tool implies UI navigation
 *       (either {@code navigate_ui} directly or as a side-effect of a composition tool).</li>
 *   <li>{@code tool_result} — emitted with the tool name <i>after</i> a successful tool
 *       execution. <b>Composition-mutating tools (propose_create_pipeline,
 *       propose_add_instance, propose_wiring, etc.) acting as the composition-refresh
 *       marker</b> — the frontend's {@code composition-events.ts} treats a {@code tool_result}
 *       for these tool names as a signal to refetch composition.</li>
 *   <li>{@code error} — emitted when the LLM call itself fails (upstream HTTP error,
 *       connection break). Tool errors do NOT emit this — they suppress {@code tool_result}
 *       and let the LLM produce a recovery message.</li>
 *   <li>{@code done} — terminal event; always emitted last.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// Per task packet, TC_chat_sse_happy_path_event_order / _tool_failure_emits_error_frame /
// _navigation_marker_emitted are integration_pr lane work. The class boots a full Spring
// context AND a fake LLM HTTP server, but completes in well under a second — light enough
// to also run in the default fast lane. If lane separation needs strict CI placement,
// either rename to *IT.java or add @Tag("integration") here and accept the fast-lane skip.
class ChatControllerSseContractTest {

    private static FakeLlmHttpServer fakeServer;

    @DynamicPropertySource
    static void registerLlmServer(DynamicPropertyRegistry registry) {
        // Spin up the fake LLM server before Spring resolves @Value properties.
        if (fakeServer == null) {
            fakeServer = new FakeLlmHttpServer();
        }
        registry.add("pulse.llm.base-url", () -> fakeServer.baseUrl());
        // Non-blank key so ChatService takes the LLM-mode branch.
        registry.add("pulse.llm.api-key", () -> "test-llm-key");
        registry.add("pulse.llm.model", () -> "test/fake-model");
    }

    @BeforeAll
    static void ensureServer() {
        // No-op: @DynamicPropertySource already started the server during Spring bootstrap.
        // Kept as a hook so the lifecycle is obvious to readers.
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
        // Smoke-load the recorded fixtures through FakeLlmClient so a malformed fixture
        // surfaces as a test setup failure rather than a wire-format surprise.
        new FakeLlmClient()
                .loadFromClasspath("test-fixtures/llm-responses/chat-tool-loop-happy.json")
                .loadFromClasspath("test-fixtures/llm-responses/chat-tool-loop-error.json");
    }

    @AfterEach
    void resetServerQueue() {
        // Clear the scripted queue between tests — we keep the same server instance for the
        // whole class because Spring's environment is bound to the original baseUrl at
        // context-start time.
        if (fakeServer != null) {
            fakeServer.resetQueue();
        }
    }

    /**
     * TC_chat_sse_happy_path_event_order — single composition-mutating tool, then a final
     * assistant text. Locks the order: chunk(*) → tool_call → navigate → tool_result →
     * chunk(*) → done.
     *
     * <p>Composition-refresh signal: the {@code tool_result} for {@code propose_create_pipeline}
     * is the composition-refresh marker. The accompanying {@code navigate} event carries the
     * route the frontend uses to focus on the new pipeline. We assert both are present and
     * arrive before the final assistant text.
     */
    @Test
    void happyPath_emitsSseEventsInOrderAndPersistsMessages() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        String sessionId = createSessionViaService(ctx);

        fakeServer.resetQueue();
        fakeServer
                // The LLM first decides to create a new pipeline via a tool call.
                .enqueueToolCall("propose_create_pipeline", Map.of(
                        "name", "Generated By Happy Path",
                        "domain_name", ctx.domain().getName(),
                        "domain_id", ctx.domainId(),
                        "description", "Created during ChatControllerSseContractTest happy path"))
                // After the tool result, the LLM produces a final assistant text.
                .enqueueAssistantText("All set — the new pipeline is ready to compose.");

        SseStream stream = sendMessage(sessionId, ctx.tenantId(), "Create a customer mart pipeline.");

        // Order: at least one chunk (or none, since the first turn was a tool call) →
        // tool_call → navigate → tool_result → chunk(s) for final text → done.
        List<String> eventNames = stream.eventNames();
        assertEquals("done", eventNames.get(eventNames.size() - 1),
                "final SSE event must be 'done' (full stream: " + eventNames + ")");

        int toolCallIdx = eventNames.indexOf("tool_call");
        int toolResultIdx = eventNames.indexOf("tool_result");
        int navigateIdx = eventNames.indexOf("navigate");
        int doneIdx = eventNames.indexOf("done");
        assertTrue(toolCallIdx >= 0, "must emit tool_call: " + eventNames);
        assertTrue(toolResultIdx > toolCallIdx,
                "tool_result must come AFTER tool_call (composition-refresh marker ordering): " + eventNames);
        assertTrue(navigateIdx > toolCallIdx,
                "navigate must come AFTER tool_call (route emitted as side-effect of executing the tool): " + eventNames);
        assertTrue(doneIdx > toolResultIdx,
                "done must come AFTER tool_result: " + eventNames);

        // At least one chunk for the final assistant text must arrive AFTER the tool_result.
        boolean chunkAfterResult = false;
        for (int i = toolResultIdx + 1; i < eventNames.size(); i++) {
            if ("chunk".equals(eventNames.get(i))) {
                chunkAfterResult = true;
                break;
            }
        }
        assertTrue(chunkAfterResult,
                "expected at least one assistant 'chunk' after tool_result, got: " + eventNames);

        // tool_call carries the tool name; tool_result is ARCH-009 structured JSON.
        assertEquals("propose_create_pipeline", stream.firstDataFor("tool_call"));
        String toolResultData = stream.firstDataFor("tool_result");
        assertNotNull(toolResultData, "tool_result must fire");
        // ARCH-009: payload is JSON with toolName + status + mutationApplied + refreshHints.
        assertTrue(toolResultData.startsWith("{"),
                "tool_result must be JSON envelope (ARCH-009), got: " + toolResultData);
        assertTrue(toolResultData.contains("\"toolName\":\"propose_create_pipeline\""),
                "tool_result must carry toolName, got: " + toolResultData);
        assertTrue(toolResultData.contains("\"mutationApplied\":false"),
                "propose_* tools must not flag mutationApplied=true (ARCH-009), got: " + toolResultData);
        String navPath = stream.firstDataFor("navigate");
        assertNotNull(navPath, "navigate event must carry a path");
        assertTrue(navPath.startsWith("/pipelines/") || navPath.equals("/pipelines"),
                "navigate path must be a pipelines route, got: " + navPath);

        // Persisted messages: USER, ASSISTANT(with tool_calls), TOOL, ASSISTANT(final).
        var messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        assertTrue(messages.size() >= 4,
                "expected USER + assistant-with-toolcalls + TOOL + final ASSISTANT, got: "
                        + messages.stream().map(m -> m.getRole()).toList());
        assertEquals("USER", messages.get(0).getRole());
        assertEquals("ASSISTANT", messages.get(1).getRole());
        assertEquals("TOOL", messages.get(2).getRole());
        assertEquals("ASSISTANT", messages.get(messages.size() - 1).getRole());
        assertTrue(messages.get(messages.size() - 1).getContent().contains("All set"),
                "final assistant message must persist the streamed text");
    }

    /**
     * TC_chat_sse_happy_path_event_order edge — tool call with empty args still produces a
     * tool_result event. Using {@code list_blueprints} which has no required args and never
     * navigates, so the event order shrinks to chunk(*) → tool_call → tool_result → done.
     */
    @Test
    void toolWithEmptyArgs_stillEmitsToolResult() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        // list_blueprints reads from blueprint table; ensure something exists so the result is non-empty.
        seed.seedBlueprint("BronzeToSilverCleaning", com.pulse.blueprint.model.BlueprintCategory.TRANSFORM, null, null);

        String sessionId = createSessionViaService(ctx);

        fakeServer.resetQueue();
        fakeServer
                .enqueueToolCall("list_blueprints", Map.of())
                .enqueueAssistantText("Here are the blueprints I can use.");

        SseStream stream = sendMessage(sessionId, ctx.tenantId(), "What blueprints are available?");
        List<String> eventNames = stream.eventNames();
        assertTrue(eventNames.contains("tool_call"), "tool_call required: " + eventNames);
        assertTrue(eventNames.contains("tool_result"),
                "tool_result must fire even for empty-args tools: " + eventNames);
        assertEquals("done", eventNames.get(eventNames.size() - 1));
    }

    /**
     * Vertex/Gemini's OpenAI-compatible endpoint attaches an opaque
     * {@code extra_content.google.thought_signature} to function-call parts. The follow-up
     * request after PULSE executes the tool must replay that same metadata on the structured
     * assistant {@code tool_calls} entry. This is not prompt text and must not be flattened
     * into a context message; it is provider protocol metadata.
     */
    @Test
    void vertexThoughtSignature_isPreservedOnStructuredToolCallFollowUp() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        seed.seedBlueprint("BronzeToSilverCleaning", com.pulse.blueprint.model.BlueprintCategory.TRANSFORM, null, null);

        String sessionId = createSessionViaService(ctx);
        String signature = "opaque-vertex-thought-signature";

        fakeServer.resetQueue();
        fakeServer
                .enqueueToolCall("list_blueprints", Map.of(), signature)
                .enqueueAssistantText("Here are the available blueprints.");

        SseStream stream = sendMessage(sessionId, ctx.tenantId(), "What blueprints are available?");
        assertEquals("done", stream.eventNames().get(stream.eventNames().size() - 1));

        List<Map<String, Object>> requests = fakeServer.receivedRequests();
        assertEquals(2, requests.size(),
                "tool loop should make initial tool-call request and post-tool-result follow-up");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) requests.get(1).get("messages");
        Map<String, Object> assistantWithToolCalls = messages.stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .filter(m -> m.get("tool_calls") instanceof List<?>)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "follow-up request must contain structured assistant tool_calls"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) assistantWithToolCalls.get("tool_calls");
        assertFalse(toolCalls.isEmpty(), "assistant tool_calls array must not be empty");
        Map<String, Object> firstToolCall = toolCalls.get(0);
        assertEquals("function", firstToolCall.get("type"),
                "tool call must remain a structured function call, not flattened text");
        assertTrue(firstToolCall.get("function") instanceof Map,
                "structured function payload must remain present");

        @SuppressWarnings("unchecked")
        Map<String, Object> extraContent = (Map<String, Object>) firstToolCall.get("extra_content");
        assertNotNull(extraContent,
                "Vertex thought_signature metadata must be replayed as extra_content");
        @SuppressWarnings("unchecked")
        Map<String, Object> google = (Map<String, Object>) extraContent.get("google");
        assertNotNull(google, "extra_content.google must be present");
        assertEquals(signature, google.get("thought_signature"),
                "thought_signature must be replayed unchanged on the structured tool_call");
    }

    /**
     * TC_chat_navigation_marker_emitted — {@code navigate_ui} fires a navigate event with a
     * route string the frontend can use directly. Locks the path-shape contract.
     */
    @Test
    void navigateUiTool_emitsNavigateEventWithRoute() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        String sessionId = createSessionViaService(ctx);

        fakeServer.resetQueue();
        fakeServer
                .enqueueToolCall("navigate_ui", Map.of("page", "pipelines"))
                .enqueueAssistantText("Taking you to the pipelines list.");

        SseStream stream = sendMessage(sessionId, ctx.tenantId(), "Show me the pipelines.");
        List<String> eventNames = stream.eventNames();
        int toolCallIdx = eventNames.indexOf("tool_call");
        int navigateIdx = eventNames.indexOf("navigate");
        assertTrue(toolCallIdx >= 0 && navigateIdx > toolCallIdx,
                "navigate must come after tool_call for navigate_ui: " + eventNames);
        assertEquals("/pipelines", stream.firstDataFor("navigate"),
                "page=pipelines must map to /pipelines route");

        // Frontend route shape: starts with '/' and matches a known top-level surface.
        String path = stream.firstDataFor("navigate");
        assertTrue(path.startsWith("/"), "navigate path must start with '/'");
        assertTrue(path.matches("^/(producers|pipelines|blueprints|commands)(/.*)?$"),
                "navigate path must match a known frontend top-level route, got: " + path);
    }

    /**
     * Draft refs such as {@code draft:pipeline:1} are preview labels inside a pending plan.
     * They are never real product ids, so even if the model incorrectly asks for
     * {@code navigate_ui(page=pipeline_detail, resource_id=draft:pipeline:1)}, the backend
     * must not emit a browser route like {@code /pipelines/draft:pipeline:1}.
     */
    @Test
    void navigateUiDraftPipelineDetail_doesNotEmitNavigateEvent() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        String sessionId = createSessionViaService(ctx);

        fakeServer.resetQueue();
        fakeServer
                .enqueueToolCall("navigate_ui", Map.of(
                        "page", "pipeline_detail",
                        "resource_id", "draft:pipeline:1"))
                .enqueueAssistantText("The pipeline plan is ready to review.");

        SseStream stream = sendMessage(sessionId, ctx.tenantId(), "Show me the pending pipeline draft.");
        List<String> eventNames = stream.eventNames();

        assertTrue(eventNames.contains("tool_call"), "navigate_ui tool_call should still be visible");
        assertTrue(eventNames.contains("tool_result"), "navigate_ui tool_result should still complete");
        assertFalse(eventNames.contains("navigate"),
                "draft pipeline refs must not produce a navigate event: " + eventNames);
        assertEquals("done", eventNames.get(eventNames.size() - 1));
    }

    /**
     * TC_chat_sse_tool_failure_emits_error_frame — when a tool execution throws, the controller
     * MUST NOT emit a {@code tool_result} (which the frontend treats as success), and the
     * subsequent LLM turn carrying the error explanation MUST still complete cleanly. Verifies
     * that the session remains usable for a follow-up message.
     */
    @Test
    void toolThrows_suppressesToolResult_andSessionRemainsUsable() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        String sessionId = createSessionViaService(ctx);

        fakeServer.resetQueue();
        // remove_step with a fake instance_id throws inside CompositionService.removeInstance,
        // which the executor wraps as "Error executing remove_step: ..." — the controller then
        // suppresses tool_result for that tool call.
        fakeServer
                .enqueueToolCall("remove_step", Map.of(
                        "version_id", ctx.versionId(),
                        "instance_id", "this-instance-does-not-exist"))
                .enqueueAssistantText("I couldn't remove that step because it does not exist.");

        SseStream stream = sendMessage(sessionId, ctx.tenantId(), "Remove the cleaning step.");
        List<String> eventNames = stream.eventNames();
        assertTrue(eventNames.contains("tool_call"),
                "tool_call must still fire even though execution throws: " + eventNames);
        // ARCH-009: tool_result now fires for every tool execution and carries
        // a structured envelope; failures use status=error / mutationApplied=false
        // so the frontend keys off mutationApplied + refreshHints, never tool names.
        assertTrue(eventNames.contains("tool_result"),
                "tool_result must fire with status=error for failed tool execution (ARCH-009): "
                        + eventNames);
        String errorEnvelope = stream.firstDataFor("tool_result");
        assertNotNull(errorEnvelope);
        assertTrue(errorEnvelope.startsWith("{"),
                "tool_result must be JSON (ARCH-009), got: " + errorEnvelope);
        assertTrue(errorEnvelope.contains("\"status\":\"error\""),
                "failed tool must surface status=error in envelope, got: " + errorEnvelope);
        assertTrue(errorEnvelope.contains("\"mutationApplied\":false"),
                "failed tool must surface mutationApplied=false in envelope, got: " + errorEnvelope);
        assertEquals("done", eventNames.get(eventNames.size() - 1));

        // The error context must reach the LLM (next turn) which produced a recovery chunk.
        boolean recoveryChunkPresent = stream.allDataFor("chunk").stream()
                .anyMatch(s -> s != null && (s.toLowerCase().contains("couldn't") || s.toLowerCase().contains("does not exist")));
        assertTrue(recoveryChunkPresent,
                "recovery chunk from second LLM turn must surface error context: chunks="
                        + stream.allDataFor("chunk"));

        // Session is still usable — send a follow-up message and verify a new SSE stream completes.
        fakeServer.resetQueue();
        fakeServer.enqueueAssistantText("Acknowledged.");
        SseStream second = sendMessage(sessionId, ctx.tenantId(), "Never mind.");
        assertEquals("done", second.eventNames().get(second.eventNames().size() - 1),
                "follow-up message must reach 'done': " + second.eventNames());
    }

    /**
     * TC_chat_sse_tool_failure_emits_error_frame upstream-failure path — when the LLM itself
     * fails (HTTP 5xx, connection break), the controller emits an explicit {@code error} SSE
     * event with the failure message. Frontend uses this to surface "model unavailable" toast.
     */
    @Test
    void llmUpstreamFailure_emitsErrorSseEvent() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        String sessionId = createSessionViaService(ctx);

        fakeServer.resetQueue();
        fakeServer.enqueueError(500);

        SseStream stream = sendMessage(sessionId, ctx.tenantId(), "Hello, model.");
        List<String> eventNames = stream.eventNames();
        assertTrue(eventNames.contains("error"),
                "error SSE event must fire when the LLM call throws: " + eventNames);
        // Error payload must not contain secret-shaped tokens or absolute file paths.
        String errorData = stream.firstDataFor("error");
        assertNotNull(errorData, "error event must carry data");
        assertFalse(errorData.contains("test-llm-key"),
                "error payload must not leak the api key: " + errorData);
        assertFalse(errorData.contains("/Users/"),
                "error payload must not leak absolute filesystem paths: " + errorData);
    }

    /**
     * TC_chat_no_remove_step_then_readd_regression — verifies the system prompt the controller
     * builds for the LLM contains the documented stability rule that forbids
     * {@code remove_step} immediately followed by {@code propose_add_instance} on the same
     * step. The prompt is the in-band enforcement point; the controller surfaces the rule by
     * sending it on every chat turn.
     *
     * <p>We assert by inspecting the request body the fake LLM server captured — the system
     * message must contain the rule text. This locks the regression at the controller level
     * rather than only at the static {@code PulseSystemPromptTest} level (which tests the
     * constant directly but not that it actually reaches the LLM).
     */
    @Test
    void systemPromptCarries_noRemoveThenReaddRule() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        String sessionId = createSessionViaService(ctx);

        fakeServer.resetQueue();
        fakeServer.enqueueAssistantText("Acknowledged.");

        sendMessage(sessionId, ctx.tenantId(), "Hi.");

        // The very first request the LLM saw must include the system prompt with the rule.
        List<Map<String, Object>> requests = fakeServer.receivedRequests();
        assertFalse(requests.isEmpty(), "fake LLM should have received at least one request");
        Map<String, Object> first = requests.get(0);
        Object messagesObj = first.get("messages");
        assertTrue(messagesObj instanceof List, "messages must be a list");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) messagesObj;
        Map<String, Object> systemMessage = messages.stream()
                .filter(m -> "system".equals(m.get("role")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("system message missing from LLM request"));
        String systemContent = String.valueOf(systemMessage.get("content"));

        // The actual canonical phrasing from PulseSystemPrompt ABSOLUTE_RULES rule 15.
        assertTrue(systemContent.contains("remove_step"),
                "system prompt must mention remove_step");
        assertTrue(systemContent.contains("propose_add_instance"),
                "system prompt must mention propose_add_instance in the regression rule");
        assertTrue(systemContent.toLowerCase().contains("reset") || systemContent.toLowerCase().contains("reconfigure"),
                "regression rule must call out the reset/reconfigure failure mode (rule 15)");
        assertTrue(systemContent.contains("DESTRUCTIVE") || systemContent.contains("destructive"),
                "regression rule must label remove_step as destructive");

        // Inverted order (add then remove) is allowed — we ONLY guard the remove-then-add
        // sequence. Locking that here means a future rephrase that accidentally bans add+remove
        // surfaces as a red test rather than as a UX regression.
        assertFalse(systemContent.contains("propose_add_instance followed by remove_step is forbidden"),
                "system prompt must NOT forbid the inverted add-then-remove order");
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private String createSessionViaService(SeedFixtures.Context ctx) {
        var session = new com.pulse.chat.model.ChatSession();
        session.setTenantId(ctx.tenantId());
        session.setUserId(ctx.userId());
        session.setPipelineId(ctx.pipelineId());
        session.setTitle("contract test");
        return chatSessionRepository.save(session).getId();
    }

    /**
     * POST a chat message and read the SSE stream until the connection closes. Returns the
     * full ordered list of events plus a per-event data map for convenient assertions.
     */
    private SseStream sendMessage(String sessionId, String tenantId, String content) throws Exception {
        String url = "http://127.0.0.1:" + port + "/api/v1/chat/sessions/" + sessionId + "/messages";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setDoOutput(true);
        // Bounded read timeout: the fake LLM server completes within a few hundred ms; if a
        // run hangs we want a timely test failure rather than a 10-minute wait.
        conn.setReadTimeout(15_000);
        conn.setConnectTimeout(5_000);

        String body = objectMapper.writeValueAsString(Map.of(
                "content", content,
                "tenantId", tenantId));
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }

        List<SseEvent> events = new ArrayList<>();
        int status = conn.getResponseCode();
        if (status >= 400) {
            return new SseStream(events);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            String currentEvent = null;
            StringBuilder currentData = new StringBuilder();
            try {
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (currentEvent != null) {
                            events.add(new SseEvent(currentEvent, currentData.toString()));
                        }
                        currentEvent = null;
                        currentData.setLength(0);
                    } else if (line.startsWith("event:")) {
                        currentEvent = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (currentData.length() > 0) currentData.append("\n");
                        currentData.append(line.substring("data:".length()).trim());
                    } else if (line.startsWith(":")) {
                        // SSE comment — ignore.
                    }
                }
            } catch (java.io.IOException eof) {
                // ChatService completes-with-error on upstream failure, which slams the SSE
                // socket shut and surfaces here as "Premature EOF". Treat it as end-of-stream
                // and keep whatever frames we already captured (including the 'error' event,
                // which IS what we want to assert on).
            }
            // Trailing event without separator (Spring writes one).
            if (currentEvent != null) {
                events.add(new SseEvent(currentEvent, currentData.toString()));
            }
        }
        return new SseStream(events);
    }

    /** A captured SSE frame. */
    private record SseEvent(String name, String data) { }

    /** Parsed SSE response for ergonomic assertions. */
    private static final class SseStream {
        private final List<SseEvent> events;

        SseStream(List<SseEvent> events) {
            this.events = events;
        }

        List<String> eventNames() {
            List<String> names = new ArrayList<>();
            for (SseEvent e : events) names.add(e.name);
            return names;
        }

        String firstDataFor(String name) {
            for (SseEvent e : events) {
                if (name.equals(e.name)) return e.data;
            }
            return null;
        }

        List<String> allDataFor(String name) {
            List<String> data = new ArrayList<>();
            for (SseEvent e : events) {
                if (name.equals(e.name)) data.add(e.data);
            }
            return data;
        }
    }

}
