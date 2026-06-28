package com.pulse.deploy.adapter;

/**
 * Phase 7 — cancellation + rollback verbs. Both write a
 * {@code CANCEL_RESULT} or {@code ROLLBACK_RESULT} evidence record
 * via the orchestrator regardless of outcome (success / failure /
 * not-supported by runtime).
 */
public interface CancelRollbackAdapter {

    /**
     * Request runtime cancellation for the run currently bound to
     * {@code deploymentRunId}. The orchestrator transitions the run
     * through {@code CANCEL_REQUESTED} → {@code CANCELLED} (or
     * {@code FAILED} if the runtime cannot honor the request).
     *
     * @param deploymentRunId   active run to cancel
     * @param reason            human-readable cause
     */
    AdapterExecution cancel(String deploymentRunId, String reason);

    /**
     * Roll forward to a previous successful package. The orchestrator
     * creates a new {@code Deployment} + {@code DeploymentRun} pointing
     * at {@code toPackageId} and drives the new run through this
     * adapter; the rollback's adapter call is a hint to the runtime
     * (e.g. "I'm replacing the previous DAG with this one") so it
     * can reconcile cleanly. Adapters that have nothing to do here
     * may return a no-op success.
     *
     * @param deploymentId      parent deployment being rolled back
     * @param toPackageId       package the rollback restores to
     * @param reason            human-readable cause
     */
    AdapterExecution rollback(String deploymentId, String toPackageId, String reason);
}
