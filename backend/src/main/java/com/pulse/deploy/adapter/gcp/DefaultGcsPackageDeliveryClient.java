package com.pulse.deploy.adapter.gcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 7 — production seam for {@link GcsPackageDeliveryClient}.
 *
 * <p>Conditional on {@code pulse.deploy.runtime.gcp.enabled=true}.
 * Currently throws on every call to keep CI ungated and the seam
 * obvious; wiring the real {@code com.google.cloud:google-cloud-storage}
 * SDK is the platform team's follow-up after Phase 7's structural
 * landing. The shape and gating mirror Phase 6's
 * {@code DefaultGitHubApiClient} pattern so the production wiring
 * lives in exactly one place.
 *
 * <p>The gated {@code com.pulse.e2e.GcpDeploymentRuntimeIT} test
 * skips when {@code GOOGLE_APPLICATION_CREDENTIALS} is unset, so
 * normal CI never trips this implementation.
 */
@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.gcp.enabled", havingValue = "true")
public class DefaultGcsPackageDeliveryClient implements GcsPackageDeliveryClient {

    @Override
    public String uploadPackagePrefix(UploadRequest request) {
        throw new UnsupportedOperationException(
                "DefaultGcsPackageDeliveryClient: production GCS wiring not yet enabled. "
                        + "Add com.google.cloud:google-cloud-storage and configure "
                        + "ApplicationDefaultCredentials to enable. Disable "
                        + "pulse.deploy.runtime.gcp.enabled to fall back to "
                        + "StubGcsPackageDeliveryClient.");
    }
}
