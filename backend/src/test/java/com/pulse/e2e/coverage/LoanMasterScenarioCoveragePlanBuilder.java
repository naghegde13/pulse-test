package com.pulse.e2e.coverage;

import com.pulse.e2e.contract.ScenarioDsl;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class LoanMasterScenarioCoveragePlanBuilder {

    public LoanMasterScenarioCoveragePlan build(List<ScenarioDsl.ScenarioDefinition> scenarios,
                                                ActiveBlueprintCoverageArtifacts artifacts) {
        Map<String, ActiveBlueprintCoverageArtifacts.ActiveBlueprintRecord> byBlueprintKey =
                artifacts.activeBlueprintCatalog().blueprints().stream()
                        .collect(Collectors.toMap(
                                ActiveBlueprintCoverageArtifacts.ActiveBlueprintRecord::blueprintKey,
                                record -> record,
                                (left, right) -> left,
                                LinkedHashMap::new
                        ));

        Set<String> coveredBlueprintKeys = scenarios.stream()
                .flatMap(scenario -> scenario.builderPlan().blueprintKeys().stream())
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> uncoveredActiveBlueprintKeys = new TreeSet<>(artifacts.coverageDenominator().includedBlueprintKeys());
        uncoveredActiveBlueprintKeys.removeAll(coveredBlueprintKeys);

        Set<String> coveredFamilies = coveredBlueprintKeys.stream()
                .map(byBlueprintKey::get)
                .filter(record -> record != null)
                .map(ActiveBlueprintCoverageArtifacts.ActiveBlueprintRecord::family)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> uncoveredFamilies = new TreeSet<>(artifacts.coverageDenominator().blueprintsByFamily().keySet());
        uncoveredFamilies.removeAll(coveredFamilies);

        Map<String, Long> scenariosByProofMode = scenarios.stream()
                .collect(Collectors.groupingBy(
                        scenario -> scenario.proofMode().name(),
                        LinkedHashMap::new,
                        Collectors.counting()));

        Map<String, Long> scenariosByHardProofStatus = scenarios.stream()
                .collect(Collectors.groupingBy(
                        scenario -> String.valueOf(scenario.fixtureRefs().getOrDefault("hard_proof_status", "UNKNOWN")),
                        LinkedHashMap::new,
                        Collectors.counting()));

        Map<String, Long> scenariosByDerivative = scenarios.stream()
                .collect(Collectors.groupingBy(
                        scenario -> String.valueOf(scenario.fixtureRefs().get("fixture_derivative_id")),
                        LinkedHashMap::new,
                        Collectors.counting()));

        List<String> liveRuntimeScenarioIds = scenarios.stream()
                .filter(scenario -> scenario.proofMode() == ScenarioDsl.ProofMode.LIVE_RUNTIME)
                .map(ScenarioDsl.ScenarioDefinition::scenarioId)
                .toList();

        List<String> staticDeployabilityScenarioIds = scenarios.stream()
                .filter(scenario -> scenario.proofMode() == ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY)
                .map(ScenarioDsl.ScenarioDefinition::scenarioId)
                .toList();

        List<String> artifactOnlyScenarioIds = scenarios.stream()
                .filter(scenario -> scenario.proofMode() == ScenarioDsl.ProofMode.ARTIFACT_ONLY)
                .map(ScenarioDsl.ScenarioDefinition::scenarioId)
                .toList();

        List<RuntimePromotionCandidate> runtimePromotionCandidates = scenarios.stream()
                .filter(scenario -> scenario.proofMode() != ScenarioDsl.ProofMode.LIVE_RUNTIME)
                .map(scenario -> new RuntimePromotionCandidate(
                        scenario.scenarioId(),
                        scenario.proofMode().name(),
                        ScenarioDsl.ProofMode.LIVE_RUNTIME.name(),
                        scenario.builderPlan().blueprintKeys(),
                        String.valueOf(scenario.fixtureRefs().get("fixture_derivative_id")),
                        String.valueOf(scenario.fixtureRefs().get("data_oracle")),
                        asMap(scenario.fixtureRefs().get("data_oracle_overrides")),
                        asStringList(scenario.fixtureRefs().get("required_runtime_capabilities")),
                        String.valueOf(scenario.fixtureRefs().get("local_execution_feasibility")),
                        String.valueOf(scenario.fixtureRefs().get("runtime_promotion_status")),
                        scenario.fixtureRefs().get("runtime_promotion_blocker") == null
                                ? null
                                : String.valueOf(scenario.fixtureRefs().get("runtime_promotion_blocker")),
                        "Promote to live runnability proof once blueprint runtime semantics are known and required local infrastructure exists.",
                        "Static deployability/artifact proof is interim; live proof requires real execution, output probes, and oracle comparison."
                ))
                .toList();

        List<ProofLedgerEntry> proofLedgerEntries = scenarios.stream()
                .map(scenario -> new ProofLedgerEntry(
                        String.valueOf(scenario.fixtureRefs().get("representative_blueprint_key")),
                        scenario.scenarioId(),
                        String.valueOf(scenario.fixtureRefs().getOrDefault("runtime_promotion_source_proof_mode", scenario.proofMode().name())),
                        scenario.proofMode().name(),
                        String.valueOf(scenario.fixtureRefs().getOrDefault("hard_proof_status", "UNKNOWN")),
                        String.valueOf(scenario.fixtureRefs().getOrDefault("runtime_promotion_status", "unknown")),
                        scenario.builderPlan().blueprintKeys(),
                        asArtifactReferences(scenario.fixtureRefs().get("hard_proof_artifacts")),
                        scenario.fixtureRefs().get("runtime_promotion_blocker") == null
                                ? null
                                : String.valueOf(scenario.fixtureRefs().get("runtime_promotion_blocker"))
                ))
                .toList();

        List<ScenarioGap> darkAreas = uncoveredActiveBlueprintKeys.stream()
                .map(key -> {
                    var record = byBlueprintKey.get(key);
                    return new ScenarioGap(
                            key,
                            record == null ? "UNKNOWN" : record.family(),
                            record == null
                                    ? "Unknown active blueprint missing from active catalog snapshot"
                                    : "No canonical loan_master scenario currently exercises this active blueprint"
                    );
                })
                .toList();

        return new LoanMasterScenarioCoveragePlan(
                artifacts.activeBlueprintCatalog().activeCatalogChecksum(),
                artifacts.coverageDenominator().denominatorChecksum(),
                scenarios.size(),
                scenariosByProofMode,
                scenariosByHardProofStatus,
                scenariosByDerivative,
                List.copyOf(coveredBlueprintKeys),
                List.copyOf(uncoveredActiveBlueprintKeys),
                List.copyOf(coveredFamilies),
                List.copyOf(uncoveredFamilies),
                liveRuntimeScenarioIds,
                staticDeployabilityScenarioIds,
                artifactOnlyScenarioIds,
                runtimePromotionCandidates,
                proofLedgerEntries,
                darkAreas,
                new LinkedHashSet<>(artifacts.coverageDenominator().includedBlueprintKeys()).containsAll(coveredBlueprintKeys)
        );
    }

    private List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .toList();
        }
        if (value == null) {
            return List.of();
        }
        return List.of(String.valueOf(value));
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, entryValue) -> normalized.put(String.valueOf(key), entryValue));
            return Map.copyOf(normalized);
        }
        return Map.of();
    }

    private List<HardProofArtifact> asArtifactReferences(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(entry -> new HardProofArtifact(
                        String.valueOf(entry.get("artifact_role")),
                        String.valueOf(entry.get("artifact_type")),
                        String.valueOf(entry.get("path_pattern")),
                        entry.get("required_verdict") == null ? null : String.valueOf(entry.get("required_verdict")),
                        entry.get("producer_test") == null ? null : String.valueOf(entry.get("producer_test"))
                ))
                .toList();
    }

    public record LoanMasterScenarioCoveragePlan(
            String activeCatalogChecksum,
            String denominatorChecksum,
            int scenarioCount,
            Map<String, Long> scenariosByProofMode,
            Map<String, Long> scenariosByHardProofStatus,
            Map<String, Long> scenariosByDerivative,
            List<String> coveredBlueprintKeys,
            List<String> uncoveredActiveBlueprintKeys,
            List<String> coveredFamilies,
            List<String> uncoveredFamilies,
            List<String> liveRuntimeScenarioIds,
            List<String> staticDeployabilityScenarioIds,
            List<String> artifactOnlyScenarioIds,
            List<RuntimePromotionCandidate> runtimePromotionCandidates,
            List<ProofLedgerEntry> proofLedgerEntries,
            List<ScenarioGap> darkAreas,
            boolean scenariosStayWithinActiveDenominator
    ) {}

    public record RuntimePromotionCandidate(
            String scenarioId,
            String currentProofMode,
            String targetProofMode,
            List<String> blueprintKeys,
            String fixtureDerivativeId,
            String dataOraclePath,
            Map<String, Object> dataOracleOverrides,
            List<String> requiredRuntimeCapabilities,
            String localExecutionFeasibility,
            String runtimePromotionStatus,
            String runtimePromotionBlocker,
            String promotionGate,
            String proofStandard
    ) {}

    public record ProofLedgerEntry(
            String representativeBlueprintKey,
            String scenarioId,
            String sourceProofMode,
            String effectiveProofMode,
            String hardProofStatus,
            String runtimePromotionStatus,
            List<String> blueprintKeys,
            List<HardProofArtifact> hardProofArtifacts,
            String blockerReason
    ) {}

    public record HardProofArtifact(
            String artifactRole,
            String artifactType,
            String pathPattern,
            String requiredVerdict,
            String producerTest
    ) {}

    public record ScenarioGap(
            String blueprintKey,
            String family,
            String reason
    ) {}
}
