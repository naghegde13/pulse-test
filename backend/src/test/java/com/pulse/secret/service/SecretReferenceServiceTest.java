package com.pulse.secret.service;

import com.pulse.sor.model.CredentialProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretReferenceServiceTest {

    private final SecretReferenceService service = new SecretReferenceService();

    @Test
    void collectBindings_normalizesGcpLatestToActiveAlias() {
        List<SecretReferenceService.RuntimeSecretBinding> bindings = service.collectBindings(Map.of(
                "api_key", "gcp-sm://projects/pulse-dev/secrets/partner-api-key/versions/latest"
        ));

        assertEquals(1, bindings.size());
        assertEquals("PARTNER_API_KEY", bindings.get(0).envVarName());
        assertEquals(
                "gcp-sm://projects/pulse-dev/secrets/partner-api-key/versions/active",
                bindings.get(0).runtimeSecretRef()
        );
        assertEquals(SecretReferenceService.DeliveryMode.ENV, bindings.get(0).deliveryMode());
    }

    @Test
    void collectBindings_marksPrivateKeysForFileDelivery() {
        List<SecretReferenceService.RuntimeSecretBinding> bindings = service.collectBindings(Map.of(
                "private_key", "vault://pulse/dev/credit-sftp/key"
        ));

        assertEquals(1, bindings.size());
        assertEquals("CREDIT_SFTP_KEY_FILE", bindings.get(0).envVarName());
        assertEquals(SecretReferenceService.DeliveryMode.FILE, bindings.get(0).deliveryMode());
    }

    @Test
    void collectBindings_readsCanonicalSecretRefModel() {
        CredentialProfile credentialProfile = new CredentialProfile();
        credentialProfile.setConnectionConfig(Map.of(
                CredentialProfile.CANONICAL_METADATA_KEY, Map.of("host", "db.example.com"),
                CredentialProfile.CANONICAL_SECRET_REFS_KEY, Map.of(
                        "password", "gcp-sm://projects/pulse-dev/secrets/postgres-password/versions/latest"
                )
        ));

        List<SecretReferenceService.RuntimeSecretBinding> bindings = service.collectBindings(credentialProfile);

        assertEquals(1, bindings.size());
        assertEquals("password", bindings.get(0).fieldName());
        assertEquals("POSTGRES_PASSWORD", bindings.get(0).envVarName());
        assertEquals(
                "gcp-sm://projects/pulse-dev/secrets/postgres-password/versions/active",
                bindings.get(0).runtimeSecretRef()
        );
    }

    @Test
    void isSecretReference_onlyRecognizesSupportedSchemes() {
        assertTrue(service.isSecretReference("vault://pulse/dev/db/password"));
        assertTrue(service.isSecretReference("gcp-sm://projects/pulse-dev/secrets/api-key"));
        assertFalse(service.isSecretReference("https://example.com/not-a-secret"));
    }
}
