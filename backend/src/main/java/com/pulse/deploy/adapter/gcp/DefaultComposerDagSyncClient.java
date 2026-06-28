package com.pulse.deploy.adapter.gcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 7 — production seam for {@link ComposerDagSyncClient}.
 * Conditional on {@code pulse.deploy.runtime.gcp.enabled=true}; throws
 * until production wiring lands. See {@link DefaultGcsPackageDeliveryClient}
 * for the rationale.
 */
@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.gcp.enabled", havingValue = "true")
public class DefaultComposerDagSyncClient implements ComposerDagSyncClient {

    @Override
    public SyncResult syncDags(SyncRequest request) {
        throw new UnsupportedOperationException(
                "DefaultComposerDagSyncClient: production Composer wiring not yet enabled. "
                        + "Implement against the Cloud Composer REST API "
                        + "(https://composer.googleapis.com/v1) using ApplicationDefaultCredentials. "
                        + "Disable pulse.deploy.runtime.gcp.enabled to fall back to "
                        + "StubComposerDagSyncClient.");
    }

    @Override
    public DagRunStatus pollDagRun(String composerEnvironment, String dagId, String dagRunId,
                                   String tokenReference) {
        throw new UnsupportedOperationException(
                "DefaultComposerDagSyncClient: production Composer DAG-run polling is not yet enabled.");
    }
}
