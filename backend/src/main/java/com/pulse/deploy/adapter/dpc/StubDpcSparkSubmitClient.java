package com.pulse.deploy.adapter.dpc;

import com.pulse.deploy.run.DeploymentRunState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.dpc.enabled",
        havingValue = "false", matchIfMissing = true)
public class StubDpcSparkSubmitClient implements DpcSparkSubmitClient {

    @Override
    public String submitPySparkJob(SubmitRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (request.mainPyFile() == null || request.mainPyFile().isBlank()) {
            throw new IllegalArgumentException("mainPyFile is required");
        }
        String fingerprint = Integer.toHexString(
                (request.dpcSparkEndpoint() + "|" + request.sparkApp() + "|" + request.mainPyFile()).hashCode());
        return "stub-dpc-spark-job-" + fingerprint;
    }

    @Override
    public JobStatus pollJob(String dpcSparkEndpoint, String jobId, String tokenReference) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }
        return new JobStatus(jobId, DeploymentRunState.SUCCEEDED, "DONE");
    }
}
