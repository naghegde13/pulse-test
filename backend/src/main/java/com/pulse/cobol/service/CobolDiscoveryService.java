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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class CobolDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CobolDiscoveryService.class);

    private static final Duration ARTIFACT_TTL = Duration.ofHours(24);
    private static final Duration RUN_TTL = Duration.ofHours(24);
    private static final long RUN_TIMEOUT_SECONDS = 600;
    private static final int ASSISTANT_LOOP_MAX_ITERATIONS = 12;
    private static final int COPYBOOK_VALIDATION_RETRY_LIMIT = 3;
    private static final java.util.Set<String> SUPPORTED_OPTION_KEYS = java.util.Set.of(
            "record_format",
            "record_length",
            "records_per_block",
            "segment_field",
            "redefine_segment_id_map",
            "ebcdic_code_page",
            "schema_retention_policy",
            "drop_group_fillers",
            "drop_value_fillers",
            "variable_size_occurs",
            "debug",
            "is_rdw_big_endian",
            "rdw_adjustment",
            "is_bdw_big_endian",
            "bdw_adjustment",
            "is_record_sequence",
            "is_xcom",
            "improved_null_detection",
            "string_trimming_policy",
            "encoding",
            "record_length_field",
            "record_length_map",
            "field_code_page",
            "occurs_mapping"
    );

    private final CobolDiscoverySessionRepository sessionRepository;
    private final CobolDiscoveryMessageRepository messageRepository;
    private final CobolDiscoveryArtifactRepository artifactRepository;
    private final CobolDiscoveryRunRepository runRepository;
    private final CobolParsingProfileRepository profileRepository;
    private final CobolDiscoveryStorageService storageService;
    private final CobolCopybookAnalyzer copybookAnalyzer;
    private final CobolSparkPreviewService sparkPreviewService;
    private final CobolDockerSparkPreviewService dockerSparkPreviewService;
    private final CobolDiscoveryAssistantService assistantService;
    private final CobolDiscoveryRunStreamService runStreamService;
    private final CobolDiscoverySessionStreamService sessionStreamService;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final boolean useLocalSpark;

    public CobolDiscoveryService(
            CobolDiscoverySessionRepository sessionRepository,
            CobolDiscoveryMessageRepository messageRepository,
            CobolDiscoveryArtifactRepository artifactRepository,
            CobolDiscoveryRunRepository runRepository,
            CobolParsingProfileRepository profileRepository,
            CobolDiscoveryStorageService storageService,
            CobolCopybookAnalyzer copybookAnalyzer,
            CobolSparkPreviewService sparkPreviewService,
            CobolDockerSparkPreviewService dockerSparkPreviewService,
            CobolDiscoveryAssistantService assistantService,
            CobolDiscoveryRunStreamService runStreamService,
            CobolDiscoverySessionStreamService sessionStreamService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.artifactRepository = artifactRepository;
        this.runRepository = runRepository;
        this.profileRepository = profileRepository;
        this.storageService = storageService;
        this.copybookAnalyzer = copybookAnalyzer;
        this.sparkPreviewService = sparkPreviewService;
        this.dockerSparkPreviewService = dockerSparkPreviewService;
        this.assistantService = assistantService;
        this.runStreamService = runStreamService;
        this.sessionStreamService = sessionStreamService;
        String sparkMode = System.getenv("COBOL_SPARK_MODE");
        this.useLocalSpark = "local".equalsIgnoreCase(sparkMode);
        if (this.useLocalSpark) {
            log.info("EBCDIC discovery: using LOCAL in-process Spark (COBOL_SPARK_MODE=local)");
        } else {
            log.info("EBCDIC discovery: using Docker Spark container (COBOL_SPARK_MODE=docker)");
        }
    }

    public CobolDiscoverySession createSession(String tenantId, String userId, String title) {
        purgeExpiredState();
        CobolDiscoverySession session = new CobolDiscoverySession();
        session.setTenantId(tenantId);
        session.setUserId(userId == null || userId.isBlank() ? "01JUSER00000000000000000" : userId);
        session.setTitle(title == null || title.isBlank() ? "EBCDIC Discovery" : title);
        session.setStatus("ACTIVE");
        CobolDiscoverySession saved = sessionRepository.save(session);
        createAssistantMessage(saved.getId(), assistantService.initialGreeting());
        return saved;
    }

    public CobolDiscoverySession getSession(String tenantId, String sessionId) {
        purgeExpiredState();
        CobolDiscoverySession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("CobolDiscoverySession", sessionId));
        requireTenant(tenantId, session.getTenantId(), "session", sessionId);
        return session;
    }

    public List<CobolDiscoveryMessage> getMessages(String tenantId, String sessionId) {
        getSession(tenantId, sessionId);
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::sanitizeMessage)
                .toList();
    }

    public Map<String, Object> getSessionMonitor(String tenantId, String sessionId) {
        CobolDiscoverySession session = getSession(tenantId, sessionId);
        List<CobolDiscoveryMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::sanitizeMessage)
                .toList();
        List<CobolDiscoveryRun> runs = runRepository.findBySessionIdOrderByCreatedAtDesc(sessionId).stream()
                .map(this::sanitizeRun)
                .toList();
        List<CobolDiscoveryArtifact> artifacts = artifactRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        Optional<CobolDiscoveryArtifact> copybookArtifact = activeArtifact(sessionId, "copybook");
        String copybookContent = copybookArtifact.map(artifact -> {
            try { return storageService.readText(artifact); } catch (IOException e) { return ""; }
        }).orElse("");

        CobolDiscoveryRun latestRun = runs.isEmpty() ? null : runs.get(0);

        Map<String, Object> monitor = new LinkedHashMap<>();
        monitor.put("session", Map.of(
                "id", session.getId(),
                "tenantId", session.getTenantId(),
                "status", session.getStatus(),
                "title", session.getTitle(),
                "createdAt", session.getCreatedAt() == null ? "" : session.getCreatedAt().toString()
        ));
        monitor.put("messageCount", messages.size());
        monitor.put("messages", messages.stream().map(m -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", m.getId());
            entry.put("role", m.getRole());
            entry.put("content", m.getContent());
            entry.put("metadata", m.getMetadata());
            entry.put("createdAt", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString());
            return entry;
        }).toList());
        monitor.put("runCount", runs.size());
        monitor.put("runs", runs.stream().map(r -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", r.getId());
            entry.put("status", r.getStatus());
            entry.put("runType", r.getRunType());
            entry.put("configSnapshot", r.getConfigSnapshot());
            entry.put("confidenceScore", r.getConfidenceScore());
            entry.put("anomalySummary", r.getAnomalySummary());
            entry.put("profilingSummary", r.getProfilingSummary());
            entry.put("samplePolicy", r.getSamplePolicy());
            entry.put("previewRowCount", r.getPreviewRows() == null ? 0 : r.getPreviewRows().size());
            entry.put("previewRows", r.getPreviewRows());
            entry.put("eventLog", r.getEventLog());
            entry.put("createdAt", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString());
            return entry;
        }).toList());
        monitor.put("artifacts", artifacts.stream().map(this::sanitizeArtifact).toList());
        monitor.put("hasCopybook", copybookArtifact.isPresent());
        monitor.put("hasDataFile", activeArtifact(sessionId, "data_file").isPresent());
        monitor.put("copybookContent", copybookContent);
        if (latestRun != null) {
            monitor.put("latestRunId", latestRun.getId());
            monitor.put("latestRunStatus", latestRun.getStatus());
            monitor.put("latestRunConfig", latestRun.getConfigSnapshot());
        }
        return monitor;
    }

    public MessageExchange postMessage(String tenantId, String sessionId, String content, Map<String, Object> currentOptionOverrides) {
        CobolDiscoverySession session = getSession(tenantId, sessionId);
        CobolDiscoveryMessage userMessage = new CobolDiscoveryMessage();
        userMessage.setSessionId(session.getId());
        userMessage.setRole("USER");
        userMessage.setContent(content == null ? "" : content);
        userMessage.setSafePayloadOnly(true);
        userMessage.setExpiresAt(Instant.now().plus(ARTIFACT_TTL));
        CobolDiscoveryMessage savedUser = messageRepository.save(userMessage);

        Optional<CobolDiscoveryArtifact> copybookArtifact = activeArtifact(sessionId, "copybook");
        boolean hasCopybook = copybookArtifact.isPresent();
        boolean hasDataFile = activeArtifact(sessionId, "data_file").isPresent();
        CobolDiscoveryRun latestRun = runRepository.findBySessionIdOrderByCreatedAtDesc(sessionId)
                .stream()
                .findFirst()
                .orElse(null);
        List<CobolDiscoveryArtifact> artifacts = artifactRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<CobolDiscoveryMessage> recentMessages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<CobolParsingProfile> profiles = profileRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
        String copybookContent = copybookArtifact.map(artifact -> {
            try {
                return storageService.readText(artifact);
            } catch (IOException e) {
                return "";
            }
        }).orElse("");
        CobolDiscoveryAssistantService.ActionPlan actionPlan = assistantService.planActions(
                content,
                currentOptionOverrides == null ? Map.of() : currentOptionOverrides,
                latestRun,
                copybookContent,
                recentMessages,
                hasCopybook,
                hasDataFile
        );
        Map<String, Object> appliedOptionOverrides = new LinkedHashMap<>(
                currentOptionOverrides == null ? Map.of() : currentOptionOverrides
        );
        List<AssistantToolAction> executedActions = new ArrayList<>();
        CobolDiscoveryRun triggeredRun = null;
        Map<String, Object> previewSummary = Map.of();
        boolean hasPlannedResponse = actionPlan.hasActions() || (actionPlan.assistantMessage() != null && !actionPlan.assistantMessage().isBlank());
        String copybookUpdateError = null;

        if (actionPlan.hasActions()) {
            if (actionPlan.copybookText() != null && !actionPlan.copybookText().isBlank()) {
                try {
                    CobolDiscoveryArtifact updatedCopybook = updateCopybookText(tenantId, sessionId, null, actionPlan.copybookText());
                    copybookContent = actionPlan.copybookText();
                    executedActions.add(new AssistantToolAction(
                            "update_copybook_text",
                            "completed",
                            Map.of("copybookText", actionPlan.copybookText()),
                            Map.of(
                                    "artifactId", updatedCopybook.getId(),
                                    "originalFilename", updatedCopybook.getOriginalFilename(),
                                    "artifactType", updatedCopybook.getArtifactType()
                            )
                    ));
                } catch (IllegalArgumentException e) {
                    copybookUpdateError = e.getMessage();
                    executedActions.add(new AssistantToolAction(
                            "update_copybook_text",
                            "rejected",
                            Map.of("copybookText", actionPlan.copybookText()),
                            Map.of("error", copybookUpdateError)
                    ));
                } catch (IOException e) {
                    copybookUpdateError = e.getMessage();
                    executedActions.add(new AssistantToolAction(
                            "update_copybook_text",
                            "failed",
                            Map.of("copybookText", actionPlan.copybookText()),
                            Map.of("error", copybookUpdateError)
                    ));
                }
            }
            if (!actionPlan.optionOverrides().isEmpty()) {
                appliedOptionOverrides = normalizeConfigForCopybook(copybookContent, actionPlan.optionOverrides());
                executedActions.add(new AssistantToolAction(
                        "update_config",
                        "completed",
                        Map.of("optionOverrides", actionPlan.optionOverrides()),
                        Map.of("appliedOptionOverrides", appliedOptionOverrides)
                ));
            }
            if (actionPlan.retrievePreviewResults()) {
                previewSummary = summarizePreview(latestRun);
                executedActions.add(new AssistantToolAction(
                        "retrieve_preview_results",
                        latestRun == null ? "skipped" : "completed",
                        Map.of(),
                        previewSummary
                ));
            }
            if (copybookUpdateError == null && "preview".equals(actionPlan.runType())) {
                triggeredRun = queuePreviewRun(
                        tenantId,
                        sessionId,
                        appliedOptionOverrides,
                        Math.max(5, actionPlan.sampleRows()),
                        true
                );
                executedActions.add(new AssistantToolAction(
                        "run_preview",
                        "queued",
                        Map.of(
                                "optionOverrides", appliedOptionOverrides,
                                "sampleRows", Math.max(5, actionPlan.sampleRows())
                        ),
                        Map.of(
                                "runId", triggeredRun.getId(),
                                "status", triggeredRun.getStatus()
                        )
                ));
            } else if (copybookUpdateError == null && "profile".equals(actionPlan.runType())) {
                triggeredRun = queueProfileRun(
                        tenantId,
                        sessionId,
                        appliedOptionOverrides,
                        Math.max(5, actionPlan.sampleRows()),
                        true
                );
                executedActions.add(new AssistantToolAction(
                        "run_full_profile",
                        "queued",
                        Map.of(
                                "optionOverrides", appliedOptionOverrides,
                                "sampleRows", Math.max(5, actionPlan.sampleRows())
                        ),
                        Map.of(
                                "runId", triggeredRun.getId(),
                                "status", triggeredRun.getStatus()
                        )
                ));
            }
        }

        CobolDiscoveryAssistantService.AssistantReply assistantReply;
        if (copybookUpdateError != null) {
            // Copybook validation failed. Call the LLM with the error so it can
            // produce a corrected copybook that auto-execute can pick up.
            String errorContext = content + "\n\n[SYSTEM] Your previous copybook revision was rejected by Cobrix syntax validation: "
                    + copybookUpdateError + ". Revise the full raw copybook text and include it in recommended_copybook_text. "
                    + "Do NOT reuse the rejected text unchanged.";
            copybookUpdateError = null; // reset so auto-execute can fire below
            assistantReply = assistantService.respondStructured(
                    errorContext,
                    hasCopybook,
                    hasDataFile,
                    latestRun,
                    copybookContent,
                    recentMessages,
                    artifacts,
                    profiles
            );
        } else if (hasPlannedResponse) {
            assistantReply = new CobolDiscoveryAssistantService.AssistantReply(
                    actionPlan.assistantMessage(),
                    actionPlan.runType() == null
                            ? Map.of()
                            : Map.of("queuedRunType", actionPlan.runType())
            );
        } else {
            assistantReply = assistantService.respondStructured(
                    content,
                    hasCopybook,
                    hasDataFile,
                    latestRun,
                    copybookContent,
                    recentMessages,
                    artifacts,
                    profiles
            );
        }
        assistantReply = normalizeAssistantReply(copybookContent, assistantReply);

        // Auto-execute: when the LLM structured reply includes actionable recommendations
        // (config, copybook text, run type) that planActions didn't catch via keyword matching,
        // execute them now instead of just storing as metadata.
        if (triggeredRun == null && copybookUpdateError == null && hasCopybook && hasDataFile) {
            @SuppressWarnings("unchecked")
            Map<String, Object> replyConfig = assistantReply.metadata() != null
                    ? (Map<String, Object>) assistantReply.metadata().getOrDefault("recommendedConfig", Map.of())
                    : Map.of();
            String replyCopybookText = assistantReply.metadata() != null
                    ? String.valueOf(assistantReply.metadata().getOrDefault("recommendedCopybookText", "")).trim()
                    : "";
            String replyRunType = assistantReply.metadata() != null
                    ? String.valueOf(assistantReply.metadata().getOrDefault("recommendedRunType", "none"))
                    : "none";
            boolean hasReplyConfig = replyConfig != null && !replyConfig.isEmpty();
            boolean hasReplyCopybook = !replyCopybookText.isBlank();
            boolean wantsRun = "preview".equals(replyRunType) || "profile".equals(replyRunType);
            // Auto-execute even if the agent forgot to set recommended_run_type,
            // as long as it provided actionable config or copybook changes
            if ((hasReplyConfig || hasReplyCopybook) && !wantsRun) {
                log.info("Agent provided config/copybook but no run type for session {}; defaulting to preview", sessionId);
                wantsRun = true;
            }

            if ((hasReplyConfig || hasReplyCopybook) && wantsRun) {
                log.info("Auto-executing LLM structured reply recommendations for session {}", sessionId);
                if (hasReplyCopybook) {
                    try {
                        updateCopybookText(tenantId, sessionId, null, replyCopybookText);
                        copybookContent = replyCopybookText;
                        executedActions.add(new AssistantToolAction(
                                "update_copybook_text", "completed",
                                Map.of("copybookText", replyCopybookText),
                                Map.of("autoExecuted", true)
                        ));
                    } catch (IllegalArgumentException e) {
                        copybookUpdateError = e.getMessage();
                        executedActions.add(new AssistantToolAction(
                                "update_copybook_text", "rejected",
                                Map.of("copybookText", replyCopybookText),
                                Map.of("error", copybookUpdateError)
                        ));
                    } catch (IOException e) {
                        copybookUpdateError = e.getMessage();
                        executedActions.add(new AssistantToolAction(
                                "update_copybook_text", "failed",
                                Map.of("copybookText", replyCopybookText),
                                Map.of("error", copybookUpdateError)
                        ));
                    }
                }
                if (copybookUpdateError == null && hasReplyConfig) {
                    appliedOptionOverrides = normalizeConfigForCopybook(copybookContent, replyConfig);
                    executedActions.add(new AssistantToolAction(
                            "update_config", "completed",
                            Map.of("optionOverrides", replyConfig),
                            Map.of("appliedOptionOverrides", appliedOptionOverrides, "autoExecuted", true)
                    ));
                }
                if (copybookUpdateError == null) {
                    Map<String, Object> runConfig = appliedOptionOverrides.isEmpty()
                            ? (hasReplyConfig ? normalizeConfigForCopybook(copybookContent, replyConfig) : Map.of())
                            : appliedOptionOverrides;
                    triggeredRun = "profile".equals(replyRunType)
                            ? queueProfileRun(tenantId, sessionId, runConfig, 50, true)
                            : queuePreviewRun(tenantId, sessionId, runConfig, 20, true);
                    executedActions.add(new AssistantToolAction(
                            "profile".equals(replyRunType) ? "run_full_profile" : "run_preview",
                            "queued",
                            Map.of("optionOverrides", runConfig, "sampleRows", "profile".equals(replyRunType) ? 50 : 20),
                            Map.of("runId", triggeredRun.getId(), "status", triggeredRun.getStatus(), "autoExecuted", true)
                    ));
                }
            }
        }

        CobolDiscoveryMessage assistant = createAssistantMessage(
                sessionId,
                assistantReply.content(),
                assistantReply.metadata()
        );
        return new MessageExchange(
                sanitizeMessage(savedUser),
                sanitizeMessage(assistant),
                (Map<String, Object>) normalizeJsonGraph(appliedOptionOverrides),
                executedActions.stream().map(this::sanitizeToolAction).toList(),
                sanitizeRun(triggeredRun),
                (Map<String, Object>) normalizeJsonGraph(previewSummary)
        );
    }

    public CobolDiscoveryArtifact uploadCopybook(String tenantId, String sessionId, MultipartFile file) throws IOException {
        purgeExpiredState();
        getSession(tenantId, sessionId);
        CobolDiscoveryArtifact artifact = storageService.storeArtifact(tenantId, sessionId, "copybook", file, ARTIFACT_TTL);
        publishCopybookUpdated(sessionId, artifact, storageService.readText(artifact));
        return artifact;
    }

    public CobolDiscoveryArtifact uploadDataFile(String tenantId, String sessionId, MultipartFile file) throws IOException {
        purgeExpiredState();
        getSession(tenantId, sessionId);
        return storageService.storeArtifact(tenantId, sessionId, "data_file", file, ARTIFACT_TTL);
    }

    public CobolDiscoveryArtifact updateCopybookText(String tenantId, String sessionId, String filename, String copybookText) throws IOException {
        purgeExpiredState();
        getSession(tenantId, sessionId);
        String validationError = validateCopybookText(copybookText);
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }
        String targetFilename = filename;
        if (targetFilename == null || targetFilename.isBlank()) {
            targetFilename = activeArtifact(sessionId, "copybook")
                    .map(CobolDiscoveryArtifact::getOriginalFilename)
                    .orElse("copybook.cob");
        }
        CobolDiscoveryArtifact artifact = storageService.storeTextArtifact(
                tenantId,
                sessionId,
                "copybook",
                targetFilename,
                copybookText,
                ARTIFACT_TTL
        );
        publishCopybookUpdated(sessionId, artifact, copybookText);
        return artifact;
    }

    public List<CobolDiscoveryArtifact> getArtifacts(String tenantId, String sessionId) {
        getSession(tenantId, sessionId);
        return artifactRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    public CobolDiscoveryRun getLatestRunForSession(String tenantId, String sessionId) {
        getSession(tenantId, sessionId);
        return runRepository.findBySessionIdOrderByCreatedAtDesc(sessionId)
                .stream()
                .findFirst()
                .map(this::sanitizeRun)
                .orElse(null);
    }

    public CobolDiscoveryRun queuePreviewRun(String tenantId, String sessionId, Map<String, Object> overrides, int sampleRows) {
        return queuePreviewRun(tenantId, sessionId, overrides, sampleRows, true);
    }

    public CobolDiscoveryRun queuePreviewRun(String tenantId, String sessionId, Map<String, Object> overrides, int sampleRows, boolean autoRefine) {
        purgeExpiredState();
        return queueRun(
                tenantId,
                sessionId,
                overrides,
                sampleRows,
                "preview",
                autoRefine,
                autoRefine ? 1 : 0,
                autoRefine ? ASSISTANT_LOOP_MAX_ITERATIONS : 0
        );
    }

    public CobolDiscoveryRun queueProfileRun(String tenantId, String sessionId, Map<String, Object> overrides, int sampleRows) {
        return queueRun(tenantId, sessionId, overrides, sampleRows, "profile", false, 0, 0);
    }

    private CobolDiscoveryRun queueProfileRun(String tenantId, String sessionId, Map<String, Object> overrides, int sampleRows, boolean assistantFollowUp) {
        return queueRun(tenantId, sessionId, overrides, sampleRows, "profile", assistantFollowUp, 0, 0);
    }

    private CobolDiscoveryRun queueRun(
            String tenantId,
            String sessionId,
            Map<String, Object> overrides,
            int sampleRows,
            String runType,
            boolean assistantFollowUp,
            int assistantLoopIteration,
            int assistantLoopMaxIterations) {
        CobolDiscoverySession session = getSession(tenantId, sessionId);
        CobolDiscoveryArtifact copybook = activeArtifact(session.getId(), "copybook")
                .orElseThrow(() -> new IllegalArgumentException("Upload a copybook before starting preview."));
        CobolDiscoveryArtifact dataFile = activeArtifact(session.getId(), "data_file")
                .orElseThrow(() -> new IllegalArgumentException("Upload an EBCDIC data file before starting preview."));

        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setSessionId(session.getId());
        run.setTenantId(tenantId);
        run.setRunType(runType);
        run.setStatus("QUEUED");
        run.setConfigSnapshot(overrides == null ? Map.of() : overrides);
        run.setProfilingSummary(Map.of());
        run.setAnomalySummary(Map.of());
        Map<String, Object> samplePolicy = new LinkedHashMap<>();
        samplePolicy.put("previewRows", Math.max(5, sampleRows));
        samplePolicy.put("assistantFollowUp", assistantFollowUp);
        samplePolicy.put("assistantLoopIteration", assistantLoopIteration);
        samplePolicy.put("assistantLoopMaxIterations", assistantLoopMaxIterations);
        run.setSamplePolicy(samplePolicy);
        run.setResultSchemaSnapshot(Map.of());
        run.setPreviewRows(List.of());
        run.setMappingSpec(List.of());
        run.setEventLog(List.of(event("queued", "Preview run queued", Map.of())));
        run.setExpiresAt(Instant.now().plus(RUN_TTL));
        CobolDiscoveryRun saved = runRepository.save(run);
        if (assistantFollowUp && assistantLoopIteration == 1) {
            createAssistantMessage(
                    session.getId(),
                    """
                    I’ve started an automatic discovery loop for this preview. I’ll inspect the flattened output, adjust Cobrix settings if needed, and keep rerunning until the preview looks right or I hit a real blocker.
                    """
            );
        }
        sessionStreamService.publish(session.getId(), "run_created", Map.of("run", sanitizeRun(saved)));
        runStreamService.publish(saved.getId(), "run_status", statusPayload(saved));

        executor.submit(() -> executeRun(saved.getId(), copybook.getId(), dataFile.getId(), overrides, sampleRows));
        return sanitizeRun(saved);
    }

    public CobolDiscoveryRun getRun(String tenantId, String runId) {
        CobolDiscoveryRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("CobolDiscoveryRun", runId));
        requireTenant(tenantId, run.getTenantId(), "run", runId);
        return sanitizeRun(run);
    }

    public List<Map<String, Object>> getRunEvents(String tenantId, String runId) {
        return getRun(tenantId, runId).getEventLog();
    }

    public SseEmitter streamRun(String tenantId, String runId) {
        CobolDiscoveryRun run = getRun(tenantId, runId);
        return runStreamService.register(runId, statusPayload(run));
    }

    public SseEmitter streamSession(String tenantId, String sessionId) {
        getSession(tenantId, sessionId);
        return sessionStreamService.register(sessionId);
    }

    public Map<String, Object> getRunPreviewTable(String tenantId, String runId) {
        CobolDiscoveryRun run = getRun(tenantId, runId);
        return Map.of(
                "status", run.getStatus(),
                "schema", run.getResultSchemaSnapshot(),
                "rows", run.getPreviewRows(),
                "mappingSpec", run.getMappingSpec()
        );
    }

    public Map<String, Object> getRunProfiling(String tenantId, String runId) {
        CobolDiscoveryRun run = getRun(tenantId, runId);
        return Map.of(
                "profilingSummary", run.getProfilingSummary(),
                "anomalySummary", run.getAnomalySummary(),
                "confidenceScore", run.getConfidenceScore()
        );
    }

    public CobolDiscoveryRun cancelRun(String tenantId, String runId) {
        CobolDiscoveryRun run = getRun(tenantId, runId);
        run.setStatus("CANCELLED");
        run.setEventLog(appendEvent(run.getEventLog(), event("cancelled", "Run cancelled", Map.of())));
        CobolDiscoveryRun saved = runRepository.save(run);
        runStreamService.publish(runId, "run_status", statusPayload(saved));
        return sanitizeRun(saved);
    }

    public List<CobolParsingProfile> listProfiles(String tenantId) {
        return profileRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    public CobolParsingProfile getProfile(String tenantId, String profileId) {
        CobolParsingProfile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("CobolParsingProfile", profileId));
        requireTenant(tenantId, profile.getTenantId(), "profile", profileId);
        return profile;
    }

    public CobolParsingProfile saveProfile(String tenantId, SaveProfileRequest request) throws IOException {
        purgeExpiredState();
        CobolDiscoveryRun run = getRun(tenantId, request.runId());
        if (!"COMPLETED".equals(run.getStatus())) {
            throw new IllegalArgumentException("Only a completed run can be saved as a COBOL profile.");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Profile name is required.");
        }
        CobolDiscoverySession session = getSession(tenantId, run.getSessionId());
        CobolDiscoveryArtifact copybook = activeArtifact(session.getId(), "copybook")
                .orElseThrow(() -> new IllegalArgumentException("The copybook artifact is no longer available."));

        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setTenantId(tenantId);
        profile.setName(request.name());
        profile.setDescription(request.description());
        profile.setCreatedBy(request.userId());
        profile.setCopybookContent(storageService.readText(copybook));
        profile.setCobrixOptions(run.getConfigSnapshot());
        @SuppressWarnings("unchecked")
        Map<String, Object> flattenSpec = (Map<String, Object>) run.getProfilingSummary().getOrDefault("flattenSpec", Map.of());
        profile.setFlattenSpec(flattenSpec);
        profile.setOutputSchemaSnapshot(run.getResultSchemaSnapshot());
        Map<String, Object> qualitySummary = new LinkedHashMap<>();
        qualitySummary.put("confidenceScore", run.getConfidenceScore());
        qualitySummary.put("profilingSummary", run.getProfilingSummary());
        qualitySummary.put("anomalySummary", run.getAnomalySummary());
        profile.setProfileQualitySummary(qualitySummary);
        profile.setMetadata(Map.of(
                "sourceSessionId", session.getId(),
                "sourceRunId", run.getId(),
                "savedAt", Instant.now().toString()
        ));
        CobolParsingProfile saved = profileRepository.save(profile);

        cleanupSessionArtifacts(session.getId());
        session.setStatus("PROFILE_SAVED");
        sessionRepository.save(session);
        return saved;
    }

    public CobolParsingProfile updateProfile(String tenantId, String profileId, UpdateProfileRequest request) {
        CobolParsingProfile profile = getProfile(tenantId, profileId);
        if (request.name() != null && !request.name().isBlank()) profile.setName(request.name().trim());
        if (request.description() != null) profile.setDescription(request.description().trim());
        if (request.cobrixOptions() != null && !request.cobrixOptions().isEmpty()) profile.setCobrixOptions(request.cobrixOptions());
        if (request.flattenSpec() != null && !request.flattenSpec().isEmpty()) profile.setFlattenSpec(request.flattenSpec());
        return profileRepository.save(profile);
    }

    public CobolDiscoverySession reprofile(String tenantId, String profileId, String userId) {
        CobolParsingProfile profile = getProfile(tenantId, profileId);
        CobolDiscoverySession session = createSession(tenantId, userId, "Reprofile: " + profile.getName());
        try {
            storageService.storeTextArtifact(
                    tenantId,
                    session.getId(),
                    "copybook",
                    profile.getName() + ".cpy",
                    profile.getCopybookContent(),
                    ARTIFACT_TTL
            );
        } catch (IOException e) {
            throw new IllegalStateException("Unable to seed reprofile session with saved copybook content", e);
        }
        createAssistantMessage(session.getId(), """
                I loaded the saved COBOL profile `%s` as the starting point.

                The saved copybook has already been staged for this reprofile session. Upload a new data file, or replace the copybook if it changed, then run preview again to validate or refine the profile.
                """.formatted(profile.getName()));
        return session;
    }

    private void executeRun(
            String runId,
            String copybookArtifactId,
            String dataArtifactId,
            Map<String, Object> overrides,
            int sampleRows) {
        CobolDiscoveryRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("CobolDiscoveryRun", runId));
        if ("CANCELLED".equals(run.getStatus())) return;
        run.setStatus("RUNNING");
        run.setEventLog(appendEvent(run.getEventLog(), event("running", "Preview run started", Map.of())));
        runRepository.save(run);
        runStreamService.publish(run.getId(), "run_status", statusPayload(run));

        String copybookContent = "";
        try {
            CobolDiscoveryArtifact copybook = artifactRepository.findById(copybookArtifactId)
                    .orElseThrow(() -> new ResourceNotFoundException("CobolDiscoveryArtifact", copybookArtifactId));
            CobolDiscoveryArtifact dataArtifact = artifactRepository.findById(dataArtifactId)
                    .orElseThrow(() -> new ResourceNotFoundException("CobolDiscoveryArtifact", dataArtifactId));

            copybookContent = storageService.readText(copybook);
            String copybookContentForRun = copybookContent;
            byte[] dataBytes = storageService.readBytes(dataArtifact);
            final String progressRunId = runId;
            Future<CobolSparkPreviewService.PreviewOutcome> future = executor.submit(() -> {
                    if (useLocalSpark) {
                        return sparkPreviewService.execute(
                                copybookContentForRun,
                                dataBytes,
                                storageService.resolve(dataArtifact),
                                overrides == null ? Map.of() : overrides,
                                Math.max(5, sampleRows),
                                progressEvent -> publishProgressById(progressRunId, progressEvent));
                    } else {
                        return dockerSparkPreviewService.execute(
                                copybookContentForRun,
                                dataBytes,
                                storageService.resolve(dataArtifact),
                                overrides == null ? Map.of() : overrides,
                                Math.max(5, sampleRows),
                                progressEvent -> publishProgressById(progressRunId, progressEvent));
                    }
            });
            CobolSparkPreviewService.PreviewOutcome outcome = future.get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Re-fetch run from DB to get latest state (progress callbacks saved on different thread)
            run = runRepository.findById(runId)
                    .orElseThrow(() -> new ResourceNotFoundException("CobolDiscoveryRun", runId));

            Map<String, Object> profiling = new LinkedHashMap<>(outcome.profilingSummary());
            profiling.put("copybookSummary", outcome.copybookSummary());
            profiling.put("flattenSpec", outcome.flattenSpec());

            run.setStatus("COMPLETED");
            run.setConfigSnapshot(outcome.chosenConfig());
            run.setProfilingSummary(profiling);
            run.setAnomalySummary(outcome.anomalySummary());
            run.setConfidenceScore(outcome.confidenceScore());
            run.setResultSchemaSnapshot(outcome.schemaSnapshot());
            run.setPreviewRows(outcome.previewRows());
            run.setMappingSpec(outcome.mappingSpec());
            run.setEventLog(outcome.eventLog());
            runRepository.save(run);
        } catch (TimeoutException ex) {
            log.error("DIAG: Preview run timed out for run {}", runId, ex);
            failRunSafe(runId, "Preview run timed out after " + RUN_TIMEOUT_SECONDS + " seconds.");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            // Walk the entire cause chain so we see the real root
            Throwable walk = cause;
            int depth = 0;
            while (walk != null && depth < 10) {
                log.error("DIAG: run {} cause[{}] {} : {}", runId, depth, walk.getClass().getName(), walk.getMessage());
                walk = walk.getCause();
                depth++;
            }
            log.error("DIAG: Full stack trace for run {}", runId, cause);
            String msg = cause.getMessage() != null ? cause.getMessage() : "Unknown execution error";
            failRunSafe(runId, msg);
        } catch (Exception ex) {
            log.error("DIAG: Unexpected exception for run {}. Class={}, message={}", runId, ex.getClass().getName(), ex.getMessage(), ex);
            String msg = ex.getMessage() != null ? ex.getMessage() : "Unknown error";
            failRunSafe(runId, msg);
        } finally {
            // Safety net: guarantee the run is never left in RUNNING state
            try {
                CobolDiscoveryRun latest = runRepository.findById(runId).orElse(null);
                if (latest != null && "RUNNING".equals(latest.getStatus())) {
                    log.warn("Run {} still RUNNING after executeRun completed — forcing to FAILED", runId);
                    latest.setStatus("FAILED");
                    latest.setAnomalySummary(Map.of("warnings", List.of("Run terminated unexpectedly while still in RUNNING state.")));
                    latest.setEventLog(appendEvent(latest.getEventLog(), event("failed", "Run forced to FAILED (safety net)", Map.of())));
                    runRepository.save(latest);
                }
            } catch (Exception safetyEx) {
                log.error("Safety-net status update failed for run {}: {}", runId, safetyEx.getMessage());
            }
        }

        // Re-fetch for post-run actions so we have the clean DB state
        run = runRepository.findById(runId).orElse(run);

        // Post-run actions in isolated try blocks so failures don't kill the executor thread
        try {
            maybeCreateAssistantFollowUp(run, copybookContent);
        } catch (Exception ex) {
            log.error("Assistant follow-up failed for run {}: {}", runId, ex.getMessage(), ex);
            try {
                createAssistantMessage(run.getSessionId(),
                        "I hit a blocker during the refinement loop: " + ex.getMessage()
                                + "\nPlease review the latest preview and adjust the copybook or config manually, then rerun.",
                        Map.of("loopBlocked", true));
            } catch (Exception msgEx) {
                log.error("Failed to send blocker message for run {}: {}", runId, msgEx.getMessage());
            }
        }
        try {
            sessionStreamService.publish(run.getSessionId(), "run_updated", Map.of("run", sanitizeRun(run)));
        } catch (Exception ex) {
            log.warn("Failed to publish run_updated SSE for run {}: {}", runId, ex.getMessage());
        }
        try {
            runStreamService.publish(run.getId(), "run_status", statusPayload(run));
        } catch (Exception ex) {
            log.warn("Failed to publish run_status SSE for run {}: {}", runId, ex.getMessage());
        }
    }

    private void failRunSafe(String runId, String errorMessage) {
        try {
            CobolDiscoveryRun fresh = runRepository.findById(runId).orElse(null);
            if (fresh == null) return;
            fresh.setStatus("FAILED");
            fresh.setAnomalySummary(Map.of("warnings", List.of(errorMessage)));
            fresh.setEventLog(appendEvent(fresh.getEventLog(), event("failed", "Run failed", Map.of("error", errorMessage))));
            runRepository.save(fresh);
        } catch (Exception ex) {
            log.error("failRunSafe could not update run {}: {}", runId, ex.getMessage());
        }
    }

    private CobolDiscoveryMessage createAssistantMessage(String sessionId, String content) {
        return createAssistantMessage(sessionId, content, Map.of());
    }

    private CobolDiscoveryMessage createAssistantMessage(String sessionId, String content, Map<String, Object> metadata) {
        CobolDiscoveryMessage message = new CobolDiscoveryMessage();
        message.setSessionId(sessionId);
        message.setRole("ASSISTANT");
        message.setContent(content);
        message.setSafePayloadOnly(true);
        message.setExpiresAt(Instant.now().plus(ARTIFACT_TTL));
        message.setMetadata(metadata == null ? Map.of() : metadata);
        CobolDiscoveryMessage saved = messageRepository.save(message);
        sessionStreamService.publish(sessionId, "message_created", Map.of("message", sanitizeMessage(saved)));
        return saved;
    }

    private Optional<CobolDiscoveryArtifact> activeArtifact(String sessionId, String type) {
        return artifactRepository.findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
                sessionId, type, "ACTIVE");
    }

    private String validateCopybookText(String copybookText) {
        CobolCopybookAnalyzer.SyntaxValidation validation = copybookAnalyzer.validateSyntax(copybookText);
        return validation.valid() ? null : validation.errorMessage();
    }

    private void publishCopybookUpdated(String sessionId, CobolDiscoveryArtifact artifact, String copybookText) {
        sessionStreamService.publish(sessionId, "copybook_updated", Map.of(
                "artifact", sanitizeArtifact(artifact),
                "copybookText", copybookText
        ));
    }

    private void cleanupSessionArtifacts(String sessionId) {
        for (CobolDiscoveryArtifact artifact : artifactRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)) {
            if ("ACTIVE".equals(artifact.getCleanupStatus())) {
                storageService.cleanupArtifact(artifact);
            }
        }
    }

    private void maybeCreateAssistantFollowUp(CobolDiscoveryRun run, String copybookContent) {
        if (!Boolean.TRUE.equals(run.getSamplePolicy().get("assistantFollowUp"))) {
            return;
        }
        int iteration = intValue(run.getSamplePolicy().get("assistantLoopIteration"), 1);
        int maxIterations = intValue(run.getSamplePolicy().get("assistantLoopMaxIterations"), ASSISTANT_LOOP_MAX_ITERATIONS);
        List<CobolDiscoveryRun> recentRuns = runRepository.findBySessionIdOrderByCreatedAtDesc(run.getSessionId());
        List<CobolDiscoveryMessage> recentMessages = messageRepository.findBySessionIdOrderByCreatedAtAsc(run.getSessionId());
        List<CobolDiscoveryArtifact> artifacts = artifactRepository.findBySessionIdOrderByCreatedAtAsc(run.getSessionId());
        List<CobolParsingProfile> profiles = profileRepository.findByTenantIdOrderByUpdatedAtDesc(run.getTenantId());
        CobolDiscoveryAssistantService.LoopDecision decision = assistantService.reviewRunForLoop(
                run,
                copybookContent,
                recentRuns,
                artifacts,
                recentMessages,
                profiles,
                iteration,
                maxIterations
        );
        LoopValidationResolution resolution = resolveLoopDecisionAfterCopybookValidation(
                run,
                copybookContent,
                recentRuns,
                artifacts,
                recentMessages,
                profiles,
                iteration,
                maxIterations,
                decision
        );
        decision = resolution.decision();

        Map<String, Object> metadata = new LinkedHashMap<>();
        String recommendedCopybookText = decision.recommendedCopybookText() == null ? "" : decision.recommendedCopybookText().trim();
        String activeCopybookContent = copybookContent;
        boolean copybookUpdated = false;
        if (!recommendedCopybookText.isBlank() && resolution.validationError() == null) {
            try {
                updateCopybookText(run.getTenantId(), run.getSessionId(), null, recommendedCopybookText);
                activeCopybookContent = recommendedCopybookText;
                copybookUpdated = true;
                metadata.put("recommendedCopybookText", recommendedCopybookText);
            } catch (IOException e) {
                log.warn("Unable to apply assistant-recommended copybook update for session {}: {}", run.getSessionId(), e.getMessage());
            }
        }
        Map<String, Object> normalizedRecommendedConfig = normalizeConfigForCopybook(activeCopybookContent, decision.recommendedConfig());
        if (!normalizedRecommendedConfig.isEmpty()) {
            metadata.put("recommendedConfig", normalizedRecommendedConfig);
            metadata.put("recommendedRunType", "preview");
        }
        String content = decision.satisfied()
                ? """
                I’m satisfied with this preview now. The preview looks good to save as a COBOL parsing profile.
                """
                : decision.assistantMessage();
        if (!normalizedRecommendedConfig.isEmpty()) {
            content += "\n\nRecommended Cobrix config:\n```json\n" + assistantService.safeJsonForUi(normalizedRecommendedConfig) + "\n```";
        }

        boolean canContinueLoop = !decision.satisfied() && iteration < maxIterations;
        boolean hasActionableChange = copybookUpdated || !normalizedRecommendedConfig.isEmpty();

        if (canContinueLoop && decision.shouldRerun() && hasActionableChange) {
            Map<String, Object> nextConfig = normalizedRecommendedConfig.isEmpty()
                    ? (Map<String, Object>) normalizeJsonGraph(run.getConfigSnapshot())
                    : normalizedRecommendedConfig;
            CobolDiscoveryRun nextRun = queueRun(
                    run.getTenantId(),
                    run.getSessionId(),
                    nextConfig,
                    intValue(run.getSamplePolicy().get("previewRows"), 20),
                    "preview",
                    true,
                    iteration + 1,
                    maxIterations
            );
            metadata.put("queuedRunId", nextRun.getId());
            metadata.put("queuedRunType", nextRun.getRunType());
            createAssistantMessage(run.getSessionId(), content, metadata);
            return;
        }

        if (canContinueLoop && !decision.satisfied() && (!decision.shouldRerun() || !hasActionableChange)) {
            log.warn("Discovery loop iteration {} for session {}: agent returned satisfied=false but provided no actionable change. Forcing retry.",
                    iteration, run.getSessionId());
            createAssistantMessage(run.getSessionId(), content, metadata);
            CobolDiscoveryAssistantService.LoopDecision retryDecision = assistantService.reviewRunForLoop(
                    run,
                    activeCopybookContent,
                    recentRuns,
                    artifacts,
                    messageRepository.findBySessionIdOrderByCreatedAtAsc(run.getSessionId()),
                    profiles,
                    iteration,
                    maxIterations
            );
            Map<String, Object> retryConfig = normalizeConfigForCopybook(activeCopybookContent, retryDecision.recommendedConfig());
            String retryCopybookText = retryDecision.recommendedCopybookText() == null ? "" : retryDecision.recommendedCopybookText().trim();
            if (!retryCopybookText.isBlank()) {
                String retryValidationError = validateCopybookText(retryCopybookText);
                if (retryValidationError != null) {
                    log.warn("Forced retry copybook also invalid for session {}: {}", run.getSessionId(), retryValidationError);
                    retryCopybookText = "";
                }
            }
            boolean retryHasChange = !retryConfig.isEmpty() || !retryCopybookText.isBlank();
            if (retryDecision.shouldRerun() && retryHasChange) {
                if (!retryCopybookText.isBlank()) {
                    try {
                        updateCopybookText(run.getTenantId(), run.getSessionId(), null, retryCopybookText);
                        activeCopybookContent = retryCopybookText;
                    } catch (Exception e) {
                        log.warn("Retry copybook update failed for session {}: {}", run.getSessionId(), e.getMessage());
                    }
                }
                Map<String, Object> nextConfig = retryConfig.isEmpty()
                        ? (Map<String, Object>) normalizeJsonGraph(run.getConfigSnapshot())
                        : retryConfig;
                CobolDiscoveryRun nextRun = queueRun(
                        run.getTenantId(),
                        run.getSessionId(),
                        nextConfig,
                        intValue(run.getSamplePolicy().get("previewRows"), 20),
                        "preview",
                        true,
                        iteration + 1,
                        maxIterations
                );
                Map<String, Object> retryMeta = new LinkedHashMap<>();
                retryMeta.put("queuedRunId", nextRun.getId());
                retryMeta.put("queuedRunType", nextRun.getRunType());
                retryMeta.put("retryForced", true);
                createAssistantMessage(run.getSessionId(), retryDecision.assistantMessage(), retryMeta);
                return;
            }
            log.warn("Discovery loop for session {}: forced retry also produced no actionable change. Stopping loop.", run.getSessionId());
            createAssistantMessage(run.getSessionId(),
                    "I hit a blocker and could not produce a valid next configuration change. " +
                    "Please review the latest preview and adjust the copybook or Cobrix options manually, then rerun.",
                    Map.of("loopBlocked", true));
            return;
        }

        // If the loop exhausted max iterations without satisfaction, signal the frontend
        if (!decision.satisfied() && iteration >= maxIterations) {
            content = "I hit the iteration limit (" + maxIterations + " runs) without fully converging. "
                    + "You can send me a message to keep trying, or adjust the copybook/config manually and rerun.\n\n"
                    + content;
            metadata.put("loopExhausted", true);
        }

        createAssistantMessage(run.getSessionId(), content, metadata);
    }

    private LoopValidationResolution resolveLoopDecisionAfterCopybookValidation(
            CobolDiscoveryRun run,
            String copybookContent,
            List<CobolDiscoveryRun> recentRuns,
            List<CobolDiscoveryArtifact> artifacts,
            List<CobolDiscoveryMessage> recentMessages,
            List<CobolParsingProfile> profiles,
            int iteration,
            int maxIterations,
            CobolDiscoveryAssistantService.LoopDecision initialDecision) {
        CobolDiscoveryAssistantService.LoopDecision decision = initialDecision;
        List<CobolDiscoveryMessage> loopMessages = new ArrayList<>(recentMessages);
        for (int attempt = 0; attempt < COPYBOOK_VALIDATION_RETRY_LIMIT; attempt++) {
            String candidateCopybookText = decision.recommendedCopybookText() == null ? "" : decision.recommendedCopybookText().trim();
            if (candidateCopybookText.isBlank()) {
                return new LoopValidationResolution(decision, null);
            }
            String validationError = validateCopybookText(candidateCopybookText);
            if (validationError == null) {
                return new LoopValidationResolution(decision, null);
            }
            CobolDiscoveryMessage feedback = createAssistantMessage(
                    run.getSessionId(),
                    """
                    Cobrix syntax validation rejected my last copybook revision, so I did not apply it.

                    Error: %s

                    I need to revise the full raw copybook text and try again. I should not reuse the rejected copybook unchanged.
                    """.formatted(validationError),
                    Map.of("copybookValidationError", validationError)
            );
            loopMessages.add(feedback);
            decision = assistantService.reviewRunForLoop(
                    run,
                    copybookContent,
                    recentRuns,
                    artifacts,
                    loopMessages,
                    profiles,
                    iteration,
                    maxIterations
            );
        }
        return new LoopValidationResolution(
                new CobolDiscoveryAssistantService.LoopDecision(
                        """
                        I hit a real blocker while revising the copybook: Cobrix syntax validation rejected repeated copybook rewrites, so I stopped before staging another invalid layout.
                        """,
                        Map.of(),
                        "",
                        false,
                        false
                ),
                "Cobrix syntax validation rejected repeated copybook rewrites."
        );
    }

    public void purgeExpiredState() {
        storageService.purgeExpiredArtifacts();
        List<CobolDiscoveryRun> expiredRuns = runRepository.findByExpiresAtBeforeAndCleanupStatus(Instant.now(), "ACTIVE");
        for (CobolDiscoveryRun run : expiredRuns) {
            if (!"COMPLETED".equals(run.getStatus())
                    && !"FAILED".equals(run.getStatus())
                    && !"CANCELLED".equals(run.getStatus())) {
                run.setStatus("EXPIRED");
            }
            run.setCleanupStatus("EXPIRED");
            runRepository.save(run);
            runStreamService.publish(run.getId(), "run_status", statusPayload(run));
        }
    }

    private void requireTenant(String requestedTenantId, String actualTenantId, String resourceType, String resourceId) {
        if (!requestedTenantId.equals(actualTenantId)) {
            throw new IllegalArgumentException(resourceType + " " + resourceId + " does not belong to tenant " + requestedTenantId);
        }
    }

    private Map<String, Object> event(String type, String message, Map<String, Object> detail) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("message", message);
        event.put("detail", detail);
        event.put("timestamp", Instant.now().toString());
        return event;
    }

    private List<Map<String, Object>> appendEvent(List<Map<String, Object>> current, Map<String, Object> next) {
        List<Map<String, Object>> events = new ArrayList<>(current == null ? List.of() : current);
        events.add(next);
        return events;
    }

    private void publishProgress(CobolDiscoveryRun run, Map<String, Object> event) {
        publishProgressById(run.getId(), event);
    }

    private void publishProgressById(String runId, Map<String, Object> event) {
        try {
            CobolDiscoveryRun fresh = runRepository.findById(runId).orElse(null);
            if (fresh == null) return;
            fresh.setEventLog(appendEvent(fresh.getEventLog(), event));
            runRepository.save(fresh);
            sessionStreamService.publish(fresh.getSessionId(), "run_progress", Map.of(
                    "runId", fresh.getId(),
                    "event", normalizeJsonGraph(event)
            ));
            runStreamService.publish(fresh.getId(), "run_progress", event);
            runStreamService.publish(fresh.getId(), "run_status", statusPayload(fresh));
        } catch (Exception ex) {
            log.warn("publishProgressById failed for run {}: {}", runId, ex.getMessage());
        }
    }

    private Map<String, Object> statusPayload(CobolDiscoveryRun run) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId());
        payload.put("status", run.getStatus());
        payload.put("runType", run.getRunType());
        payload.put("confidenceScore", run.getConfidenceScore());
        payload.put("warningCount", sizeOfUnknownCollection(run.getAnomalySummary().get("warnings")));
        payload.put("previewRowCount", sizeOfUnknownCollection(run.getPreviewRows()));
        payload.put("eventCount", sizeOfUnknownCollection(run.getEventLog()));
        payload.put("updatedAt", run.getUpdatedAt().toString());
        return payload;
    }

    private int sizeOfUnknownCollection(Object value) {
        if (value == null) return 0;
        if (value instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        if (value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value);
        }
        try {
            var method = value.getClass().getMethod("size");
            Object size = method.invoke(value);
            if (size instanceof Number number) {
                return number.intValue();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private AssistantToolAction sanitizeToolAction(AssistantToolAction action) {
        return new AssistantToolAction(
                action.name(),
                action.status(),
                (Map<String, Object>) normalizeJsonGraph(action.input()),
                (Map<String, Object>) normalizeJsonGraph(action.result())
        );
    }

    private Map<String, Object> sanitizeArtifact(CobolDiscoveryArtifact artifact) {
        if (artifact == null) return Map.of();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", artifact.getId());
        payload.put("sessionId", artifact.getSessionId());
        payload.put("tenantId", artifact.getTenantId());
        payload.put("artifactType", artifact.getArtifactType());
        payload.put("originalFilename", artifact.getOriginalFilename());
        payload.put("storageUri", artifact.getStorageUri());
        payload.put("sha256", artifact.getSha256());
        payload.put("sizeBytes", artifact.getSizeBytes());
        payload.put("contentType", artifact.getContentType());
        payload.put("cleanupStatus", artifact.getCleanupStatus());
        payload.put("expiresAt", artifact.getExpiresAt() == null ? null : artifact.getExpiresAt().toString());
        payload.put("createdAt", artifact.getCreatedAt() == null ? null : artifact.getCreatedAt().toString());
        payload.put("updatedAt", artifact.getUpdatedAt() == null ? null : artifact.getUpdatedAt().toString());
        return payload;
    }

    @SuppressWarnings("unchecked")
    private CobolDiscoveryRun sanitizeRun(CobolDiscoveryRun run) {
        if (run == null) return null;
        run.setConfigSnapshot((Map<String, Object>) normalizeJsonGraph(run.getConfigSnapshot()));
        run.setProfilingSummary((Map<String, Object>) normalizeJsonGraph(run.getProfilingSummary()));
        run.setAnomalySummary((Map<String, Object>) normalizeJsonGraph(run.getAnomalySummary()));
        run.setSamplePolicy((Map<String, Object>) normalizeJsonGraph(run.getSamplePolicy()));
        run.setResultSchemaSnapshot((Map<String, Object>) normalizeJsonGraph(run.getResultSchemaSnapshot()));
        run.setPreviewRows((List<Map<String, Object>>) normalizeJsonGraph(run.getPreviewRows()));
        run.setMappingSpec((List<Map<String, Object>>) normalizeJsonGraph(run.getMappingSpec()));
        run.setEventLog((List<Map<String, Object>>) normalizeJsonGraph(run.getEventLog()));
        return run;
    }

    @SuppressWarnings("unchecked")
    private CobolDiscoveryMessage sanitizeMessage(CobolDiscoveryMessage message) {
        if (message == null) return null;
        message.setMetadata((Map<String, Object>) normalizeJsonGraph(message.getMetadata()));
        return message;
    }

    @SuppressWarnings("unchecked")
    private Object normalizeJsonGraph(Object value) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalizeJsonGraph(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : iterable) {
                normalized.add(normalizeJsonGraph(item));
            }
            return normalized;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> normalized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                normalized.add(normalizeJsonGraph(java.lang.reflect.Array.get(value, i)));
            }
            return normalized;
        }
        String className = value.getClass().getName();
        if (className.startsWith("scala.collection.")) {
            try {
                Object iterator = value.getClass().getMethod("iterator").invoke(value);
                java.lang.reflect.Method hasNext = iterator.getClass().getMethod("hasNext");
                java.lang.reflect.Method next = iterator.getClass().getMethod("next");
                List<Object> items = new ArrayList<>();
                while (Boolean.TRUE.equals(hasNext.invoke(iterator))) {
                    items.add(next.invoke(iterator));
                }
                boolean tupleLike = items.stream().allMatch(item -> hasMethod(item, "_1") && hasMethod(item, "_2"));
                if (tupleLike) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    for (Object item : items) {
                        Object key = item.getClass().getMethod("_1").invoke(item);
                        Object nested = item.getClass().getMethod("_2").invoke(item);
                        normalized.put(String.valueOf(key), normalizeJsonGraph(nested));
                    }
                    return normalized;
                }
                return items.stream().map(this::normalizeJsonGraph).toList();
            } catch (Exception ignored) {
            }
        }
        return value;
    }

    private boolean hasMethod(Object target, String name) {
        if (target == null) return false;
        try {
            target.getClass().getMethod(name);
            return true;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private CobolDiscoveryAssistantService.AssistantReply normalizeAssistantReply(
            String copybookContent,
            CobolDiscoveryAssistantService.AssistantReply reply) {
        if (reply == null || reply.metadata() == null || !reply.metadata().containsKey("recommendedConfig")) {
            return reply;
        }
        Object value = reply.metadata().get("recommendedConfig");
        if (!(value instanceof Map<?, ?> raw)) {
            return reply;
        }
        Map<String, Object> normalized = normalizeConfigForCopybook(copybookContent, (Map<String, Object>) raw);
        Map<String, Object> metadata = new LinkedHashMap<>(reply.metadata());
        metadata.put("recommendedConfig", normalized);
        return new CobolDiscoveryAssistantService.AssistantReply(reply.content(), metadata);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeConfigForCopybook(String copybookContent, Map<String, Object> rawConfig) {
        if (rawConfig == null || rawConfig.isEmpty()) {
            return Map.of();
        }
        Map<String, String> identifierIndex = buildCopybookIdentifierIndex(copybookContent);
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawConfig.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            String canonicalKey = switch (key) {
                case "framing" -> "record_format";
                case "recordFormat" -> "record_format";
                case "recordLength" -> "record_length";
                default -> key;
            };
            if (!canonicalKey.startsWith("_") && !SUPPORTED_OPTION_KEYS.contains(canonicalKey)) {
                continue;
            }
            if ("segment_field".equals(canonicalKey) && value instanceof String str) {
                normalized.put(canonicalKey, canonicalIdentifier(str, identifierIndex));
                continue;
            }
            if ("redefine_segment_id_map".equals(canonicalKey) && value instanceof Map<?, ?> nestedMap) {
                Map<String, Object> nested = normalizeRedefineSegmentMap(nestedMap, identifierIndex);
                normalized.put(canonicalKey, nested);
                continue;
            }
            normalized.put(canonicalKey, value);
        }
        return normalized;
    }

    private Map<String, Object> normalizeRedefineSegmentMap(Map<?, ?> rawMap, Map<String, String> identifierIndex) {
        Map<String, Object> forward = new LinkedHashMap<>();
        Map<String, Object> reverse = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String rawKey = String.valueOf(entry.getKey());
            Object rawValue = entry.getValue();
            if (rawValue == null) continue;
            String value = String.valueOf(rawValue);
            String normalizedKeyLetters = rawKey.toUpperCase().replaceAll("[^A-Z0-9]", "");
            String normalizedValueLetters = value.toUpperCase().replaceAll("[^A-Z0-9]", "");
            String canonicalValue = canonicalIdentifier(value, identifierIndex);
            if (normalizedKeyLetters.length() == 1 && normalizedValueLetters.length() > 1) {
                // Forward map: discriminator value → group name.
                // Discriminator keys are case-sensitive data-file byte values — preserve as-is.
                forward.put(rawKey, canonicalValue);
            } else if (normalizedKeyLetters.length() > 1 && normalizedValueLetters.length() == 1) {
                // Reverse map: group name → discriminator value. Flip to forward.
                // Discriminator value (now key) is case-sensitive — preserve as-is.
                reverse.put(value, canonicalIdentifier(rawKey, identifierIndex));
            } else {
                // Ambiguous direction — preserve key as-is.
                forward.put(rawKey, canonicalValue);
            }
        }
        if (!forward.isEmpty() && reverse.isEmpty()) {
            return forward;
        }
        if (forward.isEmpty() && !reverse.isEmpty()) {
            return reverse;
        }
        if (!reverse.isEmpty()) {
            forward.putAll(reverse);
        }
        return forward;
    }

    private Map<String, String> buildCopybookIdentifierIndex(String copybookContent) {
        Map<String, String> index = new LinkedHashMap<>();
        if (copybookContent == null || copybookContent.isBlank()) {
            return index;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)\\b[A-Z][A-Z0-9-]*\\b").matcher(copybookContent);
        while (matcher.find()) {
            String token = matcher.group().toUpperCase();
            index.putIfAbsent(token.replaceAll("[^A-Z0-9]", ""), token);
        }
        return index;
    }

    private String canonicalIdentifier(String candidate, Map<String, String> identifierIndex) {
        if (candidate == null || candidate.isBlank()) {
            return candidate;
        }
        String normalized = candidate.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return identifierIndex.getOrDefault(normalized, candidate);
    }

    private Map<String, Object> summarizePreview(CobolDiscoveryRun latestRun) {
        if (latestRun == null) {
            return Map.of("status", "unavailable", "message", "No preview run is available yet.");
        }
        return Map.of(
                "runId", latestRun.getId(),
                "status", latestRun.getStatus(),
                "confidenceScore", latestRun.getConfidenceScore(),
                "rowCount", latestRun.getProfilingSummary().getOrDefault("rowCount", 0),
                "columnCount", latestRun.getProfilingSummary().getOrDefault("columnCount", 0),
                "headers", latestRun.getResultSchemaSnapshot().getOrDefault("fields", List.of()),
                "warnings", latestRun.getAnomalySummary().getOrDefault("warnings", List.of())
        );
    }

    private record LoopValidationResolution(
            CobolDiscoveryAssistantService.LoopDecision decision,
            String validationError) {}

    public record AssistantToolAction(String name, String status, Map<String, Object> input, Map<String, Object> result) {}
    public record MessageExchange(
            CobolDiscoveryMessage userMessage,
            CobolDiscoveryMessage assistantMessage,
            Map<String, Object> optionOverrides,
            List<AssistantToolAction> toolActions,
            CobolDiscoveryRun activeRun,
            Map<String, Object> previewSummary) {}
    public record SaveProfileRequest(String runId, String name, String description, String userId) {}
    public record UpdateProfileRequest(String name, String description, Map<String, Object> cobrixOptions, Map<String, Object> flattenSpec) {}
}
