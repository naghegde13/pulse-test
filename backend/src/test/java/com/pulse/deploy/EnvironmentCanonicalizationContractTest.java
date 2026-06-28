package com.pulse.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.deploy.environment.DeploymentEnvironment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 contract test for the canonical {@link DeploymentEnvironment} model.
 *
 * <p>Pins the legacy → canonical mapping required by the deployment
 * productization plan and proves the persisted form is always lowercase.
 * Also asserts the {@code environment-normalization-matrix.json} evidence
 * fixture documents every mapping the enum actually supports.
 */
class EnvironmentCanonicalizationContractTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource({
            // Canonical lowercase (idempotent).
            "local,        local",
            "dev,          dev",
            "integration,  integration",
            "uat,          uat",
            "prod,         prod",
            // Legacy uppercase / shorthand.
            "LOCAL,        local",
            "DEV,          dev",
            "INT,          integration",
            "INTEGRATION,  integration",
            "UAT,          uat",
            "PROD,         prod",
            "PRODUCTION,   prod"
    })
    void normalizesEveryAcceptedAlias(String input, String expectedCanonical) {
        assertEquals(expectedCanonical, DeploymentEnvironment.normalize(input));
        assertEquals(expectedCanonical, DeploymentEnvironment.parse(input).key());
    }

    @Test
    @DisplayName("Canonical persisted keys are exactly the five expected lowercase values")
    void canonicalKeysAreLockedSet() {
        Set<String> keys = new LinkedHashSet<>();
        for (DeploymentEnvironment env : DeploymentEnvironment.values()) {
            keys.add(env.key());
            assertEquals(env.key(), env.key().toLowerCase(java.util.Locale.ROOT),
                    "canonical key must be lowercase: " + env.key());
        }
        assertEquals(Set.of("local", "dev", "integration", "uat", "prod"), keys);
    }

    @Test
    @DisplayName("Labels are presentation-only and never used for storage")
    void labelsMatchProductSpec() {
        assertEquals("Local", DeploymentEnvironment.LOCAL.label());
        assertEquals("Dev", DeploymentEnvironment.DEV.label());
        assertEquals("Integration", DeploymentEnvironment.INTEGRATION.label());
        assertEquals("UAT", DeploymentEnvironment.UAT.label());
        assertEquals("Production", DeploymentEnvironment.PROD.label());
    }

    @ParameterizedTest
    @CsvSource({
            "STAGING",
            "preprod",
            "production1",
            "qa",
            "DEV ENVIRONMENT",
            "dev1"
    })
    @DisplayName("Unknown environment values throw with a clear, actionable message")
    void unknownInputsAreRejected(String input) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DeploymentEnvironment.parse(input));
        assertTrue(ex.getMessage().contains("Unknown deployment environment"),
                "Expected error message to call out unknown env, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("local, dev, integration, uat, prod"),
                "Expected error message to enumerate canonical values, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Null and blank inputs are rejected by parse() but tolerated by normalizeNullable()")
    void nullAndBlankBoundaryBehavior() {
        assertThrows(IllegalArgumentException.class, () -> DeploymentEnvironment.parse(null));
        assertThrows(IllegalArgumentException.class, () -> DeploymentEnvironment.parse(""));
        assertThrows(IllegalArgumentException.class, () -> DeploymentEnvironment.parse("   "));

        assertNull(DeploymentEnvironment.normalizeNullable(null));
        assertNull(DeploymentEnvironment.normalizeNullable(""));
        assertNull(DeploymentEnvironment.normalizeNullable("   "));
        assertEquals("dev", DeploymentEnvironment.normalizeNullable("DEV"));
        assertEquals("prod", DeploymentEnvironment.normalizeNullable("PRODUCTION"));
    }

    @Test
    @DisplayName("environment-normalization-matrix.json fixture matches the enum behavior")
    void evidenceFixtureMatchesEnumBehavior() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream(
                "/deploy/environment-normalization-matrix.json")) {
            assertNotNull(in, "environment-normalization-matrix.json missing from test resources");
            JsonNode root = mapper.readTree(in);

            // Every canonical key the matrix lists must exist in the enum.
            Set<String> matrixCanonical = new HashSet<>();
            for (JsonNode envNode : root.path("canonicalEnvironments")) {
                String key = envNode.path("key").asText();
                matrixCanonical.add(key);
                // Round-trips through parse().
                assertEquals(key, DeploymentEnvironment.parse(key).key(),
                        "matrix canonical key must round-trip through DeploymentEnvironment.parse()");
            }
            assertEquals(Set.of("local", "dev", "integration", "uat", "prod"), matrixCanonical);

            // Every legacy mapping the matrix declares must hold at runtime.
            for (JsonNode mapping : root.path("legacyToCanonical")) {
                String input = mapping.path("input").asText();
                String expected = mapping.path("canonical").asText();
                assertEquals(expected, DeploymentEnvironment.normalize(input),
                        "matrix mapping mismatch for input " + input);
            }

            // Every "rejected" non-null value in the matrix must actually be
            // rejected by parse(); null / blank entries are covered by the
            // dedicated test above so we skip them here.
            for (JsonNode rejected : root.path("rejectedInputs")) {
                JsonNode inputNode = rejected.path("input");
                if (inputNode.isNull()) continue;
                String input = inputNode.asText();
                if (input == null || input.isBlank()) continue;
                assertThrows(IllegalArgumentException.class,
                        () -> DeploymentEnvironment.parse(input),
                        "matrix declares input '" + input + "' is rejected, but parse() accepted it");
            }
        }
    }
}
