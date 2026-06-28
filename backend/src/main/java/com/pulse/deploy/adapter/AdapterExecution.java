package com.pulse.deploy.adapter;

import com.pulse.deploy.run.DeploymentRunState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 7 — outcome of a side-effecting adapter operation
 * (materialize / submit / cancel / rollback). Adapters return this
 * envelope so the {@link com.pulse.deploy.orchestrator.DeploymentRunOrchestrator}
 * can drive the run-state machine without reaching into adapter
 * internals.
 *
 * @param verb                  the verb that was executed
 * @param succeeded             {@code true} when the verb finished
 *                              cleanly. The orchestrator maps to
 *                              {@link DeploymentRunState#FAILED} on
 *                              {@code false}.
 * @param resultingState        run state the adapter believes it
 *                              moved the run to (the orchestrator
 *                              still validates the transition before
 *                              persisting)
 * @param providerRunId         provider-issued deployment-operation id
 *                              (for example an Airflow sync/activation
 *                              id). Optional validation DAG-run ids
 *                              belong in {@link #details()} under
 *                              explicit keys such as
 *                              {@code validationDagRunId}.
 * @param failureReason         non-{@code null} when {@link #succeeded}
 *                              is {@code false}
 * @param details               per-adapter detail (artifact paths,
 *                              GCS uri, …) — never includes secret
 *                              VALUES, only references
 */
public record AdapterExecution(
        String verb,
        boolean succeeded,
        DeploymentRunState resultingState,
        String providerRunId,
        String failureReason,
        Map<String, Object> details
) {
    public AdapterExecution {
        details = details == null
                ? Collections.unmodifiableMap(new LinkedHashMap<>())
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static AdapterExecution success(String verb,
                                           DeploymentRunState resultingState,
                                           String providerRunId,
                                           Map<String, Object> details) {
        return new AdapterExecution(verb, true, resultingState, providerRunId, null, details);
    }

    public static AdapterExecution failure(String verb,
                                           String failureReason,
                                           Map<String, Object> details) {
        return new AdapterExecution(verb, false, DeploymentRunState.FAILED, null, failureReason, details);
    }
}
