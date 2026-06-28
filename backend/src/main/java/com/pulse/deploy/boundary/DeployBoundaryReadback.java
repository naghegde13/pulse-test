package com.pulse.deploy.boundary;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PKT-0004 — immutable deploy boundary readback for a tenant + deployment
 * target combination. Enumerates all GCP resource targets (Composer,
 * Dataproc, BigQuery, Secret Manager, GCS/evidence/log), their
 * create-vs-validate responsibility, blocker status, and topology
 * expectations.
 *
 * <p>This readback is <b>boundary evidence only</b> — it describes what
 * resources would be needed and what blockers exist. It cannot satisfy
 * static package proof, preflight-only proof, local synthetic proof,
 * or runtime output proof gates.
 *
 * <p>Schema version: {@code deploy-boundary-readback.v1}.
 */
public record DeployBoundaryReadback(
        String schemaVersion,
        String tenantId,
        String targetId,
        String targetType,
        String environment,
        BoundaryStatus boundaryStatus,
        Instant producedAt,
        String producedBy,
        ComposerBoundary composer,
        DataprocBoundary dataproc,
        BigQueryBoundary bigquery,
        SecretManagerBoundary secretManager,
        EvidenceLogBoundary evidenceLog,
        GeneratedArtifactReadiness artifactReadiness,
        List<String> blockers,
        List<String> operatorActions,
        String boundaryEvidenceDisclaimer
) {
    public static final String SCHEMA_VERSION = "deploy-boundary-readback.v1";

    /**
     * Fixed disclaimer that deploy boundary evidence cannot be used as
     * package proof, preflight proof, local synthetic proof, or runtime
     * output proof.
     */
    public static final String EVIDENCE_DISCLAIMER =
            "Deploy boundary readback is topology/IAM/credential readiness evidence only. "
            + "It cannot be rendered as static package proof, preflight-only proof, "
            + "local synthetic proof, or runtime output proof.";

    public DeployBoundaryReadback {
        blockers = blockers == null ? List.of() : Collections.unmodifiableList(blockers);
        operatorActions = operatorActions == null ? List.of() : Collections.unmodifiableList(operatorActions);
        if (boundaryEvidenceDisclaimer == null) {
            boundaryEvidenceDisclaimer = EVIDENCE_DISCLAIMER;
        }
    }

    public boolean isBlocked() {
        return boundaryStatus == BoundaryStatus.OPERATOR_BLOCKED
                || boundaryStatus == BoundaryStatus.BLOCKED;
    }

    // ── Boundary status ──────────────────────────────────────

    public enum BoundaryStatus {
        /** All topology/IAM/credential gates satisfied; deploy path is live. */
        LIVE,
        /** Operator must grant authority before deploy can proceed. */
        OPERATOR_BLOCKED,
        /** Hard blockers exist that prevent deploy regardless of operator action. */
        BLOCKED
    }

    // ── Composer boundary ────────────────────────────────────

    public record ComposerBoundary(
            String gcpProject,
            String region,
            String composerEnvironment,
            List<String> dagTargetPaths,
            String packageDeliveryBucket,
            String deployIdentityExpectation,
            Responsibility responsibility,
            List<String> blockers,
            boolean ready
    ) {
        public ComposerBoundary {
            dagTargetPaths = dagTargetPaths == null ? List.of() : Collections.unmodifiableList(dagTargetPaths);
            blockers = blockers == null ? List.of() : Collections.unmodifiableList(blockers);
        }
    }

    // ── Dataproc boundary ────────────────────────────────────

    public record DataprocBoundary(
            String region,
            String runtimeServiceAccount,
            String batchSubmissionStrategy,
            String stagingBucket,
            String packageTarget,
            Responsibility responsibility,
            List<String> blockers,
            boolean ready
    ) {
        public DataprocBoundary {
            blockers = blockers == null ? List.of() : Collections.unmodifiableList(blockers);
        }
    }

    // ── BigQuery boundary ────────────────────────────────────

    public record BigQueryBoundary(
            List<String> targetDatasets,
            List<String> targetTables,
            List<String> ddlTargets,
            List<String> jobTargets,
            String managedIcebergConnection,
            String managedIcebergResourceRef,
            Responsibility responsibility,
            List<String> blockers,
            boolean ready
    ) {
        public BigQueryBoundary {
            targetDatasets = targetDatasets == null ? List.of() : Collections.unmodifiableList(targetDatasets);
            targetTables = targetTables == null ? List.of() : Collections.unmodifiableList(targetTables);
            ddlTargets = ddlTargets == null ? List.of() : Collections.unmodifiableList(ddlTargets);
            jobTargets = jobTargets == null ? List.of() : Collections.unmodifiableList(jobTargets);
            blockers = blockers == null ? List.of() : Collections.unmodifiableList(blockers);
        }
    }

    // ── Secret Manager boundary ──────────────────────────────

    public record SecretManagerBoundary(
            String runtimeAccessExpectation,
            List<SecretRef> requiredSecretRefs,
            Responsibility responsibility,
            List<String> blockers,
            boolean ready
    ) {
        public SecretManagerBoundary {
            requiredSecretRefs = requiredSecretRefs == null ? List.of() : Collections.unmodifiableList(requiredSecretRefs);
            blockers = blockers == null ? List.of() : Collections.unmodifiableList(blockers);
        }
    }

    /**
     * Secret reference with name and access URI — never includes secret values.
     */
    public record SecretRef(
            String name,
            String secretUri,
            String purpose
    ) {}

    // ── Evidence/log destination boundary ─────────────────────

    public record EvidenceLogBoundary(
            String evidenceBucket,
            String evidencePrefix,
            String logDataset,
            String logPrefix,
            Responsibility responsibility,
            List<String> blockers,
            boolean ready
    ) {
        public EvidenceLogBoundary {
            blockers = blockers == null ? List.of() : Collections.unmodifiableList(blockers);
        }
    }

    // ── Generated artifact readiness ─────────────────────────

    public record GeneratedArtifactReadiness(
            boolean hasGeneratedArtifacts,
            boolean hasPackage,
            String packageStatus,
            boolean artifactsReady,
            List<String> blockers
    ) {
        public GeneratedArtifactReadiness {
            blockers = blockers == null ? List.of() : Collections.unmodifiableList(blockers);
        }
    }

    // ── Create-vs-validate responsibility ────────────────────

    public enum Responsibility {
        /** PULSE creates/manages this resource. */
        PULSE_CREATES,
        /** PULSE validates but operator must create/provision. */
        OPERATOR_PROVISIONS_PULSE_VALIDATES,
        /** External system owns; PULSE only reads status. */
        EXTERNAL_READONLY
    }

    // ── Canonical JSON ───────────────────────────────────────

    public Map<String, Object> toCanonicalJson() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", schemaVersion);
        doc.put("tenantId", tenantId);
        doc.put("targetId", targetId);
        doc.put("targetType", targetType);
        doc.put("environment", environment);
        doc.put("boundaryStatus", boundaryStatus == null ? null : boundaryStatus.name());
        doc.put("producedAt", producedAt == null ? null : producedAt.toString());
        doc.put("producedBy", producedBy);
        doc.put("blockers", blockers);
        doc.put("operatorActions", operatorActions);
        doc.put("boundaryEvidenceDisclaimer", boundaryEvidenceDisclaimer);
        if (composer != null) doc.put("composer", composerToJson(composer));
        if (dataproc != null) doc.put("dataproc", dataprocToJson(dataproc));
        if (bigquery != null) doc.put("bigquery", bigqueryToJson(bigquery));
        if (secretManager != null) doc.put("secretManager", secretManagerToJson(secretManager));
        if (evidenceLog != null) doc.put("evidenceLog", evidenceLogToJson(evidenceLog));
        if (artifactReadiness != null) doc.put("artifactReadiness", artifactReadinessToJson(artifactReadiness));
        return doc;
    }

    private static Map<String, Object> composerToJson(ComposerBoundary c) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("gcpProject", c.gcpProject());
        doc.put("region", c.region());
        doc.put("composerEnvironment", c.composerEnvironment());
        doc.put("dagTargetPaths", c.dagTargetPaths());
        doc.put("packageDeliveryBucket", c.packageDeliveryBucket());
        doc.put("deployIdentityExpectation", c.deployIdentityExpectation());
        doc.put("responsibility", c.responsibility() == null ? null : c.responsibility().name());
        doc.put("blockers", c.blockers());
        doc.put("ready", c.ready());
        return doc;
    }

    private static Map<String, Object> dataprocToJson(DataprocBoundary d) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("region", d.region());
        doc.put("runtimeServiceAccount", d.runtimeServiceAccount());
        doc.put("batchSubmissionStrategy", d.batchSubmissionStrategy());
        doc.put("stagingBucket", d.stagingBucket());
        doc.put("packageTarget", d.packageTarget());
        doc.put("responsibility", d.responsibility() == null ? null : d.responsibility().name());
        doc.put("blockers", d.blockers());
        doc.put("ready", d.ready());
        return doc;
    }

    private static Map<String, Object> bigqueryToJson(BigQueryBoundary b) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("targetDatasets", b.targetDatasets());
        doc.put("targetTables", b.targetTables());
        doc.put("ddlTargets", b.ddlTargets());
        doc.put("jobTargets", b.jobTargets());
        doc.put("managedIcebergConnection", b.managedIcebergConnection());
        doc.put("managedIcebergResourceRef", b.managedIcebergResourceRef());
        doc.put("responsibility", b.responsibility() == null ? null : b.responsibility().name());
        doc.put("blockers", b.blockers());
        doc.put("ready", b.ready());
        return doc;
    }

    private static Map<String, Object> secretManagerToJson(SecretManagerBoundary s) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("runtimeAccessExpectation", s.runtimeAccessExpectation());
        doc.put("requiredSecretRefs", s.requiredSecretRefs().stream().map(r -> {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("name", r.name());
            ref.put("secretUri", r.secretUri());
            ref.put("purpose", r.purpose());
            return ref;
        }).toList());
        doc.put("responsibility", s.responsibility() == null ? null : s.responsibility().name());
        doc.put("blockers", s.blockers());
        doc.put("ready", s.ready());
        return doc;
    }

    private static Map<String, Object> evidenceLogToJson(EvidenceLogBoundary e) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("evidenceBucket", e.evidenceBucket());
        doc.put("evidencePrefix", e.evidencePrefix());
        doc.put("logDataset", e.logDataset());
        doc.put("logPrefix", e.logPrefix());
        doc.put("responsibility", e.responsibility() == null ? null : e.responsibility().name());
        doc.put("blockers", e.blockers());
        doc.put("ready", e.ready());
        return doc;
    }

    private static Map<String, Object> artifactReadinessToJson(GeneratedArtifactReadiness a) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("hasGeneratedArtifacts", a.hasGeneratedArtifacts());
        doc.put("hasPackage", a.hasPackage());
        doc.put("packageStatus", a.packageStatus());
        doc.put("artifactsReady", a.artifactsReady());
        doc.put("blockers", a.blockers());
        return doc;
    }
}
