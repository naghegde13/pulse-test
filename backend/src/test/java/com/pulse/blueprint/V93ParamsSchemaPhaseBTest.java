package com.pulse.blueprint;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts V93's params_schema upgrades for the 7 blueprints from the #44
 * Phase A audit (FreshnessChecks, ApiIngestion, FileIngestion,
 * DatabaseReadinessSensor, ScheduleAndTriggers, BronzeToSilverCleaning,
 * DedupeAndMerge). Same SQL-text test pattern as
 * V88BlueprintDqClarityTest / V92ParamsSchemaAuditTest.
 */
class V93ParamsSchemaPhaseBTest {

    private static final Path V93 = Path.of(
            "src/main/resources/db/migration/V93__params_schema_audit_phase_b.sql");

    @Test
    void v93UpdatesAllSevenAuditedBlueprints() throws Exception {
        String sql = Files.readString(V93);
        for (String key : List.of(
                "FreshnessChecks", "ApiIngestion", "FileIngestion",
                "DatabaseReadinessSensor", "ScheduleAndTriggers",
                "BronzeToSilverCleaning", "DedupeAndMerge")) {
            assertTrue(sql.contains("WHERE blueprint_key = '" + key + "'"),
                    "V93 must update blueprint_key = " + key);
        }
    }

    @Test
    void v93AddsAcceptsMnemonicWhereDateInputsExist() throws Exception {
        String sql = Files.readString(V93);
        long count = sql.lines()
                .filter(line -> line.contains("\"accepts_mnemonic\": true"))
                .count();
        assertTrue(count >= 2,
                "V93 must flag accepts_mnemonic on FileIngestion.date_value and "
                        + "DatabaseReadinessSensor.date_value; got " + count);
    }

    @Test
    void v93AddsCalendarConfigToBlueprintsWithDateRelativeBehavior() throws Exception {
        String sql = Files.readString(V93);
        long calCount = sql.lines()
                .filter(line -> line.contains("\"name\": \"holiday_calendar_id\""))
                .count();
        assertTrue(calCount >= 3,
                "V93 must add holiday_calendar_id to FreshnessChecks, FileIngestion, "
                        + "DatabaseReadinessSensor; got " + calCount);
    }

    @Test
    void v93AddsScheduleAndTriggersOrchestrationFields() throws Exception {
        String sql = Files.readString(V93);
        for (String required : List.of(
                "\"name\": \"max_active_runs\"",
                "\"name\": \"catchup_enabled\"",
                "\"name\": \"depends_on_past\"")) {
            assertTrue(sql.contains(required),
                    "V93 ScheduleAndTriggers params_schema must include " + required);
        }
    }

    @Test
    void v93BronzeToSilverCleaningReflectsActualDbtModelBehavior() throws Exception {
        String sql = Files.readString(V93);
        for (String required : List.of(
                "\"name\": \"rename_map\"",
                "\"name\": \"columns_to_trim\"",
                "\"name\": \"partition_by\"")) {
            assertTrue(sql.contains(required),
                    "V93 BronzeToSilverCleaning params_schema must include " + required);
        }
    }

    @Test
    void v93DedupeAndMergeAddsDeterministicRankingFields() throws Exception {
        String sql = Files.readString(V93);
        for (String required : List.of(
                "\"name\": \"order_by_columns\"",
                "\"name\": \"partition_by\"",
                "\"name\": \"dedup_method\"")) {
            assertTrue(sql.contains(required),
                    "V93 DedupeAndMerge params_schema must include " + required);
        }
    }
}
