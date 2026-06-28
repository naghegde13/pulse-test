package com.pulse.e2e.scenarios;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.contract.ScenarioDsl;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LoanMasterScenarioEvidenceContractTest {

    private static final Set<String> API_VALIDATED_ARTIFACTS = Set.of(
            "api-build-summary.json",
            "scenario-catalog.json",
            "scenario-coverage-plan.json",
            "coverage.json",
            "verdict.json",
            "evidence-index.json"
    );

    private static final Set<String> API_VALIDATED_TYPES = Set.of(
            "API_BUILD_SUMMARY",
            "SCENARIO_CATALOG",
            "SCENARIO_COVERAGE_PLAN",
            "COVERAGE",
            "VERDICT",
            "EVIDENCE_INDEX"
    );

    private static final Set<String> LIVE_RUNTIME_ARTIFACTS = Set.of(
            "dag-state.json",
            "task-state.json",
            "minio-output-probe.json",
            "data-oracle-comparison.json",
            "scenario-catalog.json",
            "scenario-coverage-plan.json",
            "coverage.json",
            "verdict.json",
            "evidence-index.json"
    );

    private static final Set<String> LIVE_RUNTIME_TYPES = Set.of(
            "AIRFLOW_DAG_STATE",
            "AIRFLOW_TASK_STATE",
            "SCENARIO_CATALOG",
            "SCENARIO_COVERAGE_PLAN",
            "COVERAGE",
            "VERDICT",
            "EVIDENCE_INDEX",
            "MINIO_OUTPUT_PROBE",
            "DATA_ORACLE_COMPARISON"
    );

    private static final Set<String> POSTGRES_LIVE_RUNTIME_ARTIFACTS = Set.of(
            "dag-state.json",
            "task-state.json",
            "postgres-output-probe.json",
            "postgres-data-oracle-comparison.json",
            "scenario-catalog.json",
            "scenario-coverage-plan.json",
            "coverage.json",
            "verdict.json",
            "evidence-index.json"
    );

    private static final Set<String> POSTGRES_LIVE_RUNTIME_TYPES = Set.of(
            "AIRFLOW_DAG_STATE",
            "AIRFLOW_TASK_STATE",
            "SCENARIO_CATALOG",
            "SCENARIO_COVERAGE_PLAN",
            "COVERAGE",
            "VERDICT",
            "EVIDENCE_INDEX",
            "POSTGRES_OUTPUT_PROBE",
            "POSTGRES_DATA_ORACLE_COMPARISON"
    );

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final LoanMasterScenarioFamilyCatalog catalogBuilder = new LoanMasterScenarioFamilyCatalog();

    @Test
    void canonicalCatalogStaysAlignedWithValidatorEvidenceContracts() throws Exception {
        var catalog = catalogBuilder.build(loadActiveBlueprintCatalog(), loadFixtureDerivativeIds());

        for (var spec : catalog.scenarios()) {
            ScenarioDsl.ScenarioDefinition scenario = spec.scenario();
            if (scenario.proofMode() == ScenarioDsl.ProofMode.LIVE_RUNTIME) {
                assertEquals(LIVE_RUNTIME_ARTIFACTS, Set.copyOf(scenario.evidenceExpectation().requiredArtifacts()),
                        () -> "Unexpected live-runtime artifacts for " + scenario.scenarioId());
                assertEquals(LIVE_RUNTIME_TYPES, Set.copyOf(scenario.evidenceExpectation().requiredEvidenceTypes()),
                        () -> "Unexpected live-runtime evidence types for " + scenario.scenarioId());
                assertEquals(List.of("build", "runtime", "data"), scenario.evidenceExpectation().requiredLayerIds(),
                        () -> "Unexpected live-runtime layer ids for " + scenario.scenarioId());
            } else {
                assertEquals(API_VALIDATED_ARTIFACTS, Set.copyOf(scenario.evidenceExpectation().requiredArtifacts()),
                        () -> "Unexpected API-validated artifacts for " + scenario.scenarioId());
                assertEquals(API_VALIDATED_TYPES, Set.copyOf(scenario.evidenceExpectation().requiredEvidenceTypes()),
                        () -> "Unexpected API-validated evidence types for " + scenario.scenarioId());
                assertEquals(List.of(), scenario.evidenceExpectation().requiredLayerIds(),
                        () -> "Non-runtime scenarios should not require runtime layer ids: " + scenario.scenarioId());
            }
        }
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
            var payload = objectMapper.readTree(in);
            var derivatives = payload.get("derivatives");
            assertNotNull(derivatives, "fixture-manifest.json missing derivatives array");
            return StreamSupport.stream(derivatives.spliterator(), false)
                    .map(entry -> entry.get("derivative_id").asText())
                    .collect(Collectors.toSet());
        }
    }
}
