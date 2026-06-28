package com.pulse.chat.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
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
import com.pulse.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK_P0_chat_controller_sse_contract — TC_chat_session_create_with_pipeline and
 * TC_chat_invalid_session_returns_404.
 *
 * <p>These cases do not need the LLM tool loop, so they live in a fast-lane test class.
 * They lock the contract for {@code POST /api/v1/tenants/{tenantId}/chat/sessions} and the
 * 404 behavior of the message endpoint when the session does not exist.
 *
 * <p>The contract surface under test is the {@link ChatController} REST surface — the
 * test never mocks {@code ChatService} (that would drop the controller wiring we want to
 * lock). The LLM API key is overridden to blank so any chance path that touches the LLM
 * falls through to local-mode and never reaches the network.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "pulse.llm.api-key=",
        "pulse.cors.allowed-origins=http://localhost:3000"
})
class ChatControllerSessionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ChatSessionRepository chatSessionRepository;

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
    }

    /** TC_chat_session_create_with_pipeline — happy path: session bound to pipelineId. */
    @Test
    void createSession_persistsBindings_andLatestReturnsIt() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();

        String body = objectMapper.writeValueAsString(Map.of(
                "userId", ctx.userId(),
                "pipelineId", ctx.pipelineId(),
                "title", "Build the curated mart"));

        MvcResult result = mockMvc.perform(
                        post("/api/v1/tenants/{tenantId}/chat/sessions", ctx.tenantId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode session = objectMapper.readTree(result.getResponse().getContentAsString());
        String sessionId = session.path("id").asText();
        assertNotNull(sessionId, "session id must be returned");
        assertEquals(ctx.tenantId(), session.path("tenantId").asText(), "tenantId binding");
        assertEquals(ctx.userId(), session.path("userId").asText(), "userId binding");
        assertEquals(ctx.pipelineId(), session.path("pipelineId").asText(), "pipelineId binding");
        assertEquals("Build the curated mart", session.path("title").asText(), "title");

        // Row actually persisted with those bindings.
        var persisted = chatSessionRepository.findById(sessionId).orElseThrow();
        assertEquals(ctx.tenantId(), persisted.getTenantId());
        assertEquals(ctx.userId(), persisted.getUserId());
        assertEquals(ctx.pipelineId(), persisted.getPipelineId());

        // GET /tenants/{tenantId}/chat/sessions/latest?userId=... returns the same row.
        MvcResult latest = mockMvc.perform(
                        get("/api/v1/tenants/{tenantId}/chat/sessions/latest", ctx.tenantId())
                                .param("userId", ctx.userId()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode latestJson = objectMapper.readTree(latest.getResponse().getContentAsString());
        assertEquals(sessionId, latestJson.path("id").asText(),
                "GET /sessions/latest must return the just-created session for this user");
    }

    /**
     * TC_chat_session_create_with_pipeline edge case — two sessions for the same userId/pipelineId
     * are allowed and {@code /sessions/latest} returns the most recent.
     */
    @Test
    void twoSessionsSameUserAndPipeline_latestReturnsMostRecent() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();

        String firstId = createSession(ctx.tenantId(), ctx.userId(), ctx.pipelineId(), "first");
        // Force a measurable delay so updated_at differs.
        Thread.sleep(20);
        String secondId = createSession(ctx.tenantId(), ctx.userId(), ctx.pipelineId(), "second");

        MvcResult latest = mockMvc.perform(
                        get("/api/v1/tenants/{tenantId}/chat/sessions/latest", ctx.tenantId())
                                .param("userId", ctx.userId()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode latestJson = objectMapper.readTree(latest.getResponse().getContentAsString());
        String latestId = latestJson.path("id").asText();
        assertEquals(secondId, latestId,
                "latest of two same-user/pipeline sessions must be the second one created");
        assertTrue(!firstId.equals(secondId), "session ids must differ");
    }

    /**
     * TC_chat_session_create_with_pipeline negative — calling create without a pipelineId is
     * <b>currently accepted</b> by the controller because {@code pipelineId} is optional on the
     * domain model (a citizen can start chatting before linking a pipeline; the first
     * pipeline-creating tool call back-links the session). Documenting that contract here so a
     * regression that suddenly required pipelineId would surface as a red test.
     *
     * <p>If the product decision changes to "pipelineId is required at session create", flip
     * this test to assert {@code status().isBadRequest()} and add controller-level validation.
     */
    @Test
    void createSession_withoutPipelineId_persistsWithNullPipelineId() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();

        String body = objectMapper.writeValueAsString(Map.of("userId", ctx.userId(), "title", "no pipeline yet"));
        MvcResult result = mockMvc.perform(
                        post("/api/v1/tenants/{tenantId}/chat/sessions", ctx.tenantId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode session = objectMapper.readTree(result.getResponse().getContentAsString());
        String sessionId = session.path("id").asText();
        // pipelineId may be null or absent from JSON.
        assertTrue(session.path("pipelineId").isNull() || session.path("pipelineId").isMissingNode()
                || session.path("pipelineId").asText().isEmpty(),
                "pipelineId must be null/absent when not supplied at create");
        var persisted = chatSessionRepository.findById(sessionId).orElseThrow();
        assertTrue(persisted.getPipelineId() == null || persisted.getPipelineId().isBlank(),
                "persisted pipelineId must be null/blank");
    }

    /** TC_chat_invalid_session_returns_404 — POST message to unknown session returns 404, not 500. */
    @Test
    void postMessageToUnknownSession_returns404() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "content", "hello",
                "tenantId", "tenant-home-lending"));

        MvcResult result = mockMvc.perform(
                        post("/api/v1/chat/sessions/{sessionId}/messages", "does-not-exist-01")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                                .content(body))
                .andExpect(status().isNotFound())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        // GlobalExceptionHandler emits a ProblemDetail with a clear message.
        assertTrue(responseBody.contains("ChatSession") && responseBody.contains("does-not-exist-01"),
                "404 body should name the resource type and id, got: " + responseBody);
    }

    /**
     * Edge case from the packet — GET on an unknown session id returns 404 too (otherwise the
     * chat-context bootstrap path silently surfaces a 500).
     */
    @Test
    void getUnknownSession_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/chat/sessions/{sessionId}", "missing-session-xyz"))
                .andExpect(status().isNotFound());
    }

    /** Helper — POST /sessions and return the created session id. */
    private String createSession(String tenantId, String userId, String pipelineId, String title) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "userId", userId,
                "pipelineId", pipelineId,
                "title", title));
        MvcResult res = mockMvc.perform(
                        post("/api/v1/tenants/{tenantId}/chat/sessions", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).path("id").asText();
    }
}
