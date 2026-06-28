package com.pulse.git;

import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PulseRole;
import com.pulse.auth.service.TenantService;
import com.pulse.config.TenantConfig;
import com.pulse.git.identity.GitHubPatValidationStatus;
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
 * Phase 6 closeout — disable on rotation/revoke must honor the full
 * {@code gcp-sm://projects/<projectId>/secrets/<secretId>/versions/<version>}
 * URI of the row's prior {@code credentialReference}, not drop to the
 * hardcoded {@code disableSecret("dev", secretId)} path.
 *
 * <p>Without this fidelity, a tenant whose PAT lives in
 * {@code pulse-prod-001} (or pinned to version {@code 7}) would have
 * the rotation/revoke quietly target the wrong project's secret —
 * which means either no-op (best case) or disabling some unrelated
 * dev secret that happens to share the same secret id (worst case).
 *
 * <p>This file holds the contract pin. The earlier-phase tests
 * ({@code GitHubPatCredentialFlowTest},
 * {@code GitHubPatRotationFailureKeepsActiveTest}) keep their
 * pre-existing dev-only references; this file exercises the
 * cross-project / pinned-version surface explicitly.
 */
class UserGitIdentityDisableReferenceFidelityTest {

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
        // The new write happens in a different (tenant-correct) project;
        // the test only asserts on the disable surface, not the write.
        when(secretManager.createOrUpdateSecret(anyString(), anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> "gcp-sm://projects/pulse-prod-001/secrets/"
                        + inv.getArgument(1) + "/versions/latest");
        service = new UserGitIdentityService(identityRepo, secretManager, provider, tenantService);
    }

    private CallerContext caller() {
        return new CallerContext(USER, TENANT,
                Set.of(PulseRole.PIPELINE_DEVELOPER), CallerSurface.UI);
    }

    private UserGitIdentity rowWithReference(String reference) {
        UserGitIdentity row = new UserGitIdentity();
        row.setId("id-1");
        row.setTenantId(TENANT);
        row.setPulseUserId(USER);
        row.setProvider("GITHUB");
        row.setCredentialReference(reference);
        row.setStatus("VALID");
        when(identityRepo.findByTenantIdAndPulseUserIdAndProvider(TENANT, USER, "GITHUB"))
                .thenReturn(Optional.of(row));
        return row;
    }

    @Test
    @DisplayName("Successful rotation with prior non-dev project: disableSecretByReference gets the FULL prior URI")
    void successfulRotationDisablesByFullPriorReference() {
        // Prior credential lives in pulse-prod-001, version 7.
        String priorReference =
                "gcp-sm://projects/pulse-prod-001/secrets/pulse-tenant-a-user-mike-github-pat-old123/versions/7";
        rowWithReference(priorReference);
        when(provider.validateToken(eq("ghp_new_valid"), any())).thenReturn(
                GitProviderAdapter.ValidationResult.valid("repo", "mrivera"));

        service.rotate(caller(), new UserGitIdentityService.RotateRequest("ghp_new_valid", null));

        // The disable must receive the FULL prior reference (project +
        // pinned version preserved).
        verify(secretManager, times(1)).disableSecretByReference(priorReference);
        // Legacy env-based disable must NEVER be invoked for user PAT refs.
        verify(secretManager, never()).disableSecret(anyString(), anyString());
    }

    @Test
    @DisplayName("Revoke with non-dev / pinned-version reference routes through disableSecretByReference")
    void revokeUsesFullReference() {
        // Pinned version on a non-dev project — exactly the case the
        // legacy disableSecret("dev", id) path silently mishandled.
        String reference =
                "gcp-sm://projects/pulse-uat-099/secrets/pulse-tenant-a-user-mike-github-pat-uat42/versions/12";
        rowWithReference(reference);

        service.revoke(caller());

        verify(secretManager, times(1)).disableSecretByReference(reference);
        verify(secretManager, never()).disableSecret(anyString(), anyString());
    }

    @Test
    @DisplayName("No legacy env-based disableSecret call is ever made for user PAT refs (rotate or revoke)")
    void noLegacyDisableCallsForUserPatRefs() {
        // Cycle through several distinct references; the legacy method
        // must never see traffic from these flows.
        String[] refs = {
                "gcp-sm://projects/pulse-prod-001/secrets/s1/versions/1",
                "gcp-sm://projects/pulse-integration-002/secrets/s2/versions/latest",
                "gcp-sm://projects/pulse-uat-099/secrets/s3/versions/9"
        };
        for (String ref : refs) {
            rowWithReference(ref);
            when(provider.validateToken(eq("ghp_v"), any())).thenReturn(
                    GitProviderAdapter.ValidationResult.valid("repo", "mrivera"));
            service.rotate(caller(), new UserGitIdentityService.RotateRequest("ghp_v", null));
            service.revoke(caller());
        }
        verify(secretManager, never()).disableSecret(anyString(), anyString());
        // And every reference shows up exactly once on the by-reference
        // surface (rotate disables prior; revoke disables current — but
        // after rotate, the current is the NEW secret in pulse-prod-001
        // because the test's createOrUpdateSecret stub returns that).
        for (String ref : refs) {
            verify(secretManager, times(1)).disableSecretByReference(ref);
        }
    }

    @Test
    @DisplayName("Failed rotation with non-dev prior reference: prior secret is NOT disabled (verify-before-disable holds across projects)")
    void failedRotationKeepsCrossProjectPriorActive() {
        String priorReference =
                "gcp-sm://projects/pulse-prod-001/secrets/old/versions/7";
        UserGitIdentity row = rowWithReference(priorReference);
        when(provider.validateToken(eq("ghp_new_invalid"), any())).thenReturn(
                GitProviderAdapter.ValidationResult.deny(
                        GitHubPatValidationStatus.INVALID_TOKEN, "GitHub said no."));

        service.rotate(caller(), new UserGitIdentityService.RotateRequest("ghp_new_invalid", null));

        // Prior reference unchanged AND no disable of any kind.
        assertEquals(priorReference, row.getCredentialReference());
        verify(secretManager, never()).disableSecretByReference(anyString());
        verify(secretManager, never()).disableSecret(anyString(), anyString());
    }
}
