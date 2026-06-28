package com.pulse.blueprint;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts V88 upgrades the {@code usage_guidance.when_to_use} text on the three
 * dbt-emitting transformation blueprints to explicitly disclaim DQ. Closes #27.
 *
 * <p>Why a SQL-text test instead of a SpringBootTest replay: Flyway is disabled in the
 * test profile and V81 (which seeds the blueprint rows V88 updates) doesn't run either,
 * so a SpringBootTest would have to manually populate every NOT NULL column on
 * {@code blueprints} before V88's UPDATE could even target a row. That work doesn't add
 * signal — it just verifies Hibernate, not the migration. The SQL-text assertion is a
 * reliable proxy until the Testcontainers harness (task #5) lands and we can replay
 * V81 → V88 against real Postgres.
 */
class V88BlueprintDqClarityTest {

    private static final Path V88 = Path.of(
            "src/main/resources/db/migration/V88__blueprint_dbt_dq_clarity.sql");

    @Test
    void v88UpdatesAllThreeDbtTransformBlueprints() throws Exception {
        String sql = Files.readString(V88);
        for (String key : List.of("BronzeToSilverCleaning", "PIIMasking", "SCD2Dimension")) {
            assertTrue(sql.contains("WHERE blueprint_key = '" + key + "'"),
                    "V88 must update blueprint_key = " + key);
        }
    }

    @Test
    void v88AddsDqDisclaimerToEveryUpdate() throws Exception {
        String sql = Files.readString(V88);
        // Each UPDATE must include the literal "NO data quality rules" disclaimer + the
        // pointer to DQValidator (GX). Both strings give the metadata enough specificity
        // for the agent to learn the boundary without ambiguity.
        long disclaimerCount = sql.lines()
                .filter(line -> line.contains("NO data quality rules"))
                .count();
        assertTrue(disclaimerCount >= 3,
                "V88 must include the 'NO data quality rules' disclaimer in all 3 UPDATEs; got " + disclaimerCount);
        long dqvalidatorCount = sql.lines()
                .filter(line -> line.contains("DQValidator (GX)"))
                .count();
        assertTrue(dqvalidatorCount >= 3,
                "V88 must point at 'DQValidator (GX)' as the DQ home in all 3 UPDATEs; got " + dqvalidatorCount);
    }

    @Test
    void v88TargetsTheWhenToUseField() throws Exception {
        String sql = Files.readString(V88);
        // jsonb_set on the {when_to_use} path is the contract; if anyone changes it to
        // overwrite the whole usage_guidance JSON they'll lose dbt_layer / other fields.
        assertTrue(sql.contains("'{when_to_use}'"),
                "V88 must use jsonb_set on the {when_to_use} path so other usage_guidance keys (dbt_layer, etc.) are preserved");
    }
}
