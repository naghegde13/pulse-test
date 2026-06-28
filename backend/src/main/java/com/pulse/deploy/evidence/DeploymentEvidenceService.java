package com.pulse.deploy.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.auth.policy.CallerContext;
import com.pulse.deploy.model.DeploymentEvent;
import com.pulse.deploy.model.DeploymentEvidence;
import com.pulse.deploy.preflight.PreflightCheckResult;
import com.pulse.deploy.repository.DeploymentEventRepository;
import com.pulse.deploy.repository.DeploymentEvidenceRepository;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 4 — writes envelope-conformant evidence and event rows.
 *
 * <p>Both record types use a stable {@code v1} envelope so downstream
 * UI / audit consumers can decode every kind with one parser. Phase
 * 5+ will add more evidence types (materialization manifest, output
 * probe) but the envelope shape stays the same.
 */
@Service
public class DeploymentEvidenceService {

    public static final String EVIDENCE_SCHEMA = "deployment-evidence.v1";
    public static final String EVENT_SCHEMA = "deployment-event.v1";
    public static final String TYPE_PREFLIGHT_RESULT = "PREFLIGHT_RESULT";
    public static final String TYPE_RUN_STATUS = "RUN_STATUS";
    public static final String TYPE_MATERIALIZATION_MANIFEST = "MATERIALIZATION_MANIFEST";
    /** Phase 7 — adapter plan envelope (per-adapter schemaVersion in body). */
    public static final String TYPE_ADAPTER_PLAN = "ADAPTER_PLAN";
    /** Phase 7 — adapter side-effecting execution result (materialize/submit/cancel). */
    public static final String TYPE_ADAPTER_EXECUTION = "ADAPTER_EXECUTION";
    /** Phase 7 — runtime poll snapshot ({@code runtime-status.v1}). */
    public static final String TYPE_RUNTIME_STATUS = "RUNTIME_STATUS";
    /** Phase 7 — cancel verb result ({@code CANCEL_RESULT} per plan). */
    public static final String TYPE_CANCEL_RESULT = "CANCEL_RESULT";
    public static final String EVENT_RUN_STATE_CHANGED = "RUN_STATE_CHANGED";
    public static final String EVENT_PREFLIGHT_FAILED = "PREFLIGHT_FAILED";
    public static final String EVENT_PREFLIGHT_PASSED = "PREFLIGHT_PASSED";

    private final DeploymentEvidenceRepository evidenceRepo;
    private final DeploymentEventRepository eventRepo;
    private final ObjectMapper canonicalJson = new ObjectMapper();

    public DeploymentEvidenceService(DeploymentEvidenceRepository evidenceRepo,
                                     DeploymentEventRepository eventRepo) {
        this.evidenceRepo = evidenceRepo;
        this.eventRepo = eventRepo;
    }

    /**
     * Persist a {@link PreflightCheckResult} as a {@code PREFLIGHT_RESULT}
     * evidence row. The body is the canonical JSON of the result; the
     * sha256 is computed over the same bytes for downstream replay.
     */
    public DeploymentEvidence recordPreflightResult(String deploymentId,
                                                    String deploymentRunId,
                                                    String packageId,
                                                    PreflightCheckResult result,
                                                    String correlationId) {
        Map<String, Object> body = result.toCanonicalJson();
        DeploymentEvidence evidence = new DeploymentEvidence();
        evidence.setDeploymentId(deploymentId);
        evidence.setDeploymentRunId(deploymentRunId);
        evidence.setPackageId(packageId);
        evidence.setSchemaVersion(EVIDENCE_SCHEMA);
        evidence.setArtifactId("preflight-" + (deploymentRunId != null ? deploymentRunId : "ad-hoc"));
        evidence.setType(TYPE_PREFLIGHT_RESULT);
        evidence.setPath("validation/preflight-result.json");
        evidence.setSha256(sha256Json(body));
        evidence.setProducedBy("DeploymentPreflightService");
        evidence.setCorrelationId(correlationId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", result.status());
        summary.put("blockerCount", result.blockers().size());
        evidence.setSummary(summary);
        evidence.setBody(body);
        return evidenceRepo.save(evidence);
    }

    /**
     * Persist a {@code RUN_STATE_CHANGED} event capturing the (from, to)
     * status transition with the resolved actor + body hash.
     */
    public DeploymentEvent recordRunStateChange(String deploymentId,
                                                String deploymentRunId,
                                                String fromStatus,
                                                String toStatus,
                                                CallerContext caller,
                                                String correlationId,
                                                String requestBodySha256,
                                                Map<String, Object> details) {
        DeploymentEvent event = new DeploymentEvent();
        event.setDeploymentId(deploymentId);
        event.setDeploymentRunId(deploymentRunId);
        event.setSchemaVersion(EVENT_SCHEMA);
        event.setEventType(EVENT_RUN_STATE_CHANGED);
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setActorType(caller != null && caller.surface() != null
                ? caller.surface().name() : "SYSTEM");
        event.setActorId(caller != null ? caller.userId() : "system");
        event.setSurface(caller != null && caller.surface() != null
                ? caller.surface().name() : null);
        event.setCorrelationId(correlationId);
        event.setRequestBodySha256(requestBodySha256);
        event.setDetails(details == null ? Map.of() : details);
        return eventRepo.save(event);
    }

    /**
     * Convenience: emit {@code PREFLIGHT_FAILED} or {@code PREFLIGHT_PASSED}
     * event after running preflight.
     */
    public DeploymentEvent recordPreflightOutcome(String deploymentId,
                                                  String deploymentRunId,
                                                  PreflightCheckResult result,
                                                  CallerContext caller,
                                                  String correlationId,
                                                  String requestBodySha256) {
        DeploymentEvent event = new DeploymentEvent();
        event.setDeploymentId(deploymentId);
        event.setDeploymentRunId(deploymentRunId);
        event.setSchemaVersion(EVENT_SCHEMA);
        event.setEventType(result.passed() ? EVENT_PREFLIGHT_PASSED : EVENT_PREFLIGHT_FAILED);
        event.setActorType(caller != null && caller.surface() != null
                ? caller.surface().name() : "SYSTEM");
        event.setActorId(caller != null ? caller.userId() : "system");
        event.setSurface(caller != null && caller.surface() != null
                ? caller.surface().name() : null);
        event.setCorrelationId(correlationId);
        event.setRequestBodySha256(requestBodySha256);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", result.status());
        details.put("blockers", result.blockers());
        event.setDetails(details);
        return eventRepo.save(event);
    }

    /**
     * Phase 5 — record the canonical materialization manifest produced
     * by {@code LocalMaterializationAdapter}. The manifest body is the
     * stable {@code deployment-materialization-manifest.v1} payload;
     * the supplied {@code manifestSha256} is the hash the adapter
     * already computed over its canonical JSON form so the evidence
     * row's sha256 matches the on-disk file's hash exactly.
     */
    public DeploymentEvidence recordMaterializationManifest(String deploymentId,
                                                            String deploymentRunId,
                                                            String packageId,
                                                            Map<String, Object> manifest,
                                                            String manifestSha256,
                                                            String correlationId) {
        DeploymentEvidence evidence = new DeploymentEvidence();
        evidence.setDeploymentId(deploymentId);
        evidence.setDeploymentRunId(deploymentRunId);
        evidence.setPackageId(packageId);
        evidence.setSchemaVersion(EVIDENCE_SCHEMA);
        evidence.setArtifactId("materialization-" + (deploymentRunId != null ? deploymentRunId : "ad-hoc"));
        evidence.setType(TYPE_MATERIALIZATION_MANIFEST);
        evidence.setPath("materialization-manifest.json");
        evidence.setSha256(manifestSha256);
        evidence.setProducedBy("LocalMaterializationAdapter");
        evidence.setCorrelationId(correlationId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fileCount", manifest.get("fileCount"));
        summary.put("excludedCount", manifest.get("excludedCount"));
        summary.put("totalSizeBytes", manifest.get("totalSizeBytes"));
        summary.put("packageContentSha256", manifest.get("packageContentSha256"));
        evidence.setSummary(summary);
        evidence.setBody(manifest);
        return evidenceRepo.save(evidence);
    }

    /**
     * Phase 7 — generic evidence writer used by adapter and orchestrator
     * call sites. Body is canonicalized for sha256; summary is the
     * compact UI-facing snapshot.
     */
    public DeploymentEvidence recordAdapterEvidence(String deploymentId,
                                                    String deploymentRunId,
                                                    String packageId,
                                                    String type,
                                                    String artifactId,
                                                    String path,
                                                    String producedBy,
                                                    Map<String, Object> summary,
                                                    Map<String, Object> body,
                                                    String correlationId) {
        DeploymentEvidence evidence = new DeploymentEvidence();
        evidence.setDeploymentId(deploymentId);
        evidence.setDeploymentRunId(deploymentRunId);
        evidence.setPackageId(packageId);
        evidence.setSchemaVersion(EVIDENCE_SCHEMA);
        evidence.setArtifactId(artifactId);
        evidence.setType(type);
        evidence.setPath(path);
        evidence.setSha256(sha256Json(body == null ? Map.of() : body));
        evidence.setProducedBy(producedBy);
        evidence.setCorrelationId(correlationId);
        evidence.setSummary(summary == null ? Map.of() : summary);
        evidence.setBody(body == null ? Map.of() : body);
        return evidenceRepo.save(evidence);
    }

    /** Canonical SHA-256 over a JSON body, used for evidence sha256 and idempotency hashing. */
    public String sha256Json(Map<String, Object> json) {
        try {
            byte[] bytes = canonicalJson.writeValueAsBytes(json);
            return sha256Hex(bytes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("evidence json serialization failed", e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
