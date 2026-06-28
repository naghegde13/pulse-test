package com.pulse.deploy.adapter.dpc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.dpc.enabled", havingValue = "true")
public class DefaultDpcSparkSubmitClient implements DpcSparkSubmitClient {

    @Override
    public String submitPySparkJob(SubmitRequest request) {
        throw new UnsupportedOperationException(
                "DefaultDpcSparkSubmitClient: production DPC Spark wiring not yet enabled. "
                        + "Awaiting platform confirmation of the Spark submit interface. "
                        + "Disable pulse.deploy.runtime.dpc.enabled to fall back to "
                        + "StubDpcSparkSubmitClient.");
    }

    @Override
    public JobStatus pollJob(String dpcSparkEndpoint, String jobId, String tokenReference) {
        throw new UnsupportedOperationException(
                "DefaultDpcSparkSubmitClient: production DPC Spark wiring not yet enabled.");
    }
}
