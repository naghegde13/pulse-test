package com.pulse.deploy.evidence;

import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.CleanupPolicy;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.ComposerEvidence;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.DataprocEvidence;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.BigQueryEvidence;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.EvidenceIndexEntry;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.OracleVerdictEvidence;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.OutputProofEvidence;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.PromotionDecision;
import com.pulse.deploy.model.DeploymentEvidence;
import com.pulse.deploy.repository.DeploymentEvidenceRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PKT-0005 — assembles {@link RuntimeEvidenceEnvelope} instances from
 * persisted {@link DeploymentEvidence} rows. The service enforces the
 * proof level contract: local/static/preflight evidence cannot satisfy
 * runtime output proof gates.
 *
 * <p>This service is read-only — it queries existing evidence rows
 * and assembles them into the envelope. Evidence rows are written by
 * {@link DeploymentEvidenceService} and the adapter/orchestrator layer.
 */
@Service
public class RuntimeEvidenceService {

    private final DeploymentEvidenceRepository evidenceRepo;

    public RuntimeEvidenceService(DeploymentEvidenceRepository evidenceRepo) {
        this.evidenceRepo = evidenceRepo;
    }

    /**
     * Assemble the runtime evidence envelope for a deployment run.
     * Computes the highest proof level from persisted evidence rows
     * and populates the envelope sections accordingly.
     */
    public RuntimeEvidenceEnvelope assembleForRun(String deploymentRunId,
                                                   String deploymentId,
                                                   String packageId,
                                                   String tenantId,
                                                   String environment,
                                                   String adapter) {
        List<DeploymentEvidence> rows = evidenceRepo
                .findByDeploymentRunIdOrderByCreatedAtAsc(deploymentRunId);

        EvidenceProofLevel proofLevel = computeProofLevel(rows, adapter);
        String evidenceType = computeEvidenceType(proofLevel, adapter);
        List<EvidenceIndexEntry> index = buildEvidenceIndex(rows);

        return new RuntimeEvidenceEnvelope(
                RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                proofLevel,
                evidenceType,
                deploymentRunId,
                packageId,
                tenantId,
                environment,
                adapter,
                Instant.now(),
                "RuntimeEvidenceService",
                rows.isEmpty() ? null : rows.get(0).getCorrelationId(),
                proofLevel.isRuntimeProof(),
                proofLevel.isPromotionReady(),
                null, // composer — populated by live runtime adapter
                null, // dataproc — populated by live runtime adapter
                null, // bigquery — populated by live runtime adapter
                null, // outputProof — populated by live runtime adapter
                null, // oracleVerdict — populated by oracle service
                null, // cleanupPolicy — populated by promotion service
                null, // promotionDecision — populated by promotion service
                index
        );
    }

    /**
     * Build a LOCAL_SYNTHETIC evidence envelope for a local deployment
     * run. The envelope explicitly marks that no real data-plane
     * execution occurred.
     */
    public RuntimeEvidenceEnvelope buildLocalSyntheticEnvelope(
            String deploymentRunId,
            String packageId,
            String tenantId,
            String environment,
            String correlationId,
            List<EvidenceIndexEntry> evidenceIndex) {
        return new RuntimeEvidenceEnvelope(
                RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                EvidenceProofLevel.LOCAL_SYNTHETIC,
                RuntimeEvidenceEnvelope.TYPE_LOCAL_SYNTHETIC,
                deploymentRunId,
                packageId,
                tenantId,
                environment,
                "LOCAL_MATERIALIZATION",
                Instant.now(),
                "LocalDeploymentTargetAdapter",
                correlationId,
                false,  // runtimeProof — local synthetic never satisfies this
                false,  // promotionReady — local synthetic never satisfies this
                null, null, null, null, null, null, null,
                evidenceIndex
        );
    }

    /**
     * Build a STATIC_PACKAGE evidence envelope for package assessment.
     */
    public RuntimeEvidenceEnvelope buildStaticPackageEnvelope(
            String packageId,
            String tenantId,
            String correlationId,
            List<EvidenceIndexEntry> evidenceIndex) {
        return new RuntimeEvidenceEnvelope(
                RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                EvidenceProofLevel.STATIC_PACKAGE,
                RuntimeEvidenceEnvelope.TYPE_STATIC_PACKAGE,
                null, // no deployment run for static package
                packageId,
                tenantId,
                null,
                null,
                Instant.now(),
                "PackageService",
                correlationId,
                false,  // runtimeProof — static package never satisfies this
                false,  // promotionReady — static package never satisfies this
                null, null, null, null, null, null, null,
                evidenceIndex
        );
    }

    /**
     * Build a PREFLIGHT evidence envelope.
     */
    public RuntimeEvidenceEnvelope buildPreflightEnvelope(
            String deploymentRunId,
            String packageId,
            String tenantId,
            String environment,
            String adapter,
            String correlationId,
            List<EvidenceIndexEntry> evidenceIndex) {
        return new RuntimeEvidenceEnvelope(
                RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                EvidenceProofLevel.PREFLIGHT,
                RuntimeEvidenceEnvelope.TYPE_PREFLIGHT,
                deploymentRunId,
                packageId,
                tenantId,
                environment,
                adapter,
                Instant.now(),
                "DeploymentPreflightService",
                correlationId,
                false,  // runtimeProof — preflight never satisfies this
                false,  // promotionReady — preflight never satisfies this
                null, null, null, null, null, null, null,
                evidenceIndex
        );
    }

    /**
     * Build a LIVE_RUNTIME evidence envelope with Composer/Dataproc/BigQuery
     * evidence and output probes.
     */
    public RuntimeEvidenceEnvelope buildLiveRuntimeEnvelope(
            String deploymentRunId,
            String packageId,
            String tenantId,
            String environment,
            String adapter,
            String correlationId,
            ComposerEvidence composer,
            DataprocEvidence dataproc,
            BigQueryEvidence bigquery,
            OutputProofEvidence outputProof,
            List<EvidenceIndexEntry> evidenceIndex) {
        return new RuntimeEvidenceEnvelope(
                RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                EvidenceProofLevel.LIVE_RUNTIME,
                RuntimeEvidenceEnvelope.TYPE_COMPOSER_RUN,
                deploymentRunId,
                packageId,
                tenantId,
                environment,
                adapter,
                Instant.now(),
                "GcpComposerDataprocAdapter",
                correlationId,
                true,   // runtimeProof — live runtime satisfies this
                false,  // promotionReady — requires oracle + promotion gates
                composer,
                dataproc,
                bigquery,
                outputProof,
                null, null, null,
                evidenceIndex
        );
    }

    /**
     * Build an ORACLE_VERDICT evidence envelope.
     */
    public RuntimeEvidenceEnvelope buildOracleVerdictEnvelope(
            String deploymentRunId,
            String packageId,
            String tenantId,
            String environment,
            String adapter,
            String correlationId,
            OracleVerdictEvidence oracleVerdict,
            List<EvidenceIndexEntry> evidenceIndex) {
        return new RuntimeEvidenceEnvelope(
                RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                EvidenceProofLevel.ORACLE_VERDICT,
                RuntimeEvidenceEnvelope.TYPE_ORACLE_VERDICT,
                deploymentRunId,
                packageId,
                tenantId,
                environment,
                adapter,
                Instant.now(),
                "OracleComparisonService",
                correlationId,
                true,   // runtimeProof
                false,  // promotionReady — requires promotion gates
                null, null, null, null,
                oracleVerdict,
                null, null,
                evidenceIndex
        );
    }

    /**
     * Build a PROMOTION_READINESS evidence envelope.
     */
    public RuntimeEvidenceEnvelope buildPromotionReadinessEnvelope(
            String deploymentRunId,
            String packageId,
            String tenantId,
            String environment,
            String adapter,
            String correlationId,
            CleanupPolicy cleanupPolicy,
            PromotionDecision promotionDecision,
            List<EvidenceIndexEntry> evidenceIndex) {
        return new RuntimeEvidenceEnvelope(
                RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                EvidenceProofLevel.PROMOTION_READINESS,
                RuntimeEvidenceEnvelope.TYPE_PROMOTION_READINESS,
                deploymentRunId,
                packageId,
                tenantId,
                environment,
                adapter,
                Instant.now(),
                "PromotionReadinessService",
                correlationId,
                true,   // runtimeProof
                true,   // promotionReady
                null, null, null, null, null,
                cleanupPolicy,
                promotionDecision,
                evidenceIndex
        );
    }

    // ── Internal helpers ──────────────────────────────────────

    private EvidenceProofLevel computeProofLevel(List<DeploymentEvidence> rows, String adapter) {
        boolean hasPreflight = false;
        boolean hasMaterialization = false;
        boolean hasRuntimeStatus = false;

        for (DeploymentEvidence row : rows) {
            switch (row.getType()) {
                case DeploymentEvidenceService.TYPE_PREFLIGHT_RESULT -> hasPreflight = true;
                case DeploymentEvidenceService.TYPE_MATERIALIZATION_MANIFEST -> hasMaterialization = true;
                case DeploymentEvidenceService.TYPE_RUNTIME_STATUS -> hasRuntimeStatus = true;
                default -> { /* other evidence types don't affect proof level */ }
            }
        }

        if (!hasPreflight) {
            return EvidenceProofLevel.STATIC_PACKAGE;
        }
        if (!hasMaterialization && !hasRuntimeStatus) {
            return EvidenceProofLevel.PREFLIGHT;
        }
        // Local adapter produces synthetic runs, not real runtime
        if ("LOCAL_MATERIALIZATION".equals(adapter)) {
            return EvidenceProofLevel.LOCAL_SYNTHETIC;
        }
        if (hasRuntimeStatus) {
            return EvidenceProofLevel.LIVE_RUNTIME;
        }
        return EvidenceProofLevel.PREFLIGHT;
    }

    private String computeEvidenceType(EvidenceProofLevel level, String adapter) {
        return switch (level) {
            case STATIC_PACKAGE -> RuntimeEvidenceEnvelope.TYPE_STATIC_PACKAGE;
            case PREFLIGHT -> RuntimeEvidenceEnvelope.TYPE_PREFLIGHT;
            case LOCAL_SYNTHETIC -> RuntimeEvidenceEnvelope.TYPE_LOCAL_SYNTHETIC;
            case LIVE_RUNTIME -> {
                if ("GCP_COMPOSER_DATAPROC".equals(adapter)) {
                    yield RuntimeEvidenceEnvelope.TYPE_COMPOSER_RUN;
                }
                yield RuntimeEvidenceEnvelope.TYPE_DATAPROC_BATCH;
            }
            case ORACLE_VERDICT -> RuntimeEvidenceEnvelope.TYPE_ORACLE_VERDICT;
            case PROMOTION_READINESS -> RuntimeEvidenceEnvelope.TYPE_PROMOTION_READINESS;
        };
    }

    private List<EvidenceIndexEntry> buildEvidenceIndex(List<DeploymentEvidence> rows) {
        List<EvidenceIndexEntry> index = new ArrayList<>();
        for (DeploymentEvidence row : rows) {
            EvidenceProofLevel entryLevel = mapTypeToProofLevel(row.getType());
            index.add(new EvidenceIndexEntry(
                    row.getArtifactId(),
                    row.getType(),
                    entryLevel,
                    row.getPath(),
                    row.getSha256(),
                    row.getCreatedAt()
            ));
        }
        return index;
    }

    private EvidenceProofLevel mapTypeToProofLevel(String type) {
        if (type == null) return EvidenceProofLevel.STATIC_PACKAGE;
        return switch (type) {
            case DeploymentEvidenceService.TYPE_PREFLIGHT_RESULT -> EvidenceProofLevel.PREFLIGHT;
            case DeploymentEvidenceService.TYPE_MATERIALIZATION_MANIFEST -> EvidenceProofLevel.LOCAL_SYNTHETIC;
            case DeploymentEvidenceService.TYPE_ADAPTER_PLAN, DeploymentEvidenceService.TYPE_ADAPTER_EXECUTION ->
                    EvidenceProofLevel.LOCAL_SYNTHETIC;
            case DeploymentEvidenceService.TYPE_RUNTIME_STATUS -> EvidenceProofLevel.LIVE_RUNTIME;
            default -> EvidenceProofLevel.STATIC_PACKAGE;
        };
    }
}
