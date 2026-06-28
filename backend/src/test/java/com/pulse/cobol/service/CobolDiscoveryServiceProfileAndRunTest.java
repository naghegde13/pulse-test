package com.pulse.cobol.service;

import com.pulse.cobol.model.CobolDiscoveryArtifact;
import com.pulse.cobol.model.CobolDiscoveryMessage;
import com.pulse.cobol.model.CobolDiscoveryRun;
import com.pulse.cobol.model.CobolDiscoverySession;
import com.pulse.cobol.model.CobolParsingProfile;
import com.pulse.cobol.repository.CobolDiscoveryArtifactRepository;
import com.pulse.cobol.repository.CobolDiscoveryMessageRepository;
import com.pulse.cobol.repository.CobolDiscoveryRunRepository;
import com.pulse.cobol.repository.CobolDiscoverySessionRepository;
import com.pulse.cobol.repository.CobolParsingProfileRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CobolDiscoveryServiceProfileAndRunTest {

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

    private static final String TENANT = "tenant-1";
    private static final String SESSION_ID = "session-1";
    private static final String USER_ID = "user-1";

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

        // Lenient stubs for purge-related calls
        lenient().when(runRepository.findByExpiresAtBeforeAndCleanupStatus(any(Instant.class), anyString()))
                .thenReturn(List.of());

        // Generic save stubs — set an ID if null and return the argument
        lenient().when(runRepository.save(any(CobolDiscoveryRun.class))).thenAnswer(inv -> {
            CobolDiscoveryRun run = inv.getArgument(0);
            if (run.getId() == null) {
                run.setId("run-" + System.nanoTime());
                // Simulate @PrePersist for updatedAt which statusPayload needs
                try {
                    var method = run.getClass().getSuperclass().getDeclaredMethod("onCreate");
                    method.setAccessible(true);
                    method.invoke(run);
                } catch (Exception ignored) {}
            } else if (run.getUpdatedAt() == null) {
                try {
                    var method = run.getClass().getSuperclass().getDeclaredMethod("onCreate");
                    method.setAccessible(true);
                    method.invoke(run);
                } catch (Exception ignored) {}
            }
            return run;
        });
        lenient().when(sessionRepository.save(any(CobolDiscoverySession.class))).thenAnswer(inv -> {
            CobolDiscoverySession s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId("sess-" + System.nanoTime());
            }
            return s;
        });
        lenient().when(messageRepository.save(any(CobolDiscoveryMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(profileRepository.save(any(CobolParsingProfile.class))).thenAnswer(inv -> {
            CobolParsingProfile p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId("profile-" + System.nanoTime());
            }
            return p;
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private CobolDiscoverySession activeSession() {
        CobolDiscoverySession session = new CobolDiscoverySession();
        session.setId(SESSION_ID);
        session.setTenantId(TENANT);
        session.setUserId(USER_ID);
        session.setTitle("Test Session");
        session.setStatus("ACTIVE");
        return session;
    }

    private CobolDiscoveryArtifact copybookArtifact() {
        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setId("artifact-copybook");
        artifact.setSessionId(SESSION_ID);
        artifact.setTenantId(TENANT);
        artifact.setArtifactType("copybook");
        artifact.setOriginalFilename("test.cob");
        artifact.setStorageUri("/tmp/test.cob");
        artifact.setSha256("abc123");
        artifact.setSizeBytes(100);
        artifact.setCleanupStatus("ACTIVE");
        artifact.setExpiresAt(Instant.now().plusSeconds(3600));
        return artifact;
    }

    private CobolDiscoveryArtifact dataFileArtifact() {
        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setId("artifact-data");
        artifact.setSessionId(SESSION_ID);
        artifact.setTenantId(TENANT);
        artifact.setArtifactType("data_file");
        artifact.setOriginalFilename("data.dat");
        artifact.setStorageUri("/tmp/data.dat");
        artifact.setSha256("def456");
        artifact.setSizeBytes(1024);
        artifact.setCleanupStatus("ACTIVE");
        artifact.setExpiresAt(Instant.now().plusSeconds(3600));
        return artifact;
    }

    private CobolDiscoveryRun completedRun() {
        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setId("run-1");
        run.setSessionId(SESSION_ID);
        run.setTenantId(TENANT);
        run.setRunType("preview");
        run.setStatus("COMPLETED");
        run.setConfigSnapshot(Map.of("record_format", "F"));
        run.setProfilingSummary(Map.of("rowCount", 10, "flattenSpec", Map.of()));
        run.setAnomalySummary(Map.of("warnings", List.of()));
        run.setConfidenceScore(0.95);
        run.setSamplePolicy(Map.of("previewRows", 20));
        run.setResultSchemaSnapshot(Map.of("fields", List.of()));
        run.setPreviewRows(List.of(Map.of("col1", "val1")));
        run.setMappingSpec(List.of());
        run.setEventLog(List.of(Map.of("type", "completed")));
        // Simulate @PrePersist
        try {
            var method = run.getClass().getSuperclass().getDeclaredMethod("onCreate");
            method.setAccessible(true);
            method.invoke(run);
        } catch (Exception ignored) {}
        return run;
    }

    private void stubSessionLookup() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));
    }

    private void stubCopybookPresent() {
        when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                SESSION_ID, "copybook", "ACTIVE"))
                .thenReturn(Optional.of(copybookArtifact()));
    }

    private void stubDataFilePresent() {
        when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                SESSION_ID, "data_file", "ACTIVE"))
                .thenReturn(Optional.of(dataFileArtifact()));
    }

    private void stubNoCopybook() {
        when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                SESSION_ID, "copybook", "ACTIVE"))
                .thenReturn(Optional.empty());
    }

    private void stubNoDataFile() {
        when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                SESSION_ID, "data_file", "ACTIVE"))
                .thenReturn(Optional.empty());
    }

    // ── Upload artifacts ────────────────────────────────────────────────────

    @Test
    void uploadCopybook_delegatesToStorageService() throws IOException {
        stubSessionLookup();
        MultipartFile file = mock(MultipartFile.class);
        CobolDiscoveryArtifact stored = copybookArtifact();
        when(storageService.storeArtifact(eq(TENANT), eq(SESSION_ID), eq("copybook"), eq(file), any(Duration.class)))
                .thenReturn(stored);
        when(storageService.readText(stored)).thenReturn("01 ROOT. 05 F PIC X.");

        service.uploadCopybook(TENANT, SESSION_ID, file);

        verify(storageService).storeArtifact(eq(TENANT), eq(SESSION_ID), eq("copybook"), eq(file), any(Duration.class));
    }

    @Test
    void uploadCopybook_publishesCopybookUpdatedEvent() throws IOException {
        stubSessionLookup();
        MultipartFile file = mock(MultipartFile.class);
        CobolDiscoveryArtifact stored = copybookArtifact();
        when(storageService.storeArtifact(eq(TENANT), eq(SESSION_ID), eq("copybook"), eq(file), any(Duration.class)))
                .thenReturn(stored);
        when(storageService.readText(stored)).thenReturn("copybook-text");

        service.uploadCopybook(TENANT, SESSION_ID, file);

        verify(sessionStreamService).publish(eq(SESSION_ID), eq("copybook_updated"), anyMap());
    }

    @Test
    void uploadDataFile_delegatesToStorageService() throws IOException {
        stubSessionLookup();
        MultipartFile file = mock(MultipartFile.class);
        CobolDiscoveryArtifact stored = dataFileArtifact();
        when(storageService.storeArtifact(eq(TENANT), eq(SESSION_ID), eq("data_file"), eq(file), any(Duration.class)))
                .thenReturn(stored);

        service.uploadDataFile(TENANT, SESSION_ID, file);

        verify(storageService).storeArtifact(eq(TENANT), eq(SESSION_ID), eq("data_file"), eq(file), any(Duration.class));
    }

    @Test
    void updateCopybookText_validatesBeforePersisting() throws Exception {
        stubSessionLookup();
        // Use real CobolCopybookAnalyzer for syntax validation
        CobolDiscoveryService realAnalyzerService = new CobolDiscoveryService(
                sessionRepository, messageRepository, artifactRepository, runRepository,
                profileRepository, storageService, new CobolCopybookAnalyzer(),
                sparkPreviewService, dockerSparkPreviewService, assistantService,
                runStreamService, sessionStreamService
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> realAnalyzerService.updateCopybookText(TENANT, SESSION_ID, "test.cob",
                        """
                        01 ROOT-REC.
                           05 FIELD-A PIC X(4)
                           05 FIELD-B PIC X(2).
                        """)
        );

        assertTrue(ex.getMessage().toLowerCase().contains("syntax") || ex.getMessage().toLowerCase().contains("validation"));
        verify(storageService, never()).storeTextArtifact(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void updateCopybookText_persistsWhenValid() throws Exception {
        stubSessionLookup();
        String validCopybook = "01 ROOT-REC.\n   05 FIELD-A PIC X(4).\n   05 FIELD-B PIC X(2).";
        when(copybookAnalyzer.validateSyntax(validCopybook))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(true, ""));
        CobolDiscoveryArtifact stored = copybookArtifact();
        when(storageService.storeTextArtifact(eq(TENANT), eq(SESSION_ID), eq("copybook"), eq("test.cob"), eq(validCopybook), any(Duration.class)))
                .thenReturn(stored);

        service.updateCopybookText(TENANT, SESSION_ID, "test.cob", validCopybook);

        verify(storageService).storeTextArtifact(eq(TENANT), eq(SESSION_ID), eq("copybook"), eq("test.cob"), eq(validCopybook), any(Duration.class));
    }

    @Test
    void updateCopybookText_usesExistingFilenameWhenNotProvided() throws Exception {
        stubSessionLookup();
        String validCopybook = "01 ROOT. 05 F PIC X.";
        when(copybookAnalyzer.validateSyntax(validCopybook))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(true, ""));
        CobolDiscoveryArtifact existingCopybook = copybookArtifact();
        existingCopybook.setOriginalFilename("existing-name.cob");
        when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                SESSION_ID, "copybook", "ACTIVE"))
                .thenReturn(Optional.of(existingCopybook));
        CobolDiscoveryArtifact stored = copybookArtifact();
        when(storageService.storeTextArtifact(eq(TENANT), eq(SESSION_ID), eq("copybook"), eq("existing-name.cob"), eq(validCopybook), any(Duration.class)))
                .thenReturn(stored);

        service.updateCopybookText(TENANT, SESSION_ID, null, validCopybook);

        verify(storageService).storeTextArtifact(eq(TENANT), eq(SESSION_ID), eq("copybook"), eq("existing-name.cob"), eq(validCopybook), any(Duration.class));
    }

    @Test
    void updateCopybookText_usesDefaultFilenameWhenNoPriorArtifact() throws Exception {
        stubSessionLookup();
        String validCopybook = "01 ROOT. 05 F PIC X.";
        when(copybookAnalyzer.validateSyntax(validCopybook))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(true, ""));
        stubNoCopybook();
        CobolDiscoveryArtifact stored = copybookArtifact();
        when(storageService.storeTextArtifact(eq(TENANT), eq(SESSION_ID), eq("copybook"), eq("copybook.cob"), eq(validCopybook), any(Duration.class)))
                .thenReturn(stored);

        service.updateCopybookText(TENANT, SESSION_ID, null, validCopybook);

        verify(storageService).storeTextArtifact(eq(TENANT), eq(SESSION_ID), eq("copybook"), eq("copybook.cob"), eq(validCopybook), any(Duration.class));
    }

    @Test
    void updateCopybookText_publishesCopybookUpdatedEvent() throws Exception {
        stubSessionLookup();
        String validCopybook = "01 ROOT. 05 F PIC X.";
        when(copybookAnalyzer.validateSyntax(validCopybook))
                .thenReturn(new CobolCopybookAnalyzer.SyntaxValidation(true, ""));
        stubNoCopybook();
        CobolDiscoveryArtifact stored = copybookArtifact();
        when(storageService.storeTextArtifact(eq(TENANT), eq(SESSION_ID), eq("copybook"), eq("copybook.cob"), eq(validCopybook), any(Duration.class)))
                .thenReturn(stored);

        service.updateCopybookText(TENANT, SESSION_ID, null, validCopybook);

        verify(sessionStreamService).publish(eq(SESSION_ID), eq("copybook_updated"), anyMap());
    }

    @Test
    void getArtifacts_delegatesToRepository() {
        stubSessionLookup();
        List<CobolDiscoveryArtifact> expected = List.of(copybookArtifact(), dataFileArtifact());
        when(artifactRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(expected);

        List<CobolDiscoveryArtifact> result = service.getArtifacts(TENANT, SESSION_ID);

        assertEquals(expected, result);
        verify(artifactRepository).findBySessionIdOrderByCreatedAtAsc(SESSION_ID);
    }

    // ── Queue runs ──────────────────────────────────────────────────────────

    @Test
    void queuePreviewRun_requiresCopybookArtifact() {
        stubSessionLookup();
        stubNoCopybook();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.queuePreviewRun(TENANT, SESSION_ID, Map.of(), 20)
        );
        assertTrue(ex.getMessage().contains("Upload a copybook"));
    }

    @Test
    void queuePreviewRun_requiresDataFileArtifact() {
        stubSessionLookup();
        stubCopybookPresent();
        stubNoDataFile();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.queuePreviewRun(TENANT, SESSION_ID, Map.of(), 20)
        );
        assertTrue(ex.getMessage().contains("Upload an EBCDIC data file"));
    }

    @Test
    void queuePreviewRun_createsRunWithQueuedStatusAndPreviewType() {
        stubSessionLookup();
        stubCopybookPresent();
        stubDataFilePresent();

        CobolDiscoveryRun result = service.queuePreviewRun(TENANT, SESSION_ID, Map.of(), 20);

        assertNotNull(result);
        assertEquals("QUEUED", result.getStatus());
        assertEquals("preview", result.getRunType());
        verify(runRepository).save(any(CobolDiscoveryRun.class));
    }

    @Test
    void queuePreviewRun_setsAutoRefineInSamplePolicy() {
        stubSessionLookup();
        stubCopybookPresent();
        stubDataFilePresent();

        CobolDiscoveryRun result = service.queuePreviewRun(TENANT, SESSION_ID, Map.of(), 20, true);

        assertNotNull(result.getSamplePolicy());
        assertEquals(true, result.getSamplePolicy().get("assistantFollowUp"));
    }

    @Test
    void queuePreviewRun_publishesRunCreatedSseEvent() {
        stubSessionLookup();
        stubCopybookPresent();
        stubDataFilePresent();

        service.queuePreviewRun(TENANT, SESSION_ID, Map.of(), 20);

        verify(sessionStreamService).publish(eq(SESSION_ID), eq("run_created"), anyMap());
    }

    @Test
    void queuePreviewRun_publishesRunStatusSse() {
        stubSessionLookup();
        stubCopybookPresent();
        stubDataFilePresent();

        service.queuePreviewRun(TENANT, SESSION_ID, Map.of(), 20);

        verify(runStreamService).publish(anyString(), eq("run_status"), anyMap());
    }

    @Test
    void queueProfileRun_setsCorrectRunType() {
        stubSessionLookup();
        stubCopybookPresent();
        stubDataFilePresent();

        CobolDiscoveryRun result = service.queueProfileRun(TENANT, SESSION_ID, Map.of(), 50);

        assertNotNull(result);
        assertEquals("profile", result.getRunType());
    }

    @Test
    void queuePreviewRun_enforcesMinSampleRows() {
        stubSessionLookup();
        stubCopybookPresent();
        stubDataFilePresent();

        CobolDiscoveryRun result = service.queuePreviewRun(TENANT, SESSION_ID, Map.of(), 1);

        assertNotNull(result.getSamplePolicy());
        int previewRows = ((Number) result.getSamplePolicy().get("previewRows")).intValue();
        assertTrue(previewRows >= 5, "previewRows should be at least 5 but was " + previewRows);
    }

    // ── Run retrieval ───────────────────────────────────────────────────────

    @Test
    void getRun_returnsRunWhenFoundAndTenantMatches() {
        CobolDiscoveryRun run = completedRun();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        CobolDiscoveryRun result = service.getRun(TENANT, "run-1");

        assertNotNull(result);
        assertEquals("run-1", result.getId());
    }

    @Test
    void getRun_throwsResourceNotFoundWhenMissing() {
        when(runRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getRun(TENANT, "missing"));
    }

    @Test
    void getRun_throwsIllegalArgumentWhenTenantMismatch() {
        CobolDiscoveryRun run = completedRun();
        run.setTenantId("other-tenant");
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        assertThrows(IllegalArgumentException.class, () -> service.getRun(TENANT, "run-1"));
    }

    @Test
    void getRunPreviewTable_returnsStatusSchemaRowsMappingSpec() {
        CobolDiscoveryRun run = completedRun();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        Map<String, Object> result = service.getRunPreviewTable(TENANT, "run-1");

        assertTrue(result.containsKey("status"));
        assertTrue(result.containsKey("schema"));
        assertTrue(result.containsKey("rows"));
        assertTrue(result.containsKey("mappingSpec"));
    }

    @Test
    void getRunProfiling_returnsProfilingSummaryAnomalyConfidence() {
        CobolDiscoveryRun run = completedRun();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        Map<String, Object> result = service.getRunProfiling(TENANT, "run-1");

        assertTrue(result.containsKey("profilingSummary"));
        assertTrue(result.containsKey("anomalySummary"));
        assertTrue(result.containsKey("confidenceScore"));
    }

    @Test
    void getRunEvents_returnsEventLog() {
        CobolDiscoveryRun run = completedRun();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        List<Map<String, Object>> events = service.getRunEvents(TENANT, "run-1");

        assertNotNull(events);
        assertFalse(events.isEmpty());
    }

    @Test
    void getLatestRunForSession_returnsNullWhenNoRuns() {
        stubSessionLookup();
        when(runRepository.findBySessionIdOrderByCreatedAtDesc(SESSION_ID)).thenReturn(List.of());

        CobolDiscoveryRun result = service.getLatestRunForSession(TENANT, SESSION_ID);

        assertNull(result);
    }

    @Test
    void getLatestRunForSession_returnsLatestWhenPresent() {
        stubSessionLookup();
        CobolDiscoveryRun run = completedRun();
        when(runRepository.findBySessionIdOrderByCreatedAtDesc(SESSION_ID)).thenReturn(List.of(run));

        CobolDiscoveryRun result = service.getLatestRunForSession(TENANT, SESSION_ID);

        assertNotNull(result);
        assertEquals("run-1", result.getId());
    }

    @Test
    void cancelRun_setsStatusToCancelled() {
        CobolDiscoveryRun run = completedRun();
        run.setStatus("RUNNING");
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        CobolDiscoveryRun result = service.cancelRun(TENANT, "run-1");

        assertEquals("CANCELLED", result.getStatus());
        verify(runRepository).save(any(CobolDiscoveryRun.class));
    }

    @Test
    void cancelRun_appendsCancelledEvent() {
        CobolDiscoveryRun run = completedRun();
        run.setStatus("RUNNING");
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        CobolDiscoveryRun result = service.cancelRun(TENANT, "run-1");

        boolean hasCancelledEvent = result.getEventLog().stream()
                .anyMatch(e -> "cancelled".equals(e.get("type")));
        assertTrue(hasCancelledEvent, "Event log should contain a 'cancelled' event");
    }

    @Test
    void cancelRun_publishesRunStatusEvent() {
        CobolDiscoveryRun run = completedRun();
        run.setStatus("RUNNING");
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        service.cancelRun(TENANT, "run-1");

        verify(runStreamService).publish(eq("run-1"), eq("run_status"), anyMap());
    }

    // ── Save profile ────────────────────────────────────────────────────────

    @Test
    void saveProfile_persistsAllFields() throws IOException {
        CobolDiscoveryRun run = completedRun();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        stubSessionLookup();
        stubCopybookPresent();
        when(storageService.readText(any(CobolDiscoveryArtifact.class))).thenReturn("01 ROOT. 05 F PIC X.");
        when(artifactRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of(copybookArtifact()));

        CobolDiscoveryService.SaveProfileRequest request = new CobolDiscoveryService.SaveProfileRequest(
                "run-1", "My Profile", "Profile desc", USER_ID
        );
        service.saveProfile(TENANT, request);

        ArgumentCaptor<CobolParsingProfile> captor = ArgumentCaptor.forClass(CobolParsingProfile.class);
        verify(profileRepository).save(captor.capture());
        CobolParsingProfile saved = captor.getValue();

        assertEquals("My Profile", saved.getName());
        assertEquals("Profile desc", saved.getDescription());
        assertEquals(TENANT, saved.getTenantId());
        assertNotNull(saved.getCopybookContent());
        assertNotNull(saved.getCobrixOptions());
        assertNotNull(saved.getOutputSchemaSnapshot());
        assertNotNull(saved.getProfileQualitySummary());
        assertNotNull(saved.getMetadata());
        assertTrue(saved.getMetadata().containsKey("sourceSessionId"));
        assertTrue(saved.getMetadata().containsKey("sourceRunId"));
    }

    @Test
    void saveProfile_requiresCompletedRun() {
        CobolDiscoveryRun run = completedRun();
        run.setStatus("RUNNING");
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        CobolDiscoveryService.SaveProfileRequest request = new CobolDiscoveryService.SaveProfileRequest(
                "run-1", "My Profile", "desc", USER_ID
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.saveProfile(TENANT, request)
        );
        assertTrue(ex.getMessage().contains("completed"));
    }

    @Test
    void saveProfile_requiresNonBlankName() {
        CobolDiscoveryRun run = completedRun();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        CobolDiscoveryService.SaveProfileRequest request = new CobolDiscoveryService.SaveProfileRequest(
                "run-1", "   ", "desc", USER_ID
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.saveProfile(TENANT, request)
        );
        assertTrue(ex.getMessage().contains("name") || ex.getMessage().contains("Name"));
    }

    @Test
    void saveProfile_requiresNonNullName() {
        CobolDiscoveryRun run = completedRun();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        CobolDiscoveryService.SaveProfileRequest request = new CobolDiscoveryService.SaveProfileRequest(
                "run-1", null, "desc", USER_ID
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.saveProfile(TENANT, request)
        );
        assertTrue(ex.getMessage().contains("name") || ex.getMessage().contains("Name"));
    }

    @Test
    void saveProfile_requiresCopybookArtifact() throws IOException {
        CobolDiscoveryRun run = completedRun();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        stubSessionLookup();
        stubNoCopybook();

        CobolDiscoveryService.SaveProfileRequest request = new CobolDiscoveryService.SaveProfileRequest(
                "run-1", "My Profile", "desc", USER_ID
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.saveProfile(TENANT, request)
        );
        assertTrue(ex.getMessage().contains("copybook"));
    }

    @Test
    void saveProfile_cleansUpSessionArtifacts() throws IOException {
        CobolDiscoveryRun run = completedRun();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        stubSessionLookup();
        stubCopybookPresent();
        when(storageService.readText(any(CobolDiscoveryArtifact.class))).thenReturn("01 ROOT. 05 F PIC X.");
        CobolDiscoveryArtifact activeArtifact = copybookArtifact();
        when(artifactRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of(activeArtifact));

        CobolDiscoveryService.SaveProfileRequest request = new CobolDiscoveryService.SaveProfileRequest(
                "run-1", "My Profile", "desc", USER_ID
        );
        service.saveProfile(TENANT, request);

        verify(storageService).cleanupArtifact(activeArtifact);
    }

    @Test
    void saveProfile_setsSessionStatusToProfileSaved() throws IOException {
        CobolDiscoveryRun run = completedRun();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        stubSessionLookup();
        stubCopybookPresent();
        when(storageService.readText(any(CobolDiscoveryArtifact.class))).thenReturn("01 ROOT. 05 F PIC X.");
        when(artifactRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of(copybookArtifact()));

        CobolDiscoveryService.SaveProfileRequest request = new CobolDiscoveryService.SaveProfileRequest(
                "run-1", "My Profile", "desc", USER_ID
        );
        service.saveProfile(TENANT, request);

        ArgumentCaptor<CobolDiscoverySession> sessionCaptor = ArgumentCaptor.forClass(CobolDiscoverySession.class);
        // sessionRepository.save is called (at least once for the profile save flow)
        verify(sessionRepository, atLeastOnce()).save(sessionCaptor.capture());
        boolean hasProfileSaved = sessionCaptor.getAllValues().stream()
                .anyMatch(s -> "PROFILE_SAVED".equals(s.getStatus()));
        assertTrue(hasProfileSaved, "Session should have status PROFILE_SAVED");
    }

    // ── Update profile ──────────────────────────────────────────────────────

    @Test
    void updateProfile_updatesNameWhenProvided() {
        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setId("profile-1");
        profile.setTenantId(TENANT);
        profile.setName("Old Name");
        profile.setDescription("desc");
        profile.setCobrixOptions(Map.of());
        profile.setFlattenSpec(Map.of());
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));

        CobolDiscoveryService.UpdateProfileRequest request = new CobolDiscoveryService.UpdateProfileRequest(
                "New Name", null, null, null
        );
        service.updateProfile(TENANT, "profile-1", request);

        ArgumentCaptor<CobolParsingProfile> captor = ArgumentCaptor.forClass(CobolParsingProfile.class);
        verify(profileRepository).save(captor.capture());
        assertEquals("New Name", captor.getValue().getName());
    }

    @Test
    void updateProfile_updatesDescriptionWhenProvided() {
        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setId("profile-1");
        profile.setTenantId(TENANT);
        profile.setName("Name");
        profile.setDescription("Old desc");
        profile.setCobrixOptions(Map.of());
        profile.setFlattenSpec(Map.of());
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));

        CobolDiscoveryService.UpdateProfileRequest request = new CobolDiscoveryService.UpdateProfileRequest(
                null, "New description", null, null
        );
        service.updateProfile(TENANT, "profile-1", request);

        ArgumentCaptor<CobolParsingProfile> captor = ArgumentCaptor.forClass(CobolParsingProfile.class);
        verify(profileRepository).save(captor.capture());
        assertEquals("New description", captor.getValue().getDescription());
    }

    @Test
    void updateProfile_updatesCobrixOptionsWhenProvided() {
        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setId("profile-1");
        profile.setTenantId(TENANT);
        profile.setName("Name");
        profile.setDescription("desc");
        profile.setCobrixOptions(Map.of("old_key", "old_val"));
        profile.setFlattenSpec(Map.of());
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));

        Map<String, Object> newOptions = Map.of("record_format", "V");
        CobolDiscoveryService.UpdateProfileRequest request = new CobolDiscoveryService.UpdateProfileRequest(
                null, null, newOptions, null
        );
        service.updateProfile(TENANT, "profile-1", request);

        ArgumentCaptor<CobolParsingProfile> captor = ArgumentCaptor.forClass(CobolParsingProfile.class);
        verify(profileRepository).save(captor.capture());
        assertEquals(newOptions, captor.getValue().getCobrixOptions());
    }

    @Test
    void updateProfile_updatesFlattenSpecWhenProvided() {
        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setId("profile-1");
        profile.setTenantId(TENANT);
        profile.setName("Name");
        profile.setDescription("desc");
        profile.setCobrixOptions(Map.of());
        profile.setFlattenSpec(Map.of("old", "spec"));
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));

        Map<String, Object> newSpec = Map.of("new", "spec");
        CobolDiscoveryService.UpdateProfileRequest request = new CobolDiscoveryService.UpdateProfileRequest(
                null, null, null, newSpec
        );
        service.updateProfile(TENANT, "profile-1", request);

        ArgumentCaptor<CobolParsingProfile> captor = ArgumentCaptor.forClass(CobolParsingProfile.class);
        verify(profileRepository).save(captor.capture());
        assertEquals(newSpec, captor.getValue().getFlattenSpec());
    }

    @Test
    void updateProfile_skipsBlankName() {
        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setId("profile-1");
        profile.setTenantId(TENANT);
        profile.setName("Original Name");
        profile.setDescription("desc");
        profile.setCobrixOptions(Map.of());
        profile.setFlattenSpec(Map.of());
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));

        CobolDiscoveryService.UpdateProfileRequest request = new CobolDiscoveryService.UpdateProfileRequest(
                "   ", null, null, null
        );
        service.updateProfile(TENANT, "profile-1", request);

        ArgumentCaptor<CobolParsingProfile> captor = ArgumentCaptor.forClass(CobolParsingProfile.class);
        verify(profileRepository).save(captor.capture());
        assertEquals("Original Name", captor.getValue().getName());
    }

    @Test
    void updateProfile_throwsWhenProfileNotFound() {
        when(profileRepository.findById("missing")).thenReturn(Optional.empty());

        CobolDiscoveryService.UpdateProfileRequest request = new CobolDiscoveryService.UpdateProfileRequest(
                "Name", null, null, null
        );
        assertThrows(ResourceNotFoundException.class,
                () -> service.updateProfile(TENANT, "missing", request));
    }

    @Test
    void updateProfile_throwsWhenTenantMismatch() {
        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setId("profile-1");
        profile.setTenantId("other-tenant");
        profile.setName("Name");
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));

        CobolDiscoveryService.UpdateProfileRequest request = new CobolDiscoveryService.UpdateProfileRequest(
                "Name", null, null, null
        );
        assertThrows(IllegalArgumentException.class,
                () -> service.updateProfile(TENANT, "profile-1", request));
    }

    // ── Reprofile ───────────────────────────────────────────────────────────

    @Test
    void reprofile_createsNewSessionWithCorrectTitle() {
        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setId("profile-1");
        profile.setTenantId(TENANT);
        profile.setName("My COBOL Profile");
        profile.setCopybookContent("01 ROOT. 05 F PIC X.");
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));
        when(assistantService.initialGreeting()).thenReturn("Hello");

        CobolDiscoverySession result = service.reprofile(TENANT, "profile-1", USER_ID);

        assertNotNull(result);
        assertTrue(result.getTitle().contains("Reprofile"));
        assertTrue(result.getTitle().contains("My COBOL Profile"));
    }

    @Test
    void reprofile_stagesCopybookFromProfile() throws IOException {
        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setId("profile-1");
        profile.setTenantId(TENANT);
        profile.setName("My Profile");
        profile.setCopybookContent("01 ROOT. 05 F PIC X.");
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));
        when(assistantService.initialGreeting()).thenReturn("Hello");

        service.reprofile(TENANT, "profile-1", USER_ID);

        verify(storageService).storeTextArtifact(
                eq(TENANT), anyString(), eq("copybook"), eq("My Profile.cpy"),
                eq("01 ROOT. 05 F PIC X."), any(Duration.class)
        );
    }

    @Test
    void reprofile_createsGreetingMessageReferencingProfileName() throws IOException {
        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setId("profile-1");
        profile.setTenantId(TENANT);
        profile.setName("My Profile");
        profile.setCopybookContent("01 ROOT. 05 F PIC X.");
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));
        when(assistantService.initialGreeting()).thenReturn("Hello");

        service.reprofile(TENANT, "profile-1", USER_ID);

        // The reprofile method creates two messages:
        // 1. initial greeting from createSession
        // 2. the reprofile-specific message mentioning the profile name
        ArgumentCaptor<CobolDiscoveryMessage> msgCaptor = ArgumentCaptor.forClass(CobolDiscoveryMessage.class);
        verify(messageRepository, atLeast(2)).save(msgCaptor.capture());
        boolean hasProfileNameMention = msgCaptor.getAllValues().stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("My Profile"));
        assertTrue(hasProfileNameMention, "At least one message should reference the profile name");
    }

    // ── Purge expired state ─────────────────────────────────────────────────

    @Test
    void purgeExpiredState_marksExpiredRunsAsExpired() {
        CobolDiscoveryRun queuedRun = completedRun();
        queuedRun.setStatus("QUEUED");
        queuedRun.setCleanupStatus("ACTIVE");
        when(runRepository.findByExpiresAtBeforeAndCleanupStatus(any(Instant.class), eq("ACTIVE")))
                .thenReturn(List.of(queuedRun));

        service.purgeExpiredState();

        ArgumentCaptor<CobolDiscoveryRun> captor = ArgumentCaptor.forClass(CobolDiscoveryRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        CobolDiscoveryRun saved = captor.getAllValues().stream()
                .filter(r -> "run-1".equals(r.getId()))
                .findFirst().orElseThrow();
        assertEquals("EXPIRED", saved.getStatus());
        assertEquals("EXPIRED", saved.getCleanupStatus());
    }

    @Test
    void purgeExpiredState_preservesCompletedRunStatusOnExpiry() {
        CobolDiscoveryRun completedRun = completedRun();
        completedRun.setCleanupStatus("ACTIVE");
        when(runRepository.findByExpiresAtBeforeAndCleanupStatus(any(Instant.class), eq("ACTIVE")))
                .thenReturn(List.of(completedRun));

        service.purgeExpiredState();

        ArgumentCaptor<CobolDiscoveryRun> captor = ArgumentCaptor.forClass(CobolDiscoveryRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        CobolDiscoveryRun saved = captor.getAllValues().stream()
                .filter(r -> "run-1".equals(r.getId()))
                .findFirst().orElseThrow();
        assertEquals("COMPLETED", saved.getStatus());
        assertEquals("EXPIRED", saved.getCleanupStatus());
    }

    @Test
    void purgeExpiredState_delegatesArtifactPurgeToStorageService() {
        when(runRepository.findByExpiresAtBeforeAndCleanupStatus(any(Instant.class), eq("ACTIVE")))
                .thenReturn(List.of());

        service.purgeExpiredState();

        verify(storageService).purgeExpiredArtifacts();
    }
}
