package com.pulse.storage.contract.service;

import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.runtime.TestRuntimeAuthorityFactory;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.repository.TableContractRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TableContractServiceTest {

    @Mock
    private TableContractRepository tableContractRepository;

    @Mock
    private RuntimeAuthorityService runtimeAuthorityService;

    @InjectMocks
    private TableContractService tableContractService;

    // ------------------------------------------------------------------ helpers

    private SubPipelineInstance buildInstance(String id, String name, String lakeLayer,
                                              String lakeFormat, String blueprintKey,
                                              List<Map<String, Object>> inputDatasets) {
        SubPipelineInstance instance = new SubPipelineInstance();
        instance.setId(id);
        instance.setName(name);
        instance.setLakeLayer(lakeLayer);
        instance.setLakeFormat(lakeFormat);
        instance.setBlueprintKey(blueprintKey);
        instance.setBlueprintId("bp-001");
        instance.setBlueprintVersion("1.0.0");
        instance.setExecutionOrder(1);
        instance.setPipelineId("pipe-001");
        instance.setInputDatasets(inputDatasets);
        return instance;
    }

    private void mockGcpAuthority() {
        RuntimeAuthorityService gcpService = TestRuntimeAuthorityFactory.gcpPulse();
        when(runtimeAuthorityService.getAuthority()).thenReturn(gcpService.getAuthority());
    }

    // ------------------------------------------------------------------ bronze

    @Test
    @DisplayName("generateContracts: bronze layer sets correct naming convention")
    void generateContracts_bronzeLayer_setsCorrectNamingConvention() {
        mockGcpAuthority();
        when(tableContractRepository.findByVersionIdAndProducingInstanceIdAndStatus(
                any(), any(), eq("active"))).thenReturn(List.of());
        when(tableContractRepository.save(any(TableContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<Map<String, Object>> inputs = List.of(
                Map.of("sorSlug", "salesforce", "datasetSlug", "accounts",
                        "sorId", "sor-001", "datasetId", "ds-001"));
        SubPipelineInstance instance = buildInstance(
                "inst-1", "Ingest Accounts", "bronze", "parquet",
                "ingestion_gcs_to_raw", inputs);

        List<TableContract> result = tableContractService.generateContracts(
                "pipe-001", "ver-001", List.of(instance),
                List.of(), "domain-001", "finance");

        assertEquals(1, result.size());
        TableContract contract = result.get(0);
        assertEquals("bronze", contract.getLayer());
        assertEquals("bronze_raw", contract.getTableRole());
        assertEquals("salesforce_accounts", contract.getTableName());
        assertEquals("finance/bronze/salesforce/salesforce_accounts/", contract.getRelativeStoragePath());
        assertEquals("finance_bronze", contract.getSchemaName());
        assertEquals("active", contract.getStatus());
        assertEquals("salesforce", contract.getSourceSorSlug());
        assertEquals("accounts", contract.getSourceDatasetSlug());
    }

    // ------------------------------------------------------------------ silver

    @Test
    @DisplayName("generateContracts: silver layer sets correct naming convention")
    void generateContracts_silverLayer_setsCorrectNamingConvention() {
        mockGcpAuthority();
        when(tableContractRepository.findByVersionIdAndProducingInstanceIdAndStatus(
                any(), any(), eq("active"))).thenReturn(List.of());
        when(tableContractRepository.save(any(TableContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SubPipelineInstance instance = buildInstance(
                "inst-2", "Curate Accounts", "silver", "parquet",
                "transform_dedup", null);

        List<TableContract> result = tableContractService.generateContracts(
                "pipe-001", "ver-001", List.of(instance),
                List.of(), "domain-001", "finance");

        assertEquals(1, result.size());
        TableContract contract = result.get(0);
        assertEquals("silver", contract.getLayer());
        assertEquals("silver_curated", contract.getTableRole());
        assertEquals("curate_accounts", contract.getTableName());
        assertEquals("finance/silver/curate_accounts/", contract.getRelativeStoragePath());
        assertEquals("finance_silver", contract.getSchemaName());
    }

    // ------------------------------------------------------------------ gold

    @Test
    @DisplayName("generateContracts: gold layer sets correct naming convention")
    void generateContracts_goldLayer_setsCorrectNamingConvention() {
        mockGcpAuthority();
        when(tableContractRepository.findByVersionIdAndProducingInstanceIdAndStatus(
                any(), any(), eq("active"))).thenReturn(List.of());
        when(tableContractRepository.save(any(TableContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SubPipelineInstance instance = buildInstance(
                "inst-3", "Serve Accounts", "gold", "bq_native",
                "modeling_dim_table", null);

        List<TableContract> result = tableContractService.generateContracts(
                "pipe-001", "ver-001", List.of(instance),
                List.of(), "domain-001", "finance");

        assertEquals(1, result.size());
        TableContract contract = result.get(0);
        assertEquals("gold", contract.getLayer());
        assertEquals("gold_serving", contract.getTableRole());
        assertEquals("serve_accounts", contract.getTableName());
        assertEquals("finance/gold/serve_accounts/", contract.getRelativeStoragePath());
        assertEquals("finance_gold", contract.getSchemaName());
    }

    // ------------------------------------------------------------------ no layer

    @Test
    @DisplayName("generateContracts: null lakeLayer skips non-materializing instances")
    void generateContracts_noLakeLayer_skipsNonMaterializing() {
        SubPipelineInstance instance = buildInstance(
                "inst-4", "Sensor Check", null, null,
                "sensor_file", null);

        List<TableContract> result = tableContractService.generateContracts(
                "pipe-001", "ver-001", List.of(instance),
                List.of(), "domain-001", "finance");

        assertTrue(result.isEmpty());
        verify(tableContractRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ stale marking

    @Test
    @DisplayName("generateContracts: marks stale previous active contracts on regeneration")
    void generateContracts_marksStalePreviousContracts() {
        mockGcpAuthority();

        TableContract existingContract = new TableContract();
        existingContract.setId("existing-contract-1");
        existingContract.setStatus("active");
        existingContract.setVersionId("ver-001");
        existingContract.setProducingInstanceId("inst-5");

        when(tableContractRepository.findByVersionIdAndProducingInstanceIdAndStatus(
                "ver-001", "inst-5", "active"))
                .thenReturn(List.of(existingContract));
        when(tableContractRepository.save(any(TableContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SubPipelineInstance instance = buildInstance(
                "inst-5", "Ingest Orders", "bronze", "parquet",
                "ingestion_gcs_to_raw",
                List.of(Map.of("sorSlug", "erp", "datasetSlug", "orders",
                        "sorId", "sor-002", "datasetId", "ds-002")));

        tableContractService.generateContracts(
                "pipe-001", "ver-001", List.of(instance),
                List.of(), "domain-001", "finance");

        assertEquals("stale", existingContract.getStatus());
    }

    // ------------------------------------------------------------------ slugify

    @Test
    @DisplayName("slugify handles special characters correctly")
    void slugify_handlesSpecialCharacters() {
        assertEquals("hello_world", TableContractService.slugify("Hello World"));
        assertEquals("my_pipeline_v2", TableContractService.slugify("My Pipeline (v2)"));
        assertEquals("unnamed", TableContractService.slugify(null));
        assertEquals("unnamed", TableContractService.slugify(""));
        assertEquals("unnamed", TableContractService.slugify("   "));
        assertEquals("abc_123", TableContractService.slugify("abc---123"));
        assertEquals("test", TableContractService.slugify("test!!!"));
        assertEquals("a_b_c", TableContractService.slugify("a.b.c"));
    }

    // ------------------------------------------------------------------ catalog kind derivation

    @Test
    @DisplayName("generateContracts: GCP gold uses BIGQUERY_NATIVE catalogKind")
    void generateContracts_gcpGold_usesBigQueryNative() {
        mockGcpAuthority();
        when(tableContractRepository.findByVersionIdAndProducingInstanceIdAndStatus(
                any(), any(), eq("active"))).thenReturn(List.of());
        when(tableContractRepository.save(any(TableContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SubPipelineInstance instance = buildInstance(
                "inst-6", "Gold Table", "gold", "bq_native",
                "modeling_dim_table", null);

        List<TableContract> result = tableContractService.generateContracts(
                "pipe-001", "ver-001", List.of(instance),
                List.of(), "domain-001", "finance");

        assertEquals("BIGQUERY_NATIVE", result.get(0).getCatalogKind());
    }

    @Test
    @DisplayName("generateContracts: GCP bronze uses BIGQUERY_MANAGED_ICEBERG catalogKind")
    void generateContracts_gcpBronze_usesBigQueryManagedIceberg() {
        mockGcpAuthority();
        when(tableContractRepository.findByVersionIdAndProducingInstanceIdAndStatus(
                any(), any(), eq("active"))).thenReturn(List.of());
        when(tableContractRepository.save(any(TableContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SubPipelineInstance instance = buildInstance(
                "inst-7", "Raw Ingest", "bronze", "parquet",
                "ingestion_gcs_to_raw",
                List.of(Map.of("sorSlug", "crm", "datasetSlug", "leads",
                        "sorId", "sor-003", "datasetId", "ds-003")));

        List<TableContract> result = tableContractService.generateContracts(
                "pipe-001", "ver-001", List.of(instance),
                List.of(), "domain-001", "finance");

        assertEquals("BIGQUERY_MANAGED_ICEBERG", result.get(0).getCatalogKind());
    }
}
