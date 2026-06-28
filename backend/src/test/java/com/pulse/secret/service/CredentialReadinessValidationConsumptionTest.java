package com.pulse.secret.service;

import com.pulse.auth.model.TenantGcpCredential;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.config.GcpEnvironmentConfig;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.CredentialStatus;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PKT-0016: Verifies that credential-readiness correctly consumes
 * validation-produced states (VALID, INVALID, BLOCKED, FAILED).
 * Only VALID credentials allow readiness to pass; all others block.
 */
class CredentialReadinessValidationConsumptionTest {

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

        PipelineVersion version = new PipelineVersion();
        version.setId("v-1");
        version.setPipelineId("pipeline-1");
        when(pipelineVersionRepo.findFirstByPipelineIdOrderByCreatedAtDesc("pipeline-1"))
                .thenReturn(Optional.of(version));

        SubPipelineInstance instance = new SubPipelineInstance();
        instance.setId("spi-1");
        instance.setVersionId("v-1");
        instance.setParams(Map.of("connector_instance_id", "ci-1"));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("v-1"))
                .thenReturn(List.of(instance));

        ConnectorInstance ci = new ConnectorInstance();
        ci.setId("ci-1");
        ci.setName("Test DB");
        ci.setConnectorDefinitionId("def-1");
        ci.setSorId("sor-1");
        when(ciRepo.findById("ci-1")).thenReturn(Optional.of(ci));

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-1");
        sor.setTenantId("tenant-1");
        sor.setMetadata(Map.of());
        when(sorRepo.findById("sor-1")).thenReturn(Optional.of(sor));
    }

    @Test
    @DisplayName("VALID credential (from validation) → readiness passes")
    void validCredentialAllowsReadiness() {
        stubCredential("ci-1", "dev", CredentialStatus.VALID, "LOCAL_STUB_VALID", null);
        var svc = buildService("local-stub");

        Map<String, Object> result = svc.compute("pipeline-1", "dev");

        assertThat(result.get("ready")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var connections = (List<Map<String, Object>>) result.get("connections");
        assertThat(connections).hasSize(1);
        assertThat(connections.get(0).get("status")).isEqualTo("VALID");
        assertThat(connections.get(0).get("validationCategory")).isEqualTo("LOCAL_STUB_VALID");
    }

    @Test
    @DisplayName("UNTESTED credential → readiness blocked")
    void untestedCredentialBlocksReadiness() {
        stubCredential("ci-1", "dev", CredentialStatus.UNTESTED, null, null);
        var svc = buildService("local-stub");

        Map<String, Object> result = svc.compute("pipeline-1", "dev");

        assertThat(result.get("ready")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        var connections = (List<Map<String, Object>>) result.get("connections");
        assertThat(connections.get(0).get("status")).isEqualTo("UNTESTED");
    }

    @Test
    @DisplayName("INVALID credential (from validation) → readiness blocked with reason")
    void invalidCredentialBlocksReadiness() {
        stubCredential("ci-1", "dev", CredentialStatus.INVALID,
                "SECRET_NOT_FOUND", "Secret reference for field 'password' does not exist");
        var svc = buildService("local-stub");

        Map<String, Object> result = svc.compute("pipeline-1", "dev");

        assertThat(result.get("ready")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        var connections = (List<Map<String, Object>>) result.get("connections");
        assertThat(connections.get(0).get("status")).isEqualTo("INVALID");
        assertThat((String) connections.get(0).get("reason"))
                .contains("password");
    }

    @Test
    @DisplayName("BLOCKED credential (from validation) → readiness blocked with reason")
    void blockedCredentialBlocksReadiness() {
        stubCredential("ci-1", "dev", CredentialStatus.BLOCKED,
                "SECRET_AUTHORITY_BLOCKED", "Secret authority mode is BLOCKED");
        var svc = buildService("local-stub");

        Map<String, Object> result = svc.compute("pipeline-1", "dev");

        assertThat(result.get("ready")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        var connections = (List<Map<String, Object>>) result.get("connections");
        assertThat(connections.get(0).get("status")).isEqualTo("BLOCKED");
        assertThat((String) connections.get(0).get("reason"))
                .contains("BLOCKED");
    }

    @Test
    @DisplayName("FAILED credential (from validation) → readiness blocked with reason")
    void failedCredentialBlocksReadiness() {
        stubCredential("ci-1", "dev", CredentialStatus.FAILED,
                "SECRET_ACCESS_ERROR", "Failed to verify secret: Connection refused");
        var svc = buildService("local-stub");

        Map<String, Object> result = svc.compute("pipeline-1", "dev");

        assertThat(result.get("ready")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        var connections = (List<Map<String, Object>>) result.get("connections");
        assertThat(connections.get(0).get("status")).isEqualTo("FAILED");
        assertThat((String) connections.get(0).get("reason"))
                .contains("Connection refused");
    }

    @Test
    @DisplayName("Readiness includes lastValidatedAt and validationCategory from validation")
    void readinessIncludesValidationMetadata() {
        CredentialProfile cred = new CredentialProfile();
        cred.setId("cred-1");
        cred.setConnectorInstanceId("ci-1");
        cred.setEnvironment("dev");
        cred.setStatus(CredentialStatus.VALID);
        cred.setValidationCategory("GCP_SM_VALID");
        cred.setLastValidatedAt(Instant.parse("2026-05-25T10:00:00Z"));
        when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-1", "dev"))
                .thenReturn(Optional.of(cred));
        var svc = buildService("local-stub");

        Map<String, Object> result = svc.compute("pipeline-1", "dev");

        @SuppressWarnings("unchecked")
        var connections = (List<Map<String, Object>>) result.get("connections");
        assertThat(connections.get(0).get("lastValidatedAt"))
                .isEqualTo("2026-05-25T10:00:00Z");
        assertThat(connections.get(0).get("validationCategory"))
                .isEqualTo("GCP_SM_VALID");
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void stubCredential(String ciId, String env, CredentialStatus status,
                                String validationCategory, String validationReason) {
        CredentialProfile cred = new CredentialProfile();
        cred.setId("cred-" + ciId);
        cred.setConnectorInstanceId(ciId);
        cred.setEnvironment(env);
        cred.setStatus(status);
        cred.setValidationCategory(validationCategory);
        cred.setValidationReason(validationReason);
        if (validationCategory != null) {
            cred.setLastValidatedAt(Instant.now());
        }
        when(credRepo.findByConnectorInstanceIdAndEnvironment(ciId, env))
                .thenReturn(Optional.of(cred));
    }

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
}
