package com.pulse.deploy.adapter.local;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.adapter.AdapterExecution;
import com.pulse.deploy.adapter.AdapterPlan;
import com.pulse.deploy.adapter.CancelRollbackAdapter;
import com.pulse.deploy.adapter.CapabilityCheckResult;
import com.pulse.deploy.adapter.DeploymentTargetAdapter;
import com.pulse.deploy.adapter.LocalMaterializationAdapter;
import com.pulse.deploy.adapter.LogsAdapter;
import com.pulse.deploy.adapter.MaterializationAdapter;
import com.pulse.deploy.adapter.RuntimeLogs;
import com.pulse.deploy.adapter.RuntimeStatus;
import com.pulse.deploy.adapter.SubmitPollAdapter;
import com.pulse.deploy.capability.RuntimeCapabilityMatrix;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.deploy.evidence.EvidenceProofLevel;
import com.pulse.deploy.run.DeploymentRunState;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 7 — local target facade. Wraps the existing
 * {@link LocalMaterializationAdapter} so its public {@code materialize(runId)}
 * API stays unchanged (Phase 5 contract tests depend on the
 * {@link LocalMaterializationAdapter.MaterializationResult} shape) and
 * exposes the new {@link DeploymentTargetAdapter} surface for the
 * Phase 7 orchestrator.
 *
 * <p>SubmitPoll / Logs / Cancel are no-ops because local materialization
 * has no real runtime: the package lands on disk and that's the run's
 * terminal proof. The orchestrator still drives the run through the
 * {@code SUCCEEDED} state via the no-op {@link SubmitPollAdapter}.
 */
@Component
public class LocalDeploymentTargetAdapter implements DeploymentTargetAdapter {

    public static final String TARGET_TYPE = RuntimeCapabilityMatrix.LOCAL;
    public static final String SCHEMA_VERSION_PLAN = "local-materialization-plan.v1";

    private final LocalMaterializationAdapter local;
    private final DeploymentRunRepository runRepo;
    private final RuntimeCapabilityMatrix capabilityMatrix;
    private final ObjectMapper canonicalJson = new ObjectMapper();

    private final MaterializationAdapter materialization = new LocalMaterializationLeg();
    private final SubmitPollAdapter submitPoll = new LocalSubmitPollLeg();
    private final LogsAdapter logs = new LocalLogsLeg();
    private final CancelRollbackAdapter cancelRollback = new LocalCancelRollbackLeg();

    public LocalDeploymentTargetAdapter(LocalMaterializationAdapter local,
                                        DeploymentRunRepository runRepo,
                                        RuntimeCapabilityMatrix capabilityMatrix) {
        this.local = local;
        this.runRepo = runRepo;
        this.capabilityMatrix = capabilityMatrix;
    }

    @Override public String targetType() { return TARGET_TYPE; }
    @Override public MaterializationAdapter materialization() { return materialization; }
    @Override public SubmitPollAdapter submitPoll() { return submitPoll; }
    @Override public LogsAdapter logs() { return logs; }
    @Override public CancelRollbackAdapter cancelRollback() { return cancelRollback; }

    // ------------------------------------------------------------------
    //  Materialization leg — delegates to the Phase 5 adapter
    // ------------------------------------------------------------------

    private final class LocalMaterializationLeg implements MaterializationAdapter {

        @Override
        public AdapterPlan plan(String deploymentRunId) {
            DeploymentRun run = loadRun(deploymentRunId);
            String packageId = readMetaString(run, "packageId");
            String targetId = readMetaString(run, "targetId");
            String environment = readMetaString(run, "environment");

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("verb", AdapterPlan.VERB_MATERIALIZE);
            details.put("outputRoot", "build/deployment-materialization/" + deploymentRunId);
            details.put("manifestPath", "materialization-manifest.json");
            details.put("evidenceIndexPath", "evidence-index.json");
            details.put("manifestSchemaVersion", LocalMaterializationAdapter.SCHEMA_VERSION_MANIFEST);
            details.put("evidenceIndexSchemaVersion", LocalMaterializationAdapter.SCHEMA_VERSION_EVIDENCE_INDEX);
            // Local materialization is runtime-agnostic — no secret refs,
            // no runtime IDs, no capability fallback path.
            details.put("notes", "Local materialization writes the package to disk; no runtime submit follows.");

            CapabilityCheckResult capability = capabilityMatrix.evaluate(
                    new RuntimeCapabilityMatrix.Request(TARGET_TYPE,
                            RuntimeCapabilityMatrix.FORMAT_UNSPECIFIED, false));

            return new AdapterPlan(
                    SCHEMA_VERSION_PLAN,
                    TARGET_TYPE,
                    AdapterPlan.VERB_MATERIALIZE,
                    deploymentRunId,
                    packageId,
                    run.getTenantId(),
                    environment,
                    targetId,
                    capability,
                    details,
                    sha256OfDetails(details));
        }

        @Override
        public AdapterExecution materialize(String deploymentRunId) {
            try {
                LocalMaterializationAdapter.MaterializationResult result = local.materialize(deploymentRunId);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("manifestPath", result.manifestPath().toString());
                details.put("evidenceIndexPath", result.evidenceIndexPath().toString());
                details.put("packageContentSha256", result.packageContentSha256());
                details.put("manifestSha256", result.manifestSha256());
                details.put("fileCount", result.fileCount());
                return AdapterExecution.success(
                        AdapterPlan.VERB_MATERIALIZE,
                        DeploymentRunState.MATERIALIZED,
                        // Local has no provider id; use the run id itself
                        // so downstream code that requires a non-null
                        // providerRunId still works.
                        deploymentRunId,
                        details);
            } catch (RuntimeException e) {
                return AdapterExecution.failure(
                        AdapterPlan.VERB_MATERIALIZE,
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        Map.of());
            }
        }
    }

    // ------------------------------------------------------------------
    //  SubmitPoll leg — no-op for local
    // ------------------------------------------------------------------

    private final class LocalSubmitPollLeg implements SubmitPollAdapter {

        @Override
        public AdapterPlan plan(String deploymentRunId) {
            DeploymentRun run = loadRun(deploymentRunId);
            String packageId = readMetaString(run, "packageId");
            String targetId = readMetaString(run, "targetId");
            String environment = readMetaString(run, "environment");
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("verb", AdapterPlan.VERB_SUBMIT);
            details.put("notes", "Local target has no runtime submit; this plan is a no-op proof "
                    + "so the orchestrator can still drive RUNNING -> SUCCEEDED for accounting.");
            CapabilityCheckResult capability = capabilityMatrix.evaluate(
                    new RuntimeCapabilityMatrix.Request(TARGET_TYPE,
                            RuntimeCapabilityMatrix.FORMAT_UNSPECIFIED, false));
            return new AdapterPlan(
                    SCHEMA_VERSION_PLAN, TARGET_TYPE, AdapterPlan.VERB_SUBMIT,
                    deploymentRunId, packageId, run.getTenantId(), environment, targetId,
                    capability, details, sha256OfDetails(details));
        }

        @Override
        public AdapterExecution submit(String deploymentRunId) {
            // Local submit is a synthetic SUCCEEDED; the orchestrator will
            // transition through SUBMITTING -> RUNNING -> SUCCEEDED.
            return AdapterExecution.success(
                    AdapterPlan.VERB_SUBMIT, DeploymentRunState.RUNNING,
                    deploymentRunId,
                    Map.of("notes", "local target — no runtime to submit to"));
        }

        @Override
        public RuntimeStatus poll(String deploymentRunId) {
            // PKT-0005: evidence proof level metadata explicitly marks this
            // as LOCAL_SYNTHETIC so consumers never mistake it for real
            // data-plane proof. runtimeProof and promotionReady are always
            // false for local synthetic runs.
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("notes", "local materialization is terminal at MATERIALIZED; "
                    + "synthetic SUCCEEDED so the orchestrator can complete the run.");
            details.put("proofLevel", EvidenceProofLevel.LOCAL_SYNTHETIC.name());
            details.put("runtimeProof", false);
            details.put("promotionReady", false);
            return new RuntimeStatus(
                    RuntimeStatus.SCHEMA_VERSION,
                    TARGET_TYPE,
                    deploymentRunId,
                    DeploymentRunState.SUCCEEDED,
                    "succeeded",
                    1.0,
                    Instant.now(),
                    null,
                    details);
        }
    }

    // ------------------------------------------------------------------
    //  Logs leg — empty for local
    // ------------------------------------------------------------------

    private final class LocalLogsLeg implements LogsAdapter {

        @Override
        public RuntimeLogs fetch(String deploymentRunId, int limit) {
            return RuntimeLogs.empty(TARGET_TYPE, deploymentRunId, Instant.now());
        }
    }

    // ------------------------------------------------------------------
    //  Cancel/rollback leg — no-op for local
    // ------------------------------------------------------------------

    private final class LocalCancelRollbackLeg implements CancelRollbackAdapter {

        @Override
        public AdapterExecution cancel(String deploymentRunId, String reason) {
            // Local materialization is synchronous; there's nothing to
            // cancel mid-flight. Return success so the orchestrator
            // moves CANCEL_REQUESTED -> CANCELLED for accounting.
            return AdapterExecution.success("CANCEL", DeploymentRunState.CANCELLED,
                    deploymentRunId,
                    Map.of("reason", reason == null ? "" : reason,
                           "notes", "local target has no runtime to cancel"));
        }

        @Override
        public AdapterExecution rollback(String deploymentId, String toPackageId, String reason) {
            return AdapterExecution.success("ROLLBACK", DeploymentRunState.SUCCEEDED,
                    toPackageId,
                    Map.of("deploymentId", deploymentId,
                           "toPackageId", toPackageId,
                           "reason", reason == null ? "" : reason,
                           "notes", "local target rollback is a no-op; the orchestrator "
                                   + "creates a new run for the target package."));
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private DeploymentRun loadRun(String deploymentRunId) {
        return runRepo.findById(deploymentRunId)
                .orElseThrow(() -> new ResourceNotFoundException("DeploymentRun", deploymentRunId));
    }

    private static String readMetaString(DeploymentRun run, String key) {
        Map<String, Object> meta = run.getMetadata();
        if (meta == null) return null;
        Object value = meta.get(key);
        return value == null ? null : value.toString();
    }

    private String sha256OfDetails(Map<String, Object> details) {
        try {
            byte[] bytes = canonicalJson.writeValueAsBytes(details);
            return sha256Hex(bytes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("plan details serialization failed", e);
        }
    }

    static String sha256Hex(byte[] bytes) {
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
