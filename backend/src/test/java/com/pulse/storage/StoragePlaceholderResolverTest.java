package com.pulse.storage;

import com.pulse.storage.model.LakeFormat;
import com.pulse.storage.model.LakeLayer;
import com.pulse.storage.model.StorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoragePlaceholderResolverTest {

    private StoragePlaceholderResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new StoragePlaceholderResolver(new PathConventionService());
    }

    private StorageBackend gcpDev() {
        StorageBackend sb = new StorageBackend();
        sb.setTenantId("tenant-home-lending");
        sb.setEnvironment("dev");
        sb.setBackend("GCP");
        sb.setStorageRootFiles("pulse-home-lending-dev-files");
        sb.setStorageRootLake("pulse-home-lending-dev-lake");
        sb.setGcpProject("pulse-home-lending-dev");
        return sb;
    }

    private StorageBackend dpcS3aDev() {
        StorageBackend sb = new StorageBackend();
        sb.setTenantId("tenant-home-lending");
        sb.setEnvironment("dev");
        sb.setBackend("DPC");
        sb.setStorageRootFiles("pulse-dpc-home-lending-dev-files");
        sb.setStorageRootLake("pulse-dpc-home-lending-dev-lake");
        sb.setDpcScheme("s3a");
        sb.setDpcCluster("pulse-dpc-home-lending-dev");
        return sb;
    }

    private StoragePlaceholderResolver.Context tableCtx(StorageBackend sb,
                                                         LakeLayer layer,
                                                         LakeFormat fmt) {
        return new StoragePlaceholderResolver.Context(
                sb, layer, fmt,
                "home-lending", "home-lending", "msp", "loan-master",
                "loan-master-raw",
                null, null, null);
    }

    @Test
    void resolve_dpcBronzeDelta_populatesTablePathNotBqTable() {
        var out = resolver.resolve(tableCtx(dpcS3aDev(), LakeLayer.BRONZE, LakeFormat.DELTA));
        assertEquals("DPC", out.get("__STORAGE_BACKEND__"));
        assertEquals("delta", out.get("__LAKE_FORMAT__"));
        assertEquals("bronze", out.get("__LAKE_LAYER__"));
        assertEquals(
                "s3a://pulse-dpc-home-lending-dev-lake/home-lending/msp/loan-master/bronze/loan-master-raw/",
                out.get("__TABLE_PATH__"));
        assertEquals("", out.get("__BQ_TABLE__"));
        assertEquals("", out.get("__GCP_PROJECT__"));
    }

    @Test
    void pathConvention_lakeLayerRoot_returnsDbtLocationRoot() {
        String root = new PathConventionService().lakeLayerRoot(
                dpcS3aDev(), LakeLayer.SILVER, "home-lending", "msp", "loan-master");
        assertEquals(
                "s3a://pulse-dpc-home-lending-dev-lake/home-lending/msp/loan-master/silver",
                root);
    }

    @Test
    void resolve_gcpGoldBqNative_populatesBqTableNotTablePath() {
        var out = resolver.resolve(tableCtx(gcpDev(), LakeLayer.GOLD, LakeFormat.BQ_NATIVE));
        assertEquals("GCP", out.get("__STORAGE_BACKEND__"));
        assertEquals("bq_native", out.get("__LAKE_FORMAT__"));
        assertEquals("gold", out.get("__LAKE_LAYER__"));
        assertEquals("", out.get("__TABLE_PATH__"));
        assertEquals("pulse-home-lending-dev.home_lending_gold.loan_master_raw",
                out.get("__BQ_TABLE__"));
        assertEquals("pulse-home-lending-dev", out.get("__GCP_PROJECT__"));
    }

    @Test
    void resolve_includesAllFiveFileLifecyclePaths() {
        var out = resolver.resolve(tableCtx(gcpDev(), LakeLayer.BRONZE, LakeFormat.DELTA));
        assertTrue(out.get("__FILES_SRC_PATH__").endsWith("/SRC/"));
        assertTrue(out.get("__FILES_PROCESSING_PATH__").endsWith("/Processing/"));
        assertTrue(out.get("__FILES_ARCHIVE_PATH__").endsWith("/Archive/"));
        assertTrue(out.get("__FILES_BAD_FILES_PATH__").endsWith("/bad_files/"));
        assertTrue(out.get("__FILES_OUTGOING_EXTRACTS_PATH__").endsWith("/outgoing_extracts/"));
    }

    @Test
    void resolve_populatesSlugPlaceholders() {
        var out = resolver.resolve(tableCtx(dpcS3aDev(), LakeLayer.SILVER, LakeFormat.DELTA));
        assertEquals("home-lending", out.get("__TENANT_SLUG__"));
        assertEquals("home-lending", out.get("__DOMAIN_SLUG__"));
        assertEquals("msp", out.get("__SOR_SLUG__"));
        assertEquals("loan-master", out.get("__PIPELINE_SLUG__"));
        assertEquals("loan-master-raw", out.get("__TABLE_SLUG__"));
    }

    @Test
    void resolve_populatesDbtSchema() {
        var out = resolver.resolve(tableCtx(dpcS3aDev(), LakeLayer.SILVER, LakeFormat.DELTA));
        assertEquals("home_lending_home_lending_silver", out.get("__DBT_SCHEMA__"));
    }

    @Test
    void resolve_nonTableBlueprint_emptyTablePlaceholders() {
        // DQValidator-style: no layer, no format, no table.
        var ctx = new StoragePlaceholderResolver.Context(
                dpcS3aDev(), null, null,
                "home-lending", "home-lending", "msp", "loan-master",
                null, null, null, null);
        var out = resolver.resolve(ctx);
        assertEquals("", out.get("__LAKE_LAYER__"));
        assertEquals("", out.get("__LAKE_FORMAT__"));
        assertEquals("", out.get("__TABLE_PATH__"));
        assertEquals("", out.get("__BQ_TABLE__"));
        assertEquals("", out.get("__TABLE_SLUG__"));
    }

    @Test
    void resolve_quarantinePath_onlyWhenDsAndTableProvided() {
        var ctx = new StoragePlaceholderResolver.Context(
                dpcS3aDev(), LakeLayer.SILVER, LakeFormat.DELTA,
                "home-lending", "home-lending", "msp", "loan-master",
                "customer-master", null, null, "2026-04-25");
        var out = resolver.resolve(ctx);
        assertEquals(
                "s3a://pulse-dpc-home-lending-dev-lake/home-lending/msp/loan-master/_quarantine/customer-master/2026-04-25/",
                out.get("__QUARANTINE_PATH__"));
    }

    @Test
    void resolve_checkpointPath_onlyWhenStreamNameProvided() {
        var ctx = new StoragePlaceholderResolver.Context(
                dpcS3aDev(), LakeLayer.BRONZE, LakeFormat.DELTA,
                "home-lending", "home-lending", "msp", "loan-master",
                "loan-master-cdc",
                "kafka-cdc-stream", null, null);
        var out = resolver.resolve(ctx);
        assertEquals(
                "s3a://pulse-dpc-home-lending-dev-lake/home-lending/msp/loan-master/_checkpoints/kafka-cdc-stream/",
                out.get("__CHECKPOINT_PATH__"));
    }

    @Test
    void resolve_gxDocsPath_onlyWhenCheckpointNameProvided() {
        var ctx = new StoragePlaceholderResolver.Context(
                gcpDev(), LakeLayer.SILVER, LakeFormat.DELTA,
                "home-lending", "home-lending", "msp", "loan-master",
                "customer-master", null, "bronze-silver-gate", null);
        var out = resolver.resolve(ctx);
        assertEquals(
                "gs://pulse-home-lending-dev-lake/home-lending/msp/loan-master/_gx_docs/bronze-silver-gate/",
                out.get("__GX_DOCS_PATH__"));
    }

    @Test
    void context_rejectsLayerWithoutFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> new StoragePlaceholderResolver.Context(
                        dpcS3aDev(), LakeLayer.BRONZE, null,
                        "home-lending", "home-lending", "msp", "loan-master",
                        "x", null, null, null));
    }

    @Test
    void context_rejectsFormatWithoutLayer() {
        assertThrows(IllegalArgumentException.class,
                () -> new StoragePlaceholderResolver.Context(
                        dpcS3aDev(), null, LakeFormat.DELTA,
                        "home-lending", "home-lending", "msp", "loan-master",
                        "x", null, null, null));
    }

    @Test
    void context_rejectsNullBackend() {
        assertThrows(IllegalArgumentException.class,
                () -> new StoragePlaceholderResolver.Context(
                        null, LakeLayer.BRONZE, LakeFormat.DELTA,
                        "home-lending", "home-lending", "msp", "loan-master",
                        "x", null, null, null));
    }

    @Test
    void context_rejectsNullSlugs() {
        assertThrows(IllegalArgumentException.class,
                () -> new StoragePlaceholderResolver.Context(
                        dpcS3aDev(), LakeLayer.BRONZE, LakeFormat.DELTA,
                        null, "d", "s", "p", "t",
                        null, null, null));
    }

    @Test
    void substitute_replacesEveryOccurrence() {
        Map<String, String> ph = Map.of(
                "__LAKE_FORMAT__", "delta",
                "__TABLE_PATH__", "s3a://bucket/path/");
        String template = "format=__LAKE_FORMAT__, path=__TABLE_PATH__, again=__TABLE_PATH__";
        String result = StoragePlaceholderResolver.substitute(template, ph);
        assertEquals("format=delta, path=s3a://bucket/path/, again=s3a://bucket/path/", result);
    }

    @Test
    void substitute_leavesUnknownPlaceholdersUntouched() {
        Map<String, String> ph = Map.of("__LAKE_FORMAT__", "delta");
        String result = StoragePlaceholderResolver.substitute(
                "fmt=__LAKE_FORMAT__ unknown=__NOT_DEFINED__", ph);
        assertEquals("fmt=delta unknown=__NOT_DEFINED__", result);
    }

    @Test
    void substitute_handlesNullTemplate() {
        assertEquals(null, StoragePlaceholderResolver.substitute(null, Map.of()));
    }
}
