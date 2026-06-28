package com.pulse.blueprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PKT-0006: Static guard test that scans source artifacts for deprecated
 * blueprint aliases used as executable scenario identifiers. Deprecated keys
 * may appear in migration SQL (historical), compatibility code (clearly
 * marked), and reference/historical docs — but must NOT appear as executable
 * step names in the benchmark suite proof path, AI system prompts for new
 * composition, or compile-plan layer-resolution switches.
 */
class BlueprintStaleNameGuardTest {

    /**
     * Deprecated blueprint keys from V81 migration. These aliases were either
     * absorbed, superseded, or removed. They must not appear as executable
     * scenario identifiers in proof-path docs or active code generation prompts.
     */
    private static final Set<String> DEPRECATED_KEYS = Set.of(
            "FlattenNestedStructures",
            "EnrichmentJoin",
            "ConformanceToEnterpriseModel",
            "DerivedMetricsComputation",
            "DataVaultHubLinkSat",
            "TimeSeriesOptimization",
            "GXExpectationSuite",
            "ReferentialIntegrityCheck",
            "DQScorecardPublish",
            "Reconciliation",
            "QuarantineBadRecords",
            "GenericAggregation",  // legacy misnomer for GenericAggregate
            "StructBuilder"        // legacy misnomer for JsonStruct
    );

    /**
     * Pattern matches a deprecated key used as a blueprint key reference
     * (preceded by word boundary, not inside an English sentence). Matches
     * blueprint key patterns like: → DeprecatedKey, "DeprecatedKey",
     * Sub-pipelines: ... DeprecatedKey, blueprintKey = "DeprecatedKey".
     */
    private static Pattern asIdentifier(String key) {
        // Matches the key preceded by: arrow, quote, colon, equals, comma, pipe,
        // or start of line — typical blueprint-key-as-identifier contexts
        return Pattern.compile("(?:[→\"=:,|]\\s*|^\\s*-\\s*Sub-pipelines:.*)" + Pattern.quote(key) + "\\b",
                Pattern.MULTILINE);
    }

    // ------------------------------------------------------------------
    //  Benchmark suite proof path: no deprecated keys as scenario steps
    // ------------------------------------------------------------------

    @Test
    void benchmarkSuite_noDeprecatedKeysAsScenarioSteps() throws IOException {
        Path suiteDoc = findProjectRoot().resolve("docs/PIPELINE_BENCHMARK_SUITE.md");
        if (!Files.exists(suiteDoc)) {
            // Non-fatal in CI where docs may not be present
            return;
        }
        String content = Files.readString(suiteDoc);
        List<String> violations = DEPRECATED_KEYS.stream()
                .filter(key -> appearsAsScenarioStep(content, key))
                .toList();

        assertTrue(violations.isEmpty(),
                "Deprecated blueprint keys found as scenario steps in PIPELINE_BENCHMARK_SUITE.md: "
                        + violations + ". Replace with canonical active keys.");
    }

    // ------------------------------------------------------------------
    //  Compile-plan layer resolution: no deprecated keys in switch cases
    // ------------------------------------------------------------------

    @Test
    void compilePlanLayerSwitch_noDeprecatedKeys() throws IOException {
        Path compilePlan = findProjectRoot().resolve(
                "backend/src/main/java/com/pulse/codegen/service/CompilePlanService.java");
        if (!Files.exists(compilePlan)) {
            return;
        }
        String content = Files.readString(compilePlan);

        // Extract the resolveLayer switch block (between "return switch (key)" and the
        // closing "};") and check for deprecated keys in case labels.
        int switchStart = content.indexOf("return switch (key)");
        int switchEnd = content.indexOf("};", switchStart);
        if (switchStart < 0 || switchEnd < 0) {
            return; // Structure changed, skip
        }
        String switchBlock = content.substring(switchStart, switchEnd);

        List<String> violations = DEPRECATED_KEYS.stream()
                .filter(key -> switchBlock.contains("\"" + key + "\""))
                .toList();

        assertTrue(violations.isEmpty(),
                "Deprecated blueprint keys found in CompilePlanService layer-resolution switch: "
                        + violations + ". Remove — compat.rejectIfCompatReadOnly blocks these upstream.");
    }

    // ------------------------------------------------------------------
    //  AI system prompt: deprecated keys must not be suggested for new
    //  compositions (they may appear in schema-inference compat sections)
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "GenericAggregation",
            "GXExpectationSuite",
            "StructBuilder",
            "FlattenNestedStructures",
            "EnrichmentJoin",
            "ConformanceToEnterpriseModel",
            "DerivedMetricsComputation",
            "DataVaultHubLinkSat",
            "TimeSeriesOptimization",
            "ReferentialIntegrityCheck",
            "DQScorecardPublish",
            "Reconciliation",
            "QuarantineBadRecords"
    })
    void chatSystemPrompt_noDeprecatedKeysInBlueprintSuggestions(String key) throws IOException {
        Path chatService = findProjectRoot().resolve(
                "backend/src/main/java/com/pulse/chat/service/ChatService.java");
        if (!Files.exists(chatService)) {
            return;
        }
        String content = Files.readString(chatService);

        // Only flag if the key appears inside a SYSTEM_PROMPT string literal
        // as an available/selectable blueprint (not as a "do not use" warning).
        if (content.contains("SYSTEM_PROMPT")) {
            int promptStart = content.indexOf("SYSTEM_PROMPT");
            int promptEnd = content.indexOf("\"\"\";", promptStart);
            if (promptStart >= 0 && promptEnd >= 0) {
                String prompt = content.substring(promptStart, promptEnd);
                // Look for the key as a listed blueprint reference
                if (prompt.contains("\"" + key + "\"") || prompt.contains("- " + key + ":")) {
                    // Check local context (±200 chars) for deprecation markers
                    int keyIdx = prompt.indexOf(key);
                    while (keyIdx >= 0) {
                        int contextStart = Math.max(0, keyIdx - 200);
                        int contextEnd = Math.min(prompt.length(), keyIdx + key.length() + 200);
                        String vicinity = prompt.substring(contextStart, contextEnd).toLowerCase();
                        if (!vicinity.contains("deprecated") && !vicinity.contains("do not use")
                                && !vicinity.contains("replaced by") && !vicinity.contains("legacy")) {
                            fail("Deprecated blueprint key '" + key + "' appears in ChatService SYSTEM_PROMPT "
                                    + "as a selectable blueprint without deprecation context. "
                                    + "Remove or move to a deprecated/compat section.");
                        }
                        keyIdx = prompt.indexOf(key, keyIdx + key.length());
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Detects whether a deprecated key appears as a scenario step identifier
     * (after arrows, in Sub-pipelines lines, in blueprint list entries) vs.
     * plain English usage.
     */
    private boolean appearsAsScenarioStep(String content, String key) {
        // Pattern 1: "→ DeprecatedKey" (sub-pipeline step)
        if (content.contains("→ " + key)) return true;
        // Pattern 2: "| DeprecatedKey |" or "| DeprecatedKey," (table/list entry as active blueprint)
        if (Pattern.compile("\\|\\s*" + Pattern.quote(key) + "\\s*[|,]").matcher(content).find()) return true;
        // Pattern 3: In the Blueprints: line as an active key
        if (Pattern.compile("Blueprints:.*\\b" + Pattern.quote(key) + "\\b").matcher(content).find()) return true;
        return false;
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath();
        // Walk up until we find the project root (has docs/ and backend/ dirs)
        while (current != null) {
            if (Files.isDirectory(current.resolve("docs")) && Files.isDirectory(current.resolve("backend"))) {
                return current;
            }
            current = current.getParent();
        }
        // Fallback: assume CWD
        return Path.of("").toAbsolutePath();
    }
}
