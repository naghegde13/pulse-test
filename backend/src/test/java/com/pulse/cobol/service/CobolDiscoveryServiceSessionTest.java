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
import com.pulse.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CobolDiscoveryServiceSessionTest {

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

    @BeforeEach
    void setUp() {
        // Infrastructure stubs that most tests depend on but don't assert against
        lenient().when(runRepository.findByExpiresAtBeforeAndCleanupStatus(any(), anyString()))
                .thenReturn(List.of());
        lenient().when(sessionRepository.save(any(CobolDiscoverySession.class)))
                .thenAnswer(invocation -> {
                    CobolDiscoverySession s = invocation.getArgument(0);
                    if (s.getId() == null) s.setId("test-session-id");
                    return s;
                });
        lenient().when(messageRepository.save(any(CobolDiscoveryMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(assistantService.initialGreeting()).thenReturn("Hello, welcome!");

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
    }

    // ── Session lifecycle ───────────────────────────────────────────────

    @Test
    void createSession_setsActiveStatusAndSavesSession() {
        CobolDiscoverySession result = service.createSession("tenant-1", "user-1", "Test Session");

        assertEquals("ACTIVE", result.getStatus());
        assertEquals("tenant-1", result.getTenantId());
        verify(sessionRepository).save(any(CobolDiscoverySession.class));
    }

    @Test
    void createSession_usesDefaultTitleWhenNull() {
        CobolDiscoverySession result = service.createSession("tenant-1", "user-1", null);

        assertEquals("EBCDIC Discovery", result.getTitle());
    }

    @Test
    void createSession_usesDefaultTitleWhenBlank() {
        CobolDiscoverySession result = service.createSession("tenant-1", "user-1", "   ");

        assertEquals("EBCDIC Discovery", result.getTitle());
    }

    @Test
    void createSession_usesProvidedTitleWhenGiven() {
        CobolDiscoverySession result = service.createSession("tenant-1", "user-1", "My Session");

        assertEquals("My Session", result.getTitle());
    }

    @Test
    void createSession_usesDefaultUserIdWhenNull() {
        CobolDiscoverySession result = service.createSession("tenant-1", null, "Test");

        assertEquals("01JUSER00000000000000000", result.getUserId());
    }

    @Test
    void createSession_usesDefaultUserIdWhenBlank() {
        CobolDiscoverySession result = service.createSession("tenant-1", "  ", "Test");

        assertEquals("01JUSER00000000000000000", result.getUserId());
    }

    @Test
    void createSession_sendsInitialGreetingMessage() {
        when(assistantService.initialGreeting()).thenReturn("Welcome greeting text");

        service.createSession("tenant-1", "user-1", "Test");

        ArgumentCaptor<CobolDiscoveryMessage> captor = ArgumentCaptor.forClass(CobolDiscoveryMessage.class);
        verify(messageRepository).save(captor.capture());
        CobolDiscoveryMessage saved = captor.getValue();
        assertEquals("ASSISTANT", saved.getRole());
        assertEquals("Welcome greeting text", saved.getContent());
    }

    @Test
    void createSession_callsPurgeExpiredState() {
        service.createSession("tenant-1", "user-1", "Test");

        verify(runRepository).findByExpiresAtBeforeAndCleanupStatus(any(Instant.class), eq("ACTIVE"));
    }

    // ── Session retrieval & tenant isolation ────────────────────────────

    @Test
    void getSession_returnsSessionWhenFoundAndTenantMatches() {
        CobolDiscoverySession session = buildSession("session-1", "tenant-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        CobolDiscoverySession result = service.getSession("tenant-1", "session-1");

        assertEquals("session-1", result.getId());
        assertEquals("tenant-1", result.getTenantId());
    }

    @Test
    void getSession_throwsResourceNotFoundWhenMissing() {
        when(sessionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getSession("tenant-1", "missing"));
    }

    @Test
    void getSession_throwsIllegalArgumentWhenTenantMismatch() {
        CobolDiscoverySession session = buildSession("session-1", "tenant-other");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        assertThrows(IllegalArgumentException.class,
                () -> service.getSession("tenant-1", "session-1"));
    }

    // ── Message retrieval ───────────────────────────────────────────────

    @Test
    void getMessages_validatesSessionExistenceFirst() {
        when(sessionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getMessages("tenant-1", "missing"));

        verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(anyString());
    }

    @Test
    void getMessages_returnsOrderedSanitizedMessages() {
        CobolDiscoverySession session = buildSession("session-1", "tenant-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        CobolDiscoveryMessage m1 = buildMessage("session-1", "USER", "hello");
        CobolDiscoveryMessage m2 = buildMessage("session-1", "ASSISTANT", "world");
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("session-1"))
                .thenReturn(List.of(m1, m2));

        List<CobolDiscoveryMessage> result = service.getMessages("tenant-1", "session-1");

        assertEquals(2, result.size());
        assertEquals("USER", result.get(0).getRole());
        assertEquals("ASSISTANT", result.get(1).getRole());
        // sanitizeMessage normalizes metadata — verify it didn't blow up
        assertNotNull(result.get(0).getMetadata());
        assertNotNull(result.get(1).getMetadata());
    }

    // ── Session monitor ────────────────────────────────────────────────

    @Test
    void getSessionMonitor_returnsFullStateSnapshot() {
        stubMonitorSession("session-1", "tenant-1");
        stubEmptyMonitorDependencies("session-1");

        Map<String, Object> monitor = service.getSessionMonitor("tenant-1", "session-1");

        assertTrue(monitor.containsKey("session"));
        assertTrue(monitor.containsKey("messageCount"));
        assertTrue(monitor.containsKey("messages"));
        assertTrue(monitor.containsKey("runCount"));
        assertTrue(monitor.containsKey("runs"));
        assertTrue(monitor.containsKey("artifacts"));
        assertTrue(monitor.containsKey("hasCopybook"));
        assertTrue(monitor.containsKey("hasDataFile"));
        assertTrue(monitor.containsKey("copybookContent"));
        assertEquals(0, monitor.get("messageCount"));
        assertEquals(0, monitor.get("runCount"));
        assertFalse((Boolean) monitor.get("hasCopybook"));
        assertFalse((Boolean) monitor.get("hasDataFile"));
    }

    @Test
    void getSessionMonitor_includesLatestRunFieldsWhenRunsPresent() {
        stubMonitorSession("session-1", "tenant-1");
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("session-1"))
                .thenReturn(List.of());

        CobolDiscoveryRun run = buildRun("run-1", "session-1", "tenant-1", "preview", "COMPLETED");
        run.setConfigSnapshot(Map.of("record_format", "V"));
        when(runRepository.findBySessionIdOrderByCreatedAtDesc("session-1"))
                .thenReturn(List.of(run));

        stubEmptyArtifacts("session-1");

        Map<String, Object> monitor = service.getSessionMonitor("tenant-1", "session-1");

        assertEquals("run-1", monitor.get("latestRunId"));
        assertEquals("COMPLETED", monitor.get("latestRunStatus"));
        assertNotNull(monitor.get("latestRunConfig"));
    }

    @Test
    void getSessionMonitor_handlesMissingRunsGracefully() {
        stubMonitorSession("session-1", "tenant-1");
        stubEmptyMonitorDependencies("session-1");

        Map<String, Object> monitor = service.getSessionMonitor("tenant-1", "session-1");

        assertFalse(monitor.containsKey("latestRunId"));
        assertEquals(0, monitor.get("runCount"));
    }

    @Test
    void getSessionMonitor_includesCopybookContentWhenPresent() throws IOException {
        stubMonitorSession("session-1", "tenant-1");
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("session-1"))
                .thenReturn(List.of());
        when(runRepository.findBySessionIdOrderByCreatedAtDesc("session-1"))
                .thenReturn(List.of());

        CobolDiscoveryArtifact copybook = buildArtifact("art-1", "session-1", "tenant-1", "copybook");
        when(artifactRepository.findBySessionIdOrderByCreatedAtAsc("session-1"))
                .thenReturn(List.of(copybook));
        when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                "session-1", "copybook", "ACTIVE"))
                .thenReturn(Optional.of(copybook));
        when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                "session-1", "data_file", "ACTIVE"))
                .thenReturn(Optional.empty());
        when(storageService.readText(copybook))
                .thenReturn("01 ROOT-REC.\n   05 FIELD-A PIC X(4).");

        Map<String, Object> monitor = service.getSessionMonitor("tenant-1", "session-1");

        assertTrue((Boolean) monitor.get("hasCopybook"));
        assertEquals("01 ROOT-REC.\n   05 FIELD-A PIC X(4).", monitor.get("copybookContent"));
    }

    @Test
    void getSessionMonitor_returnsEmptyCopybookContentWhenNoCopybook() {
        stubMonitorSession("session-1", "tenant-1");
        stubEmptyMonitorDependencies("session-1");

        Map<String, Object> monitor = service.getSessionMonitor("tenant-1", "session-1");

        assertEquals("", monitor.get("copybookContent"));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private CobolDiscoverySession buildSession(String id, String tenantId) {
        CobolDiscoverySession session = new CobolDiscoverySession();
        session.setId(id);
        session.setTenantId(tenantId);
        session.setTitle("Test Session");
        session.setStatus("ACTIVE");
        return session;
    }

    private CobolDiscoveryMessage buildMessage(String sessionId, String role, String content) {
        CobolDiscoveryMessage msg = new CobolDiscoveryMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMetadata(Map.of());
        return msg;
    }

    private CobolDiscoveryRun buildRun(String id, String sessionId, String tenantId,
                                       String runType, String status) {
        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setId(id);
        run.setSessionId(sessionId);
        run.setTenantId(tenantId);
        run.setRunType(runType);
        run.setStatus(status);
        run.setConfigSnapshot(Map.of());
        run.setProfilingSummary(Map.of());
        run.setAnomalySummary(Map.of());
        run.setSamplePolicy(Map.of());
        run.setResultSchemaSnapshot(Map.of());
        run.setPreviewRows(List.of());
        run.setMappingSpec(List.of());
        run.setEventLog(List.of());
        return run;
    }

    private CobolDiscoveryArtifact buildArtifact(String id, String sessionId, String tenantId,
                                                  String artifactType) {
        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setId(id);
        artifact.setSessionId(sessionId);
        artifact.setTenantId(tenantId);
        artifact.setArtifactType(artifactType);
        artifact.setOriginalFilename("test." + (artifactType.equals("copybook") ? "cpy" : "dat"));
        artifact.setStorageUri("file:///tmp/" + id);
        artifact.setSha256("abc123");
        artifact.setSizeBytes(100L);
        return artifact;
    }

    private void stubMonitorSession(String sessionId, String tenantId) {
        CobolDiscoverySession session = buildSession(sessionId, tenantId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    }

    private void stubEmptyMonitorDependencies(String sessionId) {
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId))
                .thenReturn(List.of());
        when(runRepository.findBySessionIdOrderByCreatedAtDesc(sessionId))
                .thenReturn(List.of());
        stubEmptyArtifacts(sessionId);
    }

    private void stubEmptyArtifacts(String sessionId) {
        when(artifactRepository.findBySessionIdOrderByCreatedAtAsc(sessionId))
                .thenReturn(List.of());
        when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                sessionId, "copybook", "ACTIVE"))
                .thenReturn(Optional.empty());
        when(artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                sessionId, "data_file", "ACTIVE"))
                .thenReturn(Optional.empty());
    }
}
