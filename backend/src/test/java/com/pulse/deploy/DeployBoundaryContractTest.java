package com.pulse.deploy;

import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.repository.TenantGcpConfigRepository;
import com.pulse.deploy.boundary.DeployBoundaryReadback;
import com.pulse.deploy.boundary.DeployBoundaryReadback.*;
import com.pulse.deploy.boundary.DeployBoundaryService;
import com.pulse.deploy.evidence.EvidenceProofLevel;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.runtime.config.RuntimeAuthorityProperties;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PKT-0004 — deploy boundary contract tests.
 *
 * <p>Validates the deploy boundary readback contract for:
 * <ul>
 *   <li>Composer target fields (project, region, environment, DAG/package target,
 *       deploy identity expectation, blocker status)</li>
 *   <li>Dataproc target fields (region, runtime SA, batch submission strategy,
 *       staging/package target, blocker status)</li>
 *   <li>BigQuery target fields (datasets/tables, DDL/job targets,
 *       managed-Iceberg connection/resource refs, blocker status)</li>
 *   <li>Secret Manager target fields (runtime access expectation, required
 *       secret refs without values, blocker status)</li>
 *   <li>Evidence/log destination readback (bucket/dataset/prefix targets,
 *       blocker status)</li>
 *   <li>Adapter boundary blocked/authorized states without live GCP calls</li>
 *   <li>Negative: boundary evidence cannot satisfy runtime output proof</li>
 * </ul>
 */
class DeployBoundaryContractTest {

    private DeploymentTargetRepository targetRepo;
    private TenantGcpConfigRepository gcpConfigRepo;
    private TenantGcpRuntimeTopologyRepository topologyRepo;
    private PackageRepository packageRepo;
    private RuntimeAuthorityService runtimeAuthority;
    private DeployBoundaryService service;

    @BeforeEach
    void setUp() {
        targetRepo = mock(DeploymentTargetRepository.class);
        gcpConfigRepo = mock(TenantGcpConfigRepository.class);
        topologyRepo = mock(TenantGcpRuntimeTopologyRepository.class);
        packageRepo = mock(PackageRepository.class);

        RuntimeAuthorityProperties props = new RuntimeAuthorityProperties();
        runtimeAuthority = new RuntimeAuthorityService(props);
        runtimeAuthority.initialize();

        service = new DeployBoundaryService(targetRepo, gcpConfigRepo, topologyRepo, packageRepo, runtimeAuthority);
    }

    // ── Helper: wire a GCP target ────────────────────────────

    private DeploymentTarget wireGcpTarget(String targetId, String tenantId,
                                            String environment, Map<String, Object> config) {
        DeploymentTarget target = new DeploymentTarget();
        target.setId(targetId);
        target.setTenantId(tenantId);
        target.setName("acme-gcp-" + environment);
        target.setEnvironment(environment);
        target.setTargetType("GCP_COMPOSER_DATAPROC");
        target.setConfig(config);
        target.setEnabled(true);
        when(targetRepo.findById(targetId)).thenReturn(Optional.of(target));
        return target;
    }

    private TenantGcpConfig wireGcpConfig(String tenantId, String project, String region) {
        TenantGcpConfig config = new TenantGcpConfig();
        config.setTenantId(tenantId);
        config.setControlPlaneProjectId(project);
        config.setGcpRegion(region);
        when(gcpConfigRepo.findByTenantId(tenantId)).thenReturn(Optional.of(config));
        return config;
    }

    private Package wirePackage(String packageId, String buildStatus) {
        Package pkg = new Package();
        pkg.setId(packageId);
        pkg.setBuildStatus(buildStatus);
        when(packageRepo.findById(packageId)).thenReturn(Optional.of(pkg));
        return pkg;
    }

    private TenantGcpRuntimeTopology wireTopology(String tenantId) {
        TenantGcpRuntimeTopology topo = new TenantGcpRuntimeTopology();
        topo.setTenantId(tenantId);
        topo.setComposerProjectId("acme-lending-dev");
        topo.setComposerEnvironment("projects/acme-lending-dev/locations/us-central1/environments/pulse-composer");
        topo.setComposerRegion("us-central1");
        topo.setComposerEnvironmentBucket("acme-lending-dev-composer-bucket");
        topo.setDataprocProjectId("acme-lending-dev");
        topo.setDataprocRegion("us-central1");
        topo.setDataprocWorkloadSaEmail("pulse-dataproc-workload@acme-lending-dev.iam.gserviceaccount.com");
        topo.setDataprocStagingBucket("acme-lending-dev-dataproc-staging");
        topo.setBqProjectId("acme-lending-dev");
        topo.setBqLocation("us-central1");
        topo.setBqDatasetBronze("pulse_bronze");
        topo.setBqDatasetSilver("pulse_silver");
        topo.setBqDatasetGold("pulse_gold");
        topo.setBqConnectionId("projects/acme-lending-dev/locations/us/connections/biglake-iceberg");
        topo.setIcebergStorageBucket("acme-lending-dev-iceberg");
        topo.setEvidenceSinkBucket("acme-lending-dev-evidence");
        topo.setEvidenceSinkDataset("pulse_evidence");
        topo.setSecretManagerProjectId("acme-lending-dev");
        topo.setControlPlaneSaEmail("pulse-control@acme-lending-dev.iam.gserviceaccount.com");
        when(topologyRepo.findByTenantId(tenantId)).thenReturn(Optional.of(topo));
        return topo;
    }

    // ── Topology-aware tests (PKT-0025 reconciliation) ───────

    @Nested
    @DisplayName("PKT-0025 topology reconciliation")
    class TopologyReconciliationTests {

        @Test
        @DisplayName("Topology fields override defaults when present")
        void topologyFieldsOverrideDefaults() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            TenantGcpRuntimeTopology topo = wireTopology("tenant-A");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());
            wirePackage("pkg-1", "COMPLETED");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "pkg-1");

            // Composer uses topology values
            assertEquals("acme-lending-dev-composer-bucket", readback.composer().packageDeliveryBucket());
            assertEquals("pulse-control@acme-lending-dev.iam.gserviceaccount.com",
                    readback.composer().deployIdentityExpectation());

            // Dataproc uses topology values
            assertEquals("pulse-dataproc-workload@acme-lending-dev.iam.gserviceaccount.com",
                    readback.dataproc().runtimeServiceAccount());
            assertEquals("acme-lending-dev-dataproc-staging", readback.dataproc().stagingBucket());

            // BigQuery uses topology medallion datasets
            assertTrue(readback.bigquery().targetDatasets().stream()
                    .anyMatch(d -> d.contains("pulse_bronze")));
            assertNotNull(readback.bigquery().managedIcebergConnection());

            // Evidence uses topology sink
            assertEquals("acme-lending-dev-evidence", readback.evidenceLog().evidenceBucket());

            // Overall should be LIVE with full topology
            assertEquals(BoundaryStatus.LIVE, readback.boundaryStatus());
        }

        @Test
        @DisplayName("Topology blocker naming consistent with PKT-0025 readiness categories")
        void topologyBlockerNamingConsistent() {
            // No topology, no GCP config → blockers use TOPOLOGY/IAM prefixes
            when(gcpConfigRepo.findByTenantId("tenant-B")).thenReturn(Optional.empty());
            when(topologyRepo.findByTenantId("tenant-B")).thenReturn(Optional.empty());
            wireGcpTarget("target-2", "tenant-B", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-B", "target-2", null);

            // Verify blockers use TOPOLOGY: and IAM: prefixes
            assertTrue(readback.blockers().stream().anyMatch(b -> b.startsWith("TOPOLOGY:")),
                    "Missing TOPOLOGY: blocker prefix");
            assertTrue(readback.blockers().stream().anyMatch(b -> b.startsWith("IAM:")),
                    "Missing IAM: blocker prefix");
        }
    }

    // ── Composer boundary tests ──────────────────────────────

    @Nested
    @DisplayName("Composer boundary")
    class ComposerBoundaryTests {

        @Test
        @DisplayName("Composer fields populated with tenant GCP config")
        void composerFieldsWithGcpConfig() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());
            wirePackage("pkg-1", "COMPLETED");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "pkg-1");

            assertNotNull(readback.composer());
            assertEquals("acme-lending-dev", readback.composer().gcpProject());
            assertEquals("us-central1", readback.composer().region());
            assertNotNull(readback.composer().composerEnvironment());
            assertTrue(readback.composer().composerEnvironment().contains("acme-lending-dev"));
            assertNotNull(readback.composer().packageDeliveryBucket());
            assertNotNull(readback.composer().deployIdentityExpectation());
            assertFalse(readback.composer().dagTargetPaths().isEmpty());
            assertEquals(Responsibility.OPERATOR_PROVISIONS_PULSE_VALIDATES,
                    readback.composer().responsibility());
        }

        @Test
        @DisplayName("Composer blocked when GCP config missing")
        void composerBlockedWithoutGcpConfig() {
            when(gcpConfigRepo.findByTenantId("tenant-B")).thenReturn(Optional.empty());
            wireGcpTarget("target-2", "tenant-B", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-B", "target-2", null);

            assertNotNull(readback.composer());
            assertFalse(readback.composer().ready());
            assertFalse(readback.composer().blockers().isEmpty());
            assertTrue(readback.composer().blockers().stream()
                    .anyMatch(b -> b.contains("TOPOLOGY")));
        }

        @Test
        @DisplayName("Composer custom config overrides defaults")
        void composerCustomConfig() {
            wireGcpConfig("tenant-A", "acme-prod", "europe-west1");
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("composerEnvironment", "projects/acme-prod/locations/europe-west1/environments/prod-composer");
            config.put("gcsBucket", "acme-prod-custom-packages");
            config.put("dagFilePaths", List.of("dags/main_dag.py", "dags/dq_dag.py"));
            wireGcpTarget("target-3", "tenant-A", "prod", config);
            wirePackage("pkg-1", "COMPLETED");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-3", "pkg-1");

            assertEquals("projects/acme-prod/locations/europe-west1/environments/prod-composer",
                    readback.composer().composerEnvironment());
            assertEquals("acme-prod-custom-packages", readback.composer().packageDeliveryBucket());
            assertEquals(2, readback.composer().dagTargetPaths().size());
        }
    }

    // ── Dataproc boundary tests ──────────────────────────────

    @Nested
    @DisplayName("Dataproc boundary")
    class DataprocBoundaryTests {

        @Test
        @DisplayName("Dataproc fields populated with tenant GCP config")
        void dataprocFieldsWithGcpConfig() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());
            wirePackage("pkg-1", "COMPLETED");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "pkg-1");

            assertNotNull(readback.dataproc());
            assertEquals("us-central1", readback.dataproc().region());
            assertNotNull(readback.dataproc().runtimeServiceAccount());
            assertTrue(readback.dataproc().runtimeServiceAccount().contains("acme-lending-dev"));
            assertEquals("SERVERLESS_BATCH", readback.dataproc().batchSubmissionStrategy());
            assertNotNull(readback.dataproc().stagingBucket());
            assertNotNull(readback.dataproc().packageTarget());
            assertEquals(Responsibility.OPERATOR_PROVISIONS_PULSE_VALIDATES,
                    readback.dataproc().responsibility());
        }

        @Test
        @DisplayName("Dataproc blocked when GCP config missing")
        void dataprocBlockedWithoutGcpConfig() {
            when(gcpConfigRepo.findByTenantId("tenant-B")).thenReturn(Optional.empty());
            wireGcpTarget("target-2", "tenant-B", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-B", "target-2", null);

            assertNotNull(readback.dataproc());
            assertFalse(readback.dataproc().ready());
            assertFalse(readback.dataproc().blockers().isEmpty());
        }
    }

    // ── BigQuery boundary tests ──────────────────────────────

    @Nested
    @DisplayName("BigQuery boundary")
    class BigQueryBoundaryTests {

        @Test
        @DisplayName("BigQuery fields populated with datasets/tables/DDL/job targets")
        void bigqueryFieldsPopulated() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());
            wirePackage("pkg-1", "COMPLETED");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "pkg-1");

            assertNotNull(readback.bigquery());
            assertFalse(readback.bigquery().targetDatasets().isEmpty());
            assertTrue(readback.bigquery().targetDatasets().stream()
                    .anyMatch(d -> d.contains("pulse_bronze")));
            assertTrue(readback.bigquery().targetDatasets().stream()
                    .anyMatch(d -> d.contains("pulse_gold")));
            assertFalse(readback.bigquery().ddlTargets().isEmpty());
            assertFalse(readback.bigquery().jobTargets().isEmpty());
            assertEquals(Responsibility.PULSE_CREATES, readback.bigquery().responsibility());
        }

        @Test
        @DisplayName("BigQuery managed-Iceberg connection included when configured")
        void bigqueryManagedIceberg() {
            wireGcpConfig("tenant-A", "acme-prod", "us-central1");
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("bqManagedIcebergConnection", "projects/acme-prod/locations/us/connections/biglake-iceberg");
            config.put("bqManagedIcebergResourceRef", "projects/acme-prod/datasets/iceberg_catalog");
            wireGcpTarget("target-1", "tenant-A", "prod", config);
            wirePackage("pkg-1", "COMPLETED");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "pkg-1");

            assertNotNull(readback.bigquery());
            assertEquals("projects/acme-prod/locations/us/connections/biglake-iceberg",
                    readback.bigquery().managedIcebergConnection());
            assertEquals("projects/acme-prod/datasets/iceberg_catalog",
                    readback.bigquery().managedIcebergResourceRef());
        }

        @Test
        @DisplayName("BigQuery blocked when GCP config missing")
        void bigqueryBlockedWithoutGcpConfig() {
            when(gcpConfigRepo.findByTenantId("tenant-B")).thenReturn(Optional.empty());
            wireGcpTarget("target-2", "tenant-B", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-B", "target-2", null);

            assertNotNull(readback.bigquery());
            assertFalse(readback.bigquery().ready());
        }
    }

    // ── Secret Manager boundary tests ────────────────────────

    @Nested
    @DisplayName("Secret Manager boundary")
    class SecretManagerBoundaryTests {

        @Test
        @DisplayName("Secret Manager refs use gcp-sm:// URIs without values")
        void secretManagerRefsNoValues() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());
            wirePackage("pkg-1", "COMPLETED");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "pkg-1");

            assertNotNull(readback.secretManager());
            assertNotNull(readback.secretManager().runtimeAccessExpectation());
            assertFalse(readback.secretManager().requiredSecretRefs().isEmpty());
            for (SecretRef ref : readback.secretManager().requiredSecretRefs()) {
                assertNotNull(ref.name());
                assertNotNull(ref.secretUri());
                assertTrue(ref.secretUri().startsWith("gcp-sm://"),
                        "Secret refs must use gcp-sm:// URI, got: " + ref.secretUri());
                assertNotNull(ref.purpose());
                // Verify no raw secret values leaked
                assertFalse(ref.secretUri().contains("PRIVATE KEY"),
                        "Secret ref must not contain private key material");
                assertFalse(ref.secretUri().contains("password"),
                        "Secret ref must not contain password");
            }
            assertEquals(Responsibility.OPERATOR_PROVISIONS_PULSE_VALIDATES,
                    readback.secretManager().responsibility());
        }

        @Test
        @DisplayName("Secret Manager includes standard refs (deploy SA, callback, JDBC)")
        void secretManagerStandardRefs() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", null);

            List<String> refNames = readback.secretManager().requiredSecretRefs().stream()
                    .map(SecretRef::name).toList();
            assertTrue(refNames.contains("pulse-deploy-sa"));
            assertTrue(refNames.contains("pulse-airflow-callback"));
            assertTrue(refNames.contains("pulse-jdbc-credentials"));
        }

        @Test
        @DisplayName("Secret Manager blocked when GCP config missing")
        void secretManagerBlockedWithoutGcpConfig() {
            when(gcpConfigRepo.findByTenantId("tenant-B")).thenReturn(Optional.empty());
            wireGcpTarget("target-2", "tenant-B", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-B", "target-2", null);

            assertNotNull(readback.secretManager());
            assertFalse(readback.secretManager().ready());
        }
    }

    // ── Evidence/log destination tests ───────────────────────

    @Nested
    @DisplayName("Evidence/log destination boundary")
    class EvidenceLogBoundaryTests {

        @Test
        @DisplayName("Evidence/log fields populated with bucket/dataset/prefix")
        void evidenceLogFieldsPopulated() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", null);

            assertNotNull(readback.evidenceLog());
            assertNotNull(readback.evidenceLog().evidenceBucket());
            assertTrue(readback.evidenceLog().evidenceBucket().contains("evidence"));
            assertNotNull(readback.evidenceLog().evidencePrefix());
            assertNotNull(readback.evidenceLog().logDataset());
            assertNotNull(readback.evidenceLog().logPrefix());
            assertEquals(Responsibility.PULSE_CREATES, readback.evidenceLog().responsibility());
        }

        @Test
        @DisplayName("Evidence/log blocked when GCP config missing")
        void evidenceLogBlockedWithoutGcpConfig() {
            when(gcpConfigRepo.findByTenantId("tenant-B")).thenReturn(Optional.empty());
            wireGcpTarget("target-2", "tenant-B", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-B", "target-2", null);

            assertNotNull(readback.evidenceLog());
            assertFalse(readback.evidenceLog().ready());
        }
    }

    // ── Adapter boundary blocked/authorized tests ────────────

    @Nested
    @DisplayName("Adapter boundary blocked/authorized states")
    class AdapterBoundaryStateTests {

        @Test
        @DisplayName("Fully configured GCP target is LIVE (not blocked)")
        void fullyConfiguredTargetIsLive() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());
            wirePackage("pkg-1", "COMPLETED");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "pkg-1");

            // All resources have defaults from tenant GCP config
            assertEquals(BoundaryStatus.LIVE, readback.boundaryStatus());
            assertFalse(readback.isBlocked());
            assertTrue(readback.blockers().isEmpty());
        }

        @Test
        @DisplayName("Missing GCP config → OPERATOR_BLOCKED")
        void missingGcpConfigIsOperatorBlocked() {
            when(gcpConfigRepo.findByTenantId("tenant-B")).thenReturn(Optional.empty());
            wireGcpTarget("target-2", "tenant-B", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-B", "target-2", null);

            assertEquals(BoundaryStatus.OPERATOR_BLOCKED, readback.boundaryStatus());
            assertTrue(readback.isBlocked());
            assertFalse(readback.blockers().isEmpty());
            assertTrue(readback.blockers().stream().anyMatch(b -> b.contains("GCP_CONFIG")));
        }

        @Test
        @DisplayName("Missing deployment target → BLOCKED (hard)")
        void missingTargetIsHardBlocked() {
            when(targetRepo.findById("missing-target")).thenReturn(Optional.empty());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "missing-target", null);

            assertEquals(BoundaryStatus.BLOCKED, readback.boundaryStatus());
            assertTrue(readback.isBlocked());
            assertTrue(readback.blockers().stream().anyMatch(b -> b.contains("HARD_BLOCK")));
        }

        @Test
        @DisplayName("Package not COMPLETED → blockers present")
        void packageNotCompleted() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());
            wirePackage("pkg-1", "BUILDING");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "pkg-1");

            assertNotNull(readback.artifactReadiness());
            assertFalse(readback.artifactReadiness().artifactsReady());
            assertEquals("BUILDING", readback.artifactReadiness().packageStatus());
            assertTrue(readback.isBlocked());
        }

        @Test
        @DisplayName("Package missing → blockers present")
        void packageMissing() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());
            when(packageRepo.findById("missing-pkg")).thenReturn(Optional.empty());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "missing-pkg");

            assertNotNull(readback.artifactReadiness());
            assertFalse(readback.artifactReadiness().hasPackage());
            assertTrue(readback.isBlocked());
        }

        @Test
        @DisplayName("Non-GCP target returns null for GCP-specific boundaries")
        void nonGcpTargetNullBoundaries() {
            DeploymentTarget target = new DeploymentTarget();
            target.setId("local-1");
            target.setTenantId("tenant-A");
            target.setName("local-dev");
            target.setEnvironment("local");
            target.setTargetType("LOCAL_MATERIALIZATION");
            target.setConfig(Map.of());
            target.setEnabled(true);
            when(targetRepo.findById("local-1")).thenReturn(Optional.of(target));

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "local-1", null);

            assertNull(readback.composer());
            assertNull(readback.dataproc());
            assertNull(readback.bigquery());
            assertNull(readback.secretManager());
            assertNull(readback.evidenceLog());
        }
    }

    // ── Boundary readback schema/envelope tests ──────────────

    @Nested
    @DisplayName("Boundary readback schema")
    class BoundaryReadbackSchemaTests {

        @Test
        @DisplayName("Schema version is pinned")
        void schemaVersionPinned() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", null);

            assertEquals(DeployBoundaryReadback.SCHEMA_VERSION, readback.schemaVersion());
            assertEquals("deploy-boundary-readback.v1", readback.schemaVersion());
        }

        @Test
        @DisplayName("Evidence disclaimer is always present")
        void evidenceDisclaimerAlwaysPresent() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", null);

            assertNotNull(readback.boundaryEvidenceDisclaimer());
            assertTrue(readback.boundaryEvidenceDisclaimer().contains("cannot be rendered as static package proof"));
            assertTrue(readback.boundaryEvidenceDisclaimer().contains("preflight-only proof"));
            assertTrue(readback.boundaryEvidenceDisclaimer().contains("local synthetic proof"));
            assertTrue(readback.boundaryEvidenceDisclaimer().contains("runtime output proof"));
        }

        @Test
        @DisplayName("Canonical JSON round-trip includes all fields")
        void canonicalJsonRoundTrip() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());
            wirePackage("pkg-1", "COMPLETED");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "pkg-1");
            Map<String, Object> json = readback.toCanonicalJson();

            assertNotNull(json.get("schemaVersion"));
            assertNotNull(json.get("tenantId"));
            assertNotNull(json.get("targetId"));
            assertNotNull(json.get("boundaryStatus"));
            assertNotNull(json.get("boundaryEvidenceDisclaimer"));
            assertNotNull(json.get("composer"));
            assertNotNull(json.get("dataproc"));
            assertNotNull(json.get("bigquery"));
            assertNotNull(json.get("secretManager"));
            assertNotNull(json.get("evidenceLog"));
            assertNotNull(json.get("artifactReadiness"));
        }

        @Test
        @DisplayName("Create-vs-validate responsibility present for every resource")
        void responsibilityPresentForEveryResource() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", null);

            assertNotNull(readback.composer().responsibility());
            assertNotNull(readback.dataproc().responsibility());
            assertNotNull(readback.bigquery().responsibility());
            assertNotNull(readback.secretManager().responsibility());
            assertNotNull(readback.evidenceLog().responsibility());
        }
    }

    // ── Negative: boundary evidence vs. proof levels ─────────

    @Nested
    @DisplayName("Negative: boundary evidence cannot satisfy runtime proof")
    class NegativeProofLevelTests {

        @Test
        @DisplayName("Deploy boundary readback is never runtimeProof=true")
        void boundaryReadbackNeverRuntimeProof() {
            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());
            wirePackage("pkg-1", "COMPLETED");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "pkg-1");

            // The readback itself has no runtimeProof field — it is boundary-only.
            // The disclaimer explicitly states it cannot be used as runtime proof.
            assertNotNull(readback.boundaryEvidenceDisclaimer());
            assertTrue(readback.boundaryEvidenceDisclaimer().contains("runtime output proof"));
            // Boundary status LIVE means topology is ready, NOT that runtime proof exists.
            assertEquals(BoundaryStatus.LIVE, readback.boundaryStatus());
        }

        @Test
        @DisplayName("STATIC_PACKAGE evidence cannot carry runtimeProof=true")
        void staticPackageCannotBeRuntimeProof() {
            assertThrows(IllegalArgumentException.class, () ->
                    new RuntimeEvidenceEnvelope(
                            RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                            EvidenceProofLevel.STATIC_PACKAGE,
                            RuntimeEvidenceEnvelope.TYPE_STATIC_PACKAGE,
                            "run-1", "pkg-1", "tenant-A", "dev", null,
                            Instant.now(), "test", null,
                            true,   // runtimeProof — illegal for STATIC_PACKAGE
                            false,
                            null, null, null, null, null, null, null, null
                    ));
        }

        @Test
        @DisplayName("PREFLIGHT evidence cannot carry runtimeProof=true")
        void preflightCannotBeRuntimeProof() {
            assertThrows(IllegalArgumentException.class, () ->
                    new RuntimeEvidenceEnvelope(
                            RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                            EvidenceProofLevel.PREFLIGHT,
                            RuntimeEvidenceEnvelope.TYPE_PREFLIGHT,
                            "run-1", "pkg-1", "tenant-A", "dev", null,
                            Instant.now(), "test", null,
                            true,   // runtimeProof — illegal for PREFLIGHT
                            false,
                            null, null, null, null, null, null, null, null
                    ));
        }

        @Test
        @DisplayName("LOCAL_SYNTHETIC evidence cannot carry runtimeProof=true")
        void localSyntheticCannotBeRuntimeProof() {
            assertThrows(IllegalArgumentException.class, () ->
                    new RuntimeEvidenceEnvelope(
                            RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                            EvidenceProofLevel.LOCAL_SYNTHETIC,
                            RuntimeEvidenceEnvelope.TYPE_LOCAL_SYNTHETIC,
                            "run-1", "pkg-1", "tenant-A", "dev", null,
                            Instant.now(), "test", null,
                            true,   // runtimeProof — illegal for LOCAL_SYNTHETIC
                            false,
                            null, null, null, null, null, null, null, null
                    ));
        }

        @Test
        @DisplayName("EvidenceProofLevel invariants: only LIVE_RUNTIME+ has runtimeProof")
        void proofLevelInvariantsEnforced() {
            assertFalse(EvidenceProofLevel.STATIC_PACKAGE.isRuntimeProof());
            assertFalse(EvidenceProofLevel.PREFLIGHT.isRuntimeProof());
            assertFalse(EvidenceProofLevel.LOCAL_SYNTHETIC.isRuntimeProof());
            assertTrue(EvidenceProofLevel.LIVE_RUNTIME.isRuntimeProof());
            assertTrue(EvidenceProofLevel.ORACLE_VERDICT.isRuntimeProof());
            assertTrue(EvidenceProofLevel.PROMOTION_READINESS.isRuntimeProof());
        }

        @Test
        @DisplayName("EvidenceProofLevel invariants: only PROMOTION_READINESS has promotionReady")
        void promotionReadyInvariantsEnforced() {
            assertFalse(EvidenceProofLevel.STATIC_PACKAGE.isPromotionReady());
            assertFalse(EvidenceProofLevel.PREFLIGHT.isPromotionReady());
            assertFalse(EvidenceProofLevel.LOCAL_SYNTHETIC.isPromotionReady());
            assertFalse(EvidenceProofLevel.LIVE_RUNTIME.isPromotionReady());
            assertFalse(EvidenceProofLevel.ORACLE_VERDICT.isPromotionReady());
            assertTrue(EvidenceProofLevel.PROMOTION_READINESS.isPromotionReady());
        }

        @Test
        @DisplayName("Deploy boundary LIVE status does not imply runtime evidence")
        void liveStatusNotRuntimeEvidence() {
            // A LIVE boundary status means topology is ready for deploy.
            // It does NOT mean runtime execution has occurred.
            // This is tested by ensuring the readback schema has no runtimeProof field,
            // no promotionReady field, and the disclaimer is explicit.

            wireGcpConfig("tenant-A", "acme-lending-dev", "us-central1");
            wireGcpTarget("target-1", "tenant-A", "dev", Map.of());
            wirePackage("pkg-1", "COMPLETED");

            DeployBoundaryReadback readback = service.assembleForTarget("tenant-A", "target-1", "pkg-1");
            Map<String, Object> json = readback.toCanonicalJson();

            // Boundary readback JSON must NOT contain runtimeProof or promotionReady keys
            assertFalse(json.containsKey("runtimeProof"),
                    "Boundary readback must not carry runtimeProof");
            assertFalse(json.containsKey("promotionReady"),
                    "Boundary readback must not carry promotionReady");
            assertFalse(json.containsKey("proofLevel"),
                    "Boundary readback must not carry proofLevel — it is not evidence");
        }
    }
}
