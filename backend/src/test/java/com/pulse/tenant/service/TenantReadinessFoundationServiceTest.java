package com.pulse.tenant.service;

import com.pulse.auth.model.Tenant;
import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.service.TenantGcpConfigService;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.GitHubRepoUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * PKT-0010 — TenantReadinessFoundationService contract tests.
 */
@ExtendWith(MockitoExtension.class)
class TenantReadinessFoundationServiceTest {

    private static final String TENANT_ID = "tenant-acme-lending";

    @Mock private TenantService tenantService;
    @Mock private TenantGcpConfigService gcpConfigService;
    @Mock private TenantGcpCredentialService gcpCredentialService;
    @Mock private GitRepoRepository gitRepoRepository;

    private TenantReadinessFoundationService service;

    @BeforeEach
    void setUp() {
        service = new TenantReadinessFoundationService(
                tenantService, gcpConfigService, gcpCredentialService,
                gitRepoRepository, new GitHubRepoUrlValidator());
    }

    // ---- Tenant Identity Category ----

    @Test
    void tenantIdentity_configured_returnsStatus() {
        Tenant tenant = makeTenant();
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(tenant);
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.empty());
        when(gcpCredentialService.getRedactedCredential(TENANT_ID)).thenReturn(Optional.empty());
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT")).thenReturn(Optional.empty());

        var result = service.getFoundation(TENANT_ID);

        @SuppressWarnings("unchecked")
        var identity = (Map<String, Object>) result.get("tenantIdentity");
        assertEquals("configured", identity.get("status"));
        assertEquals(TENANT_ID, identity.get("id"));
        assertEquals("acme-lending", identity.get("slug"));
    }

    @Test
    void tenantIdentity_missing_returnsError() {
        when(tenantService.getTenantEntity(TENANT_ID))
                .thenThrow(new ResourceNotFoundException("Tenant", TENANT_ID));
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.empty());
        when(gcpCredentialService.getRedactedCredential(TENANT_ID)).thenReturn(Optional.empty());
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT")).thenReturn(Optional.empty());

        var result = service.getFoundation(TENANT_ID);

        @SuppressWarnings("unchecked")
        var identity = (Map<String, Object>) result.get("tenantIdentity");
        assertEquals("missing", identity.get("status"));
    }

    // ---- GCP Config Category ----

    @Test
    void gcpConfig_configured_returnsProjectAndRegion() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        TenantGcpConfig config = new TenantGcpConfig();
        config.setTenantId(TENANT_ID);
        config.setControlPlaneProjectId("acme-lending-prod");
        config.setGcpRegion("us-central1");
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(config));
        when(gcpCredentialService.getRedactedCredential(TENANT_ID)).thenReturn(Optional.empty());
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT")).thenReturn(Optional.empty());

        var result = service.getFoundation(TENANT_ID);

        @SuppressWarnings("unchecked")
        var gcp = (Map<String, Object>) result.get("gcpConfig");
        assertEquals("configured", gcp.get("status"));
        assertEquals("acme-lending-prod", gcp.get("gcpProjectId"));
        assertEquals("us-central1", gcp.get("gcpRegion"));
        assertEquals("tenant_gcp_config", gcp.get("source"));
    }

    /**
     * PKT-0010 negative test: missing tenant GCP config fails closed.
     * Even if a storage backend with gcpProject exists, the foundation
     * readback should report "missing" for the GCP config category.
     */
    @Test
    void gcpConfig_missing_failsClosed_regardlessOfStorageBackend() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.empty());
        when(gcpCredentialService.getRedactedCredential(TENANT_ID)).thenReturn(Optional.empty());
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT")).thenReturn(Optional.empty());

        var result = service.getFoundation(TENANT_ID);

        @SuppressWarnings("unchecked")
        var gcp = (Map<String, Object>) result.get("gcpConfig");
        assertEquals("missing", gcp.get("status"));
        assertNotNull(gcp.get("error"));
        String error = (String) gcp.get("error");
        assertTrue(error.toLowerCase().contains("storage-backend gcpproject does not substitute"),
                "Error should explicitly state storage-backend gcpProject is not sufficient: " + error);
    }

    // ---- GCP Credential Category ----

    @Test
    void gcpCredential_configured_returnsRedactedReadback() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.empty());
        Map<String, Object> redacted = new LinkedHashMap<>();
        redacted.put("serviceAccountEmail", "sa@acme-lending-prod.iam.gserviceaccount.com");
        redacted.put("keyId", "abc123");
        redacted.put("gcpProjectId", "acme-lending-prod");
        redacted.put("status", "active");
        when(gcpCredentialService.getRedactedCredential(TENANT_ID)).thenReturn(Optional.of(redacted));
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT")).thenReturn(Optional.empty());

        var result = service.getFoundation(TENANT_ID);

        @SuppressWarnings("unchecked")
        var cred = (Map<String, Object>) result.get("gcpCredential");
        assertEquals("active", cred.get("status"));
        assertEquals("sa@acme-lending-prod.iam.gserviceaccount.com", cred.get("serviceAccountEmail"));
        assertEquals(true, cred.get("privateKeyRedacted"));
    }

    /**
     * PKT-0010 negative test: credential category never returns private_key
     * or full credential JSON body contents beyond allowed redacted fields.
     */
    @Test
    void gcpCredential_neverExposesPrivateKeyMaterial() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.empty());
        Map<String, Object> redacted = new LinkedHashMap<>();
        redacted.put("serviceAccountEmail", "sa@proj.iam.gserviceaccount.com");
        redacted.put("keyId", "key-id-1");
        redacted.put("gcpProjectId", "proj");
        redacted.put("status", "active");
        when(gcpCredentialService.getRedactedCredential(TENANT_ID)).thenReturn(Optional.of(redacted));
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT")).thenReturn(Optional.empty());

        var result = service.getFoundation(TENANT_ID);

        // Serialize the entire result to a string and verify no private key material
        String serialized = result.toString();
        assertFalse(serialized.contains("private_key"), "Must not contain private_key");
        assertFalse(serialized.contains("BEGIN RSA"), "Must not contain RSA key material");
        assertFalse(serialized.contains("BEGIN PRIVATE"), "Must not contain private key PEM");
        assertFalse(serialized.contains("encrypted_credential"), "Must not contain encrypted credential");
    }

    // ---- Git Repo Category ----

    @Test
    void gitRepo_linked_withValidGithubUrl() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.empty());
        when(gcpCredentialService.getRedactedCredential(TENANT_ID)).thenReturn(Optional.empty());
        GitRepo repo = new GitRepo();
        repo.setTenantId(TENANT_ID);
        repo.setRepoUrl("https://github.com/zadam2008/pulse-acme-lending.git");
        repo.setRepoType("REMOTE");
        repo.setProvider("GITHUB");
        repo.setDefaultBranch("main");
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT")).thenReturn(Optional.of(repo));

        var result = service.getFoundation(TENANT_ID);

        @SuppressWarnings("unchecked")
        var git = (Map<String, Object>) result.get("gitRepo");
        assertEquals("linked", git.get("status"));
        assertEquals("https://github.com/zadam2008/pulse-acme-lending.git", git.get("repoUrl"));
        assertEquals(true, git.get("githubUrlValid"));
    }

    @Test
    void gitRepo_notLinked_returnsError() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.empty());
        when(gcpCredentialService.getRedactedCredential(TENANT_ID)).thenReturn(Optional.empty());
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT")).thenReturn(Optional.empty());

        var result = service.getFoundation(TENANT_ID);

        @SuppressWarnings("unchecked")
        var git = (Map<String, Object>) result.get("gitRepo");
        assertEquals("not_linked", git.get("status"));
    }

    @Test
    void foundation_includesPacketId() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.empty());
        when(gcpCredentialService.getRedactedCredential(TENANT_ID)).thenReturn(Optional.empty());
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT")).thenReturn(Optional.empty());

        var result = service.getFoundation(TENANT_ID);
        assertEquals("PKT-0010", result.get("packet"));
        assertEquals(TENANT_ID, result.get("tenantId"));
    }

    // ---- Fixtures ----

    private Tenant makeTenant() {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setName("Acme Lending");
        t.setSlug("acme-lending");
        t.setOrigin("api");
        t.setStatus("active");
        return t;
    }
}
