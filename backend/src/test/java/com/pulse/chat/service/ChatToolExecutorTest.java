package com.pulse.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.model.DbtAsset;
import com.pulse.codegen.service.DbtAssetRegistryService;
import com.pulse.command.model.Plan;
import com.pulse.command.model.PlanStatus;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.pipeline.service.DqReadinessService;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatToolExecutorTest {

    @Mock private SystemOfRecordRepository sorRepo;
    @Mock private ConnectorInstanceRepository ciRepo;
    @Mock private DomainRepository domainRepo;
    @Mock private ConnectorDefinitionRepository connDefRepo;
    @Mock private CredentialProfileRepository credRepo;
    @Mock private DatasetRepository datasetRepo;
    @Mock private BlueprintRepository blueprintRepo;
    @Mock private PipelineRepository pipelineRepo;
    @Mock private CompositionService compositionService;
    @Mock private DqReadinessService dqReadinessService;
    @Mock private com.pulse.pipeline.service.PipelineService pipelineService;
    @Mock private DbtAssetRegistryService dbtAssetRegistryService;
    @Mock private com.pulse.codegen.service.CodegenExampleService codegenExampleService;
    @Mock private com.pulse.storage.repository.StorageBackendRepository storageBackendRepo;
    @Mock private com.pulse.pipeline.repository.SubPipelineInstanceRepository instanceRepo;
    @Mock private ChatService chatService;
    @Mock private com.pulse.command.service.PlanService planService;
    @Mock private com.pulse.pipeline.service.DqExpectationService dqExpectationService;
    @Mock private ChatReadToolHandler readToolHandler;
    @Mock private com.pulse.sor.service.SchemaDiscoveryService schemaDiscoveryService;
    @Mock private ChatValidationToolService validationToolService;

    private ChatToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ChatToolExecutor(
                sorRepo,
                ciRepo,
                domainRepo,
                connDefRepo,
                credRepo,
                datasetRepo,
                blueprintRepo,
                pipelineRepo,
                compositionService,
                dqReadinessService,
                new ObjectMapper(),
                pipelineService,
                dbtAssetRegistryService,
                codegenExampleService,
                storageBackendRepo,
                instanceRepo,
                chatService,
                new com.pulse.auth.policy.AuthorizationPolicyService(),
                new com.pulse.auth.policy.ActorResolverService(),
                planService,
                dqExpectationService,
                readToolHandler,
                schemaDiscoveryService,
                validationToolService
        );
    }

    @Test
    void executeFindDbtReuseCandidatePassesPlanningContextAndFormatsDecision() {
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-1");
        domain.setName("Finance");
        when(domainRepo.findByTenantIdAndName("tenant-1", "Finance")).thenReturn(Optional.of(domain));

        DbtAsset asset = new DbtAsset();
        asset.setId("asset-1");
        asset.setAssetName("employee_status_snapshot");
        asset.setAssetType("model");
        asset.setPath("models/shared/employee_status_snapshot.sql");
        asset.setBusinessConcept("employee status");
        asset.setGrain("employee");
        asset.setAccessLevel("internal");

        Map<String, Object> planningContext = new LinkedHashMap<>();
        planningContext.put("semantic_terms", List.of("employee", "status", "history"));
        planningContext.put("contract_keys", List.of("employee_id", "effective_date"));

        var match = new DbtAssetRegistryService.ReuseMatch(
                asset,
                "reuse_wrapper",
                14,
                List.of("Exact business concept match.", "Semantic metadata strongly overlaps with the requested plan context."),
                List.of("Requested reference_only was downgraded to reuse_wrapper because compatibility is partial."),
                Map.of(
                        "businessConcept", "exact",
                        "accessLevel", "exact",
                        "grain", "exact",
                        "schemaSignature", "partial",
                        "semanticOverlap", 0.67d,
                        "referenceSafe", false
                )
        );

        when(dbtAssetRegistryService.findReuseCandidate(
                eq("domain-1"),
                eq("employee status"),
                eq("model"),
                eq("employee"),
                eq("internal"),
                eq("employee id status effective date"),
                eq("reference_only"),
                anyMap()
        )).thenReturn(Optional.of(match));

        String result = executor.execute("find_dbt_reuse_candidate", Map.of(
                "domain_name", "Finance",
                "business_concept", "employee status",
                "asset_type", "model",
                "grain", "employee",
                "access_level", "internal",
                "schema_signature", "employee id status effective date",
                "emit_strategy", "reference_only",
                "planning_context", planningContext
        ), "tenant-1");

        verify(dbtAssetRegistryService).findReuseCandidate(
                "domain-1",
                "employee status",
                "model",
                "employee",
                "internal",
                "employee id status effective date",
                "reference_only",
                planningContext
        );
        assertTrue(result.contains("Decision: reuse_wrapper"));
        assertTrue(result.contains("Exact business concept match."));
        assertTrue(result.contains("Warnings:"));
        assertTrue(result.contains("Reference-safe direct reuse: no"));
        assertTrue(result.contains("Semantic overlap: 0.67"));
    }

    @Test
    void executeFindDbtReuseCandidateExplainsGenerateFallback() {
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-1");
        domain.setName("Finance");
        when(domainRepo.findByTenantIdAndName("tenant-1", "Finance")).thenReturn(Optional.of(domain));
        when(dbtAssetRegistryService.findReuseCandidate(
                eq("domain-1"),
                eq("employee status"),
                eq("model"),
                eq("employee"),
                eq("internal"),
                eq("employee id status effective date"),
                eq("generate"),
                anyMap()
        )).thenReturn(Optional.empty());

        String result = executor.execute("find_dbt_reuse_candidate", Map.of(
                "domain_name", "Finance",
                "business_concept", "employee status",
                "asset_type", "model",
                "grain", "employee",
                "access_level", "internal",
                "schema_signature", "employee id status effective date",
                "emit_strategy", "generate",
                "planning_context", Map.of("contract_keys", List.of("employee_id", "effective_date"))
        ), "tenant-1");

        assertTrue(result.contains("Decision: generate"));
        assertTrue(result.contains("Reuse was evaluated before generation."));
        assertTrue(result.contains("Planning context was considered"));
    }

    @Test
    void planCreatePipeline_createsPreviewPlanWithoutMutatingPipelineState() {
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-1");
        domain.setName("Finance");
        when(domainRepo.findByTenantIdAndName("tenant-1", "Finance")).thenReturn(Optional.of(domain));

        Plan plan = new Plan();
        plan.setId("plan-1");
        plan.setStatus(PlanStatus.PREVIEW);
        when(planService.createForSession(eq("tenant-1"), isNull(), eq("session-1"),
                eq("agent"), eq("Create pipeline: Loan Master"), anyList()))
                .thenReturn(plan);
        when(pipelineService.resolveDefaultStorageBackendForCreate("DPC")).thenReturn("DPC");

        String result = executor.execute("plan_create_pipeline", Map.of(
                "_session_id", "session-1",
                "name", "Loan Master",
                "description", "Build the pipeline",
                "domain_name", "Finance",
                "default_storage_backend", "DPC"
        ), "tenant-1");

        assertTrue(result.contains("Created pipeline plan plan-1"));
        assertTrue(result.contains("[plan_id: plan-1]"));
        assertTrue(result.contains("[declared_draft_ref: draft:pipeline:1]"));
        assertTrue(result.contains("[preview_command: index=0 type=pipeline.create aggregate=draft:pipeline:1]"));
        verify(planService).createForSession(eq("tenant-1"), isNull(), eq("session-1"),
                eq("agent"), eq("Create pipeline: Loan Master"), anyList());
        verify(pipelineService, never()).create(eq("tenant-1"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void planCreatePipelineDerivesDefaultStorageBackendWhenOmitted() {
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-1");
        domain.setName("Finance");
        when(domainRepo.findByTenantIdAndName("tenant-1", "Finance")).thenReturn(Optional.of(domain));
        when(pipelineService.resolveDefaultStorageBackendForCreate(null)).thenReturn("GCP");

        Plan plan = new Plan();
        plan.setId("plan-1");
        plan.setStatus(PlanStatus.PREVIEW);
        when(planService.createForSession(eq("tenant-1"), isNull(), eq("session-1"),
                eq("agent"), eq("Create pipeline: Loan Master"), anyList()))
                .thenReturn(plan);

        String result = executor.execute("plan_create_pipeline", Map.of(
                "_session_id", "session-1",
                "name", "Loan Master",
                "description", "Build the pipeline",
                "domain_name", "Finance"
        ), "tenant-1");

        assertTrue(result.contains("Created pipeline plan plan-1"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.pulse.command.service.PlanService.PlannedCommand>> commandsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(planService).createForSession(eq("tenant-1"), isNull(), eq("session-1"),
                eq("agent"), eq("Create pipeline: Loan Master"), commandsCaptor.capture());
        assertEquals("GCP", commandsCaptor.getValue().get(0).payload().get("defaultStorageBackend"));
    }

    // ------------------------------------------------------------
    // Agent D redesign-v2 scenarios
    // ------------------------------------------------------------

    @Test
    void viewCodeExamplesReturnsExamples() {
        com.pulse.blueprint.model.Blueprint bp = buildBlueprint("FileIngestion", "active");
        bp.setArtifactTypes(List.of("pyspark_job"));
        when(blueprintRepo.findByBlueprintKey("FileIngestion")).thenReturn(Optional.of(bp));
        when(codegenExampleService.getExamplesForBlueprint("FileIngestion"))
                .thenReturn(List.of(
                        new com.pulse.codegen.service.CodegenExampleService.Example(
                                "file_ingestion_s3_csv", "python", "example A"),
                        new com.pulse.codegen.service.CodegenExampleService.Example(
                                "file_ingestion_sftp", "python", "example B")));

        String out = executor.execute("view_code_examples",
                Map.of("blueprint_key", "FileIngestion"), "tenant-1");
        assertTrue(out.contains("**Code examples for File Ingestion:**"));
        assertTrue(out.contains("example A"));
        assertTrue(out.contains("example B"));
        // Filename + language label give the LLM the context to pattern-match correctly.
        assertTrue(out.contains("file_ingestion_s3_csv"),
                "tool output must include the example filename");
        assertTrue(out.contains("(python)"),
                "tool output must include the language label");
    }

    @Test
    void viewCodeExamplesAnnotatesDbtBlueprint() {
        com.pulse.blueprint.model.Blueprint bp = buildBlueprint("BronzeToSilverCleaning", "active");
        bp.setArtifactTypes(List.of("dbt_model"));
        when(blueprintRepo.findByBlueprintKey("BronzeToSilverCleaning")).thenReturn(Optional.of(bp));
        when(codegenExampleService.getExamplesForBlueprint("BronzeToSilverCleaning"))
                .thenReturn(List.of(
                        new com.pulse.codegen.service.CodegenExampleService.Example(
                                "stg_cleaning_basic", "sql", "cleaning")));

        String out = executor.execute("view_code_examples",
                Map.of("blueprint_key", "BronzeToSilverCleaning"), "tenant-1");
        assertTrue(out.contains("(dbt)"));
    }

    @Test
    void viewCodeExamplesRedirectsDeprecatedBlueprint() {
        com.pulse.blueprint.model.Blueprint bp = buildBlueprint("OldOne", "deprecated");
        bp.setReplacementBlueprintKey("DQValidator");
        when(blueprintRepo.findByBlueprintKey("OldOne")).thenReturn(Optional.of(bp));

        String out = executor.execute("view_code_examples",
                Map.of("blueprint_key", "OldOne"), "tenant-1");
        assertTrue(out.contains("deprecated"));
        assertTrue(out.contains("Use 'DQValidator'"));
        org.mockito.Mockito.verify(codegenExampleService,
                org.mockito.Mockito.never()).getExamplesForBlueprint(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void viewCodeExamplesReturnsEmptyMessageWhenNoExamples() {
        com.pulse.blueprint.model.Blueprint bp = buildBlueprint("FileIngestion", "active");
        when(blueprintRepo.findByBlueprintKey("FileIngestion")).thenReturn(Optional.of(bp));
        when(codegenExampleService.getExamplesForBlueprint("FileIngestion"))
                .thenReturn(List.of());

        String out = executor.execute("view_code_examples",
                Map.of("blueprint_key", "FileIngestion"), "tenant-1");
        assertTrue(out.contains("No code examples indexed for 'FileIngestion'"));
    }

    @Test
    void listSinkTargetsReturnsEmptyMessageWhenNoTargets() {
        when(sorRepo.findTargetsByTenantId("tenant-1")).thenReturn(List.of());
        String out = executor.execute("list_sink_targets", Map.of(), "tenant-1");
        assertTrue(out.startsWith("No sink targets registered"));
        verify(sorRepo).findTargetsByTenantId("tenant-1");
    }

    @Test
    void listSinkTargetsReturnsTableWhenTargetsExist() {
        com.pulse.sor.model.SystemOfRecord sor = new com.pulse.sor.model.SystemOfRecord();
        sor.setId("01JSOR0TARGETREG0000001");
        sor.setName("Analytics Warehouse");
        sor.setDomainName("Capital Markets");
        when(sorRepo.findTargetsByTenantId("tenant-1")).thenReturn(List.of(sor));
        when(ciRepo.countBySorId(sor.getId())).thenReturn(3L);

        String out = executor.execute("list_sink_targets", Map.of(), "tenant-1");
        assertTrue(out.contains("Analytics Warehouse"));
        assertTrue(out.contains("Capital Markets"));
        assertTrue(out.contains("[internal_id: " + sor.getId() + "]"));
    }

    @Test
    void createSinkTargetSavesWithForcedMetadata() {
        Domain domain = new Domain();
        domain.setId("dom-1");
        domain.setTenantId("tenant-1");
        domain.setName("Analytics");
        when(domainRepo.findById("dom-1")).thenReturn(Optional.of(domain));
        when(sorRepo.save(org.mockito.ArgumentMatchers.any(com.pulse.sor.model.SystemOfRecord.class)))
                .thenAnswer(inv -> {
                    com.pulse.sor.model.SystemOfRecord saved = inv.getArgument(0);
                    saved.setId("sor-new");
                    return saved;
                });

        String out = executor.execute("create_sink_target",
                Map.of("name", "Analytics Warehouse", "domain_id", "dom-1"), "tenant-1");
        assertTrue(out.contains("**Target created:** Analytics Warehouse"));

        org.mockito.ArgumentCaptor<com.pulse.sor.model.SystemOfRecord> captor =
                org.mockito.ArgumentCaptor.forClass(com.pulse.sor.model.SystemOfRecord.class);
        verify(sorRepo).save(captor.capture());
        com.pulse.sor.model.SystemOfRecord saved = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(
                Map.of("registry_type", "TARGET"), saved.getMetadata());
        org.junit.jupiter.api.Assertions.assertEquals("01JUSER00000000000000000", saved.getOwnerId());
        org.junit.jupiter.api.Assertions.assertEquals("tenant-1", saved.getTenantId());
    }

    @Test
    void createSinkTargetRejectsMissingDomain() {
        String out = executor.execute("create_sink_target",
                Map.of("name", "Analytics", "domain_id", ""), "tenant-1");
        assertTrue(out.startsWith("Error:"));
        verify(sorRepo, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(com.pulse.sor.model.SystemOfRecord.class));
    }

    @Test
    void createSinkTargetRejectsUnknownDomain() {
        when(domainRepo.findById("no-such")).thenReturn(Optional.empty());
        String out = executor.execute("create_sink_target",
                Map.of("name", "Analytics", "domain_id", "no-such"), "tenant-1");
        assertTrue(out.contains("Domain no-such not found for tenant"));
        verify(sorRepo, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(com.pulse.sor.model.SystemOfRecord.class));
    }

    @Test
    void proposeAddInstanceRejectsDeprecatedBlueprint() {
        com.pulse.blueprint.model.Blueprint bp = buildBlueprint("OldDq", "deprecated");
        bp.setReplacementBlueprintKey("DQValidator");
        when(blueprintRepo.findByBlueprintKey("OldDq")).thenReturn(Optional.of(bp));
        com.pulse.pipeline.model.Pipeline pipeline = new com.pulse.pipeline.model.Pipeline();
        pipeline.setId("pipe-1");
        pipeline.setActiveVersionId("v-1");
        when(pipelineRepo.findById("pipe-1")).thenReturn(Optional.of(pipeline));

        String out = executor.execute("propose_add_instance",
                Map.of(
                        "pipeline_id", "pipe-1",
                        "blueprint_key", "OldDq",
                        "instance_name", "foo"
                ),
                "tenant-1");
        assertTrue(out.startsWith("Error: Blueprint 'OldDq' is deprecated"));
        assertTrue(out.contains("Use 'DQValidator'"));
    }

    // ------------------------------------------------------------
    // Phase 1 — chat-tool environment-canonicalization proofs
    // ------------------------------------------------------------

    @Test
    void createConnector_secretPersistGoesThroughAuthorizationPolicyOnAgentSurface() {
        // Phase 3 enforcement parity: when the agent's create_connector
        // tool persists credentials, the AGENT surface must hit the
        // same SECRET_METADATA policy gate as the UI controller. With a
        // restricted actor (TENANT_USER only), the chat-tool reply must
        // surface the deny reason rather than silently saving.
        //
        // We construct a custom executor with an actor resolver spy so
        // the resolver returns TENANT_USER for the AGENT surface; all
        // other dependencies reuse the test's @Mock fields.
        com.pulse.auth.policy.ActorResolverService restrictedResolver =
                org.mockito.Mockito.spy(new com.pulse.auth.policy.ActorResolverService());
        org.mockito.Mockito.doReturn(new com.pulse.auth.policy.CallerContext(
                "user-citizen", "tenant-1",
                java.util.Set.of(com.pulse.auth.policy.PulseRole.TENANT_USER),
                com.pulse.auth.policy.CallerSurface.AGENT))
                .when(restrictedResolver).resolve(
                        com.pulse.auth.policy.CallerSurface.AGENT, "tenant-1");

        ChatToolExecutor restrictedExecutor = new ChatToolExecutor(
                sorRepo, ciRepo, domainRepo, connDefRepo, credRepo, datasetRepo,
                blueprintRepo, pipelineRepo, compositionService, dqReadinessService,
                new ObjectMapper(), pipelineService, dbtAssetRegistryService,
                codegenExampleService, storageBackendRepo, instanceRepo, chatService,
                new com.pulse.auth.policy.AuthorizationPolicyService(),
                restrictedResolver,
                planService,
                dqExpectationService,
                readToolHandler,
                schemaDiscoveryService,
                validationToolService);

        com.pulse.sor.model.SystemOfRecord sor = new com.pulse.sor.model.SystemOfRecord();
        sor.setId("sor-1");
        sor.setName("MSP Loan Master");
        sor.setTenantId("tenant-1");
        when(sorRepo.findByTenantIdOrderByNameAsc("tenant-1")).thenReturn(java.util.List.of(sor));
        com.pulse.sor.model.ConnectorDefinition def = new com.pulse.sor.model.ConnectorDefinition();
        def.setId("conn-def-jdbc");
        def.setName("Postgres JDBC");
        when(connDefRepo.findAll()).thenReturn(java.util.List.of(def));
        // Phase 3 closeout: NO ciRepo.save stub — the policy gate must
        // fire before any repository save when credential persistence
        // is requested. Stubbing ciRepo.save here would mask the
        // pre-Phase-3-closeout regression where the connector was
        // saved before SECRET_METADATA was checked.

        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("sor_name", "MSP Loan Master");
        args.put("connector_name", "MSP Postgres");
        args.put("connector_type", "Postgres JDBC");
        args.put("credential_refs", java.util.Map.of(
                "username", "gcp-sm://projects/pulse-dev/secrets/x/versions/latest"));
        args.put("credentials_environment", "DEV");

        String reply = restrictedExecutor.execute("create_connector", args, "tenant-1");

        assertTrue(reply.startsWith("Agent action denied"),
                "AGENT-surface secret persistence must be denied for TENANT_USER, got: " + reply);
        assertTrue(reply.contains("missing_role"),
                "Reply must surface the policy deny reason, got: " + reply);
        // Phase 3 closeout: a denied SECRET_METADATA gate must leave NO
        // partial state — neither the credential row nor the connector
        // instance row may be persisted. Otherwise a denied request
        // creates an orphan ConnectorInstance the agent appears to "own"
        // but cannot actually use.
        org.mockito.Mockito.verify(credRepo, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(com.pulse.sor.model.CredentialProfile.class));
        org.mockito.Mockito.verify(ciRepo, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(com.pulse.sor.model.ConnectorInstance.class));
    }

    @Test
    void requestCredentialAttach_draftConnectorDoesNotNavigateBeforeApply() {
        String out = executor.execute("request_credential_attach",
                Map.of("connector_instance_id", "draft:connector:1", "environment", "DEV"),
                "tenant-1");

        assertTrue(out.contains("planned for draft:connector:1"));
        assertTrue(out.contains("only after apply_plan resolves"));
        org.junit.jupiter.api.Assertions.assertNull(
                executor.getNavigationPath("request_credential_attach",
                        Map.of("connector_instance_id", "draft:connector:1"), out));
    }

    @Test
    void planAddStepDraftPipelineReturnsApplyInstructionNotToolFailure() {
        String out = executor.execute("plan_add_step",
                Map.of(
                        "pipeline_id", "draft:pipeline:1",
                        "blueprint_key", "FileIngestion",
                        "instance_name", "IngestLoanMaster"),
                "tenant-1");

        org.junit.jupiter.api.Assertions.assertFalse(out.startsWith("Error"),
                "draft lifecycle guidance must not be classified as a tool failure: " + out);
        assertTrue(out.contains("PREVIEW label"));
        assertTrue(out.contains("apply_plan(plan_id)"));
        assertTrue(out.contains("[draft_ref_waiting_for_apply: draft:pipeline:1]"));
        verify(pipelineRepo, never()).findById("draft:pipeline:1");
    }

    @Test
    void planWirePortsDraftPipelineReturnsApplyInstructionNotProductLookupFailure() {
        String out = executor.execute("plan_wire_ports",
                Map.of(
                        "pipeline_id", "draft:pipeline:1",
                        "source_instance_name", "IngestLoanMaster",
                        "source_port", "out",
                        "target_instance_name", "CleanLoanMaster",
                        "target_port", "in"),
                "tenant-1");

        org.junit.jupiter.api.Assertions.assertFalse(out.startsWith("Error"),
                "draft lifecycle guidance must not be classified as a tool failure: " + out);
        assertTrue(out.contains("PREVIEW label"));
        assertTrue(out.contains("apply_plan(plan_id)"));
        verify(pipelineRepo, never()).findById("draft:pipeline:1");
    }

    @Test
    void requestCredentialAttach_rejectsObjectStorageConnectors() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId("ci-s3");
        ci.setSorId("sor-1");
        ci.setName("Inbound object storage");
        ci.setConnectorDefinitionId("def-s3");
        when(ciRepo.findById("ci-s3")).thenReturn(Optional.of(ci));

        ConnectorDefinition def = new ConnectorDefinition();
        def.setId("def-s3");
        def.setName("S3-compatible Object Storage");
        def.setDockerRepository("pulse/source-s3");
        def.setConnectionSpec(Map.of("properties", Map.of()));
        when(connDefRepo.findById("def-s3")).thenReturn(Optional.of(def));

        String out = executor.execute("request_credential_attach",
                Map.of("connector_instance_id", "ci-s3", "environment", "DEV"),
                "tenant-1");

        assertTrue(out.contains("Credential attach is not required"));
        assertTrue(out.contains("storage_backend identity"));
        org.junit.jupiter.api.Assertions.assertNull(
                executor.getNavigationPath("request_credential_attach",
                        Map.of("connector_instance_id", "ci-s3"), out));
    }

    @Test
    void applyPlanNavigationUsesResolvedCredentialAttachOnly() {
        String result = """
                Applied plan plan-1 (1 command). Status: APPLIED
                [internal_attach_credential: sor_id=sor-1 connector_instance_id=ci-real environment=DEV]
                """;

        org.junit.jupiter.api.Assertions.assertEquals(
                "/producers/sor-1?attach_credential=ci-real&env=DEV",
                executor.getNavigationPath("apply_plan", Map.of("plan_id", "plan-1"), result));

        String draftResult = """
                Applied plan plan-1 (1 command). Status: APPLIED
                [internal_attach_credential: sor_id=sor-1 connector_instance_id=draft:connector:1 environment=DEV]
                """;
        org.junit.jupiter.api.Assertions.assertNull(
                executor.getNavigationPath("apply_plan", Map.of("plan_id", "plan-1"), draftResult));
    }

    @Test
    void applyPlanEmitsResolvedCredentialAttachMarkerFromAppliedUiIntent() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId("ci-real");
        ci.setSorId("sor-1");
        ci.setName("Loan DB");
        ci.setConnectorDefinitionId("def-jdbc");
        when(ciRepo.findById("ci-real")).thenReturn(Optional.of(ci));

        ConnectorDefinition def = new ConnectorDefinition();
        def.setId("def-jdbc");
        def.setName("PostgreSQL");
        def.setDockerRepository("pulse/source-postgres");
        def.setConnectionSpec(Map.of("properties", Map.of(
                "host", Map.of("pulse_role", "env_metadata"),
                "password", Map.of("pulse_role", "credential"))));
        when(connDefRepo.findById("def-jdbc")).thenReturn(Optional.of(def));

        Plan plan = new Plan();
        plan.setId("plan-1");
        plan.setStatus(PlanStatus.APPLIED);
        plan.setCommandIds(List.of("cmd-1"));
        plan.setPreviewData(Map.of("appliedUiIntents", List.of(Map.of(
                "kind", "credential_attach",
                "connectorInstanceId", "ci-real",
                "environment", "DEV"))));
        when(planService.apply("plan-1")).thenReturn(plan);

        String out = executor.execute("apply_plan", Map.of("plan_id", "plan-1"), "tenant-1");

        assertTrue(out.contains("Credential dialog ready for connector ci-real"));
        assertTrue(out.contains("[internal_attach_credential: sor_id=sor-1 connector_instance_id=ci-real environment=DEV]"));
    }

    @Test
    void createConnector_normalizesLegacyCredentialsEnvironmentToCanonicalProd() {
        // Phase 1 contract: agents pass uppercase tokens (DEV / PRODUCTION /
        // INTEGRATION / UAT) per the chat-tool schema, but the persisted
        // credential_profiles.environment column must be canonical
        // lowercase. Without normalization, ChatToolExecutor.createConnector
        // would call credRepo.save(cred) with environment='PRODUCTION' and
        // bypass every controller-level guard.
        com.pulse.sor.model.SystemOfRecord sor = new com.pulse.sor.model.SystemOfRecord();
        sor.setId("sor-1");
        sor.setName("MSP Loan Master");
        sor.setTenantId("tenant-1");
        when(sorRepo.findByTenantIdOrderByNameAsc("tenant-1")).thenReturn(List.of(sor));

        com.pulse.sor.model.ConnectorDefinition def = new com.pulse.sor.model.ConnectorDefinition();
        def.setId("conn-def-jdbc");
        def.setName("Postgres JDBC");
        when(connDefRepo.findAll()).thenReturn(List.of(def));

        when(ciRepo.save(org.mockito.ArgumentMatchers.any(com.pulse.sor.model.ConnectorInstance.class)))
                .thenAnswer(inv -> {
                    com.pulse.sor.model.ConnectorInstance ci = inv.getArgument(0);
                    ci.setId("ci-new");
                    return ci;
                });
        when(credRepo.save(org.mockito.ArgumentMatchers.any(com.pulse.sor.model.CredentialProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sor_name", "MSP Loan Master");
        args.put("connector_name", "MSP Postgres");
        args.put("connector_type", "Postgres JDBC");
        args.put("credential_refs", Map.of(
                "username", "gcp-sm://projects/pulse-dev/secrets/msp-username/versions/latest",
                "password", "gcp-sm://projects/pulse-dev/secrets/msp-password/versions/latest"
        ));
        args.put("credentials_environment", "PRODUCTION");

        String out = executor.execute("create_connector", args, "tenant-1");

        org.mockito.ArgumentCaptor<com.pulse.sor.model.CredentialProfile> captor =
                org.mockito.ArgumentCaptor.forClass(com.pulse.sor.model.CredentialProfile.class);
        verify(credRepo).save(captor.capture());
        com.pulse.sor.model.CredentialProfile saved = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("prod", saved.getEnvironment(),
                "Legacy uppercase 'PRODUCTION' must canonicalize to 'prod' before persistence");
        org.junit.jupiter.api.Assertions.assertEquals("ci-new", saved.getConnectorInstanceId());
        // Echo line in the agent-facing reply also surfaces the canonical key.
        assertTrue(out.contains("Credential references saved for prod"),
                "Agent reply must report canonical env, got: " + out);
    }

    @Test
    void createConnector_rejectsUnknownCredentialsEnvironmentWithoutSavingProfile() {
        com.pulse.sor.model.SystemOfRecord sor = new com.pulse.sor.model.SystemOfRecord();
        sor.setId("sor-1");
        sor.setName("MSP Loan Master");
        sor.setTenantId("tenant-1");
        // sorRepo lookup never reached: env validation fails first.
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sor_name", "MSP Loan Master");
        args.put("connector_name", "MSP Postgres");
        args.put("connector_type", "Postgres JDBC");
        args.put("credential_refs", Map.of(
                "username", "gcp-sm://projects/pulse-dev/secrets/msp-username/versions/latest"
        ));
        args.put("credentials_environment", "QA");

        String out = executor.execute("create_connector", args, "tenant-1");

        assertTrue(out.startsWith("Unknown credentials_environment: 'QA'"),
                "Unknown env must surface a clear error to the agent, got: " + out);
        verify(credRepo, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(com.pulse.sor.model.CredentialProfile.class));
        verify(ciRepo, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(com.pulse.sor.model.ConnectorInstance.class));
    }

    @Test
    void getStoragePaths_normalizesLegacyEnvironmentToCanonicalProdForBackendLookup() {
        // toLowerCase() alone would yield 'production', which never matches a
        // storage_backends row. Phase 1 routes the env through
        // DeploymentEnvironment.normalize so 'PRODUCTION' resolves to 'prod'.
        com.pulse.sor.model.SystemOfRecord sor = new com.pulse.sor.model.SystemOfRecord();
        sor.setId("sor-1");
        sor.setName("MSP Loan Master");
        sor.setTenantId("tenant-1");
        when(sorRepo.findById("sor-1")).thenReturn(Optional.of(sor));

        com.pulse.storage.model.StorageBackend sb = new com.pulse.storage.model.StorageBackend();
        sb.setId("01JSTRG_TEST_GCP");
        sb.setTenantId("tenant-1");
        sb.setEnvironment("prod");
        sb.setBackend("GCP");
        sb.setStorageRootFiles("pulse-tenant-1-prod-files");
        sb.setStorageRootLake("pulse-tenant-1-prod-lake");
        sb.setGcpProject("pulse-tenant-1-prod");
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-1", "prod", "GCP"))
                .thenReturn(Optional.of(sb));

        String out = executor.execute("get_storage_paths", Map.of(
                "sor_id", "sor-1",
                "environment", "PRODUCTION",
                "backend", "GCP"
        ), "tenant-1");

        verify(storageBackendRepo).findByTenantIdAndEnvironmentAndBackend(
                "tenant-1", "prod", "GCP");
        verify(storageBackendRepo, org.mockito.Mockito.never())
                .findByTenantIdAndEnvironmentAndBackend(
                        eq("tenant-1"), eq("production"), org.mockito.ArgumentMatchers.any());
        // The resolved bucket from the canonical row must surface in the
        // tool reply, proving the lookup actually hit the row.
        assertTrue(out.contains("pulse-tenant-1-prod-files"),
                "Resolved bucket must come from the canonical 'prod' row, got: " + out);
    }

    @Test
    void requestCredentialAttach_emitsInternalMarkerWithCanonicalEnvironment() {
        // The frontend consumes [internal_attach_credential: ... environment=...]
        // and routes the user to /producers/{sorId}?env=...; canonicalizing
        // here keeps the URL contract consistent with UI-driven flows even
        // when the agent supplies a legacy uppercase token.
        com.pulse.sor.model.ConnectorInstance ci = new com.pulse.sor.model.ConnectorInstance();
        ci.setId("ci-1");
        ci.setSorId("sor-1");
        ci.setName("MSP Postgres");
        when(ciRepo.findById("ci-1")).thenReturn(Optional.of(ci));

        String out = executor.execute("request_credential_attach", Map.of(
                "connector_instance_id", "ci-1",
                "environment", "PRODUCTION"
        ), "tenant-1");

        assertTrue(out.contains("[internal_attach_credential:"),
                "Output must include the internal marker, got: " + out);
        assertTrue(out.contains("environment=prod"),
                "Marker must use canonical 'prod' env, got: " + out);
        assertTrue(!out.contains("environment=PRODUCTION"),
                "Marker must NOT leak the legacy uppercase env, got: " + out);
        // Tool's user-facing message also surfaces the canonical key.
        assertTrue(out.contains("(prod)"),
                "Reply must reference the canonical env in the human message, got: " + out);
    }

    private com.pulse.blueprint.model.Blueprint buildBlueprint(String key, String status) {
        com.pulse.blueprint.model.Blueprint bp = new com.pulse.blueprint.model.Blueprint();
        bp.setBlueprintKey(key);
        bp.setName(humanize(key));
        bp.setDescription("desc");
        bp.setCategory(com.pulse.blueprint.model.BlueprintCategory.INGESTION);
        bp.setStatus(status);
        return bp;
    }

    private String humanize(String key) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) out.append(' ');
            out.append(c);
        }
        return out.toString();
    }
}
