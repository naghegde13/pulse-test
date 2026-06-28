package com.pulse.deploy.adapter.dpc;

import com.pulse.deploy.run.DeploymentRunState;

import java.util.List;
import java.util.Map;

/**
 * Phase 7 — narrow DPC Airflow seam (the OpenShift-hosted Airflow
 * REST API). Used by {@link DpcAirflowOpenShiftSparkAdapter} to
 * deliver DAGs and trigger runs.
 */
public interface DpcAirflowClient {

    SyncResult syncDags(SyncRequest request);

    /**
     * Read DAG-run state for {@code dagRunId}. Maps Airflow lifecycle
     * ({@code queued}, {@code running}, {@code success}, {@code failed},
     * {@code cancelled}) onto a {@link DeploymentRunState}.
     */
    DagRunStatus pollDagRun(String dpcAirflowEndpoint, String dagId, String dagRunId,
                            String tokenReference);

    default BrokerDagRun triggerDagRun(String logicalDagId, String dagRunId, Map<String, Object> conf) {
        throw new UnsupportedOperationException("Broker DAG trigger is not configured");
    }

    default void cancelDagRun(String dpcAirflowEndpoint, String dagId, String dagRunId,
                              String tokenReference) {
        throw new UnsupportedOperationException("Broker DAG cancellation is not configured");
    }

    /**
     * @param dpcAirflowEndpoint    DPC Airflow REST endpoint
     * @param dagBucketUri          object-store URI of the package
     *                              prefix the Airflow worker can reach
     * @param dagFilePaths          relative paths of DAG files in the
     *                              uploaded package
     * @param triggerImmediately    when {@code true}, request a manual
     *                              DAG run after sync
     * @param tokenReference        {@code gcp-sm://} reference to the
     *                              bearer token / SA used for Airflow
     * @param validationConf        optional Airflow DAG-run conf for an
     *                              explicit smoke-validation trigger
     */
    record SyncRequest(
            String dpcAirflowEndpoint,
            String dagBucketUri,
            List<String> dagFilePaths,
            boolean triggerImmediately,
            String tokenReference,
            Map<String, Object> validationConf
    ) {}

    record SyncResult(
            List<String> syncedDagNames,
            String triggeredDagRunId
    ) {}

    record DagRunStatus(
            String dagRunId,
            DeploymentRunState effectiveState,
            String providerStatus,
            String failureReason
    ) {}

    record BrokerDagRun(
            String dagRunId,
            String providerStatus
    ) {}
}
