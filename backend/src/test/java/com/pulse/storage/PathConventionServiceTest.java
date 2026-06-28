package com.pulse.storage;

import com.pulse.storage.model.FileLifecycle;
import com.pulse.storage.model.LakeFormat;
import com.pulse.storage.model.LakeLayer;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.model.TableLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathConventionServiceTest {

    private PathConventionService svc;

    @BeforeEach
    void setUp() {
        svc = new PathConventionService();
    }

    private StorageBackend gcpDev() {
        StorageBackend sb = new StorageBackend();
        sb.setId("01JSTRG_HOME_DEV_GCP______");
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
        sb.setId("01JSTRG_HOME_DEV_DPC______");
        sb.setTenantId("tenant-home-lending");
        sb.setEnvironment("dev");
        sb.setBackend("DPC");
        sb.setStorageRootFiles("pulse-dpc-home-lending-dev-files");
        sb.setStorageRootLake("pulse-dpc-home-lending-dev-lake");
        sb.setDpcScheme("s3a");
        sb.setDpcCluster("pulse-dpc-home-lending-dev");
        return sb;
    }

    private StorageBackend dpcHdfsDev() {
        StorageBackend sb = dpcS3aDev();
        sb.setDpcScheme("hdfs");
        return sb;
    }

    // --- filesPath ---------------------------------------------------------

    @Test
    void filesPath_gcp_src() {
        String path = svc.filesPath(gcpDev(), "home-lending", "msp", "loan-master",
                FileLifecycle.SRC);
        assertEquals("gs://pulse-home-lending-dev-files/home-lending/msp/loan-master/SRC/", path);
    }

    @Test
    void filesPath_dpc_s3a_processing() {
        String path = svc.filesPath(dpcS3aDev(), "home-lending", "msp", "loan-master",
                FileLifecycle.PROCESSING);
        assertEquals("s3a://pulse-dpc-home-lending-dev-files/home-lending/msp/loan-master/Processing/",
                path);
    }

    @Test
    void filesPath_dpc_hdfs_archive() {
        String path = svc.filesPath(dpcHdfsDev(), "home-lending", "msp", "loan-master",
                FileLifecycle.ARCHIVE);
        assertEquals("hdfs://pulse-dpc-home-lending-dev-files/home-lending/msp/loan-master/Archive/",
                path);
    }

    @Test
    void filesPath_badFiles_lowercaseFolder() {
        // bad_files (renamed from Quarantine per user spec) preserves
        // snake_case as the folder name to distinguish from the
        // capitalized lifecycle folders.
        String path = svc.filesPath(gcpDev(), "home-lending", "msp", "loan-master",
                FileLifecycle.BAD_FILES);
        assertTrue(path.endsWith("/bad_files/"), "got: " + path);
    }

    @Test
    void filesPath_outgoingExtracts_lowercaseFolder() {
        String path = svc.filesPath(gcpDev(), "home-lending", "msp", "loan-master",
                FileLifecycle.OUTGOING_EXTRACTS);
        assertTrue(path.endsWith("/outgoing_extracts/"), "got: " + path);
    }

    @Test
    void filesPath_rejectsUnnormalizedDomain() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.filesPath(gcpDev(), "Home Lending", "msp", "loan-master",
                        FileLifecycle.SRC));
    }

    // --- tableLocation -----------------------------------------------------

    @Test
    void tableLocation_dpc_delta_returnsObjectStorePath() {
        TableLocation loc = svc.tableLocation(dpcS3aDev(), LakeLayer.BRONZE, LakeFormat.DELTA,
                "home-lending", "msp", "loan-master", "loan-master-raw");
        TableLocation.ObjectStorePath osp = assertInstanceOf(
                TableLocation.ObjectStorePath.class, loc);
        assertEquals(
                "s3a://pulse-dpc-home-lending-dev-lake/home-lending/msp/loan-master/bronze/loan-master-raw/",
                osp.uri());
    }

    @Test
    void tableLocation_dpc_parquet_returnsObjectStorePath() {
        TableLocation loc = svc.tableLocation(dpcS3aDev(), LakeLayer.GOLD, LakeFormat.PARQUET,
                "home-lending", "msp", "loan-master", "fct-orders");
        TableLocation.ObjectStorePath osp = assertInstanceOf(
                TableLocation.ObjectStorePath.class, loc);
        assertTrue(osp.uri().endsWith("/gold/fct-orders/"), "got: " + osp.uri());
    }

    @Test
    void tableLocation_gcp_iceberg_external_returnsObjectStorePath() {
        TableLocation loc = svc.tableLocation(gcpDev(), LakeLayer.SILVER,
                LakeFormat.ICEBERG_EXTERNAL,
                "home-lending", "msp", "loan-master", "customer-master");
        TableLocation.ObjectStorePath osp = assertInstanceOf(
                TableLocation.ObjectStorePath.class, loc);
        assertEquals(
                "gs://pulse-home-lending-dev-lake/home-lending/msp/loan-master/silver/customer-master/",
                osp.uri());
    }

    @Test
    void tableLocation_gcp_iceberg_bq_managed_returnsObjectStorePath() {
        // BQ-managed Iceberg lives at our prefix; BQ owns below.
        TableLocation loc = svc.tableLocation(gcpDev(), LakeLayer.BRONZE,
                LakeFormat.ICEBERG_BQ_MANAGED,
                "home-lending", "msp", "loan-master", "loan-master-raw");
        assertInstanceOf(TableLocation.ObjectStorePath.class, loc);
    }

    @Test
    void tableLocation_gcp_gold_bq_native_returnsCatalogIdentifier() {
        TableLocation loc = svc.tableLocation(gcpDev(), LakeLayer.GOLD, LakeFormat.BQ_NATIVE,
                "home-lending", "msp", "loan-master", "fct-orders");
        TableLocation.CatalogIdentifier cat = assertInstanceOf(
                TableLocation.CatalogIdentifier.class, loc);
        assertEquals("pulse-home-lending-dev", cat.project());
        assertEquals("home_lending_gold", cat.dataset());
        assertEquals("fct_orders", cat.table());
        assertEquals("pulse-home-lending-dev.home_lending_gold.fct_orders", cat.fullyQualified());
    }

    @Test
    void tableLocation_bqNative_throwsWhenGcpProjectMissing() {
        StorageBackend sb = gcpDev();
        sb.setGcpProject(null);
        assertThrows(IllegalStateException.class,
                () -> svc.tableLocation(sb, LakeLayer.GOLD, LakeFormat.BQ_NATIVE,
                        "home-lending", "msp", "loan-master", "fct-orders"));
    }

    // --- quarantinePath / checkpointPath / gxDocsPath ---------------------

    @Test
    void quarantinePath_includesUnderscoreQuarantineAndDs() {
        String path = svc.quarantinePath(gcpDev(), "home-lending", "msp", "loan-master",
                "loan-master-raw", "2026-04-25");
        assertEquals(
                "gs://pulse-home-lending-dev-lake/home-lending/msp/loan-master/_quarantine/loan-master-raw/2026-04-25/",
                path);
    }

    @Test
    void checkpointPath_includesUnderscoreCheckpointsAndStreamName() {
        String path = svc.checkpointPath(dpcS3aDev(), "home-lending", "msp", "loan-master",
                "kafka-orders-stream");
        assertEquals(
                "s3a://pulse-dpc-home-lending-dev-lake/home-lending/msp/loan-master/_checkpoints/kafka-orders-stream/",
                path);
    }

    @Test
    void gxDocsPath_includesUnderscoreGxDocsAndCheckpointName() {
        String path = svc.gxDocsPath(gcpDev(), "home-lending", "msp", "loan-master",
                "bronze-silver-gate");
        assertEquals(
                "gs://pulse-home-lending-dev-lake/home-lending/msp/loan-master/_gx_docs/bronze-silver-gate/",
                path);
    }

    // --- dbtSchemaName ----------------------------------------------------

    @Test
    void dbtSchemaName_snakeCaseFromKebab() {
        // Tenant + domain + layer → snake_case dataset name suitable for
        // BQ + dbt.
        assertEquals("home_lending_acme_bronze",
                svc.dbtSchemaName("home-lending", "acme", LakeLayer.BRONZE));
    }
}
