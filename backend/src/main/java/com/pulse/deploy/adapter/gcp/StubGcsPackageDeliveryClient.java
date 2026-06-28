package com.pulse.deploy.adapter.gcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 7 — default in-process stub for {@link GcsPackageDeliveryClient}.
 *
 * <p>Returns the canonical {@code gs://} URI deterministically from
 * the request fields without making any network call. Test flows and
 * non-cloud production deployments use this bean unless
 * {@link DefaultGcsPackageDeliveryClient} is explicitly enabled by
 * {@code pulse.deploy.runtime.gcp.enabled=true}.
 *
 * <p>Conditional on the inverse of the production flag so component
 * scan resolves cleanly: production flips the flag and only
 * {@link DefaultGcsPackageDeliveryClient} is registered; default /
 * unset / explicit false leaves the stub as the only bean.
 */
@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.gcp.enabled",
        havingValue = "false", matchIfMissing = true)
public class StubGcsPackageDeliveryClient implements GcsPackageDeliveryClient {

    @Override
    public String uploadPackagePrefix(UploadRequest request) {
        if (request == null || request.bucket() == null || request.bucket().isBlank()) {
            throw new IllegalArgumentException("bucket is required");
        }
        if (request.prefix() == null || request.prefix().isBlank()) {
            throw new IllegalArgumentException("prefix is required");
        }
        // No filesystem writes — the orchestrator already ran local
        // materialization before calling us; the stub just computes the
        // gs:// URI the adapter will pin in the plan/evidence.
        return "gs://" + request.bucket() + "/" + request.prefix();
    }
}
