package com.pulse.cobol.service;

import com.pulse.cobol.model.CobolDiscoveryArtifact;
import com.pulse.cobol.model.CobolDiscoveryMessage;
import com.pulse.cobol.model.CobolDiscoveryRun;
import com.pulse.cobol.model.CobolDiscoverySession;
import com.pulse.cobol.repository.CobolDiscoveryArtifactRepository;
import com.pulse.cobol.repository.CobolDiscoveryMessageRepository;
import com.pulse.cobol.repository.CobolDiscoveryRunRepository;
import com.pulse.cobol.repository.CobolDiscoverySessionRepository;
import com.pulse.cobol.repository.CobolParsingProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Orchestration tests for {@link CobolDiscoveryService#postMessage} —
 * covering action plan execution, auto-execute, and the response routing.
 */
@ExtendWith(MockitoExtension.class)
class CobolDiscoveryServiceOrchestrationTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String SESSION_ID = "session-1";
    private static final String VALID_COPYBOOK = """
            01 ROOT-REC.
               05 FIELD-A PIC X(4).
               05 FIELD-B PIC X(2).
            """;

    @Mock private CobolDiscoverySessionRepository sessionRepository;
    @Mock private CobolDiscoveryMessageRepository messageRepository;
    @Mock private CobolDiscoveryArtifactRepository artifactRepository;
    @Mock private CobolDiscoveryRunRepository runRepository;
    @Mock private CobolParsingProfileRepository profileRepository;
    @Mock private CobolDiscoveryStorageService storageService;
    @Mock private CobolCopybookAnalyzer copybookAnalyzer;
    @Mock private CobolSparkPreviewService sparkPreviewService;
    @Mock private CobolDockerSparkPreviewService dockerSparkPreviewService;
    @Mock private CobolDiscoveryAssistantService assistantService;
    @Mock private CobolDiscoveryRunStreamService runStreamService;
    @Mock private CobolDiscoverySessionStreamService sessionStreamService;

    private CobolDiscoveryService service;

    /** Reusable session fixture. */
    private CobolDiscoverySession session;

    @BeforeEach
    void setUp() {
        service = new CobolDiscoveryService(
                sessionRepository,
                messageRepository,
                artifactRepository,
                runRepository,
                profileRepository,
                storageService,
                copybookAnalyzer,
                sparkPreviewService,
                dockerSparkPreviewService,
                assistantService,
                runStreamService,
                sessionStreamService
        );

        session = new CobolDiscoverySession();
        session.setId(SESSION_ID);
        session.setTenantId(TENANT_ID);
        session.setStatus("ACTIVE");
        session.setTitle("Test Session");

        // Base stubs required by every postMessage invocation
        lenient().when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        lenient().when(runRepository.findByExpiresAtBeforeAndCleanupStatus(any(), anyString())).thenReturn(List.of());
        lenient().when(messageRepository.save(any(CobolDiscoveryMessage.class)))
                .thenAnswer(invocation -> {
                    CobolDiscoveryMessage msg = invocation.getArgument(0);
                    if (msg.getId() == null) msg.setId("msg-" + System.nanoTime());
                    return msg;
                });
        lenient().when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of());
        lenient().when(runRepository.findBySessionIdOrderByCreatedAtDesc(SESSION_ID)).thenReturn(List.of());
        lenient().when(artifactRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of());
        lenient().when(profileRepository.findByTenantIdOrderByUpdatedAtDesc(TENANT_ID)).thenReturn(List.of());
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    /** Build a copybook artifact stub that is considered "active". */
    private CobolDiscoveryArtifact copybookArtifact() {
        CobolDiscoveryArtifact a = new CobolDiscoveryArtifact();
        a.setId("art-copybook");
        a.setSessionId(SESSION_ID);
        a.setTenantId(TENANT_ID);
        a.setArtifactType("copybook");
        a.setOriginalFilename("copybook.cob");
        a.setStorageUri("file:///tmp/copybook.cob");
        a.setSha256("abc123");
        a.setSizeBytes(100);
        a.setCleanupStatus("ACTIVE");
        a.setExpiresAt(Instant.now().plusSeconds(3600));
        return a;
    }

    /** Build a data file artifact stub that is considered "active". */
    private CobolDiscoveryArtifact dataFileArtifact() {
        CobolDiscoveryArtifact a = new CobolDiscoveryArtifact();
        a.setId("art-datafile");
        a.setSessionId(SESSION_ID);
        a.setTenantId(TENANT_ID);
        a.setArtifactType("data_file");
        a.setOriginalFilename("data.dat");
        a.setStorageUri("file:///tmp/data.dat");
        a.setSha256("def456");
        a.setSizeBytes(2048);
        a.setCleanupStatus("ACTIVE");
        a.setExpiresAt(Instant.now().plusSeconds(3600));
        return a;
    }

    /** Configure the artifact repository so both copybook and data_file are active. */
    private void stubBothArtifactsPresent() {
        CobolDiscoveryArtifact copybook = copybookArtifact();
        CobolDiscoveryArtifact dataFile = dataFileArtifact();
        lenient().when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                SESSION_ID, "copybook", "ACTIVE")).thenReturn(Optional.of(copybook));
        lenient().when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                SESSION_ID, "data_file", "ACTIVE")).thenReturn(Optional.of(dataFile));
        lenient().when(artifactRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .thenReturn(List.of(copybook, dataFile));
        try {
            lenient().when(storageService.readText(any())).thenReturn(VALID_COPYBOOK);
            lenient().when(storageService.readBytes(any())).thenReturn(new byte[]{0x01, 0x02});
        } catch (IOException ignored) { /* mocking setup */ }
    }

    /** Configure only the copybook artifact as active. */
    private void stubCopybookOnly() {
        CobolDiscoveryArtifact copybook = copybookArtifact();
        lenient().when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                SESSION_ID, "copybook", "ACTIVE")).thenReturn(Optional.of(copybook));
        lenient().when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                SESSION_ID, "data_file", "ACTIVE")).thenReturn(Optional.empty());
        try {
            lenient().when(storageService.readText(any())).thenReturn(VALID_COPYBOOK);
        } catch (IOException ignored) { /* mocking setup */ }
    }

    /** Configure no active artifacts. */
    private void stubNoArtifacts() {
        lenient().when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    /** Stub assistantService.planActions to return ActionPlan.none(). */
    private void stubPlanActionsNone() {
        lenient().when(assistantService.planActions(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(CobolDiscoveryAssistantService.ActionPlan.none());
    }

    /** Stub assistantService.respondStructured to return a simple reply. */
    private void stubRespondStructured(String content, Map<String, Object> metadata) {
        lenient().when(assistantService.respondStructured(any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenReturn(new CobolDiscoveryAssistantService.AssistantReply(content, metadata));
    }

    /**
     * Stub runRepository.save to assign an ID and simulate JPA lifecycle hooks
     * (createdAt / updatedAt) since {@code @PrePersist} doesn't fire in unit tests.
     */
    private void stubRunSave() {
        lenient().when(runRepository.save(any(CobolDiscoveryRun.class)))
                .thenAnswer(invocation -> {
                    CobolDiscoveryRun run = invocation.getArgument(0);
                    if (run.getId() == null) run.setId("run-" + System.nanoTime());
                    // Simulate @PrePersist / @PreUpdate so sanitizeRun won't NPE on getUpdatedAt()
                    try {
                        java.lang.reflect.Field createdAtField = com.pulse.common.model.BaseEntity.class.getDeclaredField("createdAt");
                        createdAtField.setAccessible(true);
                        if (createdAtField.get(run) == null) createdAtField.set(run, Instant.now());
                        java.lang.reflect.Field updatedAtField = com.pulse.common.model.BaseEntity.class.getDeclaredField("updatedAt");
                        updatedAtField.setAccessible(true);
                        updatedAtField.set(run, Instant.now());
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                    return run;
                });
    }

    /** Build a completed latest run for use in preview summaries. */
    private CobolDiscoveryRun completedRun() {
        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setId("run-completed");
        run.setSessionId(SESSION_ID);
        run.setTenantId(TENANT_ID);
        run.setRunType("preview");
        run.setStatus("COMPLETED");
        run.setConfigSnapshot(Map.of("record_format", "F"));
        run.setProfilingSummary(Map.of("rowCount", 10, "columnCount", 3));
        run.setAnomalySummary(Map.of("warnings", List.of()));
        run.setSamplePolicy(Map.of("previewRows", 20));
        run.setResultSchemaSnapshot(Map.of("fields", List.of("FIELD-A", "FIELD-B")));
        run.setPreviewRows(List.of(Map.of("FIELD-A", "ABCD", "FIELD-B", "EF")));
        run.setMappingSpec(List.of());
        run.setEventLog(List.of());
        run.setConfidenceScore(85.0);
        return run;
    }

    // ──────────────────────────────────────────────────────────────────
    // 1. Basic postMessage flow
    // ──────────────────────────────────────────────────────────────────

    @Test
    void postMessage_createsUserMessageAndAssistantMessage() {
        stubNoArtifacts();
        stubPlanActionsNone();
        stubRespondStructured("Hello from assistant", Map.of());

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "Hello", Map.of());

        // user message + assistant message from postMessage + assistant inside createAssistantMessage
        verify(messageRepository, atLeast(2)).save(any(CobolDiscoveryMessage.class));
        assertNotNull(exchange.userMessage());
        assertNotNull(exchange.assistantMessage());
    }

    @Test
    void postMessage_savesUserMessageWithCorrectRoleAndContent() {
        stubNoArtifacts();
        stubPlanActionsNone();
        stubRespondStructured("Response", Map.of());

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "My question", Map.of());

        assertEquals("USER", exchange.userMessage().getRole());
        assertEquals("My question", exchange.userMessage().getContent());
    }

    @Test
    void postMessage_delegatesToAssistantPlanActions() {
        stubNoArtifacts();
        stubPlanActionsNone();
        stubRespondStructured("Response", Map.of());

        service.postMessage(TENANT_ID, SESSION_ID, "run preview", Map.of("record_format", "F"));

        verify(assistantService).planActions(
                eq("run preview"),
                eq(Map.of("record_format", "F")),
                any(),              // latestRun
                any(),              // copybookContent
                any(),              // recentMessages
                anyBoolean(),       // hasCopybook
                anyBoolean()        // hasDataFile
        );
    }

    @Test
    void postMessage_returnsMessageExchangeWithUserAndAssistantMessages() {
        stubNoArtifacts();
        stubPlanActionsNone();
        stubRespondStructured("All good", Map.of());

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "status", Map.of());

        assertNotNull(exchange);
        assertNotNull(exchange.userMessage());
        assertNotNull(exchange.assistantMessage());
        assertNotNull(exchange.optionOverrides());
        assertNotNull(exchange.toolActions());
    }

    @Test
    void postMessage_handlesNullContent() {
        stubNoArtifacts();
        stubPlanActionsNone();
        stubRespondStructured("Response", Map.of());

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, null, Map.of());

        // No NPE, user message content defaults to ""
        assertEquals("", exchange.userMessage().getContent());
    }

    @Test
    void postMessage_handlesNullOptionOverrides() {
        stubNoArtifacts();
        stubPlanActionsNone();
        stubRespondStructured("Response", Map.of());

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "hello", null);

        // No NPE, optionOverrides is an empty map
        assertNotNull(exchange.optionOverrides());
    }

    // ──────────────────────────────────────────────────────────────────
    // 7. Action plan with no actions → respondStructured
    // ──────────────────────────────────────────────────────────────────

    @Test
    void postMessage_callsRespondStructuredWhenNoActionsAndNoPlanMessage() {
        stubNoArtifacts();
        stubPlanActionsNone();
        stubRespondStructured("Structured response", Map.of());

        service.postMessage(TENANT_ID, SESSION_ID, "what should I do", Map.of());

        verify(assistantService).respondStructured(
                any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any()
        );
    }

    // ──────────────────────────────────────────────────────────────────
    // 8–9. Action plan execution — copybook update
    // ──────────────────────────────────────────────────────────────────

    @Test
    void postMessage_executesCopybookUpdateFromActionPlan() throws IOException {
        stubBothArtifactsPresent();

        // copybookAnalyzer returns valid
        when(copybookAnalyzer.validateSyntax(anyString()))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(true, null));

        CobolDiscoveryArtifact updatedArtifact = copybookArtifact();
        updatedArtifact.setId("art-updated-copybook");
        when(storageService.storeTextArtifact(anyString(), anyString(), eq("copybook"), anyString(), anyString(), any()))
                .thenReturn(updatedArtifact);

        CobolDiscoveryAssistantService.ActionPlan plan = new CobolDiscoveryAssistantService.ActionPlan(
                "I updated the copybook.",
                Map.of(),
                VALID_COPYBOOK,
                null,
                0,
                false,
                List.of(new CobolDiscoveryAssistantService.ToolActionRequest("update_copybook_text", Map.of("copybookText", VALID_COPYBOOK)))
        );
        when(assistantService.planActions(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(plan);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "update the copybook", Map.of());

        verify(storageService).storeTextArtifact(eq(TENANT_ID), eq(SESSION_ID), eq("copybook"), anyString(), eq(VALID_COPYBOOK), any());
        assertTrue(exchange.toolActions().stream()
                .anyMatch(a -> "update_copybook_text".equals(a.name()) && "completed".equals(a.status())));
    }

    @Test
    void postMessage_rejectsCopybookUpdateOnValidationFailure() throws IOException {
        stubBothArtifactsPresent();

        String invalidCopybook = "GARBAGE TEXT";
        when(copybookAnalyzer.validateSyntax(invalidCopybook))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(false, "Syntax error at line 1"));

        CobolDiscoveryAssistantService.ActionPlan plan = new CobolDiscoveryAssistantService.ActionPlan(
                "I updated the copybook.",
                Map.of(),
                invalidCopybook,
                null,
                0,
                false,
                List.of(new CobolDiscoveryAssistantService.ToolActionRequest("update_copybook_text", Map.of("copybookText", invalidCopybook)))
        );
        when(assistantService.planActions(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(plan);
        // respondStructured will be called because copybook update was rejected
        stubRespondStructured("Copybook was invalid, let me fix it.", Map.of());

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "update the copybook", Map.of());

        verify(storageService, never()).storeTextArtifact(anyString(), anyString(), eq("copybook"), anyString(), eq(invalidCopybook), any());
        assertTrue(exchange.toolActions().stream()
                .anyMatch(a -> "update_copybook_text".equals(a.name()) && "rejected".equals(a.status())));
        // respondStructured is called because of the copybook error path
        verify(assistantService).respondStructured(any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any());
    }

    // ──────────────────────────────────────────────────────────────────
    // 10. Action plan execution — config update
    // ──────────────────────────────────────────────────────────────────

    @Test
    void postMessage_executesConfigUpdateFromActionPlan() {
        stubBothArtifactsPresent();

        Map<String, Object> configOverrides = Map.of("record_format", "V", "ebcdic_code_page", "cp037");
        CobolDiscoveryAssistantService.ActionPlan plan = new CobolDiscoveryAssistantService.ActionPlan(
                "I applied the config.",
                configOverrides,
                "",
                null,
                0,
                false,
                List.of(new CobolDiscoveryAssistantService.ToolActionRequest("update_config", configOverrides))
        );
        when(assistantService.planActions(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(plan);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "update the config", Map.of());

        // optionOverrides in the exchange should contain the config
        assertNotNull(exchange.optionOverrides());
        assertTrue(exchange.toolActions().stream()
                .anyMatch(a -> "update_config".equals(a.name()) && "completed".equals(a.status())));
    }

    // ──────────────────────────────────────────────────────────────────
    // 11–13. Action plan execution — preview/profile run
    // ──────────────────────────────────────────────────────────────────

    @Test
    void postMessage_queuesPreviewRunWhenActionPlanSaysPreview() throws IOException {
        stubBothArtifactsPresent();
        stubRunSave();

        // copybookAnalyzer not needed for this path directly but normalizeConfig may run
        lenient().when(copybookAnalyzer.validateSyntax(anyString()))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(true, null));

        CobolDiscoveryAssistantService.ActionPlan plan = new CobolDiscoveryAssistantService.ActionPlan(
                "I queued a preview run.",
                Map.of(),
                "",
                "preview",
                20,
                false,
                List.of(new CobolDiscoveryAssistantService.ToolActionRequest("run_preview", Map.of()))
        );
        when(assistantService.planActions(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(plan);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "run preview", Map.of());

        verify(runRepository).save(argThat(run -> "preview".equals(run.getRunType())));
        assertNotNull(exchange.activeRun());
    }

    @Test
    void postMessage_queuesProfileRunWhenActionPlanSaysProfile() throws IOException {
        stubBothArtifactsPresent();
        stubRunSave();

        lenient().when(copybookAnalyzer.validateSyntax(anyString()))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(true, null));

        CobolDiscoveryAssistantService.ActionPlan plan = new CobolDiscoveryAssistantService.ActionPlan(
                "I queued a full profile run.",
                Map.of(),
                "",
                "profile",
                50,
                false,
                List.of(new CobolDiscoveryAssistantService.ToolActionRequest("run_full_profile", Map.of()))
        );
        when(assistantService.planActions(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(plan);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "run profile", Map.of());

        verify(runRepository).save(argThat(run -> "profile".equals(run.getRunType())));
        assertNotNull(exchange.activeRun());
    }

    @Test
    void postMessage_skipsRunWhenCopybookUpdateWasRejected() throws IOException {
        stubBothArtifactsPresent();

        String invalidCopybook = "INVALID GARBAGE";
        when(copybookAnalyzer.validateSyntax(invalidCopybook))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(false, "Syntax error"));

        // Plan has both copybook update and preview run
        CobolDiscoveryAssistantService.ActionPlan plan = new CobolDiscoveryAssistantService.ActionPlan(
                "Updating copybook and running preview.",
                Map.of(),
                invalidCopybook,
                "preview",
                20,
                false,
                List.of(
                        new CobolDiscoveryAssistantService.ToolActionRequest("update_copybook_text", Map.of()),
                        new CobolDiscoveryAssistantService.ToolActionRequest("run_preview", Map.of())
                )
        );
        when(assistantService.planActions(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(plan);
        stubRespondStructured("Copybook invalid, fixing.", Map.of());

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "update copybook and run preview", Map.of());

        // Run should NOT be queued because copybook update was rejected
        verify(runRepository, never()).save(argThat(run -> "preview".equals(run.getRunType()) && "QUEUED".equals(run.getStatus())));
        assertNull(exchange.activeRun());
    }

    // ──────────────────────────────────────────────────────────────────
    // 14. Action plan execution — retrieve preview
    // ──────────────────────────────────────────────────────────────────

    @Test
    void postMessage_retrievesPreviewResultsWhenRequested() {
        stubBothArtifactsPresent();

        CobolDiscoveryRun latest = completedRun();
        when(runRepository.findBySessionIdOrderByCreatedAtDesc(SESSION_ID)).thenReturn(List.of(latest));

        CobolDiscoveryAssistantService.ActionPlan plan = new CobolDiscoveryAssistantService.ActionPlan(
                "Here are the preview results.",
                Map.of(),
                "",
                null,
                0,
                true,
                List.of(new CobolDiscoveryAssistantService.ToolActionRequest("retrieve_preview_results", Map.of()))
        );
        when(assistantService.planActions(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(plan);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "show preview results", Map.of());

        assertNotNull(exchange.previewSummary());
        assertFalse(exchange.previewSummary().isEmpty());
    }

    // ──────────────────────────────────────────────────────────────────
    // 15–20. Auto-execute from LLM structured reply
    // ──────────────────────────────────────────────────────────────────

    @Test
    void postMessage_autoExecutesWhenLlmReplyHasRecommendedConfigAndRunType() throws IOException {
        stubBothArtifactsPresent();
        stubRunSave();
        stubPlanActionsNone();

        lenient().when(copybookAnalyzer.validateSyntax(anyString()))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(true, null));

        Map<String, Object> metadata = Map.of(
                "recommendedConfig", Map.of("record_format", "V"),
                "recommendedRunType", "preview"
        );
        stubRespondStructured("Try V framing.", metadata);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "what should I try", Map.of());

        verify(runRepository).save(argThat(run -> "preview".equals(run.getRunType())));
        assertNotNull(exchange.activeRun());
    }

    @Test
    void postMessage_autoExecuteDefaultsToPreviewWhenRunTypeMissing() throws IOException {
        stubBothArtifactsPresent();
        stubRunSave();
        stubPlanActionsNone();

        lenient().when(copybookAnalyzer.validateSyntax(anyString()))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(true, null));

        // Metadata has config but no recommendedRunType
        Map<String, Object> metadata = Map.of(
                "recommendedConfig", Map.of("record_format", "V")
        );
        stubRespondStructured("Try V framing.", metadata);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "what should I try", Map.of());

        // Should default to preview
        verify(runRepository).save(argThat(run -> "preview".equals(run.getRunType())));
        assertNotNull(exchange.activeRun());
    }

    @Test
    void postMessage_skipsAutoExecuteWhenNoCopybookArtifact() {
        // Only data file, no copybook
        CobolDiscoveryArtifact dataFile = dataFileArtifact();
        when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                SESSION_ID, "copybook", "ACTIVE")).thenReturn(Optional.empty());
        when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                SESSION_ID, "data_file", "ACTIVE")).thenReturn(Optional.of(dataFile));

        stubPlanActionsNone();

        Map<String, Object> metadata = Map.of(
                "recommendedConfig", Map.of("record_format", "V"),
                "recommendedRunType", "preview"
        );
        stubRespondStructured("Try V framing.", metadata);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "what should I try", Map.of());

        verify(runRepository, never()).save(any(CobolDiscoveryRun.class));
        assertNull(exchange.activeRun());
    }

    @Test
    void postMessage_skipsAutoExecuteWhenNoDataFileArtifact() {
        stubCopybookOnly();
        stubPlanActionsNone();

        Map<String, Object> metadata = Map.of(
                "recommendedConfig", Map.of("record_format", "V"),
                "recommendedRunType", "preview"
        );
        stubRespondStructured("Try V framing.", metadata);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "what should I try", Map.of());

        verify(runRepository, never()).save(any(CobolDiscoveryRun.class));
        assertNull(exchange.activeRun());
    }

    @Test
    void postMessage_skipsAutoExecuteWhenRunAlreadyTriggeredByPlan() throws IOException {
        stubBothArtifactsPresent();
        stubRunSave();

        lenient().when(copybookAnalyzer.validateSyntax(anyString()))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(true, null));

        // Plan triggers a run
        CobolDiscoveryAssistantService.ActionPlan plan = new CobolDiscoveryAssistantService.ActionPlan(
                "Running preview.",
                Map.of(),
                "",
                "preview",
                20,
                false,
                List.of(new CobolDiscoveryAssistantService.ToolActionRequest("run_preview", Map.of()))
        );
        when(assistantService.planActions(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(plan);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "run preview", Map.of());

        // Only one run should be created (from plan), not duplicated by auto-execute
        verify(runRepository, times(1)).save(argThat(run -> "QUEUED".equals(run.getStatus())));
    }

    @Test
    void postMessage_autoExecuteAppliesCopybookTextFromReply() throws IOException {
        stubBothArtifactsPresent();
        stubRunSave();
        stubPlanActionsNone();

        lenient().when(copybookAnalyzer.validateSyntax(anyString()))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(true, null));

        CobolDiscoveryArtifact updatedArtifact = copybookArtifact();
        updatedArtifact.setId("art-auto-copybook");
        when(storageService.storeTextArtifact(anyString(), anyString(), eq("copybook"), anyString(), anyString(), any()))
                .thenReturn(updatedArtifact);

        String newCopybook = VALID_COPYBOOK;
        Map<String, Object> metadata = Map.of(
                "recommendedConfig", Map.of("record_format", "V"),
                "recommendedCopybookText", newCopybook,
                "recommendedRunType", "preview"
        );
        stubRespondStructured("Updated copybook and running preview.", metadata);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "fix it", Map.of());

        // The auto-execute path trims the copybook text, so we verify with anyString()
        verify(storageService).storeTextArtifact(eq(TENANT_ID), eq(SESSION_ID), eq("copybook"), anyString(), anyString(), any());
        assertTrue(exchange.toolActions().stream()
                .anyMatch(a -> "update_copybook_text".equals(a.name()) && "completed".equals(a.status())));
    }

    // ──────────────────────────────────────────────────────────────────
    // 21–22. Has planned response path
    // ──────────────────────────────────────────────────────────────────

    @Test
    void postMessage_usesActionPlanMessageWhenPlanHasActionsAndMessage() {
        stubBothArtifactsPresent();

        Map<String, Object> configOverrides = Map.of("record_format", "V");
        CobolDiscoveryAssistantService.ActionPlan plan = new CobolDiscoveryAssistantService.ActionPlan(
                "I applied your config changes successfully.",
                configOverrides,
                "",
                null,
                0,
                false,
                List.of(new CobolDiscoveryAssistantService.ToolActionRequest("update_config", configOverrides))
        );
        when(assistantService.planActions(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(plan);

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "apply config", Map.of());

        // Assistant message should match the plan message, not from respondStructured
        assertEquals("I applied your config changes successfully.", exchange.assistantMessage().getContent());
        // respondStructured should NOT have been called
        verify(assistantService, never()).respondStructured(any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any());
    }

    @Test
    void postMessage_callsRespondStructuredWhenPlanHasNoActionsAndNoMessage() {
        stubNoArtifacts();

        // ActionPlan.none() has blank message and no actions
        stubPlanActionsNone();
        stubRespondStructured("Here is my structured guidance.", Map.of());

        CobolDiscoveryService.MessageExchange exchange =
                service.postMessage(TENANT_ID, SESSION_ID, "help me", Map.of());

        verify(assistantService).respondStructured(
                any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any()
        );
        assertEquals("Here is my structured guidance.", exchange.assistantMessage().getContent());
    }
}
