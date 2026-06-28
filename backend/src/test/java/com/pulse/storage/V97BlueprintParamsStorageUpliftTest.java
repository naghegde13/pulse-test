package com.pulse.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL-text test for V97 blueprint params storage uplift (#30 P3). Same
 * pattern as V92ParamsSchemaAuditTest / V93ParamsSchemaPhaseBTest /
 * V95DestinationBindingTest / V96StorageBackendsTest.
 */
class V97BlueprintParamsStorageUpliftTest {

    private static final Path V97 = Path.of(
            "src/main/resources/db/migration/V97__blueprint_params_storage_backend_uplift.sql");

    @Test
    void v97AddsStorageBackendField() throws Exception {
        String sql = Files.readString(V97);
        assertTrue(sql.contains("\"name\": \"storage_backend\""),
                "V97 must add storage_backend field");
        assertTrue(sql.contains("\"options\": [\"DPC\", \"GCP\"]"),
                "V97 storage_backend options must be DPC|GCP");
    }

    @Test
    void v97TouchesAllRelevantCategoriesForStorageBackend() throws Exception {
        String sql = Files.readString(V97);
        assertTrue(sql.contains("category IN ('DATA_QUALITY','ORCHESTRATION','INGESTION','TRANSFORM','MODELING')"),
                "V97 must add storage_backend to all 5 affected categories");
    }

    @Test
    void v97AddsLakeLayerAndFormatToIngestionWithBronzeDefault() throws Exception {
        String sql = Files.readString(V97);
        // The INGESTION update must contain "default": "bronze".
        int ingestionIdx = sql.indexOf("category = 'INGESTION'");
        int bronzeDefaultIdx = sql.indexOf("\"default\": \"bronze\"");
        assertTrue(ingestionIdx > 0, "V97 must have an INGESTION update");
        assertTrue(bronzeDefaultIdx > 0 && bronzeDefaultIdx < ingestionIdx,
                "V97 INGESTION update must default lake_layer=bronze");
    }

    @Test
    void v97AddsLakeLayerAndFormatToTransformWithSilverDefault() throws Exception {
        String sql = Files.readString(V97);
        int transformIdx = sql.indexOf("category = 'TRANSFORM'");
        int silverDefaultIdx = sql.indexOf("\"default\": \"silver\"");
        assertTrue(transformIdx > 0, "V97 must have a TRANSFORM update");
        assertTrue(silverDefaultIdx > 0 && silverDefaultIdx < transformIdx,
                "V97 TRANSFORM update must default lake_layer=silver");
    }

    @Test
    void v97AddsLakeLayerAndFormatToModelingWithGoldDefault() throws Exception {
        String sql = Files.readString(V97);
        int modelingIdx = sql.indexOf("category = 'MODELING'");
        int goldDefaultIdx = sql.indexOf("\"default\": \"gold\"");
        assertTrue(modelingIdx > 0, "V97 must have a MODELING update");
        assertTrue(goldDefaultIdx > 0 && goldDefaultIdx < modelingIdx,
                "V97 MODELING update must default lake_layer=gold");
    }

    @Test
    void v97LakeFormatDeclaresAllFiveOptions() throws Exception {
        String sql = Files.readString(V97);
        for (String fmt : List.of("\"delta\"", "\"iceberg_external\"",
                "\"iceberg_bq_managed\"", "\"bq_native\"", "\"parquet\"")) {
            assertTrue(sql.contains(fmt),
                    "V97 lake_format options must include " + fmt);
        }
    }

    @Test
    void v97IsIdempotentViaNotLikeGuards() throws Exception {
        String sql = Files.readString(V97);
        // Each UPDATE has a NOT (params_schema::text LIKE '%"<field>"%')
        // guard so re-running V97 is a no-op.
        long count = sql.lines()
                .filter(line -> line.contains("NOT (params_schema::text LIKE"))
                .count();
        assertTrue(count >= 4,
                "V97 must guard each UPDATE against double-application; got " + count);
    }

    @Test
    void v97DoesNotTouchDestinationCategoryWhichIsAlreadyV95() throws Exception {
        String sql = Files.readString(V97);
        // Sinks (DESTINATION) handle storage_backend implicitly via
        // V95's connector_instance_id; V97 must not touch DESTINATION
        // schemas.
        assertTrue(!sql.contains("category = 'DESTINATION'"),
                "V97 must not modify DESTINATION blueprints (V95 owns them)");
    }
}
