package com.pulse.deploy.adapter.dpc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.dpc.enabled",
        havingValue = "false", matchIfMissing = true)
public class StubDpcObjectStoreClient implements DpcObjectStoreClient {

    @Override
    public String uploadPackagePrefix(UploadRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (request.bucket() == null || request.bucket().isBlank()) {
            throw new IllegalArgumentException("bucket is required");
        }
        if (request.prefix() == null || request.prefix().isBlank()) {
            throw new IllegalArgumentException("prefix is required");
        }
        // DPC object stores expose either an S3-compat endpoint or a
        // bare bucket URI. The stub returns a deterministic
        // s3://<bucket>/<prefix> form so plan/evidence are byte-stable.
        return "s3://" + request.bucket() + "/" + request.prefix();
    }
}
