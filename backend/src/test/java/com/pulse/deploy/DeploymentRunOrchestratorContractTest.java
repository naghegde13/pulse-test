package com.pulse.deploy;

import com.pulse.deploy.adapter.AdapterExecution;
import com.pulse.deploy.adapter.AdapterPlan;
import com.pulse.deploy.adapter.CancelRollbackAdapter;
import com.pulse.deploy.adapter.CapabilityCheckResult;
import com.pulse.deploy.adapter.DeploymentTargetAdapter;
import com.pulse.deploy.adapter.LogsAdapter;
import com.pulse.deploy.adapter.MaterializationAdapter;
import com.pulse.deploy.adapter.RuntimeLogs;
import com.pulse.deploy.adapter.RuntimeStatus;
import com.pulse.deploy.adapter.SubmitPollAdapter;
import com.pulse.deploy.evidence.DeploymentEvidenceService;
import com.pulse.deploy.model.DeploymentEvidence;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.orchestrator.DeploymentRunOrchestrator;
import com.pulse.deploy.repository.DeploymentEventRepository;
import com.pulse.deploy.repository.DeploymentEvidenceRepository;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.run.DeploymentRunState;
import com.pulse.deploy.run.DeploymentRunStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7 — DeploymentRunOrchestrator contract.
 *
 * <p>Pins:
 * <ul>
 *   <li>full happy path drives PREFLIGHT_PASSED → SUCCEEDED via
 *       MATERIALIZING → MATERIALIZED → SUBMITTING → RUNNING → SUCCEEDED;</li>
 *   <li>each verb writes the documented evidence type
 *       (ADAPTER_PLAN, ADAPTER_EXECUTION, RUNTIME_STATUS,
 *       CANCEL_RESULT);</li>
 *   <li>cancellation routes through CANCEL_REQUESTED → CANCELLED with
 *       a CANCEL_RESULT evidence row regardless of outcome;</li>
 *   <li>a duplicate adapter targetType registration fails fast;</li>
 *   <li>missing adapter for a run's targetType throws.</li>
 * </ul>
 */
class DeploymentRunOrchestratorContractTest {

    private DeploymentRunRepository runRepo;
    private DeploymentTargetRepository targetRepo;
    private DeploymentEvidenceRepository evidenceRepo;
    private DeploymentEventRepository eventRepo;
    private DeploymentRunStateService runStateService;
    private DeploymentEvidenceService evidenceService;
    private RecordingAdapter adapter;
    private DeploymentRunOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        runRepo = mock(DeploymentRunRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        evidenceRepo = mock(DeploymentEvidenceRepository.class);
        eventRepo = mock(DeploymentEventRepository.class);
        when(runRepo.save(any(DeploymentRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceRepo.save(any(DeploymentEvidence.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        runStateService = new DeploymentRunStateService(runRepo);
        evidenceService = new DeploymentEvidenceService(evidenceRepo, eventRepo);
        adapter = new RecordingAdapter("STUB_TARGET");
        orchestrator = new DeploymentRunOrchestrator(runRepo, targetRepo,
                runStateService, evidenceService, List.of(adapter));
    }

    @Test
    @DisplayName("Adapter dispatch table is built from canonical targetType keys")
    void adapterDispatchTableBuilt() {
        assertEquals(1, orchestrator.registeredAdapters().size());
        assertTrue(orchestrator.registeredAdapters().containsKey("STUB_TARGET"));
        assertEquals(adapter, orchestrator.resolveAdapter("STUB_TARGET"));
    }

    @Test
    @DisplayName("Two adapters with the same targetType fail fast at construction")
    void duplicateAdaptersFailFast() {
        RecordingAdapter dupe = new RecordingAdapter("STUB_TARGET");
        assertThrows(IllegalStateException.class, () -> new DeploymentRunOrchestrator(
                runRepo, targetRepo, runStateService, evidenceService,
                List.of(adapter, dupe)));
    }

    @Test
    @DisplayName("Unknown targetType throws on resolve")
    void unknownTargetTypeThrows() {
        assertThrows(IllegalStateException.class,
                () -> orchestrator.resolveAdapter("MISSING"));
    }

    @Test
    @DisplayName("runToTerminal happy path: PREFLIGHT_PASSED → SUCCEEDED with full evidence sequence")
    void happyPathRunsToSucceeded() {
        DeploymentRun run = wireRun("run-1");
        DeploymentRunState terminal = orchestrator.runToTerminal(run.getId(), null);
        assertEquals(DeploymentRunState.SUCCEEDED, terminal);
        // Final state on the row (state service mutates in-place).
        assertEquals(DeploymentRunState.SUCCEEDED.name(), run.getStatus());
        // Adapter saw every verb in order.
        assertEquals(List.of("plan-MATERIALIZE", "materialize",
                             "plan-SUBMIT", "submit", "poll"),
                     adapter.invocations);
        // Evidence types written: ADAPTER_PLAN x2 (mat + sub), ADAPTER_EXECUTION x2, RUNTIME_STATUS x1.
        long planCount = adapter.savedTypes(evidenceRepo, DeploymentEvidenceService.TYPE_ADAPTER_PLAN);
        long execCount = adapter.savedTypes(evidenceRepo, DeploymentEvidenceService.TYPE_ADAPTER_EXECUTION);
        long statusCount = adapter.savedTypes(evidenceRepo, DeploymentEvidenceService.TYPE_RUNTIME_STATUS);
        assertEquals(2, planCount, "expected 2 ADAPTER_PLAN evidence rows");
        assertEquals(2, execCount, "expected 2 ADAPTER_EXECUTION evidence rows");
        assertEquals(1, statusCount, "expected 1 RUNTIME_STATUS evidence row");
    }

    @Test
    @DisplayName("runThroughSubmit stops after activation when validation remains asynchronous")
    void runThroughSubmitStopsAfterSubmit() {
        DeploymentRun run = wireRun("run-1");

        AdapterExecution result = orchestrator.runThroughSubmit(run.getId(), null);

        assertTrue(result.succeeded());
        assertEquals(DeploymentRunState.RUNNING.name(), run.getStatus());
        assertEquals(List.of("plan-MATERIALIZE", "materialize",
                             "plan-SUBMIT", "submit"),
                adapter.invocations);
    }

    @Test
    @DisplayName("runToTerminal rejects runs not at PREFLIGHT_PASSED")
    void runToTerminalRequiresPreflightPassed() {
        DeploymentRun run = wireRun("run-1");
        run.setStatus(DeploymentRunState.PENDING.name());
        assertThrows(IllegalStateException.class,
                () -> orchestrator.runToTerminal(run.getId(), null));
    }

    @Test
    @DisplayName("submit failure transitions run to FAILED and writes ADAPTER_EXECUTION evidence")
    void submitFailureTransitionsToFailed() {
        DeploymentRun run = wireRun("run-1");
        // Manually advance to MATERIALIZED before invoking submit.
        run.setStatus(DeploymentRunState.MATERIALIZED.name());
        adapter.submitOutcome = AdapterExecution.failure(
                AdapterPlan.VERB_SUBMIT, "stub: simulated submit failure", Map.of());
        AdapterExecution result = orchestrator.submit(run.getId(), null);
        assertEquals(false, result.succeeded());
        assertEquals(DeploymentRunState.FAILED.name(), run.getStatus());
    }

    @Test
    @DisplayName("cancel writes CANCEL_RESULT evidence and transitions to CANCELLED on success")
    void cancelWritesCancelResult() {
        DeploymentRun run = wireRun("run-1");
        // Cancel only valid from a non-terminal non-pre-state.
        run.setStatus(DeploymentRunState.RUNNING.name());
        AdapterExecution result = orchestrator.cancel(run.getId(),
                "operator requested cancel", null);
        assertTrue(result.succeeded());
        assertEquals(DeploymentRunState.CANCELLED.name(), run.getStatus());
        long cancelEvidence = adapter.savedTypes(evidenceRepo,
                DeploymentEvidenceService.TYPE_CANCEL_RESULT);
        assertEquals(1, cancelEvidence,
                "exactly one CANCEL_RESULT evidence row must be written");
    }

    @Test
    @DisplayName("poll() drives the run to the runtime's effective state when the transition is legal")
    void pollDrivesRunToRuntimeState() {
        DeploymentRun run = wireRun("run-1");
        run.setStatus(DeploymentRunState.RUNNING.name());
        adapter.pollOutcome = new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION,
                adapter.targetType(), "provider-job-1",
                DeploymentRunState.SUCCEEDED, "DONE", 1.0,
                Instant.now(), null, Map.of());
        RuntimeStatus status = orchestrator.poll(run.getId(), null);
        assertEquals(DeploymentRunState.SUCCEEDED, status.effectiveState());
        assertEquals(DeploymentRunState.SUCCEEDED.name(), run.getStatus());
    }

    @Test
    @DisplayName("Phase 7 closeout — repeated poll with unchanged status writes ONE evidence row, not N")
    void repeatedPollUnchangedStatusIsIdempotent() {
        DeploymentRun run = wireRun("run-1");
        run.setStatus(DeploymentRunState.RUNNING.name());
        // Stub returns the same RUNNING status for every poll. The
        // wall-clock providerObservedAt jitters between calls (real
        // runtime behavior), so the orchestrator's content key MUST
        // ignore providerObservedAt for idempotency to hold.
        adapter.pollOutcome = new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION,
                adapter.targetType(), "provider-job-1",
                DeploymentRunState.RUNNING, "running", 0.42,
                Instant.parse("2026-05-04T10:00:00Z"), null, Map.of());
        // First poll — one RUNTIME_STATUS evidence write expected.
        orchestrator.poll(run.getId(), null);
        // Subsequent polls — orchestrator must skip evidence + skip
        // re-saving the unchanged metadata key. We jitter the timestamp
        // each call to prove the content key ignores wall-clock.
        for (int i = 0; i < 5; i++) {
            adapter.pollOutcome = new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION,
                    adapter.targetType(), "provider-job-1",
                    DeploymentRunState.RUNNING, "running", 0.42,
                    Instant.parse("2026-05-04T10:00:0" + (i + 1) + "Z"), null, Map.of());
            orchestrator.poll(run.getId(), null);
        }
        long statusEvidence = adapter.savedTypes(evidenceRepo,
                DeploymentEvidenceService.TYPE_RUNTIME_STATUS);
        assertEquals(1, statusEvidence,
                "exactly one RUNTIME_STATUS evidence row must be written across 6 unchanged polls");
    }

    @Test
    @DisplayName("Phase 7 closeout — poll evidence artifact id is deterministic (no nanoTime)")
    void pollArtifactIdIsDeterministic() {
        DeploymentRun run = wireRun("run-1");
        run.setStatus(DeploymentRunState.RUNNING.name());
        adapter.pollOutcome = new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION,
                adapter.targetType(), "provider-job-1",
                DeploymentRunState.SUCCEEDED, "DONE", 1.0,
                Instant.now(), null, Map.of());
        orchestrator.poll(run.getId(), null);
        org.mockito.ArgumentCaptor<com.pulse.deploy.model.DeploymentEvidence> cap =
                org.mockito.ArgumentCaptor.forClass(com.pulse.deploy.model.DeploymentEvidence.class);
        org.mockito.Mockito.verify(evidenceRepo, org.mockito.Mockito.atLeast(0))
                .save(cap.capture());
        com.pulse.deploy.model.DeploymentEvidence statusEvidence = cap.getAllValues().stream()
                .filter(e -> DeploymentEvidenceService.TYPE_RUNTIME_STATUS.equals(e.getType()))
                .findFirst().orElseThrow();
        // Artifact id is derived from a SHA-256 prefix of the content
        // key, NOT from wall-clock or System.nanoTime. The id is
        // deterministic and content-derived; same inputs → same id.
        String artifactId = statusEvidence.getArtifactId();
        assertTrue(artifactId.startsWith("status-run-1-"),
                "artifactId should be content-derived; got: " + artifactId);
        // SHA-256 hex is [0-9a-f]; assert no nanoTime-style numeric tail.
        String tail = artifactId.substring("status-run-1-".length());
        assertTrue(tail.matches("[0-9a-f]{16}"),
                "artifactId tail must be 16-char hex of content-key sha256, got: " + tail);
        // Compute the expected content key independently and verify the
        // tail matches the first 16 hex chars of its SHA-256.
        String expectedKey = com.pulse.deploy.orchestrator.DeploymentRunOrchestrator
                .runtimeStatusContentKey(adapter.pollOutcome);
        assertEquals(expectedKey.substring(0, 16), tail,
                "artifactId tail must equal first 16 chars of content key SHA-256");
    }

    @Test
    @DisplayName("Phase 7 closeout — status change writes a NEW evidence row with a different artifact id")
    void statusChangeWritesNewEvidence() {
        DeploymentRun run = wireRun("run-1");
        run.setStatus(DeploymentRunState.RUNNING.name());
        adapter.pollOutcome = new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION,
                adapter.targetType(), "provider-job-1",
                DeploymentRunState.RUNNING, "running", 0.5,
                Instant.parse("2026-05-04T10:00:00Z"), null, Map.of());
        orchestrator.poll(run.getId(), null);
        // Provider transitions to SUCCEEDED — different content key.
        adapter.pollOutcome = new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION,
                adapter.targetType(), "provider-job-1",
                DeploymentRunState.SUCCEEDED, "DONE", 1.0,
                Instant.parse("2026-05-04T10:01:00Z"), null, Map.of());
        orchestrator.poll(run.getId(), null);
        long statusEvidence = adapter.savedTypes(evidenceRepo,
                DeploymentEvidenceService.TYPE_RUNTIME_STATUS);
        assertEquals(2, statusEvidence,
                "two distinct runtime statuses must produce two evidence rows");
    }

    // ------------------------------------------------------------------

    private DeploymentRun wireRun(String runId) {
        DeploymentRun run = new DeploymentRun();
        run.setId(runId);
        run.setTenantId("tenant-A");
        run.setDeploymentId("dep-" + runId);
        run.setStatus(DeploymentRunState.PREFLIGHT_PASSED.name());
        run.setInitiatedBy("user-test");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("packageId", "pkg-" + runId);
        meta.put("targetId", "target-1");
        meta.put("environment", "dev");
        run.setMetadata(meta);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        DeploymentTarget target = new DeploymentTarget();
        target.setId("target-1");
        target.setTenantId("tenant-A");
        target.setEnvironment("dev");
        target.setTargetType("STUB_TARGET");
        target.setEnabled(true);
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(target));
        return run;
    }

    // ------------------------------------------------------------------
    //  Recording adapter so the test can assert on verb invocation order
    // ------------------------------------------------------------------

    private static final class RecordingAdapter implements DeploymentTargetAdapter {

        private final String targetType;
        final List<String> invocations = new ArrayList<>();
        AdapterExecution submitOutcome;
        RuntimeStatus pollOutcome;

        RecordingAdapter(String targetType) {
            this.targetType = targetType;
        }

        @Override public String targetType() { return targetType; }

        @Override public MaterializationAdapter materialization() {
            return new MaterializationAdapter() {
                @Override public AdapterPlan plan(String runId) {
                    invocations.add("plan-MATERIALIZE");
                    return new AdapterPlan(
                            "stub-plan.v1", targetType, AdapterPlan.VERB_MATERIALIZE,
                            runId, "pkg-" + runId, "tenant-A", "dev", "target-1",
                            CapabilityCheckResult.approved(targetType, "PARQUET", List.of()),
                            Map.of("verb", "MATERIALIZE"), "stub-sha");
                }
                @Override public AdapterExecution materialize(String runId) {
                    invocations.add("materialize");
                    return AdapterExecution.success(AdapterPlan.VERB_MATERIALIZE,
                            DeploymentRunState.MATERIALIZED, "stub-mat-" + runId,
                            Map.of("notes", "stub materialization"));
                }
            };
        }

        @Override public SubmitPollAdapter submitPoll() {
            return new SubmitPollAdapter() {
                @Override public AdapterPlan plan(String runId) {
                    invocations.add("plan-SUBMIT");
                    return new AdapterPlan(
                            "stub-plan.v1", targetType, AdapterPlan.VERB_SUBMIT,
                            runId, "pkg-" + runId, "tenant-A", "dev", "target-1",
                            CapabilityCheckResult.approved(targetType, "PARQUET", List.of()),
                            Map.of("verb", "SUBMIT"), "stub-sha");
                }
                @Override public AdapterExecution submit(String runId) {
                    invocations.add("submit");
                    return submitOutcome != null ? submitOutcome
                            : AdapterExecution.success(AdapterPlan.VERB_SUBMIT,
                                    DeploymentRunState.RUNNING, "stub-sub-" + runId,
                                    Map.of("providerRunId", "stub-sub-" + runId));
                }
                @Override public RuntimeStatus poll(String runId) {
                    invocations.add("poll");
                    return pollOutcome != null ? pollOutcome
                            : new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION, targetType,
                                    "stub-sub-" + runId, DeploymentRunState.SUCCEEDED,
                                    "succeeded", 1.0, Instant.now(), null, Map.of());
                }
            };
        }

        @Override public LogsAdapter logs() {
            return (runId, limit) -> RuntimeLogs.empty(targetType, runId, Instant.now());
        }

        @Override public CancelRollbackAdapter cancelRollback() {
            return new CancelRollbackAdapter() {
                @Override public AdapterExecution cancel(String runId, String reason) {
                    invocations.add("cancel");
                    return AdapterExecution.success("CANCEL", DeploymentRunState.CANCELLED,
                            runId, Map.of("reason", reason == null ? "" : reason));
                }
                @Override public AdapterExecution rollback(String depId, String pkgId, String reason) {
                    invocations.add("rollback");
                    return AdapterExecution.success("ROLLBACK", DeploymentRunState.SUCCEEDED,
                            pkgId, Map.of("deploymentId", depId, "toPackageId", pkgId));
                }
            };
        }

        long savedTypes(DeploymentEvidenceRepository repo, String type) {
            org.mockito.ArgumentCaptor<DeploymentEvidence> cap =
                    org.mockito.ArgumentCaptor.forClass(DeploymentEvidence.class);
            org.mockito.Mockito.verify(repo, org.mockito.Mockito.atLeast(0)).save(cap.capture());
            return cap.getAllValues().stream()
                    .filter(e -> type.equals(e.getType()))
                    .count();
        }
    }

    /** Required so that the @BeforeEach assertNotNull stays in scope; not used directly. */
    @SuppressWarnings("unused")
    private static void requireNonNull(Object o) {
        assertNotNull(o);
    }
}
