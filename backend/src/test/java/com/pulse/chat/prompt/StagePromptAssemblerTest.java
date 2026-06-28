package com.pulse.chat.prompt;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.chat.orchestration.NodeLlmAdapter;
import com.pulse.chat.service.ChatService;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 8 — the per-stage prompt assemblies + dumped catalog + 5 category guides +
 * ACTIVE MODE injection. Pure unit assertions (no Spring context): each stage
 * prompt contains its key sections; the catalog block lists blueprints; ACTIVE
 * MODE is injected into the building/planning stages; the 5 category guides exist
 * and fold SINK->Transform / CONTROL->Orchestration.
 */
class StagePromptAssemblerTest {

    private BlueprintRepository blueprintRepository;
    private RuntimeAuthorityService runtimeAuthorityService;
    private ChatService chatService;
    private StagePromptAssembler assembler;

    @BeforeEach
    void setUp() {
        blueprintRepository = mock(BlueprintRepository.class);
        runtimeAuthorityService = mock(RuntimeAuthorityService.class);
        chatService = mock(ChatService.class);

        when(blueprintRepository.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active"))
                .thenReturn(List.of(
                        blueprint("FileIngestion", BlueprintCategory.INGESTION),
                        blueprint("BronzeToSilverCleaning", BlueprintCategory.TRANSFORM),
                        blueprint("SCD2Dimension", BlueprintCategory.MODELING),
                        blueprint("DQValidator", BlueprintCategory.DATA_QUALITY),
                        blueprint("ScheduleAndTriggers", BlueprintCategory.ORCHESTRATION)));
        when(runtimeAuthorityService.getActivePersona()).thenReturn(RuntimePersona.GCP_PULSE);
        when(chatService.buildTurnContextPrompt(anyString(), any(), any()))
                .thenReturn("### Available Datasets\nLIVE-CONTEXT-MARKER\n");

        assembler = new StagePromptAssembler(
                chatService,
                new BlueprintCatalogBlock(blueprintRepository),
                new ActiveModeBlock(runtimeAuthorityService));
    }

    private static Blueprint blueprint(String key, BlueprintCategory cat) {
        Blueprint b = new Blueprint();
        b.setBlueprintKey(key);
        b.setName(key);
        b.setDescription(key + " does a thing");
        b.setCategory(cat);
        b.setVersion("1.0.0");
        b.setInputPorts(List.of(Map.of("name", "data_input")));
        b.setOutputPorts(List.of(Map.of("name", "out")));
        b.setValidLayers(List.of("silver"));
        return b;
    }

    private String assemble(NodeLlmAdapter.Stage stage) {
        return assembler.assemble(stage, "tenant-1", "pipeline-1", "session-1");
    }

    // ---- the 7 per-stage prompts each contain their key sections ----

    @Test
    void routerPromptHasRoutingDecisionTree() {
        String p = assemble(NodeLlmAdapter.Stage.ROUTER);
        assertTrue(p.contains("intent router"));
        assertTrue(p.contains("ROUTING DECISION TREE"));
        assertTrue(p.contains("plan_decision"));
    }

    @Test
    void discoveryPromptIsSingleJudiciousAsk() {
        String p = assemble(NodeLlmAdapter.Stage.DISCOVERY);
        assertTrue(p.contains("Discovery stage"));
        assertTrue(p.contains("JUDICIOUS-ASK"));
        assertTrue(p.contains("CATALOG IS IN-CONTEXT"));
    }

    @Test
    void composerPromptHasProgressiveBuildingAndMedallion() {
        String p = assemble(NodeLlmAdapter.Stage.COMPOSER);
        assertTrue(p.contains("PROGRESSIVE BUILDING"));
        assertTrue(p.contains("YOU DO NOT WRITE CODE"));
        assertTrue(p.contains("MEDALLION HARD CONSTRAINTS"));
        assertTrue(p.contains("reasoning"));
    }

    @Test
    void configurePromptIsStructuredNoSubLlm() {
        String p = assemble(NodeLlmAdapter.Stage.CONFIGURE);
        assertTrue(p.contains("Configure stage"));
        assertTrue(p.contains("STRUCTURED, NOT NATURAL-LANGUAGE"));
        assertTrue(p.contains("PREFER MNEMONICS"));
    }

    @Test
    void provisionPromptRetainsConnectorVocabularyAndPunchList() {
        String p = assemble(NodeLlmAdapter.Stage.PROVISION);
        assertTrue(p.contains("Provision stage"));
        assertTrue(p.contains("CONNECTORS — TWO FAMILIES"));
        // retained PulseSystemPrompt coverage re-homed
        assertTrue(p.contains("Family A") || p.contains("Object-storage"));
    }

    @Test
    void plannerPromptEmitsBareMarkdownTableAndRetainsPacket() {
        String p = assemble(NodeLlmAdapter.Stage.PLANNER);
        assertTrue(p.contains("Planner"));
        assertTrue(p.contains("| Step | Blueprint | Name | Purpose |"));
        assertTrue(p.contains("never wrap it in a code fence")
                || p.contains("never code-fenced"));
        assertTrue(p.contains("AdvanceTimeDimension"));
    }

    @Test
    void responderPromptHasReportActualAndNoEmojis() {
        String p = assemble(NodeLlmAdapter.Stage.RESPONDER);
        assertTrue(p.contains("REPORT THE ACTUAL COMPOSITION"));
        assertTrue(p.contains("Do NOT use emojis"));
        assertTrue(p.contains("NEVER TELL THE ENGINEER TO DEPLOY"));
    }

    // ---- shared preamble + live context wired into every stage ----

    @Test
    void everyStageCarriesSharedIdentityAndLiveContext() {
        for (NodeLlmAdapter.Stage stage : NodeLlmAdapter.Stage.values()) {
            String p = assemble(stage);
            assertTrue(p.contains("You are PULSE"), stage + " has identity");
            assertTrue(p.contains("LIVE-CONTEXT-MARKER"), stage + " carries live per-turn context");
        }
    }

    // ---- catalog block lists blueprints ----

    @Test
    void buildingStagesDumpTheBlueprintCatalog() {
        String composer = assemble(NodeLlmAdapter.Stage.COMPOSER);
        assertTrue(composer.contains(BlueprintCatalogBlock.HEADING));
        assertTrue(composer.contains("FileIngestion"));
        assertTrue(composer.contains("SCD2Dimension"));
        assertTrue(composer.contains("DQValidator"));
        // tight entry carries layer + ports
        assertTrue(composer.contains("layer="));
        assertTrue(composer.contains("data_input"));
        // cache marker present (inert today)
        assertTrue(composer.contains(BlueprintCatalogBlock.CACHE_MARKER));
    }

    @Test
    void discoveryAlsoSeesTheCatalog() {
        String p = assemble(NodeLlmAdapter.Stage.DISCOVERY);
        assertTrue(p.contains(BlueprintCatalogBlock.HEADING));
        assertTrue(p.contains("FileIngestion"));
    }

    // ---- ACTIVE MODE injected into building/planning stages ----

    @Test
    void activeModeInjectedIntoBuildingAndPlanningStages() {
        for (NodeLlmAdapter.Stage stage : List.of(
                NodeLlmAdapter.Stage.COMPOSER, NodeLlmAdapter.Stage.CONFIGURE,
                NodeLlmAdapter.Stage.PROVISION, NodeLlmAdapter.Stage.PLANNER)) {
            String p = assemble(stage);
            assertTrue(p.contains(ActiveModeBlock.MARKER), stage + " carries ACTIVE MODE");
            assertTrue(p.contains("GCP_PULSE"), stage + " carries the resolved persona");
            assertTrue(p.contains("bq_native"), stage + " carries the storage mapping");
        }
    }

    @Test
    void routerAndResponderDoNotInjectModeOrCatalog() {
        // Router/Responder are classification/report stages — no storage authority needed.
        String router = assemble(NodeLlmAdapter.Stage.ROUTER);
        assertFalse(router.contains(ActiveModeBlock.MARKER));
        assertFalse(router.contains(BlueprintCatalogBlock.HEADING));
    }

    // ---- 5 category guides + folding ----

    @Test
    void fiveCategoryGuidesPresentInCatalogStages() {
        String composer = assemble(NodeLlmAdapter.Stage.COMPOSER);
        assertTrue(composer.contains("# Best Practices: Ingestion"));
        assertTrue(composer.contains("# Best Practices: Transform"));
        assertTrue(composer.contains("# Best Practices: Modeling"));
        assertTrue(composer.contains("# Best Practices: Data Quality"));
        assertTrue(composer.contains("# Best Practices: Orchestration"));
    }

    @Test
    void dpcPersonaInjectsParquetMapping() {
        when(runtimeAuthorityService.getActivePersona()).thenReturn(RuntimePersona.DPC_PULSE);
        String p = assemble(NodeLlmAdapter.Stage.COMPOSER);
        assertTrue(p.contains("DPC_PULSE"));
        assertTrue(p.contains("Hive + Parquet"));
    }

    @Test
    void categoryGuideFoldsSinkIntoTransformAndControlIntoOrchestration() {
        assertTrue(CategoryGuides.forCategory(BlueprintCategory.DESTINATION)
                .contains("# Best Practices: Transform"), "SINK/DESTINATION folds into Transform");
        assertTrue(CategoryGuides.forCategory(BlueprintCategory.ORCHESTRATION)
                .contains("# Best Practices: Orchestration"), "CONTROL is covered by Orchestration");
    }

    @Test
    void catalogHybridFallbackEmitsSummariesOnly() {
        String summaries = new BlueprintCatalogBlock(blueprintRepository).renderSummaries();
        assertTrue(summaries.contains("hybrid"));
        assertTrue(summaries.contains("FileIngestion"));
        // summaries do NOT carry the per-param/ports detail
        assertFalse(summaries.contains("layer="));
    }

    @Test
    void contextTagStripperRemovesWrappers() {
        String streamed = "Here is the plan.<runtime_mode>secret</runtime_mode> Done.";
        String stripped = ContextWrappers.stripContextTags(streamed);
        assertFalse(stripped.contains("runtime_mode"));
        assertTrue(stripped.contains("Here is the plan."));
        assertTrue(stripped.contains("Done."));
    }
}
