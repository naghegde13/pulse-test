package com.pulse.deploy.orchestrator;

import com.pulse.auth.policy.CallerContext;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.adapter.AdapterExecution;
import com.pulse.deploy.adapter.AdapterPlan;
import com.pulse.deploy.adapter.DeploymentTargetAdapter;
import com.pulse.deploy.adapter.RuntimeStatus;
import com.pulse.deploy.evidence.DeploymentEvidenceService;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.run.DeploymentRunState;
import com.pulse.deploy.run.DeploymentRunStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 7 — single owner of run-state-machine sequencing for every
 * deployment target adapter.
 *
 * <p>Discrete verbs ({@link #materialize}, {@link #submit},
 * {@link #poll}, {@link #cancel}, {@link #rollback}) drive one
 * transition at a time. The convenience
 * {@link #runToTerminal(String, CallerContext)} drives a stub-backed
 * run end-to-end synchronously — used by the deploy controller for
 * local materialization and by the contract tests for stub-backed
 * cloud adapters. Production cloud flows would move {@code runToTerminal}
 * onto an async scheduler in Phase 8+.
 *
 * <p>Adapter dispatch: orchestrator builds its own keyed dispatch
 * table from {@link DeploymentTargetAdapter#targetType()} on
 * construction so adapters only need to declare their canonical key
 * once.
 */
@Service
public class DeploymentRunOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRunOrchestrator.class);

    private final DeploymentRunRepository runRepo;
    private final DeploymentTargetRepository targetRepo;
    private final DeploymentRunStateService runStateService;
    private final DeploymentEvidenceService evidenceService;
    private final Map<String, DeploymentTargetAdapter> adapters = new ConcurrentHashMap<>();

    public DeploymentRunOrchestrator(DeploymentRunRepository runRepo,
                                     DeploymentTargetRepository targetRepo,
                                     DeploymentRunStateService runStateService,
                                     DeploymentEvidenceService evidenceService,
                                     List<DeploymentTargetAdapter> registeredAdapters) {
        this.runRepo = runRepo;
        this.targetRepo = targetRepo;
        this.runStateService = runStateService;
        this.evidenceService = evidenceService;
        for (DeploymentTargetAdapter adapter : registeredAdapters) {
            DeploymentTargetAdapter prior = adapters.put(adapter.targetType(), adapter);
            if (prior != null) {
                throw new IllegalStateException(
                        "Two adapters claim the same canonical targetType: "
                                + adapter.targetType()
                                + " — " + prior.getClass().getName()
                                + " vs " + adapter.getClass().getName());
            }
        }
        log.info("DeploymentRunOrchestrator initialized with adapters: {}", adapters.keySet());
    }

    /** Adapters keyed by canonical targetType — read-only view for tests/diagnostics. */
    public Map<String, DeploymentTargetAdapter> registeredAdapters() {
        return Map.copyOf(adapters);
    }

    public DeploymentTargetAdapter resolveAdapter(String targetType) {
        DeploymentTargetAdapter adapter = adapters.get(targetType);
        if (adapter == null) {
            throw new IllegalStateException(
                    "No DeploymentTargetAdapter registered for targetType=" + targetType
                            + "; known: " + adapters.keySet());
        }
        return adapter;
    }

    // ------------------------------------------------------------------
    //  Discrete verbs
    // ------------------------------------------------------------------

    public AdapterExecution materialize(String runId, CallerContext caller) {
        DeploymentRun run = loadRun(runId);
        DeploymentTargetAdapter adapter = adapterForRun(run);
        AdapterPlan plan = adapter.materialization().plan(runId);
        recordPlan(run, plan, caller);
        AdapterExecution execution = adapter.materialization().materialize(runId);
        // Some adapter implementations advance the run state themselves
        // (e.g. LocalMaterializationAdapter does PREFLIGHT_PASSED ->
        // MATERIALIZING -> MATERIALIZED internally to keep the Phase 5
        // contract). Re-read the run after the adapter call and only
        // drive transitions the adapter didn't already perform.
        DeploymentRun after = loadRun(runId);
        DeploymentRunState current = DeploymentRunState.parse(after.getStatus());
        if (execution.succeeded()) {
            if (current == DeploymentRunState.PREFLIGHT_PASSED) {
                runStateService.transition(runId, DeploymentRunState.MATERIALIZING,
                        "orchestrator: materialize start");
                runStateService.transition(runId, DeploymentRunState.MATERIALIZED,
                        "orchestrator: materialize succeeded");
            } else if (current == DeploymentRunState.MATERIALIZING) {
                runStateService.transition(runId, DeploymentRunState.MATERIALIZED,
                        "orchestrator: materialize succeeded");
            }
            // current == MATERIALIZED already → no-op, adapter handled it.
        } else if (current != DeploymentRunState.FAILED) {
            runStateService.transition(runId, DeploymentRunState.FAILED,
                    "materialize failed: " + execution.failureReason());
        }
        recordExecution(run, execution, caller);
        return execution;
    }

    public AdapterExecution submit(String runId, CallerContext caller) {
        DeploymentRun run = loadRun(runId);
        DeploymentTargetAdapter adapter = adapterForRun(run);
        AdapterPlan plan = adapter.submitPoll().plan(runId);
        recordPlan(run, plan, caller);
        // Phase 7 contract: write provider id BEFORE poll. The
        // orchestrator transitions MATERIALIZED -> SUBMITTING -> RUNNING
        // around the adapter call so the run row's status reflects the
        // submit lifecycle even if the adapter returns sync-success.
        runStateService.transition(runId, DeploymentRunState.SUBMITTING,
                "submit started by orchestrator");
        AdapterExecution execution = adapter.submitPoll().submit(runId);
        if (execution.succeeded() && execution.providerRunId() != null) {
            stashRunMeta(run, "providerRunId", execution.providerRunId());
            if (!execution.details().containsKey("activationProviderRunId")) {
                stashRunMeta(run, "activationProviderRunId", execution.providerRunId());
            }
            // Adapter-specific sync/validation metadata is stashed by the
            // adapter via execution.details so poll() and evidence can
            // distinguish activation from optional Airflow validation.
            for (var entry : execution.details().entrySet()) {
                if (entry.getValue() != null) {
                    stashRunMeta(run, entry.getKey(), entry.getValue());
                }
            }
            runRepo.save(run);
            runStateService.transition(runId, DeploymentRunState.RUNNING,
                    "submit succeeded; provider id=" + execution.providerRunId());
        } else if (!execution.succeeded()) {
            runStateService.transition(runId, DeploymentRunState.FAILED,
                    "submit failed: " + execution.failureReason());
        }
        recordExecution(run, execution, caller);
        return execution;
    }

    /**
     * Phase 7 closeout — read-only, idempotent for unchanged runtime
     * status. A scheduler that loops {@code poll(runId)} every N seconds
     * writes ZERO new evidence rows and triggers ZERO new state
     * transitions while the runtime status is unchanged.
     *
     * <p>Idempotency mechanism:
     * <ul>
     *   <li>Compute a deterministic content key from
     *       {@code (providerRunId, effectiveState, providerStatus,
     *       failureReason)}.</li>
     *   <li>Compare against {@code metadata.lastRuntimeStatusKey} on
     *       the run row.</li>
     *   <li>If unchanged → return the {@link RuntimeStatus} without
     *       writing evidence and without attempting a state transition
     *       (the prior poll already did both).</li>
     *   <li>If changed → write a single RUNTIME_STATUS evidence row
     *       with a deterministic artifact id derived from the content
     *       key, drive the state transition (if legal), and persist
     *       the new key on the run.</li>
     * </ul>
     *
     * <p>Side effects on first observation are intentional and bounded;
     * unbounded duplicate writes are impossible because the artifact
     * id is deterministic.
     */
    public RuntimeStatus poll(String runId, CallerContext caller) {
        DeploymentRun run = loadRun(runId);
        DeploymentTargetAdapter adapter = adapterForRun(run);
        RuntimeStatus status = adapter.submitPoll().poll(runId);
        String newKey = runtimeStatusContentKey(status);
        String priorKey = readMetaString(run, "lastRuntimeStatusKey");
        if (newKey.equals(priorKey)) {
            // Unchanged poll — no-op for evidence + state.
            return status;
        }
        recordRuntimeStatus(run, status, newKey, caller);
        if (status.providerRunId() != null && !status.providerRunId().isBlank()) {
            stashRunMeta(run, "providerRunId", status.providerRunId());
        }
        Object validationStatus = status.details().get("validationStatus");
        if (validationStatus != null) {
            stashRunMeta(run, "validationStatus", validationStatus);
        }
        stashRunMeta(run, "lastRuntimeStatusKey", newKey);
        runRepo.save(run);
        // Drive the run forward when the runtime says so. The state
        // machine rejects illegal transitions, so the orchestrator
        // never lands the run in an inconsistent place.
        DeploymentRunState current = DeploymentRunState.parse(run.getStatus());
        DeploymentRunState target = status.effectiveState();
        if (target != null && current != null && current != target
                && DeploymentRunState.canTransition(current, target)) {
            runStateService.transition(runId, target,
                    target == DeploymentRunState.FAILED ? status.failureReason() : null);
        }
        return status;
    }

    /**
     * Deterministic content key for a {@link RuntimeStatus}. Two polls
     * that return identical values for {@code providerRunId},
     * {@code effectiveState}, {@code providerStatus}, and
     * {@code failureReason} produce the same key. Wall-clock fields
     * ({@code providerObservedAt}) and free-form {@code details} are
     * intentionally excluded so jitter on the provider's clock does
     * not break idempotency.
     */
    public static String runtimeStatusContentKey(RuntimeStatus status) {
        if (status == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(status.adapter() == null ? "" : status.adapter()).append('|');
        sb.append(status.providerRunId() == null ? "" : status.providerRunId()).append('|');
        sb.append(status.effectiveState() == null ? "" : status.effectiveState().name()).append('|');
        sb.append(status.providerStatus() == null ? "" : status.providerStatus()).append('|');
        sb.append(status.failureReason() == null ? "" : status.failureReason());
        return sha256Hex(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public AdapterExecution cancel(String runId, String reason, CallerContext caller) {
        DeploymentRun run = loadRun(runId);
        DeploymentTargetAdapter adapter = adapterForRun(run);
        DeploymentRunState current = DeploymentRunState.parse(run.getStatus());
        if (current != null && current != DeploymentRunState.CANCEL_REQUESTED
                && DeploymentRunState.canTransition(current, DeploymentRunState.CANCEL_REQUESTED)) {
            runStateService.transition(runId, DeploymentRunState.CANCEL_REQUESTED, reason);
        }
        AdapterExecution execution = adapter.cancelRollback().cancel(runId, reason);
        // Always emit CANCEL_RESULT evidence, success or failure.
        recordCancelResult(run, execution, caller);
        if (execution.succeeded()
                && DeploymentRunState.canTransition(
                        DeploymentRunState.parse(run.getStatus()), DeploymentRunState.CANCELLED)) {
            runStateService.transition(runId, DeploymentRunState.CANCELLED,
                    reason == null ? "cancel acknowledged" : reason);
        } else if (!execution.succeeded()) {
            runStateService.transition(runId, DeploymentRunState.FAILED,
                    "cancel failed: " + execution.failureReason());
        }
        return execution;
    }

    public AdapterExecution rollback(String deploymentId, String toPackageId,
                                     String reason, String targetType, CallerContext caller) {
        DeploymentTargetAdapter adapter = resolveAdapter(targetType);
        return adapter.cancelRollback().rollback(deploymentId, toPackageId, reason);
    }

    // ------------------------------------------------------------------
    //  Convenience: drive a stub-backed run to a terminal state
    // ------------------------------------------------------------------

    /**
     * Drives a run synchronously through MATERIALIZE -> SUBMIT -> POLL
     * until it reaches a terminal state. Designed for stub-backed
     * adapters and the local materialization adapter; production cloud
     * flows would move this onto an async scheduler.
     *
     * <p>Caller contract: {@code runId} must already be at
     * {@link DeploymentRunState#PREFLIGHT_PASSED} (Phase 4 lands runs
     * there after preflight succeeds).
     */
    public DeploymentRunState runToTerminal(String runId, CallerContext caller) {
        DeploymentRun run = loadRun(runId);
        DeploymentRunState current = DeploymentRunState.parse(run.getStatus());
        if (current != DeploymentRunState.PREFLIGHT_PASSED) {
            throw new IllegalStateException(
                    "runToTerminal requires PREFLIGHT_PASSED, got " + run.getStatus());
        }
        AdapterExecution mat = materialize(runId, caller);
        if (!mat.succeeded()) return DeploymentRunState.FAILED;
        AdapterExecution sub = submit(runId, caller);
        if (!sub.succeeded()) return DeploymentRunState.FAILED;
        // After submit, poll once for stub-backed adapters which are
        // synchronously SUCCEEDED. Production cloud would loop with a
        // backoff; Phase 7 takes a single poll because every shipped
        // adapter resolves SUCCEEDED on the first poll under stubs.
        RuntimeStatus status = poll(runId, caller);
        return status.effectiveState();
    }

    /**
     * Drives a run through materialization and submit only. Used when deploy
     * activation succeeds but smoke validation is intentionally left running
     * asynchronously through Airflow.
     */
    public AdapterExecution runThroughSubmit(String runId, CallerContext caller) {
        DeploymentRun run = loadRun(runId);
        DeploymentRunState current = DeploymentRunState.parse(run.getStatus());
        if (current != DeploymentRunState.PREFLIGHT_PASSED) {
            throw new IllegalStateException(
                    "runThroughSubmit requires PREFLIGHT_PASSED, got " + run.getStatus());
        }
        AdapterExecution mat = materialize(runId, caller);
        if (!mat.succeeded()) {
            return mat;
        }
        return submit(runId, caller);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private DeploymentRun loadRun(String runId) {
        return runRepo.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("DeploymentRun", runId));
    }

    private DeploymentTargetAdapter adapterForRun(DeploymentRun run) {
        String targetId = readMetaString(run, "targetId");
        if (targetId == null) {
            throw new IllegalStateException("DeploymentRun " + run.getId()
                    + " has no metadata.targetId; orchestrator cannot resolve adapter.");
        }
        DeploymentTarget target = targetRepo.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("DeploymentTarget", targetId));
        return resolveAdapter(target.getTargetType());
    }

    private void recordPlan(DeploymentRun run, AdapterPlan plan, CallerContext caller) {
        Map<String, Object> body = plan.toCanonicalJson();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("adapter", plan.adapter());
        summary.put("verb", plan.verb());
        summary.put("planSha256", plan.planSha256());
        summary.put("capabilityApproved", plan.capability() != null && plan.capability().approved());
        evidenceService.recordAdapterEvidence(
                run.getDeploymentId(), run.getId(),
                readMetaString(run, "packageId"),
                DeploymentEvidenceService.TYPE_ADAPTER_PLAN,
                "plan-" + plan.adapter().toLowerCase() + "-" + plan.verb().toLowerCase()
                        + "-" + run.getId(),
                "adapter-plans/" + plan.adapter() + "/" + plan.verb() + "/" + run.getId() + ".json",
                "DeploymentRunOrchestrator",
                summary, body, run.getCorrelationId());
    }

    private void recordExecution(DeploymentRun run, AdapterExecution execution, CallerContext caller) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verb", execution.verb());
        body.put("succeeded", execution.succeeded());
        body.put("resultingState", execution.resultingState() == null
                ? null : execution.resultingState().name());
        body.put("providerRunId", execution.providerRunId());
        body.put("failureReason", execution.failureReason());
        body.put("details", execution.details());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("verb", execution.verb());
        summary.put("succeeded", execution.succeeded());
        summary.put("providerRunId", execution.providerRunId());
        evidenceService.recordAdapterEvidence(
                run.getDeploymentId(), run.getId(),
                readMetaString(run, "packageId"),
                DeploymentEvidenceService.TYPE_ADAPTER_EXECUTION,
                "exec-" + execution.verb().toLowerCase() + "-" + run.getId(),
                "adapter-executions/" + execution.verb() + "/" + run.getId() + ".json",
                "DeploymentRunOrchestrator",
                summary, body, run.getCorrelationId());
    }

    private void recordRuntimeStatus(DeploymentRun run, RuntimeStatus status,
                                     String contentKey, CallerContext caller) {
        Map<String, Object> body = status.toCanonicalJson();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("adapter", status.adapter());
        summary.put("effectiveState", status.effectiveState() == null
                ? null : status.effectiveState().name());
        summary.put("providerStatus", status.providerStatus());
        summary.put("providerRunId", status.providerRunId());
        // Phase 7 closeout — deterministic artifact id derived from the
        // status content (NOT System.nanoTime). Two polls with identical
        // status produce the same artifact id; the orchestrator already
        // skips the write before reaching here when the key is unchanged,
        // so this id is also a stable downstream cross-reference for the
        // first observation of any given runtime status.
        String artifactId = "status-" + run.getId() + "-" + contentKey.substring(0, 16);
        evidenceService.recordAdapterEvidence(
                run.getDeploymentId(), run.getId(),
                readMetaString(run, "packageId"),
                DeploymentEvidenceService.TYPE_RUNTIME_STATUS,
                artifactId,
                "runtime-status/" + run.getId() + ".json",
                "DeploymentRunOrchestrator",
                summary, body, run.getCorrelationId());
    }

    private void recordCancelResult(DeploymentRun run, AdapterExecution execution, CallerContext caller) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verb", execution.verb());
        body.put("succeeded", execution.succeeded());
        body.put("resultingState", execution.resultingState() == null
                ? null : execution.resultingState().name());
        body.put("failureReason", execution.failureReason());
        body.put("details", execution.details());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("succeeded", execution.succeeded());
        summary.put("verb", execution.verb());
        evidenceService.recordAdapterEvidence(
                run.getDeploymentId(), run.getId(),
                readMetaString(run, "packageId"),
                DeploymentEvidenceService.TYPE_CANCEL_RESULT,
                "cancel-" + run.getId(),
                "cancel-result/" + run.getId() + ".json",
                "DeploymentRunOrchestrator",
                summary, body, run.getCorrelationId());
    }

    private static String readMetaString(DeploymentRun run, String key) {
        Map<String, Object> meta = run.getMetadata();
        if (meta == null) return null;
        Object value = meta.get(key);
        return value == null ? null : value.toString();
    }

    private static void stashRunMeta(DeploymentRun run, String key, Object value) {
        Map<String, Object> meta = run.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(run.getMetadata());
        meta.put(key, value);
        run.setMetadata(meta);
    }
}
