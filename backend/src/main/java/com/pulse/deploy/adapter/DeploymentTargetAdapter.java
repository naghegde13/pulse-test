package com.pulse.deploy.adapter;

/**
 * Phase 7 — facade composing the four sub-interfaces a deployment
 * target adapter implements. The
 * {@link com.pulse.deploy.orchestrator.DeploymentRunOrchestrator}
 * dispatches verbs against this facade based on the canonical
 * {@link #targetType()}.
 *
 * <p>One Spring bean per adapter; the orchestrator injects them as
 * a {@code List<DeploymentTargetAdapter>} (rather than a Map) so
 * Spring auto-discovers each implementation by package and the
 * orchestrator builds its own keyed dispatch table from
 * {@link #targetType()}. This avoids forcing every adapter to
 * declare a {@code @Bean(name=…)} just to land in the dispatch map.
 */
public interface DeploymentTargetAdapter {

    /**
     * Canonical target type key this adapter handles. Must match one of
     * the values pinned by the V106 check constraint:
     * {@code LOCAL_MATERIALIZATION},
     * {@code GCP_COMPOSER_DATAPROC},
     * {@code DPC_AIRFLOW_OPENSHIFT_SPARK}.
     */
    String targetType();

    /** Materialization leg. Always present. */
    MaterializationAdapter materialization();

    /**
     * Submit-poll leg. Some adapters (LOCAL_MATERIALIZATION) implement
     * this as a no-op that returns synthetic SUCCEEDED status because
     * local has no real runtime. Never {@code null}.
     */
    SubmitPollAdapter submitPoll();

    /** Logs leg. Never {@code null}; may return empty batches. */
    LogsAdapter logs();

    /** Cancel/rollback leg. Never {@code null}. */
    CancelRollbackAdapter cancelRollback();
}
