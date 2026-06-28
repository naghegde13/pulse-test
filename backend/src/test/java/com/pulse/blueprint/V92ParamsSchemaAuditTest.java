package com.pulse.blueprint;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts V92 upgrades the 4 example-needing blueprints with date-shaped params
 * to flag accepts_mnemonic and add the calendar-config fields. Closes the schema
 * portion of #43.
 */
class V92ParamsSchemaAuditTest {

    private static final Path V92 = Path.of(
            "src/main/resources/db/migration/V92__params_schema_audit_date_mnemonic.sql");

    @Test
    void v92UpdatesAllFourBlueprintsWithDateShapedParams() throws Exception {
        String sql = Files.readString(V92);
        for (String key : List.of("BackfillAndReplay", "BulkBackfill",
                                   "FileArrivalSensor", "ObjectStoreKeySensor")) {
            assertTrue(sql.contains("WHERE blueprint_key = '" + key + "'"),
                    "V92 must update blueprint_key = " + key);
        }
    }

    @Test
    void v92AddsAcceptsMnemonicFlagOnEveryDateShapedParam() throws Exception {
        String sql = Files.readString(V92);
        long count = sql.lines()
                .filter(line -> line.contains("\"accepts_mnemonic\": true"))
                .count();
        assertTrue(count >= 6,
                "V92 must flag accepts_mnemonic on every date-shaped param; got " + count);
    }

    @Test
    void v92AddsCalendarConfigToEveryUpdatedBlueprint() throws Exception {
        String sql = Files.readString(V92);
        long calCount = sql.lines()
                .filter(line -> line.contains("\"name\": \"holiday_calendar_id\""))
                .count();
        long fiscalCount = sql.lines()
                .filter(line -> line.contains("\"name\": \"fiscal_offset_months\""))
                .count();
        assertTrue(calCount >= 4,
                "V92 must add holiday_calendar_id to every blueprint with mnemonic date params; got " + calCount);
        assertTrue(fiscalCount >= 4,
                "V92 must add fiscal_offset_months to every blueprint with mnemonic date params; got " + fiscalCount);
    }

    @Test
    void v92SplitsFileArrivalSensorPathIntoBucketAndFilenamePattern() throws Exception {
        String sql = Files.readString(V92);
        for (String requiredField : List.of(
                "\"name\": \"bucket\"",
                "\"name\": \"path_prefix\"",
                "\"name\": \"filename_pattern\"",
                "\"name\": \"pattern_kind\"",
                "\"name\": \"date_format\"",
                "\"name\": \"date_value\"",
                "\"name\": \"expected_size_min\"",
                "\"name\": \"multiple_files_mode\"")) {
            assertTrue(sql.contains(requiredField),
                    "V92 (FileArrivalSensor) params_schema must include " + requiredField);
        }
    }

    @Test
    void v92AddsGcsToFileArrivalSensorStorageKind() throws Exception {
        String sql = Files.readString(V92);
        // Search the FileArrivalSensor section for s3/gcs/sftp triple.
        assertTrue(sql.contains("\"s3\", \"gcs\", \"sftp\""),
                "FileArrivalSensor storage_kind enum must include s3, gcs, sftp after V92");
    }
}
