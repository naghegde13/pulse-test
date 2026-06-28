package com.pulse.e2e.validation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.e2e.LoanMasterFixture;
import com.pulse.e2e.api.ApiScenarioClient;
import com.pulse.e2e.builder.ApiScenarioBuilder;
import com.pulse.e2e.contract.ScenarioDsl;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.LocalGitService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "pulse.git.local-repo-base=${java.io.tmpdir}/pulse-e2e-validation-repos")
@Transactional
class ApiScenarioValidationIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired private GitRepoRepository gitRepoRepository;
    @Autowired private LocalGitService localGitService;

    @TempDir Path tempDir;

    @Test
    void builderAndValidatorProducePassingEvidenceForCanonicalLoanMasterScenario() throws Exception {
        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        Map<String, Object> oracle = loadOracle();

        ScenarioDsl.ScenarioDefinition scenario = new ScenarioDsl.ScenarioDefinition(
                "loan-master-api-artifact",
                "Loan Master API Artifact Scenario",
                ScenarioDsl.ProofMode.ARTIFACT_ONLY,
                ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE,
                List.of("phase1", "phase2", "api-builder", "validation"),
                new ScenarioDsl.BuilderPlan(
                        "tenant-api-artifact",
                        "servicing",
                        "loan_master",
                        List.of("FileIngestion", "GenericFilter", "LakeWriter"),
                        "loan_master"
                ),
                new ScenarioDsl.EvidenceExpectation(
                        List.of("api-build-summary.json", "scenario-catalog.json", "scenario-coverage-plan.json",
                                "coverage.json", "verdict.json", "evidence-index.json"),
                        List.of("API_BUILD_SUMMARY", "SCENARIO_CATALOG", "SCENARIO_COVERAGE_PLAN",
                                "COVERAGE", "VERDICT", "EVIDENCE_INDEX"),
                        "verdict.json"
                ),
                Map.of(
                        "fixture_manifest", "e2e/fixtures/loan_master/fixture-manifest.json",
                        "active_blueprint_catalog", "e2e/coverage/active-blueprint-catalog.json",
                        "coverage_denominator", "e2e/coverage/coverage-denominator.json",
                        "blueprint_family_pruning", "e2e/coverage/blueprint-family-pruning.json"
                )
        );

        ApiScenarioBuilder builder = new ApiScenarioBuilder(
                new ApiScenarioClient(mockMvc, objectMapper),
                blueprintRepository,
                connectorDefinitionRepository,
                gitRepoRepository,
                localGitService,
                tempDir.resolve("tenant-git")
        );
        ApiScenarioBuilder.ScenarioExecution execution = builder.execute(scenario, fixture);

        assertEquals("COMPLETED", execution.generationRun().getStatus());
        assertNotNull(execution.builtPackage().getArtifactHash());
        assertEquals(Set.copyOf(scenario.builderPlan().blueprintKeys()),
                execution.instances().stream().map(inst -> inst.getBlueprintKey()).collect(Collectors.toSet()));

        ArtifactOnlyScenarioValidator validator = new ArtifactOnlyScenarioValidator(objectMapper);
        ArtifactOnlyScenarioValidator.ValidationResult validation = validator.validate(
                execution,
                oracle,
                tempDir.resolve("evidence")
        );

        assertEquals("PASS", validation.verdict());
        assertTrue(validation.failureCodes().isEmpty());
        assertTrue(Files.exists(tempDir.resolve("evidence/api-build-summary.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence/scenario-catalog.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence/scenario-coverage-plan.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence/coverage.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence/verdict.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence/evidence-index.json")));
        assertEquals(
                Set.of("API_BUILD_SUMMARY", "SCENARIO_CATALOG", "SCENARIO_COVERAGE_PLAN", "COVERAGE", "VERDICT", "EVIDENCE_INDEX"),
                validation.evidenceBundle().artifacts().stream().map(artifact -> artifact.type()).collect(Collectors.toSet())
        );

        Map<String, Object> scenarioCoveragePlan = objectMapper.readValue(
                tempDir.resolve("evidence/scenario-coverage-plan.json").toFile(),
                new TypeReference<>() {}
        );
        assertTrue(((List<?>) scenarioCoveragePlan.get("runtimePromotionCandidates")).size() > 0);
        assertTrue(((List<?>) scenarioCoveragePlan.get("liveRuntimeScenarioIds")).size() > 0);

        Map<String, Object> verdict = objectMapper.readValue(
                tempDir.resolve("evidence/verdict.json").toFile(),
                new TypeReference<>() {}
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> verdictScenarioCoveragePlan = (Map<String, Object>) verdict.get("scenarioCoveragePlan");
        assertNotNull(verdictScenarioCoveragePlan);
        assertEquals(scenarioCoveragePlan.get("scenarioCount"), verdictScenarioCoveragePlan.get("scenarioCount"));
        assertEquals(((List<?>) scenarioCoveragePlan.get("darkAreas")).size(), verdictScenarioCoveragePlan.get("darkAreaCount"));
        assertEquals(scenarioCoveragePlan.get("scenariosStayWithinActiveDenominator"), verdictScenarioCoveragePlan.get("scenariosStayWithinActiveDenominator"));
    }

    private Map<String, Object> loadOracle() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("e2e/oracle/loan_master/data-oracle.json")) {
            assertNotNull(in, "Missing loan master oracle resource");
            return objectMapper.readValue(in, new TypeReference<>() {});
        }
    }
}
