package com.pulse.secret.service;

import com.pulse.auth.model.TenantGcpCredential;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.config.GcpEnvironmentConfig;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.secret.model.SecretAuthorityMode;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the fail-closed proof status behavior in
 * CredentialReadinessService.compute(). Ensures that:
 * - GCP mode without tenant credential never reports PROVEN
 * - Absent tenantId always fails closed (BLOCKED, not PROVEN)
 * - Only active tenant credential + gcp mode yields PROVEN
 */
class CredentialReadinessProofStatusTest {

    private PipelineRepository pipelineRepo;
    private PipelineVersionRepository pipelineVersionRepo;
    private SubPipelineInstanceRepository instanceRepo;
    private ConnectorInstanceRepository ciRepo;
    private ConnectorDefinitionRepository connDefRepo;
    private SystemOfRecordRepository sorRepo;
    private CredentialProfileRepository credRepo;
    private TenantGcpCredentialService tenantCredentialService;

    @BeforeEach
    void setUp() {
        pipelineRepo = mock(PipelineRepository.class);
        pipelineVersionRepo = mock(PipelineVersionRepository.class);
        instanceRepo = mock(SubPipelineInstanceRepository.class);
        ciRepo = mock(ConnectorInstanceRepository.class);
        connDefRepo = mock(ConnectorDefinitionRepository.class);
        sorRepo = mock(SystemOfRecordRepository.class);
        credRepo = mock(CredentialProfileRepository.class);
        tenantCredentialService = mock(TenantGcpCredentialService.class);

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        when(pipelineRepo.findById("pipeline-1")).thenReturn(Optional.of(pipeline));
        when(pipelineVersionRepo.findFirstByPipelineIdOrderByCreatedAtDesc("pipeline-1"))
                .thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------------
    // Fail-closed: absent tenantId
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Absent TenantId Fails Closed")
    class AbsentTenantId {

        @Test
        void nullTenantId_gcpMode_reports_BLOCKED_not_PROVEN() {
            var svc = buildService("gcp");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", null);

            assertThat(result.get("secretAuthorityMode")).isEqualTo("TENANT_GCP_SECRET_MANAGER");
            assertThat(result.get("secretAuthorityProofStatus")).isEqualTo("BLOCKED");
        }

        @Test
        void blankTenantId_gcpMode_reports_BLOCKED_not_PROVEN() {
            var svc = buildService("gcp");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "  ");

            assertThat(result.get("secretAuthorityProofStatus")).isEqualTo("BLOCKED");
        }

        @Test
        void nullTenantId_localStub_reports_NON_PROOF() {
            var svc = buildService("local-stub");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", null);

            assertThat(result.get("secretAuthorityMode")).isEqualTo("LOCAL_STUB");
            assertThat(result.get("secretAuthorityProofStatus")).isEqualTo("NON_PROOF");
        }

        @Test
        void noArgOverload_alsoFailsClosed() {
            var svc = buildService("gcp");
            // The two-arg overload passes null tenantId internally
            Map<String, Object> result = svc.compute("pipeline-1", "dev");

            assertThat(result.get("secretAuthorityProofStatus")).isEqualTo("BLOCKED");
        }
    }

    // ------------------------------------------------------------------------
    // Fail-closed: GCP mode with missing/inactive credential
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("GCP Mode Without Active Credential Fails Closed")
    class GcpModeWithoutActiveCredential {

        @Test
        void gcpMode_noTenantCredential_reports_BLOCKED() {
            when(tenantCredentialService.getCredentialEntity("tenant-nocred"))
                    .thenReturn(Optional.empty());

            var svc = buildService("gcp");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-nocred");

            assertThat(result.get("secretAuthorityProofStatus")).isEqualTo("BLOCKED");
        }

        @Test
        void gcpMode_revokedCredential_reports_BLOCKED() {
            TenantGcpCredential cred = activeTenantCredential();
            cred.setStatus("revoked");
            when(tenantCredentialService.getCredentialEntity("tenant-revoked"))
                    .thenReturn(Optional.of(cred));

            var svc = buildService("gcp");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-revoked");

            assertThat(result.get("secretAuthorityProofStatus")).isEqualTo("BLOCKED");
        }
    }

    // ------------------------------------------------------------------------
    // Happy path: only active credential yields PROVEN
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Active Credential Yields PROVEN")
    class ActiveCredentialProven {

        @Test
        void gcpMode_activeCredential_reports_PROVEN() {
            when(tenantCredentialService.getCredentialEntity("tenant-good"))
                    .thenReturn(Optional.of(activeTenantCredential()));

            var svc = buildService("gcp");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-good");

            assertThat(result.get("secretAuthorityMode")).isEqualTo("TENANT_GCP_SECRET_MANAGER");
            assertThat(result.get("secretAuthorityProofStatus")).isEqualTo("PROVEN");
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private CredentialReadinessService buildService(String secretManagerMode) {
        GcpEnvironmentConfig gcpConfig = new GcpEnvironmentConfig();
        gcpConfig.setSecretManagerMode(secretManagerMode);
        SecretAuthorityReadinessService readinessService =
                new SecretAuthorityReadinessService(gcpConfig, tenantCredentialService, null);
        ConnectorCredentialStrategyClassifier strategyClassifier =
                new ConnectorCredentialStrategyClassifier(ciRepo, connDefRepo,
                        tenantCredentialService, mock(StorageScaffoldStatusRepository.class));

        return new CredentialReadinessService(
                pipelineRepo, pipelineVersionRepo, instanceRepo,
                ciRepo, connDefRepo, sorRepo, credRepo, readinessService,
                strategyClassifier);
    }

    private TenantGcpCredential activeTenantCredential() {
        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setTenantId("tenant-good");
        cred.setControlPlaneProjectId("pulse-dev-project");
        cred.setServiceAccountEmail("sa@pulse-dev-project.iam.gserviceaccount.com");
        cred.setKeyId("key-id-abc123");
        cred.setEncryptedCredential("encrypted-blob");
        cred.setStatus("active");
        return cred;
    }
}
