package com.pulse.deploy.evidence;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PKT-0005 — immutable runtime evidence envelope that explicitly
 * separates static package proof, preflight/deploy request, local
 * synthetic lifecycle evidence, real Composer/Dataproc/BigQuery
 * runtime proof, output URI/table probes, oracle verdict, cleanup
 * policy, and promotion readiness.
 *
 * <p>The envelope carries an explicit {@link #proofLevel} and
 * {@link #evidenceType} so consumers can never accidentally treat
 * local/static/preflight evidence as runtime output proof.
 *
 * <p>Schema version: {@code runtime-evidence-envelope.v1}.
 *
 * @param schemaVersion   stable envelope version
 * @param proofLevel      the proof ladder tier this evidence occupies
 * @param evidenceType    specific evidence kind within the proof level
 * @param deploymentRunId the run this evidence belongs to
 * @param packageId       the package under evaluation
 * @param tenantId        tenant scope
 * @param environment     canonical deployment environment
 * @param adapter         canonical target type key
 * @param producedAt      when this evidence was produced
 * @param producedBy      service/adapter that produced the evidence
 * @param correlationId   traced across all related evidence rows
 * @param runtimeProof    true only for LIVE_RUNTIME+ proof levels
 * @param promotionReady  true only for PROMOTION_READINESS
 * @param composer        Composer evidence (null unless LIVE_RUNTIME+)
 * @param dataproc        Dataproc evidence (null unless LIVE_RUNTIME+)
 * @param bigquery        BigQuery evidence (null unless LIVE_RUNTIME+)
 * @param outputProof     output URI/table readback (null unless LIVE_RUNTIME+)
 * @param oracleVerdict   oracle comparison result (null unless ORACLE_VERDICT+)
 * @param cleanupPolicy   cleanup policy decision (null unless PROMOTION_READINESS)
 * @param promotionDecision promotion readiness decision (null unless PROMOTION_READINESS)
 * @param evidenceIndex   links to all evidence artifacts for this run
 */
public record RuntimeEvidenceEnvelope(
        String schemaVersion,
        EvidenceProofLevel proofLevel,
        String evidenceType,
        String deploymentRunId,
        String packageId,
        String tenantId,
        String environment,
        String adapter,
        Instant producedAt,
        String producedBy,
        String correlationId,
        boolean runtimeProof,
        boolean promotionReady,
        ComposerEvidence composer,
        DataprocEvidence dataproc,
        BigQueryEvidence bigquery,
        OutputProofEvidence outputProof,
        OracleVerdictEvidence oracleVerdict,
        CleanupPolicy cleanupPolicy,
        PromotionDecision promotionDecision,
        List<EvidenceIndexEntry> evidenceIndex
) {
    public static final String SCHEMA_VERSION = "runtime-evidence-envelope.v1";

    // Evidence type constants
    public static final String TYPE_STATIC_PACKAGE = "STATIC_PACKAGE_ASSESSMENT";
    public static final String TYPE_PREFLIGHT = "PREFLIGHT_CHECK";
    public static final String TYPE_LOCAL_SYNTHETIC = "LOCAL_SYNTHETIC_RUN";
    public static final String TYPE_COMPOSER_RUN = "COMPOSER_DAG_RUN";
    public static final String TYPE_DATAPROC_BATCH = "DATAPROC_BATCH_RUN";
    public static final String TYPE_BIGQUERY_JOB = "BIGQUERY_JOB_RUN";
    public static final String TYPE_OUTPUT_PROBE = "OUTPUT_PROBE";
    public static final String TYPE_ORACLE_VERDICT = "ORACLE_VERDICT";
    public static final String TYPE_PROMOTION_READINESS = "PROMOTION_READINESS";

    public RuntimeEvidenceEnvelope {
        evidenceIndex = evidenceIndex == null
                ? List.of()
                : Collections.unmodifiableList(evidenceIndex);
        // Enforce proof level invariants at construction time
        if (proofLevel != null) {
            if (runtimeProof && !proofLevel.isRuntimeProof()) {
                throw new IllegalArgumentException(
                        "runtimeProof=true requires proof level LIVE_RUNTIME or higher, got " + proofLevel);
            }
            if (promotionReady && !proofLevel.isPromotionReady()) {
                throw new IllegalArgumentException(
                        "promotionReady=true requires proof level PROMOTION_READINESS, got " + proofLevel);
            }
        }
    }

    public Map<String, Object> toCanonicalJson() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", schemaVersion);
        doc.put("proofLevel", proofLevel == null ? null : proofLevel.name());
        doc.put("evidenceType", evidenceType);
        doc.put("deploymentRunId", deploymentRunId);
        doc.put("packageId", packageId);
        doc.put("tenantId", tenantId);
        doc.put("environment", environment);
        doc.put("adapter", adapter);
        doc.put("producedAt", producedAt == null ? null : producedAt.toString());
        doc.put("producedBy", producedBy);
        doc.put("correlationId", correlationId);
        doc.put("runtimeProof", runtimeProof);
        doc.put("promotionReady", promotionReady);
        if (composer != null) doc.put("composer", composer.toCanonicalJson());
        if (dataproc != null) doc.put("dataproc", dataproc.toCanonicalJson());
        if (bigquery != null) doc.put("bigquery", bigquery.toCanonicalJson());
        if (outputProof != null) doc.put("outputProof", outputProof.toCanonicalJson());
        if (oracleVerdict != null) doc.put("oracleVerdict", oracleVerdict.toCanonicalJson());
        if (cleanupPolicy != null) doc.put("cleanupPolicy", cleanupPolicy.toCanonicalJson());
        if (promotionDecision != null) doc.put("promotionDecision", promotionDecision.toCanonicalJson());
        if (!evidenceIndex.isEmpty()) {
            doc.put("evidenceIndex", evidenceIndex.stream()
                    .map(EvidenceIndexEntry::toCanonicalJson)
                    .toList());
        }
        return doc;
    }

    // ── Composer evidence ─────────────────────────────────────

    public record ComposerEvidence(
            String environment,
            String dagId,
            String dagRunId,
            Map<String, String> taskStates,
            List<String> logLinks,
            Instant observedAt
    ) {
        public ComposerEvidence {
            taskStates = taskStates == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(taskStates));
            logLinks = logLinks == null ? List.of() : Collections.unmodifiableList(logLinks);
        }

        public Map<String, Object> toCanonicalJson() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("environment", environment);
            doc.put("dagId", dagId);
            doc.put("dagRunId", dagRunId);
            doc.put("taskStates", taskStates);
            doc.put("logLinks", logLinks);
            doc.put("observedAt", observedAt == null ? null : observedAt.toString());
            return doc;
        }
    }

    // ── Dataproc evidence ─────────────────────────────────────

    public record DataprocEvidence(
            String batchId,
            String batchStatus,
            String driverLogLocation,
            String applicationLogLocation,
            Map<String, Object> sparkJobEvidence,
            Instant observedAt
    ) {
        public DataprocEvidence {
            sparkJobEvidence = sparkJobEvidence == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(sparkJobEvidence));
        }

        public Map<String, Object> toCanonicalJson() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("batchId", batchId);
            doc.put("batchStatus", batchStatus);
            doc.put("driverLogLocation", driverLogLocation);
            doc.put("applicationLogLocation", applicationLogLocation);
            doc.put("sparkJobEvidence", sparkJobEvidence);
            doc.put("observedAt", observedAt == null ? null : observedAt.toString());
            return doc;
        }
    }

    // ── BigQuery evidence ─────────────────────────────────────

    public record BigQueryEvidence(
            List<String> jobIds,
            List<String> targetDatasets,
            List<String> targetTables,
            Map<String, String> ddlJobStatus,
            Map<String, String> queryJobStatus,
            List<TableProbe> tableProbes,
            Instant observedAt
    ) {
        public BigQueryEvidence {
            jobIds = jobIds == null ? List.of() : Collections.unmodifiableList(jobIds);
            targetDatasets = targetDatasets == null ? List.of() : Collections.unmodifiableList(targetDatasets);
            targetTables = targetTables == null ? List.of() : Collections.unmodifiableList(targetTables);
            ddlJobStatus = ddlJobStatus == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ddlJobStatus));
            queryJobStatus = queryJobStatus == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(queryJobStatus));
            tableProbes = tableProbes == null ? List.of() : Collections.unmodifiableList(tableProbes);
        }

        public Map<String, Object> toCanonicalJson() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("jobIds", jobIds);
            doc.put("targetDatasets", targetDatasets);
            doc.put("targetTables", targetTables);
            doc.put("ddlJobStatus", ddlJobStatus);
            doc.put("queryJobStatus", queryJobStatus);
            doc.put("tableProbes", tableProbes.stream().map(TableProbe::toCanonicalJson).toList());
            doc.put("observedAt", observedAt == null ? null : observedAt.toString());
            return doc;
        }
    }

    public record TableProbe(
            String tableReference,
            String uri,
            long rowCount,
            String checksum,
            String probeStatus,
            Instant probedAt
    ) {
        public Map<String, Object> toCanonicalJson() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("tableReference", tableReference);
            doc.put("uri", uri);
            doc.put("rowCount", rowCount);
            doc.put("checksum", checksum);
            doc.put("probeStatus", probeStatus);
            doc.put("probedAt", probedAt == null ? null : probedAt.toString());
            return doc;
        }
    }

    // ── Output proof evidence ─────────────────────────────────

    public record OutputProofEvidence(
            List<OutputEntry> outputs,
            Instant observedAt
    ) {
        public OutputProofEvidence {
            outputs = outputs == null ? List.of() : Collections.unmodifiableList(outputs);
        }

        public Map<String, Object> toCanonicalJson() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("outputs", outputs.stream().map(OutputEntry::toCanonicalJson).toList());
            doc.put("observedAt", observedAt == null ? null : observedAt.toString());
            return doc;
        }
    }

    public record OutputEntry(
            String outputType,
            String uri,
            String tableReference,
            long rowCount,
            String checksum,
            String semanticProbeResult,
            String probeStatus
    ) {
        public Map<String, Object> toCanonicalJson() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("outputType", outputType);
            doc.put("uri", uri);
            doc.put("tableReference", tableReference);
            doc.put("rowCount", rowCount);
            doc.put("checksum", checksum);
            doc.put("semanticProbeResult", semanticProbeResult);
            doc.put("probeStatus", probeStatus);
            return doc;
        }
    }

    // ── Oracle verdict evidence ───────────────────────────────

    public record OracleVerdictEvidence(
            String semanticComparison,
            String expectedSummary,
            String actualSummary,
            String verdict,
            List<String> evidenceIndexLinks,
            Map<String, Object> comparisonDetails,
            Instant evaluatedAt
    ) {
        public OracleVerdictEvidence {
            evidenceIndexLinks = evidenceIndexLinks == null ? List.of() : Collections.unmodifiableList(evidenceIndexLinks);
            comparisonDetails = comparisonDetails == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(comparisonDetails));
        }

        public Map<String, Object> toCanonicalJson() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("semanticComparison", semanticComparison);
            doc.put("expectedSummary", expectedSummary);
            doc.put("actualSummary", actualSummary);
            doc.put("verdict", verdict);
            doc.put("evidenceIndexLinks", evidenceIndexLinks);
            doc.put("comparisonDetails", comparisonDetails);
            doc.put("evaluatedAt", evaluatedAt == null ? null : evaluatedAt.toString());
            return doc;
        }
    }

    // ── Cleanup policy ────────────────────────────────────────

    public record CleanupPolicy(
            String policy,
            boolean retainArtifacts,
            boolean retainRuntimeLogs,
            int retentionDays,
            String cleanupStatus,
            Instant scheduledAt
    ) {
        public Map<String, Object> toCanonicalJson() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("policy", policy);
            doc.put("retainArtifacts", retainArtifacts);
            doc.put("retainRuntimeLogs", retainRuntimeLogs);
            doc.put("retentionDays", retentionDays);
            doc.put("cleanupStatus", cleanupStatus);
            doc.put("scheduledAt", scheduledAt == null ? null : scheduledAt.toString());
            return doc;
        }
    }

    // ── Promotion decision ────────────────────────────────────

    public record PromotionDecision(
            boolean ready,
            String reason,
            List<String> satisfiedGates,
            List<String> unsatisfiedGates,
            String targetEnvironment,
            Instant decidedAt
    ) {
        public PromotionDecision {
            satisfiedGates = satisfiedGates == null ? List.of() : Collections.unmodifiableList(satisfiedGates);
            unsatisfiedGates = unsatisfiedGates == null ? List.of() : Collections.unmodifiableList(unsatisfiedGates);
        }

        public Map<String, Object> toCanonicalJson() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("ready", ready);
            doc.put("reason", reason);
            doc.put("satisfiedGates", satisfiedGates);
            doc.put("unsatisfiedGates", unsatisfiedGates);
            doc.put("targetEnvironment", targetEnvironment);
            doc.put("decidedAt", decidedAt == null ? null : decidedAt.toString());
            return doc;
        }
    }

    // ── Evidence index entry ──────────────────────────────────

    public record EvidenceIndexEntry(
            String artifactId,
            String evidenceType,
            EvidenceProofLevel proofLevel,
            String path,
            String sha256,
            Instant producedAt
    ) {
        public Map<String, Object> toCanonicalJson() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("artifactId", artifactId);
            doc.put("evidenceType", evidenceType);
            doc.put("proofLevel", proofLevel == null ? null : proofLevel.name());
            doc.put("path", path);
            doc.put("sha256", sha256);
            doc.put("producedAt", producedAt == null ? null : producedAt.toString());
            return doc;
        }
    }
}
