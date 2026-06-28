package com.pulse.blueprint;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL-text test for V94 — asserts the migration remaps example_keys for
 * the 8 blueprints flagged in #44 Phase D, each pointing at its now-
 * dedicated example file.
 */
class V94ExampleKeysRemapTest {

    private static final Path V94 = Path.of(
            "src/main/resources/db/migration/V94__remap_example_keys_to_dedicated_files.sql");

    @Test
    void v94RemapsAllEightBlueprints() throws Exception {
        String sql = Files.readString(V94);
        for (String key : List.of(
                "GenericRouter", "JsonFlatten", "JsonStruct",
                "SchemaNormalization", "FreshnessChecks",
                "FeatureTablePublish", "ReferenceDataPublish",
                "SnapshotModel")) {
            assertTrue(sql.contains("WHERE blueprint_key = '" + key + "'"),
                    "V94 must remap example_keys for blueprint_key = " + key);
        }
    }

    @Test
    void v94PointsEachBlueprintAtItsDedicatedFile() throws Exception {
        String sql = Files.readString(V94);
        // Each (blueprint, dedicated_example_key) pair must appear in the migration.
        List<String[]> expected = List.of(
                new String[]{"GenericRouter", "int_router_by_predicate"},
                new String[]{"JsonFlatten", "stg_json_flatten"},
                new String[]{"JsonStruct", "stg_json_struct"},
                new String[]{"SchemaNormalization", "stg_schema_normalization"},
                new String[]{"FreshnessChecks", "freshness_checks"},
                new String[]{"FeatureTablePublish", "feature_table_publish"},
                new String[]{"ReferenceDataPublish", "reference_data_publish"},
                new String[]{"SnapshotModel", "snp_snapshot_model"}
        );
        for (String[] pair : expected) {
            String blueprint = pair[0];
            String exampleKey = pair[1];
            // The migration's UPDATE statement for this blueprint MUST
            // contain the dedicated example_key. We assert co-presence
            // by finding the example key somewhere AFTER the example_keys
            // path setter and BEFORE the blueprint_key WHERE clause.
            String pattern = "\"" + exampleKey + "\"";
            assertTrue(sql.contains(pattern)
                            && sql.indexOf(pattern) < sql.indexOf(
                                    "WHERE blueprint_key = '" + blueprint + "'"),
                    "V94 must point " + blueprint + " at example_key " + exampleKey);
        }
    }
}
