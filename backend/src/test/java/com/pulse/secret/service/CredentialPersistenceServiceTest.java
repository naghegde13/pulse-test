package com.pulse.secret.service;

import com.pulse.auth.service.TenantService;
import com.pulse.config.GcpEnvironmentConfig;
import com.pulse.config.TenantConfig.TenantDefinition;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.CredentialStatus;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CredentialPersistenceServiceTest {

    @Mock private CredentialProfileRepository credRepo;
    @Mock private ConnectorInstanceRepository ciRepo;
    @Mock private SystemOfRecordRepository sorRepo;
    @Mock private DomainRepository domainRepo;
    @Mock private TenantService tenantService;
    @Mock private GcpSecretManagerService gcpSecretManagerService;
    @Mock private GcpEnvironmentConfig gcpEnvironmentConfig;

    // Real SecretReferenceService — its behavior is part of the contract we verify.
    private final SecretReferenceService secretReferenceService = new SecretReferenceService();

    private CredentialPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new CredentialPersistenceService(
                credRepo, ciRepo, sorRepo, domainRepo, tenantService,
                gcpSecretManagerService, gcpEnvironmentConfig, secretReferenceService);
    }

    // -----------------------------------------------------------------------
    //  sanitize
    // -----------------------------------------------------------------------

    @Test
    void sanitize_splitsMetadataFromSecretRefsAndMarksSecretFields() {
        CredentialProfile cred = new CredentialProfile();
        cred.setId("cred-1");
        cred.setConnectorInstanceId("ci-1");
        cred.setEnvironment("dev");
        cred.setStatus(CredentialStatus.UNTESTED);
        cred.setConnectionConfig(Map.of(
                "host", "db.example.com",
                "password", "plain-secret",
                "api_key", "gcp-sm://projects/pulse-dev/secrets/external-api-key/versions/latest"
        ));

        CredentialProfile sanitized = service.sanitize(cred);

        // metadataConfig carries only non-secret fields.
        assertEquals(Map.of("host", "db.example.com"), sanitized.getMetadataConfig());
        // secretRefs surfaces the gcp-sm:// references (no plaintext).
        assertEquals(
                Map.of("api_key", "gcp-sm://projects/pulse-dev/secrets/external-api-key/versions/latest"),
                sanitized.getSecretRefs()
        );
        // secretMetadata flags every secret field, distinguishing plaintext-submitted from referenced.
        assertFalse(sanitized.getSecretMetadata().get("password").secretReference());
        assertTrue(sanitized.getSecretMetadata().get("api_key").secretReference());
        // The plain-secret plaintext does NOT surface through the sanitized view.
        assertFalse(sanitized.getMetadataConfig().containsKey("password"));
    }

    // -----------------------------------------------------------------------
    //  persistCredential — new credential row
    // -----------------------------------------------------------------------

    @Test
    void persistCredential_createsNewCredentialForEnvironment() {
        stubSourceInstance("ci-1", "sor-1", "tenant-1", "dom-1");
        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "dev"))
                .thenReturn(Optional.empty());
        when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> {
            CredentialProfile saved = inv.getArgument(0);
            saved.setId("cred-new");
            return saved;
        });
        when(gcpEnvironmentConfig.resolveProjectId("dev")).thenReturn("pulse-dev");

        Map<String, Object> body = Map.of(
                "metadata", Map.of("host", "db.example.com", "port", "5432")
        );

        CredentialProfile response = service.persistCredential("ci-1", "dev", body);

        assertNotNull(response);
        assertEquals("ci-1", response.getConnectorInstanceId());
        assertEquals("dev", response.getEnvironment());
        assertEquals(Map.of("host", "db.example.com", "port", "5432"),
                response.getMetadataConfig());
        assertEquals(CredentialStatus.UNTESTED, response.getStatus());
    }

    // -----------------------------------------------------------------------
    //  persistCredential — updates existing row and resets status
    // -----------------------------------------------------------------------

    @Test
    void persistCredential_updatesExistingCredentialAndResetsStatus() {
        stubSourceInstance("ci-1", "sor-1", "tenant-1", "dom-1");
        CredentialProfile existing = new CredentialProfile();
        existing.setId("cred-1");
        existing.setConnectorInstanceId("ci-1");
        existing.setEnvironment("dev");
        existing.setConnectionConfig(Map.of("host", "old-host.com"));
        existing.setStatus(CredentialStatus.VALID);
        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "dev"))
                .thenReturn(Optional.of(existing));
        when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gcpEnvironmentConfig.resolveProjectId("dev")).thenReturn("pulse-dev");

        Map<String, Object> body = Map.of(
                "metadata", Map.of("host", "new-host.com", "port", "5432")
        );

        CredentialProfile response = service.persistCredential("ci-1", "dev", body);

        assertEquals("new-host.com", response.getMetadataConfig().get("host"));
        assertEquals("5432", response.getMetadataConfig().get("port"));
        assertEquals(CredentialStatus.UNTESTED, response.getStatus());
    }

    // -----------------------------------------------------------------------
    //  persistCredential — blank secret submissions preserve the stored ref
    // -----------------------------------------------------------------------

    @Test
    void persistCredential_preservesExistingSecretWhenBlankIsSubmitted() {
        stubSourceInstance("ci-1", "sor-1", "tenant-1", "dom-1");
        CredentialProfile existing = new CredentialProfile();
        existing.setId("cred-1");
        existing.setConnectorInstanceId("ci-1");
        existing.setEnvironment("dev");
        existing.setConnectionConfig(new HashMap<>(Map.of(
                "host", "old-host.com",
                "password", "gcp-sm://projects/pulse-dev/secrets/postgres-password/versions/latest"
        )));
        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "dev"))
                .thenReturn(Optional.of(existing));
        when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gcpEnvironmentConfig.resolveProjectId("dev")).thenReturn("pulse-dev");

        Map<String, Object> body = Map.of(
                "metadata", Map.of("host", "new-host.com"),
                "secretValues", Map.of("password", "")
        );

        CredentialProfile response = service.persistCredential("ci-1", "dev", body);

        assertEquals("new-host.com", response.getMetadataConfig().get("host"));
        assertEquals(
                "gcp-sm://projects/pulse-dev/secrets/postgres-password/versions/latest",
                response.getSecretRefs().get("password")
        );
        assertTrue(response.getSecretMetadata().get("password").secretReference());
        // Blank secret MUST NOT trigger a Secret Manager write.
        verify(gcpSecretManagerService, never()).createOrUpdateSecret(any(), any(), any(), anyMap());
    }

    // -----------------------------------------------------------------------
    //  persistCredential — incoming secret references bypass Secret Manager
    // -----------------------------------------------------------------------

    @Test
    void persistCredential_acceptsIncomingSecretReferenceWithoutCallingSecretManager() {
        stubSourceInstance("ci-1", "sor-1", "tenant-1", "dom-1");
        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "dev"))
                .thenReturn(Optional.empty());
        when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gcpEnvironmentConfig.resolveProjectId("dev")).thenReturn("pulse-dev");

        Map<String, Object> body = Map.of(
                "metadata", Map.of("host", "new-host.com"),
                "secretRefs", Map.of(
                        "api_key", "gcp-sm://projects/pulse-dev/secrets/partner-api-key/versions/latest"
                )
        );

        CredentialProfile response = service.persistCredential("ci-1", "dev", body);

        assertEquals("new-host.com", response.getMetadataConfig().get("host"));
        assertEquals(
                "gcp-sm://projects/pulse-dev/secrets/partner-api-key/versions/latest",
                response.getSecretRefs().get("api_key")
        );
        assertTrue(response.getSecretMetadata().get("api_key").secretReference());
        verify(gcpSecretManagerService, never()).createOrUpdateSecret(any(), any(), any(), anyMap());
    }

    // -----------------------------------------------------------------------
    //  persistCredential — plaintext secretValues go through Secret Manager
    // -----------------------------------------------------------------------

    @Test
    void persistCredential_swapsPlaintextSecretValuesForGcpSmReferences() {
        stubSourceInstance("ci-1", "sor-1", "tenant-1", "dom-1");
        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "dev"))
                .thenReturn(Optional.empty());
        when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gcpEnvironmentConfig.resolveProjectId("dev")).thenReturn("pulse-dev");

        String expectedSecretId = "pulse-dev-home-lending-sales-source-los-oracle-password-ci-1";
        String expectedRef = "gcp-sm://projects/pulse-dev/secrets/" + expectedSecretId + "/versions/latest";
        when(gcpSecretManagerService.buildSecretId(any(SecretNamingContext.class)))
                .thenReturn(expectedSecretId);
        when(gcpSecretManagerService.createOrUpdateSecret(
                eq("dev"), eq(expectedSecretId), eq("plaintext-secret"), anyMap()))
                .thenReturn(expectedRef);

        Map<String, Object> body = Map.of(
                "metadata", Map.of("host", "db.example.com", "port", 5432),
                "secretValues", Map.of("password", "plaintext-secret")
        );

        CredentialProfile response = service.persistCredential("ci-1", "dev", body);

        // Plaintext never surfaces anywhere.
        assertFalse(response.getConnectionMetadata().containsKey("password"));
        assertEquals(expectedRef, response.getSecretRefs().get("password"));
        // Non-secret fields remain in metadata.
        assertEquals("db.example.com", response.getMetadataConfig().get("host"));
        assertEquals(5432, response.getMetadataConfig().get("port"));

        // Verify the saved entity holds ONLY the gcp-sm:// reference for the secret.
        ArgumentCaptor<CredentialProfile> captor = ArgumentCaptor.forClass(CredentialProfile.class);
        verify(credRepo).save(captor.capture());
        CredentialProfile saved = captor.getValue();
        assertEquals(expectedRef, saved.getSecretReferences().get("password"));
        assertFalse(saved.getConnectionMetadata().containsKey("password"));
        // Audit column populated.
        assertEquals("pulse-dev", saved.getSecretProjectId());
    }

    @Test
    void persistCredential_acceptsStructuredCredentialPayload() {
        stubSourceInstance("ci-1", "sor-1", "tenant-1", "dom-1");
        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "dev"))
                .thenReturn(Optional.empty());
        when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gcpEnvironmentConfig.resolveProjectId("dev")).thenReturn("pulse-dev");

        String expectedSecretId = "pulse-dev-home-lending-sales-source-los-oracle-password-ci-1";
        String expectedRef = "gcp-sm://projects/pulse-dev/secrets/" + expectedSecretId + "/versions/latest";
        when(gcpSecretManagerService.buildSecretId(any(SecretNamingContext.class)))
                .thenReturn(expectedSecretId);
        when(gcpSecretManagerService.createOrUpdateSecret(
                eq("dev"), eq(expectedSecretId), eq("plaintext-secret"), anyMap()))
                .thenReturn(expectedRef);

        Map<String, Object> payload = new HashMap<>();
        payload.put("metadata", Map.of("host", "db.example.com", "port", 5432));
        payload.put("secretValues", Map.of("password", "plaintext-secret"));
        payload.put("secretRefs", Map.of(
                "api_key", "gcp-sm://projects/pulse-dev/secrets/partner-api-key/versions/latest"
        ));

        CredentialProfile response = service.persistCredential("ci-1", "dev", payload);

        // Non-secret fields surface through metadataConfig.
        assertEquals(Map.of("host", "db.example.com", "port", 5432), response.getMetadataConfig());
        // Plaintext is swapped for the Secret Manager reference; no plaintext surfaces.
        assertFalse(response.getMetadataConfig().containsKey("password"));
        assertEquals(expectedRef, response.getSecretRefs().get("password"));
        // An incoming secretRefs entry passes through unchanged.
        assertEquals(
                "gcp-sm://projects/pulse-dev/secrets/partner-api-key/versions/latest",
                response.getSecretRefs().get("api_key")
        );
        // Both secret fields are marked as secret references in the sanitized metadata.
        assertTrue(response.getSecretMetadata().get("password").secretReference());
        assertTrue(response.getSecretMetadata().get("api_key").secretReference());

        ArgumentCaptor<CredentialProfile> captor = ArgumentCaptor.forClass(CredentialProfile.class);
        verify(credRepo).save(captor.capture());
        CredentialProfile saved = captor.getValue();
        assertEquals(expectedRef, saved.getSecretReferences().get("password"));
        assertEquals(
                "gcp-sm://projects/pulse-dev/secrets/partner-api-key/versions/latest",
                saved.getSecretReferences().get("api_key")
        );
        assertEquals(5432, saved.getConnectionMetadata().get("port"));
    }

    // -----------------------------------------------------------------------
    //  persistCredential — target SORs produce kind=target naming contexts
    // -----------------------------------------------------------------------

    @Test
    void persistCredential_usesTargetKindForSinkSors() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId("ci-1");
        ci.setSorId("sor-target");
        ci.setName("Snowflake Analytics");
        when(ciRepo.findById("ci-1")).thenReturn(Optional.of(ci));

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-target");
        sor.setTenantId("tenant-1");
        sor.setDomainId("dom-1");
        sor.setMetadata(Map.of("registry_type", "TARGET"));
        when(sorRepo.findById("sor-target")).thenReturn(Optional.of(sor));

        TenantDefinition tenant = new TenantDefinition();
        tenant.setId("tenant-1");
        tenant.setSlug("home-lending");
        when(tenantService.getTenant("tenant-1")).thenReturn(tenant);

        Domain domain = new Domain();
        domain.setId("dom-1");
        domain.setSlug("sales");
        when(domainRepo.findById("dom-1")).thenReturn(Optional.of(domain));

        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "dev"))
                .thenReturn(Optional.empty());
        when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gcpEnvironmentConfig.resolveProjectId("dev")).thenReturn("pulse-dev");
        when(gcpSecretManagerService.buildSecretId(any(SecretNamingContext.class)))
                .thenReturn("generated-secret-id");
        when(gcpSecretManagerService.createOrUpdateSecret(
                eq("dev"), eq("generated-secret-id"), eq("plaintext"), anyMap()))
                .thenReturn("gcp-sm://projects/pulse-dev/secrets/generated-secret-id/versions/latest");

        Map<String, Object> body = Map.of(
                "secretValues", Map.of("password", "plaintext")
        );

        service.persistCredential("ci-1", "dev", body);

        ArgumentCaptor<SecretNamingContext> captor = ArgumentCaptor.forClass(SecretNamingContext.class);
        verify(gcpSecretManagerService).buildSecretId(captor.capture());
        assertEquals("target", captor.getValue().resourceKind());
        assertEquals("sales", captor.getValue().domainSlug());
        assertEquals("home-lending", captor.getValue().tenantSlug());
    }

    // -----------------------------------------------------------------------
    //  skipCredential — persists SKIPPED with empty canonical config
    // -----------------------------------------------------------------------

    @Test
    void skipCredential_persistsSkippedStatusWithEmptyCanonicalConfig() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId("ci-1");
        when(ciRepo.findById("ci-1")).thenReturn(Optional.of(ci));
        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "dev"))
                .thenReturn(Optional.empty());
        when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        CredentialProfile response = service.skipCredential("ci-1", "dev");

        assertEquals(CredentialStatus.SKIPPED, response.getStatus());
        assertNull(response.getSecretProjectId());

        ArgumentCaptor<CredentialProfile> captor = ArgumentCaptor.forClass(CredentialProfile.class);
        verify(credRepo).save(captor.capture());
        CredentialProfile saved = captor.getValue();
        assertEquals(CredentialStatus.SKIPPED, saved.getStatus());
        assertTrue(saved.getConnectionMetadata().isEmpty());
        assertTrue(saved.getSecretReferences().isEmpty());
        assertNull(saved.getSecretProjectId());
        verify(gcpSecretManagerService, never()).createOrUpdateSecret(any(), any(), any(), anyMap());
    }

    // -----------------------------------------------------------------------
    //  Phase 1 contract: service-level env normalization
    // -----------------------------------------------------------------------

    @Test
    void persistCredential_normalizesLegacyEnvAtServiceEntry() {
        // Direct service caller (e.g. ChatToolExecutor) hands in legacy
        // uppercase 'PRODUCTION'. The service must canonicalize to 'prod'
        // before doing the credential lookup, persisting the row, calling
        // GcpEnvironmentConfig.resolveProjectId, or asking GSM for a
        // secretId. Otherwise direct callers can still strand
        // credential_profiles.environment at legacy uppercase values.
        stubSourceInstance("ci-1", "sor-1", "tenant-1", "dom-1");
        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "prod"))
                .thenReturn(Optional.empty());
        when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gcpEnvironmentConfig.resolveProjectId("prod")).thenReturn("pulse-prod");

        CredentialProfile response = service.persistCredential("ci-1", "PRODUCTION",
                Map.of("metadata", Map.of("host", "prod-db.example.com")));

        assertEquals("prod", response.getEnvironment());
        verify(credRepo).findByConnectorInstanceIdAndEnvironment("ci-1", "prod");
        verify(gcpEnvironmentConfig).resolveProjectId("prod");
    }

    @Test
    void skipCredential_normalizesLegacyEnvAtServiceEntry() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId("ci-1");
        when(ciRepo.findById("ci-1")).thenReturn(Optional.of(ci));
        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "integration"))
                .thenReturn(Optional.empty());
        when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        CredentialProfile response = service.skipCredential("ci-1", "INT");

        assertEquals("integration", response.getEnvironment());
        verify(credRepo).findByConnectorInstanceIdAndEnvironment("ci-1", "integration");
    }

    @Test
    void skipCredential_overwritesExistingCredential() {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId("ci-1");
        when(ciRepo.findById("ci-1")).thenReturn(Optional.of(ci));

        CredentialProfile existing = new CredentialProfile();
        existing.setConnectorInstanceId("ci-1");
        existing.setEnvironment("dev");
        existing.setConnectionConfig(Map.of(
                "host", "old-host.com",
                "password", "gcp-sm://projects/pulse-dev/secrets/old-password/versions/latest"
        ));
        existing.setStatus(CredentialStatus.VALID);
        existing.setSecretProjectId("pulse-dev");
        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "dev"))
                .thenReturn(Optional.of(existing));
        when(credRepo.save(any(CredentialProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        CredentialProfile response = service.skipCredential("ci-1", "dev");

        assertEquals(CredentialStatus.SKIPPED, response.getStatus());
        assertTrue(response.getConnectionMetadata().isEmpty());
        assertTrue(response.getSecretReferences().isEmpty());
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void stubSourceInstance(String ciId, String sorId, String tenantId, String domainId) {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId(ciId);
        ci.setSorId(sorId);
        ci.setName("LOS Oracle");
        when(ciRepo.findById(ciId)).thenReturn(Optional.of(ci));

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId(sorId);
        sor.setTenantId(tenantId);
        sor.setDomainId(domainId);
        sor.setMetadata(Map.of());
        when(sorRepo.findById(sorId)).thenReturn(Optional.of(sor));

        TenantDefinition tenant = new TenantDefinition();
        tenant.setId(tenantId);
        tenant.setSlug("home-lending");
        when(tenantService.getTenant(tenantId)).thenReturn(tenant);

        Domain domain = new Domain();
        domain.setId(domainId);
        domain.setSlug("sales");
        when(domainRepo.findById(domainId)).thenReturn(Optional.of(domain));
    }
}
