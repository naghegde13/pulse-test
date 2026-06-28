package com.pulse.blueprint;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL-text test for V95 — asserts the migration adds target/connector
 * binding fields (target_id, connector_instance_id, connector_name,
 * target_credential_ref) to all four DESTINATION blueprints flagged in
 * #47. Same pattern as V92ParamsSchemaAuditTest /
 * V93ParamsSchemaPhaseBTest.
 */
class V95DestinationBindingTest {

    private static final Path V95 = Path.of(
            "src/main/resources/db/migration/V95__destination_target_connector_binding.sql");

    @Test
    void v95UpdatesAllFourDestinationBlueprints() throws Exception {
        String sql = Files.readString(V95);
        for (String key : List.of("DatabaseWriter", "LakeWriter",
                "StreamWriter", "WarehouseWriter")) {
            assertTrue(sql.contains("WHERE blueprint_key = '" + key + "'"),
                    "V95 must update blueprint_key = " + key);
        }
    }

    @Test
    void v95AddsTargetIdToEveryDestinationBlueprint() throws Exception {
        String sql = Files.readString(V95);
        long count = sql.lines()
                .filter(line -> line.contains("\"name\": \"target_id\""))
                .count();
        assertTrue(count == 4,
                "V95 must add target_id to all 4 destinations; got " + count);
    }

    @Test
    void v95AddsConnectorInstanceIdToEveryDestinationBlueprint() throws Exception {
        String sql = Files.readString(V95);
        long count = sql.lines()
                .filter(line -> line.contains("\"name\": \"connector_instance_id\""))
                .count();
        assertTrue(count == 4,
                "V95 must add connector_instance_id to all 4 destinations; got " + count);
    }

    @Test
    void v95AddsConnectorNameDisplayField() throws Exception {
        String sql = Files.readString(V95);
        long count = sql.lines()
                .filter(line -> line.contains("\"name\": \"connector_name\""))
                .count();
        assertTrue(count == 4,
                "V95 must add connector_name to all 4 destinations; got " + count);
    }

    @Test
    void v95AddsTargetCredentialRefForExplicitOverride() throws Exception {
        String sql = Files.readString(V95);
        long count = sql.lines()
                .filter(line -> line.contains("\"name\": \"target_credential_ref\""))
                .count();
        assertTrue(count == 4,
                "V95 must add target_credential_ref to all 4 destinations; got " + count);
    }

    @Test
    void v95LakeWriterDeclaresLakeFormatSelector() throws Exception {
        String sql = Files.readString(V95);
        // LakeWriter must declare lake_format so the codegen layer
        // chooses between sinks/lake_delta.py and sinks/lake_iceberg.py.
        int lakeIdx = sql.indexOf("WHERE blueprint_key = 'LakeWriter'");
        int lakeFormatIdx = sql.indexOf("\"name\": \"lake_format\"");
        assertTrue(lakeFormatIdx > 0 && lakeFormatIdx < lakeIdx,
                "V95 LakeWriter must declare lake_format enum (delta|iceberg)");
    }

    @Test
    void v95StreamWriterDeclaresPublishModeAndSchemaStrategy() throws Exception {
        String sql = Files.readString(V95);
        int streamIdx = sql.indexOf("WHERE blueprint_key = 'StreamWriter'");
        for (String required : List.of(
                "\"name\": \"publish_mode\"",
                "\"name\": \"schema_strategy\"",
                "\"name\": \"key_columns\"")) {
            int fieldIdx = sql.indexOf(required);
            assertTrue(fieldIdx > 0 && fieldIdx < streamIdx,
                    "V95 StreamWriter must declare " + required);
        }
    }
}
