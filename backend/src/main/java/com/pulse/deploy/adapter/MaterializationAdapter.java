package com.pulse.deploy.adapter;

/**
 * Phase 7 — first leg of every {@link DeploymentTargetAdapter}:
 * convert an immutable {@code Package} into the runtime-shaped
 * artifact set the adapter will hand off to its runtime.
 *
 * <p>Local materialization is the canonical implementation and is
 * also reused by GCP/DPC adapters as the source-of-truth file set
 * before they upload to GCS / DPC object storage.
 */
public interface MaterializationAdapter {

    /**
     * Side-effect-free plan describing what {@link #materialize(String)}
     * would do for {@code deploymentRunId}. Must be deterministic for
     * a given (run, package) pair.
     */
    AdapterPlan plan(String deploymentRunId);

    /**
     * Execute the plan: write artifacts, compute manifests, transition
     * the run from {@code PREFLIGHT_PASSED} → {@code MATERIALIZING} →
     * {@code MATERIALIZED}.
     */
    AdapterExecution materialize(String deploymentRunId);
}
