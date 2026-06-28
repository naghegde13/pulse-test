package com.pulse.deploy.adapter.gcp;

import com.pulse.deploy.run.DeploymentRunState;

import java.util.List;

/**
 * Phase 7 — narrow Dataproc Spark-submit / status seam used by
 * {@link GcpComposerDataprocAdapter}. Tests inject the stub.
 */
public interface DataprocSubmitClient {

    /**
     * Submit a PySpark job to a Dataproc cluster (or serverless batch).
     * Returns the Dataproc-issued job id; the adapter records it on
     * the run BEFORE the first {@link #pollJob(String, String, String)}.
     */
    String submitPySparkJob(SubmitRequest request);

    /**
     * Read current Dataproc job state for {@code jobId}. Maps the
     * Dataproc lifecycle ({@code PENDING}, {@code RUNNING}, {@code DONE},
     * {@code ERROR}, {@code CANCELLED}) onto a {@link DeploymentRunState}.
     */
    JobStatus pollJob(String project, String region, String jobId);

    /**
     * @param project           GCP project id
     * @param region            Dataproc region (e.g. {@code us-central1})
     * @param cluster           Dataproc cluster name (or serverless batch
     *                          template id when applicable)
     * @param mainPyFile        {@code gs://} URI of the entry-point
     *                          PySpark script
     * @param pythonFiles       extra {@code gs://} URIs to add to the
     *                          spark-submit {@code --py-files}
     * @param jarFiles          extra {@code gs://} URIs for jars
     * @param args              extra command-line args
     * @param tokenReference    {@code gcp-sm://} reference to the
     *                          bearer token / SA key
     */
    record SubmitRequest(
            String project,
            String region,
            String cluster,
            String mainPyFile,
            List<String> pythonFiles,
            List<String> jarFiles,
            List<String> args,
            String tokenReference
    ) {}

    /**
     * @param jobId             Dataproc-issued job id
     * @param effectiveState    mapped run-machine state
     * @param providerStatus    raw Dataproc state string
     */
    record JobStatus(
            String jobId,
            DeploymentRunState effectiveState,
            String providerStatus
    ) {}
}
