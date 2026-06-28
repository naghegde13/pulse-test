package com.pulse.e2e.coverage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoanMasterScenarioCoveragePlanWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir Path tempDir;

    @Test
    void writeCanonicalPlan_persistsScenarioCoveragePlanArtifact() throws Exception {
        Path output = tempDir.resolve("evidence/scenario-coverage-plan.json");
        var writer = new LoanMasterScenarioCoveragePlanWriter(objectMapper);

        var plan = writer.writeCanonicalPlan(output);

        assertTrue(Files.exists(output));
        Map<String, Object> json = new ObjectMapper().readValue(output.toFile(), new TypeReference<>() {});
        assertEquals(plan.denominatorChecksum(), json.get("denominatorChecksum"));
        assertEquals(plan.activeCatalogChecksum(), json.get("activeCatalogChecksum"));
        assertEquals(plan.scenarioCount(), ((Number) json.get("scenarioCount")).intValue());
        assertTrue(((java.util.List<?>) json.get("darkAreas")).size() > 0, "Expected explicit dark-area gaps");
        var promotionCandidates = (java.util.List<?>) json.get("runtimePromotionCandidates");
        assertEquals(plan.runtimePromotionCandidates().size(), promotionCandidates.size());
        assertTrue(promotionCandidates.stream()
                .map(item -> (Map<?, ?>) item)
                .allMatch(item -> "LIVE_RUNTIME".equals(item.get("targetProofMode"))));
        assertTrue(promotionCandidates.stream()
                .map(item -> (Map<?, ?>) item)
                .allMatch(item -> item.containsKey("requiredRuntimeCapabilities")
                        && item.containsKey("localExecutionFeasibility")
                        && item.containsKey("promotionGate")
                        && item.containsKey("proofStandard")));
        assertEquals(27, ((java.util.List<?>) json.get("liveRuntimeScenarioIds")).size());
        assertEquals(29, ((java.util.List<?>) json.get("proofLedgerEntries")).size());
        assertEquals(27, ((Map<?, ?>) json.get("scenariosByHardProofStatus")).get("PASS"));
        assertEquals(
                plan.runtimePromotionCandidates().stream().map(candidate -> candidate.scenarioId()).sorted().toList(),
                promotionCandidates.stream()
                        .map(item -> (Map<?, ?>) item)
                        .map(item -> String.valueOf(item.get("scenarioId")))
                        .sorted()
                        .toList()
        );
        assertEquals(
                plan.liveRuntimeScenarioIds().stream().sorted().toList(),
                ((java.util.List<?>) json.get("liveRuntimeScenarioIds")).stream()
                        .map(String::valueOf)
                        .sorted()
                        .toList()
        );
        assertEquals(
                plan.scenariosByProofMode().values().stream().mapToLong(Long::longValue).sum(),
                ((Number) json.get("scenarioCount")).longValue()
        );
    }
}
