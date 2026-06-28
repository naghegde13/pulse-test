package com.pulse.e2e.coverage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.e2e.scenarios.LoanMasterScenarioFamilyCatalog;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoanMasterScenarioCoveragePlanBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ActiveBlueprintCoverageCatalogBuilder coverageCatalogBuilder = new ActiveBlueprintCoverageCatalogBuilder();
    private final LoanMasterScenarioCoveragePlanBuilder planBuilder = new LoanMasterScenarioCoveragePlanBuilder();
    private final LoanMasterScenarioFamilyCatalog scenarioCatalogBuilder = new LoanMasterScenarioFamilyCatalog();

    @Test
    void build_emitsExplicitCoverageDarkAreasAgainstActiveDenominator() throws Exception {
        var activeBlueprints = loadBlueprints("e2e/coverage/api-active-blueprints.json");
        var allBlueprints = loadBlueprints("e2e/coverage/api-all-blueprints.json");
        var coverageArtifacts = coverageCatalogBuilder.build(activeBlueprints, allBlueprints);
        var scenarios = scenarioCatalogBuilder.build(loadActiveCatalogRecords(), loadFixtureDerivativeIds()).scenarios().stream()
                .map(LoanMasterScenarioFamilyCatalog.ScenarioFamilySpec::scenario)
                .toList();

        var plan = planBuilder.build(scenarios, coverageArtifacts);

        assertEquals(29, plan.scenarioCount());
        assertEquals(27L, plan.scenariosByProofMode().get("LIVE_RUNTIME"));
        assertEquals(0L, plan.scenariosByProofMode().getOrDefault("STATIC_DEPLOYABILITY", 0L));
        assertEquals(2L, plan.scenariosByProofMode().get("ARTIFACT_ONLY"));
        assertEquals(27L, plan.scenariosByHardProofStatus().get("PASS"));
        assertEquals(0L, plan.scenariosByHardProofStatus().getOrDefault("PENDING", 0L));
        assertEquals(2L, plan.scenariosByHardProofStatus().get("BLOCKED"));
        assertEquals(coverageArtifacts.activeBlueprintCatalog().activeCatalogChecksum(), plan.activeCatalogChecksum());
        assertEquals(coverageArtifacts.coverageDenominator().denominatorChecksum(), plan.denominatorChecksum());
        assertTrue(plan.scenariosStayWithinActiveDenominator(), "Scenarios must not reference deprecated/deferred blueprints");
        assertFalse(plan.uncoveredActiveBlueprintKeys().isEmpty(), "Plan should make remaining dark areas explicit");
        assertFalse(plan.darkAreas().isEmpty(), "Dark areas should be enumerated for uncovered active blueprints");
        assertTrue(plan.coveredFamilies().containsAll(List.of("INGESTION", "DESTINATION", "TRANSFORM")));
        assertTrue(plan.coveredBlueprintKeys().contains("SCD2Dimension"));
        assertTrue(plan.coveredBlueprintKeys().contains("GenericAggregate"));
        assertEquals(2, plan.runtimePromotionCandidates().size(),
                "Scenarios without oracle-backed live-runtime proof should remain in the promotion backlog");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .allMatch(candidate -> "LIVE_RUNTIME".equals(candidate.targetProofMode())),
                "Static/artifact scenarios must target live runnability proof");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .allMatch(candidate -> "e2e/oracle/loan_master/data-oracle.json".equals(candidate.dataOraclePath())),
                "Promotion candidates must carry the canonical oracle path");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .allMatch(candidate -> candidate.dataOracleOverrides().containsKey("row_count")),
                "Promotion candidates must carry derivative oracle overrides");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .allMatch(candidate -> "blocked".equals(candidate.runtimePromotionStatus())),
                "Remaining backlog items must stay blocked until their runtime blockers are resolved");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "transform-json-flatten-servicing-static-deployability".equals(candidate.scenarioId())),
                "JsonFlatten should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "transform-dedupe-merge-current-static-deployability".equals(candidate.scenarioId())),
                "DedupeAndMerge should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "transform-json-struct-borrower-static-deployability".equals(candidate.scenarioId())),
                "JsonStruct should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "transform-schema-risk-band-static-deployability".equals(candidate.scenarioId())),
                "SchemaNormalization should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "transform-generic-router-investor-state-static-deployability".equals(candidate.scenarioId())),
                "GenericRouter should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "destination-database-delinquent-static-deployability".equals(candidate.scenarioId())),
                "DatabaseWriter should stay promoted once oracle-backed Postgres runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "modeling-aggregate-materialization-state-static-deployability".equals(candidate.scenarioId())),
                "AggregateMaterialization should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "modeling-feature-table-current-static-deployability".equals(candidate.scenarioId())),
                "FeatureTablePublish should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "modeling-reference-data-state-static-deployability".equals(candidate.scenarioId())),
                "ReferenceDataPublish should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "reuse-capable-fact-delinquent-static-deployability".equals(candidate.scenarioId())),
                "FactBuild should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "reuse-capable-wide-mart-delinquent-static-deployability".equals(candidate.scenarioId())),
                "WideDenormalizedMart should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "reuse-capable-scd2-current-static-deployability".equals(candidate.scenarioId())),
                "SCD2Dimension should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "orchestration-sensor-file-arrival-current-static-deployability".equals(candidate.scenarioId())),
                "FileArrivalSensor should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "ingestion-snapshot-full-static-deployability".equals(candidate.scenarioId())),
                "SnapshotIngestion should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "ingestion-backfill-delinquent-static-deployability".equals(candidate.scenarioId())),
                "BulkBackfill should stay promoted once oracle-backed runtime proof passes");
        assertTrue(plan.runtimePromotionCandidates().stream()
                        .noneMatch(candidate -> "data-quality-required-fields-live-runtime".equals(candidate.scenarioId())),
                "DQValidator should stay promoted once oracle-backed runtime proof passes");
        assertEquals(29, plan.proofLedgerEntries().size(),
                "Coverage plan must expose the hard-proof ledger for every representative blueprint");
        assertEquals(27L, plan.proofLedgerEntries().stream()
                        .filter(entry -> "PASS".equals(entry.hardProofStatus()))
                        .count());
        assertTrue(plan.proofLedgerEntries().stream()
                        .filter(entry -> "PASS".equals(entry.hardProofStatus()))
                        .allMatch(entry -> entry.hardProofArtifacts().stream()
                                .anyMatch(artifact -> ("DATA_ORACLE_COMPARISON".equals(artifact.artifactType())
                                        || "POSTGRES_DATA_ORACLE_COMPARISON".equals(artifact.artifactType()))
                                        && "PASS".equals(artifact.requiredVerdict()))),
                "PASS ledger entries must carry an oracle-comparison PASS artifact");
    }

    private List<Blueprint> loadBlueprints(String resourcePath) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, () -> "Missing resource: " + resourcePath);
            return objectMapper.readValue(in, new TypeReference<>() {});
        }
    }

    private List<LoanMasterScenarioFamilyCatalog.ActiveBlueprintCatalogRecord> loadActiveCatalogRecords() throws Exception {
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
