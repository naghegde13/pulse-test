package com.pulse.deploy.adapter.dpc;

import com.pulse.deploy.run.DeploymentRunState;

import java.util.List;

/**
 * Phase 7 — narrow Spark-submit seam for DPC's approved Spark
 * interface (running on OpenShift). Used by
 * {@link DpcAirflowOpenShiftSparkAdapter}.
 */
public interface DpcSparkSubmitClient {

    String submitPySparkJob(SubmitRequest request);

    JobStatus pollJob(String dpcSparkEndpoint, String jobId, String tokenReference);

    /**
     * @param dpcSparkEndpoint     DPC Spark submission endpoint
     * @param sparkApp             Spark application name (or template)
     * @param mainPyFile           object-store URI of the entry-point script
     * @param pythonFiles          extra py-files
     * @param jarFiles             extra jars
     * @param args                 command-line args
     * @param tokenReference       {@code gcp-sm://} reference to creds
     */
    record SubmitRequest(
            String dpcSparkEndpoint,
            String sparkApp,
            String mainPyFile,
            List<String> pythonFiles,
            List<String> jarFiles,
            List<String> args,
            String tokenReference
    ) {}

    record JobStatus(
            String jobId,
            DeploymentRunState effectiveState,
            String providerStatus
    ) {}
}
