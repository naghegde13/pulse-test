package com.pulse.deploy.adapter.dpc;

import java.util.List;

/**
 * Phase 7 — narrow DPC object-storage seam used by
 * {@link DpcAirflowOpenShiftSparkAdapter}. Tests inject the stub.
 */
public interface DpcObjectStoreClient {

    String uploadPackagePrefix(UploadRequest request);

    /**
     * @param endpoint           DPC object-store endpoint (S3/MinIO-compatible)
     * @param bucket             target bucket / namespace
     * @param prefix             object-key prefix
     * @param relativePaths      sorted list of files to upload
     * @param tokenReference     {@code gcp-sm://} reference to creds
     */
    record UploadRequest(
            String endpoint,
            String bucket,
            String prefix,
            List<String> relativePaths,
            String tokenReference
    ) {}
}
