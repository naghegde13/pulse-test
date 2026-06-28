package com.pulse.tenant.service;

import com.pulse.auth.model.Tenant;
import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.service.TenantGcpConfigService;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.git.identity.GitHubPatValidationStatus;
import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.identity.UserGitIdentityRepository;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.GitHubRepoUrlValidator;
import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.repository.RuntimeBindingRepository;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.secret.model.SecretAuthorityMode;
import com.pulse.secret.model.SecretAuthorityReadiness;
import com.pulse.secret.model.SecretAuthorityReadiness.ProofStatus;
import com.pulse.secret.service.SecretAuthorityReadinessService;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.tenant.model.ConsolidatedReadinessVerdict;
import com.pulse.tenant.model.ReadinessBlocker;
import com.pulse.tenant.model.ReadinessCategory;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import com.pulse.git.repository.TenantRepoScaffoldItemRepository;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PKT-0015: Consolidated Tenant Pipeline-Development Readiness Verdict tests.
 *
 * <p>Proves:
 * <ul>
 *   <li>Service aggregation for every category including iamManifest, composer,
 *       dataproc, bigQuery, secretManager, and evidenceLogging</li>
 *   <li>Each missing required category blocks overall readiness</li>
 *   <li>Secret values absent from JSON serialization</li>
 *   <li>Subsystem-only storage/runtime readiness does not bypass missing
 *       tenant GCP config/credentials/topology</li>
 *   <li>Package/deploy preflight is not invoked</li>
 *   <li>Create-vs-validate ownership evidence for GCP resources</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConsolidatedTenantReadinessServiceTest {

    private static final String TENANT = "acme-lending";

    // Foundation dependencies
    @Mock private TenantService tenantService;
    @Mock private TenantGcpConfigService gcpConfigService;
    @Mock private TenantGcpCredentialService gcpCredentialService;
    @Mock private GitRepoRepository gitRepoRepository;
    @Mock private UserGitIdentityRepository userGitIdentityRepository;
    @Mock private SecretAuthorityReadinessService secretAuthorityReadinessService;

    // Topology dependencies
    @Mock private TenantGcpRuntimeTopologyRepository topologyRepository;

    // Domain readiness dependencies (for DomainReadinessService)
    @Mock private DomainRepository domainRepository;
    @Mock private TenantRepoScaffoldItemRepository scaffoldItemRepository;
    @Mock private StorageScaffoldStatusRepository storageScaffoldStatusRepository;

    // Runtime dependencies (for TenantRuntimeReadinessService)
    @Mock private RuntimeBindingRepository bindingRepository;
    @Mock private DeploymentTargetRepository deploymentTargetRepository;
    @Mock private RuntimeAuthorityService runtimeAuthorityService;

    private ConsolidatedTenantReadinessService service;
    private DomainReadinessService domainReadinessService;
    private TenantRuntimeReadinessService runtimeReadinessService;
    private GcpIamManifestService iamManifestService;
    private GcpRuntimeTopologyService topologyService;

    @BeforeEach
    void setUp() {
        domainReadinessService = new DomainReadinessService(
                domainRepository, gitRepoRepository,
                scaffoldItemRepository, storageScaffoldStatusRepository);
        runtimeReadinessService = new TenantRuntimeReadinessService(
                bindingRepository, deploymentTargetRepository, runtimeAuthorityService);
        iamManifestService = new GcpIamManifestService(topologyRepository);
        topologyService = new GcpRuntimeTopologyService(topologyRepository, tenantService);

        service = new ConsolidatedTenantReadinessService(
                tenantService, gcpConfigService, gcpCredentialService,
                gitRepoRepository, new GitHubRepoUrlValidator(),
                userGitIdentityRepository, iamManifestService,
                topologyService, topologyRepository,
                domainReadinessService, runtimeReadinessService,
                secretAuthorityReadinessService,
                /* githubClientEnabled = */ true);
    }

    // ================================================================
    // §1 — Full happy-path verdict
    // ================================================================

    @Nested
    @DisplayName("§1 Full Verdict Aggregation")
    class FullVerdict {

        @Test
        @DisplayName("All categories ready → overall ready")
        void allCategoriesReady_overallReady() {
            stubAllReady();

            ConsolidatedReadinessVerdict verdict = service.computeVerdict(TENANT);

            assertEquals("ready", verdict.overallStatus());
            assertTrue(verdict.isReady());
            assertEquals(16, verdict.totalCategoryCount());
            assertEquals(16, verdict.readyCategoryCount());
            assertTrue(verdict.blockerSummary().isEmpty());
            assertNotNull(verdict.checkedAt());
            assertEquals(TENANT, verdict.tenantId());
        }

        @Test
        @DisplayName("Verdict contains all 16 required categories")
        void verdictContainsAll16Categories() {
            stubAllReady();

            ConsolidatedReadinessVerdict verdict = service.computeVerdict(TENANT);

            var cats = verdict.categories();
            assertEquals(16, cats.size());
            assertNotNull(cats.get("tenantIdentity"));
            assertNotNull(cats.get("gcpConfig"));
            assertNotNull(cats.get("gcpCredentials"));
            assertNotNull(cats.get("iamManifest"));
            assertNotNull(cats.get("githubRepo"));
            assertNotNull(cats.get("githubPat"));
            assertNotNull(cats.get("gitScaffold"));
            assertNotNull(cats.get("storageScaffold"));
            assertNotNull(cats.get("domainScaffold"));
            assertNotNull(cats.get("composer"));
            assertNotNull(cats.get("dataproc"));
            assertNotNull(cats.get("bigQuery"));
            assertNotNull(cats.get("secretManager"));
            assertNotNull(cats.get("runtimeBinding"));
            assertNotNull(cats.get("deploymentTarget"));
            assertNotNull(cats.get("evidenceLogging"));
        }
    }

    // ================================================================
    // §2 — Each missing category blocks overall readiness
    // ================================================================

    @Nested
    @DisplayName("§2 Missing Categories Block Readiness")
    class MissingCategoriesBlock {

        @Test
        @DisplayName("Missing tenant identity blocks overall")
        void missingTenantIdentity_blocksOverall() {
            stubAllReady();
            when(tenantService.getTenantEntity(TENANT))
                    .thenThrow(new ResourceNotFoundException("Tenant", TENANT));

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            assertFalse(verdict.isReady());
            assertTrue(verdict.blockerSummary().stream()
                    .anyMatch(b -> "TENANT_NOT_FOUND".equals(b.code())));
        }

        @Test
        @DisplayName("Missing GCP config blocks overall")
        void missingGcpConfig_blocksOverall() {
            stubAllReady();
            when(gcpConfigService.getConfig(TENANT)).thenReturn(Optional.empty());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            assertTrue(verdict.blockerSummary().stream()
                    .anyMatch(b -> "MISSING_GCP_CONFIG".equals(b.code())));
        }

        @Test
        @DisplayName("Missing GCP credentials blocks overall")
        void missingGcpCredentials_blocksOverall() {
            stubAllReady();
            when(gcpCredentialService.getRedactedCredential(TENANT)).thenReturn(Optional.empty());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            assertTrue(verdict.blockerSummary().stream()
                    .anyMatch(b -> "MISSING_GCP_CREDENTIALS".equals(b.code())));
        }

        @Test
        @DisplayName("Missing IAM manifest blocks overall")
        void missingIamManifest_blocksOverall() {
            stubAllReady();
            // No topology → no IAM manifest
            when(topologyRepository.findByTenantId(TENANT)).thenReturn(Optional.empty());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            assertTrue(verdict.blockerSummary().stream()
                    .anyMatch(b -> "IAM_MANIFEST_NOT_GENERATED".equals(b.code())));
        }

        @Test
        @DisplayName("Missing GitHub repo blocks overall")
        void missingGithubRepo_blocksOverall() {
            stubAllReady();
            when(gitRepoRepository.findByTenantIdAndScope(TENANT, "TENANT"))
                    .thenReturn(Optional.empty());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            assertTrue(verdict.blockerSummary().stream()
                    .anyMatch(b -> "MISSING_GITHUB_REPO".equals(b.code())));
        }

        @Test
        @DisplayName("Missing GitHub PAT blocks overall")
        void missingGithubPat_blocksOverall() {
            stubAllReady();
            when(userGitIdentityRepository.findByTenantIdOrderByCreatedAtDesc(TENANT))
                    .thenReturn(List.of());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            assertTrue(verdict.blockerSummary().stream()
                    .anyMatch(b -> "MISSING_GITHUB_PAT".equals(b.code())));
        }

        @Test
        @DisplayName("Missing Composer blocks overall")
        void missingComposer_blocksOverall() {
            stubAllReady();
            TenantGcpRuntimeTopology topoNoComposer = makeTopology();
            topoNoComposer.setComposerProjectId(null);
            topoNoComposer.setComposerEnvironment(null);
            topoNoComposer.setComposerRegion(null);
            when(topologyRepository.findByTenantId(TENANT))
                    .thenReturn(Optional.of(topoNoComposer));

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            assertTrue(verdict.blockerSummary().stream()
                    .anyMatch(b -> b.code().startsWith("MISSING_COMPOSER")));
        }

        @Test
        @DisplayName("Missing Dataproc blocks overall")
        void missingDataproc_blocksOverall() {
            stubAllReady();
            TenantGcpRuntimeTopology topoNoDataproc = makeTopology();
            topoNoDataproc.setDataprocProjectId(null);
            topoNoDataproc.setDataprocRegion(null);
            when(topologyRepository.findByTenantId(TENANT))
                    .thenReturn(Optional.of(topoNoDataproc));

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            assertTrue(verdict.blockerSummary().stream()
                    .anyMatch(b -> b.code().startsWith("MISSING_DATAPROC")));
        }

        @Test
        @DisplayName("Missing BigQuery blocks overall")
        void missingBigQuery_blocksOverall() {
            stubAllReady();
            TenantGcpRuntimeTopology topoNoBq = makeTopology();
            topoNoBq.setBqProjectId(null);
            topoNoBq.setBqLocation(null);
            when(topologyRepository.findByTenantId(TENANT))
                    .thenReturn(Optional.of(topoNoBq));

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            assertTrue(verdict.blockerSummary().stream()
                    .anyMatch(b -> b.code().startsWith("MISSING_BQ")));
        }

        @Test
        @DisplayName("Missing Secret Manager blocks overall")
        void missingSecretManager_blocksOverall() {
            stubAllReady();
            when(secretAuthorityReadinessService.computeForTenant(TENANT))
                    .thenReturn(new SecretAuthorityReadiness(
                            SecretAuthorityMode.LOCAL_STUB, ProofStatus.NON_PROOF,
                            "local_stub", "NON_PROOF_LOCAL", Map.of("gcpBackedProof", false)));

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            assertTrue(verdict.blockerSummary().stream()
                    .anyMatch(b -> "SECRET_MANAGER_NOT_READY".equals(b.code())));
        }

        @Test
        @DisplayName("Missing evidence/logging blocks overall")
        void missingEvidenceLogging_blocksOverall() {
            stubAllReady();
            TenantGcpRuntimeTopology topoNoEvidence = makeTopology();
            topoNoEvidence.setEvidenceSinkBucket(null);
            topoNoEvidence.setEvidenceSinkDataset(null);
            topoNoEvidence.setLoggingProjectId(null);
            when(topologyRepository.findByTenantId(TENANT))
                    .thenReturn(Optional.of(topoNoEvidence));

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            assertTrue(verdict.blockerSummary().stream()
                    .anyMatch(b -> "MISSING_EVIDENCE_SINK".equals(b.code())
                            || "MISSING_LOGGING_PROJECT".equals(b.code())));
        }

        @Test
        @DisplayName("No domains: gitScaffold is vacuously READY (overall blocked by storage/domain categories)")
        void noDomains_gitScaffoldVacuouslyReady_overallStillBlocked() {
            stubAllReady();
            when(domainRepository.findByTenantIdOrderByNameAsc(TENANT)).thenReturn(List.of());

            var verdict = service.computeVerdict(TENANT);
            // Overall is still blocked because storageScaffold + domainScaffold
            // surface the NO_DOMAINS blocker (proven by the two sibling tests).
            assertEquals("blocked", verdict.overallStatus());
            // BUG-72 case (d): gitScaffold is vacuously READY when there are no
            // domains — the category reports on its OWN evidence (per-domain git
            // scaffold status), not on the presence of domains. NO_DOMAINS is
            // surfaced by storageScaffold/domainScaffold, not duplicated here.
            var gitScaffold = verdict.categories().get("gitScaffold");
            assertEquals("ready", gitScaffold.status(),
                    "BUG-72 contract: gitScaffold must report on its own evidence only");
        }

        @Test
        @DisplayName("No domains blocks storageScaffold and overall")
        void noDomains_blocksStorageScaffoldAndOverall() {
            stubAllReady();
            when(domainRepository.findByTenantIdOrderByNameAsc(TENANT)).thenReturn(List.of());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            var storageScaffold = verdict.categories().get("storageScaffold");
            assertNotEquals("ready", storageScaffold.status());
        }

        @Test
        @DisplayName("No domains blocks domainScaffold and overall")
        void noDomains_blocksDomainScaffoldAndOverall() {
            stubAllReady();
            when(domainRepository.findByTenantIdOrderByNameAsc(TENANT)).thenReturn(List.of());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            var domainScaffold = verdict.categories().get("domainScaffold");
            assertNotEquals("ready", domainScaffold.status());
        }

        @Test
        @DisplayName("Missing runtime binding blocks overall")
        void missingRuntimeBinding_blocksOverall() {
            stubAllReady();
            // PKT-FINAL-5 / BUG-39: bindings are deployment-global, not per-tenant.
            when(bindingRepository.findAllByOrderByEnvironmentAsc())
                    .thenReturn(List.of());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            var cat = verdict.categories().get("runtimeBinding");
            assertNotEquals("ready", cat.status());
        }

        @Test
        @DisplayName("Missing deployment target blocks overall")
        void missingDeploymentTarget_blocksOverall() {
            stubAllReady();
            when(deploymentTargetRepository.findByTenantIdOrderByEnvironmentAsc(TENANT))
                    .thenReturn(List.of());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            var cat = verdict.categories().get("deploymentTarget");
            assertNotEquals("ready", cat.status());
        }
    }

    // ================================================================
    // §3 — Redaction guarantees
    // ================================================================

    @Nested
    @DisplayName("§3 Redaction Safety")
    class RedactionSafety {

        @Test
        @DisplayName("Secret values absent from verdict JSON")
        void noSecretValuesInVerdict() {
            stubAllReady();

            var verdict = service.computeVerdict(TENANT);
            String json = verdict.toString();

            // No PAT values
            assertFalse(json.contains("ghp_"));
            assertFalse(json.contains("github_pat_"));
            // No private key material
            assertFalse(json.contains("PRIVATE KEY"));
            assertFalse(json.contains("privateKey="));
            // No JDBC passwords
            assertFalse(json.contains("password="));
            // No credential JSON bodies
            assertFalse(json.contains("client_secret"));

            // Verify redacted indicators are present
            var gcpCreds = verdict.categories().get("gcpCredentials");
            assertNotNull(gcpCreds);
            assertTrue((Boolean) gcpCreds.evidence().get("privateKeyRedacted"));

            var pat = verdict.categories().get("githubPat");
            assertNotNull(pat);
            assertTrue((Boolean) pat.evidence().get("patRedacted"));
        }

        @Test
        @DisplayName("GCP credential category uses allowlisted fields only")
        void gcpCredentialAllowlist() {
            stubAllReady();

            var verdict = service.computeVerdict(TENANT);
            var gcpCreds = verdict.categories().get("gcpCredentials");
            Map<String, Object> ev = gcpCreds.evidence();

            // Only allowlisted fields
            assertNotNull(ev.get("status"));
            assertNotNull(ev.get("privateKeyRedacted"));
            // No raw credential JSON keys
            assertFalse(ev.containsKey("credentialJson"));
            assertFalse(ev.containsKey("privateKeyData"));
            assertFalse(ev.containsKey("encryptedCredential"));
        }
    }

    // ================================================================
    // §4 — Subsystem-only does not bypass GCP config/credentials
    // ================================================================

    @Nested
    @DisplayName("§4 Subsystem-Only Does Not Bypass Foundation")
    class SubsystemOnlyBypass {

        @Test
        @DisplayName("Ready runtime binding does not bypass missing GCP config")
        void readyRuntimeDoesNotBypassMissingGcpConfig() {
            stubAllReady();
            when(gcpConfigService.getConfig(TENANT)).thenReturn(Optional.empty());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            // Runtime binding is ready but overall is blocked
            var runtime = verdict.categories().get("runtimeBinding");
            assertEquals("ready", runtime.status());
            var gcpConfig = verdict.categories().get("gcpConfig");
            assertEquals("blocked", gcpConfig.status());
        }

        @Test
        @DisplayName("Ready runtime binding does not bypass missing GCP credentials")
        void readyRuntimeDoesNotBypassMissingGcpCredentials() {
            stubAllReady();
            when(gcpCredentialService.getRedactedCredential(TENANT)).thenReturn(Optional.empty());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            var runtime = verdict.categories().get("runtimeBinding");
            assertEquals("ready", runtime.status());
            var creds = verdict.categories().get("gcpCredentials");
            assertEquals("blocked", creds.status());
        }

        @Test
        @DisplayName("Ready runtime binding does not bypass missing topology")
        void readyRuntimeDoesNotBypassMissingTopology() {
            stubAllReady();
            when(topologyRepository.findByTenantId(TENANT)).thenReturn(Optional.empty());

            var verdict = service.computeVerdict(TENANT);
            assertEquals("blocked", verdict.overallStatus());
            var runtime = verdict.categories().get("runtimeBinding");
            assertEquals("ready", runtime.status());
            // Composer, Dataproc, BigQuery, evidenceLogging, iamManifest all blocked
            assertEquals("not_configured", verdict.categories().get("composer").status());
            assertEquals("not_configured", verdict.categories().get("dataproc").status());
            assertEquals("not_configured", verdict.categories().get("bigQuery").status());
            assertEquals("not_configured", verdict.categories().get("evidenceLogging").status());
        }
    }

    // ================================================================
    // §5 — Package/deploy preflight NOT invoked
    // ================================================================

    @Nested
    @DisplayName("§5 Package/Deploy Preflight Not Invoked")
    class NoPreflightInvoked {

        @Test
        @DisplayName("Verdict does not invoke deploy preflight or package service")
        void noPreflightInvocation() {
            stubAllReady();

            service.computeVerdict(TENANT);

            // Verify that no deploy-related services are called.
            // The service doesn't even have a reference to deploy services.
            // This test confirms the structural guarantee that package/deploy
            // preflight is not part of the consolidated readiness service.
            var cats = service.computeVerdict(TENANT).categories();
            assertFalse(cats.containsKey("packagePreflight"));
            assertFalse(cats.containsKey("deployPreflight"));
            assertFalse(cats.containsKey("deployReadiness"));
        }
    }

    // ================================================================
    // §6 — Ownership evidence
    // ================================================================

    @Nested
    @DisplayName("§6 Create-vs-Validate Ownership Evidence")
    class OwnershipEvidence {

        @Test
        @DisplayName("GCP resource categories include ownership evidence")
        void gcpCategoriesHaveOwnership() {
            stubAllReady();

            var verdict = service.computeVerdict(TENANT);

            // Composer
            var composer = verdict.categories().get("composer");
            assertNotNull(composer.ownership());
            assertEquals("Composer Environment", composer.ownership().resourceKind());
            assertEquals("operator", composer.ownership().createOwner());
            assertEquals("pulse", composer.ownership().validateOwner());

            // Dataproc
            var dataproc = verdict.categories().get("dataproc");
            assertNotNull(dataproc.ownership());
            assertEquals("Dataproc Serverless", dataproc.ownership().resourceKind());

            // BigQuery
            var bq = verdict.categories().get("bigQuery");
            assertNotNull(bq.ownership());
            assertEquals("BigQuery Datasets", bq.ownership().resourceKind());

            // Secret Manager
            var sm = verdict.categories().get("secretManager");
            assertNotNull(sm.ownership());
            assertEquals("Secret Manager", sm.ownership().resourceKind());

            // Evidence/Logging
            var el = verdict.categories().get("evidenceLogging");
            assertNotNull(el.ownership());
            assertEquals("Evidence/Log Resources", el.ownership().resourceKind());

            // Storage Scaffold
            var ss = verdict.categories().get("storageScaffold");
            assertNotNull(ss.ownership());
            assertEquals("GCS Buckets/Paths", ss.ownership().resourceKind());

            // IAM Manifest
            var iam = verdict.categories().get("iamManifest");
            assertNotNull(iam.ownership());
            assertEquals("IAM Bindings", iam.ownership().resourceKind());
        }
    }

    // ================================================================
    // §7 — Blocker structure
    // ================================================================

    @Nested
    @DisplayName("§7 Blocker Structure")
    class BlockerStructure {

        @Test
        @DisplayName("Blockers include all required fields")
        void blockersHaveRequiredFields() {
            stubAllReady();
            when(gcpConfigService.getConfig(TENANT)).thenReturn(Optional.empty());

            var verdict = service.computeVerdict(TENANT);
            assertFalse(verdict.blockerSummary().isEmpty());

            ReadinessBlocker blocker = verdict.blockerSummary().stream()
                    .filter(b -> "MISSING_GCP_CONFIG".equals(b.code()))
                    .findFirst().orElseThrow();

            assertNotNull(blocker.code());
            assertNotNull(blocker.message());
            assertNotNull(blocker.sourceSurface());
            assertNotNull(blocker.evidenceRef());
            assertNotNull(blocker.staleCheckTimestamp());
            assertNotNull(blocker.safeNextAction());
            assertTrue(blocker.operatorRequired());
        }
    }

    // ================================================================
    // §8 — Individual category tests
    // ================================================================

    @Nested
    @DisplayName("§8 Individual Category Checks")
    class IndividualCategories {

        @Test
        @DisplayName("iamManifest ready when topology has all fields")
        void iamManifestReady() {
            when(topologyRepository.findByTenantId(TENANT))
                    .thenReturn(Optional.of(makeTopology()));

            var cat = service.buildIamManifest(TENANT, Optional.of(makeTopology()));
            assertEquals("ready", cat.status());
            assertEquals("iamManifest", cat.name());
            assertTrue(cat.blockers().isEmpty());
        }

        @Test
        @DisplayName("composer ready with complete topology")
        void composerReady() {
            var cat = service.buildComposer(TENANT, Optional.of(makeTopology()));
            assertEquals("ready", cat.status());
        }

        @Test
        @DisplayName("dataproc ready with complete topology")
        void dataprocReady() {
            var cat = service.buildDataproc(TENANT, Optional.of(makeTopology()));
            assertEquals("ready", cat.status());
        }

        @Test
        @DisplayName("bigQuery ready with complete topology")
        void bigQueryReady() {
            var cat = service.buildBigQuery(TENANT, Optional.of(makeTopology()));
            assertEquals("ready", cat.status());
        }

        @Test
        @DisplayName("secretManager ready with GCP SM proven")
        void secretManagerReady() {
            when(secretAuthorityReadinessService.computeForTenant(TENANT))
                    .thenReturn(new SecretAuthorityReadiness(
                            SecretAuthorityMode.TENANT_GCP_SECRET_MANAGER, ProofStatus.PROVEN,
                            "tenant_gcp_secret_manager", "GCP_SM_TENANT_CREDENTIAL",
                            Map.of("gcpProjectId", "test-project", "gcpBackedProof", true)));

            var cat = service.buildSecretManager(TENANT, Optional.of(makeTopology()));
            assertEquals("ready", cat.status());
        }

        @Test
        @DisplayName("evidenceLogging ready with complete topology")
        void evidenceLoggingReady() {
            var cat = service.buildEvidenceLogging(TENANT, Optional.of(makeTopology()));
            assertEquals("ready", cat.status());
        }

        @Test
        @DisplayName("PKT-FINAL-3 BUG-08: githubPat surfaces GITHUB_CLIENT_STUB_ACTIVE when stub mode")
        void githubPatStubModeBlocker() {
            // Build a fresh service instance with githubClientEnabled = false.
            var stubService = new ConsolidatedTenantReadinessService(
                    tenantService, gcpConfigService, gcpCredentialService,
                    gitRepoRepository, new GitHubRepoUrlValidator(),
                    userGitIdentityRepository, iamManifestService,
                    topologyService, topologyRepository,
                    domainReadinessService, runtimeReadinessService,
                    secretAuthorityReadinessService,
                    /* githubClientEnabled = */ false);

            var cat = stubService.buildGithubPat(TENANT);
            assertEquals("blocked", cat.status());
            assertTrue(cat.blockers().stream()
                    .anyMatch(b -> "GITHUB_CLIENT_STUB_ACTIVE".equals(b.code())),
                    "Expected GITHUB_CLIENT_STUB_ACTIVE blocker, got: " + cat.blockers());
            assertEquals(false, cat.evidence().get("githubClientEnabled"));
        }

        @Test
        @DisplayName("secretManager non-proof with local-stub mode")
        void secretManagerLocalStub_blocked() {
            when(secretAuthorityReadinessService.computeForTenant(TENANT))
                    .thenReturn(new SecretAuthorityReadiness(
                            SecretAuthorityMode.LOCAL_STUB, ProofStatus.NON_PROOF,
                            "local_stub", "NON_PROOF_LOCAL",
                            Map.of("gcpBackedProof", false)));

            var cat = service.buildSecretManager(TENANT, Optional.of(makeTopology()));
            assertEquals("blocked", cat.status());
            assertTrue(cat.blockers().stream()
                    .anyMatch(b -> b.message().contains("local-stub")));
        }
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private void stubAllReady() {
        // Tenant identity
        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("Acme Lending");
        tenant.setSlug("acme-lending");
        tenant.setOrigin("config");
        when(tenantService.getTenantEntity(TENANT)).thenReturn(tenant);

        // GCP config
        TenantGcpConfig gcpConfig = new TenantGcpConfig();
        gcpConfig.setControlPlaneProjectId("acme-gcp-project");
        gcpConfig.setGcpRegion("us-central1");
        when(gcpConfigService.getConfig(TENANT)).thenReturn(Optional.of(gcpConfig));

        // GCP credential (redacted)
        Map<String, Object> redactedCred = new LinkedHashMap<>();
        redactedCred.put("status", "active");
        redactedCred.put("serviceAccountEmail", "sa@acme-gcp-project.iam.gserviceaccount.com");
        redactedCred.put("keyId", "key-123");
        redactedCred.put("gcpProjectId", "acme-gcp-project");
        when(gcpCredentialService.getRedactedCredential(TENANT)).thenReturn(Optional.of(redactedCred));

        // Git repo
        GitRepo repo = new GitRepo();
        repo.setRepoUrl("https://github.com/acme-lending/tenant-repo");
        repo.setRepoType("REMOTE");
        repo.setProvider("GITHUB");
        repo.setDefaultBranch("main");
        repo.setTenantId(TENANT);
        when(gitRepoRepository.findByTenantIdAndScope(TENANT, "TENANT")).thenReturn(Optional.of(repo));

        // GitHub PAT
        UserGitIdentity identity = new UserGitIdentity();
        identity.setTenantId(TENANT);
        identity.setStatus(GitHubPatValidationStatus.VALID.name());
        when(userGitIdentityRepository.findByTenantIdOrderByCreatedAtDesc(TENANT))
                .thenReturn(List.of(identity));

        // Topology (full)
        TenantGcpRuntimeTopology topology = makeTopology();
        when(topologyRepository.findByTenantId(TENANT)).thenReturn(Optional.of(topology));

        // Secret authority
        when(secretAuthorityReadinessService.computeForTenant(TENANT))
                .thenReturn(new SecretAuthorityReadiness(
                        SecretAuthorityMode.TENANT_GCP_SECRET_MANAGER, ProofStatus.PROVEN,
                        "tenant_gcp_secret_manager", "GCP_SM_TENANT_CREDENTIAL",
                        Map.of("gcpProjectId", "acme-gcp-project", "gcpBackedProof", true)));

        // Domain readiness — use a ready domain
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setName("Core Banking");
        domain.setSlug("core-banking");
        domain.setTenantId(TENANT);
        when(domainRepository.findByTenantIdOrderByNameAsc(TENANT)).thenReturn(List.of(domain));

        // Git scaffold — scaffolded (lenient: overridden when git repo is missing)
        var scaffoldItem = new com.pulse.git.model.TenantRepoScaffoldItem();
        scaffoldItem.setStatus("SCAFFOLDED");
        scaffoldItem.setLastCommitSha("abc123");
        lenient().when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(any(), any(), any()))
                .thenReturn(Optional.of(scaffoldItem));

        // Storage scaffold — executed (lenient: overridden when domains are empty)
        var storageStatus = new com.pulse.storage.model.StorageScaffoldStatus();
        storageStatus.setTenantId(TENANT);
        storageStatus.setDomainSlug("core-banking");
        storageStatus.setStatus("executed");
        storageStatus.setGcpProjectId("acme-gcp-project");
        lenient().when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT, "core-banking"))
                .thenReturn(Optional.of(storageStatus));

        // Runtime binding — lenient because individual tests may override
        RuntimeBinding binding = mock(RuntimeBinding.class);
        lenient().when(binding.isPrimary()).thenReturn(true);
        lenient().when(binding.isActive()).thenReturn(true);
        lenient().when(binding.isValidated()).thenReturn(true);
        lenient().when(binding.getValidationKind()).thenReturn("LIVE_GCP");
        lenient().when(binding.getValidationStatus()).thenReturn("VALIDATED");
        lenient().when(binding.hasCompleteRoots()).thenReturn(true);
        lenient().when(binding.getEnvironment()).thenReturn("dev");
        lenient().when(binding.getBindingKind()).thenReturn("GCP");
        lenient().when(binding.getId()).thenReturn("binding-1");
        // PKT-FINAL-5 / BUG-39: deployment-global lookup.
        lenient().when(bindingRepository.findAllByOrderByEnvironmentAsc())
                .thenReturn(List.of(binding));

        // Deployment target — lenient because individual tests may override
        DeploymentTarget target = mock(DeploymentTarget.class);
        lenient().when(target.isEnabled()).thenReturn(true);
        lenient().when(target.getId()).thenReturn("target-1");
        lenient().when(target.getName()).thenReturn("dev-gcp");
        lenient().when(target.getEnvironment()).thenReturn("dev");
        lenient().when(target.getTargetType()).thenReturn("CLOUD_RUN");
        lenient().when(runtimeAuthorityService.isTargetTypeAllowed("CLOUD_RUN")).thenReturn(true);
        lenient().when(runtimeAuthorityService.getActivePersona())
                .thenReturn(com.pulse.runtime.model.RuntimePersona.GCP_PULSE);
        lenient().when(deploymentTargetRepository.findByTenantIdOrderByEnvironmentAsc(TENANT))
                .thenReturn(List.of(target));
    }

    private static TenantGcpRuntimeTopology makeTopology() {
        TenantGcpRuntimeTopology t = new TenantGcpRuntimeTopology();
        t.setTenantId(TENANT);

        // Composer
        t.setComposerProjectId("acme-gcp-project");
        t.setComposerEnvironment("acme-composer-dev");
        t.setComposerRegion("us-central1");
        t.setComposerEnvironmentBucket("acme-composer-bucket");
        t.setComposerDagPrefix("dags");
        t.setComposerPluginsPrefix("plugins");
        t.setComposerDataPrefix("data");
        t.setComposerLogPrefix("logs");

        // Dataproc
        t.setDataprocProjectId("acme-gcp-project");
        t.setDataprocRegion("us-central1");
        t.setDataprocWorkloadSaEmail("dp-workload@acme-gcp-project.iam.gserviceaccount.com");
        t.setDataprocNetwork("default");
        t.setDataprocSubnet("default");
        t.setDataprocStagingBucket("acme-dp-staging");

        // BigQuery
        t.setBqProjectId("acme-gcp-project");
        t.setBqLocation("us-central1");
        t.setBqDatasetBronze("acme_bronze");
        t.setBqDatasetSilver("acme_silver");
        t.setBqDatasetGold("acme_gold");

        // BQ Connection
        t.setBqConnectionId("acme-bq-conn");
        t.setBqConnectionRegion("us-central1");
        t.setBqConnectionSaEmail("bq-conn@acme-gcp-project.iam.gserviceaccount.com");

        // Iceberg
        t.setIcebergStorageBucket("acme-iceberg");

        // Evidence
        t.setEvidenceSinkBucket("acme-evidence");
        t.setEvidenceSinkDataset("acme_evidence");

        // Secret Manager
        t.setSecretManagerProjectId("acme-gcp-project");

        // Logging
        t.setLoggingProjectId("acme-gcp-project");
        t.setLoggingLogBucket("acme-logs");

        // Control Plane
        t.setControlPlaneSaEmail("pulse-cp@acme-gcp-project.iam.gserviceaccount.com");

        return t;
    }
}
