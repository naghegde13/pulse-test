package com.pulse.cobol.controller;

import com.pulse.cobol.model.CobolDiscoveryArtifact;
import com.pulse.cobol.model.CobolDiscoveryMessage;
import com.pulse.cobol.model.CobolDiscoveryRun;
import com.pulse.cobol.model.CobolDiscoverySession;
import com.pulse.cobol.model.CobolParsingProfile;
import com.pulse.cobol.service.CobolDiscoveryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class CobolDiscoveryController {

    private final CobolDiscoveryService discoveryService;

    public CobolDiscoveryController(CobolDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @PostMapping("/ebcdic-discovery/sessions")
    public ResponseEntity<CobolDiscoverySession> createSession(
            @PathVariable String tenantId,
            @RequestBody CreateSessionRequest request) {
        return ResponseEntity.ok(discoveryService.createSession(tenantId, request.userId(), request.title()));
    }

    @GetMapping("/ebcdic-discovery/sessions/{sessionId}")
    public ResponseEntity<CobolDiscoverySession> getSession(
            @PathVariable String tenantId,
            @PathVariable String sessionId) {
        return ResponseEntity.ok(discoveryService.getSession(tenantId, sessionId));
    }

    @GetMapping("/ebcdic-discovery/sessions/{sessionId}/messages")
    public ResponseEntity<List<CobolDiscoveryMessage>> getMessages(
            @PathVariable String tenantId,
            @PathVariable String sessionId) {
        return ResponseEntity.ok(discoveryService.getMessages(tenantId, sessionId));
    }

    @GetMapping("/ebcdic-discovery/sessions/{sessionId}/monitor")
    public ResponseEntity<Map<String, Object>> getSessionMonitor(
            @PathVariable String tenantId,
            @PathVariable String sessionId) {
        return ResponseEntity.ok(discoveryService.getSessionMonitor(tenantId, sessionId));
    }

    @PostMapping("/ebcdic-discovery/sessions/{sessionId}/messages")
    public ResponseEntity<CobolDiscoveryService.MessageExchange> postMessage(
            @PathVariable String tenantId,
            @PathVariable String sessionId,
            @RequestBody MessageRequest request) {
        return ResponseEntity.ok(discoveryService.postMessage(
                tenantId,
                sessionId,
                request.content(),
                request.currentOptionOverrides()
        ));
    }

    @PostMapping("/ebcdic-discovery/sessions/{sessionId}/copybook")
    public ResponseEntity<CobolDiscoveryArtifact> uploadCopybook(
            @PathVariable String tenantId,
            @PathVariable String sessionId,
            @RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(discoveryService.uploadCopybook(tenantId, sessionId, file));
    }

    @PostMapping("/ebcdic-discovery/sessions/{sessionId}/data-file")
    public ResponseEntity<CobolDiscoveryArtifact> uploadDataFile(
            @PathVariable String tenantId,
            @PathVariable String sessionId,
            @RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(discoveryService.uploadDataFile(tenantId, sessionId, file));
    }

    @GetMapping("/ebcdic-discovery/sessions/{sessionId}/artifacts")
    public ResponseEntity<List<CobolDiscoveryArtifact>> getArtifacts(
            @PathVariable String tenantId,
            @PathVariable String sessionId) {
        return ResponseEntity.ok(discoveryService.getArtifacts(tenantId, sessionId));
    }

    @GetMapping("/ebcdic-discovery/sessions/{sessionId}/latest-run")
    public ResponseEntity<CobolDiscoveryRun> getLatestRunForSession(
            @PathVariable String tenantId,
            @PathVariable String sessionId) {
        CobolDiscoveryRun run = discoveryService.getLatestRunForSession(tenantId, sessionId);
        return run == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(run);
    }

    @GetMapping(value = "/ebcdic-discovery/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSession(
            @PathVariable String tenantId,
            @PathVariable String sessionId) {
        return discoveryService.streamSession(tenantId, sessionId);
    }

    @PostMapping("/ebcdic-discovery/sessions/{sessionId}/runs/preview")
    public ResponseEntity<CobolDiscoveryRun> startPreview(
            @PathVariable String tenantId,
            @PathVariable String sessionId,
            @RequestBody(required = false) RunRequest request) {
        RunRequest payload = request == null ? new RunRequest(Map.of(), 20, true) : request;
        return ResponseEntity.ok(discoveryService.queuePreviewRun(
                tenantId, sessionId, payload.optionOverrides(), payload.sampleRows(), payload.autoRefine()));
    }

    @PostMapping("/ebcdic-discovery/sessions/{sessionId}/runs/profile")
    public ResponseEntity<CobolDiscoveryRun> startProfileRun(
            @PathVariable String tenantId,
            @PathVariable String sessionId,
            @RequestBody(required = false) RunRequest request) {
        RunRequest payload = request == null ? new RunRequest(Map.of(), 50, false) : request;
        return ResponseEntity.ok(discoveryService.queueProfileRun(
                tenantId, sessionId, payload.optionOverrides(), payload.sampleRows()));
    }

    @GetMapping("/ebcdic-discovery/runs/{runId}")
    public ResponseEntity<CobolDiscoveryRun> getRun(
            @PathVariable String tenantId,
            @PathVariable String runId) {
        return ResponseEntity.ok(discoveryService.getRun(tenantId, runId));
    }

    @GetMapping("/ebcdic-discovery/runs/{runId}/events")
    public ResponseEntity<List<Map<String, Object>>> getRunEvents(
            @PathVariable String tenantId,
            @PathVariable String runId) {
        return ResponseEntity.ok(discoveryService.getRunEvents(tenantId, runId));
    }

    @GetMapping(value = "/ebcdic-discovery/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRun(
            @PathVariable String tenantId,
            @PathVariable String runId) {
        return discoveryService.streamRun(tenantId, runId);
    }

    @GetMapping("/ebcdic-discovery/runs/{runId}/preview-table")
    public ResponseEntity<Map<String, Object>> getPreviewTable(
            @PathVariable String tenantId,
            @PathVariable String runId) {
        return ResponseEntity.ok(discoveryService.getRunPreviewTable(tenantId, runId));
    }

    @GetMapping("/ebcdic-discovery/runs/{runId}/profiling")
    public ResponseEntity<Map<String, Object>> getRunProfiling(
            @PathVariable String tenantId,
            @PathVariable String runId) {
        return ResponseEntity.ok(discoveryService.getRunProfiling(tenantId, runId));
    }

    @PostMapping("/ebcdic-discovery/runs/{runId}/cancel")
    public ResponseEntity<CobolDiscoveryRun> cancelRun(
            @PathVariable String tenantId,
            @PathVariable String runId) {
        return ResponseEntity.ok(discoveryService.cancelRun(tenantId, runId));
    }

    @PostMapping("/cobol-profiles")
    public ResponseEntity<CobolParsingProfile> saveProfile(
            @PathVariable String tenantId,
            @RequestBody SaveProfileRequest request) throws IOException {
        return ResponseEntity.ok(discoveryService.saveProfile(
                tenantId,
                new CobolDiscoveryService.SaveProfileRequest(
                        request.runId(),
                        request.name(),
                        request.description(),
                        request.userId()
                )));
    }

    @GetMapping("/cobol-profiles")
    public ResponseEntity<List<CobolParsingProfile>> listProfiles(@PathVariable String tenantId) {
        return ResponseEntity.ok(discoveryService.listProfiles(tenantId));
    }

    @GetMapping("/cobol-profiles/{profileId}")
    public ResponseEntity<CobolParsingProfile> getProfile(
            @PathVariable String tenantId,
            @PathVariable String profileId) {
        return ResponseEntity.ok(discoveryService.getProfile(tenantId, profileId));
    }

    @PutMapping("/cobol-profiles/{profileId}")
    public ResponseEntity<CobolParsingProfile> updateProfile(
            @PathVariable String tenantId,
            @PathVariable String profileId,
            @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(discoveryService.updateProfile(
                tenantId,
                profileId,
                new CobolDiscoveryService.UpdateProfileRequest(
                        request.name(),
                        request.description(),
                        request.cobrixOptions(),
                        request.flattenSpec()
                )));
    }

    @PostMapping("/cobol-profiles/{profileId}/reprofile")
    public ResponseEntity<CobolDiscoverySession> reprofile(
            @PathVariable String tenantId,
            @PathVariable String profileId,
            @RequestBody(required = false) ReprofileRequest request) {
        String userId = request == null ? "01JUSER00000000000000000" : request.userId();
        return ResponseEntity.ok(discoveryService.reprofile(tenantId, profileId, userId));
    }

    public record CreateSessionRequest(String userId, String title) {}
    public record MessageRequest(String content, Map<String, Object> currentOptionOverrides) {}
    public record RunRequest(Map<String, Object> optionOverrides, int sampleRows, boolean autoRefine) {}
    public record SaveProfileRequest(String runId, String name, String description, String userId) {}
    public record UpdateProfileRequest(String name, String description, Map<String, Object> cobrixOptions, Map<String, Object> flattenSpec) {}
    public record ReprofileRequest(String userId) {}
}
