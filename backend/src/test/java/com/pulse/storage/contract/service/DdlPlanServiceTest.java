package com.pulse.storage.contract.service;

import com.pulse.runtime.TestRuntimeAuthorityFactory;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.service.DdlPlanService.DdlStatement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DdlPlanServiceTest {

    @Mock
    private RuntimeAuthorityService runtimeAuthorityService;

    @InjectMocks
    private DdlPlanService ddlPlanService;

    // ------------------------------------------------------------------ helpers

    private TableContract buildContract(String id, String schemaName, String catalogTableName,
                                         String catalogKind, String relativeStoragePath,
                                         String layer) {
        TableContract contract = new TableContract();
        contract.setId(id);
        contract.setSchemaName(schemaName);
        contract.setCatalogTableName(catalogTableName);
        contract.setCatalogKind(catalogKind);
        contract.setRelativeStoragePath(relativeStoragePath);
        contract.setLayer(layer);
        contract.setTableName(catalogTableName);
        contract.setTableFormat("parquet");
        contract.setPipelineId("pipe-001");
        contract.setVersionId("ver-001");
        contract.setProducingInstanceId("inst-001");
        contract.setOutputPortName("main_output");
        contract.setDomainId("domain-001");
        contract.setDomainSlug("finance");
        contract.setTableSlug(catalogTableName);
        contract.setLogicalTableId("ver-001/inst-001/main_output");
        contract.setTableRole("bronze_raw");
        contract.setWriteMode("append");
        contract.setDdlStrategy("create_if_not_exists");
        contract.setWriterOwner("inst-001");
        contract.setContractVersion(1);
        contract.setStatus("active");
        return contract;
    }

    // ------------------------------------------------------------------ HIVE

    @Test
    @DisplayName("generateDdlPlan: HIVE catalog produces schema and table statements")
    void generateDdlPlan_hiveCatalog_producesSchemaAndTable() {
        TableContract contract = buildContract(
                "tc-1", "finance_bronze", "salesforce_accounts",
                "HIVE", "finance/bronze/salesforce/salesforce_accounts/", "bronze");

        List<DdlStatement> plan = ddlPlanService.generateDdlPlan(
                List.of(contract), "DPC_PULSE", Map.of());

        assertEquals(2, plan.size());

        DdlStatement schemaStmt = plan.get(0);
        assertEquals("schema", schemaStmt.phase());
        assertEquals("HIVE_JDBC", schemaStmt.executor());
        assertEquals("HIVE", schemaStmt.dialect());
        assertTrue(schemaStmt.body().contains("CREATE DATABASE IF NOT EXISTS finance_bronze"));
        assertEquals("create_if_not_exists", schemaStmt.idempotencyMode());
        assertNotNull(schemaStmt.sha256());

        DdlStatement tableStmt = plan.get(1);
        assertEquals("table", tableStmt.phase());
        assertEquals("HIVE_JDBC", tableStmt.executor());
        assertEquals("HIVE", tableStmt.dialect());
        assertTrue(tableStmt.body().contains("finance_bronze.salesforce_accounts"));
        assertTrue(tableStmt.body().contains("STORED AS PARQUET"));
        assertTrue(tableStmt.body().contains(contract.getRelativeStoragePath()));
    }

    // ------------------------------------------------------------------ BIGQUERY_NATIVE

    @Test
    @DisplayName("generateDdlPlan: BIGQUERY_NATIVE produces schema and table statements")
    void generateDdlPlan_bigQueryNative_producesSchemaAndTable() {
        TableContract contract = buildContract(
                "tc-2", "finance_gold", "serve_accounts",
                "BIGQUERY_NATIVE", "finance/gold/serve_accounts/", "gold");

        List<DdlStatement> plan = ddlPlanService.generateDdlPlan(
                List.of(contract), "GCP_PULSE", Map.of());

        assertEquals(2, plan.size());

        DdlStatement schemaStmt = plan.get(0);
        assertEquals("schema", schemaStmt.phase());
        assertEquals("BIGQUERY_SQL", schemaStmt.executor());
        assertEquals("BIGQUERY", schemaStmt.dialect());
        assertTrue(schemaStmt.body().contains("CREATE SCHEMA IF NOT EXISTS"));
        assertTrue(schemaStmt.body().contains("finance_gold"));

        DdlStatement tableStmt = plan.get(1);
        assertEquals("table", tableStmt.phase());
        assertEquals("BIGQUERY_SQL", tableStmt.executor());
        assertEquals("BIGQUERY", tableStmt.dialect());
        assertTrue(tableStmt.body().contains("CREATE TABLE IF NOT EXISTS"));
        assertTrue(tableStmt.body().contains("finance_gold"));
        assertTrue(tableStmt.body().contains("serve_accounts"));
    }

    // ------------------------------------------------------------------ BIGQUERY_MANAGED_ICEBERG

    @Test
    @DisplayName("generateDdlPlan: BIGQUERY_MANAGED_ICEBERG produces schema and BigLake Iceberg table")
    void generateDdlPlan_bigLakeIceberg_producesSchemaAndTable() {
        TableContract contract = buildContract(
                "tc-3", "finance_bronze", "salesforce_leads",
                "BIGQUERY_MANAGED_ICEBERG", "finance/bronze/salesforce/salesforce_leads/", "bronze");

        List<DdlStatement> plan = ddlPlanService.generateDdlPlan(
                List.of(contract), "GCP_PULSE", Map.of());

        assertEquals(2, plan.size());

        DdlStatement schemaStmt = plan.get(0);
        assertEquals("schema", schemaStmt.phase());
        assertEquals("BIGQUERY_SQL", schemaStmt.executor());
        assertTrue(schemaStmt.body().contains("finance_bronze"));

        DdlStatement tableStmt = plan.get(1);
        assertEquals("table", tableStmt.phase());
        assertEquals("BIGQUERY_SQL", tableStmt.executor());
        assertTrue(tableStmt.body().contains("WITH CONNECTION `${biglake_connection}`"));
        assertTrue(tableStmt.body().contains("file_format = 'PARQUET'"));
        assertTrue(tableStmt.body().contains("table_format = 'ICEBERG'"));
        assertTrue(tableStmt.body().contains(contract.getRelativeStoragePath()));
    }

    // ------------------------------------------------------------------ NONE catalog

    @Test
    @DisplayName("generateDdlPlan: NONE catalogKind skips contract entirely")
    void generateDdlPlan_noneCatalog_skipsContract() {
        TableContract contract = buildContract(
                "tc-4", "finance_bronze", "temp_staging",
                "NONE", "finance/staging/temp/", "bronze");

        List<DdlStatement> plan = ddlPlanService.generateDdlPlan(
                List.of(contract), "GCP_PULSE", Map.of());

        assertTrue(plan.isEmpty());
    }

    // ------------------------------------------------------------------ deduplication

    @Test
    @DisplayName("generateDdlPlan: deduplicates schema creation for same schemaName")
    void generateDdlPlan_deduplicatesSchemaCreation() {
        TableContract contract1 = buildContract(
                "tc-5", "finance_bronze", "salesforce_accounts",
                "HIVE", "finance/bronze/salesforce/salesforce_accounts/", "bronze");
        TableContract contract2 = buildContract(
                "tc-6", "finance_bronze", "salesforce_orders",
                "HIVE", "finance/bronze/salesforce/salesforce_orders/", "bronze");

        List<DdlStatement> plan = ddlPlanService.generateDdlPlan(
                List.of(contract1, contract2), "DPC_PULSE", Map.of());

        assertEquals(3, plan.size());

        long schemaCount = plan.stream()
                .filter(s -> "schema".equals(s.phase()))
                .count();
        assertEquals(1, schemaCount, "Schema creation should be deduplicated");

        long tableCount = plan.stream()
                .filter(s -> "table".equals(s.phase()))
                .count();
        assertEquals(2, tableCount);
    }

    // ------------------------------------------------------------------ SHA-256 stability

    @Test
    @DisplayName("generateDdlPlan: each statement has a valid non-empty SHA-256 hash")
    void generateDdlPlan_statementsHaveValidHashes() {
        TableContract contract = buildContract(
                "tc-7", "finance_silver", "curated_accounts",
                "HIVE", "finance/silver/curated_accounts/", "silver");

        List<DdlStatement> plan = ddlPlanService.generateDdlPlan(
                List.of(contract), "DPC_PULSE", Map.of());

        for (DdlStatement stmt : plan) {
            assertNotNull(stmt.sha256());
            assertFalse(stmt.sha256().isBlank());
            assertEquals(64, stmt.sha256().length(), "SHA-256 hex should be 64 chars");
        }
    }

    // ------------------------------------------------------------------ null catalogKind

    @Test
    @DisplayName("generateDdlPlan: null catalogKind skips contract")
    void generateDdlPlan_nullCatalogKind_skipsContract() {
        TableContract contract = buildContract(
                "tc-8", "finance_bronze", "temp_table",
                null, "finance/staging/temp/", "bronze");
        contract.setCatalogKind(null);

        List<DdlStatement> plan = ddlPlanService.generateDdlPlan(
                List.of(contract), "GCP_PULSE", Map.of());

        assertTrue(plan.isEmpty());
    }
}
