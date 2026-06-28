package com.pulse.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.sor.service.SchemaDiscoveryService;
import com.pulse.sor.service.SchemaDiscoveryService.DiscoveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * PKT-0026: Chat tool tests for derive_dataset_schema and create_dataset_from_discovery.
 */
@ExtendWith(MockitoExtension.class)
class SchemaDiscoveryChatToolTest {

    @Mock private SystemOfRecordRepository sorRepo;
    @Mock private ConnectorInstanceRepository ciRepo;
    @Mock private DomainRepository domainRepo;
    @Mock private ConnectorDefinitionRepository connDefRepo;
    @Mock private CredentialProfileRepository credRepo;
    @Mock private DatasetRepository datasetRepo;
    @Mock private com.pulse.blueprint.repository.BlueprintRepository blueprintRepo;
    @Mock private com.pulse.pipeline.repository.PipelineRepository pipelineRepo;
    @Mock private com.pulse.pipeline.service.CompositionService compositionService;
    @Mock private com.pulse.pipeline.service.DqReadinessService dqReadinessService;
    @Mock private com.pulse.pipeline.service.PipelineService pipelineService;
    @Mock private com.pulse.codegen.service.DbtAssetRegistryService dbtAssetRegistryService;
    @Mock private com.pulse.codegen.service.CodegenExampleService codegenExampleService;
    @Mock private com.pulse.storage.repository.StorageBackendRepository storageBackendRepo;
    @Mock private com.pulse.pipeline.repository.SubPipelineInstanceRepository instanceRepo;
    @Mock private ChatService chatService;
    @Mock private com.pulse.command.service.PlanService planService;
    @Mock private com.pulse.pipeline.service.DqExpectationService dqExpectationService;
    @Mock private ChatReadToolHandler readToolHandler;
    @Mock private SchemaDiscoveryService schemaDiscoveryService;
    @Mock private ChatValidationToolService validationToolService;

    private ChatToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ChatToolExecutor(
                sorRepo, ciRepo, domainRepo, connDefRepo, credRepo, datasetRepo,
                blueprintRepo, pipelineRepo, compositionService, dqReadinessService,
                new ObjectMapper(), pipelineService, dbtAssetRegistryService,
                codegenExampleService, storageBackendRepo, instanceRepo, chatService,
                new com.pulse.auth.policy.AuthorizationPolicyService(),
                new com.pulse.auth.policy.ActorResolverService(),
                planService, dqExpectationService, readToolHandler,
                schemaDiscoveryService, validationToolService
        );
    }

    /** Set up connector instance → SOR → tenant binding for tenant isolation checks. */
    private void mockConnectorTenantBinding(String ciId, String sorId, String tenantId) {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(sorId);
        when(ciRepo.findById(ciId)).thenReturn(Optional.of(ci));

        SystemOfRecord sor = new SystemOfRecord();
        sor.setTenantId(tenantId);
        sor.setName("test_loanmaster");
        sor.setDomainName("Lending");
        when(sorRepo.findById(sorId)).thenReturn(Optional.of(sor));
    }

    @Test
    void deriveDatasetSchema_tableDiscovery_returnsFormattedResult() {
        mockConnectorTenantBinding("ci-001", "sor-001", "acme");

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("name", "loan_id", "type", "VARCHAR", "nullable", false, "pii", false));
        fields.add(Map.of("name", "ssn", "type", "VARCHAR", "nullable", true, "pii", true));
        var result = new DiscoveryResult(fields, "PII", "TABLE_DISCOVERY",
                Map.of("method", "TABLE_DISCOVERY", "tableName", "Loan_Master"), List.of());

        when(schemaDiscoveryService.discoverFromTable("ci-001", "Loan_Master", "dev"))
                .thenReturn(result);

        Map<String, Object> args = new HashMap<>();
        args.put("connector_instance_id", "ci-001");
        args.put("source_type", "table");
        args.put("table_name", "Loan_Master");
        args.put("environment", "dev");

        String output = executor.execute("derive_dataset_schema", args, "acme");

        assertTrue(output.contains("Schema Discovery Result"));
        assertTrue(output.contains("TABLE_DISCOVERY"));
        assertTrue(output.contains("PII"));
        assertTrue(output.contains("loan_id"));
        assertTrue(output.contains("ssn"));
        assertTrue(output.contains("create_dataset_from_discovery"));
    }

    @Test
    void deriveDatasetSchema_credentialBlocked_returnsBlockedMessage() {
        mockConnectorTenantBinding("ci-001", "sor-001", "acme");

        when(schemaDiscoveryService.discoverFromTable("ci-001", "Loan_Master", "dev"))
                .thenThrow(new IllegalStateException("Credentials not validated"));

        Map<String, Object> args = new HashMap<>();
        args.put("connector_instance_id", "ci-001");
        args.put("source_type", "table");
        args.put("table_name", "Loan_Master");
        args.put("environment", "dev");

        String output = executor.execute("derive_dataset_schema", args, "acme");

        assertTrue(output.contains("blocked"));
        assertTrue(output.contains("Credentials not validated"));
    }

    @Test
    void deriveDatasetSchema_missingParams_returnsError() {
        mockConnectorTenantBinding("ci-001", "sor-001", "acme");

        Map<String, Object> args = new HashMap<>();
        args.put("connector_instance_id", "ci-001");
        args.put("source_type", "table");
        // No table_name

        String output = executor.execute("derive_dataset_schema", args, "acme");
        assertTrue(output.contains("missing required parameters"));
    }

    @Test
    void deriveDatasetSchema_missingSourceType_returnsError() {
        Map<String, Object> args = new HashMap<>();
        args.put("connector_instance_id", "ci-001");
        // No source_type

        String output = executor.execute("derive_dataset_schema", args, "acme");
        assertTrue(output.contains("required"));
    }

    @Test
    void createDatasetFromDiscovery_success_persistsDataset() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId("sor-001");
        when(ciRepo.findById("ci-001")).thenReturn(Optional.of(ci));

        SystemOfRecord sor = new SystemOfRecord();
        sor.setTenantId("acme");
        sor.setName("test_loanmaster");
        sor.setDomainName("Lending");
        when(sorRepo.findById("sor-001")).thenReturn(Optional.of(sor));

        when(datasetRepo.save(any(Dataset.class))).thenAnswer(inv -> {
            Dataset ds = inv.getArgument(0);
            ds.setId("ds-new-001");
            return ds;
        });

        Map<String, Object> args = new HashMap<>();
        args.put("connector_instance_id", "ci-001");
        args.put("name", "Loan_Master");
        args.put("description", "Loan master dataset");
        args.put("fields", List.of(
                Map.of("name", "loan_id", "type", "VARCHAR"),
                Map.of("name", "ssn", "type", "VARCHAR", "pii", true)
        ));
        args.put("classification", "PII");
        args.put("discovery_method", "TABLE_DISCOVERY");
        args.put("discovery_proof", Map.of("method", "TABLE_DISCOVERY", "tableName", "Loan_Master"));
        args.put("time_grain", "DAILY");
        args.put("asof_column_name", "as_of_date");
        args.put("current_asof", "2026-05-24");

        String output = executor.execute("create_dataset_from_discovery", args, "acme");

        assertTrue(output.contains("Loan_Master"));
        assertTrue(output.contains("created from discovery"));
        assertTrue(output.contains("PII"));
        assertTrue(output.contains("TABLE_DISCOVERY"));
        assertTrue(output.contains("as_of_date"));
        assertTrue(output.contains("2026-05-24"));
    }

    @Test
    void createDatasetFromDiscovery_missingName_returnsError() {
        Map<String, Object> args = new HashMap<>();
        args.put("connector_instance_id", "ci-001");
        // No name
        args.put("fields", List.of(Map.of("name", "id")));

        String output = executor.execute("create_dataset_from_discovery", args, "acme");
        assertTrue(output.contains("required"));
    }

    @Test
    void createDatasetFromDiscovery_missingFields_returnsError() {
        Map<String, Object> args = new HashMap<>();
        args.put("connector_instance_id", "ci-001");
        args.put("name", "test");
        // No fields

        String output = executor.execute("create_dataset_from_discovery", args, "acme");
        assertTrue(output.contains("required"));
    }

    @Test
    void deriveDatasetSchema_queryDiscovery_passesQueryToService() {
        mockConnectorTenantBinding("ci-001", "sor-001", "acme");

        List<Map<String, Object>> fields = List.of(
                Map.of("name", "loan_id", "type", "VARCHAR")
        );
        var result = new DiscoveryResult(fields, "INTERNAL", "QUERY_DISCOVERY",
                Map.of("method", "QUERY_DISCOVERY", "queryHash", "abc"), List.of());

        when(schemaDiscoveryService.discoverFromQuery("ci-001", "SELECT * FROM loans", "dev"))
                .thenReturn(result);

        Map<String, Object> args = new HashMap<>();
        args.put("connector_instance_id", "ci-001");
        args.put("source_type", "query");
        args.put("query", "SELECT * FROM loans");
        args.put("environment", "dev");

        String output = executor.execute("derive_dataset_schema", args, "acme");
        assertTrue(output.contains("QUERY_DISCOVERY"));
    }

    @Test
    void deriveDatasetSchema_sampleDiscovery_passesToService() {
        mockConnectorTenantBinding("ci-001", "sor-001", "acme");

        List<Map<String, Object>> fields = List.of(
                Map.of("name", "id", "type", "INTEGER")
        );
        var result = new DiscoveryResult(fields, "INTERNAL", "SAMPLE_UPLOAD",
                Map.of("method", "SAMPLE_UPLOAD", "sampleHash", "xyz"), List.of());

        when(schemaDiscoveryService.discoverFromSample("ci-001", "id,name\n1,test", "CSV"))
                .thenReturn(result);

        Map<String, Object> args = new HashMap<>();
        args.put("connector_instance_id", "ci-001");
        args.put("source_type", "sample");
        args.put("sample_data", "id,name\n1,test");
        args.put("sample_format", "CSV");

        String output = executor.execute("derive_dataset_schema", args, "acme");
        assertTrue(output.contains("SAMPLE_UPLOAD"));
    }
}
