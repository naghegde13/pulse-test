package com.pulse.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL-text test for V96 storage backends data model. Same pattern as
 * V92ParamsSchemaAuditTest / V93ParamsSchemaPhaseBTest / V95DestinationBindingTest.
 *
 * <p>Asserts the new table exists, all the constraints we depend on
 * for application-layer validation are codified at the database layer,
 * the seed loops over tenants × envs × backends, and the gold-on-GCP
 * rule (locked spec section 5) is enforced as a CHECK.
 */
class V96StorageBackendsTest {

    private static final Path V96 = Path.of(
            "src/main/resources/db/migration/V96__storage_backends_and_pipeline_storage_columns.sql");

    @Test
    void v96CreatesStorageBackendsTable() throws Exception {
        String sql = Files.readString(V96);
        assertTrue(sql.contains("CREATE TABLE storage_backends"),
                "V96 must create storage_backends table");
    }

    @Test
    void v96StorageBackendsHasUniquePerTenantEnvBackend() throws Exception {
        String sql = Files.readString(V96);
        assertTrue(sql.contains("UNIQUE (tenant_id, environment, backend)"),
                "V96 must enforce UNIQUE(tenant_id, environment, backend)");
    }

    @Test
    void v96EnforcesEnvironmentEnumAtDb() throws Exception {
        String sql = Files.readString(V96);
        assertTrue(sql.contains("CHECK (environment IN ('dev','integration','uat','prod'))"),
                "V96 must enforce environment enum at DB layer");
    }

    @Test
    void v96EnforcesBackendEnumAtDb() throws Exception {
        String sql = Files.readString(V96);
        assertTrue(sql.contains("CHECK (backend IN ('DPC','GCP'))"),
                "V96 must enforce backend enum at DB layer");
    }

    @Test
    void v96EnforcesProvisioningStatusEnum() throws Exception {
        String sql = Files.readString(V96);
        assertTrue(sql.contains("CHECK (provisioning_status IN "
                        + "('pending','validated','failed','disabled'))"),
                "V96 must enforce provisioning_status enum");
    }

    @Test
    void v96EnforcesBackendSpecificColumnPopulation() throws Exception {
        String sql = Files.readString(V96);
        assertTrue(sql.contains("backend = 'GCP'")
                        && sql.contains("backend = 'DPC'")
                        && sql.contains("dpc_scheme IN ('s3a','hdfs')"),
                "V96 must enforce GCP rows have gcp_project + DPC rows have dpc_scheme/cluster");
    }

    @Test
    void v96AddsDefaultStorageBackendToPipelines() throws Exception {
        String sql = Files.readString(V96);
        assertTrue(sql.contains("ALTER TABLE pipelines")
                        && sql.contains("default_storage_backend"),
                "V96 must add default_storage_backend to pipelines");
    }

    @Test
    void v96AddsStorageColumnsToSubPipelineInstances() throws Exception {
        String sql = Files.readString(V96);
        for (String col : List.of("storage_backend", "lake_layer", "lake_format")) {
            assertTrue(sql.contains("ADD COLUMN " + col),
                    "V96 must add column " + col + " to sub_pipeline_instances");
        }
    }

    @Test
    void v96EnforcesLakeLayerEnum() throws Exception {
        String sql = Files.readString(V96);
        assertTrue(sql.contains("lake_layer IN ('bronze','silver','gold')"),
                "V96 must enforce lake_layer enum");
    }

    @Test
    void v96EnforcesLakeFormatEnum() throws Exception {
        String sql = Files.readString(V96);
        for (String fmt : List.of("'delta'", "'iceberg_external'",
                "'iceberg_bq_managed'", "'bq_native'", "'parquet'")) {
            assertTrue(sql.contains(fmt),
                    "V96 lake_format CHECK must include " + fmt);
        }
    }

    @Test
    void v96EnforcesGoldOnGcpRule() throws Exception {
        String sql = Files.readString(V96);
        // Locked rule from spec section 5: storage_backend=GCP AND
        // lake_layer=gold ⇒ lake_format='bq_native'.
        assertTrue(sql.contains("storage_backend = 'GCP'")
                        && sql.contains("lake_layer = 'gold'")
                        && sql.contains("lake_format <> 'bq_native'"),
                "V96 must enforce gold-on-GCP rule (lake_format must be bq_native)");
    }

    @Test
    void v96SeedsStorageBackendsForAllTenantsEnvsBackends() throws Exception {
        String sql = Files.readString(V96);
        // Loop over active tenants × 4 envs × {GCP, DPC} = 8 rows / tenant.
        assertTrue(sql.contains("FOR t IN SELECT id, slug FROM tenants"),
                "V96 seed must iterate active tenants");
        assertTrue(sql.contains("ARRAY['dev','integration','uat','prod']"),
                "V96 seed must iterate 4 envs");
        assertTrue(sql.contains("INSERT INTO storage_backends")
                        && sql.split("INSERT INTO storage_backends").length >= 3,
                "V96 seed must insert both GCP and DPC rows per (tenant, env)");
    }

    @Test
    void v96SeedMarksDevValidatedAndOtherEnvsPending() throws Exception {
        String sql = Files.readString(V96);
        assertTrue(sql.contains("CASE WHEN is_dev THEN 'validated' ELSE 'pending' END"),
                "V96 seed must mark dev rows validated and non-dev rows pending");
    }

    @Test
    void v96CreatesLookupIndex() throws Exception {
        String sql = Files.readString(V96);
        assertTrue(sql.contains("CREATE INDEX idx_storage_backends_lookup")
                        && sql.contains("(tenant_id, environment, backend)"),
                "V96 must create the (tenant_id, environment, backend) lookup index");
    }
}
