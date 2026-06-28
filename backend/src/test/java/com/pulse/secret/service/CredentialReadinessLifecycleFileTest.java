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
import com.pulse.sor.model.ConnectorCredentialStrategy;
import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.ConnectorType;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.CredentialStatus;
import com.pulse.sor.model.ReleaseStage;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.storage.model.StorageScaffoldStatus;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PKT-0018: Tests that CredentialReadinessService correctly handles
 * lifecycle-file connectors (INHERIT_TENANT_GCP_SERVICE_ACCOUNT) vs
 * external connectors (CONNECTOR_SPECIFIC) in pipeline readiness.
 */
class CredentialReadinessLifecycleFileTest {

    private PipelineRepository pipelineRepo;
    private PipelineVersionRepository pipelineVersionRepo;
    private SubPipelineInstanceRepository instanceRepo;
    private ConnectorInstanceRepository ciRepo;
    private ConnectorDefinitionRepository connDefRepo;
    private SystemOfRecordRepository sorRepo;
    private CredentialProfileRepository credRepo;
    private TenantGcpCredentialService tenantCredentialService;
    private StorageScaffoldStatusRepository scaffoldRepo;

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
        scaffoldRepo = mock(StorageScaffoldStatusRepository.class);

        // Shared pipeline setup
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        when(pipelineRepo.findById("pipeline-1")).thenReturn(Optional.of(pipeline));

        PipelineVersion version = new PipelineVersion();
        version.setId("version-1");
        version.setPipelineId("pipeline-1");
        when(pipelineVersionRepo.findFirstByPipelineIdOrderByCreatedAtDesc("pipeline-1"))
                .thenReturn(Optional.of(version));
    }

    // -----------------------------------------------------------------------
    //  Lifecycle-file connector: ready when tenant GCP + scaffold ready
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Lifecycle-file connector readiness")
    class LifecycleFileReadiness {

        @Test
        @DisplayName("Lifecycle-file connector READY when GCP credential active and scaffold exists")
        void lifecycleFileReadyWhenGcpAndScaffoldReady() {
            stubSubPipelineWithConnector("version-1", "ci-s3");
            stubS3ConnectorInstance("ci-s3", "sor-1");
            stubSor("sor-1", "tenant-1");
            stubActiveTenantCredential("tenant-1");
            stubScaffold("tenant-1", "previewed");

            var svc = buildService("local-stub");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-1");

            List<Map<String, Object>> connections = getConnections(result);
            assertThat(connections).hasSize(1);
            Map<String, Object> conn = connections.get(0);
            assertThat(conn.get("credentialStrategy")).isEqualTo("INHERIT_TENANT_GCP_SERVICE_ACCOUNT");
            assertThat(conn.get("status")).isEqualTo("READY");
            assertThat(conn.get("gcpCredentialStatus")).isEqualTo("active");
            assertThat(conn.get("storageScaffoldStatus")).isEqualTo("ready");
        }

        @Test
        @DisplayName("Lifecycle-file BLOCKED when GCP credential missing")
        void lifecycleFileBlockedWhenGcpMissing() {
            stubSubPipelineWithConnector("version-1", "ci-s3");
            stubS3ConnectorInstance("ci-s3", "sor-1");
            stubSor("sor-1", "tenant-1");
            when(tenantCredentialService.getCredentialEntity("tenant-1"))
                    .thenReturn(Optional.empty());
            stubScaffold("tenant-1", "previewed");

            var svc = buildService("local-stub");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-1");

            List<Map<String, Object>> connections = getConnections(result);
            assertThat(connections.get(0).get("status")).isEqualTo("BLOCKED");
            assertThat(connections.get(0).get("gcpCredentialStatus")).isEqualTo("missing");
            assertThat(result.get("ready")).isEqualTo(false);
        }

        @Test
        @DisplayName("Lifecycle-file BLOCKED when scaffold missing")
        void lifecycleFileBlockedWhenScaffoldMissing() {
            stubSubPipelineWithConnector("version-1", "ci-s3");
            stubS3ConnectorInstance("ci-s3", "sor-1");
            stubSor("sor-1", "tenant-1");
            stubActiveTenantCredential("tenant-1");
            when(scaffoldRepo.findByTenantId("tenant-1")).thenReturn(List.of());

            var svc = buildService("local-stub");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-1");

            List<Map<String, Object>> connections = getConnections(result);
            assertThat(connections.get(0).get("status")).isEqualTo("BLOCKED");
            assertThat(connections.get(0).get("storageScaffoldStatus")).isEqualTo("missing");
            assertThat(result.get("ready")).isEqualTo(false);
        }

        @Test
        @DisplayName("Lifecycle-file resolves tenantId from SOR when not provided")
        void lifecycleFileResolvesTenantFromSor() {
            stubSubPipelineWithConnector("version-1", "ci-s3");
            stubS3ConnectorInstance("ci-s3", "sor-1");
            stubSor("sor-1", "tenant-1");
            stubActiveTenantCredential("tenant-1");
            stubScaffold("tenant-1", "executed");

            var svc = buildService("local-stub");
            // No tenantId in the call — should resolve from SOR
            Map<String, Object> result = svc.compute("pipeline-1", "dev");

            List<Map<String, Object>> connections = getConnections(result);
            assertThat(connections.get(0).get("status")).isEqualTo("READY");
        }

        @Test
        @DisplayName("Readback includes credentialStrategy field")
        void readbackIncludesCredentialStrategy() {
            stubSubPipelineWithConnector("version-1", "ci-s3");
            stubS3ConnectorInstance("ci-s3", "sor-1");
            stubSor("sor-1", "tenant-1");
            stubActiveTenantCredential("tenant-1");
            stubScaffold("tenant-1", "previewed");

            var svc = buildService("local-stub");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-1");

            List<Map<String, Object>> connections = getConnections(result);
            assertThat(connections.get(0)).containsKey("credentialStrategy");
            assertThat(connections.get(0).get("credentialStrategy"))
                    .isEqualTo("INHERIT_TENANT_GCP_SERVICE_ACCOUNT");
        }
    }

    // -----------------------------------------------------------------------
    //  External connector: still requires connector-specific validation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("External connector still requires credential profile")
    class ExternalConnectorValidation {

        @Test
        @DisplayName("External connector with VALID credential is ready")
        void externalWithValidCredentialReady() {
            stubSubPipelineWithConnector("version-1", "ci-pg");
            stubPostgresConnectorInstance("ci-pg", "sor-1");
            stubSor("sor-1", "tenant-1");
            stubCredentialProfile("ci-pg", "dev", CredentialStatus.VALID);

            var svc = buildService("local-stub");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-1");

            List<Map<String, Object>> connections = getConnections(result);
            assertThat(connections.get(0).get("credentialStrategy")).isEqualTo("CONNECTOR_SPECIFIC");
            assertThat(connections.get(0).get("status")).isEqualTo("VALID");
            assertThat(result.get("ready")).isEqualTo(true);
        }

        @Test
        @DisplayName("External connector with MISSING credential is not ready")
        void externalWithMissingCredentialNotReady() {
            stubSubPipelineWithConnector("version-1", "ci-pg");
            stubPostgresConnectorInstance("ci-pg", "sor-1");
            stubSor("sor-1", "tenant-1");
            when(credRepo.findByConnectorInstanceIdAndEnvironment("ci-pg", "dev"))
                    .thenReturn(Optional.empty());

            var svc = buildService("local-stub");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-1");

            List<Map<String, Object>> connections = getConnections(result);
            assertThat(connections.get(0).get("credentialStrategy")).isEqualTo("CONNECTOR_SPECIFIC");
            assertThat(connections.get(0).get("status")).isEqualTo("MISSING");
            assertThat(result.get("ready")).isEqualTo(false);
        }

        @Test
        @DisplayName("External connector with UNTESTED credential is not ready")
        void externalWithUntestedCredentialNotReady() {
            stubSubPipelineWithConnector("version-1", "ci-pg");
            stubPostgresConnectorInstance("ci-pg", "sor-1");
            stubSor("sor-1", "tenant-1");
            stubCredentialProfile("ci-pg", "dev", CredentialStatus.UNTESTED);

            var svc = buildService("local-stub");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-1");

            List<Map<String, Object>> connections = getConnections(result);
            assertThat(connections.get(0).get("status")).isEqualTo("UNTESTED");
            assertThat(result.get("ready")).isEqualTo(false);
        }
    }

    // -----------------------------------------------------------------------
    //  Mixed pipeline: lifecycle-file + external
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Mixed pipeline with both connector types")
    class MixedPipeline {

        @Test
        @DisplayName("Pipeline ready only when both lifecycle-file and external are ready")
        void mixedPipelineReadyWhenBothReady() {
            // Two sub-pipelines: one S3, one Postgres
            SubPipelineInstance s3Sub = makeSubPipeline("sub-1", "version-1", "ci-s3");
            SubPipelineInstance pgSub = makeSubPipeline("sub-2", "version-1", "ci-pg");
            when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("version-1"))
                    .thenReturn(List.of(s3Sub, pgSub));

            stubS3ConnectorInstance("ci-s3", "sor-1");
            stubPostgresConnectorInstance("ci-pg", "sor-1");
            stubSor("sor-1", "tenant-1");
            stubActiveTenantCredential("tenant-1");
            stubScaffold("tenant-1", "previewed");
            stubCredentialProfile("ci-pg", "dev", CredentialStatus.VALID);

            var svc = buildService("local-stub");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-1");

            assertThat(result.get("ready")).isEqualTo(true);
            List<Map<String, Object>> connections = getConnections(result);
            assertThat(connections).hasSize(2);
            assertThat(connections.get(0).get("credentialStrategy"))
                    .isEqualTo("INHERIT_TENANT_GCP_SERVICE_ACCOUNT");
            assertThat(connections.get(1).get("credentialStrategy"))
                    .isEqualTo("CONNECTOR_SPECIFIC");
        }

        @Test
        @DisplayName("Pipeline not ready when lifecycle-file blocked even if external ready")
        void mixedPipelineNotReadyWhenLifecycleBlocked() {
            SubPipelineInstance s3Sub = makeSubPipeline("sub-1", "version-1", "ci-s3");
            SubPipelineInstance pgSub = makeSubPipeline("sub-2", "version-1", "ci-pg");
            when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("version-1"))
                    .thenReturn(List.of(s3Sub, pgSub));

            stubS3ConnectorInstance("ci-s3", "sor-1");
            stubPostgresConnectorInstance("ci-pg", "sor-1");
            stubSor("sor-1", "tenant-1");
            // GCP credential missing → lifecycle-file blocked
            when(tenantCredentialService.getCredentialEntity("tenant-1"))
                    .thenReturn(Optional.empty());
            when(scaffoldRepo.findByTenantId("tenant-1")).thenReturn(List.of());
            stubCredentialProfile("ci-pg", "dev", CredentialStatus.VALID);

            var svc = buildService("local-stub");
            Map<String, Object> result = svc.compute("pipeline-1", "dev", "tenant-1");

            assertThat(result.get("ready")).isEqualTo(false);
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getConnections(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("connections");
    }

    private CredentialReadinessService buildService(String secretManagerMode) {
        GcpEnvironmentConfig gcpConfig = new GcpEnvironmentConfig();
        gcpConfig.setSecretManagerMode(secretManagerMode);
        SecretAuthorityReadinessService readinessService =
                new SecretAuthorityReadinessService(gcpConfig, tenantCredentialService, null);
        ConnectorCredentialStrategyClassifier strategyClassifier =
                new ConnectorCredentialStrategyClassifier(ciRepo, connDefRepo,
                        tenantCredentialService, scaffoldRepo);

        return new CredentialReadinessService(
                pipelineRepo, pipelineVersionRepo, instanceRepo,
                ciRepo, connDefRepo, sorRepo, credRepo, readinessService,
                strategyClassifier);
    }

    private void stubSubPipelineWithConnector(String versionId, String ciId) {
        SubPipelineInstance sub = makeSubPipeline("sub-1", versionId, ciId);
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId))
                .thenReturn(List.of(sub));
    }

    private SubPipelineInstance makeSubPipeline(String id, String versionId, String ciId) {
        SubPipelineInstance sub = new SubPipelineInstance();
        sub.setId(id);
        sub.setVersionId(versionId);
        sub.setParams(Map.of("connector_instance_id", ciId));
        return sub;
    }

    private void stubS3ConnectorInstance(String ciId, String sorId) {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId(ciId);
        ci.setSorId(sorId);
        ci.setConnectorDefinitionId("def-s3");
        ci.setName("S3 Landing");
        when(ciRepo.findById(ciId)).thenReturn(Optional.of(ci));

        ConnectorDefinition def = new ConnectorDefinition();
        def.setId("def-s3");
        def.setName("S3-compatible Object Storage");
        def.setDockerRepository("pulse/source-s3");
        def.setDockerImageTag("1.0.0");
        def.setConnectorType(ConnectorType.SOURCE);
        def.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        def.setConnectionSpec(Map.of());
        def.setCredentialStrategy(ConnectorCredentialStrategy.INHERIT_TENANT_GCP_SERVICE_ACCOUNT);
        when(connDefRepo.findById("def-s3")).thenReturn(Optional.of(def));
    }

    private void stubPostgresConnectorInstance(String ciId, String sorId) {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId(ciId);
        ci.setSorId(sorId);
        ci.setConnectorDefinitionId("def-pg");
        ci.setName("Postgres Source");
        when(ciRepo.findById(ciId)).thenReturn(Optional.of(ci));

        ConnectorDefinition def = new ConnectorDefinition();
        def.setId("def-pg");
        def.setName("PostgreSQL");
        def.setDockerRepository("pulse/source-postgres");
        def.setDockerImageTag("1.0.0");
        def.setConnectorType(ConnectorType.SOURCE);
        def.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        def.setConnectionSpec(Map.of("required", List.of("host", "port")));
        def.setCredentialStrategy(ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);
        when(connDefRepo.findById("def-pg")).thenReturn(Optional.of(def));
    }

    private void stubSor(String sorId, String tenantId) {
        SystemOfRecord sor = new SystemOfRecord();
        sor.setId(sorId);
        sor.setTenantId(tenantId);
        sor.setMetadata(Map.of());
        when(sorRepo.findById(sorId)).thenReturn(Optional.of(sor));
    }

    private void stubActiveTenantCredential(String tenantId) {
        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setTenantId(tenantId);
        cred.setControlPlaneProjectId("pulse-project-1");
        cred.setServiceAccountEmail("sa@pulse-project-1.iam.gserviceaccount.com");
        cred.setKeyId("key-abc");
        cred.setEncryptedCredential("encrypted-blob");
        cred.setStatus("active");
        when(tenantCredentialService.getCredentialEntity(tenantId))
                .thenReturn(Optional.of(cred));
    }

    private void stubScaffold(String tenantId, String status) {
        StorageScaffoldStatus scaffold = new StorageScaffoldStatus();
        scaffold.setTenantId(tenantId);
        scaffold.setDomainSlug("lending");
        scaffold.setStatus(status);
        when(scaffoldRepo.findByTenantId(tenantId)).thenReturn(List.of(scaffold));
    }

    private void stubCredentialProfile(String ciId, String env, CredentialStatus status) {
        CredentialProfile cred = new CredentialProfile();
        cred.setId("cred-" + ciId + "-" + env);
        cred.setConnectorInstanceId(ciId);
        cred.setEnvironment(env);
        cred.setStatus(status);
        cred.setConnectionMetadata(Map.of("host", "db.example.com"));
        cred.setSecretReferences(Map.of());
        when(credRepo.findByConnectorInstanceIdAndEnvironment(ciId, env))
                .thenReturn(Optional.of(cred));
    }
}
