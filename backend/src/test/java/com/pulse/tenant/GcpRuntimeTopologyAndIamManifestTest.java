package com.pulse.tenant;

import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import com.pulse.tenant.service.GcpIamManifestService;
import com.pulse.tenant.service.GcpRuntimeTopologyService;
import com.pulse.tenant.controller.TenantGcpRuntimeTopologyController;
import com.pulse.tenant.controller.TenantGcpRuntimeTopologyController.TopologyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * PKT-0025: GCP Runtime Topology and Least-Privilege IAM Manifest proof.
 *
 * <p>Proves:
 * <ul>
 *   <li>Topology CRUD/readback with all required fields</li>
 *   <li>Readback includes Composer, Dataproc, BigQuery, Iceberg, evidence topology</li>
 *   <li>IAM manifest generation with split service accounts</li>
 *   <li>Resource-scoped grants for GCS, Secret Manager, Composer, Dataproc, BigQuery</li>
 *   <li>Readiness category with topology completeness checks</li>
 *   <li>Negative: location mismatch blocks readiness</li>
 *   <li>Negative: missing BQ connection SA blocks readiness</li>
 *   <li>Negative: missing topology returns not_configured</li>
 *   <li>No live GCP execution — iamBindingExecution = OPERATOR_BLOCKED</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GcpRuntimeTopologyAndIamManifestTest {

    private static final String TENANT = "acme-lending";

    // ================================================================
    // §1 — Topology Service CRUD and Readback
    // ================================================================
    @Nested
    @DisplayName("§1 Topology Service CRUD and Readback")
    class TopologyServiceCrud {

        @Mock private TenantGcpRuntimeTopologyRepository repo;
        @Mock private TenantService tenantService;

        private GcpRuntimeTopologyService service;

        @BeforeEach
        void setUp() {
            service = new GcpRuntimeTopologyService(repo, tenantService);
        }

        @Test
        @DisplayName("getTopology returns empty when not configured")
        void getTopology_notConfigured() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.empty());
            assertTrue(service.getTopology(TENANT).isEmpty());
        }

        @Test
        @DisplayName("setTopology upserts and returns saved entity")
        void setTopology_creates() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.empty());
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TenantGcpRuntimeTopology incoming = fullTopology();
            TenantGcpRuntimeTopology saved = service.setTopology(TENANT, incoming);

            assertEquals(TENANT, saved.getTenantId());
            assertEquals("acme-composer-env", saved.getComposerEnvironment());
            assertEquals("us-central1", saved.getComposerRegion());
        }

        @Test
        @DisplayName("setTopology throws when tenant does not exist")
        void setTopology_tenantMissing() {
            when(tenantService.getTenantEntity(TENANT))
                    .thenThrow(new ResourceNotFoundException("Tenant", TENANT));

            assertThrows(ResourceNotFoundException.class,
                    () -> service.setTopology(TENANT, fullTopology()));
        }

        @Test
        @DisplayName("Readback includes all Composer fields")
        void readback_composerFields() {
            TenantGcpRuntimeTopology t = fullTopology();
            t.setTenantId(TENANT);
            Map<String, Object> rb = service.buildReadback(t);

            @SuppressWarnings("unchecked")
            var composer = (Map<String, Object>) rb.get("composer");
            assertEquals("acme-project", composer.get("projectId"));
            assertEquals("acme-composer-env", composer.get("environment"));
            assertEquals("us-central1", composer.get("region"));
            assertEquals("acme-composer-bucket", composer.get("environmentBucket"));
            assertEquals("dags/", composer.get("dagPrefix"));
            assertEquals("plugins/", composer.get("pluginsPrefix"));
            assertEquals("data/", composer.get("dataPrefix"));
            assertEquals("logs/", composer.get("logPrefix"));
        }

        @Test
        @DisplayName("Readback includes all Dataproc fields")
        void readback_dataprocFields() {
            TenantGcpRuntimeTopology t = fullTopology();
            t.setTenantId(TENANT);
            Map<String, Object> rb = service.buildReadback(t);

            @SuppressWarnings("unchecked")
            var dataproc = (Map<String, Object>) rb.get("dataproc");
            assertEquals("acme-project", dataproc.get("projectId"));
            assertEquals("us-central1", dataproc.get("region"));
            assertEquals("dataproc-workload@acme-project.iam.gserviceaccount.com",
                    dataproc.get("workloadServiceAccount"));
            assertEquals("default", dataproc.get("network"));
            assertEquals("default", dataproc.get("subnet"));
            assertEquals("acme-dataproc-staging", dataproc.get("stagingBucket"));
        }

        @Test
        @DisplayName("Readback includes all BigQuery fields")
        void readback_bigqueryFields() {
            TenantGcpRuntimeTopology t = fullTopology();
            t.setTenantId(TENANT);
            Map<String, Object> rb = service.buildReadback(t);

            @SuppressWarnings("unchecked")
            var bq = (Map<String, Object>) rb.get("bigquery");
            assertEquals("acme-project", bq.get("projectId"));
            assertEquals("us-central1", bq.get("location"));
            assertEquals("acme_bronze", bq.get("datasetBronze"));
            assertEquals("acme_silver", bq.get("datasetSilver"));
            assertEquals("acme_gold", bq.get("datasetGold"));

            @SuppressWarnings("unchecked")
            var bqConn = (Map<String, Object>) rb.get("bigqueryConnection");
            assertEquals("acme-bq-conn", bqConn.get("connectionId"));
            assertEquals("us-central1", bqConn.get("connectionRegion"));
            assertEquals("bq-conn-sa@acme-project.iam.gserviceaccount.com",
                    bqConn.get("connectionServiceAccount"));
        }

        @Test
        @DisplayName("Readback includes Iceberg and evidence fields")
        void readback_icebergAndEvidence() {
            TenantGcpRuntimeTopology t = fullTopology();
            t.setTenantId(TENANT);
            Map<String, Object> rb = service.buildReadback(t);

            @SuppressWarnings("unchecked")
            var iceberg = (Map<String, Object>) rb.get("iceberg");
            assertEquals("acme-iceberg-storage", iceberg.get("storageBucket"));

            @SuppressWarnings("unchecked")
            var evidence = (Map<String, Object>) rb.get("evidence");
            assertEquals("acme-evidence-sink", evidence.get("sinkBucket"));
            assertEquals("acme_evidence", evidence.get("sinkDataset"));
        }

        @Test
        @DisplayName("Readback includes Secret Manager and logging fields")
        void readback_secretManagerAndLogging() {
            TenantGcpRuntimeTopology t = fullTopology();
            t.setTenantId(TENANT);
            Map<String, Object> rb = service.buildReadback(t);

            @SuppressWarnings("unchecked")
            var sm = (Map<String, Object>) rb.get("secretManager");
            assertEquals("acme-project", sm.get("projectId"));

            @SuppressWarnings("unchecked")
            var logging = (Map<String, Object>) rb.get("logging");
            assertEquals("acme-project", logging.get("projectId"));
            assertEquals("acme-log-bucket", logging.get("logBucket"));
        }

        @Test
        @DisplayName("Readback includes control plane service account")
        void readback_controlPlaneSa() {
            TenantGcpRuntimeTopology t = fullTopology();
            t.setTenantId(TENANT);
            Map<String, Object> rb = service.buildReadback(t);

            assertEquals("pulse-cp@acme-project.iam.gserviceaccount.com",
                    rb.get("controlPlaneServiceAccount"));
        }

        @Test
        @DisplayName("Readback never exposes secret material")
        void readback_noSecretMaterial() {
            TenantGcpRuntimeTopology t = fullTopology();
            t.setTenantId(TENANT);
            Map<String, Object> rb = service.buildReadback(t);

            String serialized = rb.toString();
            assertFalse(serialized.contains("private_key"));
            assertFalse(serialized.contains("BEGIN RSA"));
            assertFalse(serialized.contains("BEGIN PRIVATE"));
            assertFalse(serialized.contains("encrypted_credential"));
            assertFalse(serialized.contains("password"));
        }
    }

    // ================================================================
    // §2 — IAM Manifest Generation with Split Service Accounts
    // ================================================================
    @Nested
    @DisplayName("§2 IAM Manifest Generation")
    class IamManifestGeneration {

        @Mock private TenantGcpRuntimeTopologyRepository repo;

        private GcpIamManifestService iamService;

        @BeforeEach
        void setUp() {
            iamService = new GcpIamManifestService(repo);
        }

        @Test
        @DisplayName("Manifest status is generated when topology exists")
        void manifest_generated() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(fullTopologyWithTenant()));
            var manifest = iamService.generateManifest(TENANT);

            assertEquals("generated", manifest.get("status"));
            assertEquals("PKT-0025", manifest.get("packet"));
            assertEquals("OPERATOR_BLOCKED", manifest.get("iamBindingExecution"));
        }

        @Test
        @DisplayName("Manifest returns not_configured when no topology")
        void manifest_notConfigured() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.empty());
            var manifest = iamService.generateManifest(TENANT);

            assertEquals("not_configured", manifest.get("status"));
            assertNotNull(manifest.get("error"));
        }

        @Test
        @DisplayName("Manifest includes three split service accounts")
        void manifest_splitServiceAccounts() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(fullTopologyWithTenant()));
            var manifest = iamService.generateManifest(TENANT);

            @SuppressWarnings("unchecked")
            var sas = (Map<String, Object>) manifest.get("serviceAccounts");
            assertNotNull(sas.get("controlPlane"));
            assertNotNull(sas.get("dataprocWorkload"));
            assertNotNull(sas.get("bigqueryConnection"));

            @SuppressWarnings("unchecked")
            var cp = (Map<String, Object>) sas.get("controlPlane");
            assertEquals("pulse-cp@acme-project.iam.gserviceaccount.com", cp.get("email"));

            @SuppressWarnings("unchecked")
            var dp = (Map<String, Object>) sas.get("dataprocWorkload");
            assertEquals("dataproc-workload@acme-project.iam.gserviceaccount.com", dp.get("email"));

            @SuppressWarnings("unchecked")
            var bqc = (Map<String, Object>) sas.get("bigqueryConnection");
            assertEquals("bq-conn-sa@acme-project.iam.gserviceaccount.com", bqc.get("email"));
        }

        @Test
        @DisplayName("Control plane grants include Composer, SM, logging, evidence")
        void manifest_controlPlaneGrants() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(fullTopologyWithTenant()));
            var manifest = iamService.generateManifest(TENANT);

            @SuppressWarnings("unchecked")
            var grants = (List<Map<String, Object>>) manifest.get("controlPlaneGrants");
            assertFalse(grants.isEmpty());

            // Composer user grant
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/composer.user".equals(g.get("role"))));
            // Composer bucket storage grant
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/storage.objectAdmin".equals(g.get("role"))
                    && g.get("resource").toString().contains("acme-composer-bucket")));
            // Secret Manager grant
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/secretmanager.secretAccessor".equals(g.get("role"))));
            // Logging write grant
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/logging.logWriter".equals(g.get("role"))));
            // Evidence bucket write grant
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/storage.objectCreator".equals(g.get("role"))
                    && g.get("resource").toString().contains("acme-evidence-sink")));
            // Evidence BQ dataset grant
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/bigquery.dataEditor".equals(g.get("role"))
                    && g.get("resource").toString().contains("acme_evidence")));
        }

        @Test
        @DisplayName("Dataproc workload grants include submit, monitor, actAs, worker, BQ, Iceberg")
        void manifest_dataprocWorkloadGrants() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(fullTopologyWithTenant()));
            var manifest = iamService.generateManifest(TENANT);

            @SuppressWarnings("unchecked")
            var grants = (List<Map<String, Object>>) manifest.get("dataprocWorkloadGrants");
            assertFalse(grants.isEmpty());

            // Dataproc editor (submit/monitor)
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/dataproc.editor".equals(g.get("role"))));
            // Dataproc worker
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/dataproc.worker".equals(g.get("role"))));
            // actAs (serviceAccountUser)
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/iam.serviceAccountUser".equals(g.get("role"))
                    && g.get("resource").toString().contains("dataproc-workload@")));
            // Staging bucket
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/storage.objectAdmin".equals(g.get("role"))
                    && g.get("resource").toString().contains("acme-dataproc-staging")));
            // BQ job user
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/bigquery.jobUser".equals(g.get("role"))));
            // BQ data editor
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/bigquery.dataEditor".equals(g.get("role"))));
            // Iceberg bucket access
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/storage.objectAdmin".equals(g.get("role"))
                    && g.get("resource").toString().contains("acme-iceberg-storage")));
        }

        @Test
        @DisplayName("BigQuery connection grants include Iceberg bucket and connection user")
        void manifest_bqConnectionGrants() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(fullTopologyWithTenant()));
            var manifest = iamService.generateManifest(TENANT);

            @SuppressWarnings("unchecked")
            var grants = (List<Map<String, Object>>) manifest.get("bigqueryConnectionGrants");
            assertFalse(grants.isEmpty());

            // BQ connection SA → Iceberg bucket
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/storage.objectAdmin".equals(g.get("role"))
                    && g.get("resource").toString().contains("acme-iceberg-storage")));
            // BQ connection user
            assertTrue(grants.stream().anyMatch(g ->
                    "roles/bigquery.connectionUser".equals(g.get("role"))
                    && g.get("resource").toString().contains("acme-bq-conn")));
        }

        @Test
        @DisplayName("All grants include docReference for audit trail")
        void manifest_grantsHaveDocReferences() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(fullTopologyWithTenant()));
            var manifest = iamService.generateManifest(TENANT);

            @SuppressWarnings("unchecked")
            var cpGrants = (List<Map<String, Object>>) manifest.get("controlPlaneGrants");
            for (var grant : cpGrants) {
                assertNotNull(grant.get("docReference"),
                        "Grant " + grant.get("role") + " missing docReference");
                assertTrue(grant.get("docReference").toString().startsWith("https://"),
                        "docReference must be an HTTPS URL");
            }
        }

        @Test
        @DisplayName("Manifest includes doc references list")
        void manifest_topLevelDocReferences() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(fullTopologyWithTenant()));
            var manifest = iamService.generateManifest(TENANT);

            @SuppressWarnings("unchecked")
            var refs = (List<Map<String, String>>) manifest.get("docReferences");
            assertNotNull(refs);
            assertFalse(refs.isEmpty());
            assertTrue(refs.stream().anyMatch(r -> r.get("topic").contains("Composer")));
            assertTrue(refs.stream().anyMatch(r -> r.get("topic").contains("Dataproc")));
            assertTrue(refs.stream().anyMatch(r -> r.get("topic").contains("BigQuery")));
            assertTrue(refs.stream().anyMatch(r -> r.get("topic").contains("Secret Manager")));
        }

        @Test
        @DisplayName("iamBindingExecution is always OPERATOR_BLOCKED")
        void manifest_operatorBlocked() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(fullTopologyWithTenant()));
            var manifest = iamService.generateManifest(TENANT);
            assertEquals("OPERATOR_BLOCKED", manifest.get("iamBindingExecution"));
        }
    }

    // ================================================================
    // §3 — Readiness Category: Topology Completeness
    // ================================================================
    @Nested
    @DisplayName("§3 Readiness Category")
    class ReadinessCategory {

        @Mock private TenantGcpRuntimeTopologyRepository repo;
        @Mock private TenantService tenantService;

        private GcpRuntimeTopologyService service;

        @BeforeEach
        void setUp() {
            service = new GcpRuntimeTopologyService(repo, tenantService);
        }

        @Test
        @DisplayName("Ready when all topology fields configured and locations consistent")
        void ready_fullTopology() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(fullTopologyWithTenant()));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("ready", cat.get("status"));
            assertFalse(cat.containsKey("blockers"));
            assertTrue((Boolean) cat.get("hasComposer"));
            assertTrue((Boolean) cat.get("hasDataproc"));
            assertTrue((Boolean) cat.get("hasBigQuery"));
            assertTrue((Boolean) cat.get("hasBigQueryConnection"));
            assertTrue((Boolean) cat.get("hasIceberg"));
            assertTrue((Boolean) cat.get("hasEvidence"));
        }

        @Test
        @DisplayName("not_configured when no topology exists")
        void notConfigured_noTopology() {
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.empty());

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("not_configured", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s -> s.contains("No GCP runtime topology")));
        }

        @Test
        @DisplayName("Incomplete when Composer topology missing")
        void incomplete_missingComposer() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setComposerProjectId(null);
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s -> s.contains("Composer topology incomplete")));
        }

        @Test
        @DisplayName("Incomplete when Dataproc topology missing")
        void incomplete_missingDataproc() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setDataprocProjectId(null);
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s -> s.contains("Dataproc topology incomplete")));
        }

        @Test
        @DisplayName("Incomplete when Dataproc workload SA missing")
        void incomplete_missingDataprocSa() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setDataprocWorkloadSaEmail(null);
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s -> s.contains("Dataproc workload service account")));
        }

        @Test
        @DisplayName("Incomplete when BigQuery topology missing")
        void incomplete_missingBigQuery() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setBqProjectId(null);
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s -> s.contains("BigQuery topology incomplete")));
        }

        @Test
        @DisplayName("Incomplete when BigQuery medallion datasets missing")
        void incomplete_missingBqDatasets() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setBqDatasetBronze(null);
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s -> s.contains("medallion datasets incomplete")));
        }

        @Test
        @DisplayName("Incomplete when BigQuery connection missing")
        void incomplete_missingBqConnection() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setBqConnectionId(null);
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s -> s.contains("BigQuery connection not configured")));
        }

        @Test
        @DisplayName("Incomplete when Iceberg storage bucket missing")
        void incomplete_missingIceberg() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setIcebergStorageBucket(null);
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s -> s.contains("Iceberg storage bucket")));
        }

        @Test
        @DisplayName("Incomplete when evidence sink missing")
        void incomplete_missingEvidence() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setEvidenceSinkBucket(null);
            t.setEvidenceSinkDataset(null);
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s -> s.contains("Evidence sink not configured")));
        }
    }

    // ================================================================
    // §4 — Negative: Location Mismatch Blocks Readiness
    // ================================================================
    @Nested
    @DisplayName("§4 Location Mismatch Blocker")
    class LocationMismatch {

        @Mock private TenantGcpRuntimeTopologyRepository repo;
        @Mock private TenantService tenantService;

        private GcpRuntimeTopologyService service;

        @BeforeEach
        void setUp() {
            service = new GcpRuntimeTopologyService(repo, tenantService);
        }

        @Test
        @DisplayName("Composer/Dataproc region mismatch blocks readiness")
        void composerDataprocMismatch() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setComposerRegion("us-central1");
            t.setDataprocRegion("us-east1");
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s ->
                    s.contains("Location mismatch") && s.contains("Composer") && s.contains("Dataproc")));
        }

        @Test
        @DisplayName("Composer/BigQuery location mismatch blocks readiness")
        void composerBqMismatch() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setComposerRegion("us-central1");
            t.setBqLocation("EU");
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s ->
                    s.contains("Location mismatch") && s.contains("Composer") && s.contains("BigQuery")));
        }

        @Test
        @DisplayName("Dataproc/BigQuery location mismatch blocks readiness")
        void dataprocBqMismatch() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setDataprocRegion("europe-west1");
            t.setBqLocation("us-central1");
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s ->
                    s.contains("Location mismatch") && s.contains("Dataproc") && s.contains("BigQuery")));
        }

        @Test
        @DisplayName("BQ connection region/BQ location mismatch blocks readiness")
        void bqConnectionRegionMismatch() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setBqConnectionRegion("europe-west1");
            t.setBqLocation("us-central1");
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s ->
                    s.contains("BigQuery connection region")));
        }

        @Test
        @DisplayName("Consistent locations pass without mismatch blocker")
        void consistentLocations() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("ready", cat.get("status"));
            assertFalse(cat.containsKey("blockers"));
        }
    }

    // ================================================================
    // §5 — Negative: Missing BQ Connection SA Bucket Grants
    // ================================================================
    @Nested
    @DisplayName("§5 Missing BQ Connection SA Blocks Readiness")
    class MissingBqConnectionSa {

        @Mock private TenantGcpRuntimeTopologyRepository repo;
        @Mock private TenantService tenantService;

        private GcpRuntimeTopologyService service;

        @BeforeEach
        void setUp() {
            service = new GcpRuntimeTopologyService(repo, tenantService);
        }

        @Test
        @DisplayName("BQ connection configured but SA email missing blocks readiness")
        void bqConnectionWithoutSa() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setBqConnectionSaEmail(null);
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var cat = service.buildReadinessCategory(TENANT);
            assertEquals("incomplete", cat.get("status"));
            @SuppressWarnings("unchecked")
            var blockers = (List<String>) cat.get("blockers");
            assertTrue(blockers.stream().anyMatch(s ->
                    s.contains("BigQuery connection service account not configured")));
        }

        @Test
        @DisplayName("Missing BQ connection SA prevents IAM grant generation for BQ connection")
        void missingBqSa_noIamGrants() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            t.setBqConnectionSaEmail(null);
            t.setTenantId(TENANT);

            GcpIamManifestService iamService = new GcpIamManifestService(repo);
            when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(t));

            var manifest = iamService.generateManifest(TENANT);

            @SuppressWarnings("unchecked")
            var bqGrants = (List<Map<String, Object>>) manifest.get("bigqueryConnectionGrants");
            // No storage.objectAdmin grant for BQ connection since SA is missing
            assertFalse(bqGrants.stream().anyMatch(g ->
                    "roles/storage.objectAdmin".equals(g.get("role"))));
        }
    }

    // ================================================================
    // §6 — Controller Tests
    // ================================================================
    @Nested
    @DisplayName("§6 Controller API Surface")
    class ControllerTests {

        @Mock private GcpRuntimeTopologyService topologyService;
        @Mock private GcpIamManifestService iamManifestService;

        private TenantGcpRuntimeTopologyController controller;

        @BeforeEach
        void setUp() {
            controller = new TenantGcpRuntimeTopologyController(topologyService, iamManifestService);
        }

        @Test
        @DisplayName("GET topology returns 200 when configured")
        void getTopology_200() {
            TenantGcpRuntimeTopology t = fullTopologyWithTenant();
            when(topologyService.getTopology(TENANT)).thenReturn(Optional.of(t));
            when(topologyService.buildReadback(t)).thenReturn(Map.of("tenantId", TENANT));

            var response = controller.getTopology(TENANT);
            assertEquals(200, response.getStatusCode().value());
        }

        @Test
        @DisplayName("GET topology returns 404 when not configured")
        void getTopology_404() {
            when(topologyService.getTopology(TENANT)).thenReturn(Optional.empty());

            var ex = assertThrows(ResponseStatusException.class,
                    () -> controller.getTopology(TENANT));
            assertEquals(404, ex.getStatusCode().value());
        }

        @Test
        @DisplayName("PUT topology returns 200 on success")
        void setTopology_200() {
            TenantGcpRuntimeTopology saved = fullTopologyWithTenant();
            when(topologyService.setTopology(any(), any())).thenReturn(saved);
            when(topologyService.buildReadback(saved)).thenReturn(Map.of("tenantId", TENANT));

            TopologyRequest req = new TopologyRequest(
                    "proj", "env", "us-central1", "bucket", "dags/", "plugins/", "data/", "logs/",
                    "proj", "us-central1", "sa@proj.iam", "default", "default", "staging",
                    "proj", "us-central1", "bronze", "silver", "gold",
                    "conn", "us-central1", "bq@proj.iam",
                    "iceberg-bucket", "evidence-bucket", "evidence_ds",
                    "proj", "proj", "log-bucket", "cp@proj.iam"
            );
            var response = controller.setTopology(TENANT, req);
            assertEquals(200, response.getStatusCode().value());
        }

        @Test
        @DisplayName("GET IAM manifest returns 200")
        void getIamManifest_200() {
            when(iamManifestService.generateManifest(TENANT))
                    .thenReturn(Map.of("status", "generated"));

            var response = controller.getIamManifest(TENANT);
            assertEquals(200, response.getStatusCode().value());
            assertEquals("generated", response.getBody().get("status"));
        }

        @Test
        @DisplayName("TopologyRequest.toEntity() maps all fields correctly")
        void topologyRequest_toEntity() {
            TopologyRequest req = new TopologyRequest(
                    "proj", "env", "us-central1", "bucket", "dags/", "plugins/", "data/", "logs/",
                    "proj", "us-central1", "dp-sa@proj.iam", "net", "sub", "staging",
                    "proj", "us-central1", "bronze", "silver", "gold",
                    "conn", "us-central1", "bq-sa@proj.iam",
                    "iceberg", "evidence", "ev_ds",
                    "sm-proj", "log-proj", "log-bucket", "cp-sa@proj.iam"
            );
            TenantGcpRuntimeTopology t = req.toEntity();

            assertEquals("proj", t.getComposerProjectId());
            assertEquals("env", t.getComposerEnvironment());
            assertEquals("dp-sa@proj.iam", t.getDataprocWorkloadSaEmail());
            assertEquals("bronze", t.getBqDatasetBronze());
            assertEquals("conn", t.getBqConnectionId());
            assertEquals("bq-sa@proj.iam", t.getBqConnectionSaEmail());
            assertEquals("iceberg", t.getIcebergStorageBucket());
            assertEquals("evidence", t.getEvidenceSinkBucket());
            assertEquals("sm-proj", t.getSecretManagerProjectId());
            assertEquals("cp-sa@proj.iam", t.getControlPlaneSaEmail());
        }
    }

    // ================================================================
    // §7 — Readiness Controller Integration with PKT-0025
    // ================================================================
    @Nested
    @DisplayName("§7 Readiness Controller includes gcpRuntimeTopology")
    class ReadinessControllerIntegration {

        @Mock private com.pulse.tenant.service.TenantReadinessFoundationService foundationService;
        @Mock private com.pulse.git.service.GitHubRepoUrlValidator gitValidator;
        @Mock private com.pulse.storage.service.StorageScaffoldService scaffoldService;
        @Mock private com.pulse.tenant.service.DomainReadinessService domainReadinessService;
        @Mock private com.pulse.tenant.service.TenantRuntimeReadinessService runtimeReadinessService;
        @Mock private GcpRuntimeTopologyService topologyService;

        @Test
        @DisplayName("GET /readiness includes gcpRuntimeTopology category")
        void readiness_includesTopology() {
            var controller = new com.pulse.tenant.controller.TenantReadinessController(
                    foundationService, new com.pulse.git.service.GitHubRepoUrlValidator(),
                    scaffoldService, domainReadinessService,
                    runtimeReadinessService, topologyService, null);

            when(foundationService.getFoundation(TENANT)).thenReturn(Map.of("packet", "PKT-0010"));
            when(scaffoldService.buildReadinessCategory(TENANT)).thenReturn(Map.of("status", "previewed"));
            when(domainReadinessService.buildAllDomainReadiness(TENANT)).thenReturn(Map.of("ready", true));
            when(runtimeReadinessService.buildRuntimeBindingCategory(TENANT)).thenReturn(Map.of("status", "ready"));
            when(runtimeReadinessService.buildDeploymentTargetCategory(TENANT)).thenReturn(Map.of("status", "ready"));
            when(topologyService.buildReadinessCategory(TENANT)).thenReturn(Map.of("status", "ready"));

            var response = controller.getReadiness(TENANT);
            assertEquals(200, response.getStatusCode().value());

            var body = response.getBody();
            assertNotNull(body.get("gcpRuntimeTopology"));
            @SuppressWarnings("unchecked")
            var topology = (Map<String, Object>) body.get("gcpRuntimeTopology");
            assertEquals("ready", topology.get("status"));
        }
    }

    // ================================================================
    // §8 — Schema/Migration Contract: Entity Fields Match SQL
    // ================================================================
    @Nested
    @DisplayName("§8 Entity/Schema Contract")
    class EntitySchemaContract {

        @Test
        @DisplayName("Entity has all expected topology fields")
        void entity_hasAllFields() {
            TenantGcpRuntimeTopology t = new TenantGcpRuntimeTopology();

            // Composer
            t.setComposerProjectId("p"); t.setComposerEnvironment("e");
            t.setComposerRegion("r"); t.setComposerEnvironmentBucket("b");
            t.setComposerDagPrefix("d"); t.setComposerPluginsPrefix("p");
            t.setComposerDataPrefix("d"); t.setComposerLogPrefix("l");

            // Dataproc
            t.setDataprocProjectId("p"); t.setDataprocRegion("r");
            t.setDataprocWorkloadSaEmail("sa"); t.setDataprocNetwork("n");
            t.setDataprocSubnet("s"); t.setDataprocStagingBucket("b");

            // BigQuery
            t.setBqProjectId("p"); t.setBqLocation("l");
            t.setBqDatasetBronze("b"); t.setBqDatasetSilver("s"); t.setBqDatasetGold("g");

            // BQ Connection
            t.setBqConnectionId("c"); t.setBqConnectionRegion("r");
            t.setBqConnectionSaEmail("sa");

            // Iceberg, Evidence, SM, Logging, CP SA
            t.setIcebergStorageBucket("b");
            t.setEvidenceSinkBucket("b"); t.setEvidenceSinkDataset("d");
            t.setSecretManagerProjectId("p");
            t.setLoggingProjectId("p"); t.setLoggingLogBucket("b");
            t.setControlPlaneSaEmail("sa");

            // Verify all getters return what was set
            assertEquals("p", t.getComposerProjectId());
            assertEquals("e", t.getComposerEnvironment());
            assertEquals("sa", t.getDataprocWorkloadSaEmail());
            assertEquals("b", t.getBqDatasetBronze());
            assertEquals("c", t.getBqConnectionId());
            assertEquals("sa", t.getBqConnectionSaEmail());
            assertEquals("b", t.getIcebergStorageBucket());
            assertEquals("b", t.getEvidenceSinkBucket());
            assertEquals("p", t.getSecretManagerProjectId());
            assertEquals("sa", t.getControlPlaneSaEmail());
        }

        @Test
        @DisplayName("Entity extends BaseEntity with ULID id")
        void entity_extendsBaseEntity() {
            assertTrue(com.pulse.common.model.BaseEntity.class
                    .isAssignableFrom(TenantGcpRuntimeTopology.class));
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static TenantGcpRuntimeTopology fullTopology() {
        TenantGcpRuntimeTopology t = new TenantGcpRuntimeTopology();
        // Composer
        t.setComposerProjectId("acme-project");
        t.setComposerEnvironment("acme-composer-env");
        t.setComposerRegion("us-central1");
        t.setComposerEnvironmentBucket("acme-composer-bucket");
        t.setComposerDagPrefix("dags/");
        t.setComposerPluginsPrefix("plugins/");
        t.setComposerDataPrefix("data/");
        t.setComposerLogPrefix("logs/");
        // Dataproc
        t.setDataprocProjectId("acme-project");
        t.setDataprocRegion("us-central1");
        t.setDataprocWorkloadSaEmail("dataproc-workload@acme-project.iam.gserviceaccount.com");
        t.setDataprocNetwork("default");
        t.setDataprocSubnet("default");
        t.setDataprocStagingBucket("acme-dataproc-staging");
        // BigQuery
        t.setBqProjectId("acme-project");
        t.setBqLocation("us-central1");
        t.setBqDatasetBronze("acme_bronze");
        t.setBqDatasetSilver("acme_silver");
        t.setBqDatasetGold("acme_gold");
        // BQ Connection
        t.setBqConnectionId("acme-bq-conn");
        t.setBqConnectionRegion("us-central1");
        t.setBqConnectionSaEmail("bq-conn-sa@acme-project.iam.gserviceaccount.com");
        // Iceberg
        t.setIcebergStorageBucket("acme-iceberg-storage");
        // Evidence
        t.setEvidenceSinkBucket("acme-evidence-sink");
        t.setEvidenceSinkDataset("acme_evidence");
        // Secret Manager
        t.setSecretManagerProjectId("acme-project");
        // Logging
        t.setLoggingProjectId("acme-project");
        t.setLoggingLogBucket("acme-log-bucket");
        // Control plane
        t.setControlPlaneSaEmail("pulse-cp@acme-project.iam.gserviceaccount.com");
        return t;
    }

    private static TenantGcpRuntimeTopology fullTopologyWithTenant() {
        TenantGcpRuntimeTopology t = fullTopology();
        t.setTenantId(TENANT);
        return t;
    }
}
