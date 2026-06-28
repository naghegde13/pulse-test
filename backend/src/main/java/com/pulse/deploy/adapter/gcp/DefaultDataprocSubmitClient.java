package com.pulse.deploy.adapter.gcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 7 — production seam for {@link DataprocSubmitClient}.
 * Conditional on {@code pulse.deploy.runtime.gcp.enabled=true}; throws
 * until production wiring lands. See {@link DefaultGcsPackageDeliveryClient}
 * for the rationale.
 */
@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.gcp.enabled", havingValue = "true")
public class DefaultDataprocSubmitClient implements DataprocSubmitClient {

    @Override
    public String submitPySparkJob(SubmitRequest request) {
        throw new UnsupportedOperationException(
                "DefaultDataprocSubmitClient: production Dataproc wiring not yet enabled. "
                        + "Implement against the Dataproc REST API "
                        + "(https://dataproc.googleapis.com/v1) or add com.google.cloud:google-cloud-dataproc. "
                        + "Disable pulse.deploy.runtime.gcp.enabled to fall back to "
                        + "StubDataprocSubmitClient.");
    }

    @Override
    public JobStatus pollJob(String project, String region, String jobId) {
        throw new UnsupportedOperationException(
                "DefaultDataprocSubmitClient: production Dataproc wiring not yet enabled.");
    }
}
