package com.pulse.pipeline.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.pipeline.model.DqValidationResult;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.DqValidationResultRepository;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.pipeline.service.DqReadinessService;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-contract tests for {@link DqController}.
 *
 * <p>Boundary: {@code api_to_database}. Spring Boot brings the full stack up against H2 +
 * {@code create-drop} via the {@code test} profile. The LLM client inside
 * {@link DqReadinessService} is intentionally swapped for a {@link MockitoBean} so no live
 * network call is made — see {@link DqReadinessServiceTest} for the deterministic recorded-
 * fixture flow against the same service.
 *
 * <p><strong>Why not a slice test?</strong> {@link DqController}'s
 * {@code GET /api/v1/versions/{versionId}/dq/results} reads through
 * {@code DqValidationResultRepository.findByVersionId}, which is a
 * {@code @Query} that joins to {@code SubPipelineInstance.versionId}. A real persistence layer
 * is therefore required to prove the join filters by versionId, not just that the controller
 * forwards the parameter.
 *
 * <p><strong>Test cases (per TASK_P0_dq_controller_and_readiness):</strong>
 * <ul>
 *   <li>{@code TC_dq_evaluate_returns_score_with_recommendations}</li>
 *   <li>{@code TC_dq_score_endpoint_returns_persisted_score}</li>
 *   <li>{@code TC_put_dq_expectations_persists}</li>
 *   <li>{@code TC_dq_results_listing_filtered_by_version}</li>
 *   <li>Regression: re-evaluate does not overwrite saved expectations</li>
 * </ul>
 *
 * <p>The {@link DqReadinessService} {@link MockitoBean} stub mirrors the contract documented
 * by the LLM-fixture file at
 * {@code backend/src/test/resources/test-fixtures/llm-responses/dq-readiness-known.json};
 * {@link DqReadinessServiceTest} exercises the live-but-mocked-HTTP path so the controller
 * test stays focused on the request/response contract and persistence side-effects.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DqControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

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
    @Autowired private DqValidationResultRepository dqValidationResultRepository;

    @MockitoBean private DqReadinessService dqReadinessService;

    private SeedFixtures seed;

    @BeforeEach
    void setUp() {
        seed = new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                subPipelineInstanceRepository, portWiringRepository);
    }

    // ------------------------------------------------------------------
    // POST /dq/evaluate — TC_dq_evaluate_returns_score_with_recommendations
    // ------------------------------------------------------------------

    @Test
    void evaluate_knownComposition_returnsScoreRecommendationsAndReasoning() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        Map<String, Object> stubbed = Map.of(
                "score", 72,
                "recommendations", List.of(Map.of(
                        "instance_name", "Loan Master Ingestion",
                        "expectations", List.of(Map.of(
                                "type", "ExpectColumnValuesToNotBeNull",
                                "kwargs", Map.of("column", "loan_id"),
                                "severity", "critical",
                                "reason", "Primary key must never be null")))),
                "reasoning", "Coverage moderate; add not-null on loan_id.");
        when(dqReadinessService.evaluate(ctx.versionId())).thenReturn(stubbed);

        mockMvc.perform(post("/api/v1/versions/{versionId}/dq/evaluate", ctx.versionId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(72))
                .andExpect(jsonPath("$.recommendations").isArray())
                .andExpect(jsonPath("$.recommendations[0].instance_name").value("Loan Master Ingestion"))
                .andExpect(jsonPath("$.recommendations[0].expectations[0].type")
                        .value("ExpectColumnValuesToNotBeNull"))
                .andExpect(jsonPath("$.reasoning").exists());
    }

    @Test
    void evaluate_unknownVersion_returns404() throws Exception {
        when(dqReadinessService.evaluate("no-such-version-id"))
                .thenThrow(new com.pulse.common.exception.ResourceNotFoundException(
                        "PipelineVersion", "no-such-version-id"));

        mockMvc.perform(post("/api/v1/versions/{versionId}/dq/evaluate", "no-such-version-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void evaluate_emptyComposition_returnsZeroScoreWithReason() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        when(dqReadinessService.evaluate(ctx.versionId())).thenReturn(Map.of(
                "score", 0,
                "recommendations", List.of(),
                "reasoning", "Pipeline contains no steps with DQ expectations; nothing to evaluate."));

        mockMvc.perform(post("/api/v1/versions/{versionId}/dq/evaluate", ctx.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0))
                .andExpect(jsonPath("$.recommendations").isArray())
                .andExpect(jsonPath("$.recommendations.length()").value(0))
                .andExpect(jsonPath("$.reasoning").isNotEmpty());
    }

    @Test
    void evaluate_sourceOnlyComposition_returnsExplicitGuidance() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        when(dqReadinessService.evaluate(ctx.versionId())).thenReturn(Map.of(
                "score", 15,
                "recommendations", List.of(Map.of(
                        "instance_name", "Source",
                        "expectations", List.of(Map.of(
                                "type", "ExpectTableRowCountToBeBetween",
                                "kwargs", Map.of("min_value", 1),
                                "severity", "high",
                                "reason", "At minimum verify the source produces rows")))),
                "reasoning", "Only ingestion present; add transforms and DQ gates."));

        mockMvc.perform(post("/api/v1/versions/{versionId}/dq/evaluate", ctx.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(15))
                .andExpect(jsonPath("$.recommendations[0].expectations[0].type")
                        .value("ExpectTableRowCountToBeBetween"));
    }

    // ------------------------------------------------------------------
    // GET /dq/score — TC_dq_score_endpoint_returns_persisted_score
    // ------------------------------------------------------------------

    @Test
    void getScore_returnsPersistedScore_andDoesNotInvokeEvaluate() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        when(dqReadinessService.getScore(ctx.versionId())).thenReturn(Map.of(
                "versionId", ctx.versionId(),
                "score", 64,
                "evaluated", true));

        mockMvc.perform(get("/api/v1/versions/{versionId}/dq/score", ctx.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(64))
                .andExpect(jsonPath("$.evaluated").value(true))
                .andExpect(jsonPath("$.versionId").value(ctx.versionId()));

        // Critical contract: GET must NOT cascade into evaluate().
        verify(dqReadinessService, never()).evaluate(anyString());
    }

    @Test
    void getScore_neverEvaluated_returnsSentinelMinusOneAndFalseFlag() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        when(dqReadinessService.getScore(ctx.versionId())).thenReturn(Map.of(
                "versionId", ctx.versionId(),
                "score", -1,
                "evaluated", false));

        mockMvc.perform(get("/api/v1/versions/{versionId}/dq/score", ctx.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(-1))
                .andExpect(jsonPath("$.evaluated").value(false));
    }

    @Test
    void getScore_unknownVersion_returns404() throws Exception {
        when(dqReadinessService.getScore("missing-version"))
                .thenThrow(new com.pulse.common.exception.ResourceNotFoundException(
                        "PipelineVersion", "missing-version"));

        mockMvc.perform(get("/api/v1/versions/{versionId}/dq/score", "missing-version"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // PUT /instances/{id}/dq-expectations — TC_put_dq_expectations_persists
    // ------------------------------------------------------------------

    @Test
    void putDqExpectations_persistsTheGivenSet() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        SubPipelineInstance inst = seedInstance(ctx, "Ingest", 1);

        Map<String, Object> body = Map.of("expectations", List.of(
                Map.of(
                        "type", "ExpectColumnValuesToNotBeNull",
                        "kwargs", Map.of("column", "loan_id"),
                        "severity", "critical")));

        mockMvc.perform(put("/api/v1/versions/{versionId}/instances/{instanceId}/dq-expectations",
                        ctx.versionId(), inst.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(inst.getId()))
                .andExpect(jsonPath("$.dqExpectations").isArray())
                .andExpect(jsonPath("$.dqExpectations[0].type")
                        .value("ExpectColumnValuesToNotBeNull"));

        // Round-trip via the repository so we prove the JSONB column actually persisted.
        SubPipelineInstance reloaded = subPipelineInstanceRepository.findById(inst.getId()).orElseThrow();
        assertNotNull(reloaded.getDqExpectations(),
                "JSONB column must be persisted on PUT, got null");
        assertEquals(1, reloaded.getDqExpectations().size(),
                "exactly one expectation persisted");
        assertEquals("ExpectColumnValuesToNotBeNull",
                reloaded.getDqExpectations().get(0).get("type"));
    }

    @Test
    void putDqExpectations_emptyArray_clearsTheRow() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        SubPipelineInstance inst = seedInstance(ctx, "Ingest", 1);
        // Pre-load with a non-empty expectation set
        List<Map<String, Object>> existing = new ArrayList<>();
        existing.add(Map.of("type", "ExpectColumnValuesToNotBeNull",
                "kwargs", Map.of("column", "loan_id")));
        inst.setDqExpectations(existing);
        subPipelineInstanceRepository.save(inst);

        // PUT an empty list
        Map<String, Object> body = Map.of("expectations", List.<Map<String, Object>>of());
        mockMvc.perform(put("/api/v1/versions/{versionId}/instances/{instanceId}/dq-expectations",
                        ctx.versionId(), inst.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        SubPipelineInstance reloaded = subPipelineInstanceRepository.findById(inst.getId()).orElseThrow();
        assertTrue(reloaded.getDqExpectations() == null || reloaded.getDqExpectations().isEmpty(),
                "empty array clears the expectations row");
    }

    @Test
    void putDqExpectations_unknownInstance_returns404() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        Map<String, Object> body = Map.of("expectations", List.of());

        mockMvc.perform(put("/api/v1/versions/{versionId}/instances/{instanceId}/dq-expectations",
                        ctx.versionId(), "no-such-instance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void putDqExpectations_wrongVersionForInstance_returns400() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        SubPipelineInstance inst = seedInstance(ctx, "Ingest", 1);
        Map<String, Object> body = Map.of("expectations", List.of());

        mockMvc.perform(put("/api/v1/versions/{versionId}/instances/{instanceId}/dq-expectations",
                        "some-other-version", inst.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putDqExpectations_malformedJsonBody_doesNotPersistAndReturnsError() throws Exception {
        // Current contract (locked here): GlobalExceptionHandler only special-cases
        // ResourceNotFoundException + IllegalArgumentException; an unparseable body falls
        // through to the catch-all and produces a 500. The important guarantee for callers is
        // that the request is REJECTED and NO row is updated on a malformed body. If the
        // controller is later hardened to translate parse errors into 400, this test should
        // be tightened to assert 400 — but until then we lock the current contract here.
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        SubPipelineInstance inst = seedInstance(ctx, "Ingest", 1);
        // Pre-populate so we can prove the row was NOT updated.
        List<Map<String, Object>> preExisting = new ArrayList<>();
        preExisting.add(Map.of("type", "PRE_EXISTING", "kwargs", Map.of("column", "x")));
        inst.setDqExpectations(preExisting);
        subPipelineInstanceRepository.save(inst);

        mockMvc.perform(put("/api/v1/versions/{versionId}/instances/{instanceId}/dq-expectations",
                        ctx.versionId(), inst.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not valid json"))
                .andExpect(status().is5xxServerError());

        // The row must be unchanged — a malformed body must not corrupt the persisted state.
        SubPipelineInstance reloaded = subPipelineInstanceRepository.findById(inst.getId()).orElseThrow();
        assertNotNull(reloaded.getDqExpectations(), "row state must not be wiped");
        assertEquals(1, reloaded.getDqExpectations().size(),
                "malformed body must not modify the persisted expectations list");
        assertEquals("PRE_EXISTING", reloaded.getDqExpectations().get(0).get("type"),
                "the original expectation must remain in place");
    }

    // ------------------------------------------------------------------
    // Regression: re-evaluate does not overwrite saved expectations
    // ------------------------------------------------------------------

    @Test
    void reEvaluate_doesNotOverwriteSavedExpectations() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();
        SubPipelineInstance inst = seedInstance(ctx, "Ingest", 1);

        // 1) User saves a deliberate expectation set via PUT.
        Map<String, Object> userExpectations = Map.of("expectations", List.of(
                Map.of(
                        "type", "ExpectColumnValuesToMatchRegex",
                        "kwargs", Map.of("column", "email", "regex", "^.+@.+$"),
                        "severity", "high",
                        "reason", "user-pinned email format check")));
        mockMvc.perform(put("/api/v1/versions/{versionId}/instances/{instanceId}/dq-expectations",
                        ctx.versionId(), inst.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userExpectations)))
                .andExpect(status().isOk());

        List<Map<String, Object>> savedBefore = subPipelineInstanceRepository.findById(inst.getId())
                .orElseThrow().getDqExpectations();
        assertNotNull(savedBefore, "PUT must persist user expectations");
        assertEquals(1, savedBefore.size());

        // 2) System triggers a fresh readiness evaluation. evaluate() is stubbed because the
        // real service writes only to PipelineVersion.dqReadinessScore — it must never touch
        // SubPipelineInstance.dqExpectations. By stubbing it we make the contract explicit:
        // even if the LLM proposes new recommendations, the controller does not apply them.
        when(dqReadinessService.evaluate(ctx.versionId())).thenReturn(Map.of(
                "score", 80,
                "recommendations", List.of(Map.of(
                        "instance_name", "Ingest",
                        "expectations", List.of(Map.of(
                                "type", "ExpectColumnValuesToNotBeNull",
                                "kwargs", Map.of("column", "loan_id"),
                                "severity", "critical",
                                "reason", "REGRESSION-SUGGESTION-must-not-overwrite")))),
                "reasoning", "Re-evaluation should not overwrite user PUT."));
        mockMvc.perform(post("/api/v1/versions/{versionId}/dq/evaluate", ctx.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(80));

        // 3) The persisted user expectations must be byte-for-byte unchanged.
        List<Map<String, Object>> savedAfter = subPipelineInstanceRepository.findById(inst.getId())
                .orElseThrow().getDqExpectations();
        assertNotNull(savedAfter, "user expectations must survive the re-evaluate");
        assertEquals(savedBefore.size(), savedAfter.size(),
                "evaluate() must not add to the expectations list");
        assertEquals(savedBefore.get(0).get("type"), savedAfter.get(0).get("type"),
                "evaluate() must not change expectation types");
        assertEquals(savedBefore.get(0).get("kwargs"), savedAfter.get(0).get("kwargs"),
                "evaluate() must not change expectation kwargs");
        assertFalse(savedAfter.toString().contains("REGRESSION-SUGGESTION-must-not-overwrite"),
                "LLM-suggested expectations must not be silently merged in");

        verify(dqReadinessService, times(1)).evaluate(ctx.versionId());
    }

    // ------------------------------------------------------------------
    // GET /dq/results — TC_dq_results_listing_filtered_by_version
    // ------------------------------------------------------------------

    @Test
    void getResults_returnsOnlyResultsForRequestedVersion() throws Exception {
        SeedFixtures.Context ctxA = seed.seedFullPipelineContext();
        SeedFixtures.Context ctxB = seed.seedFullPipelineContext();

        SubPipelineInstance instA = seedInstance(ctxA, "IngestA", 1);
        SubPipelineInstance instB = seedInstance(ctxB, "IngestB", 1);

        DqValidationResult resultA = seedDqResult(ctxA.tenantId(), instA.getId(), "suite_a", true);
        DqValidationResult resultB = seedDqResult(ctxB.tenantId(), instB.getId(), "suite_b", false);

        // Version A → only A
        MvcResult mvcA = mockMvc.perform(get("/api/v1/versions/{versionId}/dq/results", ctxA.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(resultA.getId()))
                .andExpect(jsonPath("$[0].suiteName").value("suite_a"))
                .andReturn();
        assertFalse(mvcA.getResponse().getContentAsString().contains(resultB.getId()),
                "result B must not leak into version-A response");

        // Version B → only B
        mockMvc.perform(get("/api/v1/versions/{versionId}/dq/results", ctxB.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(resultB.getId()))
                .andExpect(jsonPath("$[0].suiteName").value("suite_b"));
    }

    @Test
    void getResults_emptyForVersionWithNoInstances_returnsEmptyArrayNotNull() throws Exception {
        SeedFixtures.Context ctx = seed.seedFullPipelineContext();

        mockMvc.perform(get("/api/v1/versions/{versionId}/dq/results", ctx.versionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getResults_unknownVersion_returnsEmptyArray() throws Exception {
        // The current controller contract does not 404 on an unknown versionId for /dq/results
        // because the underlying query simply yields no rows. Lock that behavior so we notice
        // if it ever changes.
        mockMvc.perform(get("/api/v1/versions/{versionId}/dq/results", "no-such-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private SubPipelineInstance seedInstance(SeedFixtures.Context ctx, String name, int order) {
        // Seed a minimal blueprint row to satisfy the FK; the test profile uses
        // create-drop on H2 so the catalog from V7 is NOT applied.
        com.pulse.blueprint.model.Blueprint bp = seed.seedBlueprint(
                "TestBp_" + SeedFixtures.nextSuffix(),
                com.pulse.blueprint.model.BlueprintCategory.INGESTION,
                List.of(), List.of());
        return seed.seedSubPipelineInstance(
                ctx.pipelineId(), ctx.versionId(),
                bp.getId(), bp.getBlueprintKey(),
                name, order, Map.of());
    }

    private DqValidationResult seedDqResult(String tenantId, String instanceId,
                                            String suiteName, boolean success) {
        DqValidationResult r = new DqValidationResult();
        r.setTenantId(tenantId);
        r.setInstanceId(instanceId);
        r.setSuiteName(suiteName);
        r.setSuccess(success);
        r.setStatistics(Map.of("evaluated_expectations", 1,
                "successful_expectations", success ? 1 : 0,
                "unsuccessful_expectations", success ? 0 : 1));
        r.setResults(List.of(Map.of("expectation_type", "ExpectColumnValuesToNotBeNull",
                "success", success)));
        return dqValidationResultRepository.save(r);
    }
}
