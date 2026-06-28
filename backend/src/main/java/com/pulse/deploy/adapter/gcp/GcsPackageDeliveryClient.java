package com.pulse.deploy.adapter.gcp;

import java.util.List;

/**
 * Phase 7 — narrow GCP Cloud Storage seam used by
 * {@link GcpComposerDataprocAdapter} for package delivery. Tests
 * inject {@link StubGcsPackageDeliveryClient}; production wiring is
 * the {@code @ConditionalOnProperty pulse.deploy.runtime.gcp.enabled}
 * gated {@link DefaultGcsPackageDeliveryClient}.
 */
public interface GcsPackageDeliveryClient {

    /**
     * Upload the materialized package files to {@code gs://<bucket>/<prefix>/...}.
     * Returns the canonical {@code gs://} URI of the uploaded prefix
     * so the adapter can pin it in the plan/evidence.
     */
    String uploadPackagePrefix(UploadRequest request);

    /**
     * @param bucket             target GCS bucket name
     * @param prefix             object key prefix (e.g. {@code packages/<runId>/})
     * @param relativePaths      sorted list of relative paths under the
     *                           materialization output root that should
     *                           be uploaded
     * @param tokenReference     {@code gcp-sm://} reference to the OAuth
     *                           bearer token / service-account key that
     *                           authorizes the upload
     */
    record UploadRequest(
            String bucket,
            String prefix,
            List<String> relativePaths,
            String tokenReference
    ) {}
}
