package com.pulse.e2e.scenarios;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoanMasterScenarioFamilyCatalogTest {

    private static final Path SNAPSHOT = Path.of("src/test/resources/e2e/scenarios/loan-master-scenario-families.json");
    private static final Set<String> LOCAL_DOCKER_RUNTIME_CAPABILITIES = Set.of(
            "airflow",
            "spark",
            "dbt",
            "gx",
            "minio",
            "postgres"
    );

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final LoanMasterScenarioFamilyCatalog catalogBuilder = new LoanMasterScenarioFamilyCatalog();

    @Test
    void catalogProducesExpandedDeterministicScenarioFamiliesAcrossLocalRunnableBlueprints() throws Exception {
        var activeBlueprints = loadActiveBlueprintCatalog();
        var derivativeIds = loadFixtureDerivativeIds();

        var catalog = catalogBuilder.build(activeBlueprints, derivativeIds);
        var scenarios = catalog.scenarios();

        assertEquals(29, scenarios.size(), "expected expanded deterministic local-runnable scenario catalog");
        assertEquals(29, scenarios.stream().map(LoanMasterScenarioFamilyCatalog.ScenarioFamilySpec::scenarioId).distinct().count());

        Map<String, Long> familyCounts = scenarios.stream()
                .collect(Collectors.groupingBy(LoanMasterScenarioFamilyCatalog.ScenarioFamilySpec::blueprintFamily,
                        TreeMap::new,
                        Collectors.counting()));
        assertEquals(Map.of(
                "DATA_QUALITY", 4L,
                "DESTINATION", 2L,
                "INGESTION", 3L,
                "MODELING", 5L,
                "ORCHESTRATION_POLICY", 1L,
                "ORCHESTRATION_SENSOR", 1L,
                "REUSE_CAPABLE", 3L,
                "TRANSFORM", 10L
        ), familyCounts);

        assertTrue(scenarios.stream().anyMatch(spec -> "SCD2Dimension".equals(spec.representativeBlueprintKey())),
                "SCD2Dimension is local Spark/dbt scope and must be explicitly covered");
        assertTrue(scenarios.stream().anyMatch(spec -> "GenericAggregate".equals(spec.representativeBlueprintKey())),
                "Local transform blueprints must be directly covered, not hidden as dark areas");
        assertTrue(scenarios.stream().noneMatch(spec -> spec.scenario().builderPlan().blueprintKeys().contains("WarehouseWriter")),
                "Catalog must not require fake warehouse infrastructure");

        for (var spec : scenarios) {
            assertTrue(derivativeIds.contains(spec.fixtureDerivativeId()), () -> "unknown derivative for " + spec.scenarioId());
            assertTrue(spec.scenario().builderPlan().blueprintKeys().contains(spec.representativeBlueprintKey()),
                    () -> "execution chain must include representative blueprint for " + spec.scenarioId());
            assertEquals("loan_master", spec.scenario().builderPlan().fixtureId());
            assertEquals("servicing", spec.scenario().builderPlan().domainSlug());
            assertEquals("loan_master", spec.scenario().builderPlan().sourceDataset());
            assertEquals("LOCAL_AIRFLOW_BRIDGE", spec.scenario().runtimeAdapter().name());
            assertEquals("e2e/fixtures/loan_master/fixture-manifest.json", spec.scenario().fixtureRefs().get("fixture_manifest"));
            assertEquals("e2e/oracle/loan_master/data-oracle.json", spec.scenario().fixtureRefs().get("data_oracle"));
            assertEquals(spec.fixtureDerivativeId(), spec.scenario().fixtureRefs().get("data_oracle_derivative_id"));
            @SuppressWarnings("unchecked")
            Map<String, Object> oracleOverrides = (Map<String, Object>) spec.scenario().fixtureRefs().get("data_oracle_overrides");
            assertNotNull(oracleOverrides, () -> "missing oracle overrides for " + spec.scenarioId());
            assertEquals(spec.fixtureDerivativeId(), oracleOverrides.get("derivative_id"));
            assertTrue(oracleOverrides.containsKey("row_count"), () -> "missing row count override for " + spec.scenarioId());
            assertTrue(oracleOverrides.containsKey("column_count"), () -> "missing column count override for " + spec.scenarioId());
            assertTrue(oracleOverrides.containsKey("canonical_csv_sha256"), () -> "missing checksum override for " + spec.scenarioId());
        }

        var liveRuntimeFamilies = scenarios.stream()
                .filter(spec -> spec.scenario().proofMode() == com.pulse.e2e.contract.ScenarioDsl.ProofMode.LIVE_RUNTIME)
                .toList();
        assertEquals(27, liveRuntimeFamilies.size(), "Only hard-proof ledger backed families should be marked live runtime");
        assertTrue(liveRuntimeFamilies.stream().allMatch(spec -> "DOCKER_COMPOSE_LOCAL".equals(spec.localExecutionFeasibility())));
        assertTrue(liveRuntimeFamilies.stream().allMatch(spec ->
                        LOCAL_DOCKER_RUNTIME_CAPABILITIES.containsAll(spec.requiredRuntimeCapabilities())),
                "Live-runtime scenarios must stay within the known local docker capability set");
        assertEquals(
                Set.of(
                        "ingestion-file-current-live-runtime",
                        "ingestion-backfill-delinquent-static-deployability",
                        "ingestion-snapshot-full-static-deployability",
                        "transform-bronze-cleaning-full-static-deployability",
                        "transform-filter-current-live-runtime",
                        "transform-generic-aggregate-state-static-deployability",
                        "transform-generic-join-investor-state-static-deployability",
                        "transform-generic-router-investor-state-static-deployability",
                        "transform-json-flatten-servicing-static-deployability",
                        "transform-schema-risk-band-static-deployability",
                        "transform-json-struct-borrower-static-deployability",
                        "transform-pii-masking-borrower-static-deployability",
                        "transform-dedupe-merge-current-static-deployability",
                        "modeling-aggregate-materialization-state-static-deployability",
                        "modeling-feature-table-current-static-deployability",
                        "modeling-incremental-merge-late-arriving-static-deployability",
                        "modeling-reference-data-state-static-deployability",
                        "reuse-capable-fact-delinquent-static-deployability",
                        "reuse-capable-scd2-current-static-deployability",
                        "reuse-capable-wide-mart-delinquent-static-deployability",
                        "data-quality-required-fields-live-runtime",
                        "data-quality-anomaly-current-artifact-only",
                        "data-quality-freshness-current-static-deployability",
                        "data-quality-schema-drift-full-static-deployability",
                        "orchestration-sensor-file-arrival-current-static-deployability",
                        "destination-lake-current-live-runtime",
                        "destination-database-delinquent-static-deployability"
                ),
                liveRuntimeFamilies.stream().map(LoanMasterScenarioFamilyCatalog.ScenarioFamilySpec::scenarioId).collect(Collectors.toSet())
        );
        assertTrue(liveRuntimeFamilies.stream()
                        .allMatch(spec -> "PASS".equals(spec.scenario().fixtureRefs().get("hard_proof_status"))),
                "Every live-runtime scenario must carry PASS hard-proof status");
        assertTrue(liveRuntimeFamilies.stream()
                        .allMatch(spec -> ((List<?>) spec.scenario().fixtureRefs().get("hard_proof_artifacts")).stream()
                                .map(Map.class::cast)
                                .anyMatch(artifact -> ("DATA_ORACLE_COMPARISON".equals(artifact.get("artifact_type"))
                                        || "POSTGRES_DATA_ORACLE_COMPARISON".equals(artifact.get("artifact_type")))
                                        && "PASS".equals(artifact.get("required_verdict")))),
                "Every live-runtime scenario must carry an oracle-comparison PASS artifact reference");

        var pendingFamilies = scenarios.stream()
                .filter(spec -> "pending_runtime_proof".equals(spec.scenario().fixtureRefs().get("runtime_promotion_status")))
                .toList();
        assertEquals(0, pendingFamilies.size(), "Unproven but feasible scenarios must remain pending runtime proof");
        assertTrue(pendingFamilies.stream()
                        .noneMatch(spec -> "transform-json-struct-borrower-static-deployability".equals(spec.scenarioId())),
                "JsonStruct should stay promoted once an accepted hard-proof bundle exists");
        assertTrue(pendingFamilies.stream()
                        .noneMatch(spec -> "reuse-capable-scd2-current-static-deployability".equals(spec.scenarioId())),
                "SCD2 should stay promoted once an accepted hard-proof bundle exists");
        assertTrue(pendingFamilies.stream()
                        .noneMatch(spec -> "destination-database-delinquent-static-deployability".equals(spec.scenarioId())),
                "DatabaseWriter should stay promoted once an accepted hard-proof bundle exists");
        assertTrue(pendingFamilies.stream()
                        .noneMatch(spec -> "modeling-aggregate-materialization-state-static-deployability".equals(spec.scenarioId())),
                "AggregateMaterialization should stay promoted once an accepted hard-proof bundle exists");
        assertTrue(pendingFamilies.stream()
                        .noneMatch(spec -> "modeling-feature-table-current-static-deployability".equals(spec.scenarioId())),
                "FeatureTablePublish should stay promoted once an accepted hard-proof bundle exists");
        assertTrue(pendingFamilies.stream()
                        .noneMatch(spec -> "modeling-reference-data-state-static-deployability".equals(spec.scenarioId())),
                "ReferenceDataPublish should stay promoted once an accepted hard-proof bundle exists");
        assertTrue(pendingFamilies.stream()
                        .noneMatch(spec -> "reuse-capable-fact-delinquent-static-deployability".equals(spec.scenarioId())),
                "FactBuild should stay promoted once an accepted hard-proof bundle exists");
        assertTrue(pendingFamilies.stream()
                        .noneMatch(spec -> "reuse-capable-wide-mart-delinquent-static-deployability".equals(spec.scenarioId())),
                "WideDenormalizedMart should stay promoted once an accepted hard-proof bundle exists");
        assertTrue(pendingFamilies.stream()
                        .noneMatch(spec -> "orchestration-sensor-file-arrival-current-static-deployability".equals(spec.scenarioId())),
                "FileArrivalSensor should stay promoted once an accepted hard-proof bundle exists");
        assertTrue(pendingFamilies.stream()
                        .noneMatch(spec -> "modeling-incremental-merge-late-arriving-static-deployability".equals(spec.scenarioId())),
                "IncrementalMerge should stay promoted once an accepted two-run hard-proof bundle exists");
        assertTrue(pendingFamilies.stream()
                        .noneMatch(spec -> "data-quality-required-fields-live-runtime".equals(spec.scenarioId())),
                "DQValidator should stay promoted once an accepted hard-proof bundle exists");

        var blockedFamilies = scenarios.stream()
                .filter(spec -> "blocked".equals(spec.scenario().fixtureRefs().get("runtime_promotion_status")))
                .toList();
        assertEquals(2, blockedFamilies.size(), "Explicitly blocked scenarios must remain blocked");
        assertTrue(blockedFamilies.stream().allMatch(spec ->
                        spec.scenario().fixtureRefs().containsKey("runtime_promotion_blocker")),
                "Blocked scenarios must surface an explicit runtime promotion blocker");
        assertEquals(
                Set.of(
                        "modeling-snapshot-late-arriving-artifact-only",
                        "orchestration-policy-advance-time-late-arriving-artifact-only"
                ),
                blockedFamilies.stream().map(LoanMasterScenarioFamilyCatalog.ScenarioFamilySpec::scenarioId).collect(Collectors.toSet())
        );
    }

    @Test
    void snapshotMatchesCheckedInScenarioFamilyCatalog() throws Exception {
        var catalog = catalogBuilder.build(loadActiveBlueprintCatalog(), loadFixtureDerivativeIds());
        String actual = objectMapper.writeValueAsString(catalog) + System.lineSeparator();
        maybeRewriteSnapshot(actual);
        assertTrue(Files.exists(SNAPSHOT), () -> "Missing snapshot file: " + SNAPSHOT);
        assertEquals(Files.readString(SNAPSHOT), actual);
    }

    private void maybeRewriteSnapshot(String actual) throws Exception {
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("UPDATE_E2E_SCENARIO_SNAPSHOTS", "false"))) {
            return;
        }
        Files.createDirectories(SNAPSHOT.getParent());
        Files.writeString(SNAPSHOT, actual);
    }

    private List<LoanMasterScenarioFamilyCatalog.ActiveBlueprintCatalogRecord> loadActiveBlueprintCatalog() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("e2e/coverage/active-blueprint-catalog.json")) {
            assertNotNull(in, "Missing active blueprint catalog resource");
            var root = objectMapper.readTree(in);
            List<LoanMasterScenarioFamilyCatalog.ActiveBlueprintCatalogRecord> blueprints = objectMapper.convertValue(
                    root.path("blueprints"),
                    new TypeReference<>() {}
            );
            assertNotNull(blueprints, "active-blueprint-catalog.json missing blueprints array");
            assertFalse(blueprints.isEmpty(), "active-blueprint-catalog.json should not be empty");
            return blueprints.stream()
                    .sorted(Comparator.comparing(LoanMasterScenarioFamilyCatalog.ActiveBlueprintCatalogRecord::blueprintKey))
                    .toList();
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
