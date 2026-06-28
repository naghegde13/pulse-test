package com.pulse.e2e.validation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.e2e.LoanMasterFixture;
import com.pulse.e2e.api.ApiScenarioClient;
import com.pulse.e2e.builder.ApiScenarioBuilder;
import com.pulse.e2e.contract.ScenarioDsl;
import com.pulse.e2e.coverage.LoanMasterScenarioCoveragePlanWriter;
import com.pulse.e2e.scenarios.LoanMasterScenarioFamilyCatalog;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "pulse.git.local-repo-base=${java.io.tmpdir}/pulse-e2e-database-writer-repos")
@Transactional
class DatabaseWriterLiveRuntimeEvidenceIT {

    private static final String SCENARIO_ID = "destination-database-delinquent-static-deployability";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;

    @TempDir Path tempDir;

    @Test
    void promotionCatalogsDatabaseWriterAsLiveRuntimeWithCanonicalEvidenceContract() throws Exception {
        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        ScenarioDsl.ScenarioDefinition scenario = loadScenarioDefinition();

        assertEquals(ScenarioDsl.ProofMode.LIVE_RUNTIME, scenario.proofMode());
        assertEquals(ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE, scenario.runtimeAdapter());
        assertTrue(scenario.featureTags().containsAll(List.of("live_runtime", "postgres", "docker_compose_local")));
        assertFalse(scenario.featureTags().contains("static_deployability"));
        assertEquals(List.of(
                        "dag-state.json",
                        "task-state.json",
                        "minio-output-probe.json",
                        "data-oracle-comparison.json",
                        "scenario-catalog.json",
                        "scenario-coverage-plan.json",
                        "coverage.json",
                        "verdict.json",
                        "evidence-index.json"
                ),
                scenario.evidenceExpectation().requiredArtifacts());
        assertTrue(scenario.evidenceExpectation().requiredEvidenceTypes().contains("MINIO_OUTPUT_PROBE"));
        assertTrue(scenario.evidenceExpectation().requiredEvidenceTypes().contains("DATA_ORACLE_COMPARISON"));

        assertEquals("POSTGRES", scenario.fixtureRefs().get("storage_backend"));
        assertEquals("hard_proven_live_runtime", scenario.fixtureRefs().get("runtime_promotion_status"));
        assertEquals("STATIC_DEPLOYABILITY", scenario.fixtureRefs().get("runtime_promotion_source_proof_mode"));
        assertEquals("PASS", scenario.fixtureRefs().get("hard_proof_status"));
        assertFalse(scenario.fixtureRefs().containsKey("runtime_promotion_blocker"));

        ApiScenarioBuilder builder = new ApiScenarioBuilder(
                new ApiScenarioClient(mockMvc, objectMapper),
                blueprintRepository,
                connectorDefinitionRepository
        );
        ApiScenarioBuilder.ScenarioExecution execution = builder.execute(withTenant(scenario, "tenant-db-writer-artifact"), fixture);

        assertEquals("COMPLETED", execution.generationRun().getStatus());
        assertEquals(List.of("FileIngestion", "GenericFilter", "DatabaseWriter"),
                execution.instances().stream().map(instance -> instance.getBlueprintKey()).toList());

        var scenarioCoveragePlan = new LoanMasterScenarioCoveragePlanWriter(objectMapper)
                .writeCanonicalPlan(tempDir.resolve("scenario-coverage-plan.json"));
        assertTrue(scenarioCoveragePlan.liveRuntimeScenarioIds().contains(SCENARIO_ID));
        assertFalse(scenarioCoveragePlan.staticDeployabilityScenarioIds().contains(SCENARIO_ID));
        assertTrue(scenarioCoveragePlan.runtimePromotionCandidates().stream()
                .noneMatch(candidate -> SCENARIO_ID.equals(candidate.scenarioId())));
    }

    private ScenarioDsl.ScenarioDefinition loadScenarioDefinition() throws Exception {
        var catalog = new LoanMasterScenarioFamilyCatalog().build(loadActiveBlueprintCatalog(), loadFixtureDerivativeIds());
        return catalog.scenarios().stream()
                .map(LoanMasterScenarioFamilyCatalog.ScenarioFamilySpec::scenario)
                .filter(scenario -> SCENARIO_ID.equals(scenario.scenarioId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing scenario " + SCENARIO_ID));
    }

    private ScenarioDsl.ScenarioDefinition withTenant(ScenarioDsl.ScenarioDefinition scenario, String tenantId) {
        return new ScenarioDsl.ScenarioDefinition(
                scenario.scenarioId(),
                scenario.displayName(),
                scenario.proofMode(),
                scenario.runtimeAdapter(),
                scenario.featureTags(),
                new ScenarioDsl.BuilderPlan(
                        tenantId,
                        scenario.builderPlan().domainSlug(),
                        scenario.builderPlan().sourceDataset(),
                        scenario.builderPlan().blueprintKeys(),
                        scenario.builderPlan().fixtureId()
                ),
                scenario.evidenceExpectation(),
                scenario.fixtureRefs()
        );
    }

    private List<LoanMasterScenarioFamilyCatalog.ActiveBlueprintCatalogRecord> loadActiveBlueprintCatalog() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("e2e/coverage/active-blueprint-catalog.json")) {
            assertNotNull(in, "Missing active blueprint catalog resource");
            var root = objectMapper.readTree(in);
            return objectMapper.convertValue(root.path("blueprints"), new TypeReference<>() {});
        }
    }

    private Set<String> loadFixtureDerivativeIds() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("e2e/fixtures/loan_master/fixture-manifest.json")) {
            assertNotNull(in, "Missing fixture manifest resource");
            Map<String, Object> payload = objectMapper.readValue(in, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> derivatives = (List<Map<String, Object>>) payload.get("derivatives");
            assertNotNull(derivatives, "fixture-manifest.json missing derivatives array");
            return derivatives.stream()
                    .map(entry -> String.valueOf(entry.get("derivative_id")))
                    .collect(Collectors.toSet());
        }
    }
}
