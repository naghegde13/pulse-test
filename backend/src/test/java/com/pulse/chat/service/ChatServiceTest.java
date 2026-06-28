package com.pulse.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.chat.model.ChatMessage;
import com.pulse.chat.model.ChatSession;
import com.pulse.chat.repository.ChatMessageRepository;
import com.pulse.chat.repository.ChatSessionRepository;
import com.pulse.codegen.model.DbtAsset;
import com.pulse.codegen.service.DbtAssetRegistryService;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private final ChatSessionRepository sessionRepo = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messageRepo = mock(ChatMessageRepository.class);
    private final BlueprintRepository blueprintRepo = mock(BlueprintRepository.class);
    private final SystemOfRecordRepository sorRepo = mock(SystemOfRecordRepository.class);
    private final DomainRepository domainRepo = mock(DomainRepository.class);
    private final DatasetRepository datasetRepo = mock(DatasetRepository.class);
    private final PipelineRepository pipelineRepo = mock(PipelineRepository.class);
    private final CompositionService compositionService = mock(CompositionService.class);
    private final DbtAssetRegistryService dbtAssetRegistryService = mock(DbtAssetRegistryService.class);
    private final ChatToolExecutor toolExecutor = mock(ChatToolExecutor.class);
    private final PhaseDetector phaseDetector = mock(PhaseDetector.class);

    private final ChatService service = new ChatService(
            sessionRepo,
            messageRepo,
            blueprintRepo,
            sorRepo,
            domainRepo,
            datasetRepo,
            pipelineRepo,
            compositionService,
            dbtAssetRegistryService,
            toolExecutor,
            phaseDetector,
            new ObjectMapper()
    );

    @Test
    void buildSystemPrompt_includesPipelineAndRegistryContextPackets() {
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-1");
        domain.setName("Servicing");
        domain.setCurrentBusinessDate(LocalDate.of(2026, 4, 19));
        domain.setBusinessDateGrain("DAILY");

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-1");
        sor.setName("Loan Servicing");
        sor.setDomainName("Servicing");

        Dataset dataset = new Dataset();
        dataset.setId("ds-1");
        dataset.setName("customers");
        dataset.setQualifiedName("tenant-1.servicing.raw.customers");
        dataset.setSchemaFormat("JSON_SCHEMA");
        dataset.setClassification("CONFIDENTIAL");
        dataset.setSchemaSnapshot(Map.of("fields", List.of(
                Map.of("name", "customer_id", "type", "string"),
                Map.of("name", "email", "type", "string")
        )));

        Blueprint blueprint = new Blueprint();
        blueprint.setBlueprintKey("BronzeToSilverCleaning");
        blueprint.setName("Bronze To Silver Cleaning");
        blueprint.setDescription("Clean and conform bronze records before reuse.");
        blueprint.setCategory(BlueprintCategory.TRANSFORM);

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-1");
        pipeline.setName("Customer Quality Pipeline");
        pipeline.setDomainId("domain-1");
        pipeline.setDomainName("Servicing");
        pipeline.setActiveVersionId("version-1");

        SubPipelineInstance instance = new SubPipelineInstance();
        instance.setId("inst-1");
        instance.setExecutionOrder(1);
        instance.setName("Clean Customers");
        instance.setBlueprintKey("BronzeToSilverCleaning");

        PortWiring wiring = new PortWiring();
        wiring.setSourceInstanceId("inst-1");
        wiring.setSourcePortName("cleaned_output");
        wiring.setTargetInstanceId("inst-1");
        wiring.setTargetPortName("data_input");

        DbtAsset dbtAsset = new DbtAsset();
        dbtAsset.setAssetName("dim_customer");
        dbtAsset.setAssetType("model");
        dbtAsset.setBusinessConcept("customer");
        dbtAsset.setGrain("one row per customer");
        dbtAsset.setAccessLevel("internal");

        when(domainRepo.findByTenantIdOrderByNameAsc("tenant-1")).thenReturn(List.of(domain));
        when(sorRepo.findByTenantIdOrderByNameAsc("tenant-1")).thenReturn(List.of(sor));
        when(datasetRepo.findByTenantIdOrderByQualifiedNameAsc("tenant-1")).thenReturn(List.of(dataset));
        when(blueprintRepo.findByDeferredFalseOrderByCategoryAscNameAsc()).thenReturn(List.of(blueprint));
        when(pipelineRepo.findById("pipeline-1")).thenReturn(Optional.of(pipeline));
        when(compositionService.getComposition("version-1"))
                .thenReturn(new CompositionService.CompositionView(List.of(instance), List.of(wiring)));
        when(dbtAssetRegistryService.listDomainAssets("domain-1")).thenReturn(List.of(dbtAsset));

        String prompt = service.buildSystemPrompt("tenant-1", "pipeline-1");

        assertTrue(prompt.contains("### Registered Domains"));
        assertTrue(prompt.contains("### Current Pipeline Context"));
        assertTrue(prompt.contains("Customer Quality Pipeline"));
        assertTrue(prompt.contains("Clean Customers :: BronzeToSilverCleaning"));
        assertTrue(prompt.contains("### Domain dbt Asset Registry Snapshot"));
        assertTrue(prompt.contains("dim_customer"));
        assertTrue(prompt.contains("tenant-1.servicing.raw.customers"));
    }

    @Test
    void buildSystemPrompt_withoutPipelineNotesMissingPipelineContext() {
        when(domainRepo.findByTenantIdOrderByNameAsc("tenant-1")).thenReturn(List.of());
        when(sorRepo.findByTenantIdOrderByNameAsc("tenant-1")).thenReturn(List.of());
        when(datasetRepo.findByTenantIdOrderByQualifiedNameAsc("tenant-1")).thenReturn(List.of());
        when(blueprintRepo.findByDeferredFalseOrderByCategoryAscNameAsc()).thenReturn(List.of());

        String prompt = service.buildSystemPrompt("tenant-1", null);

        assertTrue(prompt.contains("No active pipeline is linked to this chat session yet."));
        assertTrue(prompt.contains("No pipeline-linked domain context available yet."));
    }

    @Test
    void buildSystemPrompt_includesTargetedGenerationAndStructuredFactPackets() {
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-1");
        domain.setName("Servicing");

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-1");
        pipeline.setName("Customer Quality Pipeline");
        pipeline.setDomainId("domain-1");
        pipeline.setDomainName("Servicing");
        pipeline.setActiveVersionId("version-1");

        SubPipelineInstance cleaning = new SubPipelineInstance();
        cleaning.setId("inst-1");
        cleaning.setExecutionOrder(1);
        cleaning.setName("Clean Customers");
        cleaning.setBlueprintKey("BronzeToSilverCleaning");

        ChatSession session = new ChatSession();
        session.setTenantId("tenant-1");
        session.setUserId("user-1");
        session.setPipelineId("pipeline-1");

        ChatMessage toolMessage = new ChatMessage();
        toolMessage.setSessionId("session-1");
        toolMessage.setRole("TOOL");
        toolMessage.setContent("Found reusable dbt asset.");
        toolMessage.setToolResults(Map.of(
                "tool_name", "find_dbt_reuse_candidate",
                "facts", List.of(Map.of(
                        "fact_type", "decision",
                        "fact_key", "dbt.reuse_choice",
                        "fact_value", Map.of("decision", "reuse_wrapper", "asset_name", "dim_customer"),
                        "persistence_status", "session_only"
                ))));

        when(domainRepo.findByTenantIdOrderByNameAsc("tenant-1")).thenReturn(List.of(domain));
        when(sorRepo.findByTenantIdOrderByNameAsc("tenant-1")).thenReturn(List.of());
        when(datasetRepo.findByTenantIdOrderByQualifiedNameAsc("tenant-1")).thenReturn(List.of());
        when(blueprintRepo.findByDeferredFalseOrderByCategoryAscNameAsc()).thenReturn(List.of());
        when(pipelineRepo.findById("pipeline-1")).thenReturn(Optional.of(pipeline));
        when(compositionService.getComposition("version-1"))
                .thenReturn(new CompositionService.CompositionView(List.of(cleaning), List.of()));
        when(dbtAssetRegistryService.listDomainAssets("domain-1")).thenReturn(List.of());
        when(sessionRepo.findById("session-1")).thenReturn(Optional.of(session));
        when(messageRepo.findBySessionIdOrderByCreatedAtAsc("session-1")).thenReturn(List.of(toolMessage));

        String prompt = service.buildSystemPrompt("tenant-1", "pipeline-1", "session-1");

        assertTrue(prompt.contains("### Targeted Generation Retrieval Packets"));
        assertTrue(prompt.contains("dbt_silver_cleaning"));
        assertTrue(prompt.contains("dbt.reuse_choice"));
        assertTrue(prompt.contains("### Structured Session Facts"));
    }

    @Test
    void buildToolResultPayload_linksSessionToCreatedPipeline() {
        ChatSession session = new ChatSession();
        session.setTenantId("tenant-1");
        session.setUserId("user-1");

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-1");
        pipeline.setName("Customer Quality Pipeline");

        when(sessionRepo.findById("session-1")).thenReturn(Optional.of(session));
        when(pipelineRepo.findById("pipeline-1")).thenReturn(Optional.of(pipeline));

        Map<String, Object> payload = service.buildToolResultPayload(
                "session-1",
                "tenant-1",
                "propose_create_pipeline",
                Map.of("name", "Customer Quality Pipeline", "description", "Build the customer quality pipeline"),
                """
                Pipeline created successfully.
                Name: Customer Quality Pipeline
                Domain: Servicing
                [internal_id: pipeline-1]
                [internal_version_id: version-1]
                [NOTE: IDs are for tool calls only. NEVER show to user.]
                """);

        assertEquals("pipeline-1", session.getPipelineId());
        assertTrue(((List<?>) payload.get("facts")).toString().contains("chat.session.pipeline_link"));
        assertTrue(((List<?>) payload.get("facts")).toString().contains("pipeline.created"));
        verify(sessionRepo).save(session);
    }

    @Test
    void buildToolResultEnvelope_planCreateIncludesDraftRefsAndPreviewCommands() {
        var envelope = service.buildToolResultEnvelope(
                "plan_create_pipeline",
                Map.of(),
                """
                Created pipeline plan plan-1 for pending pipeline draft:pipeline:1.
                [plan_id: plan-1]
                [declared_draft_ref: draft:pipeline:1]
                [preview_command: index=0 type=pipeline.create aggregate=draft:pipeline:1]
                """);

        assertTrue(envelope.planCreated());
        assertEquals("plan-1", envelope.planId());
        assertEquals(List.of("draft:pipeline:1"), envelope.declaredDraftRefs());
        assertEquals("pipeline.create", envelope.previewCommands().get(0).get("type"));
        assertTrue(envelope.refreshHints().contains("plan"));
    }

    @Test
    void buildToolResultEnvelope_applyPlanIncludesRealCredentialAttachIntent() {
        var envelope = service.buildToolResultEnvelope(
                "apply_plan",
                Map.of("plan_id", "plan-1"),
                """
                Applied plan plan-1 (1 command). Status: APPLIED
                [internal_attach_credential: sor_id=sor-1 connector_instance_id=ci-real-1 environment=DEV]
                """);

        assertTrue(envelope.mutationApplied());
        assertEquals("plan-1", envelope.planId());
        assertEquals(1, envelope.uiIntents().size());
        assertEquals("credential_attach", envelope.uiIntents().get(0).get("kind"));
        assertEquals("ci-real-1", envelope.uiIntents().get(0).get("connectorInstanceId"));
        assertTrue(envelope.refreshHints().contains("credentials"));
    }

    // -----------------------------------------------------------------------
    //  BUG-2026-05-25-56 / Agent B — outbound tool_calls unwrap.
    //
    //  PULSE stores tool_calls JSONB as {"calls": [...]} (internal wrapper)
    //  but OpenAI/OpenRouter expects a BARE array on the wire. ChatService
    //  exposes unwrapToolCalls() which must:
    //   - return the inner List<?> when stored is {"calls": [...]}
    //   - return null (so the field is OMITTED, not "tool_calls": null) for
    //     null / empty / mis-shaped / empty-list rows — OpenRouter rejects
    //     null with "tool_calls must be an array".
    // -----------------------------------------------------------------------

    @Test
    void handleLLMMode_unwrapsStoredToolCallsForOutbound() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("calls", List.of(
                Map.of("id", "call_cpHeFIYOdGYKtdS9EAo0FWCm",
                       "type", "function",
                       "function", Map.of("name", "list_data_sources", "arguments", "{}"))
        ));

        List<?> outbound = ChatService.unwrapToolCalls(stored);

        assertNotNull(outbound, "outbound tool_calls must not be null when stored has a non-empty 'calls' list");
        assertEquals(1, outbound.size());
        Object first = outbound.get(0);
        assertTrue(first instanceof Map<?, ?>,
                "outbound tool_calls must be a List of Maps (OpenAI/OpenRouter shape), not a wrapper Map");
        Map<?, ?> firstMap = (Map<?, ?>) first;
        assertEquals("call_cpHeFIYOdGYKtdS9EAo0FWCm", firstMap.get("id"));
        assertEquals("function", firstMap.get("type"));
    }

    @Test
    void handleLLMMode_omitsToolCallsIfStoredEmpty() {
        // 1) null map → omit
        assertNull(ChatService.unwrapToolCalls(null),
                "null stored map must return null (omit field)");

        // 2) empty map → omit
        assertNull(ChatService.unwrapToolCalls(Map.of()),
                "empty stored map must return null (omit field)");

        // 3) missing 'calls' key entirely → omit
        Map<String, Object> noCallsKey = new HashMap<>();
        noCallsKey.put("tool_call_id", "call_x");
        assertNull(ChatService.unwrapToolCalls(noCallsKey),
                "map without 'calls' key must return null (omit field)");

        // 4) 'calls' key present but null value → omit
        Map<String, Object> nullCalls = new HashMap<>();
        nullCalls.put("calls", null);
        assertNull(ChatService.unwrapToolCalls(nullCalls),
                "map with null 'calls' value must return null (omit field)");

        // 5) 'calls' key present but wrong type (a Map, not a List) → omit
        Map<String, Object> wrongShape = new HashMap<>();
        wrongShape.put("calls", Map.of("not", "a list"));
        assertNull(ChatService.unwrapToolCalls(wrongShape),
                "map with non-List 'calls' value must return null (omit field)");

        // 6) 'calls' key present but empty list → omit (no point sending tool_calls: [])
        Map<String, Object> emptyList = new HashMap<>();
        emptyList.put("calls", List.of());
        assertNull(ChatService.unwrapToolCalls(emptyList),
                "map with empty 'calls' list must return null (omit field)");
    }

    @Test
    void handleLLMMode_persistedAssistantToolCallsRoundTrip() {
        List<Map<String, Object>> tcList = List.of(
                Map.of("id", "call_abc",
                       "type", "function",
                       "function", Map.of("name", "create_data_source", "arguments", "{\"name\":\"MSP\"}"))
        );
        // shape persisted by ChatService line ~263: setToolCalls(Map.of("calls", tcList))
        Map<String, Object> stored = Map.of("calls", tcList);

        List<?> outbound = ChatService.unwrapToolCalls(stored);

        assertNotNull(outbound);
        assertEquals(tcList, outbound, "round-tripped tool_calls must equal the originally persisted list");
    }
}
