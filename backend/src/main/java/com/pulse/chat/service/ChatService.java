package com.pulse.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.codegen.service.DbtAssetRegistryService;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.chat.ChatStreamingException;
import com.pulse.chat.model.ChatMessage;
import com.pulse.chat.model.ChatSession;
import com.pulse.chat.repository.ChatMessageRepository;
import com.pulse.chat.repository.ChatSessionRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.llm.LlmEndpointService;
import com.pulse.llm.LlmSurface;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    // ADR 0025 / decision 3-03: raised 30 -> 40. A 10-step pipeline ~= 30 ops
    // ~= 30 tool calls, so 30 is too tight; 40 is a one-constant change with no
    // downside but a latency tail. The graph driver graceful-halts at the bound
    // (appends an assistant message), it does NOT throw.
    public static final int MAX_TOOL_ROUNDS = 40;
    private static final Map<String, List<String>> DBT_BEST_PRACTICE_CARDS = Map.ofEntries(
            Map.entry("BronzeToSilverCleaning", List.of(
                    "Project only the columns the downstream contract needs; keep business keys stable while cleaning.",
                    "Treat cleanup as idempotent silver work so the model can run safely on retries and backfills.")),
            Map.entry("SchemaNormalization", List.of(
                    "Pin every output column to a declared type; never infer types in the target schema.",
                    "Prefer explicit source_column → target_column mapping over implicit passthrough so renames survive refactors.")),
            Map.entry("GenericFilter", List.of(
                    "Keep filter predicates deterministic and sargable against the upstream partitioning.",
                    "Reference only columns that exist in the resolved upstream schema — the planner flags MISSING_COLUMN otherwise.")),
            Map.entry("GenericAggregate", List.of(
                    "Declare group_by_columns explicitly so the planner can validate grain changes against the schema contract.",
                    "Name aggregation aliases with their function (sum_, count_, avg_) to make downstream ref() lookups self-describing.")),
            Map.entry("GenericJoin", List.of(
                    "Use left_join_keys / right_join_keys so type-mismatch conflicts surface at propagation instead of at runtime.",
                    "Deduplicate same-name columns with stable right_-prefixing; never silently drop a column on collision.")),
            Map.entry("GenericRouter", List.of(
                    "Every route emits a full schema copy — keep route predicates mutually exclusive for data integrity.",
                    "Prefer a named default route over an implicit catch-all so lineage is explicit.")),
            Map.entry("JsonFlatten", List.of(
                    "Materialize flattened struct fields as explicit columns, not passthrough, so downstream contracts are stable.",
                    "When exploding arrays, document the grain change (row multiplication) in the model docblock.")),
            Map.entry("JsonStruct", List.of(
                    "Keep struct_name aligned with the downstream access pattern (mart columns use the struct name in dot-notation).",
                    "Drop source columns only when the struct fully subsumes them — otherwise keep both for lineage clarity.")),
            Map.entry("PIIMasking", List.of(
                    "Pick a masking strategy that preserves type (hash vs. tokenize) so downstream joins still work on the surrogate.",
                    "Mask at staging, never at serving — gold-layer PII unmasking is always a compliance failure.")),
            Map.entry("DedupeAndMerge", List.of(
                    "Define dedup keys at the business grain; using technical keys is almost always wrong.",
                    "Capture survivorship rules (LATEST_WINS, MERGE, ORDER_BY_COLUMN) in params, not in a custom macro.")),
            Map.entry("IncrementalMerge", List.of(
                    "Define the merge key explicitly and make incremental filters deterministic from the source grain.",
                    "Prefer late-arriving-safe predicates so replays do not silently miss updates.")),
            Map.entry("SCD2Dimension", List.of(
                    "Separate the business key from the change-detection columns and persist effective/expiry timestamps explicitly.",
                    "Preserve one current row per business key and keep hash/change detection logic deterministic.")),
            Map.entry("SnapshotModel", List.of(
                    "Use snapshot semantics only for point-in-time history; do not overload snapshots for ordinary incremental marts.",
                    "Choose stable unique keys and change capture columns before generating snapshot SQL.")),
            Map.entry("FactBuild", List.of(
                    "Declare grain first (one row per …), then choose measures and dimension keys that satisfy that grain.",
                    "Always partition gold facts by event_date (or equivalent) so replay + audit queries stay bounded.")),
            Map.entry("WideDenormalizedMart", List.of(
                    "Keep the mart's column list tied to a BI contract; denormalization without a contract is tech debt.",
                    "Prefer ref() over source() in mart SQL so dbt's graph captures lineage end-to-end.")),
            Map.entry("AggregateMaterialization", List.of(
                    "Materialize aggregates as tables, not views, so dashboard latency stays predictable.",
                    "Use deterministic period anchors (first-of-month, ISO week) to keep joins to calendar dims exact.")),
            Map.entry("FeatureTablePublish", List.of(
                    "Pin the point-in-time column explicitly; feature tables without an as-of column leak training/serving skew.",
                    "Keep entity_key stable across refreshes — versioning the key breaks downstream feature stores.")),
            Map.entry("ReferenceDataPublish", List.of(
                    "Version every publish; readers rely on the version column to detect stale caches.",
                    "Promote reference data via dbt with on_change='skip' so accidental re-runs are no-ops.")));

    private static final Map<String, List<ExamplePacket>> BLUEPRINT_EXAMPLE_PACKETS = Map.ofEntries(
            Map.entry("FileIngestion", List.of(new ExamplePacket(
                    "pyspark_file_ingestion", "pyspark.ingest.file",
                    List.of("spark.apache.org", "docs.databricks.com"),
                    "Python",
                    "Show deterministic schema reads (CSV header + inferSchema=false) and audit-column adds.",
                    "Use S3/GCS-native auth, set fs.s3a.* hadoop config, write to bronze layer."))),
            Map.entry("ApiIngestion", List.of(new ExamplePacket(
                    "pyspark_api_ingestion", "pyspark.ingest.rest",
                    List.of("docs.python-requests.org"),
                    "Python",
                    "Show paginated fetch loops with bounded retries and Bearer auth headers.",
                    "Normalize paginated responses before createDataFrame; always set a timeout."))),
            Map.entry("StreamIngestion", List.of(new ExamplePacket(
                    "pyspark_stream_kafka", "pyspark.stream.kafka",
                    List.of("spark.apache.org/docs/latest/structured-streaming-kafka-integration.html"),
                    "Python",
                    "Show structured-streaming readStream with checkpointLocation and explicit trigger mode.",
                    "Never drop the checkpoint dir; it is the exactly-once contract."))),
            Map.entry("SnapshotIngestion", List.of(new ExamplePacket(
                    "pyspark_jdbc_snapshot", "pyspark.ingest.jdbc",
                    List.of("spark.apache.org/docs/latest/sql-data-sources-jdbc.html"),
                    "Python",
                    "Show dialect-aware JDBC URL construction (Oracle SID/Service, Postgres DB, MySQL, SQL Server).",
                    "Partition large reads with partitionColumn + lowerBound/upperBound to avoid single-task pulls."))),
            Map.entry("CDCIngestion", List.of(new ExamplePacket(
                    "cdc_debezium_or_poll", "cdc.dualmode",
                    List.of("debezium.io"),
                    "Python",
                    "Show both the debezium kafka-envelope path and the incremental-poll fallback driven by a watermark column.",
                    "When using poll, persist the watermark via the pipeline_state API after the successful write."))),
            Map.entry("BulkBackfill", List.of(new ExamplePacket(
                    "pyspark_bulk_backfill", "pyspark.ingest.backfill",
                    List.of("spark.apache.org"),
                    "Python",
                    "Show chunk-size + parallelism tuning and date-range windowing for large historical pulls.",
                    "Backfill runs MUST be idempotent; key writes by (partition_col, run_id)."))),
            Map.entry("BronzeToSilverCleaning", List.of(new ExamplePacket(
                    "dbt_silver_cleaning", "dbt.silver.cleaning",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show deterministic casts, trims, null handling, contract-safe renames.",
                    "Use CTE-based staging, preserve raw keys, keep cleanup logic side-effect free."))),
            Map.entry("SchemaNormalization", List.of(new ExamplePacket(
                    "dbt_schema_normalization", "dbt.silver.normalize",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show explicit source_column → target_column mapping with declared types.",
                    "Never infer types in the target schema — pin them."))),
            Map.entry("GenericFilter", List.of(new ExamplePacket(
                    "dbt_filter_simple", "dbt.silver.filter",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show composite WHERE clauses that rely only on columns in the upstream schema.",
                    "Prefer raw_sql mode only when the visual builder can't express the predicate."))),
            Map.entry("GenericAggregate", List.of(new ExamplePacket(
                    "dbt_aggregate_window", "dbt.silver.aggregate",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show group-by + aggregation + (optional) window frames.",
                    "Name aliases descriptively; do not reuse base column names."))),
            Map.entry("GenericJoin", List.of(new ExamplePacket(
                    "dbt_join_two_sources", "dbt.silver.join",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show left/right typed join keys plus explicit column selection to avoid ambiguous output.",
                    "Use aliases (l, r) so downstream column references are unambiguous."))),
            Map.entry("GenericRouter", List.of(new ExamplePacket(
                    "dbt_router_split", "dbt.silver.router",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show one dbt model per route, each filtering on mutually exclusive predicates.",
                    "The default route's NOT (...) clause should be generated from route predicates."))),
            Map.entry("JsonFlatten", List.of(new ExamplePacket(
                    "dbt_json_flatten", "dbt.silver.flatten",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show {{ star(...) }} + explicit struct field access.",
                    "For array columns use LATERAL VIEW OUTER EXPLODE; document the row multiplication."))),
            Map.entry("JsonStruct", List.of(new ExamplePacket(
                    "dbt_json_struct", "dbt.silver.struct",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show NAMED_STRUCT() builders for struct outputs and TO_JSON() for JSON string outputs.",
                    "Drop source columns only when the struct fully subsumes them."))),
            Map.entry("PIIMasking", List.of(new ExamplePacket(
                    "dbt_pii_masking", "dbt.silver.pii",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show SHA2() hashing and CONCAT-based partial redaction.",
                    "Mask at staging, never at serving."))),
            Map.entry("DedupeAndMerge", List.of(new ExamplePacket(
                    "dbt_dedupe_merge", "dbt.silver.dedupe",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show ROW_NUMBER() OVER (PARTITION BY dedup_keys ORDER BY order_by) = 1.",
                    "Capture survivorship rules in params, not custom macros."))),
            Map.entry("IncrementalMerge", List.of(new ExamplePacket(
                    "dbt_incremental_merge", "dbt.incremental.merge",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show incremental predicates, unique keys, replay-safe merge conditions.",
                    "Filter on reproducible watermarks and align merge keys to the analytical grain."))),
            Map.entry("SCD2Dimension", List.of(new ExamplePacket(
                    "dbt_scd2_dimension", "dbt.scd2.dimension",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show business keys, effective dating, current-row flags, change detection columns.",
                    "Prefer explicit valid_from/valid_to semantics and deterministic row versioning."))),
            Map.entry("SnapshotModel", List.of(new ExamplePacket(
                    "dbt_snapshot_model", "dbt.snapshot.model",
                    List.of("docs.getdbt.com"),
                    "YAML + SQL",
                    "Show check/timestamp strategies and stable unique keys.",
                    "Keep snapshot configuration minimal and grounded in point-in-time history needs."))),
            Map.entry("FactBuild", List.of(new ExamplePacket(
                    "dbt_fact_build", "dbt.gold.fact",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show grain-anchored fact build with dimension keys and declared measures.",
                    "Always partition gold facts by event_date (or equivalent)."))),
            Map.entry("WideDenormalizedMart", List.of(new ExamplePacket(
                    "dbt_wide_mart", "dbt.gold.mart",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show fact × dimensions joins producing a BI-serving wide table.",
                    "Prefer ref() over source() to preserve lineage."))),
            Map.entry("AggregateMaterialization", List.of(new ExamplePacket(
                    "dbt_aggregate_mat", "dbt.gold.aggregate",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show deterministic period anchors and pre-joined dimension keys.",
                    "Materialize as table for dashboard latency predictability."))),
            Map.entry("FeatureTablePublish", List.of(new ExamplePacket(
                    "dbt_feature_publish", "dbt.gold.feature",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show entity_key + point_in_time_column + typed feature columns.",
                    "Never mix training and serving cutoffs in the same table."))),
            Map.entry("ReferenceDataPublish", List.of(new ExamplePacket(
                    "dbt_reference_publish", "dbt.gold.reference",
                    List.of("docs.getdbt.com"),
                    "SQL + Jinja",
                    "Show versioned publish with stable keys and on_change='skip'.",
                    "Accidental re-runs must be no-ops."))),
            Map.entry("DQValidator", List.of(new ExamplePacket(
                    "gx_dq_validator", "gx.dq.validator",
                    List.of("docs.greatexpectations.io"),
                    "Python + YAML",
                    "Show expectation suites tied to column contracts with severity metadata.",
                    "Prefer explicit expectation kwargs and document blocking vs warning-level rules."))),
            Map.entry("FreshnessChecks", List.of(new ExamplePacket(
                    "gx_freshness", "gx.dq.freshness",
                    List.of("docs.greatexpectations.io"),
                    "Python",
                    "Show ExpectColumnMaxToBeBetween on a timestamp column with a lower bound driven by business_date.",
                    "Tie the threshold to the dataset's declared time_grain, not a hard-coded number."))),
            Map.entry("SchemaDriftDetection", List.of(new ExamplePacket(
                    "gx_schema_drift", "gx.dq.drift",
                    List.of("docs.greatexpectations.io"),
                    "Python",
                    "Show ExpectTableColumnsToMatchSet + per-column ExpectColumnToExist.",
                    "Drift policy (block/warn/auto_adapt) comes from params; never hard-code it."))),
            Map.entry("AnomalyDetection", List.of(new ExamplePacket(
                    "gx_anomaly", "gx.dq.anomaly",
                    List.of("docs.greatexpectations.io"),
                    "Python",
                    "Show ExpectColumnValueZScoresToBeLessThan with configurable threshold.",
                    "Pair z-score with a volume check so row-count anomalies also surface."))),
            Map.entry("WarehouseWriter", List.of(new ExamplePacket(
                    "pyspark_warehouse_write", "pyspark.sink.warehouse",
                    List.of("spark.apache.org"),
                    "Python",
                    "Show BigQuery via spark-bigquery-connector with partitionField + clusteredFields.",
                    "Use writeMethod='direct' for production writes."))),
            Map.entry("LakeWriter", List.of(new ExamplePacket(
                    "pyspark_lake_write", "pyspark.sink.lake",
                    List.of("docs.delta.io", "iceberg.apache.org"),
                    "Python",
                    "Show Delta MERGE INTO and Iceberg MERGE INTO syntax.",
                    "Use unique keys for idempotent merges; never plain overwrite on silver."))),
            Map.entry("StreamWriter", List.of(new ExamplePacket(
                    "pyspark_stream_write", "pyspark.sink.kafka",
                    List.of("spark.apache.org"),
                    "Python",
                    "Show to_json(struct(*)) serialization and explicit topic + bootstrap config.",
                    "Always set a key column for Kafka partition stability."))),
            Map.entry("DatabaseWriter", List.of(new ExamplePacket(
                    "pyspark_jdbc_write", "pyspark.sink.jdbc",
                    List.of("spark.apache.org"),
                    "Python",
                    "Show JDBC write with batchsize and mode='append'/'upsert' by dialect.",
                    "Use dialect-specific upsert for Postgres (ON CONFLICT) and Databricks (MERGE)."))));

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final BlueprintRepository blueprintRepo;
    private final SystemOfRecordRepository sorRepo;
    private final DomainRepository domainRepo;
    private final DatasetRepository datasetRepo;
    private final PipelineRepository pipelineRepo;
    private final CompositionService compositionService;
    private final DbtAssetRegistryService dbtAssetRegistryService;
    private final ChatToolExecutor toolExecutor;
    private final PhaseDetector phaseDetector;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Value("${pulse.llm.api-key:}")
    private String apiKey;

    @Value("${pulse.llm.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${pulse.llm.model:openai/gpt-5.2}")
    private String model;

    @Value("${pulse.llm.reasoning-model:o4-mini}")
    private String reasoningModel;

    @Autowired(required = false)
    private LlmEndpointService llmEndpointService;

    // ADR 0025 / IMPL-ui-composition Phase 1: the LangGraph4j orchestration
    // driver. `pulse.chat.orchestration=graph` routes chat turns through the
    // StateGraph (GraphDriver — the REAL path: 7 stages as nodes, op-queue
    // staging, Plan-Preview gate). `=loop` remains a temporary rollback switch
    // for the proven single tool-loop (handleLLMMode). @Lazy breaks the
    // Chat<->Graph cycle.
    @Value("${pulse.chat.orchestration:graph}")
    private String orchestrationMode;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.pulse.chat.orchestration.GraphDriver graphDriver;

    @Autowired(required = false)
    private com.pulse.command.service.PlanService planServiceRef;

    // Phase 7 (snapshot/revert + compaction): the chat-history truncation half of
    // a Restore (the n8n truncateMessagesAfter analogue). Field-injected (like
    // graphDriver/planServiceRef) so the constructor signature — depended on by
    // ChatServiceTest's manual wiring — is untouched.
    @Autowired(required = false)
    private ChatHistoryService chatHistoryService;

    public ChatService(ChatSessionRepository sessionRepo,
                       ChatMessageRepository messageRepo,
                       BlueprintRepository blueprintRepo,
                       SystemOfRecordRepository sorRepo,
                       DomainRepository domainRepo,
                       DatasetRepository datasetRepo,
                       PipelineRepository pipelineRepo,
                       CompositionService compositionService,
                       DbtAssetRegistryService dbtAssetRegistryService,
                       ChatToolExecutor toolExecutor,
                       PhaseDetector phaseDetector,
                       ObjectMapper objectMapper) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.blueprintRepo = blueprintRepo;
        this.sorRepo = sorRepo;
        this.domainRepo = domainRepo;
        this.datasetRepo = datasetRepo;
        this.pipelineRepo = pipelineRepo;
        this.compositionService = compositionService;
        this.dbtAssetRegistryService = dbtAssetRegistryService;
        this.toolExecutor = toolExecutor;
        this.phaseDetector = phaseDetector;
        this.objectMapper = objectMapper;
    }

    private boolean isLlmConfigured(LlmSurface surface) {
        if (llmEndpointService != null && llmEndpointService.isConfigured(surface)) {
            return llmEndpointService.isConfigured(surface);
        }
        return apiKey != null && !apiKey.isBlank();
    }

    private String modelFor(LlmSurface surface, String fallbackModel) {
        if (llmEndpointService != null && llmEndpointService.isConfigured(surface)) {
            return llmEndpointService.model(surface);
        }
        return fallbackModel;
    }

    private HttpURLConnection openLlmConnection(LlmSurface surface, String title) throws IOException {
        if (llmEndpointService != null && llmEndpointService.isConfigured(surface)) {
            return llmEndpointService.openChatCompletionsConnection(surface, title);
        }
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/chat/completions").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        return conn;
    }

    public ChatSession createSession(String tenantId, String userId, String pipelineId, String title) {
        ChatSession session = new ChatSession();
        session.setTenantId(tenantId);
        session.setUserId(userId);
        session.setPipelineId(pipelineId);
        session.setTitle(title != null ? title : "New conversation");
        return sessionRepo.save(session);
    }

    public List<ChatSession> listSessions(String tenantId) {
        return sessionRepo.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    public List<ChatMessage> getMessages(String sessionId) {
        return messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    public ChatSession getSession(String sessionId) {
        return sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("ChatSession", sessionId));
    }

    public Optional<ChatSession> getLatestSession(String tenantId, String userId) {
        List<ChatSession> sessions = sessionRepo.findByTenantIdAndUserIdOrderByUpdatedAtDesc(tenantId, userId);
        return sessions.isEmpty() ? Optional.empty() : Optional.of(sessions.get(0));
    }

    /**
     * One-shot call to the reasoning model (o4-mini) for heavy-lift analysis.
     * Used by tool executors that need deeper reasoning (DQ suggestions, pipeline planning).
     */
    @SuppressWarnings("unchecked")
    public String reasoningCall(String systemPrompt, String userPrompt) {
        if (!isLlmConfigured(LlmSurface.CHAT_REASONING)) {
            return "Reasoning model not available (no API key configured).";
        }
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelFor(LlmSurface.CHAT_REASONING, reasoningModel));
            requestBody.put("messages", List.of(
                    Map.of("role", "developer", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));

            HttpURLConnection conn = openLlmConnection(LlmSurface.CHAT_REASONING, "PULSE Chat Reasoning");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(objectMapper.writeValueAsBytes(requestBody));
            }

            String responseBody;
            try (var is = conn.getInputStream()) {
                responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            var parsed = objectMapper.readValue(responseBody, Map.class);
            var choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices != null && !choices.isEmpty()) {
                var message = (Map<String, Object>) choices.get(0).get("message");
                return (String) message.get("content");
            }
            return "No response from reasoning model.";
        } catch (Exception e) {
            log.error("Reasoning model call failed", e);
            return "Reasoning model error: " + e.getMessage();
        }
    }

    public SseEmitter sendMessage(String sessionId, String userContent, String tenantId) {
        ChatSession session = getSession(sessionId);

        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("USER");
        userMsg.setContent(userContent);
        messageRepo.save(userMsg);

        // SseEmitter timeout is the MAX time the emitter can be open
        // (it does NOT reset on activity — flowing data won't extend it).
        // For a full agent turn that involves multiple tool calls (each
        // ~few seconds of LLM thinking + tool exec) plus a long
        // prose-generation response, 120s was insufficient: an E2E run
        // (#11) hit the timeout mid-message; the connection closed,
        // frontend's reader saw `done`, but the backend's executor kept
        // running and wrote the full message to DB — leaving the user
        // with a partial-content render that only a hard refresh would
        // recover. 600s = 10 min covers all observed agent runs and is
        // the standard upper bound for LLM streams in practice.
        SseEmitter emitter = new SseEmitter(600_000L);
        final var emitterDead = new java.util.concurrent.atomic.AtomicBoolean(false);
        emitter.onCompletion(() -> emitterDead.set(true));
        emitter.onTimeout(() -> emitterDead.set(true));
        emitter.onError(e -> emitterDead.set(true));

        executor.submit(() -> {
            try {
                if (!isLlmConfigured(LlmSurface.CHAT)) {
                    handleLocalMode(sessionId, userContent, tenantId, emitter);
                } else if (graphMode() && graphDriver != null) {
                    handleGraphMode(sessionId, tenantId, session.getPipelineId(), emitter, emitterDead);
                } else {
                    handleLLMMode(sessionId, tenantId, session.getPipelineId(), emitter, emitterDead);
                }
            } catch (Exception e) {
                log.error("Chat streaming error for session {}", sessionId, e);
                // BUG-2026-05-25-57: emit a STRUCTURED SSE error event so the chat
                // panel can render the real cause inline. Do NOT call
                // completeWithError — that propagates to GlobalExceptionHandler,
                // which tries to write a ProblemDetail (application/problem+json)
                // to an already-committed text/event-stream response and fails.
                writeSseError(emitter, emitterDead, e);
                if (!emitterDead.get()) {
                    try { emitter.complete(); } catch (Exception ignored) {}
                }
            }
        });

        return emitter;
    }

    /**
     * Emit a structured SSE {@code event: error} frame with a JSON payload.
     * Shape: {@code {"code":"...", "message":"...", "cause":"...", "upstream":"..."}}.
     *
     * The frontend ({@code chat-panel.tsx}) reads {@code event: error} frames and
     * renders the structured fields inline (replacing the legacy generic
     * "Sorry, I encountered an error." message).
     */
    private void writeSseError(SseEmitter emitter,
                               java.util.concurrent.atomic.AtomicBoolean dead,
                               Throwable error) {
        if (dead.get()) return;
        try {
            String code;
            String upstream = null;
            if (error instanceof ChatStreamingException cse) {
                code = cse.getCode();
                upstream = cse.getUpstreamBody();
            } else if (error instanceof IOException) {
                code = ChatStreamingException.CODE_STREAM_INTERRUPTED;
            } else {
                code = ChatStreamingException.CODE_STREAM_ERROR;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code);
            payload.put("message", error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
            payload.put("cause", causeChain(error));
            if (upstream != null && !upstream.isBlank()) {
                payload.put("upstream", upstream);
            }
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name("error").data(json, MediaType.APPLICATION_JSON));
        } catch (Exception sendFailure) {
            dead.set(true);
            log.debug("Failed to write SSE error frame: {}", sendFailure.getMessage());
        }
    }

    /**
     * Compact human-readable cause chain (root cause first). Used inside the
     * structured SSE error payload so operators can see exactly what failed.
     */
    private static String causeChain(Throwable t) {
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

    private void handleLocalMode(String sessionId, String userContent, String tenantId, SseEmitter emitter) throws IOException {
        String reply = generateLocalReply(userContent, tenantId);
        emitter.send(SseEmitter.event().name("chunk").data(reply));

        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSessionId(sessionId);
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setContent(reply);
        messageRepo.save(assistantMsg);

        emitter.send(SseEmitter.event().name("done").data(""));
        emitter.complete();
    }

    @SuppressWarnings("unchecked")
    private void safeSend(SseEmitter emitter, java.util.concurrent.atomic.AtomicBoolean dead, SseEmitter.SseEventBuilder event) {
        if (dead.get()) return;
        try {
            emitter.send(event);
        } catch (Exception e) {
            dead.set(true);
            log.debug("Client disconnected during SSE send: {}", e.getMessage());
        }
    }

    /**
     * Public SSE seam for the graph driver ({@code GraphDriver}, ADR 0025): emit
     * a named event through the same dead-aware path the legacy loop uses, so the
     * graph stages emit the SAME events the frontend already reads
     * (chunk/tool_call/tool_result/navigate/candidate_graph/plan/done/error).
     */
    public void emitEvent(SseEmitter emitter, java.util.concurrent.atomic.AtomicBoolean dead,
                          String eventName, String data) {
        safeSend(emitter, dead, SseEmitter.event().name(eventName).data(data == null ? "" : data));
    }

    /** Public navigation-path resolver for the graph driver (navigate_ui intent). */
    public String resolveNavigationPathForUiIntent(String page, String resourceId) {
        return navigationPathForUiIntent(page, resourceId);
    }

    /**
     * Rebuild the OpenAI/OpenRouter message list for a session from persisted
     * chat history, prepending the system prompt. Factored out of
     * {@code handleLLMMode} (ADR 0025 / IMPL-ui-composition Phase 1) so the
     * graph driver ({@code GraphDriver}) reuses the SAME history rebuild —
     * including the BUG-2026-05-27 orphan-{@code tool}-message pairing guard —
     * without duplicating it. Returns a mutable list the caller appends to.
     */
    public List<Map<String, Object>> rebuildMessagesForSession(String sessionId, String tenantId, String pipelineId) {
        String systemPrompt = buildSystemPrompt(tenantId, pipelineId, sessionId);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        appendRebuiltHistory(messages, sessionId);
        return messages;
    }

    /**
     * Append the rebuilt persisted history (assistant/tool/user) to
     * {@code messages}, preserving the BUG-2026-05-27 orphan-tool pairing guard.
     */
    @SuppressWarnings("unchecked")
    private void appendRebuiltHistory(List<Map<String, Object>> messages, String sessionId) {
        List<ChatMessage> history = messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // BUG-2026-05-27-CHAT-TURN2-CALL-ID-MISMATCH: track every tool_calls[].id
        // we have emitted into the rebuilt history so we can validate that any
        // subsequent `tool` message references a real call. If an orphan tool
        // message slips through (id mismatch or assistant turn missing/empty),
        // Azure/OpenRouter rejects the whole request with
        // "No tool call found for function call output with call_id X."
        // We drop the orphan tool message instead of forwarding the mismatch.
        java.util.Set<String> knownToolCallIds = new java.util.HashSet<>();

        for (var msg : history) {
            String role = msg.getRole().toLowerCase();
            if ("assistant".equals(role) && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                // BUG-2026-05-25-56: PULSE stores tool_calls JSONB as
                // {"calls": [...]} (internal wrapper). OpenAI/OpenRouter expects
                // a BARE array. unwrapToolCalls() safely extracts the inner
                // list and returns null for legacy/empty/mis-shaped rows so we
                // OMIT the field entirely rather than send "tool_calls": null
                // (which OpenRouter rejects with HTTP 400).
                Map<String, Object> m = new HashMap<>();
                m.put("role", "assistant");
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
                List<?> tcOut = unwrapToolCalls(msg.getToolCalls());
                if (tcOut != null) {
                    m.put("tool_calls", tcOut);
                    // Record every id this assistant turn introduces so the
                    // pairing-validity check below can recognise legal tool
                    // responses.
                    for (Object tc : tcOut) {
                        if (tc instanceof Map<?, ?> tcMap) {
                            Object id = tcMap.get("id");
                            if (id != null && !id.toString().isBlank()) {
                                knownToolCallIds.add(id.toString());
                            }
                        }
                    }
                }
                messages.add(m);
            } else if ("tool".equals(role)) {
                // Rebuild tool result message. content is always a string per
                // OpenAI spec; tool_call_id is pulled out of the stored
                // toolCalls map. Defensive: missing/empty map → empty id (the
                // assistant turn just above should always have set it).
                Object tcid = (msg.getToolCalls() != null) ? msg.getToolCalls().get("tool_call_id") : null;
                String tcidStr = tcid == null ? "" : tcid.toString();
                // BUG-2026-05-27 pairing check: drop the tool message if its
                // tool_call_id does not match any tool_calls[].id we have
                // emitted for the assistant turns so far. Forwarding the
                // mismatch causes the upstream LLM to 400 with
                // "no tool call found for function call output."
                if (tcidStr.isBlank() || !knownToolCallIds.contains(tcidStr)) {
                    log.warn("Dropping orphan tool message during turn-2+ rebuild "
                            + "(sessionId={}, tool_call_id='{}', knownIds={}). "
                            + "This is the BUG-2026-05-27 pairing guard preventing "
                            + "Azure/OpenRouter from returning 'no tool call found "
                            + "for function call output'.",
                            sessionId, tcidStr, knownToolCallIds);
                    continue;
                }
                Map<String, Object> m = new HashMap<>();
                m.put("role", "tool");
                m.put("tool_call_id", tcidStr);
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
                messages.add(m);
            } else {
                messages.add(Map.of("role", role, "content", msg.getContent() != null ? msg.getContent() : ""));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleLLMMode(String sessionId, String tenantId, String pipelineId, SseEmitter emitter, java.util.concurrent.atomic.AtomicBoolean emitterDead) throws Exception {
        List<Map<String, Object>> messages = rebuildMessagesForSession(sessionId, tenantId, pipelineId);

        int toolRounds = 0;
        while (toolRounds < MAX_TOOL_ROUNDS) {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelFor(LlmSurface.CHAT, model));
            requestBody.put("messages", messages);
            requestBody.put("tools", ChatTools.getToolDefinitions());
            requestBody.put("stream", true);
            // Explicit max_tokens guard against silent finish_reason='length'
            // truncation. Without this, OpenRouter uses the model's default
            // which varies by model (some default to 4k or less). 16k covers
            // long agent turns with multiple tables / tool reasoning. If a
            // response ever hits this ceiling it'll still truncate, but we
            // log it via the finish_reason check below so it's diagnosable.
            requestBody.put("max_tokens", 16_000);

            // Stream from LLM and accumulate deltas
            var streamResult = streamLLM(requestBody, emitter, emitterDead);

            if (streamResult.toolCalls != null && !streamResult.toolCalls.isEmpty()) {
                // Build the assistant message with tool_calls for conversation history
                List<Map<String, Object>> tcList = new ArrayList<>();
                for (var tc : streamResult.toolCalls) {
                    tcList.add(toOutboundToolCall(tc));
                }
                Map<String, Object> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", streamResult.content != null ? streamResult.content : "");
                assistantMsg.put("tool_calls", tcList);
                messages.add(assistantMsg);

                // Persist assistant+tool_calls message
                ChatMessage tcAssistantMsg = new ChatMessage();
                tcAssistantMsg.setSessionId(sessionId);
                tcAssistantMsg.setRole("ASSISTANT");
                tcAssistantMsg.setContent(streamResult.content != null ? streamResult.content : "");
                tcAssistantMsg.setToolCalls(Map.of("calls", tcList));
                messageRepo.save(tcAssistantMsg);

                for (var tc : streamResult.toolCalls) {
                    Map<String, Object> args = objectMapper.readValue(tc.arguments, Map.class);
                    args.putIfAbsent("_session_id", sessionId);
                    args.putIfAbsent("_pipeline_id", pipelineId);

                    safeSend(emitter, emitterDead, SseEmitter.event().name("tool_call").data(tc.name));

                    if ("navigate_ui".equals(tc.name)) {
                        String page = (String) args.get("page");
                        String resourceId = (String) args.get("resource_id");
                        String navPath = navigationPathForUiIntent(page, resourceId);
                        if (navPath != null) {
                            safeSend(emitter, emitterDead, SseEmitter.event().name("navigate").data(navPath));
                        }
                    }

                    String result = toolExecutor.execute(tc.name, args, tenantId);

                    // ARCH-009: tool_result events carry the structured
                    // ToolResult JSON envelope so frontend refresh/toast can
                    // key off mutationApplied + refreshHints (not tool names).
                    com.pulse.chat.model.ToolResult envelope =
                            buildToolResultEnvelope(tc.name, args, result);
                    safeSend(emitter, emitterDead,
                            SseEmitter.event().name("tool_result")
                                    .data(envelope.toJson(objectMapper)));

                    String navPath = toolExecutor.getNavigationPath(tc.name, args, result);
                    if (navPath != null) {
                        safeSend(emitter, emitterDead, SseEmitter.event().name("navigate").data(navPath));
                    }

                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", tc.id,
                            "content", result
                    ));

                    // Persist tool result message
                    ChatMessage toolMsg = new ChatMessage();
                    toolMsg.setSessionId(sessionId);
                    toolMsg.setRole("TOOL");
                    toolMsg.setContent(result);
                    toolMsg.setToolCalls(Map.of("tool_call_id", tc.id));
                    Map<String, Object> toolResultPayload = buildToolResultPayload(sessionId, tenantId, tc.name, args, result);
                    if (!toolResultPayload.isEmpty()) {
                        toolMsg.setToolResults(toolResultPayload);
                    }
                    messageRepo.save(toolMsg);
                }
                toolRounds++;
            } else {
                // Text response was already streamed token-by-token to the frontend
                if (streamResult.content != null && !streamResult.content.isEmpty()) {
                    ChatMessage assistantMsg = new ChatMessage();
                    assistantMsg.setSessionId(sessionId);
                    assistantMsg.setRole("ASSISTANT");
                    assistantMsg.setContent(streamResult.content);
                    messageRepo.save(assistantMsg);
                }
                break;
            }
        }

        safeSend(emitter, emitterDead, SseEmitter.event().name("done").data(""));
        if (!emitterDead.get()) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    /** Whether the chat turn is driven by the LangGraph4j StateGraph (ADR 0025). */
    public boolean graphMode() {
        return "graph".equalsIgnoreCase(orchestrationMode);
    }

    /**
     * Drive a chat turn through the LangGraph4j orchestration graph (ADR 0025 /
     * IMPL-ui-composition Phase 1). Delegates to {@link com.pulse.chat.orchestration.GraphDriver},
     * which runs the 7 stages as graph nodes, drains the op-queue to STAGING, and
     * — when mutations are staged — creates a PREVIEW plan + emits the {@code plan}
     * gate event (Apply is the decision endpoint's job, P4). The SAME SSE events
     * the frontend reads are emitted through the retained emitter plumbing.
     */
    private void handleGraphMode(String sessionId, String tenantId, String pipelineId,
                                 SseEmitter emitter, java.util.concurrent.atomic.AtomicBoolean emitterDead) throws Exception {
        graphDriver.handleTurn(sessionId, tenantId, pipelineId, emitter, emitterDead);
    }

    /**
     * Resolve a Plan-Preview decision (ADR 0025 / IMPL-ui-composition Phase 4) —
     * the {@code POST /api/v1/chat/sessions/{sessionId}/plans/{planId}/decision}
     * transport, the {@code interruptBefore}-resume analogue:
     * <ul>
     *   <li><b>approve</b> → approve the plan (session-scoped) then apply it
     *       ({@code apply_plan} canonical write — the composition.* commands).</li>
     *   <li><b>reject</b> → cancel/discard the plan; no canonical write.</li>
     *   <li><b>modify</b> → cancel the prior plan and mark for a composer rebuild
     *       (capped at {@link com.pulse.chat.orchestration.PlanGate#MAX_PLAN_MODIFY_ITERATIONS});
     *       the next chat turn re-stages from the feedback.</li>
     * </ul>
     * Returns a small status map for the JSON response.
     */
    public Map<String, Object> decidePlan(String sessionId, String planId,
                                          String decisionRaw, String feedback) {
        if (planServiceRef == null) {
            throw new IllegalStateException("PlanService is not available");
        }
        com.pulse.chat.orchestration.PlanGate.Decision decision =
                com.pulse.chat.orchestration.PlanGate.Decision.parse(decisionRaw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        out.put("decision", decision.name().toLowerCase());
        switch (decision) {
            case APPROVE -> {
                planServiceRef.approveForSession(planId, sessionId, null);
                var applied = planServiceRef.apply(planId);
                out.put("status", applied.getStatus().name());
                out.put("commandIds", applied.getCommandIds() == null ? List.of() : applied.getCommandIds());
                out.put("mutationApplied", applied.getStatus() == com.pulse.command.model.PlanStatus.APPLIED);
            }
            case REJECT -> {
                // Pre-Apply revert (Phase 7 §2.2): discard the STAGING graph; the
                // CANONICAL graph is untouched (write-locked behind Apply). Cancel
                // the PREVIEW plan and hand the client the canonical snapshot to
                // re-render from (drop the staging clone). No canonical write.
                var cancelled = planServiceRef.cancel(planId);
                out.put("status", cancelled.getStatus().name());
                out.put("mutationApplied", false);
                out.put("restoredGraph", canonicalGraphForSession(sessionId));
            }
            case MODIFY -> {
                // Discard the prior plan; the next chat turn re-stages from feedback.
                var cancelled = planServiceRef.cancel(planId);
                out.put("status", cancelled.getStatus().name());
                out.put("rebuild", true);
                out.put("feedback", feedback == null ? "" : feedback);
                out.put("restoredGraph", canonicalGraphForSession(sessionId));
            }
        }
        return out;
    }

    /**
     * Restore a session to a turn (IMPL-ui-composition Phase 7; 07-orchestration
     * §2.2) — the {@code POST /chat/sessions/{sessionId}/turns/{anchorMessageId}/restore}
     * handler. <b>Undo = restore the checkpoint snapshot, NOT an inverse plan</b>
     * (ADR 0025 §3 / SPEC §7.16 #13):
     * <ol>
     *   <li>truncate the chat back to the turn's anchor message — delete the anchor
     *       and every message after it (the n8n {@code truncateMessagesAfter}
     *       analogue, {@code [read I]});</li>
     *   <li>restore the version's composition from the per-turn snapshot. The
     *       snapshot is the {@code COMPOSITION_VIEW} the checkpointer round-trips;
     *       since canonical is write-locked behind Apply, when nothing was applied
     *       the canonical already IS the snapshot (a no-op restore). For an
     *       already-applied turn, the snapshot is reconstructed onto canonical;</li>
     *   <li>reset the orchestration phase to a fresh build baseline (the next
     *       message builds, not mis-routes on an empty staging graph; the n8n
     *       restore -> build-mode reset).</li>
     * </ol>
     * Returns a small status map (deleted message count + the restored graph).
     */
    public Map<String, Object> restoreToTurn(String sessionId, String anchorMessageId) {
        ChatSession session = getSession(sessionId);
        String pipelineId = session.getPipelineId();

        int deleted = (chatHistoryService != null)
                ? chatHistoryService.truncateMessagesAfter(sessionId, anchorMessageId)
                : 0;

        Map<String, Object> restoredGraph = Map.of();
        if (graphDriver != null && pipelineId != null) {
            Map<String, Object> snapshot = restoreSnapshotForSession(sessionId, pipelineId);
            // Reconstruct canonical from the snapshot (deterministic snapshot
            // restore — handles the already-applied case; a no-op when canonical
            // already matches the snapshot, i.e. nothing was applied).
            restoredGraph = graphDriver.restoreCompositionFromSnapshot(pipelineId, snapshot);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("anchorMessageId", anchorMessageId);
        out.put("deletedMessageCount", deleted);
        // Phase reset to a fresh build baseline (the next turn builds).
        out.put("phase", "build");
        out.put("restoredGraph", restoredGraph);
        return out;
    }

    /**
     * Resolve the per-turn revert snapshot ({@code COMPOSITION_VIEW}) for a
     * session. Tries the checkpointer (the snapshot STORE — {@code getState} on
     * the latest turn checkpoint, which carries the turn-start canonical
     * snapshot); falls back to the current canonical when no checkpoint state is
     * available (the common pre-Apply case, where canonical IS the snapshot).
     */
    private Map<String, Object> restoreSnapshotForSession(String sessionId, String pipelineId) {
        // The checkpointer IS the snapshot store: getState on the session thread
        // returns the turn-start COMPOSITION_VIEW. langgraph4j types stay inside
        // GraphDriver; the durable round-trip is asserted in the postgres-lane IT.
        Map<String, Object> snap = graphDriver.latestCheckpointCompositionView(sessionId);
        if (snap != null && snap.containsKey("instances")) {
            return snap;
        }
        // Fallback: the current canonical (write-locked behind Apply, so for an
        // un-applied turn this equals the turn-start snapshot).
        return graphDriver.canonicalGraphPayload(pipelineId);
    }

    /**
     * The current canonical composition for a session's pipeline, as a graph
     * payload — the "re-render from the snapshot" content a pre-Apply revert
     * (reject/modify) returns to the client. Empty when no graph driver or
     * pipeline resolves.
     */
    private Map<String, Object> canonicalGraphForSession(String sessionId) {
        try {
            if (graphDriver == null) return Map.of();
            ChatSession session = getSession(sessionId);
            String pipelineId = session.getPipelineId();
            if (pipelineId == null) return Map.of();
            return graphDriver.canonicalGraphPayload(pipelineId);
        } catch (Exception e) {
            log.debug("canonicalGraphForSession({}) -> empty ({})", sessionId, e.getMessage());
            return Map.of();
        }
    }

    private String navigationPathForUiIntent(String page, String resourceId) {
        return switch (page) {
            case "data_sources" -> "/producers";
            case "data_source_detail" -> detailNavigationPath("/producers", resourceId);
            case "pipelines" -> "/pipelines";
            case "pipeline_detail" -> detailNavigationPath("/pipelines", resourceId);
            case "blueprints" -> "/blueprints";
            case "commands" -> "/commands";
            default -> null;
        };
    }

    private String detailNavigationPath(String basePath, String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return null;
        }
        if (resourceId.startsWith("draft:")) {
            log.warn("Suppressing navigate_ui detail route for draft resource id '{}'. "
                    + "Draft refs are preview labels and are not routable product ids.",
                    resourceId);
            return null;
        }
        return basePath + "/" + resourceId;
    }

    /**
     * Safely unwrap the {@code tool_calls} JSONB stored on a chat_messages row
     * into the bare {@code List} that OpenAI/OpenRouter expects on the outbound
     * chat-completions request.
     *
     * PULSE persists tool_calls as {@code {"calls": [...]}} (see
     * {@code handleLLMMode} where the assistant message is saved). The
     * OpenAI/OpenRouter API wants {@code "tool_calls": [...]} — a JSON array
     * directly. If the wrapper is missing, malformed, or empty, this helper
     * returns {@code null} so the caller OMITS the field entirely instead of
     * emitting {@code "tool_calls": null}, which OpenRouter rejects with HTTP
     * 400 {@code invalid_request_error: tool_calls must be an array}.
     *
     * Visible for testing.
     */
    static List<?> unwrapToolCalls(Map<String, Object> stored) {
        if (stored == null || stored.isEmpty()) return null;
        Object calls = stored.get("calls");
        if (calls instanceof List<?> list && !list.isEmpty()) return list;
        // Defensive: legacy rows might have stored the array directly without
        // a wrapper. We can't safely guess at that shape here (the Map type
        // means we'd be inspecting key/value pairs, not a List), so return
        // null and OMIT the field. This is safer than risking another 400.
        return null;
    }

    public record ToolCallAccumulator(String id, String name, String arguments, Map<String, Object> extraContent) {}
    public record StreamResult(String content, List<ToolCallAccumulator> toolCalls) {}

    public static Map<String, Object> toOutboundToolCall(ToolCallAccumulator tc) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", tc.id);
        wire.put("type", "function");
        wire.put("function", Map.of("name", tc.name, "arguments", tc.arguments));
        if (tc.extraContent != null && !tc.extraContent.isEmpty()) {
            wire.put("extra_content", tc.extraContent);
        }
        return wire;
    }

    /**
     * Streams the LLM response. Text deltas are forwarded to the frontend immediately.
     * Tool call deltas are buffered and returned for execution.
     */
    @SuppressWarnings("unchecked")
    private StreamResult streamLLM(Map<String, Object> requestBody, SseEmitter emitter, java.util.concurrent.atomic.AtomicBoolean emitterDead) throws Exception {
        // Backward-compatible overload: the legacy single-loop path streams on
        // the primary CHAT surface.
        return streamLLM(LlmSurface.CHAT, requestBody, emitter, emitterDead);
    }

    /**
     * The graph-stage streaming seam (ADR 0025 §4) the {@code NodeLlmAdapter}
     * calls. Builds the chat-completions request body for {@code surface}
     * (resolving the per-stage model tier), streams it through the single
     * replay-preserving {@link #streamLLM(LlmSurface, Map, SseEmitter, java.util.concurrent.atomic.AtomicBoolean)}
     * seam, and returns the accumulated text + structured tool calls. Tool
     * execution + history persistence stay in the graph driver, NOT here.
     */
    public StreamResult streamStage(LlmSurface surface,
                                    List<Map<String, Object>> messages,
                                    SseEmitter emitter,
                                    java.util.concurrent.atomic.AtomicBoolean emitterDead) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelFor(surface, surface == LlmSurface.CHAT_REASONING ? reasoningModel : model));
        requestBody.put("messages", messages);
        requestBody.put("tools", ChatTools.getToolDefinitions());
        requestBody.put("stream", true);
        requestBody.put("max_tokens", 16_000);
        return streamLLM(surface, requestBody, emitter, emitterDead);
    }

    /** Whether the LLM seam is configured for a given stage surface. */
    public boolean isStageConfigured(LlmSurface surface) {
        return isLlmConfigured(surface);
    }

    /**
     * Surface-parameterized streaming (ADR 0025 §4): each graph stage node
     * resolves its OWN model tier (CHAT / CHAT_REASONING / CHAT_CHEAP) and
     * streams through this single seam — preserving structured tool-call replay
     * (completed calls stay {@code function} entries via {@link #toOutboundToolCall})
     * and the Vertex thought-signature ({@code extra_content.google.thought_signature}
     * captured + replayed unchanged). The replay machinery below is UNCHANGED;
     * only the connection's surface is parameterized.
     */
    @SuppressWarnings("unchecked")
    StreamResult streamLLM(LlmSurface surface, Map<String, Object> requestBody, SseEmitter emitter, java.util.concurrent.atomic.AtomicBoolean emitterDead) throws Exception {
        HttpURLConnection conn = openLlmConnection(surface, "PULSE Chat");
        conn.setConnectTimeout(30_000);
        // Read timeout = max time waiting for next byte from OpenRouter,
        // not total stream time. LLMs occasionally pause for tens of
        // seconds between deltas (especially when picking the next tool
        // call or after a tool result). 120s caused #11 mid-stream
        // truncation: model paused, read threw SocketTimeoutException,
        // loop exited, partial 666-byte response persisted silently.
        // 600s matches the SseEmitter timeout we set upstream so the two
        // budgets align.
        conn.setReadTimeout(600_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(objectMapper.writeValueAsBytes(requestBody));
        }

        // BUG-2026-05-25-57: on non-2xx, capture the upstream provider's error
        // body (e.g. OpenRouter's JSON `error` envelope) and propagate it via a
        // typed ChatStreamingException so writeSseError can surface the real
        // cause to the chat panel. Without this, all the operator sees is
        // java.io.IOException's generic "Server returned HTTP response code:
        // 400" — the actual reason ("tool_calls must be an array" etc.) is
        // dropped on the floor.
        int responseCode = conn.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            String upstream = readErrorBody(conn);
            throw new ChatStreamingException(
                    ChatStreamingException.CODE_UPSTREAM_LLM_ERROR,
                    "LLM provider returned HTTP " + responseCode,
                    upstream,
                    null);
        }

        StringBuilder contentAccum = new StringBuilder();
        Map<Integer, String> toolCallIds = new HashMap<>();
        Map<Integer, String> toolCallNames = new HashMap<>();
        Map<Integer, StringBuilder> toolCallArgs = new HashMap<>();
        Map<Integer, Map<String, Object>> toolCallExtraContent = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                try {
                    var chunk = objectMapper.readValue(data, Map.class);
                    var choices = (List<Map<String, Object>>) chunk.get("choices");
                    if (choices == null || choices.isEmpty()) continue;

                    var choice = choices.get(0);
                    // Detect silent truncation. If finish_reason is set
                    // and != 'stop'/'tool_calls', log it so we can
                    // diagnose. 'length' means max_tokens was hit and the
                    // response is incomplete.
                    Object finishReason = choice.get("finish_reason");
                    if (finishReason != null && !"stop".equals(finishReason)
                            && !"tool_calls".equals(finishReason)) {
                        log.warn("LLM stream finish_reason={} — response may be truncated. "
                                + "accum_chars={}", finishReason, contentAccum.length());
                    }
                    var delta = (Map<String, Object>) choice.get("delta");
                    if (delta == null) continue;

                    // Stream text content to frontend
                    String textDelta = (String) delta.get("content");
                    if (textDelta != null) {
                        contentAccum.append(textDelta);
                        safeSend(emitter, emitterDead, SseEmitter.event().name("chunk").data(textDelta));
                    }

                    // Buffer tool call deltas
                    var tcDeltas = (List<Map<String, Object>>) delta.get("tool_calls");
                    if (tcDeltas != null) {
                        for (var tcd : tcDeltas) {
                            int idx = tcd.get("index") != null ? ((Number) tcd.get("index")).intValue() : 0;
                            if (tcd.get("id") != null) {
                                toolCallIds.put(idx, (String) tcd.get("id"));
                            }
                            Object extraContent = tcd.get("extra_content");
                            if (extraContent instanceof Map<?, ?> extraMap && !extraMap.isEmpty()) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> normalized = objectMapper.convertValue(extraMap, Map.class);
                                toolCallExtraContent.put(idx, normalized);
                            }
                            var fn = (Map<String, Object>) tcd.get("function");
                            if (fn != null) {
                                if (fn.get("name") != null) {
                                    toolCallNames.put(idx, (String) fn.get("name"));
                                }
                                if (fn.get("arguments") != null) {
                                    toolCallArgs.computeIfAbsent(idx, k -> new StringBuilder())
                                            .append(fn.get("arguments"));
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Was log.debug — silent by default. ANY chunk that
                    // fails to parse silently drops its content. User
                    // surfaced empty-row-4 truncation in #11 E2E that
                    // wasn't explained by any timeout / max_tokens
                    // theory; this silent path is a plausible cause.
                    // Bumped to warn + exception type/message so future
                    // truncations are at least visible in backend logs.
                    log.warn("Failed to parse stream chunk ({}): data={}",
                            e.getClass().getSimpleName() + ": " + e.getMessage(),
                            data.length() > 240 ? data.substring(0, 240) + "…" : data);
                }
            }
        }

        // Build tool call list if any
        List<ToolCallAccumulator> toolCalls = null;
        if (!toolCallIds.isEmpty()) {
            toolCalls = new ArrayList<>();
            for (var idx : toolCallIds.keySet()) {
                String id = toolCallIds.get(idx);
                String name = toolCallNames.getOrDefault(idx, "unknown");
                String args = toolCallArgs.containsKey(idx) ? toolCallArgs.get(idx).toString() : "{}";
                toolCalls.add(new ToolCallAccumulator(id, name, args, toolCallExtraContent.get(idx)));
            }
        }

        String content = contentAccum.length() > 0 ? contentAccum.toString() : null;
        return new StreamResult(content, toolCalls);
    }

    /**
     * Best-effort read of an upstream HTTP error body. Returns the body string
     * (possibly empty) or {@code null} on read failure. Defensive: never throws.
     */
    private static String readErrorBody(HttpURLConnection conn) {
        try {
            var stream = conn.getErrorStream();
            if (stream == null) stream = conn.getInputStream();
            if (stream == null) return null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                    if (sb.length() > 4096) break; // cap payload
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private String callLLM(Map<String, Object> requestBody) throws Exception {
        HttpURLConnection conn = openLlmConnection(LlmSurface.CHAT, "PULSE Chat");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(objectMapper.writeValueAsBytes(requestBody));
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    @SuppressWarnings("unchecked")
    String buildSystemPrompt(String tenantId, String pipelineId) {
        return buildSystemPrompt(tenantId, pipelineId, null);
    }

    /**
     * Public accessor for the per-turn CONTEXT/system-prompt block, so the graph
     * path's per-stage assembler ({@code com.pulse.chat.prompt.StagePromptAssembler})
     * can REUSE the live dataset-schema / domain / pipeline / session-fact
     * injection rather than duplicating it (IMPL-ui-composition Phase 8 task 4/6).
     * Delegates to the package-private {@link #buildSystemPrompt(String,String,String)};
     * the legacy single-loop path keeps calling the package-private form unchanged.
     */
    public String buildTurnContextPrompt(String tenantId, String pipelineId, String sessionId) {
        return buildSystemPrompt(tenantId, pipelineId, sessionId);
    }

    @SuppressWarnings("unchecked")
    String buildSystemPrompt(String tenantId, String pipelineId, String sessionId) {
        var domains = domainRepo.findByTenantIdOrderByNameAsc(tenantId);
        var sors = sorRepo.findByTenantIdOrderByNameAsc(tenantId);
        var datasets = datasetRepo.findByTenantIdOrderByQualifiedNameAsc(tenantId);
        var blueprints = blueprintRepo.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active");

        StringBuilder domainSummary = new StringBuilder();
        for (var domain : domains) {
            domainSummary.append("- **").append(domain.getName()).append("**");
            if (domain.getCurrentBusinessDate() != null) {
                domainSummary.append(" | business_date=").append(domain.getCurrentBusinessDate());
            }
            if (domain.getBusinessDateGrain() != null) {
                domainSummary.append(" | grain=").append(domain.getBusinessDateGrain());
            }
            domainSummary.append(" | id=`").append(domain.getId()).append("`\n");
        }
        if (domains.isEmpty()) domainSummary.append("None registered.\n");

        StringBuilder sorSummary = new StringBuilder();
        for (var sor : sors) {
            sorSummary.append("- **").append(sor.getName()).append("** (").append(sor.getDomainName())
                    .append(") ID: `").append(sor.getId()).append("`\n");
        }
        if (sors.isEmpty()) sorSummary.append("None registered.\n");

        StringBuilder dsSummary = new StringBuilder();
        for (var ds : datasets) {
            dsSummary.append("- `").append(ds.getQualifiedName()).append("` [").append(ds.getSchemaFormat()).append("]");
            if (ds.getClassification() != null) dsSummary.append(" (").append(ds.getClassification()).append(")");
            dsSummary.append(" ID: `").append(ds.getId()).append("`\n");

            // Include schema column summaries for AI reasoning
            if (ds.getSchemaSnapshot() != null) {
                Object fields = ds.getSchemaSnapshot().get("fields");
                if (fields instanceof List<?> fieldList && !fieldList.isEmpty()) {
                    dsSummary.append("  Schema: ");
                    int count = 0;
                    for (Object f : fieldList) {
                        if (f instanceof Map<?, ?> fm) {
                            if (count > 0) dsSummary.append(", ");
                            dsSummary.append(fm.get("name")).append("(").append(fm.get("type")).append(")");
                            count++;
                        }
                    }
                    dsSummary.append("\n");
                }
            }

            // Include data classification info
            if (ds.getClassification() != null) {
                dsSummary.append("  Classification: ").append(ds.getClassification()).append("\n");
            }

            // Include temporal/periodicity metadata if available in definition_config
            if (ds.getDefinitionConfig() != null) {
                Object temporalMetadata = ds.getDefinitionConfig().get("temporal_metadata");
                if (temporalMetadata instanceof Map<?, ?> tm) {
                    dsSummary.append("  Temporal: ");
                    if (tm.get("time_grain") != null) dsSummary.append("grain=").append(tm.get("time_grain"));
                    if (tm.get("file_naming_pattern") != null) dsSummary.append(", pattern=").append(tm.get("file_naming_pattern"));
                    if (tm.get("arrival_cron") != null) dsSummary.append(", arrival=").append(tm.get("arrival_cron"));
                    if (tm.get("arrival_timezone") != null) dsSummary.append(", tz=").append(tm.get("arrival_timezone"));
                    dsSummary.append("\n");
                }
            }
        }
        if (datasets.isEmpty()) dsSummary.append("None defined yet. User needs to define datasets on the Data Sources page first.\n");

        StringBuilder bpSummary = new StringBuilder();
        String currentCat = "";
        for (var bp : blueprints) {
            if (!bp.getCategory().name().equals(currentCat)) {
                currentCat = bp.getCategory().name();
                bpSummary.append("\n**").append(currentCat).append("**\n");
            }
            bpSummary.append("- `").append(bp.getBlueprintKey()).append("`: ").append(bp.getDescription(), 0, Math.min(80, bp.getDescription().length())).append("\n");
        }

        String pipelineSummary = buildPipelineSummary(tenantId, pipelineId);
        String dbtAssetSummary = buildDbtAssetSummary(tenantId, pipelineId);
        String targetedGenerationSummary = buildTargetedGenerationSummary(tenantId, pipelineId);
        String sessionFactSummary = buildStructuredFactSummary(sessionId);

        ConversationPhase phase = phaseDetector != null
                ? phaseDetector.detect(sessionId, pipelineId, tenantId)
                : ConversationPhase.DISCOVERY;

        StringBuilder sb = new StringBuilder();
        sb.append(PulseSystemPrompt.IDENTITY);
        sb.append(PulseSystemPrompt.ABSOLUTE_RULES);
        sb.append(PulseSystemPrompt.RUNTIME_FIELDS_PUNCH_LIST);
        sb.append(PulseSystemPrompt.CONNECTOR_VOCABULARY);
        sb.append(PulseSystemPrompt.MEDALLION_RULES);
        sb.append(PulseSystemPrompt.DBT_ANNOTATIONS);
        sb.append(PulseSystemPrompt.WORKFLOW_PACKET);
        sb.append(PulseSystemPrompt.PLANNER_PACKET);
        sb.append(PulseSystemPrompt.TOOL_GUIDELINES);

        if (phase == ConversationPhase.REVIEW_BUILD) {
            sb.append(PulseSystemPrompt.GENERATION_PACKET);
        }
        if (phase == ConversationPhase.SILVER
                || phase == ConversationPhase.GOLD
                || phase == ConversationPhase.DQ
                || phase == ConversationPhase.REVIEW_BUILD) {
            sb.append(PulseSystemPrompt.REASONING_FRAMEWORK);
        }

        sb.append(PulseSystemPrompt.buildContextSection(
                domainSummary.toString(),
                pipelineSummary,
                dbtAssetSummary,
                sorSummary.toString(),
                dsSummary.toString(),
                bpSummary.toString(),
                targetedGenerationSummary,
                sessionFactSummary));
        return sb.toString();
    }

    public List<Map<String, Object>> getSessionFacts(String sessionId) {
        getSession(sessionId);
        List<Map<String, Object>> flattened = new ArrayList<>();
        for (var msg : messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId)) {
            if (msg.getToolResults() == null) continue;
            Object facts = msg.getToolResults().get("facts");
            if (!(facts instanceof List<?> factList)) continue;
            for (Object rawFact : factList) {
                if (rawFact instanceof Map<?, ?> rawMap) {
                    Map<String, Object> fact = new LinkedHashMap<>();
                    rawMap.forEach((key, value) -> fact.put(String.valueOf(key), value));
                    fact.putIfAbsent("message_id", msg.getId());
                    fact.putIfAbsent("captured_at", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
                    fact.putIfAbsent("tool_name", msg.getToolResults().get("tool_name"));
                    flattened.add(fact);
                }
            }
        }
        return flattened;
    }

    private String buildPipelineSummary(String tenantId, String pipelineId) {
        if (pipelineId == null || pipelineId.isBlank()) {
            return "No active pipeline is linked to this chat session yet.\n";
        }

        var pipeline = pipelineRepo.findById(pipelineId)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .orElse(null);
        if (pipeline == null) {
            return "Linked pipeline could not be resolved.\n";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("- **").append(pipeline.getName()).append("**");
        if (pipeline.getDomainName() != null) {
            summary.append(" | domain=").append(pipeline.getDomainName());
        }
        if (pipeline.getDomainId() != null) {
            summary.append(" | domain_id=`").append(pipeline.getDomainId()).append("`");
        }
        summary.append(" | id=`").append(pipeline.getId()).append("`\n");

        if (pipeline.getActiveVersionId() == null) {
            summary.append("  No active version yet.\n");
            return summary.toString();
        }

        summary.append("  Active version: `").append(pipeline.getActiveVersionId()).append("`\n");
        var composition = compositionService.getComposition(pipeline.getActiveVersionId());
        if (composition.instances().isEmpty()) {
            summary.append("  Composition: empty\n");
            return summary.toString();
        }

        summary.append("  Composition steps:\n");
        for (var instance : composition.instances()) {
            summary.append("  - [").append(instance.getExecutionOrder()).append("] ")
                    .append(instance.getName())
                    .append(" :: ").append(instance.getBlueprintKey()).append("\n");
        }
        if (!composition.wirings().isEmpty()) {
            summary.append("  Wirings:\n");
            Map<String, String> nameById = new HashMap<>();
            for (var instance : composition.instances()) {
                nameById.put(instance.getId(), instance.getName());
            }
            for (var wiring : composition.wirings()) {
                summary.append("  - ")
                        .append(nameById.getOrDefault(wiring.getSourceInstanceId(), wiring.getSourceInstanceId()))
                        .append(".").append(wiring.getSourcePortName())
                        .append(" -> ")
                        .append(nameById.getOrDefault(wiring.getTargetInstanceId(), wiring.getTargetInstanceId()))
                        .append(".").append(wiring.getTargetPortName())
                        .append("\n");
            }
        }
        return summary.toString();
    }

    private String buildDbtAssetSummary(String tenantId, String pipelineId) {
        if (pipelineId == null || pipelineId.isBlank()) {
            return "No pipeline-linked domain context available yet.\n";
        }

        var pipeline = pipelineRepo.findById(pipelineId)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .orElse(null);
        if (pipeline == null || pipeline.getDomainId() == null || pipeline.getDomainId().isBlank()) {
            return "No canonical domain_id is linked to the current pipeline.\n";
        }

        var assets = dbtAssetRegistryService.listDomainAssets(pipeline.getDomainId());
        if (assets.isEmpty()) {
            return "No indexed dbt assets found for the current pipeline domain.\n";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Domain `").append(pipeline.getDomainId()).append("` assets:\n");
        assets.stream().limit(12).forEach(asset -> {
            summary.append("- **").append(asset.getAssetName()).append("** (").append(asset.getAssetType()).append(")");
            if (asset.getBusinessConcept() != null) {
                summary.append(" | concept=").append(asset.getBusinessConcept());
            }
            if (asset.getGrain() != null) {
                summary.append(" | grain=").append(asset.getGrain());
            }
            if (asset.getAccessLevel() != null) {
                summary.append(" | access=").append(asset.getAccessLevel());
            }
            summary.append("\n");
        });
        if (assets.size() > 12) {
            summary.append("- ... ").append(assets.size() - 12).append(" more assets indexed\n");
        }
        return summary.toString();
    }

    private String buildTargetedGenerationSummary(String tenantId, String pipelineId) {
        if (pipelineId == null || pipelineId.isBlank()) {
            return "No active generation blueprint set is linked yet.\n";
        }

        var pipeline = pipelineRepo.findById(pipelineId)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .orElse(null);
        if (pipeline == null || pipeline.getActiveVersionId() == null) {
            return "No active generation blueprint set is linked yet.\n";
        }

        var composition = compositionService.getComposition(pipeline.getActiveVersionId());
        LinkedHashSet<String> activeBlueprintKeys = new LinkedHashSet<>();
        for (var instance : composition.instances()) {
            if (instance.getBlueprintKey() != null && !instance.getBlueprintKey().isBlank()) {
                activeBlueprintKeys.add(instance.getBlueprintKey());
            }
        }
        if (activeBlueprintKeys.isEmpty()) {
            return "No active generation blueprint set is linked yet.\n";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Active blueprint set: ").append(String.join(", ", activeBlueprintKeys)).append("\n");

        List<String> cards = new ArrayList<>();
        List<ExamplePacket> packets = new ArrayList<>();
        for (String blueprintKey : activeBlueprintKeys) {
            cards.addAll(DBT_BEST_PRACTICE_CARDS.getOrDefault(blueprintKey, List.of()));
            packets.addAll(BLUEPRINT_EXAMPLE_PACKETS.getOrDefault(blueprintKey, List.of()));
        }

        if (cards.isEmpty()) {
            summary.append("No targeted dbt best-practice cards matched the active blueprint set.\n");
        } else {
            summary.append("dbt best-practice cards:\n");
            for (int i = 0; i < Math.min(cards.size(), 6); i++) {
                summary.append("- ").append(cards.get(i)).append("\n");
            }
        }

        if (packets.isEmpty()) {
            summary.append("No blueprint example packets matched the active blueprint set.\n");
        } else {
            summary.append("Example packets:\n");
            for (ExamplePacket packet : packets) {
                summary.append("- ").append(packet.title())
                        .append(" | exa_query_pack_key=").append(packet.exaQueryPackKey())
                        .append(" | preferred_domains=").append(String.join(", ", packet.preferredDomains()))
                        .append(" | language_hint=").append(packet.languageHint())
                        .append(" | quality=").append(packet.qualityCriteria())
                        .append(" | snippet=").append(packet.snippet())
                        .append("\n");
            }
        }
        return summary.toString();
    }

    private String buildStructuredFactSummary(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "No structured session facts captured yet.\n";
        }
        List<Map<String, Object>> facts = getSessionFacts(sessionId);
        if (facts.isEmpty()) {
            return "No structured session facts captured yet.\n";
        }

        StringBuilder summary = new StringBuilder();
        int limit = Math.min(facts.size(), 8);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> fact = facts.get(i);
            summary.append("- ")
                    .append(fact.getOrDefault("fact_type", "fact"))
                    .append(" :: ")
                    .append(fact.getOrDefault("fact_key", "unknown"))
                    .append(" => ")
                    .append(summarizeFactValue(fact.get("fact_value")))
                    .append(" | status=")
                    .append(fact.getOrDefault("persistence_status", "session_only"))
                    .append("\n");
        }
        if (facts.size() > limit) {
            summary.append("- ... ").append(facts.size() - limit).append(" more structured facts captured\n");
        }
        return summary.toString();
    }

    /**
     * ARCH-009: build the structured {@link com.pulse.chat.model.ToolResult}
     * envelope for the SSE {@code tool_result} event. The envelope's
     * {@code mutationApplied} + {@code refreshHints} fields are the frontend's
     * authoritative refresh trigger; never key off the tool name.
     */
    public com.pulse.chat.model.ToolResult buildToolResultEnvelope(String toolName,
                                                            Map<String, Object> args,
                                                            String result) {
        boolean isError = result != null
                && (result.startsWith("Error") || result.startsWith("Failed"));
        if (isError) {
            return com.pulse.chat.model.ToolResult.error(toolName,
                    result.length() > 500 ? result.substring(0, 500) : result);
        }

        // apply_plan is the canonical mutator: surface plan id + command ids
        // + standard refresh hints so the frontend can drop chat/composition
        // cache.
        if ("apply_plan".equals(toolName)) {
            String planId = args == null ? null : (String) args.get("plan_id");
            List<Map<String, Object>> uiIntents = extractCredentialAttachIntents(result);
            List<String> hints = new ArrayList<>(List.of("plan", "commands", "composition", "pipeline"));
            if (!uiIntents.isEmpty()) {
                hints.add("credentials");
            }
            return com.pulse.chat.model.ToolResult.builder("apply_plan")
                    .status("ok")
                    .mutationApplied(true)
                    .planId(planId)
                    .uiIntents(uiIntents)
                    .refreshHints(hints)
                    .message(result)
                    .build();
        }

        // plan-producing tools (currently propose_*) leave product state
        // untouched; the envelope reports planCreated=true so the frontend
        // can show the preview without triggering data refresh.
        if (toolName != null && (toolName.startsWith("plan_") || toolName.startsWith("propose_"))) {
            String planId = extractTaggedValue(result, "plan_id");
            return com.pulse.chat.model.ToolResult.builder(toolName)
                    .status("planCreated")
                    .planCreated(true)
                    .planId(planId)
                    .declaredDraftRefs(extractTaggedValues(result, "declared_draft_ref"))
                    .previewCommands(extractPreviewCommands(result))
                    .refreshHints(List.of("plan", "commands"))
                    .message(result)
                    .build();
        }

        // All other tools are read-only or scoped mutators that keep their
        // own refresh semantics. Surface refresh hints derived from the tool
        // family so the frontend stays in sync without name lookups.
        List<String> hints = inferRefreshHints(toolName);
        boolean mutated = isWriteTool(toolName);
        return com.pulse.chat.model.ToolResult.builder(toolName)
                .status("ok")
                .mutationApplied(mutated)
                .refreshHints(hints)
                .message(result == null
                        ? ""
                        : (result.length() > 500 ? result.substring(0, 500) : result))
                .build();
    }

    private List<String> inferRefreshHints(String toolName) {
        if (toolName == null) return List.of();
        return switch (toolName) {
            case "create_data_source", "create_connector",
                 "list_data_sources", "list_connectors" -> List.of("data_sources");
            case "create_dataset", "list_datasets" -> List.of("datasets");
            case "create_domain", "list_domains" -> List.of("domains");
            case "create_sink_target", "list_sink_targets" -> List.of("sink_targets");
            case "list_blueprints", "get_blueprint_detail" -> List.of("blueprints");
            case "get_composition", "wire_ports", "remove_step",
                 "configure_step_params", "apply_dq_expectations" -> List.of("composition", "pipeline");
            case "update_pipeline_orchestration" -> List.of("pipeline", "orchestration");
            case "request_credential_attach" -> List.of("credentials");
            default -> List.of();
        };
    }

    private boolean isWriteTool(String toolName) {
        if (toolName == null) return false;
        return switch (toolName) {
            case "create_data_source", "create_connector", "create_dataset",
                 "create_domain", "create_sink_target", "configure_step_params",
                 "update_pipeline_orchestration", "wire_ports", "remove_step",
                 "apply_dq_expectations" -> true;
            default -> false;
        };
    }

    public Map<String, Object> buildToolResultPayload(String sessionId, String tenantId, String toolName, Map<String, Object> args, String result) {
        List<Map<String, Object>> facts = extractStructuredFacts(toolName, args, result);
        String linkedPipelineId = linkSessionToPipelineIfPresent(sessionId, tenantId, toolName, args, result);
        if (linkedPipelineId != null) {
            facts.add(0, structuredFact(
                    "link",
                    "chat.session.pipeline_link",
                    Map.of("pipeline_id", linkedPipelineId),
                    "promoted"));
        }
        if (facts.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_name", toolName);
        payload.put("success", !result.startsWith("Error"));
        payload.put("facts", facts);
        return payload;
    }

    private List<Map<String, Object>> extractStructuredFacts(String toolName, Map<String, Object> args, String result) {
        if (result == null || result.startsWith("Error")) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> facts = new ArrayList<>();
        switch (toolName) {
            case "find_dbt_reuse_candidate" -> {
                String decision = extractLineValue(result, "Decision");
                if (decision != null) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("decision", decision);
                    putIfPresent(value, "asset_name", extractLineValue(result, "Asset"));
                    putIfPresent(value, "recommended_emit_strategy", extractLineValue(result, "Recommended emit strategy"));
                    if (args.get("planning_context") instanceof Map<?, ?> planningContext) {
                        value.put("planning_context", planningContext);
                    }
                    facts.add(structuredFact("decision", "dbt.reuse_choice", value, "session_only"));
                }
            }
            case "propose_create_pipeline" -> {
                String pipelineId = extractTaggedValue(result, "internal_id");
                if (pipelineId != null) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("pipeline_id", pipelineId);
                    putIfPresent(value, "pipeline_name", extractLineValue(result, "Name"));
                    putIfPresent(value, "domain_name", extractLineValue(result, "Domain"));
                    putIfPresent(value, "version_id", extractTaggedValue(result, "internal_version_id"));
                    facts.add(structuredFact("entity", "pipeline.created", value, "session_only"));
                }
            }
            case "propose_add_instance" -> facts.add(structuredFact(
                    "composition",
                    "pipeline.instance_added",
                    Map.of(
                            "pipeline_id", args.get("pipeline_id"),
                            "blueprint_key", args.get("blueprint_key"),
                            "instance_name", args.get("instance_name")),
                    "session_only"));
            case "propose_wiring" -> facts.add(structuredFact(
                    "composition",
                    "pipeline.wiring_added",
                    Map.of(
                            "pipeline_id", args.get("pipeline_id"),
                            "source_instance_name", args.get("source_instance_name"),
                            "source_port", args.get("source_port"),
                            "target_instance_name", args.get("target_instance_name"),
                            "target_port", args.get("target_port")),
                    "session_only"));
            case "suggest_dq_expectations" -> facts.add(structuredFact(
                    "decision",
                    "dq.expectations.suggested",
                    Map.of(
                            "pipeline_id", args.get("pipeline_id"),
                            "version_id", args.get("version_id"),
                            "instance_id", args.get("instance_id"),
                            "summary", abbreviate(result, 240)),
                    "session_only"));
            default -> {
            }
        }
        return facts;
    }

    private Map<String, Object> structuredFact(String factType, String factKey, Map<String, Object> factValue, String persistenceStatus) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("fact_type", factType);
        fact.put("fact_key", factKey);
        fact.put("fact_value", factValue);
        fact.put("persistence_status", persistenceStatus);
        return fact;
    }

    private String linkSessionToPipelineIfPresent(String sessionId, String tenantId, String toolName, Map<String, Object> args, String result) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        String pipelineId = null;
        if ("propose_create_pipeline".equals(toolName)) {
            pipelineId = extractTaggedValue(result, "internal_id");
        }
        if ((pipelineId == null || pipelineId.isBlank()) && args.get("pipeline_id") instanceof String explicitPipelineId && !explicitPipelineId.isBlank()) {
            pipelineId = explicitPipelineId;
        }
        if (pipelineId == null || pipelineId.isBlank()) {
            return null;
        }

        var pipeline = pipelineRepo.findById(pipelineId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                .orElse(null);
        if (pipeline == null) {
            return null;
        }

        var session = sessionRepo.findById(sessionId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                .orElse(null);
        if (session == null) {
            return null;
        }

        if (!pipelineId.equals(session.getPipelineId())) {
            session.setPipelineId(pipelineId);
            sessionRepo.save(session);
        }
        return pipelineId;
    }

    private String extractTaggedValue(String result, String tag) {
        var matcher = java.util.regex.Pattern.compile("\\[" + java.util.regex.Pattern.quote(tag) + ": (\\S+)]").matcher(result);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> extractTaggedValues(String result, String tag) {
        if (result == null || result.isBlank()) {
            return List.of();
        }
        var matcher = java.util.regex.Pattern
                .compile("\\[" + java.util.regex.Pattern.quote(tag) + ": (\\S+)]")
                .matcher(result);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private List<Map<String, Object>> extractPreviewCommands(String result) {
        if (result == null || result.isBlank()) {
            return List.of();
        }
        var matcher = java.util.regex.Pattern
                .compile("\\[preview_command: index=(\\d+) type=(\\S+) aggregate=(\\S+)]")
                .matcher(result);
        List<Map<String, Object>> commands = new ArrayList<>();
        while (matcher.find()) {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("index", Integer.parseInt(matcher.group(1)));
            command.put("type", matcher.group(2));
            command.put("aggregateId", matcher.group(3));
            commands.add(command);
        }
        return commands;
    }

    private List<Map<String, Object>> extractCredentialAttachIntents(String result) {
        if (result == null || result.isBlank()) {
            return List.of();
        }
        var matcher = java.util.regex.Pattern
                .compile("\\[internal_attach_credential: sor_id=(\\S+) connector_instance_id=(\\S+) environment=(\\S+)]")
                .matcher(result);
        List<Map<String, Object>> intents = new ArrayList<>();
        while (matcher.find()) {
            Map<String, Object> intent = new LinkedHashMap<>();
            intent.put("kind", "credential_attach");
            intent.put("sorId", matcher.group(1));
            intent.put("connectorInstanceId", matcher.group(2));
            intent.put("environment", matcher.group(3));
            intents.add(intent);
        }
        return intents;
    }

    private String extractLineValue(String result, String label) {
        for (String line : result.split("\\R")) {
            if (line.startsWith(label + ": ")) {
                return line.substring((label + ": ").length()).trim();
            }
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String summarizeFactValue(Object value) {
        if (value == null) {
            return "n/a";
        }
        if (value instanceof Map<?, ?> map) {
            List<String> pairs = new ArrayList<>();
            for (var entry : map.entrySet()) {
                pairs.add(entry.getKey() + "=" + entry.getValue());
                if (pairs.size() >= 4) break;
            }
            return String.join(", ", pairs);
        }
        if (value instanceof Collection<?> collection) {
            List<String> items = new ArrayList<>();
            for (Object item : collection) {
                items.add(String.valueOf(item));
                if (items.size() >= 4) break;
            }
            return String.join(", ", items);
        }
        return String.valueOf(value);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private String generateLocalReply(String userContent, String tenantId) {
        String lower = userContent.toLowerCase();
        var blueprints = blueprintRepo.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active");
        var sors = sorRepo.findByTenantIdOrderByNameAsc(tenantId);
        var datasets = datasetRepo.findByTenantIdOrderByQualifiedNameAsc(tenantId);

        if (lower.contains("blueprint") || lower.contains("pattern") || lower.contains("step")) {
            StringBuilder sb = new StringBuilder("Available blueprint patterns:\n\n");
            String cat = "";
            for (var bp : blueprints) {
                if (!bp.getCategory().name().equals(cat)) {
                    cat = bp.getCategory().name();
                    sb.append("\n**").append(cat).append("**\n");
                }
                sb.append("- **").append(bp.getName()).append("**: ").append(bp.getDescription(), 0, Math.min(100, bp.getDescription().length()));
                if (bp.getDescription().length() > 100) sb.append("...");
                sb.append("\n");
            }
            return sb.toString();
        }
        if (lower.contains("source") || lower.contains("sor") || lower.contains("data source") || lower.contains("system")) {
            StringBuilder sb = new StringBuilder("Here are your registered Systems of Record:\n\n");
            for (var sor : sors) sb.append("- **").append(sor.getName()).append("** (").append(sor.getDomainName()).append(")\n");
            if (sors.isEmpty()) sb.append("No data sources registered yet. Go to the Data Sources page to register one.\n");
            else sb.append("\nTell me which source you want to use and what data you need.");
            return sb.toString();
        }
        if (lower.contains("dataset") || lower.contains("table") || lower.contains("schema")) {
            StringBuilder sb = new StringBuilder("Available datasets:\n\n");
            for (var ds : datasets) {
                sb.append("- **").append(ds.getName()).append("** (`").append(ds.getQualifiedName()).append("`)");
                if (ds.getClassification() != null) sb.append(" [").append(ds.getClassification()).append("]");
                sb.append("\n");
            }
            if (datasets.isEmpty()) sb.append("No datasets defined yet. Go to Data Sources to define datasets for your connectors.");
            return sb.toString();
        }
        if (lower.contains("pipeline") || lower.contains("build") || lower.contains("create") || lower.contains("need") || lower.contains("want")) {
            StringBuilder sb = new StringBuilder("I can help you build a medallion-aligned pipeline.\n\n");
            sb.append("Start by telling me which layer you are beginning in:\n");
            sb.append("- **Bronze** for raw ingestion only\n");
            sb.append("- **Silver** for cleanup, validation, conformance, and joins\n");
            sb.append("- **Gold** for business-ready marts, dimensions, and published outputs\n\n");
            sb.append("If the plan needs a dbt-backed transform or model, I should check whether a reusable domain dbt asset already exists before generating a new one.\n\n");
            sb.append("To get started, I need to know:\n\n");
            sb.append("1. **What data** do you need? (which SOR/dataset)\n");
            sb.append("2. **What layer/work** is this? (bronze ingestion, silver conformance, gold serving)\n");
            sb.append("3. **Where does the output go?** (Snowflake, BigQuery, Delta Lake)\n\n");
            if (!sors.isEmpty()) {
                sb.append("Your available sources: ");
                for (int i = 0; i < sors.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("**").append(sors.get(i).getName()).append("**");
                }
                sb.append("\n");
            }
            return sb.toString();
        }
        return "I am your PULSE pipeline assistant. I can help you:\n\n" +
                "- Explore your **data sources** and datasets\n" +
                "- Browse available **blueprint patterns**\n" +
                "- **Compose a pipeline** by selecting the right steps\n" +
                "- **Explain** what each part of your pipeline does\n\n" +
                "What would you like to do?";
    }

    private record ExamplePacket(
            String title,
            String exaQueryPackKey,
            List<String> preferredDomains,
            String languageHint,
            String qualityCriteria,
            String snippet) {
    }

}
