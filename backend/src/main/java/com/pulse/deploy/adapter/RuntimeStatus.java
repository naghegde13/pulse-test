package com.pulse.deploy.adapter;

import com.pulse.deploy.run.DeploymentRunState;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 7 — adapter-agnostic snapshot of deploy-boundary Airflow
 * activation or explicit validation state. Returned by
 * {@link SubmitPollAdapter#poll(String)}; the
 * {@link com.pulse.deploy.orchestrator.DeploymentRunOrchestrator}
 * maps {@link #effectiveState} onto {@link DeploymentRunState}
 * transitions.
 *
 * <p>Schema: {@code runtime-status.v1} — shared across adapters.
 *
 * @param schemaVersion         {@code runtime-status.v1}
 * @param adapter               canonical target type key
 * @param providerRunId         provider-issued operation id this poll
 *                              describes
 * @param effectiveState        adapter's read of the Airflow sync or
 *                              validation operation's current
 *                              run-machine state. Expected to be one
 *                              of {@code RUNNING}, {@code SUCCEEDED},
 *                              {@code FAILED}, {@code CANCELLED},
 *                              {@code TIMED_OUT}.
 * @param providerStatus        raw provider status string (for example
 *                              {@code "queued"}, {@code "RUNNING"},
 *                              {@code "ERROR"}) — for human display only
 * @param progress              0..1 progress estimate, or {@code null}
 *                              when the runtime cannot supply one
 * @param providerObservedAt    timestamp of the polled snapshot
 * @param failureReason         {@code null} unless terminal-failed
 * @param details               per-adapter detail (task counts,
 *                              executor info, …)
 */
public record RuntimeStatus(
        String schemaVersion,
        String adapter,
        String providerRunId,
        DeploymentRunState effectiveState,
        String providerStatus,
        Double progress,
        Instant providerObservedAt,
        String failureReason,
        Map<String, Object> details
) {
    public static final String SCHEMA_VERSION = "runtime-status.v1";

    public RuntimeStatus {
        details = details == null
                ? Collections.unmodifiableMap(new LinkedHashMap<>())
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public Map<String, Object> toCanonicalJson() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", schemaVersion);
        doc.put("adapter", adapter);
        doc.put("providerRunId", providerRunId);
        doc.put("effectiveState", effectiveState == null ? null : effectiveState.name());
        doc.put("providerStatus", providerStatus);
        doc.put("progress", progress);
        doc.put("providerObservedAt", providerObservedAt == null ? null : providerObservedAt.toString());
        doc.put("failureReason", failureReason);
        doc.put("details", details);
        return doc;
    }
}
