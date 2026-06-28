package com.pulse.auth.service;

import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.model.TenantGcpCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantGcpCredentialResolverTest {

    @Mock private TenantGcpConfigService configService;
    @Mock private TenantGcpCredentialService credentialService;
    @InjectMocks private TenantGcpCredentialResolver resolver;

    @Test
    void probe_allConfigured_returnsReady() {
        TenantGcpConfig config = new TenantGcpConfig();
        config.setControlPlaneProjectId("pulse-proof-04261847");
        config.setGcpRegion("us-central1");

        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setTenantId("tenant-acme");
        cred.setControlPlaneProjectId("pulse-proof-04261847");
        cred.setServiceAccountEmail("pulse-acme@pulse-proof-04261847.iam.gserviceaccount.com");
        cred.setKeyId("key-abc-123");
        cred.setStatus("active");

        when(configService.getConfig("tenant-acme")).thenReturn(Optional.of(config));
        when(credentialService.getCredentialEntity("tenant-acme")).thenReturn(Optional.of(cred));

        Map<String, Object> result = resolver.probe("tenant-acme");

        assertEquals("ready", result.get("status"));
        assertEquals("tenant_postgres", result.get("credentialSource"));
        assertEquals("pulse-proof-04261847", result.get("controlPlaneProjectId"));
        assertEquals("pulse-acme@pulse-proof-04261847.iam.gserviceaccount.com",
                result.get("serviceAccountEmail"));
        assertEquals("key-abc-123", result.get("keyId"));
        assertEquals(false, result.get("ambientAuthUsed"));
        assertEquals(true, result.get("privateKeyRedacted"));
    }

    @Test
    void probe_noConfig_failsClosed() {
        when(configService.getConfig("tenant-acme")).thenReturn(Optional.empty());

        Map<String, Object> result = resolver.probe("tenant-acme");

        assertEquals("failed", result.get("status"));
        assertEquals(false, result.get("ambientAuthUsed"));
        assertTrue(result.get("error").toString().contains("No GCP config found"));
    }

    @Test
    void probe_noCredential_failsClosed() {
        TenantGcpConfig config = new TenantGcpConfig();
        config.setControlPlaneProjectId("pulse-proof-04261847");
        config.setGcpRegion("us-central1");

        when(configService.getConfig("tenant-acme")).thenReturn(Optional.of(config));
        when(credentialService.getCredentialEntity("tenant-acme")).thenReturn(Optional.empty());

        Map<String, Object> result = resolver.probe("tenant-acme");

        assertEquals("failed", result.get("status"));
        assertEquals(false, result.get("ambientAuthUsed"));
        assertTrue(result.get("error").toString().contains("No GCP credential found"));
    }

    @Test
    void probe_projectMismatch_fails() {
        TenantGcpConfig config = new TenantGcpConfig();
        config.setControlPlaneProjectId("pulse-proof-04261847");
        config.setGcpRegion("us-central1");

        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setControlPlaneProjectId("wrong-project");
        cred.setServiceAccountEmail("sa@wrong-project.iam.gserviceaccount.com");
        cred.setKeyId("key-123");
        cred.setStatus("active");

        when(configService.getConfig("tenant-acme")).thenReturn(Optional.of(config));
        when(credentialService.getCredentialEntity("tenant-acme")).thenReturn(Optional.of(cred));

        Map<String, Object> result = resolver.probe("tenant-acme");

        assertEquals("failed", result.get("status"));
        assertTrue(result.get("error").toString().contains("Project mismatch"));
        assertEquals(false, result.get("ambientAuthUsed"));
    }

    @Test
    void probe_revokedCredential_fails() {
        TenantGcpConfig config = new TenantGcpConfig();
        config.setControlPlaneProjectId("pulse-proof-04261847");

        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setControlPlaneProjectId("pulse-proof-04261847");
        cred.setStatus("revoked");

        when(configService.getConfig("tenant-acme")).thenReturn(Optional.of(config));
        when(credentialService.getCredentialEntity("tenant-acme")).thenReturn(Optional.of(cred));

        Map<String, Object> result = resolver.probe("tenant-acme");

        assertEquals("failed", result.get("status"));
        assertTrue(result.get("error").toString().contains("revoked"));
    }

    @Test
    void probe_neverUsesAmbientAuth() {
        // Even when config+credential exist, ambientAuthUsed must be false
        TenantGcpConfig config = new TenantGcpConfig();
        config.setControlPlaneProjectId("p");

        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setControlPlaneProjectId("p");
        cred.setServiceAccountEmail("sa@p.iam.gserviceaccount.com");
        cred.setKeyId("k");
        cred.setStatus("active");

        when(configService.getConfig("t")).thenReturn(Optional.of(config));
        when(credentialService.getCredentialEntity("t")).thenReturn(Optional.of(cred));

        Map<String, Object> result = resolver.probe("t");

        assertEquals(false, result.get("ambientAuthUsed"));
    }

    @Test
    void probe_neverReturnsPrivateKeyInResult() {
        TenantGcpConfig config = new TenantGcpConfig();
        config.setControlPlaneProjectId("p");

        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setControlPlaneProjectId("p");
        cred.setServiceAccountEmail("sa@p.iam.gserviceaccount.com");
        cred.setKeyId("k");
        cred.setStatus("active");
        cred.setEncryptedCredential("ENCRYPTED_BLOB");

        when(configService.getConfig("t")).thenReturn(Optional.of(config));
        when(credentialService.getCredentialEntity("t")).thenReturn(Optional.of(cred));

        Map<String, Object> result = resolver.probe("t");

        String serialized = result.toString();
        assertFalse(serialized.contains("ENCRYPTED_BLOB"),
                "Probe result must never contain encrypted credential material");
        assertFalse(serialized.contains("PRIVATE KEY"),
                "Probe result must never contain private key text");
        // privateKeyRedacted=true is expected; but actual private key value must be absent
        assertFalse(serialized.contains("BEGIN RSA"),
                "Probe result must never contain raw private key content");
    }

    @Test
    void probe_usesConfiguredProjectNotHardcoded() {
        // The probe derives project from tenant config, not hardcoded values
        TenantGcpConfig config = new TenantGcpConfig();
        config.setControlPlaneProjectId("custom-project-id");
        config.setGcpRegion("europe-west1");

        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setControlPlaneProjectId("custom-project-id");
        cred.setServiceAccountEmail("sa@custom-project-id.iam.gserviceaccount.com");
        cred.setKeyId("custom-key");
        cred.setStatus("active");

        when(configService.getConfig("tenant-other")).thenReturn(Optional.of(config));
        when(credentialService.getCredentialEntity("tenant-other")).thenReturn(Optional.of(cred));

        Map<String, Object> result = resolver.probe("tenant-other");

        assertEquals("ready", result.get("status"));
        assertEquals("custom-project-id", result.get("controlPlaneProjectId"));
        assertEquals("sa@custom-project-id.iam.gserviceaccount.com",
                result.get("serviceAccountEmail"));
    }

    @Test
    void probe_credentialPrincipalDiffersFromConfiguredReadback_fails() {
        // If the SA email in the credential doesn't match what's expected
        // (project mismatch between config and credential is the signal)
        TenantGcpConfig config = new TenantGcpConfig();
        config.setControlPlaneProjectId("project-a");

        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setControlPlaneProjectId("project-b");
        cred.setServiceAccountEmail("sa@project-b.iam.gserviceaccount.com");
        cred.setKeyId("k");
        cred.setStatus("active");

        when(configService.getConfig("t")).thenReturn(Optional.of(config));
        when(credentialService.getCredentialEntity("t")).thenReturn(Optional.of(cred));

        Map<String, Object> result = resolver.probe("t");
        assertEquals("failed", result.get("status"));
    }

    @Test
    void resolveCredentialJson_noCredential_throws() {
        when(credentialService.getCredentialEntity("tenant-acme")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> resolver.resolveCredentialJson("tenant-acme"));
    }

    @Test
    void resolveCredentialJson_hasCredential_returns() {
        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setCredentialMode(com.pulse.auth.model.TenantGcpCredential.CredentialMode.STATIC_KEY);
        cred.setControlPlaneProjectId("p");
        cred.setServiceAccountEmail("sa@p.iam.gserviceaccount.com");
        cred.setKeyId("k");
        cred.setEncryptedCredential("ENCRYPTED");
        when(credentialService.getCredentialEntity("tenant-acme"))
                .thenReturn(Optional.of(cred));
        when(credentialService.getDecryptedCredential("tenant-acme"))
                .thenReturn(Optional.of("{\"type\":\"service_account\"}"));

        String json = resolver.resolveCredentialJson("tenant-acme");
        assertEquals("{\"type\":\"service_account\"}", json);
    }

    @Test
    void resolveCredentialJson_impersonationMode_throws() {
        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setCredentialMode(com.pulse.auth.model.TenantGcpCredential.CredentialMode.IMPERSONATION);
        cred.setControlPlaneProjectId("p");
        cred.setServiceAccountEmail("sa@p.iam.gserviceaccount.com");
        cred.setTenantServiceAccountEmail("sa@p.iam.gserviceaccount.com");
        when(credentialService.getCredentialEntity("tenant-acme"))
                .thenReturn(Optional.of(cred));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> resolver.resolveCredentialJson("tenant-acme"));
        assertTrue(ex.getMessage().contains("IMPERSONATION"),
                "Expected message about IMPERSONATION mode; got: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // PKT-FINAL-6 — IMPERSONATION mode probe + resolveCredentials surface
    // ------------------------------------------------------------------

    @Test
    void probe_impersonationMode_surfacesCredentialMode() {
        TenantGcpConfig config = new TenantGcpConfig();
        config.setControlPlaneProjectId("pulse-proof-04261847");
        config.setGcpRegion("us-central1");

        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setCredentialMode(TenantGcpCredential.CredentialMode.IMPERSONATION);
        cred.setControlPlaneProjectId("pulse-proof-04261847");
        cred.setServiceAccountEmail("pulse-tenant@pulse-proof-04261847.iam.gserviceaccount.com");
        cred.setTenantServiceAccountEmail("pulse-tenant@pulse-proof-04261847.iam.gserviceaccount.com");
        cred.setKeyId(null);
        cred.setStatus("active");

        when(configService.getConfig("tenant-acme")).thenReturn(Optional.of(config));
        when(credentialService.getCredentialEntity("tenant-acme")).thenReturn(Optional.of(cred));

        Map<String, Object> result = resolver.probe("tenant-acme");

        // ADC may or may not be present in this test env. Either way, the
        // probe must surface the credentialMode so callers can render the UI
        // and not mistake an IMPERSONATION row for a STATIC_KEY one.
        assertEquals("IMPERSONATION", result.get("credentialMode"));
        assertNotNull(result.get("status"));
        if ("ready".equals(result.get("status"))) {
            assertNull(result.get("deprecationNotice"));
        } else {
            // If ADC isn't available locally, the probe surfaces the specific
            // operator-actionable error rather than failing silently.
            assertEquals("failed", result.get("status"));
            assertTrue(result.get("error").toString().contains("ADC not available"),
                    "Expected ADC absence to surface the gcloud auth ADC blocker; got: "
                            + result.get("error"));
        }
    }

    @Test
    void resolveCredentials_returnsImpersonatedCredentials_forImpersonationMode() throws Exception {
        // ImpersonatedCredentials.create() requires a non-null source. We
        // can't easily construct one without ADC, so this test ONLY verifies
        // the IMPERSONATION branch fails-with-actionable-message when ADC is
        // absent — when ADC IS available, the same code path returns an
        // ImpersonatedCredentials instance (covered by the resolver-class
        // javadoc + the upstream google-auth contract).
        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setCredentialMode(TenantGcpCredential.CredentialMode.IMPERSONATION);
        cred.setControlPlaneProjectId("pulse-proof-04261847");
        cred.setServiceAccountEmail("sa@pulse-proof-04261847.iam.gserviceaccount.com");
        cred.setTenantServiceAccountEmail("sa@pulse-proof-04261847.iam.gserviceaccount.com");
        when(credentialService.getCredentialEntity("tenant-acme")).thenReturn(Optional.of(cred));

        try {
            var credentials = resolver.resolveCredentials("tenant-acme");
            // If we DID find ADC (rare in CI; common in local dev), the
            // returned credentials must be the Impersonated variant — never
            // a raw ServiceAccountCredentials.
            assertNotNull(credentials);
            assertTrue(credentials.getClass().getName().endsWith("ImpersonatedCredentials"),
                    "Expected ImpersonatedCredentials for IMPERSONATION mode, got: "
                            + credentials.getClass());
        } catch (java.io.IOException e) {
            // ADC absent — must surface the specific operator-actionable blocker.
            assertTrue(e.getMessage().contains("ADC not available")
                    || (e.getCause() != null && String.valueOf(e.getCause().getMessage())
                            .contains("Application Default Credentials")),
                    "Expected ADC-absent IOException to mention ADC; got: " + e.getMessage());
        }
    }

    @Test
    void resolveCredentials_returnsServiceAccountCredentials_forStaticKey() throws Exception {
        String fakeJson = "{\"type\":\"service_account\","
                + "\"project_id\":\"pulse-proof-04261847\","
                + "\"private_key_id\":\"k1\","
                + "\"client_email\":\"sa@pulse-proof-04261847.iam.gserviceaccount.com\","
                + "\"client_id\":\"123\","
                + "\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nNOT A REAL KEY\\n-----END PRIVATE KEY-----\\n\"}";
        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setCredentialMode(TenantGcpCredential.CredentialMode.STATIC_KEY);
        cred.setControlPlaneProjectId("pulse-proof-04261847");
        cred.setServiceAccountEmail("sa@pulse-proof-04261847.iam.gserviceaccount.com");
        cred.setKeyId("k1");
        cred.setEncryptedCredential("ENC");
        when(credentialService.getCredentialEntity("tenant-acme")).thenReturn(Optional.of(cred));
        when(credentialService.getDecryptedCredential("tenant-acme"))
                .thenReturn(Optional.of(fakeJson));

        // google-auth refuses to parse private keys that are not PEM-encoded.
        // We only care that the STATIC_KEY branch flows through
        // ServiceAccountCredentials.fromStream — assert by type when parsing
        // succeeds, otherwise assert the failure happens during parsing
        // rather than from a stub/null reference.
        try {
            var credentials = resolver.resolveCredentials("tenant-acme");
            assertNotNull(credentials);
            assertTrue(credentials.getClass().getName().endsWith("ServiceAccountCredentials"),
                    "Expected ServiceAccountCredentials for STATIC_KEY mode, got: "
                            + credentials.getClass());
        } catch (java.io.IOException | IllegalArgumentException e) {
            // Acceptable — the fake key is invalid, so fromStream blows up
            // somewhere inside google-auth (PEM/Base64 parsing). The important
            // contract is that the STATIC_KEY branch did run (not the
            // impersonation path).
            assertFalse(String.valueOf(e.getMessage()).contains("ADC not available"),
                    "STATIC_KEY mode must not route through the ADC branch");
        }
    }
}
