package com.pulse.git;

import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PulseRole;
import com.pulse.auth.service.TenantService;
import com.pulse.config.TenantConfig;
import com.pulse.git.identity.GitHubPatValidationStatus;
import com.pulse.git.identity.MaskedGitIdentity;
import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.identity.UserGitIdentityRepository;
import com.pulse.git.identity.UserGitIdentityService;
import com.pulse.git.provider.GitProviderAdapter;
import com.pulse.secret.service.GcpSecretManagerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6 closeout — verify-before-disable rotation must NOT
 * invalidate the active credential when the new token fails
 * validation. The plan's exact words: "Pulse keeps the prior valid
 * credential active and records the failed rotation status".
 */
class GitHubPatRotationFailureKeepsActiveTest {

    private UserGitIdentityRepository identityRepo;
    private GcpSecretManagerService secretManager;
    private GitProviderAdapter provider;
    private TenantService tenantService;
    private UserGitIdentityService service;

    private static final String TENANT = "tenant-A";
    private static final String USER = "user-mike";
    private static final String PRIOR_REFERENCE =
            "gcp-sm://projects/pulse-prod-001/secrets/pulse-tenant-a-user-mike-github-pat-old123/versions/3";

    @BeforeEach
    void setUp() {
        identityRepo = mock(UserGitIdentityRepository.class);
        secretManager = mock(GcpSecretManagerService.class);
        provider = mock(GitProviderAdapter.class);
        tenantService = mock(TenantService.class);
        TenantConfig.TenantDefinition def = new TenantConfig.TenantDefinition();
        def.setId(TENANT);
        def.setSlug("tenant-a");
        when(tenantService.getTenant(TENANT)).thenReturn(def);
        when(identityRepo.save(any(UserGitIdentity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(secretManager.createOrUpdateSecret(anyString(), anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> "gcp-sm://projects/pulse-prod-001/secrets/"
                        + inv.getArgument(1) + "/versions/latest");
        service = new UserGitIdentityService(identityRepo, secretManager, provider, tenantService);
    }

    private CallerContext caller() {
        return new CallerContext(USER, TENANT,
                Set.of(PulseRole.PIPELINE_DEVELOPER), CallerSurface.UI);
    }

    @Test
    @DisplayName("Failed rotation: existing row stays VALID, prior reference unchanged, prior secret NOT disabled")
    void failedRotationKeepsActiveCredential() {
        UserGitIdentity existing = new UserGitIdentity();
        existing.setId("id-1");
        existing.setTenantId(TENANT);
        existing.setPulseUserId(USER);
        existing.setProvider("GITHUB");
        existing.setCredentialReference(PRIOR_REFERENCE);
        existing.setStatus("VALID");
        existing.setVerifiedAt(java.time.Instant.parse("2026-04-01T00:00:00Z"));
        when(identityRepo.findByTenantIdAndPulseUserIdAndProvider(TENANT, USER, "GITHUB"))
                .thenReturn(Optional.of(existing));
        // The new token is rejected.
        when(provider.validateToken(eq("ghp_new_invalid"), any())).thenReturn(
                GitProviderAdapter.ValidationResult.deny(
                        GitHubPatValidationStatus.INVALID_TOKEN, "GitHub said no."));

        MaskedGitIdentity masked = service.rotate(caller(),
                new UserGitIdentityService.RotateRequest("ghp_new_invalid", null));

        // Active credential reference UNCHANGED.
        assertEquals(PRIOR_REFERENCE, existing.getCredentialReference(),
                "Failed rotation must NOT change the active credentialReference");
        // Active status STILL VALID — downstream readers must not see
        // INVALID_TOKEN here.
        assertEquals("VALID", existing.getStatus(),
                "Failed rotation must NOT clobber the active row.status from VALID");
        assertEquals("VALID", masked.status(),
                "Masked response must continue to surface VALID");
        // Prior secret was NOT disabled — neither via the legacy
        // env-based path nor via the closeout reference path.
        verify(secretManager, never()).disableSecret(anyString(), anyString());
        verify(secretManager, never()).disableSecretByReference(anyString());
        // Failed-rotation diagnostic IS surfaced — the user can see what went wrong.
        assertNotNull(masked.lastRotationAttemptStatus(),
                "Masked response must record the failed-rotation status");
        assertEquals("INVALID_TOKEN", masked.lastRotationAttemptStatus());
        assertEquals("GitHub said no.", masked.lastRotationAttemptError());
        assertNotNull(masked.lastRotationAttemptAt());
        // verifiedAt unchanged (no successful rotation happened).
        assertEquals(java.time.Instant.parse("2026-04-01T00:00:00Z"), masked.verifiedAt());
    }

    @Test
    @DisplayName("Successful rotation clears the failed-rotation diagnostic from the prior attempt")
    void successfulRotationClearsPriorFailedDiagnostic() {
        UserGitIdentity existing = new UserGitIdentity();
        existing.setId("id-1");
        existing.setTenantId(TENANT);
        existing.setPulseUserId(USER);
        existing.setProvider("GITHUB");
        existing.setCredentialReference(PRIOR_REFERENCE);
        existing.setStatus("VALID");
        // Prior failed-rotation noise on the row.
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("lastRotationAttemptStatus", "INVALID_TOKEN");
        meta.put("lastRotationAttemptError", "old failure");
        meta.put("lastRotationAttemptAt", "2026-04-01T00:00:00Z");
        existing.setMetadata(meta);
        when(identityRepo.findByTenantIdAndPulseUserIdAndProvider(TENANT, USER, "GITHUB"))
                .thenReturn(Optional.of(existing));
        when(provider.validateToken(eq("ghp_new_valid"), any())).thenReturn(
                GitProviderAdapter.ValidationResult.valid("repo", "mrivera"));

        MaskedGitIdentity masked = service.rotate(caller(),
                new UserGitIdentityService.RotateRequest("ghp_new_valid", null));

        assertEquals("VALID", masked.status());
        assertNull(masked.lastRotationAttemptStatus(),
                "Successful rotation must clear the prior failed-rotation diagnostic");
        assertNull(masked.lastRotationAttemptError());
        assertNull(masked.lastRotationAttemptAt());
        // And the prior secret IS disabled — through the closeout
        // reference path, with the FULL prior gcp-sm:// URI (project +
        // pinned version preserved). The legacy env-based path must
        // NOT be invoked for user PAT refs.
        verify(secretManager).disableSecretByReference(PRIOR_REFERENCE);
        verify(secretManager, never()).disableSecret(anyString(), anyString());
    }

    @Test
    @DisplayName("Repeated failed rotations don't snowball — each writes the same diagnostic shape")
    void repeatedFailedRotationDoesNotSnowball() {
        UserGitIdentity existing = new UserGitIdentity();
        existing.setId("id-1");
        existing.setTenantId(TENANT);
        existing.setPulseUserId(USER);
        existing.setProvider("GITHUB");
        existing.setCredentialReference(PRIOR_REFERENCE);
        existing.setStatus("VALID");
        when(identityRepo.findByTenantIdAndPulseUserIdAndProvider(TENANT, USER, "GITHUB"))
                .thenReturn(Optional.of(existing));
        when(provider.validateToken(eq("ghp_x"), any())).thenReturn(
                GitProviderAdapter.ValidationResult.deny(
                        GitHubPatValidationStatus.PROVIDER_UNAVAILABLE, "503 from GitHub"));

        service.rotate(caller(), new UserGitIdentityService.RotateRequest("ghp_x", null));
        service.rotate(caller(), new UserGitIdentityService.RotateRequest("ghp_x", null));

        // Status still VALID after multiple failed rotations.
        assertEquals("VALID", existing.getStatus());
        // Prior secret never touched — neither legacy nor reference path.
        verify(secretManager, never()).disableSecret(anyString(), anyString());
        verify(secretManager, never()).disableSecretByReference(anyString());
        // Diagnostic reflects the latest attempt only (no list growth).
        assertEquals("PROVIDER_UNAVAILABLE", existing.getMetadata().get("lastRotationAttemptStatus"));
        assertTrue(existing.getMetadata().toString().length() < 500,
                "metadata must not snowball; got: " + existing.getMetadata());
    }
}
