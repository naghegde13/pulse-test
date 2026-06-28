package com.pulse.deploy.adapter;

/**
 * Phase 7 — second leg of every {@link DeploymentTargetAdapter}:
 * hand the materialized artifacts off to the target Airflow runtime and observe
 * activation or explicit validation state.
 *
 * <p>{@link #submit(String)} must record the provider-issued Airflow
 * sync/activation id on the run BEFORE the first {@link #poll(String)}.
 * Optional smoke validation may record a separate Airflow DAG-run id in
 * explicit metadata. Deploy adapters must not submit Spark/dbt/GX work
 * directly; generated Airflow DAG tasks own runtime execution.
 */
public interface SubmitPollAdapter {

    /** Side-effect-free plan describing what {@link #submit(String)} would do. */
    AdapterPlan plan(String deploymentRunId);

    /**
     * Publish/sync/activate the materialized package in target Airflow.
     * Transitions the run from {@code MATERIALIZED} → {@code SUBMITTING} →
     * {@code RUNNING} and stores the provider-issued deployment id. Optional
     * smoke validation may trigger an Airflow DAG run, but normal deploy does
     * not execute pipeline work.
     */
    AdapterExecution submit(String deploymentRunId);

    /**
     * Read the current runtime state. Read-only; the orchestrator (not
     * the adapter) drives any resulting state transition. Returning
     * {@code null} is illegal — adapters that cannot reach the runtime
     * must return a {@link RuntimeStatus} with the appropriate
     * {@code effectiveState} and a populated {@code failureReason}.
     */
    RuntimeStatus poll(String deploymentRunId);
}
