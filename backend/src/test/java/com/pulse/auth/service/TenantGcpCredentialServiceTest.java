package com.pulse.auth.service;

import com.pulse.auth.model.TenantGcpCredential;
import com.pulse.auth.repository.TenantGcpCredentialRepository;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantGcpCredentialServiceTest {

    @Mock private TenantGcpCredentialRepository credentialRepo;
    @Mock private TenantRepository tenantRepo;
    @Mock private TenantCredentialEncryptor encryptor;
    @InjectMocks private TenantGcpCredentialService service;

    private static final String VALID_SA_JSON = """
            {
              "type": "service_account",
              "project_id": "pulse-proof-04261847",
              "private_key_id": "key-abc-123",
              "private_key": "redacted-test-key-material",
              "client_email": "pulse-acme@pulse-proof-04261847.iam.gserviceaccount.com",
              "client_id": "123456789",
              "auth_uri": "https://accounts.google.com/o/oauth2/auth",
              "token_uri": "https://oauth2.googleapis.com/token"
            }
            """;

    @Test
    void submitCredential_validJson_storesEncryptedAndReturnsRedacted() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        when(credentialRepo.findByTenantId("tenant-acme")).thenReturn(Optional.empty());
        when(encryptor.encrypt(any())).thenReturn("ENCRYPTED_BASE64");
        when(credentialRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> readback = service.submitCredential("tenant-acme", VALID_SA_JSON);

        assertEquals("tenant-acme", readback.get("tenantId"));
        assertEquals("pulse-acme@pulse-proof-04261847.iam.gserviceaccount.com",
                readback.get("serviceAccountEmail"));
        assertEquals("key-abc-123", readback.get("keyId"));
        assertEquals("pulse-proof-04261847", readback.get("controlPlaneProjectId"));
        assertEquals("active", readback.get("status"));
        assertEquals("tenant_postgres", readback.get("credentialSource"));
        assertEquals(true, readback.get("privateKeyRedacted"));

        // Verify private key is NEVER in readback
        assertFalse(readback.toString().contains("PRIVATE KEY"));
        assertFalse(readback.toString().contains("MIIE"));
    }

    @Test
    void submitCredential_storesEncryptedCredential() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        when(credentialRepo.findByTenantId("tenant-acme")).thenReturn(Optional.empty());
        when(encryptor.encrypt(any())).thenReturn("ENCRYPTED_BASE64");
        when(credentialRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submitCredential("tenant-acme", VALID_SA_JSON);

        ArgumentCaptor<TenantGcpCredential> captor = ArgumentCaptor.forClass(TenantGcpCredential.class);
        verify(credentialRepo).save(captor.capture());

        TenantGcpCredential saved = captor.getValue();
        assertEquals("ENCRYPTED_BASE64", saved.getEncryptedCredential());
        assertEquals("pulse-acme@pulse-proof-04261847.iam.gserviceaccount.com",
                saved.getServiceAccountEmail());
        assertEquals("key-abc-123", saved.getKeyId());
    }

    @Test
    void submitCredential_missingClientEmail_throws() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        String json = """
                {"project_id": "p", "private_key_id": "k", "private_key": "redacted-test-key-material"}
                """;
        assertThrows(IllegalArgumentException.class,
                () -> service.submitCredential("tenant-acme", json));
    }

    @Test
    void submitCredential_missingPrivateKeyId_throws() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        String json = """
                {"project_id": "p", "client_email": "e@e.com", "private_key": "redacted-test-key-material"}
                """;
        assertThrows(IllegalArgumentException.class,
                () -> service.submitCredential("tenant-acme", json));
    }

    @Test
    void submitCredential_missingProjectId_throws() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        String json = """
                {"client_email": "e@e.com", "private_key_id": "k", "private_key": "redacted-test-key-material"}
                """;
        assertThrows(IllegalArgumentException.class,
                () -> service.submitCredential("tenant-acme", json));
    }

    @Test
    void submitCredential_missingPrivateKey_throws() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        String json = """
                {"project_id": "p", "client_email": "e@e.com", "private_key_id": "k"}
                """;
        assertThrows(IllegalArgumentException.class,
                () -> service.submitCredential("tenant-acme", json));
    }

    @Test
    void submitCredential_invalidJson_throws() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.submitCredential("tenant-acme", "not valid json"));
    }

    @Test
    void submitCredential_nullTenantId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submitCredential(null, VALID_SA_JSON));
    }

    @Test
    void submitCredential_tenantNotFound_throws() {
        when(tenantRepo.existsById("missing")).thenReturn(false);
        assertThrows(ResourceNotFoundException.class,
                () -> service.submitCredential("missing", VALID_SA_JSON));
    }

    @Test
    void getRedactedCredential_exists_neverContainsPrivateKey() {
        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setTenantId("tenant-acme");
        cred.setServiceAccountEmail("sa@project.iam.gserviceaccount.com");
        cred.setKeyId("key-123");
        cred.setControlPlaneProjectId("my-project");
        cred.setStatus("active");
        cred.setEncryptedCredential("ENCRYPTED");

        when(credentialRepo.findByTenantId("tenant-acme")).thenReturn(Optional.of(cred));

        Optional<Map<String, Object>> result = service.getRedactedCredential("tenant-acme");

        assertTrue(result.isPresent());
        Map<String, Object> readback = result.get();
        assertEquals(true, readback.get("privateKeyRedacted"));
        assertFalse(readback.containsKey("privateKey"));
        assertFalse(readback.containsKey("private_key"));
        assertFalse(readback.containsKey("encryptedCredential"));

        // Exhaustive check: no value in the readback contains key material
        String serialized = readback.toString();
        assertFalse(serialized.contains("ENCRYPTED"));
        assertFalse(serialized.contains("PRIVATE KEY"));
    }

    @Test
    void getRedactedCredential_notExists_returnsEmpty() {
        when(credentialRepo.findByTenantId("missing")).thenReturn(Optional.empty());
        assertTrue(service.getRedactedCredential("missing").isEmpty());
    }

    @Test
    void getDecryptedCredential_returnsDecrypted() {
        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setEncryptedCredential("ENCRYPTED");
        when(credentialRepo.findByTenantId("tenant-acme")).thenReturn(Optional.of(cred));
        when(encryptor.decrypt("ENCRYPTED")).thenReturn("{\"decrypted\": true}");

        Optional<String> result = service.getDecryptedCredential("tenant-acme");

        assertTrue(result.isPresent());
        assertEquals("{\"decrypted\": true}", result.get());
    }

    @Test
    void submitCredential_updatesExistingRecord() {
        TenantGcpCredential existing = new TenantGcpCredential();
        existing.setTenantId("tenant-acme");
        existing.setServiceAccountEmail("old@old.iam.gserviceaccount.com");

        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        when(credentialRepo.findByTenantId("tenant-acme")).thenReturn(Optional.of(existing));
        when(encryptor.encrypt(any())).thenReturn("NEW_ENCRYPTED");
        when(credentialRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> readback = service.submitCredential("tenant-acme", VALID_SA_JSON);

        assertEquals("pulse-acme@pulse-proof-04261847.iam.gserviceaccount.com",
                readback.get("serviceAccountEmail"));

        // Verify it updated the existing record, not created a new one
        verify(credentialRepo, times(1)).save(existing);
    }

    // ------------------------------------------------------------------
    // IMPERSONATION mode (PKT-FINAL-6 / BUG-2026-05-25-48)
    // ------------------------------------------------------------------

    @Test
    void submitImpersonationCredential_validEmail_storesWithNoKeyMaterial() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        when(credentialRepo.findByTenantId("tenant-acme")).thenReturn(Optional.empty());
        when(credentialRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> readback = service.submitImpersonationCredential(
                "tenant-acme",
                "pulse-tenant-acme@pulse-proof-04261847.iam.gserviceaccount.com");

        assertEquals("tenant-acme", readback.get("tenantId"));
        assertEquals("IMPERSONATION", readback.get("credentialMode"));
        assertEquals("pulse-tenant-acme@pulse-proof-04261847.iam.gserviceaccount.com",
                readback.get("serviceAccountEmail"));
        assertNull(readback.get("keyId"));
        assertEquals("pulse-proof-04261847", readback.get("controlPlaneProjectId"),
                "Control-plane project must be inferred from SA email domain");
        assertEquals(true, readback.get("privateKeyRedacted"));
        assertNull(readback.get("deprecationNotice"),
                "IMPERSONATION mode is not deprecated; no notice expected");

        ArgumentCaptor<TenantGcpCredential> captor = ArgumentCaptor.forClass(TenantGcpCredential.class);
        verify(credentialRepo).save(captor.capture());
        TenantGcpCredential saved = captor.getValue();
        assertEquals(TenantGcpCredential.CredentialMode.IMPERSONATION, saved.getCredentialMode());
        assertNull(saved.getEncryptedCredential(),
                "IMPERSONATION rows must not store any encrypted material");
        assertNull(saved.getKeyId(),
                "IMPERSONATION rows have no SA key");
        assertEquals("pulse-tenant-acme@pulse-proof-04261847.iam.gserviceaccount.com",
                saved.getTenantServiceAccountEmail());
    }

    @Test
    void submitImpersonationCredential_invalidEmail_throws() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);

        // Missing .iam.gserviceaccount.com suffix
        assertThrows(IllegalArgumentException.class,
                () -> service.submitImpersonationCredential("tenant-acme", "user@example.com"));
        // Underscore in project name — GCP project IDs allow only [a-z0-9-]
        assertThrows(IllegalArgumentException.class,
                () -> service.submitImpersonationCredential("tenant-acme",
                        "pulse-tenant@my_project.iam.gserviceaccount.com"));
        // Blank
        assertThrows(IllegalArgumentException.class,
                () -> service.submitImpersonationCredential("tenant-acme", ""));
    }

    @Test
    void submitImpersonationCredential_nullTenantId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submitImpersonationCredential(null,
                        "pulse-tenant@pulse-proof-04261847.iam.gserviceaccount.com"));
    }

    @Test
    void submitImpersonationCredential_tenantNotFound_throws() {
        when(tenantRepo.existsById("missing")).thenReturn(false);
        assertThrows(ResourceNotFoundException.class,
                () -> service.submitImpersonationCredential("missing",
                        "pulse-tenant@pulse-proof-04261847.iam.gserviceaccount.com"));
    }

    @Test
    void submitImpersonationCredential_replacesExistingStaticKey() {
        TenantGcpCredential existing = new TenantGcpCredential();
        existing.setTenantId("tenant-acme");
        existing.setCredentialMode(TenantGcpCredential.CredentialMode.STATIC_KEY);
        existing.setEncryptedCredential("OLD_ENCRYPTED");
        existing.setKeyId("old-key");

        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        when(credentialRepo.findByTenantId("tenant-acme")).thenReturn(Optional.of(existing));
        when(credentialRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submitImpersonationCredential(
                "tenant-acme",
                "pulse-tenant@pulse-proof-04261847.iam.gserviceaccount.com");

        // The existing row must have its key material cleared on mode swap
        assertEquals(TenantGcpCredential.CredentialMode.IMPERSONATION, existing.getCredentialMode());
        assertNull(existing.getEncryptedCredential(),
                "Switching to IMPERSONATION must clear prior encrypted material");
        assertNull(existing.getKeyId(),
                "Switching to IMPERSONATION must clear prior key ID");
    }

    @Test
    void getDecryptedCredential_impersonationMode_returnsEmpty() {
        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setCredentialMode(TenantGcpCredential.CredentialMode.IMPERSONATION);
        cred.setEncryptedCredential(null);
        when(credentialRepo.findByTenantId("tenant-acme")).thenReturn(Optional.of(cred));

        Optional<String> result = service.getDecryptedCredential("tenant-acme");
        assertTrue(result.isEmpty(),
                "IMPERSONATION rows have no encrypted material to decrypt");
    }

    @Test
    void readback_staticKeyMode_includesDeprecationNotice() {
        when(tenantRepo.existsById("tenant-acme")).thenReturn(true);
        when(credentialRepo.findByTenantId("tenant-acme")).thenReturn(Optional.empty());
        when(encryptor.encrypt(any())).thenReturn("ENCRYPTED_BASE64");
        when(credentialRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> readback = service.submitCredential("tenant-acme", VALID_SA_JSON);

        assertEquals("STATIC_KEY", readback.get("credentialMode"));
        assertNotNull(readback.get("deprecationNotice"),
                "STATIC_KEY mode readback must include a deprecation notice");
        assertTrue(readback.get("deprecationNotice").toString().contains("IMPERSONATION"),
                "Deprecation notice should point operators at IMPERSONATION mode");
    }
}
