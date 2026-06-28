package com.pulse.support;

import com.pulse.secret.service.SecretManagerException;
import com.pulse.secret.service.SecretNamingContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC_in_memory_gcp_secret_manager — the fake mirrors the production surface: get/put round
 * trips, lexicographic listing, latest vs versioned reads, and the SAME exception type as
 * production for missing secrets.
 */
class InMemoryGcpSecretManagerTest {

    @Test
    void putGetRoundTrip_returnsLatestValueByDefault() {
        InMemoryGcpSecretManager fake = new InMemoryGcpSecretManager();
        fake.createOrUpdateSecret("dev", "pulse-dev-acme-default-source-creds-pat-1", "value-v1", null);
        fake.createOrUpdateSecret("dev", "pulse-dev-acme-default-source-creds-pat-1", "value-v2", null);

        assertEquals("value-v2", fake.getSecretValue("dev", "pulse-dev-acme-default-source-creds-pat-1"),
                "latest write wins");
        assertTrue(fake.secretExists("dev", "pulse-dev-acme-default-source-creds-pat-1"));
        assertEquals(2, fake.versionCount("dev", "pulse-dev-acme-default-source-creds-pat-1"),
                "two versions written");
    }

    @Test
    void unsetSecret_throwsSameTypeAsProduction() {
        InMemoryGcpSecretManager fake = new InMemoryGcpSecretManager();
        // Production class throws SecretManagerException for missing local-stub secrets;
        // tests must catch the same type, not a fresh "NotFound".
        SecretManagerException ex = assertThrows(SecretManagerException.class,
                () -> fake.getSecretValue("dev", "pulse-dev-missing"));
        assertTrue(ex.getMessage().contains("pulse-dev-missing"),
                "error mentions the missing secret id: " + ex.getMessage());
    }

    @Test
    void listSecretIds_isLexicographic() {
        InMemoryGcpSecretManager fake = new InMemoryGcpSecretManager();
        fake.createOrUpdateSecret("dev", "zeta", "v", null);
        fake.createOrUpdateSecret("dev", "alpha", "v", null);
        fake.createOrUpdateSecret("dev", "mike", "v", null);

        List<String> ids = fake.listSecretIds("dev");
        assertEquals(List.of("alpha", "mike", "zeta"), ids);
    }

    @Test
    void buildSecretId_appliesNamingConvention() {
        InMemoryGcpSecretManager fake = new InMemoryGcpSecretManager();
        // Tenant + domain segments are passed pre-slugified in production callers; the
        // production buildSecretId only lowercases them. Resource + field segments DO go
        // through full slugify (spaces -> '-'). We mirror that exact contract.
        SecretNamingContext ctx = new SecretNamingContext(
                "dev", "acme", "loan-servicing", "source", "Loan Master", "pat", "01HULID");
        String id = fake.buildSecretId(ctx);
        assertTrue(id.startsWith("pulse-acme-loan-servicing-source-loan-master-pat-"),
                "secret id slugs each segment: " + id);
        assertFalse(id.contains("Acme"), "tenant lowercased");
        // Production drops env from the secret id in non-local-stub mode; we mirror that.
        assertFalse(id.startsWith("pulse-dev-"), "env not embedded in non-stub mode: " + id);
    }

    @Test
    void buildSecretReference_producesGcpSmUri() {
        InMemoryGcpSecretManager fake = new InMemoryGcpSecretManager();
        fake.setProjectId("dev", "pulse-dev-12345");
        String ref = fake.buildSecretReference("dev", "pulse-dev-acme-source-creds");
        assertEquals("gcp-sm://projects/pulse-dev-12345/secrets/pulse-dev-acme-source-creds/versions/latest",
                ref);
    }

    @Test
    void getByReference_honorsExplicitVersion() {
        InMemoryGcpSecretManager fake = new InMemoryGcpSecretManager();
        fake.createOrUpdateSecret("dev", "rotated", "old", null);     // version 1
        fake.createOrUpdateSecret("dev", "rotated", "new", null);     // version 2

        String latestRef = fake.buildSecretReference("dev", "rotated");
        assertEquals("new", fake.getSecretValueByReference(latestRef));

        String pinnedRef = "gcp-sm://projects/pulse-test/secrets/rotated/versions/1";
        assertEquals("old", fake.getSecretValueByReference(pinnedRef));
    }

    @Test
    void disableSecret_disablesAllEnabledVersions() {
        InMemoryGcpSecretManager fake = new InMemoryGcpSecretManager();
        fake.createOrUpdateSecret("dev", "s", "v1", null);
        fake.createOrUpdateSecret("dev", "s", "v2", null);
        fake.disableSecret("dev", "s");

        // After disable, latest read fails with the production exception type.
        assertThrows(SecretManagerException.class, () -> fake.getSecretValue("dev", "s"));
    }

    @Test
    void disableByReference_explicitVersion_disablesOnlyThatOne() {
        InMemoryGcpSecretManager fake = new InMemoryGcpSecretManager();
        fake.createOrUpdateSecret("dev", "s", "v1", null);
        fake.createOrUpdateSecret("dev", "s", "v2", null);
        fake.disableSecretByReference("gcp-sm://projects/pulse-test/secrets/s/versions/1");

        // v2 still readable as latest
        assertEquals("v2", fake.getSecretValue("dev", "s"));
        // v1 specifically rejects with SecretManagerException
        assertThrows(SecretManagerException.class,
                () -> fake.getSecretValueByReference("gcp-sm://projects/pulse-test/secrets/s/versions/1"));
    }

    @Test
    void labels_roundTrip() {
        InMemoryGcpSecretManager fake = new InMemoryGcpSecretManager();
        fake.createOrUpdateSecret("dev", "labeled", "v",
                Map.of("environment", "dev", "tenant", "acme"));
        Map<String, String> labels = fake.getLatestLabels("dev", "labeled");
        assertEquals("dev", labels.get("environment"));
        assertEquals("acme", labels.get("tenant"));
    }

    @Test
    void mode_isInformational() {
        assertEquals("in-memory", new InMemoryGcpSecretManager().getSecretManagerMode());
        assertEquals("local-stub", new InMemoryGcpSecretManager("local-stub").getSecretManagerMode());
    }
}
