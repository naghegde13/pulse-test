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
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6 — UserGitIdentityService contracts.
 *
 * <p>Pins:
 * <ul>
 *   <li>The PAT value flows ONLY into {@link GcpSecretManagerService}; the
 *       persisted {@link UserGitIdentity} row stores a {@code gcp-sm://}
 *       reference, never plaintext.</li>
 *   <li>Caller-supplied identity / credentialReference / secretId fields
 *       on the request body are not honored — the request record has no
 *       such fields, and the service rejects calls without a resolved
 *       actor.</li>
 *   <li>Read APIs return only {@link MaskedGitIdentity}; the
 *       credentialReferenceMasked field never includes the full secret id.</li>
 *   <li>Rotation: verify-before-disable. A failed validation leaves the
 *       prior credential reference active and surfaces a deny status.</li>
 *   <li>Revocation: GSM secret disabled and status flips to REVOKED;
 *       the row stays for audit.</li>
 *   <li>Provider validation deny states (INVALID_TOKEN, INSUFFICIENT_SCOPE,
 *       REPO_ACCESS_DENIED, PROVIDER_UNAVAILABLE, EXPIRED) all surface
 *       on the row's status field.</li>
 * </ul>
 */
class GitHubPatCredentialFlowTest {

    private UserGitIdentityRepository identityRepo;
    private GcpSecretManagerService secretManager;
    private GitProviderAdapter provider;
    private TenantService tenantService;
    private UserGitIdentityService service;

    private static final String TENANT = "tenant-A";
    private static final String USER = "user-mike";

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
                .thenAnswer(inv -> "gcp-sm://projects/pulse-dev/secrets/"
                        + inv.getArgument(1) + "/versions/latest");
        service = new UserGitIdentityService(identityRepo, secretManager, provider, tenantService);
    }

    private CallerContext caller() {
        return new CallerContext(USER, TENANT,
                Set.of(PulseRole.PIPELINE_DEVELOPER), CallerSurface.UI);
    }

    @Test
    @DisplayName("register stores ONLY a gcp-sm:// reference; PAT flows through GSM, not the row")
    void registerStoresOnlyReference() {
        when(provider.validateToken(eq("ghp_secret"), any())).thenReturn(
                GitProviderAdapter.ValidationResult.valid("repo,read:user", "mrivera"));
        when(identityRepo.findByTenantIdAndPulseUserIdAndProvider(TENANT, USER, "GITHUB"))
                .thenReturn(Optional.empty());

        MaskedGitIdentity masked = service.register(caller(), new UserGitIdentityService.RegisterRequest(
                "ghp_secret", "mrivera", "Mike Rivera", "mike@home-lending.com",
                null, "https://github.com/acme/loans"));

        // GSM was called with the PAT value once, and the persisted row
        // got the returned gcp-sm:// reference.
        verify(secretManager, times(1)).createOrUpdateSecret(anyString(), anyString(),
                eq("ghp_secret"), anyMap());
        ArgumentCaptor<UserGitIdentity> captor = ArgumentCaptor.forClass(UserGitIdentity.class);
        verify(identityRepo, times(2)).save(captor.capture());
        UserGitIdentity saved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertTrue(saved.getCredentialReference().startsWith("gcp-sm://"),
                "credential_reference must be a gcp-sm:// URI");
        // Plaintext token must NOT appear anywhere on the row.
        assertFalse(saved.getCredentialReference().contains("ghp_secret"));
        assertEquals("VALID", saved.getStatus());
        assertNotNull(saved.getVerifiedAt());

        // The masked view never contains the full secret id.
        assertNotNull(masked.credentialReferenceMasked());
        assertFalse(masked.credentialReferenceMasked().contains("/versions/latest"),
                "masked reference must not surface the full secret id, got: "
                        + masked.credentialReferenceMasked());
    }

    @Test
    @DisplayName("register without resolved actor is rejected — caller cannot spoof identity")
    void registerWithoutResolvedActorRejected() {
        var req = new UserGitIdentityService.RegisterRequest(
                "ghp_secret", "x", "X", "x@y", null, null);
        // Null caller → fail.
        assertThrows(IllegalArgumentException.class, () -> service.register(null, req));
        // Blank user id → fail.
        CallerContext blank = new CallerContext("", TENANT,
                Set.of(PulseRole.PIPELINE_DEVELOPER), CallerSurface.UI);
        assertThrows(IllegalArgumentException.class, () -> service.register(blank, req));
        // Blank tenant id → fail.
        CallerContext noTenant = new CallerContext(USER, "",
                Set.of(PulseRole.PIPELINE_DEVELOPER), CallerSurface.UI);
        assertThrows(IllegalArgumentException.class, () -> service.register(noTenant, req));
    }

    @Test
    @DisplayName("RegisterRequest record has no spoofable identity fields")
    void registerRequestRecordHasNoSpoofableFields() {
        // Phase 6 closeout: the request record's component list is the
        // single source of truth for what the wire accepts. Any
        // future addition of pulseUserId/credentialReference/secretId
        // would reintroduce a spoof vector — this test catches that.
        Set<String> wireFields = new java.util.HashSet<>();
        for (var component : UserGitIdentityService.RegisterRequest.class.getRecordComponents()) {
            wireFields.add(component.getName());
        }
        Set<String> forbidden = Set.of("pulseUserId", "credentialReference", "secretId",
                "userId", "tenantId");
        for (String f : forbidden) {
            assertFalse(wireFields.contains(f),
                    "RegisterRequest must NOT expose '" + f + "' — that would let callers spoof actor identity.");
        }
    }

    @Test
    @DisplayName("rotate verifies the new token BEFORE disabling the prior secret")
    void rotateVerifiesBeforeDisablingPrior() {
        UserGitIdentity existing = new UserGitIdentity();
        existing.setId("id-1");
        existing.setTenantId(TENANT);
        existing.setPulseUserId(USER);
        existing.setProvider("GITHUB");
        existing.setCredentialReference("gcp-sm://projects/pulse-dev/secrets/old-id/versions/latest");
        existing.setStatus("VALID");
        when(identityRepo.findByTenantIdAndPulseUserIdAndProvider(TENANT, USER, "GITHUB"))
                .thenReturn(Optional.of(existing));

        // Failed validation → prior secret must NOT be disabled.
        when(provider.validateToken(eq("ghp_new_invalid"), any())).thenReturn(
                GitProviderAdapter.ValidationResult.deny(
                        GitHubPatValidationStatus.INVALID_TOKEN, "GitHub said no."));

        MaskedGitIdentity masked = service.rotate(caller(),
                new UserGitIdentityService.RotateRequest("ghp_new_invalid", null));
        // Phase 6 closeout — failed rotation MUST keep the prior
        // credential active. The active row.status stays VALID; the
        // failed-rotation diagnostic surfaces in the dedicated field.
        assertEquals("VALID", masked.status());
        assertEquals("INVALID_TOKEN", masked.lastRotationAttemptStatus());
        // Original credentialReference unchanged.
        assertEquals("gcp-sm://projects/pulse-dev/secrets/old-id/versions/latest",
                existing.getCredentialReference());
        verify(secretManager, never()).disableSecret(anyString(), anyString());
        verify(secretManager, never()).disableSecretByReference(anyString());
    }

    @Test
    @DisplayName("rotate with a valid new token disables the prior secret and switches the reference")
    void rotateValidNewTokenSwitchesReference() {
        UserGitIdentity existing = new UserGitIdentity();
        existing.setId("id-1");
        existing.setTenantId(TENANT);
        existing.setPulseUserId(USER);
        existing.setProvider("GITHUB");
        existing.setCredentialReference("gcp-sm://projects/pulse-dev/secrets/old-id/versions/latest");
        existing.setStatus("VALID");
        when(identityRepo.findByTenantIdAndPulseUserIdAndProvider(TENANT, USER, "GITHUB"))
                .thenReturn(Optional.of(existing));
        when(provider.validateToken(eq("ghp_new_valid"), any())).thenReturn(
                GitProviderAdapter.ValidationResult.valid("repo", "mrivera"));

        MaskedGitIdentity masked = service.rotate(caller(),
                new UserGitIdentityService.RotateRequest("ghp_new_valid", null));
        assertEquals("VALID", masked.status());
        // Phase 6 closeout — disable routes through the FULL prior
        // gcp-sm:// URI, not the legacy env-based path.
        verify(secretManager, times(1)).disableSecretByReference(
                "gcp-sm://projects/pulse-dev/secrets/old-id/versions/latest");
        verify(secretManager, never()).disableSecret(anyString(), anyString());
        assertNotNull(existing.getLastRotatedAt());
        assertFalse(existing.getCredentialReference().contains("old-id"),
                "credentialReference must point at the new secret after rotation");
    }

    @Test
    @DisplayName("revoke disables GSM secret and flips status to REVOKED")
    void revokeDisablesAndFlips() {
        UserGitIdentity existing = new UserGitIdentity();
        existing.setId("id-1");
        existing.setTenantId(TENANT);
        existing.setPulseUserId(USER);
        existing.setProvider("GITHUB");
        existing.setCredentialReference("gcp-sm://projects/pulse-dev/secrets/old-id/versions/latest");
        existing.setStatus("VALID");
        when(identityRepo.findByTenantIdAndPulseUserIdAndProvider(TENANT, USER, "GITHUB"))
                .thenReturn(Optional.of(existing));

        MaskedGitIdentity masked = service.revoke(caller());
        assertEquals("REVOKED", masked.status());
        // Phase 6 closeout — revoke routes through the FULL gcp-sm://
        // URI, not the legacy env-based path.
        verify(secretManager, times(1)).disableSecretByReference(
                "gcp-sm://projects/pulse-dev/secrets/old-id/versions/latest");
        verify(secretManager, never()).disableSecret(anyString(), anyString());
        assertNotNull(existing.getRevokedAt());
    }

    @Test
    @DisplayName("Provider validation deny states all map to row.status with a stable enum value")
    void providerDenyStatesMapToRowStatus() {
        for (GitHubPatValidationStatus deny : new GitHubPatValidationStatus[]{
                GitHubPatValidationStatus.INVALID_TOKEN,
                GitHubPatValidationStatus.INSUFFICIENT_SCOPE,
                GitHubPatValidationStatus.REPO_ACCESS_DENIED,
                GitHubPatValidationStatus.REVOKED,
                GitHubPatValidationStatus.PROVIDER_UNAVAILABLE,
                GitHubPatValidationStatus.EXPIRED}) {
            when(identityRepo.findByTenantIdAndPulseUserIdAndProvider(TENANT, USER, "GITHUB"))
                    .thenReturn(Optional.empty());
            when(provider.validateToken(eq("ghp_x"), any())).thenReturn(
                    GitProviderAdapter.ValidationResult.deny(deny, "deny: " + deny));
            MaskedGitIdentity masked = service.register(caller(),
                    new UserGitIdentityService.RegisterRequest(
                            "ghp_x", null, null, null, null, null));
            assertEquals(deny.name(), masked.status(),
                    "deny state " + deny + " must surface on row status");
            assertNull(masked.verifiedAt(),
                    "verifiedAt must remain null for non-VALID status");
        }
    }

    @Test
    @DisplayName("getMasked returns the current row without leaking the secret id")
    void getMaskedDoesNotLeakSecretId() {
        UserGitIdentity existing = new UserGitIdentity();
        existing.setId("id-1");
        existing.setTenantId(TENANT);
        existing.setPulseUserId(USER);
        existing.setProvider("GITHUB");
        existing.setCredentialReference(
                "gcp-sm://projects/pulse-dev/secrets/pulse-tenant-a-user-mike-github-pat-abc123/versions/latest");
        existing.setStatus("VALID");
        when(identityRepo.findByTenantIdAndPulseUserIdAndProvider(TENANT, USER, "GITHUB"))
                .thenReturn(Optional.of(existing));

        Optional<MaskedGitIdentity> masked = service.getMasked(caller());
        assertTrue(masked.isPresent());
        String reference = masked.get().credentialReferenceMasked();
        assertNotNull(reference);
        assertTrue(reference.startsWith("gcp-sm://"));
        assertFalse(reference.contains("/versions/latest"),
                "masked reference must NOT include the full secret id with version suffix");
    }
}
