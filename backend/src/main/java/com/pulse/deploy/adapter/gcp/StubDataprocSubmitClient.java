package com.pulse.deploy.adapter.gcp;

import com.pulse.deploy.run.DeploymentRunState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 7 — default in-process stub for {@link DataprocSubmitClient}.
 * Returns deterministic synthetic job IDs and treats poll() as
 * always-succeeded so the orchestrator can drive a stub-backed run
 * to {@code SUCCEEDED} without external infrastructure.
 */
@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.gcp.enabled",
        havingValue = "false", matchIfMissing = true)
public class StubDataprocSubmitClient implements DataprocSubmitClient {

    @Override
    public String submitPySparkJob(SubmitRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (request.project() == null || request.project().isBlank()) {
            throw new IllegalArgumentException("project is required");
        }
        if (request.mainPyFile() == null || request.mainPyFile().isBlank()) {
            throw new IllegalArgumentException("mainPyFile is required");
        }
        // Job id is derived from request fields so two identical
        // submissions produce the same id (idempotent in stub mode).
        String fingerprint = Integer.toHexString(
                (request.project() + "|" + request.region() + "|" + request.mainPyFile()).hashCode());
        return "stub-dataproc-job-" + fingerprint;
    }

    @Override
    public JobStatus pollJob(String project, String region, String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }
        return new JobStatus(jobId, DeploymentRunState.SUCCEEDED, "DONE");
    }
}
