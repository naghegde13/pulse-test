package com.pulse.storage.service;

import com.google.auth.Credentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.pulse.auth.service.TenantGcpCredentialResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * PKT-FINAL-5: Per-tenant {@link Storage} client factory used by
 * {@link StorageScaffoldService#executeInternal} for scaffold folder marker
 * creation. Resolves the tenant's GCP service-account JSON via
 * {@link TenantGcpCredentialResolver} and constructs a {@link Storage}
 * instance scoped to the tenant's GCP project.
 *
 * <p>Isolated as a separate component so tests can mock the {@link Storage}
 * instance directly and so future credential modes (impersonation, ADC)
 * can be added without touching the scaffold execute path.
 */
@Component
public class GcsStorageClientFactory {

    private final TenantGcpCredentialResolver credentialResolver;

    public GcsStorageClientFactory(TenantGcpCredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    /**
     * Construct a {@link Storage} client for the given tenant using the
     * tenant-configured GCP credentials. Fails closed: if the tenant has
     * no credential configured, the underlying resolver throws.
     *
     * @param tenantId    the tenant whose credential should be used
     * @param gcpProjectId the GCP project to scope the client to
     * @return a {@link Storage} client authenticated as the tenant SA
     * @throws IOException if the credential JSON cannot be parsed
     */
    public Storage build(String tenantId, String gcpProjectId) throws IOException {
        // PKT-FINAL-7: route through resolveCredentials so IMPERSONATION mode
        // works (resolveCredentialJson hard-fails for impersonated tenants).
        Credentials credentials = credentialResolver.resolveCredentials(tenantId);
        return StorageOptions.newBuilder()
                .setProjectId(gcpProjectId)
                .setCredentials(credentials)
                .build()
                .getService();
    }
}
