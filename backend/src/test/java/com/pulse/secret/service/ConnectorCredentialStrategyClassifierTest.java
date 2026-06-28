package com.pulse.secret.service;

import com.pulse.auth.model.TenantGcpCredential;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.sor.model.ConnectorCredentialStrategy;
import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.storage.model.StorageScaffoldStatus;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * PKT-0018: Strategy classifier and lifecycle-file readiness tests.
 */
@ExtendWith(MockitoExtension.class)
class ConnectorCredentialStrategyClassifierTest {

    @Mock private ConnectorInstanceRepository ciRepo;
    @Mock private ConnectorDefinitionRepository connDefRepo;
    @Mock private TenantGcpCredentialService tenantGcpCredentialService;
    @Mock private StorageScaffoldStatusRepository scaffoldRepo;

    private ConnectorCredentialStrategyClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new ConnectorCredentialStrategyClassifier(
                ciRepo, connDefRepo, tenantGcpCredentialService, scaffoldRepo);
    }

    // -----------------------------------------------------------------------
    //  Strategy classification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Strategy classification")
    class StrategyClassification {

        @Test
        @DisplayName("S3 source connector resolves to INHERIT_TENANT_GCP_SERVICE_ACCOUNT")
        void s3SourceInheritsTenantGcp() {
            ConnectorDefinition def = makeDefinition("01JCONN0SRC0S3000000001",
                    "pulse/source-s3", ConnectorCredentialStrategy.INHERIT_TENANT_GCP_SERVICE_ACCOUNT);

            assertThat(classifier.resolveForDefinition(def))
                    .isEqualTo(ConnectorCredentialStrategy.INHERIT_TENANT_GCP_SERVICE_ACCOUNT);
        }

        @Test
        @DisplayName("S3 destination connector resolves to INHERIT_TENANT_GCP_SERVICE_ACCOUNT")
        void s3DestinationInheritsTenantGcp() {
            ConnectorDefinition def = makeDefinition("01JCONN0DST0S3000000001",
                    "pulse/destination-s3", ConnectorCredentialStrategy.INHERIT_TENANT_GCP_SERVICE_ACCOUNT);

            assertThat(classifier.resolveForDefinition(def))
                    .isEqualTo(ConnectorCredentialStrategy.INHERIT_TENANT_GCP_SERVICE_ACCOUNT);
        }

        @Test
        @DisplayName("PostgreSQL connector resolves to CONNECTOR_SPECIFIC")
        void postgresIsConnectorSpecific() {
            ConnectorDefinition def = makeDefinition("01JCONN0SRC0POSTGRES00001",
                    "pulse/source-postgres", ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);

            assertThat(classifier.resolveForDefinition(def))
                    .isEqualTo(ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);
        }

        @Test
        @DisplayName("SFTP connector resolves to CONNECTOR_SPECIFIC")
        void sftpIsConnectorSpecific() {
            ConnectorDefinition def = makeDefinition("01JCONN0SRC0SFTP0000001",
                    "pulse/source-sftp", ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);

            assertThat(classifier.resolveForDefinition(def))
                    .isEqualTo(ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);
        }

        @Test
        @DisplayName("Kafka connector resolves to CONNECTOR_SPECIFIC")
        void kafkaIsConnectorSpecific() {
            ConnectorDefinition def = makeDefinition("01JCONN0SRC0KAFKA00000001",
                    "pulse/source-kafka", ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);

            assertThat(classifier.resolveForDefinition(def))
                    .isEqualTo(ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);
        }

        @Test
        @DisplayName("REST API connector resolves to CONNECTOR_SPECIFIC")
        void restApiIsConnectorSpecific() {
            ConnectorDefinition def = makeDefinition("01JCONN0SRC0RESTAPI00001",
                    "pulse/source-rest-api", ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);

            assertThat(classifier.resolveForDefinition(def))
                    .isEqualTo(ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);
        }

        @Test
        @DisplayName("Instance resolution walks to definition")
        void instanceResolutionWalksToDefinition() {
            ConnectorInstance ci = makeInstance("ci-1", "def-s3");
            ConnectorDefinition def = makeDefinition("def-s3",
                    "pulse/source-s3", ConnectorCredentialStrategy.INHERIT_TENANT_GCP_SERVICE_ACCOUNT);
            when(ciRepo.findById("ci-1")).thenReturn(Optional.of(ci));
            when(connDefRepo.findById("def-s3")).thenReturn(Optional.of(def));

            assertThat(classifier.resolveForInstance("ci-1"))
                    .isEqualTo(ConnectorCredentialStrategy.INHERIT_TENANT_GCP_SERVICE_ACCOUNT);
        }

        @Test
        @DisplayName("Missing definition defaults to CONNECTOR_SPECIFIC")
        void missingDefinitionDefaultsToConnectorSpecific() {
            ConnectorInstance ci = makeInstance("ci-1", "def-missing");
            when(ciRepo.findById("ci-1")).thenReturn(Optional.of(ci));
            when(connDefRepo.findById("def-missing")).thenReturn(Optional.empty());

            assertThat(classifier.resolveForInstance("ci-1"))
                    .isEqualTo(ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);
        }

        @Test
        @DisplayName("Missing instance defaults to CONNECTOR_SPECIFIC")
        void missingInstanceDefaultsToConnectorSpecific() {
            when(ciRepo.findById("ci-missing")).thenReturn(Optional.empty());

            assertThat(classifier.resolveForInstance("ci-missing"))
                    .isEqualTo(ConnectorCredentialStrategy.CONNECTOR_SPECIFIC);
        }
    }

    // -----------------------------------------------------------------------
    //  Lifecycle-file readiness: ready path
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Lifecycle-file readiness — ready")
    class LifecycleFileReady {

        @Test
        @DisplayName("Ready when tenant GCP credential active and scaffold exists")
        void readyWhenGcpActiveAndScaffoldExists() {
            stubActiveTenantCredential("tenant-1");
            stubScaffolds("tenant-1", List.of(makeScaffold("previewed")));

            Map<String, Object> result = classifier.computeLifecycleFileReadiness("tenant-1");

            assertThat(result.get("ready")).isEqualTo(true);
            assertThat(result.get("credentialStrategy"))
                    .isEqualTo("INHERIT_TENANT_GCP_SERVICE_ACCOUNT");
            assertThat(result.get("gcpCredentialStatus")).isEqualTo("active");
            assertThat(result.get("storageScaffoldStatus")).isEqualTo("ready");
            assertThat(result).doesNotContainKey("blockers");
        }

        @Test
        @DisplayName("Ready when scaffold status is executed")
        void readyWithExecutedScaffold() {
            stubActiveTenantCredential("tenant-1");
            stubScaffolds("tenant-1", List.of(makeScaffold("executed")));

            Map<String, Object> result = classifier.computeLifecycleFileReadiness("tenant-1");

            assertThat(result.get("ready")).isEqualTo(true);
            assertThat(result.get("storageScaffoldStatus")).isEqualTo("ready");
        }

        @Test
        @DisplayName("Readback includes GCP project and SA email, never private key")
        void readbackIncludesRedactedGcpMetadata() {
            stubActiveTenantCredential("tenant-1");
            stubScaffolds("tenant-1", List.of(makeScaffold("previewed")));

            Map<String, Object> result = classifier.computeLifecycleFileReadiness("tenant-1");

            assertThat(result.get("gcpProjectId")).isEqualTo("pulse-project-1");
            assertThat(result.get("serviceAccountEmail")).isEqualTo("sa@pulse-project-1.iam.gserviceaccount.com");
            assertThat(result.get("privateKeyRedacted")).isEqualTo(true);
            // No private key material anywhere in the result
            assertThat(result.toString()).doesNotContain("PRIVATE KEY");
        }
    }

    // -----------------------------------------------------------------------
    //  Lifecycle-file readiness: blocked paths
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Lifecycle-file readiness — blocked")
    class LifecycleFileBlocked {

        @Test
        @DisplayName("Blocked when tenant GCP credential missing")
        void blockedWhenGcpCredentialMissing() {
            when(tenantGcpCredentialService.getCredentialEntity("tenant-1"))
                    .thenReturn(Optional.empty());
            stubScaffolds("tenant-1", List.of(makeScaffold("previewed")));

            Map<String, Object> result = classifier.computeLifecycleFileReadiness("tenant-1");

            assertThat(result.get("ready")).isEqualTo(false);
            assertThat(result.get("gcpCredentialStatus")).isEqualTo("missing");
            assertThat((List<?>) result.get("blockers")).isNotEmpty();
            assertThat(result.get("blockers").toString()).contains("No tenant GCP credential");
        }

        @Test
        @DisplayName("Blocked when tenant GCP credential inactive")
        void blockedWhenGcpCredentialInactive() {
            TenantGcpCredential cred = makeGcpCredential("inactive");
            when(tenantGcpCredentialService.getCredentialEntity("tenant-1"))
                    .thenReturn(Optional.of(cred));
            stubScaffolds("tenant-1", List.of(makeScaffold("previewed")));

            Map<String, Object> result = classifier.computeLifecycleFileReadiness("tenant-1");

            assertThat(result.get("ready")).isEqualTo(false);
            assertThat(result.get("gcpCredentialStatus")).isEqualTo("inactive");
            assertThat(result.get("blockers").toString()).contains("inactive");
        }

        @Test
        @DisplayName("Blocked when storage scaffold missing")
        void blockedWhenScaffoldMissing() {
            stubActiveTenantCredential("tenant-1");
            when(scaffoldRepo.findByTenantId("tenant-1")).thenReturn(List.of());

            Map<String, Object> result = classifier.computeLifecycleFileReadiness("tenant-1");

            assertThat(result.get("ready")).isEqualTo(false);
            assertThat(result.get("storageScaffoldStatus")).isEqualTo("missing");
            assertThat(result.get("blockers").toString()).contains("No storage scaffold");
        }

        @Test
        @DisplayName("Blocked when scaffold exists but only operator_blocked")
        void blockedWhenScaffoldOperatorBlocked() {
            stubActiveTenantCredential("tenant-1");
            stubScaffolds("tenant-1", List.of(makeScaffold("operator_blocked")));

            Map<String, Object> result = classifier.computeLifecycleFileReadiness("tenant-1");

            assertThat(result.get("ready")).isEqualTo(false);
            assertThat(result.get("storageScaffoldStatus")).isEqualTo("not_ready");
        }

        @Test
        @DisplayName("Blocked when both GCP credential and scaffold missing")
        void blockedWhenBothMissing() {
            when(tenantGcpCredentialService.getCredentialEntity("tenant-1"))
                    .thenReturn(Optional.empty());
            when(scaffoldRepo.findByTenantId("tenant-1")).thenReturn(List.of());

            Map<String, Object> result = classifier.computeLifecycleFileReadiness("tenant-1");

            assertThat(result.get("ready")).isEqualTo(false);
            List<?> blockers = (List<?>) result.get("blockers");
            assertThat(blockers).hasSize(2);
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private ConnectorDefinition makeDefinition(String id, String dockerRepo,
                                                ConnectorCredentialStrategy strategy) {
        ConnectorDefinition def = new ConnectorDefinition();
        def.setId(id);
        def.setName(dockerRepo);
        def.setDockerRepository(dockerRepo);
        def.setCredentialStrategy(strategy);
        return def;
    }

    private ConnectorInstance makeInstance(String id, String defId) {
        ConnectorInstance ci = new ConnectorInstance();
        ci.setId(id);
        ci.setConnectorDefinitionId(defId);
        return ci;
    }

    private void stubActiveTenantCredential(String tenantId) {
        TenantGcpCredential cred = makeGcpCredential("active");
        when(tenantGcpCredentialService.getCredentialEntity(tenantId))
                .thenReturn(Optional.of(cred));
    }

    private TenantGcpCredential makeGcpCredential(String status) {
        TenantGcpCredential cred = new TenantGcpCredential();
        cred.setStatus(status);
        cred.setControlPlaneProjectId("pulse-project-1");
        cred.setServiceAccountEmail("sa@pulse-project-1.iam.gserviceaccount.com");
        cred.setKeyId("key-123");
        return cred;
    }

    private StorageScaffoldStatus makeScaffold(String status) {
        StorageScaffoldStatus scaffold = new StorageScaffoldStatus();
        scaffold.setTenantId("tenant-1");
        scaffold.setDomainSlug("lending");
        scaffold.setStatus(status);
        return scaffold;
    }

    private void stubScaffolds(String tenantId, List<StorageScaffoldStatus> scaffolds) {
        when(scaffoldRepo.findByTenantId(tenantId)).thenReturn(scaffolds);
    }
}
