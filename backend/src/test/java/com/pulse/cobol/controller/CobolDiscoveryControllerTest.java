package com.pulse.cobol.controller;

import com.pulse.cobol.model.CobolDiscoveryArtifact;
import com.pulse.cobol.model.CobolDiscoveryMessage;
import com.pulse.cobol.model.CobolDiscoveryRun;
import com.pulse.cobol.model.CobolDiscoverySession;
import com.pulse.cobol.model.CobolParsingProfile;
import com.pulse.cobol.service.CobolDiscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CobolDiscoveryControllerTest {

    @Mock private CobolDiscoveryService discoveryService;

    @InjectMocks
    private CobolDiscoveryController controller;

    // -----------------------------------------------------------------------
    //  Session endpoints
    // -----------------------------------------------------------------------

    @Test
    void createSession_delegatesAndReturns200() {
        CobolDiscoverySession session = buildSession("s1");
        when(discoveryService.createSession("t1", "user1", "My Session"))
                .thenReturn(session);

        ResponseEntity<CobolDiscoverySession> response = controller.createSession(
                "t1",
                new CobolDiscoveryController.CreateSessionRequest("user1", "My Session"));

        assertEquals(200, response.getStatusCode().value());
        assertSame(session, response.getBody());
        verify(discoveryService).createSession("t1", "user1", "My Session");
    }

    @Test
    void getSession_delegatesAndReturns200() {
        CobolDiscoverySession session = buildSession("s1");
        when(discoveryService.getSession("t1", "s1")).thenReturn(session);

        ResponseEntity<CobolDiscoverySession> response = controller.getSession("t1", "s1");

        assertEquals(200, response.getStatusCode().value());
        assertSame(session, response.getBody());
        verify(discoveryService).getSession("t1", "s1");
    }

    @Test
    void getMessages_delegatesAndReturns200() {
        CobolDiscoveryMessage msg = buildMessage("m1");
        when(discoveryService.getMessages("t1", "s1")).thenReturn(List.of(msg));

        ResponseEntity<List<CobolDiscoveryMessage>> response = controller.getMessages("t1", "s1");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        verify(discoveryService).getMessages("t1", "s1");
    }

    @Test
    void getSessionMonitor_delegatesAndReturns200() {
        Map<String, Object> monitor = Map.of("session", Map.of("id", "s1"));
        when(discoveryService.getSessionMonitor("t1", "s1")).thenReturn(monitor);

        ResponseEntity<Map<String, Object>> response = controller.getSessionMonitor("t1", "s1");

        assertEquals(200, response.getStatusCode().value());
        assertSame(monitor, response.getBody());
        verify(discoveryService).getSessionMonitor("t1", "s1");
    }

    @Test
    void postMessage_delegatesAndReturns200() {
        Map<String, Object> overrides = Map.of("record_format", "V");
        CobolDiscoveryService.MessageExchange exchange = new CobolDiscoveryService.MessageExchange(
                buildMessage("m1"), buildMessage("m2"), overrides, List.of(), null, Map.of());
        when(discoveryService.postMessage("t1", "s1", "hello", overrides)).thenReturn(exchange);

        ResponseEntity<CobolDiscoveryService.MessageExchange> response = controller.postMessage(
                "t1", "s1",
                new CobolDiscoveryController.MessageRequest("hello", overrides));

        assertEquals(200, response.getStatusCode().value());
        assertSame(exchange, response.getBody());
        verify(discoveryService).postMessage("t1", "s1", "hello", overrides);
    }

    // -----------------------------------------------------------------------
    //  Artifact endpoints
    // -----------------------------------------------------------------------

    @Test
    void uploadCopybook_delegatesAndReturns200() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "test.cob", "text/plain", "01 REC.".getBytes());
        CobolDiscoveryArtifact artifact = buildArtifact("a1");
        when(discoveryService.uploadCopybook("t1", "s1", file)).thenReturn(artifact);

        ResponseEntity<CobolDiscoveryArtifact> response = controller.uploadCopybook("t1", "s1", file);

        assertEquals(200, response.getStatusCode().value());
        assertSame(artifact, response.getBody());
        verify(discoveryService).uploadCopybook("t1", "s1", file);
    }

    @Test
    void uploadDataFile_delegatesAndReturns200() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "data.dat", "application/octet-stream", new byte[]{0x01, 0x02});
        CobolDiscoveryArtifact artifact = buildArtifact("a2");
        when(discoveryService.uploadDataFile("t1", "s1", file)).thenReturn(artifact);

        ResponseEntity<CobolDiscoveryArtifact> response = controller.uploadDataFile("t1", "s1", file);

        assertEquals(200, response.getStatusCode().value());
        assertSame(artifact, response.getBody());
        verify(discoveryService).uploadDataFile("t1", "s1", file);
    }

    @Test
    void getArtifacts_delegatesAndReturns200() {
        CobolDiscoveryArtifact artifact = buildArtifact("a1");
        when(discoveryService.getArtifacts("t1", "s1")).thenReturn(List.of(artifact));

        ResponseEntity<List<CobolDiscoveryArtifact>> response = controller.getArtifacts("t1", "s1");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        verify(discoveryService).getArtifacts("t1", "s1");
    }

    // -----------------------------------------------------------------------
    //  Run endpoints
    // -----------------------------------------------------------------------

    @Test
    void startPreview_delegatesWithDefaultsWhenRequestNull() {
        CobolDiscoveryRun run = buildRun("r1");
        when(discoveryService.queuePreviewRun("t1", "s1", Map.of(), 20, true)).thenReturn(run);

        ResponseEntity<CobolDiscoveryRun> response = controller.startPreview("t1", "s1", null);

        assertEquals(200, response.getStatusCode().value());
        assertSame(run, response.getBody());
        verify(discoveryService).queuePreviewRun("t1", "s1", Map.of(), 20, true);
    }

    @Test
    void startPreview_delegatesWithProvidedOverrides() {
        Map<String, Object> overrides = Map.of("record_format", "F");
        CobolDiscoveryRun run = buildRun("r1");
        when(discoveryService.queuePreviewRun("t1", "s1", overrides, 50, false)).thenReturn(run);

        ResponseEntity<CobolDiscoveryRun> response = controller.startPreview(
                "t1", "s1",
                new CobolDiscoveryController.RunRequest(overrides, 50, false));

        assertEquals(200, response.getStatusCode().value());
        assertSame(run, response.getBody());
        verify(discoveryService).queuePreviewRun("t1", "s1", overrides, 50, false);
    }

    @Test
    void startProfileRun_delegatesWithDefaultsWhenRequestNull() {
        CobolDiscoveryRun run = buildRun("r1");
        when(discoveryService.queueProfileRun("t1", "s1", Map.of(), 50)).thenReturn(run);

        ResponseEntity<CobolDiscoveryRun> response = controller.startProfileRun("t1", "s1", null);

        assertEquals(200, response.getStatusCode().value());
        assertSame(run, response.getBody());
        verify(discoveryService).queueProfileRun("t1", "s1", Map.of(), 50);
    }

    @Test
    void startProfileRun_delegatesWithProvidedOverrides() {
        Map<String, Object> overrides = Map.of("encoding", "cp037");
        CobolDiscoveryRun run = buildRun("r1");
        when(discoveryService.queueProfileRun("t1", "s1", overrides, 100)).thenReturn(run);

        ResponseEntity<CobolDiscoveryRun> response = controller.startProfileRun(
                "t1", "s1",
                new CobolDiscoveryController.RunRequest(overrides, 100, false));

        assertEquals(200, response.getStatusCode().value());
        assertSame(run, response.getBody());
        verify(discoveryService).queueProfileRun("t1", "s1", overrides, 100);
    }

    @Test
    void getRun_delegatesAndReturns200() {
        CobolDiscoveryRun run = buildRun("r1");
        when(discoveryService.getRun("t1", "r1")).thenReturn(run);

        ResponseEntity<CobolDiscoveryRun> response = controller.getRun("t1", "r1");

        assertEquals(200, response.getStatusCode().value());
        assertSame(run, response.getBody());
        verify(discoveryService).getRun("t1", "r1");
    }

    @Test
    void getRunEvents_delegatesAndReturns200() {
        List<Map<String, Object>> events = List.of(Map.of("type", "running"));
        when(discoveryService.getRunEvents("t1", "r1")).thenReturn(events);

        ResponseEntity<List<Map<String, Object>>> response = controller.getRunEvents("t1", "r1");

        assertEquals(200, response.getStatusCode().value());
        assertSame(events, response.getBody());
        verify(discoveryService).getRunEvents("t1", "r1");
    }

    @Test
    void getPreviewTable_delegatesAndReturns200() {
        Map<String, Object> table = Map.of("status", "COMPLETED", "rows", List.of());
        when(discoveryService.getRunPreviewTable("t1", "r1")).thenReturn(table);

        ResponseEntity<Map<String, Object>> response = controller.getPreviewTable("t1", "r1");

        assertEquals(200, response.getStatusCode().value());
        assertSame(table, response.getBody());
        verify(discoveryService).getRunPreviewTable("t1", "r1");
    }

    @Test
    void getRunProfiling_delegatesAndReturns200() {
        Map<String, Object> profiling = Map.of("confidenceScore", 0.95);
        when(discoveryService.getRunProfiling("t1", "r1")).thenReturn(profiling);

        ResponseEntity<Map<String, Object>> response = controller.getRunProfiling("t1", "r1");

        assertEquals(200, response.getStatusCode().value());
        assertSame(profiling, response.getBody());
        verify(discoveryService).getRunProfiling("t1", "r1");
    }

    @Test
    void cancelRun_delegatesAndReturns200() {
        CobolDiscoveryRun run = buildRun("r1");
        when(discoveryService.cancelRun("t1", "r1")).thenReturn(run);

        ResponseEntity<CobolDiscoveryRun> response = controller.cancelRun("t1", "r1");

        assertEquals(200, response.getStatusCode().value());
        assertSame(run, response.getBody());
        verify(discoveryService).cancelRun("t1", "r1");
    }

    @Test
    void getLatestRunForSession_returns204WhenNull() {
        when(discoveryService.getLatestRunForSession("t1", "s1")).thenReturn(null);

        ResponseEntity<CobolDiscoveryRun> response = controller.getLatestRunForSession("t1", "s1");

        assertEquals(204, response.getStatusCode().value());
        assertNull(response.getBody());
        verify(discoveryService).getLatestRunForSession("t1", "s1");
    }

    @Test
    void getLatestRunForSession_returns200WhenPresent() {
        CobolDiscoveryRun run = buildRun("r1");
        when(discoveryService.getLatestRunForSession("t1", "s1")).thenReturn(run);

        ResponseEntity<CobolDiscoveryRun> response = controller.getLatestRunForSession("t1", "s1");

        assertEquals(200, response.getStatusCode().value());
        assertSame(run, response.getBody());
        verify(discoveryService).getLatestRunForSession("t1", "s1");
    }

    // -----------------------------------------------------------------------
    //  SSE endpoints
    // -----------------------------------------------------------------------

    @Test
    void streamSession_delegatesAndReturnsSseEmitter() {
        SseEmitter emitter = new SseEmitter();
        when(discoveryService.streamSession("t1", "s1")).thenReturn(emitter);

        SseEmitter result = controller.streamSession("t1", "s1");

        assertSame(emitter, result);
        verify(discoveryService).streamSession("t1", "s1");
    }

    @Test
    void streamRun_delegatesAndReturnsSseEmitter() {
        SseEmitter emitter = new SseEmitter();
        when(discoveryService.streamRun("t1", "r1")).thenReturn(emitter);

        SseEmitter result = controller.streamRun("t1", "r1");

        assertSame(emitter, result);
        verify(discoveryService).streamRun("t1", "r1");
    }

    // -----------------------------------------------------------------------
    //  Profile endpoints
    // -----------------------------------------------------------------------

    @Test
    void saveProfile_delegatesAndReturns200() throws IOException {
        CobolParsingProfile profile = buildProfile("p1");
        CobolDiscoveryService.SaveProfileRequest serviceReq =
                new CobolDiscoveryService.SaveProfileRequest("r1", "MyProfile", "desc", "user1");
        when(discoveryService.saveProfile("t1", serviceReq)).thenReturn(profile);

        ResponseEntity<CobolParsingProfile> response = controller.saveProfile(
                "t1",
                new CobolDiscoveryController.SaveProfileRequest("r1", "MyProfile", "desc", "user1"));

        assertEquals(200, response.getStatusCode().value());
        assertSame(profile, response.getBody());
        verify(discoveryService).saveProfile("t1", serviceReq);
    }

    @Test
    void listProfiles_delegatesAndReturns200() {
        CobolParsingProfile profile = buildProfile("p1");
        when(discoveryService.listProfiles("t1")).thenReturn(List.of(profile));

        ResponseEntity<List<CobolParsingProfile>> response = controller.listProfiles("t1");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        verify(discoveryService).listProfiles("t1");
    }

    @Test
    void getProfile_delegatesAndReturns200() {
        CobolParsingProfile profile = buildProfile("p1");
        when(discoveryService.getProfile("t1", "p1")).thenReturn(profile);

        ResponseEntity<CobolParsingProfile> response = controller.getProfile("t1", "p1");

        assertEquals(200, response.getStatusCode().value());
        assertSame(profile, response.getBody());
        verify(discoveryService).getProfile("t1", "p1");
    }

    @Test
    void updateProfile_delegatesAndReturns200() {
        Map<String, Object> cobrixOpts = Map.of("record_format", "V");
        Map<String, Object> flattenSpec = Map.of("flatten", true);
        CobolParsingProfile profile = buildProfile("p1");
        CobolDiscoveryService.UpdateProfileRequest serviceReq =
                new CobolDiscoveryService.UpdateProfileRequest("NewName", "NewDesc", cobrixOpts, flattenSpec);
        when(discoveryService.updateProfile("t1", "p1", serviceReq)).thenReturn(profile);

        ResponseEntity<CobolParsingProfile> response = controller.updateProfile(
                "t1", "p1",
                new CobolDiscoveryController.UpdateProfileRequest("NewName", "NewDesc", cobrixOpts, flattenSpec));

        assertEquals(200, response.getStatusCode().value());
        assertSame(profile, response.getBody());
        verify(discoveryService).updateProfile("t1", "p1", serviceReq);
    }

    @Test
    void reprofile_delegatesWithDefaultUserIdWhenRequestNull() {
        CobolDiscoverySession session = buildSession("s1");
        when(discoveryService.reprofile("t1", "p1", "01JUSER00000000000000000")).thenReturn(session);

        ResponseEntity<CobolDiscoverySession> response = controller.reprofile("t1", "p1", null);

        assertEquals(200, response.getStatusCode().value());
        assertSame(session, response.getBody());
        verify(discoveryService).reprofile("t1", "p1", "01JUSER00000000000000000");
    }

    @Test
    void reprofile_delegatesWithProvidedUserId() {
        CobolDiscoverySession session = buildSession("s1");
        when(discoveryService.reprofile("t1", "p1", "custom-user")).thenReturn(session);

        ResponseEntity<CobolDiscoverySession> response = controller.reprofile(
                "t1", "p1",
                new CobolDiscoveryController.ReprofileRequest("custom-user"));

        assertEquals(200, response.getStatusCode().value());
        assertSame(session, response.getBody());
        verify(discoveryService).reprofile("t1", "p1", "custom-user");
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private CobolDiscoverySession buildSession(String id) {
        CobolDiscoverySession session = new CobolDiscoverySession();
        session.setId(id);
        session.setTenantId("t1");
        session.setUserId("user1");
        session.setTitle("Test Session");
        session.setStatus("ACTIVE");
        return session;
    }

    private CobolDiscoveryMessage buildMessage(String id) {
        CobolDiscoveryMessage msg = new CobolDiscoveryMessage();
        msg.setId(id);
        msg.setSessionId("s1");
        msg.setRole("USER");
        msg.setContent("hello");
        return msg;
    }

    private CobolDiscoveryRun buildRun(String id) {
        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setId(id);
        run.setSessionId("s1");
        run.setTenantId("t1");
        run.setRunType("preview");
        run.setStatus("QUEUED");
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

    private CobolDiscoveryArtifact buildArtifact(String id) {
        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setId(id);
        artifact.setSessionId("s1");
        artifact.setTenantId("t1");
        artifact.setArtifactType("copybook");
        artifact.setOriginalFilename("test.cob");
        artifact.setStorageUri("/tmp/test.cob");
        artifact.setSha256("abc123");
        artifact.setSizeBytes(100L);
        return artifact;
    }

    private CobolParsingProfile buildProfile(String id) {
        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setId(id);
        profile.setTenantId("t1");
        profile.setName("TestProfile");
        profile.setDescription("A test profile");
        profile.setCopybookContent("01 REC.");
        profile.setCobrixOptions(Map.of());
        profile.setFlattenSpec(Map.of());
        profile.setOutputSchemaSnapshot(Map.of());
        profile.setProfileQualitySummary(Map.of());
        profile.setMetadata(Map.of());
        return profile;
    }
}
