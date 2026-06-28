package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrioritySemanticPackScaffoldTest {

    private static final Path FIXTURE_ROOT = Path.of("src/test/resources/e2e/fixtures/blueprint_semantic");
    private static final Path ORACLE_ROOT = Path.of("src/test/resources/e2e/oracle/blueprint_semantic");
    private static final List<String> PRIORITY_BLUEPRINTS = List.of(
            "GenericFilter",
            "GenericJoin",
            "GenericRouter",
            "IncrementalMerge",
            "SCD2Dimension",
            "SnapshotModel",
            "DQValidator",
            "FreshnessChecks",
            "SchemaDriftDetection",
            "AnomalyDetection"
    );

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void prioritySemanticPacksExistWithStableFileGraphs() throws Exception {
        for (String blueprint : PRIORITY_BLUEPRINTS) {
            Path packRoot = FIXTURE_ROOT.resolve(blueprint);
            Path oracleRoot = ORACLE_ROOT.resolve(blueprint);
            Path manifestPath = packRoot.resolve("fixture-manifest.json");
            Path oraclePath = oracleRoot.resolve("oracle.json");
            Path comparatorPath = oracleRoot.resolve("comparator-config.json");

            assertTrue(Files.exists(manifestPath), () -> "missing manifest for " + blueprint);
            assertTrue(Files.exists(oraclePath), () -> "missing oracle for " + blueprint);
            assertTrue(Files.exists(comparatorPath), () -> "missing comparator config for " + blueprint);

            JsonNode manifest = objectMapper.readTree(manifestPath.toFile());
            JsonNode oracle = objectMapper.readTree(oraclePath.toFile());
            JsonNode comparator = objectMapper.readTree(comparatorPath.toFile());

            assertEquals(blueprint, manifest.get("blueprintKey").asText());
            assertEquals(blueprint, oracle.get("blueprintKey").asText());
            assertFalse(manifest.get("semanticPackVersion").asText().isBlank());
            assertFalse(manifest.get("businessScenario").asText().isBlank());
            assertTrue(manifest.get("businessScenario").asText().length() >= 40);
            assertTrue(manifest.get("inputFiles").isArray());
            assertFalse(manifest.get("inputFiles").isEmpty());
            assertTrue(manifest.get("expectedOutputs").isArray() || manifest.get("expectedMetrics").isArray());
            assertEquals("comparator-config.json", manifest.get("comparator").get("configPath").asText());
            assertEquals(comparator.get("type").asText(), manifest.get("comparator").get("type").asText());

            for (JsonNode inputFile : manifest.get("inputFiles")) {
                assertTrue(Files.exists(packRoot.resolve(inputFile.get("path").asText())),
                        () -> "missing input artifact for " + blueprint + ": " + inputFile.get("path").asText());
            }
            for (JsonNode expectedOutput : manifest.get("expectedOutputs")) {
                assertTrue(Files.exists(packRoot.resolve(expectedOutput.get("path").asText())),
                        () -> "missing expected output artifact for " + blueprint + ": " + expectedOutput.get("path").asText());
            }
            for (JsonNode expectedMetric : manifest.get("expectedMetrics")) {
                assertTrue(Files.exists(packRoot.resolve(expectedMetric.get("path").asText())),
                        () -> "missing expected metric artifact for " + blueprint + ": " + expectedMetric.get("path").asText());
            }

            if ("SnapshotModel".equals(blueprint)) {
                assertTrue(manifest.get("promotionEligible").asBoolean());
                assertFalse(manifest.get("promotionCriteria").isEmpty());
                assertEquals("gcp-golden", oracle.get("proofShape").asText());
            } else if (!"GenericFilter".equals(blueprint)) {
                assertFalse(manifest.get("promotionEligible").asBoolean());
                assertEquals("blocked-semantic-dev", oracle.get("proofShape").asText());
            } else {
                assertEquals("gcp-golden", oracle.get("proofShape").asText());
            }

            boolean stateful = manifest.get("scopeClassifications").get("statefulScope").asBoolean();
            if (stateful) {
                assertFalse(manifest.get("multiRunCases").isEmpty(), () -> "stateful pack missing multiRunCases for " + blueprint);
            }
        }
    }
}
