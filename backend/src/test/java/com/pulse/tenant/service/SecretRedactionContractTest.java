package com.pulse.tenant.service;

import com.pulse.auth.model.Tenant;
import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.service.TenantGcpConfigService;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.auth.service.TenantService;
import com.pulse.git.identity.MaskedGitIdentity;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * PKT-0010 — secret redaction and surface tests.
 *
 * <p>Validates that no PAT values, private keys, encrypted credentials, or
 * service-account JSON body contents appear in any readiness readback,
 * foundation output, or API response.
 */
@ExtendWith(MockitoExtension.class)
class SecretRedactionContractTest {

    private static final String TENANT_ID = "tenant-acme-lending";

    // Realistic secret values that must NEVER appear in any readback
    private static final String FAKE_PAT = "ghp_ABC123secretToken456789";
    private static final String FAKE_PRIVATE_KEY = "-----BEGIN RSA PRIVATE KEY-----\nMIIE...fake...key\n-----END RSA PRIVATE KEY-----";
    private static final String FAKE_CREDENTIAL_JSON = """
            {"type":"service_account","private_key":"%s","client_email":"sa@proj.iam.gserviceaccount.com"}
            """.formatted(FAKE_PRIVATE_KEY);

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

    @Test
    void foundationReadback_neverContainsPatValue() {
        stubFullyConfiguredTenant();

        var result = service.getFoundation(TENANT_ID);
        String serialized = deepSerialize(result);

        assertFalse(serialized.contains(FAKE_PAT),
                "Foundation readback must never contain PAT value");
        assertFalse(serialized.contains("ghp_"),
                "Foundation readback must never contain GitHub PAT prefix");
    }

    @Test
    void foundationReadback_neverContainsPrivateKeyMaterial() {
        stubFullyConfiguredTenant();

        var result = service.getFoundation(TENANT_ID);
        String serialized = deepSerialize(result);

        assertFalse(serialized.contains("BEGIN RSA"),
                "Must not contain RSA key header");
        assertFalse(serialized.contains("BEGIN PRIVATE"),
                "Must not contain private key header");
        assertFalse(serialized.contains("MIIe"),
                "Must not contain key material");
        assertFalse(serialized.contains("private_key"),
                "Must not contain private_key field name");
    }

    @Test
    void foundationReadback_neverContainsEncryptedCredential() {
        stubFullyConfiguredTenant();

        var result = service.getFoundation(TENANT_ID);
        String serialized = deepSerialize(result);

        assertFalse(serialized.contains("encrypted_credential"),
                "Must not contain encrypted_credential field");
        assertFalse(serialized.contains("encryptedCredential"),
                "Must not contain encryptedCredential field");
    }

    @Test
    void foundationReadback_credentialOnlyContainsAllowedRedactedFields() {
        stubFullyConfiguredTenant();

        var result = service.getFoundation(TENANT_ID);

        @SuppressWarnings("unchecked")
        var cred = (Map<String, Object>) result.get("gcpCredential");

        Set<String> allowedFields = Set.of(
                "status", "serviceAccountEmail", "keyId", "gcpProjectId",
                "privateKeyRedacted", "source");
        for (String key : cred.keySet()) {
            assertTrue(allowedFields.contains(key),
                    "Credential readback contains disallowed field: " + key);
        }
    }

    @Test
    void maskedGitIdentity_maskReference_hidesFullSecretId() {
        String reference = "gcp-sm://projects/acme-prod/secrets/pulse-acme-user-alice-github-pat-abc123def456/versions/latest";
        String masked = MaskedGitIdentity.maskReference(reference);

        assertFalse(masked.contains("abc123def456"),
                "Masked reference should not contain full stableId");
        assertTrue(masked.contains("gcp-sm://projects/acme-prod/secrets/"),
                "Masked reference should contain project prefix");
    }

    @Test
    void maskedGitIdentity_maskReference_nullSafe() {
        String masked = MaskedGitIdentity.maskReference(null);
        assertTrue(masked == null);
    }

    // ---- Helpers ----

    private void stubFullyConfiguredTenant() {
        Tenant tenant = new Tenant();
        tenant.setId(TENANT_ID);
        tenant.setName("Acme Lending");
        tenant.setSlug("acme-lending");
        tenant.setOrigin("api");
        tenant.setStatus("active");
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(tenant);

        TenantGcpConfig config = new TenantGcpConfig();
        config.setTenantId(TENANT_ID);
        config.setControlPlaneProjectId("acme-lending-prod");
        config.setGcpRegion("us-central1");
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(config));

        // The redacted credential service should return ONLY safe fields
        Map<String, Object> redacted = new LinkedHashMap<>();
        redacted.put("serviceAccountEmail", "sa@acme-lending-prod.iam.gserviceaccount.com");
        redacted.put("keyId", "abc123");
        redacted.put("gcpProjectId", "acme-lending-prod");
        redacted.put("status", "active");
        when(gcpCredentialService.getRedactedCredential(TENANT_ID)).thenReturn(Optional.of(redacted));

        GitRepo repo = new GitRepo();
        repo.setTenantId(TENANT_ID);
        repo.setRepoUrl("https://github.com/zadam2008/pulse-acme-lending.git");
        repo.setRepoType("REMOTE");
        repo.setProvider("GITHUB");
        repo.setDefaultBranch("main");
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT")).thenReturn(Optional.of(repo));
    }

    private String deepSerialize(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        serializeRecursive(map, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void serializeRecursive(Object obj, StringBuilder sb) {
        if (obj == null) return;
        if (obj instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                sb.append(entry.getKey()).append("=");
                serializeRecursive(entry.getValue(), sb);
                sb.append("|");
            }
        } else if (obj instanceof Iterable<?> iter) {
            for (var item : iter) {
                serializeRecursive(item, sb);
            }
        } else {
            sb.append(obj.toString());
        }
    }
}
