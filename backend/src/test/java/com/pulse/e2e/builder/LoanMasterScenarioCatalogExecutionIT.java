package com.pulse.e2e.builder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.e2e.LoanMasterFixture;
import com.pulse.e2e.api.ApiScenarioClient;
import com.pulse.e2e.contract.ScenarioDsl;
import com.pulse.e2e.scenarios.LoanMasterScenarioFamilyCatalog;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.LocalGitService;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "pulse.git.local-repo-base=${java.io.tmpdir}/pulse-e2e-catalog-builder-repos")
@Transactional
class LoanMasterScenarioCatalogExecutionIT {

    private static final Logger log = LoggerFactory.getLogger(LoanMasterScenarioCatalogExecutionIT.class);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired private GitRepoRepository gitRepoRepository;
    @Autowired private LocalGitService localGitService;

    @PersistenceContext private EntityManager entityManager;

    @TempDir Path tempDir;

    @Test
    void scenarioFamiliesBuildThroughApiHarnessWithoutUnavailableInfrastructure() throws Exception {
        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        ApiScenarioBuilder builder = new ApiScenarioBuilder(
                new ApiScenarioClient(mockMvc, objectMapper),
                blueprintRepository,
                connectorDefinitionRepository,
                gitRepoRepository,
                localGitService,
                tempDir.resolve("tenant-git")
        );

        var scenarios = loadScenarioDefinitions();
        assertEquals(29, scenarios.size(), "expected expanded local-runnable loan_master scenario catalog");
        assertTrue(scenarios.stream().noneMatch(scenario -> scenario.builderPlan().blueprintKeys().contains("WarehouseWriter")),
                "Catalog must not require fake Snowflake/warehouse infrastructure");
        assertTrue(scenarios.stream().anyMatch(scenario -> scenario.builderPlan().blueprintKeys().contains("SCD2Dimension")),
                "SCD2Dimension should be in local Spark/dbt scope");

        int total = scenarios.size();
        int index = 0;
        long suiteStartNanos = System.nanoTime();
        Path progressLog = Path.of("build/e2e/scenario-catalog-progress.log");
        Files.createDirectories(progressLog.getParent());
        Files.deleteIfExists(progressLog);
        for (var baseScenario : scenarios) {
            int scenarioNumber = index + 1;
            var scenario = withTenant(baseScenario, "tenant-lm-cat-" + index++);
            ApiScenarioBuilder.ScenarioExecution execution;
            long scenarioStartNanos = System.nanoTime();
            try {
                execution = builder.execute(scenario, fixture);
            } catch (Exception | AssertionError ex) {
                fail("Scenario failed through API builder: " + scenario.scenarioId()
                        + " blueprints=" + scenario.builderPlan().blueprintKeys(), ex);
                return;
            }

            assertEquals("COMPLETED", execution.generationRun().getStatus(), scenario.scenarioId());
            assertEquals("ARTIFACT_BUNDLE", execution.builtPackage().getPackageType(), scenario.scenarioId());
            assertNotNull(execution.builtPackage().getArtifactHash(), scenario.scenarioId());
            assertEquals(
                    scenario.builderPlan().blueprintKeys(),
                    execution.instances().stream().map(SubPipelineInstance::getBlueprintKey).toList(),
                    scenario.scenarioId()
            );
            assertTrue(execution.activeBlueprintKeys().containsAll(scenario.builderPlan().blueprintKeys()),
                    () -> "Scenario references inactive blueprint: " + scenario.scenarioId());
            assertFalse(execution.artifacts().isEmpty(), scenario.scenarioId());
            logScenarioProgress(progressLog, scenarioNumber, total, suiteStartNanos, scenarioStartNanos, scenario);
            entityManager.flush();
            entityManager.clear();
        }
    }

    private void logScenarioProgress(Path progressLog,
                                     int completed,
                                     int total,
                                     long suiteStartNanos,
                                     long scenarioStartNanos,
                                     ScenarioDsl.ScenarioDefinition scenario) throws Exception {
        long nowNanos = System.nanoTime();
        double elapsedSeconds = secondsBetween(suiteStartNanos, nowNanos);
        double lastScenarioSeconds = secondsBetween(scenarioStartNanos, nowNanos);
        double averageSeconds = elapsedSeconds / completed;
        double remainingSeconds = averageSeconds * (total - completed);
        double percent = (completed * 100.0) / total;
        String percentText = String.format(Locale.ROOT, "%.1f%%", percent);
        String elapsedText = formatSeconds(elapsedSeconds);
        String averageText = formatSeconds(averageSeconds);
        String remainingText = formatSeconds(remainingSeconds);
        String lastScenarioText = formatSeconds(lastScenarioSeconds);

        log.info(
                "Scenario catalog progress completed={}/{} percent={} elapsed={} avg_per_scenario={} eta={} last_scenario={} last_duration={} proof_mode={} blueprints={}",
                completed,
                total,
                percentText,
                elapsedText,
                averageText,
                remainingText,
                scenario.scenarioId(),
                lastScenarioText,
                scenario.proofMode(),
                scenario.builderPlan().blueprintKeys()
        );
        Files.writeString(progressLog, String.format(Locale.ROOT,
                        "timestamp=%s completed=%d total=%d percent=%s elapsed=%s avg_per_scenario=%s eta=%s last_scenario=%s last_duration=%s proof_mode=%s blueprints=%s%n",
                        Instant.now(),
                        completed,
                        total,
                        percentText,
                        elapsedText,
                        averageText,
                        remainingText,
                        scenario.scenarioId(),
                        lastScenarioText,
                        scenario.proofMode(),
                        scenario.builderPlan().blueprintKeys()),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private double secondsBetween(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000_000.0;
    }

    private String formatSeconds(double seconds) {
        if (seconds < 60.0) {
            return String.format(Locale.ROOT, "%.1fs", seconds);
        }
        return String.format(Locale.ROOT, "%.1fm", seconds / 60.0);
    }

    private List<ScenarioDsl.ScenarioDefinition> loadScenarioDefinitions() throws Exception {
        return new LoanMasterScenarioFamilyCatalog()
                .build(loadActiveBlueprintCatalog(), loadFixtureDerivativeIds())
                .scenarios().stream()
                .map(LoanMasterScenarioFamilyCatalog.ScenarioFamilySpec::scenario)
                .toList();
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
