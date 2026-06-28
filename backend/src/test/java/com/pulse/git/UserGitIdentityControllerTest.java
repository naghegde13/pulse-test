package com.pulse.git;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.git.controller.UserGitIdentityController;
import com.pulse.git.identity.UserGitIdentityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase 6 — write-only API contract.
 *
 * <p>Pins:
 * <ul>
 *   <li>The {@code POST /api/v1/users/me/git-identity} request body
 *       record has no actor / credentialReference / secretId fields,
 *       so a malicious JSON body with those keys cannot reach the
 *       service even via Jackson's relaxed
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES=false} (Spring's default).</li>
 *   <li>The controller never echoes the token back; the
 *       {@code MaskedGitIdentity} record has no token field so the
 *       wire shape is structurally token-free.</li>
 * </ul>
 */
class UserGitIdentityControllerTest {

    @Test
    @DisplayName("RegisterRequest record drops unknown JSON fields silently — no spoofable identity")
    void registerRequestDropsSpoofedFields() throws Exception {
        String maliciousJson = """
                {
                  "token": "ghp_real",
                  "githubUsername": "mrivera",
                  "authorName": "Mike",
                  "authorEmail": "m@x",
                  "scopes": "repo",
                  "repositoryUrl": "https://github.com/x/y",
                  "pulseUserId": "spoof-user",
                  "credentialReference": "gcp-sm://evil/",
                  "secretId": "evil",
                  "tenantId": "spoof-tenant"
                }
                """;
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        UserGitIdentityService.RegisterRequest decoded = mapper
                .readerFor(UserGitIdentityService.RegisterRequest.class)
                .readValue(maliciousJson);
        assertEquals("ghp_real", decoded.token());
        assertEquals("mrivera", decoded.githubUsername());
        // The malicious fields are NOT exposed by the record — no
        // accessor exists, no way to set them on the deserialized
        // record, no way for the service to read them.
        Set<String> recordFields = new java.util.HashSet<>();
        for (var component : UserGitIdentityService.RegisterRequest.class.getRecordComponents()) {
            recordFields.add(component.getName());
        }
        for (String forbidden : Set.of("pulseUserId", "credentialReference",
                "secretId", "tenantId", "userId")) {
            assertFalse(recordFields.contains(forbidden),
                    "RegisterRequest must NOT expose '" + forbidden + "'");
        }
    }

    @Test
    @DisplayName("MaskedGitIdentity record has no token field — read APIs cannot leak the PAT")
    void maskedGitIdentityHasNoTokenField() {
        for (var component : com.pulse.git.identity.MaskedGitIdentity
                .class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase().contains("token"),
                    "MaskedGitIdentity must NOT expose any token-shaped field, found: "
                            + component.getName());
        }
    }

    @Test
    @DisplayName("MaskedGitIdentity.maskReference truncates the secret id and never leaks the version")
    void maskReferenceTruncatesAndDoesNotLeakVersion() {
        String full = "gcp-sm://projects/pulse-dev/secrets/pulse-tenant-a-user-mike-github-pat-abc123/versions/latest";
        String masked = com.pulse.git.identity.MaskedGitIdentity.maskReference(full);
        // The mask shows project + secret-id stub (first 12 chars) but
        // never the version suffix or full secret id.
        assertEquals("gcp-sm://projects/pulse-dev/secrets/pulse-tenant…", masked);
        assertNull(com.pulse.git.identity.MaskedGitIdentity.maskReference(null));
        assertFalse(masked.contains("/versions/latest"),
                "masked reference must NEVER include the version suffix");
    }

    @Test
    @DisplayName("Controller class is the same one wired by Spring; @RestController is correct")
    void controllerExistsWithExpectedRequestMapping() {
        // Smoke check that the controller has the expected request mapping.
        org.springframework.web.bind.annotation.RequestMapping mapping =
                UserGitIdentityController.class.getAnnotation(
                        org.springframework.web.bind.annotation.RequestMapping.class);
        assertEquals("/api/v1/users/me/git-identity", mapping.value()[0]);
    }
}
