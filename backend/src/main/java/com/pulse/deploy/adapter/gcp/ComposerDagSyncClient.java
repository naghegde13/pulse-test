package com.pulse.deploy.adapter.gcp;

import com.pulse.deploy.run.DeploymentRunState;

import java.util.List;
import java.util.Map;

/**
 * Phase 7 — narrow Composer DAG-sync seam used by
 * {@link GcpComposerDataprocAdapter}. Tests inject the stub.
 */
public interface ComposerDagSyncClient {

    /**
     * Sync the given DAG file objects (already uploaded under a GCS
     * prefix) into the Composer environment's {@code dags/} folder.
     * Returns the Composer DAG run id when the adapter immediately
     * triggered a manual run; otherwise returns the synced DAG name.
     */
    SyncResult syncDags(SyncRequest request);

    /**
     * Read Airflow DAG-run state for an explicitly triggered validation run.
     */
    DagRunStatus pollDagRun(String composerEnvironment, String dagId, String dagRunId,
                            String tokenReference);

    default void cancelDagRun(String composerEnvironment, String dagId, String dagRunId,
                              String tokenReference) {
        throw new UnsupportedOperationException("Composer DAG cancellation is not configured");
    }

    /**
     * @param composerEnvironment   {@code projects/<project>/locations/<region>/environments/<env>}
     * @param gcsPackagePrefix      {@code gs://<bucket>/<prefix>}
     * @param dagFilePaths          relative paths of DAG files inside
     *                              the package prefix
     * @param triggerImmediately    when {@code true} and the runtime
     *                              supports it, request a manual DAG
     *                              run; otherwise just sync
     * @param tokenReference        {@code gcp-sm://} reference to the
     *                              bearer token / SA key
     * @param validationConf        optional Airflow DAG-run conf for an
     *                              explicit smoke-validation trigger
     */
    record SyncRequest(
            String composerEnvironment,
            String gcsPackagePrefix,
            List<String> dagFilePaths,
            boolean triggerImmediately,
            String tokenReference,
            Map<String, Object> validationConf
    ) {}

    /**
     * @param syncedDagNames        DAG names installed (one per file)
     * @param triggeredDagRunId     Composer DAG run id when an immediate
     *                              run was triggered; {@code null}
     *                              otherwise
     */
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
}
