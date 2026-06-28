package com.pulse.secret.service;

import com.pulse.auth.model.TenantGcpCredential;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.config.GcpEnvironmentConfig;
import com.pulse.secret.model.SecretAuthorityMode;
import com.pulse.secret.model.SecretAuthorityReadiness;
import com.pulse.secret.model.SecretAuthorityReadiness.ProofStatus;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecretAuthorityReadinessServiceTest {

    private final TenantGcpCredentialService credentialService = mock(TenantGcpCredentialService.class);
    private final TenantGcpRuntimeTopologyRepository topologyRepository =
            mock(TenantGcpRuntimeTopologyRepository.class);

    // ------------------------------------------------------------------------
    // Mode resolution
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Mode Resolution")
    class ModeResolution {

        @Test
        void localStubMode_resolves_to_LOCAL_STUB() {
            var service = service("local-stub");
            assertThat(service.resolveMode()).isEqualTo(SecretAuthorityMode.LOCAL_STUB);
        }

        @Test
        void localStubMode_caseInsensitive() {
            var service = service("LOCAL-STUB");
            assertThat(service.resolveMode()).isEqualTo(SecretAuthorityMode.LOCAL_STUB);
        }

        @Test
        void gcpMode_resolves_to_TENANT_GCP_SECRET_MANAGER() {
            var service = service("gcp");
            assertThat(service.resolveMode()).isEqualTo(SecretAuthorityMode.TENANT_GCP_SECRET_MANAGER);
        }

        @Test
        void gcpSecretManagerMode_resolves_to_TENANT_GCP_SECRET_MANAGER() {
            var service = service("gcp-secret-manager");
            assertThat(service.resolveMode()).isEqualTo(SecretAuthorityMode.TENANT_GCP_SECRET_MANAGER);
        }

        @Test
        void nullMode_resolves_to_BLOCKED() {
            var service = service(null);
            assertThat(service.resolveMode()).isEqualTo(SecretAuthorityMode.BLOCKED);
        }

        @Test
        void blankMode_resolves_to_BLOCKED() {
            var service = service("  ");
            assertThat(service.resolveMode()).isEqualTo(SecretAuthorityMode.BLOCKED);
        }

        @Test
        void unknownMode_resolves_to_BLOCKED() {
            var service = service("hashicorp-vault");
            assertThat(service.resolveMode()).isEqualTo(SecretAuthorityMode.BLOCKED);
        }
    }

    // ------------------------------------------------------------------------
    // Local-stub readiness (NON_PROOF)
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Local-Stub Readiness (NON_PROOF)")
    class LocalStubReadiness {

        @Test
        void localStub_returns_NON_PROOF_status() {
            var service = service("local-stub");
            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");

            assertThat(readiness.mode()).isEqualTo(SecretAuthorityMode.LOCAL_STUB);
            assertThat(readiness.proofStatus()).isEqualTo(ProofStatus.NON_PROOF);
            assertThat(readiness.isReady()).isFalse();
        }

        @Test
        void localStub_credentialSource_is_local_stub_encrypted_disk() {
            var service = service("local-stub");
            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");

            assertThat(readiness.credentialSource()).isEqualTo("local_stub_encrypted_disk");
        }

        @Test
        void localStub_validationCategory_is_NON_PROOF_LOCAL() {
            var service = service("local-stub");
            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");

            assertThat(readiness.validationCategory()).isEqualTo("NON_PROOF_LOCAL");
        }

        @Test
        void localStub_context_shows_gcpBackedProof_false() {
            var service = service("local-stub");
            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");

            assertThat(readiness.redactedContext())
                    .containsEntry("gcpBackedProof", false)
                    .containsEntry("ambientAuthAccepted", false)
                    .containsEntry("secretManagerMode", "local-stub");
        }

        @Test
        void localStub_isReady_returns_false_for_realWorld_gcpBacked_scenario() {
            var service = service("local-stub");
            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");

            // Local-stub is explicitly non-proof for real-world GCP-backed scenario
            assertThat(readiness.isReady()).isFalse();
            assertThat(readiness.proofStatus()).isNotEqualTo(ProofStatus.PROVEN);
        }
    }

    // ------------------------------------------------------------------------
    // Tenant GCP Secret Manager readiness (PROVEN)
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Tenant GCP Secret Manager Readiness (PROVEN)")
    class TenantGcpSmReadiness {

        @Test
        void tenantGcpSm_withActiveCredential_returns_PROVEN() {
            var service = service("gcp");
            TenantGcpCredential cred = activeTenantCredential();
            when(credentialService.getCredentialEntity("tenant-acme"))
                    .thenReturn(Optional.of(cred));

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");

            assertThat(readiness.mode()).isEqualTo(SecretAuthorityMode.TENANT_GCP_SECRET_MANAGER);
            assertThat(readiness.proofStatus()).isEqualTo(ProofStatus.PROVEN);
            assertThat(readiness.isReady()).isTrue();
        }

        @Test
        void tenantGcpSm_readback_includes_redacted_metadata_without_secrets() {
            var service = service("gcp");
            TenantGcpCredential cred = activeTenantCredential();
            when(credentialService.getCredentialEntity("tenant-acme"))
                    .thenReturn(Optional.of(cred));

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");
            Map<String, Object> ctx = readiness.redactedContext();

            assertThat(ctx).containsEntry("gcpProjectId", "pulse-dev-project");
            assertThat(ctx).containsEntry("serviceAccountEmail", "sa@pulse-dev-project.iam.gserviceaccount.com");
            assertThat(ctx).containsEntry("keyId", "key-id-abc123");
            assertThat(ctx).containsEntry("credentialStatus", "active");
            assertThat(ctx).containsEntry("privateKeyRedacted", true);
            assertThat(ctx).containsEntry("gcpBackedProof", true);
            assertThat(ctx).containsEntry("ambientAuthAccepted", false);

            // Verify no secret material in the readback
            assertThat(ctx.values().stream()
                    .filter(v -> v instanceof String)
                    .map(Object::toString)
                    .noneMatch(s -> s.contains("PRIVATE KEY") || s.contains("BEGIN RSA")))
                    .isTrue();
        }

        @Test
        void tenantGcpSm_credentialSource_is_tenant_gcp_secret_manager() {
            var service = service("gcp");
            when(credentialService.getCredentialEntity("tenant-acme"))
                    .thenReturn(Optional.of(activeTenantCredential()));

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");
            assertThat(readiness.credentialSource()).isEqualTo("tenant_gcp_secret_manager");
        }

        @Test
        void tenantGcpSm_validationCategory_is_GCP_SM_TENANT_CREDENTIAL() {
            var service = service("gcp");
            when(credentialService.getCredentialEntity("tenant-acme"))
                    .thenReturn(Optional.of(activeTenantCredential()));

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");
            assertThat(readiness.validationCategory()).isEqualTo("GCP_SM_TENANT_CREDENTIAL");
        }

        @Test
        void tenantGcpSm_noCredential_returns_BLOCKED() {
            var service = service("gcp");
            when(credentialService.getCredentialEntity("tenant-missing"))
                    .thenReturn(Optional.empty());

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-missing");

            assertThat(readiness.mode()).isEqualTo(SecretAuthorityMode.BLOCKED);
            assertThat(readiness.proofStatus()).isEqualTo(ProofStatus.BLOCKED);
            assertThat(readiness.isReady()).isFalse();
        }

        @Test
        void tenantGcpSm_inactiveCredential_returns_BLOCKED() {
            var service = service("gcp");
            TenantGcpCredential cred = activeTenantCredential();
            cred.setStatus("revoked");
            when(credentialService.getCredentialEntity("tenant-acme"))
                    .thenReturn(Optional.of(cred));

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");

            assertThat(readiness.mode()).isEqualTo(SecretAuthorityMode.BLOCKED);
            assertThat(readiness.proofStatus()).isEqualTo(ProofStatus.BLOCKED);
            assertThat(readiness.isReady()).isFalse();
        }
    }

    // ------------------------------------------------------------------------
    // Fail-closed: GCP mode without tenant credential never reports PROVEN
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Fail-Closed: GCP Mode Without Credential Never PROVEN")
    class FailClosedGcpModeWithoutCredential {

        @Test
        void gcpMode_withoutTenantCredential_is_BLOCKED_not_PROVEN() {
            // This is the core fail-closed invariant: mode=gcp does NOT imply PROVEN.
            // Proof requires an active tenant credential entity.
            var service = service("gcp");
            when(credentialService.getCredentialEntity("tenant-no-cred"))
                    .thenReturn(Optional.empty());

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-no-cred");

            assertThat(readiness.proofStatus()).isEqualTo(ProofStatus.BLOCKED);
            assertThat(readiness.proofStatus()).isNotEqualTo(ProofStatus.PROVEN);
            assertThat(readiness.isReady()).isFalse();
        }

        @Test
        void gcpMode_withRevokedCredential_is_BLOCKED_not_PROVEN() {
            var service = service("gcp");
            TenantGcpCredential cred = activeTenantCredential();
            cred.setStatus("revoked");
            when(credentialService.getCredentialEntity("tenant-revoked"))
                    .thenReturn(Optional.of(cred));

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-revoked");

            assertThat(readiness.proofStatus()).isNotEqualTo(ProofStatus.PROVEN);
            assertThat(readiness.isReady()).isFalse();
        }

        @Test
        void gcpMode_withExpiredCredential_is_BLOCKED_not_PROVEN() {
            var service = service("gcp");
            TenantGcpCredential cred = activeTenantCredential();
            cred.setStatus("expired");
            when(credentialService.getCredentialEntity("tenant-expired"))
                    .thenReturn(Optional.of(cred));

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-expired");

            assertThat(readiness.proofStatus()).isNotEqualTo(ProofStatus.PROVEN);
            assertThat(readiness.isReady()).isFalse();
        }

        @Test
        void onlyActiveCredential_can_produce_PROVEN() {
            // Exhaustive: only "active" status yields PROVEN
            var service = service("gcp");
            when(credentialService.getCredentialEntity("tenant-acme"))
                    .thenReturn(Optional.of(activeTenantCredential()));

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");
            assertThat(readiness.proofStatus()).isEqualTo(ProofStatus.PROVEN);
        }
    }

    // ------------------------------------------------------------------------
    // Connector-instance enrichment
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Connector Instance Context Enrichment")
    class ConnectorInstanceEnrichment {

        @Test
        void computeForConnectorInstance_includes_ciId_and_environment() {
            var service = service("local-stub");

            SecretAuthorityReadiness readiness = service.computeForConnectorInstance(
                    "tenant-acme", "ci-oracle-1", "integration");

            assertThat(readiness.redactedContext())
                    .containsEntry("connectorInstanceId", "ci-oracle-1")
                    .containsEntry("environment", "integration");
        }
    }

    // ------------------------------------------------------------------------
    // Negative: Ambient gcloud/env auth rejection
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Negative: Ambient Auth Rejection")
    class AmbientAuthRejection {

        @Test
        void ambientAuth_is_never_sufficient_for_proof_in_localStub() {
            var service = service("local-stub");
            assertThat(service.isAmbientAuthSufficientForProof()).isFalse();
        }

        @Test
        void ambientAuth_is_never_sufficient_for_proof_in_gcpMode() {
            var service = service("gcp");
            assertThat(service.isAmbientAuthSufficientForProof()).isFalse();
        }

        @Test
        void ambientAuth_is_never_sufficient_for_proof_in_blockedMode() {
            var service = service(null);
            assertThat(service.isAmbientAuthSufficientForProof()).isFalse();
        }

        @Test
        void localStub_readiness_explicitly_rejects_ambient_auth() {
            var service = service("local-stub");
            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-x");
            assertThat(readiness.redactedContext()).containsEntry("ambientAuthAccepted", false);
        }

        @Test
        void gcpSm_readiness_explicitly_rejects_ambient_auth() {
            var service = service("gcp");
            when(credentialService.getCredentialEntity("tenant-x"))
                    .thenReturn(Optional.of(activeTenantCredential()));

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-x");
            assertThat(readiness.redactedContext()).containsEntry("ambientAuthAccepted", false);
        }
    }

    // ------------------------------------------------------------------------
    // Negative: Local-stub GCP-SM reference cannot satisfy GCP-backed readiness
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Negative: Local-Stub Reference Cannot Satisfy GCP Readiness")
    class LocalStubReferenceRejection {

        @Test
        void localStub_gcpSmReference_cannot_satisfy_gcpReadiness() {
            var service = service("local-stub");
            String fakeRef = "gcp-sm://projects/pulse-dev/secrets/my-secret/versions/latest";
            assertThat(service.isLocalStubReferenceSufficientForGcpReadiness(fakeRef)).isFalse();
        }

        @Test
        void localStub_nullReference_cannot_satisfy_gcpReadiness() {
            var service = service("local-stub");
            assertThat(service.isLocalStubReferenceSufficientForGcpReadiness(null)).isFalse();
        }

        @Test
        void gcpMode_reference_still_returns_false_for_this_check() {
            // This method specifically tests whether a local-stub-backed reference
            // can satisfy GCP readiness. In gcp mode it's also false because the
            // method tests the local-stub scenario explicitly.
            var service = service("gcp");
            String ref = "gcp-sm://projects/pulse-dev/secrets/my-secret/versions/latest";
            assertThat(service.isLocalStubReferenceSufficientForGcpReadiness(ref)).isFalse();
        }
    }

    // ------------------------------------------------------------------------
    // Redacted readback contract tests
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Redacted Readback Contract")
    class RedactedReadbackContract {

        @Test
        void readback_never_contains_private_key_material() {
            var service = service("gcp");
            TenantGcpCredential cred = activeTenantCredential();
            when(credentialService.getCredentialEntity("tenant-acme"))
                    .thenReturn(Optional.of(cred));

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");

            // Serialize all context values and verify no secret leakage
            String serialized = readiness.redactedContext().toString();
            assertThat(serialized).doesNotContain("PRIVATE KEY");
            assertThat(serialized).doesNotContain("BEGIN RSA");
            assertThat(serialized).doesNotContain("private_key");
            assertThat(serialized).doesNotContain("-----BEGIN");
        }

        @Test
        void readback_includes_secretAuthority_credentialSource_validationMode() {
            var service = service("gcp");
            when(credentialService.getCredentialEntity("tenant-acme"))
                    .thenReturn(Optional.of(activeTenantCredential()));

            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");

            // The three required fields per packet evidence
            assertThat(readiness.mode()).isNotNull();
            assertThat(readiness.credentialSource()).isNotNull().isNotBlank();
            assertThat(readiness.validationCategory()).isNotNull().isNotBlank();
        }

        @Test
        void localStub_readback_has_no_tenant_credentials_metadata() {
            var service = service("local-stub");
            SecretAuthorityReadiness readiness = service.computeForTenant("tenant-acme");

            // Local-stub never shows tenant credential metadata since it has none
            assertThat(readiness.redactedContext())
                    .doesNotContainKey("gcpProjectId")
                    .doesNotContainKey("serviceAccountEmail")
                    .doesNotContainKey("keyId");
        }
    }

    // ------------------------------------------------------------------------
    // PKT-FINAL-5 / BUG-54: Per-tenant mode override precedence
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Per-Tenant Mode Override Precedence")
    class PerTenantModeOverride {

        @Test
        void perTenantMode_takesPrecedence_overGlobalDefault() {
            // Global mode is local-stub, but tenant has opted into GCP_SECRET_MANAGER.
            GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
            cfg.setSecretManagerMode("local-stub");
            TenantGcpRuntimeTopology topology = new TenantGcpRuntimeTopology();
            topology.setSecretAuthorityMode("GCP_SECRET_MANAGER");
            when(topologyRepository.findByTenantId("tenant-opted-in"))
                    .thenReturn(Optional.of(topology));

            SecretAuthorityReadinessService svc = new SecretAuthorityReadinessService(
                    cfg, credentialService, topologyRepository);

            assertThat(svc.resolveMode("tenant-opted-in"))
                    .isEqualTo(SecretAuthorityMode.TENANT_GCP_SECRET_MANAGER);
        }

        @Test
        void missingTopologyRow_fallsBack_toGlobalMode() {
            GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
            cfg.setSecretManagerMode("gcp-secret-manager");
            when(topologyRepository.findByTenantId("tenant-no-binding"))
                    .thenReturn(Optional.empty());

            SecretAuthorityReadinessService svc = new SecretAuthorityReadinessService(
                    cfg, credentialService, topologyRepository);

            assertThat(svc.resolveMode("tenant-no-binding"))
                    .isEqualTo(SecretAuthorityMode.TENANT_GCP_SECRET_MANAGER);
        }

        @Test
        void perTenantLocalStub_overrides_globalGcpMode() {
            // Global says gcp, but the tenant explicitly wants local-stub.
            GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
            cfg.setSecretManagerMode("gcp");
            TenantGcpRuntimeTopology topology = new TenantGcpRuntimeTopology();
            topology.setSecretAuthorityMode("LOCAL_STUB");
            when(topologyRepository.findByTenantId("tenant-prefers-local"))
                    .thenReturn(Optional.of(topology));

            SecretAuthorityReadinessService svc = new SecretAuthorityReadinessService(
                    cfg, credentialService, topologyRepository);

            assertThat(svc.resolveMode("tenant-prefers-local"))
                    .isEqualTo(SecretAuthorityMode.LOCAL_STUB);
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private SecretAuthorityReadinessService service(String mode) {
        GcpEnvironmentConfig cfg = new GcpEnvironmentConfig();
        cfg.setSecretManagerMode(mode);
        // Default tests assume no per-tenant override — the topology repo
        // returns empty so global mode applies (back-compat path).
        return new SecretAuthorityReadinessService(cfg, credentialService, topologyRepository);
    }

    private TenantGcpCredential activeTenantCredential() {
        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setTenantId("tenant-acme");
        cred.setControlPlaneProjectId("pulse-dev-project");
        cred.setServiceAccountEmail("sa@pulse-dev-project.iam.gserviceaccount.com");
        cred.setKeyId("key-id-abc123");
        cred.setEncryptedCredential("encrypted-blob-never-returned");
        cred.setStatus("active");
        return cred;
    }
}
