package com.pulse.deploy.adapter.dpc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.dpc.enabled", havingValue = "true")
public class DefaultDpcObjectStoreClient implements DpcObjectStoreClient {

    @Override
    public String uploadPackagePrefix(UploadRequest request) {
        throw new UnsupportedOperationException(
                "DefaultDpcObjectStoreClient: production DPC object-store wiring not yet enabled. "
                        + "Implement against the platform-confirmed S3 (or compatible) endpoint. "
                        + "Disable pulse.deploy.runtime.dpc.enabled to fall back to "
                        + "StubDpcObjectStoreClient.");
    }
}
