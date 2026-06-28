package com.pulse.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.chat.ChatStreamingException;
import com.pulse.chat.model.ChatMessage;
import com.pulse.chat.model.ChatSession;
import com.pulse.chat.service.ChatService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/tenants/{tenantId}/chat/sessions")
    public ResponseEntity<List<ChatSession>> listSessions(@PathVariable String tenantId) {
        return ResponseEntity.ok(chatService.listSessions(tenantId));
    }

    @PostMapping("/tenants/{tenantId}/chat/sessions")
    public ResponseEntity<ChatSession> createSession(
            @PathVariable String tenantId,
            @RequestBody Map<String, String> request) {
        String userId = request.getOrDefault("userId", "01JUSER00000000000000000");
        String pipelineId = request.get("pipelineId");
        String title = request.get("title");
        return ResponseEntity.ok(chatService.createSession(tenantId, userId, pipelineId, title));
    }

    @GetMapping("/tenants/{tenantId}/chat/sessions/latest")
    public ResponseEntity<ChatSession> getLatestSession(
            @PathVariable String tenantId,
            @RequestParam String userId) {
        return chatService.getLatestSession(tenantId, userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/chat/sessions/{sessionId}")
    public ResponseEntity<ChatSession> getSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(chatService.getSession(sessionId));
    }

    @GetMapping("/chat/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessage>> getMessages(@PathVariable String sessionId) {
        return ResponseEntity.ok(chatService.getMessages(sessionId));
    }

    @GetMapping("/chat/sessions/{sessionId}/facts")
    public ResponseEntity<List<Map<String, Object>>> getSessionFacts(@PathVariable String sessionId) {
        return ResponseEntity.ok(chatService.getSessionFacts(sessionId));
    }

    @PostMapping(value = "/chat/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> request) {
        String content = request.get("content");
        String tenantId = request.getOrDefault("tenantId", "tenant-home-lending");
        return chatService.sendMessage(sessionId, content, tenantId);
    }

    /**
     * Plan-Preview decision (ADR 0025 / IMPL-ui-composition Phase 4) — the
     * session-scoped {@code interruptBefore}-resume transport. Body:
     * {@code {"decision": "approve|modify|reject", "feedback": "..."}}.
     * approve → apply_plan path (canonical + Command-Log write); reject →
     * cancel/discard; modify → discard + mark for composer rebuild. Returns a
     * small JSON status.
     */
    @PostMapping("/chat/sessions/{sessionId}/plans/{planId}/decision")
    public ResponseEntity<Map<String, Object>> decidePlan(
            @PathVariable String sessionId,
            @PathVariable String planId,
            @RequestBody Map<String, String> request) {
        String decision = request.get("decision");
        String feedback = request.get("feedback");
        try {
            return ResponseEntity.ok(chatService.decidePlan(sessionId, planId, decision, feedback));
        } catch (IllegalArgumentException ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("planId", planId);
            err.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        }
    }

    /**
     * Restore a session to a turn (ADR 0025 §3 / IMPL-ui-composition Phase 7) —
     * the applied-turn <b>Restore</b> transport. Undo = restore the checkpoint
     * snapshot (NOT an inverse plan): truncates the chat back to the turn's anchor
     * message (the n8n {@code truncateMessagesAfter} analogue), restores the
     * version's composition from the per-turn snapshot, and resets the phase to a
     * fresh build baseline. Returns {@code {sessionId, anchorMessageId,
     * deletedMessageCount, phase, restoredGraph}}.
     */
    @PostMapping("/chat/sessions/{sessionId}/turns/{anchorMessageId}/restore")
    public ResponseEntity<Map<String, Object>> restoreToTurn(
            @PathVariable String sessionId,
            @PathVariable String anchorMessageId) {
        try {
            return ResponseEntity.ok(chatService.restoreToTurn(sessionId, anchorMessageId));
        } catch (IllegalArgumentException ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("sessionId", sessionId);
            err.put("anchorMessageId", anchorMessageId);
            err.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        }
    }

    // BUG-2026-05-25-57: chat-specific exception handlers must NOT fall
    // through to GlobalExceptionHandler. GlobalExceptionHandler returns
    // ProblemDetail (application/problem+json), which Spring cannot write to
    // an already-committed text/event-stream response — Spring then throws
    // HttpMessageNotWritableException and the operator sees a generic error.
    //
    // The streaming-time happy path emits SSE error frames from
    // ChatService#writeSseError (background thread). These handlers exist as
    // a defense-in-depth net for any synchronous failure raised on the
    // request thread BEFORE the SSE response is committed (e.g. session
    // lookup miss, request-body parse error escalated to a chat exception).
    // In that pre-stream state the response is still un-committed, so we
    // write an SSE error frame directly and close the stream cleanly.

    @ExceptionHandler(ChatStreamingException.class)
    public void handleChatStreamingException(ChatStreamingException ex,
                                             HttpServletResponse response) throws IOException {
        log.error("Chat streaming exception escaped to controller", ex);
        writeSseErrorResponse(response, ex.getCode(), ex.getMessage(), ex.getUpstreamBody(), ex);
    }

    @ExceptionHandler(IOException.class)
    public void handleStreamingIoException(IOException ex,
                                           HttpServletResponse response) throws IOException {
        log.error("Streaming IOException escaped to controller", ex);
        writeSseErrorResponse(response,
                ChatStreamingException.CODE_STREAM_INTERRUPTED,
                ex.getMessage(),
                null,
                ex);
    }

    private void writeSseErrorResponse(HttpServletResponse response,
                                       String code,
                                       String message,
                                       String upstreamBody,
                                       Throwable cause) throws IOException {
        if (response.isCommitted()) {
            // Already streaming — ChatService#writeSseError handled this path
            // on the background thread. Nothing left to do here.
            return;
        }
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code != null ? code : ChatStreamingException.CODE_STREAM_ERROR);
        payload.put("message", message != null ? message : (cause != null ? cause.getClass().getSimpleName() : "Chat error"));
        payload.put("cause", causeChain(cause));
        if (upstreamBody != null && !upstreamBody.isBlank()) {
            payload.put("upstream", upstreamBody);
        }
        String json = objectMapper.writeValueAsString(payload);

        try (PrintWriter w = response.getWriter()) {
            w.write("event: error\n");
            w.write("data: ");
            w.write(json);
            w.write("\n\n");
            w.flush();
        }
    }

    private static String causeChain(Throwable t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 5) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null) sb.append(": ").append(cur.getMessage());
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }
}
