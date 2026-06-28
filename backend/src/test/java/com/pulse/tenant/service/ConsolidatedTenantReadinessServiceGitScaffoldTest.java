package com.pulse.tenant.service;

import com.pulse.auth.service.TenantGcpConfigService;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.auth.service.TenantService;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.git.identity.UserGitIdentityRepository;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.repository.TenantRepoScaffoldItemRepository;
import com.pulse.git.service.GitHubRepoUrlValidator;
import com.pulse.runtime.repository.RuntimeBindingRepository;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.secret.service.SecretAuthorityReadinessService;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import com.pulse.tenant.model.ReadinessCategory;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-72 regression guard: {@link ConsolidatedTenantReadinessService#buildGitScaffold(String, Map)}
 * must report exclusively on per-domain {@code gitScaffold.status}.
 *
 * <p>The pre-fix code keyed off the combined {@code domainReadiness.ready}
 * flag, which folds in unrelated per-domain checks (storage scaffold, etc.).
 * That produced FAIL verdicts on the Git Scaffold card with a
 * {@code GIT_SCAFFOLD_INCOMPLETE} code even when every domain was, in fact,
 * git-scaffolded — the operator saw a category lying about its own evidence.
 *
 * <p>Cases covered:
 * <ul>
 *   <li>(a) all domains scaffolded, every other check ready → READY</li>
 *   <li>(b) all domains scaffolded, storage scaffold failing → READY (the bug)</li>
 *   <li>(c) some domains NOT scaffolded → BLOCKED with per-domain blocker</li>
 *   <li>(d) zero domains → READY (vacuous; other categories surface NO_DOMAINS)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConsolidatedTenantReadinessServiceGitScaffoldTest {

    private static final String TENANT = "acme-lending";

    // The service has many constructor dependencies; for THIS test we only
    // exercise the package-private buildGitScaffold(...) pure-function-style
    // method, so unused collaborators stay as bare mocks.
    @Mock private TenantService tenantService;
    @Mock private TenantGcpConfigService gcpConfigService;
    @Mock private TenantGcpCredentialService gcpCredentialService;
    @Mock private GitRepoRepository gitRepoRepository;
    @Mock private UserGitIdentityRepository userGitIdentityRepository;
    @Mock private SecretAuthorityReadinessService secretAuthorityReadinessService;
    @Mock private TenantGcpRuntimeTopologyRepository topologyRepository;
    @Mock private DomainRepository domainRepository;
    @Mock private TenantRepoScaffoldItemRepository scaffoldItemRepository;
    @Mock private StorageScaffoldStatusRepository storageScaffoldStatusRepository;
    @Mock private RuntimeBindingRepository bindingRepository;
    @Mock private DeploymentTargetRepository deploymentTargetRepository;
    @Mock private RuntimeAuthorityService runtimeAuthorityService;

    private ConsolidatedTenantReadinessService service;

    @BeforeEach
    void setUp() {
        var domainReadinessService = new DomainReadinessService(
                domainRepository, gitRepoRepository,
                scaffoldItemRepository, storageScaffoldStatusRepository);
        var runtimeReadinessService = new TenantRuntimeReadinessService(
                bindingRepository, deploymentTargetRepository, runtimeAuthorityService);
        var iamManifestService = new GcpIamManifestService(topologyRepository);
        var topologyService = new GcpRuntimeTopologyService(topologyRepository, tenantService);

        service = new ConsolidatedTenantReadinessService(
                tenantService, gcpConfigService, gcpCredentialService,
                gitRepoRepository, new GitHubRepoUrlValidator(),
                userGitIdentityRepository, iamManifestService,
                topologyService, topologyRepository,
                domainReadinessService, runtimeReadinessService,
                secretAuthorityReadinessService,
                /* githubClientEnabled = */ true);
    }

    @Test
    @DisplayName("(a) all domains scaffolded, combined-readiness=true → READY")
    void allDomainsScaffolded_combinedReady_categoryReady() {
        Map<String, Object> readiness = readinessMap(true, List.of(
                scaffoldedDomain("d1", "core-banking", "executed"),
                scaffoldedDomain("d2", "risk", "executed"),
                scaffoldedDomain("d3", "ops", "executed")));

        ReadinessCategory cat = service.buildGitScaffold(TENANT, readiness);

        assertEquals("ready", cat.status(), "all 3 domains scaffolded should be READY");
        assertTrue(cat.blockers().isEmpty(), "no blockers when category is ready");
        assertEquals(3L, cat.evidence().get("gitScaffoldedDomainCount"));
        assertEquals(3, cat.evidence().get("domainCount"));
    }

    @Test
    @DisplayName("(b) BUG-72 fix: all domains scaffolded but combined-readiness=false (storage failing) → still READY")
    void allDomainsScaffolded_butStorageBlocked_categoryStillReady() {
        // Combined readiness=false because storage is not executed on any
        // domain. Pre-fix this produced gitScaffold=BLOCKED with the
        // misleading GIT_SCAFFOLD_INCOMPLETE / "Domain readiness check not
        // fully ready" message even though every domain IS git-scaffolded.
        Map<String, Object> readiness = readinessMap(false, List.of(
                scaffoldedDomain("d1", "core-banking", "not_scaffolded"),
                scaffoldedDomain("d2", "risk", "not_scaffolded"),
                scaffoldedDomain("d3", "ops", "not_scaffolded")));

        ReadinessCategory cat = service.buildGitScaffold(TENANT, readiness);

        assertEquals("ready", cat.status(),
                "BUG-72: git scaffold category must not report storage-related failures");
        assertTrue(cat.blockers().isEmpty(),
                "no git-scaffold blockers when every domain IS git-scaffolded; got: " + cat.blockers());
        assertEquals(3L, cat.evidence().get("gitScaffoldedDomainCount"));
    }

    @Test
    @DisplayName("(c) one domain NOT scaffolded → BLOCKED with per-domain blocker")
    void partialScaffold_categoryBlockedWithPerDomainBlocker() {
        Map<String, Object> readiness = readinessMap(false, List.of(
                scaffoldedDomain("d1", "core-banking", "executed"),
                unscaffoldedDomain("d2", "risk", "blocked", "executed"),
                scaffoldedDomain("d3", "ops", "executed")));

        ReadinessCategory cat = service.buildGitScaffold(TENANT, readiness);

        assertEquals("blocked", cat.status());
        assertFalse(cat.blockers().isEmpty(), "expected at least one per-domain blocker");
        // The blocker message must name the offending domain (risk), not be
        // the generic "Domain readiness check not fully ready" fallback.
        var blocker = cat.blockers().get(0);
        assertEquals("GIT_SCAFFOLD_INCOMPLETE", blocker.code());
        assertTrue(blocker.message().contains("risk"),
                "blocker should name the unscaffolded domain; got: " + blocker.message());
        assertTrue(blocker.operatorRequired(),
                "REMOTE-repo blocked status requires operator action (PAT/identity)");
        assertEquals(2L, cat.evidence().get("gitScaffoldedDomainCount"));
    }

    @Test
    @DisplayName("(d) zero domains → READY (vacuous; NO_DOMAINS surfaces in storageScaffold/domainScaffold)")
    void zeroDomains_categoryVacuouslyReady() {
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("ready", false);
        readiness.put("domainCount", 0);
        readiness.put("domains", List.of());

        ReadinessCategory cat = service.buildGitScaffold(TENANT, readiness);

        assertEquals("ready", cat.status(),
                "zero domains is vacuously ready for THIS category; storageScaffold/domainScaffold "
                        + "surface the NO_DOMAINS blocker, no need to duplicate it here");
        assertTrue(cat.blockers().isEmpty());
        assertEquals(0, cat.evidence().get("domainCount"));
    }

    // -----------------------------------------------------------------
    //  Helpers — mirror the shape DomainReadinessService.buildAllDomainReadiness produces
    // -----------------------------------------------------------------

    private static Map<String, Object> readinessMap(boolean combinedReady,
                                                    List<Map<String, Object>> domains) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", TENANT);
        m.put("packet", "PKT-0013");
        m.put("ready", combinedReady);
        m.put("domainCount", domains.size());
        m.put("domains", domains);
        return m;
    }

    /** Domain with gitScaffold.status=scaffolded and configurable storage status. */
    private static Map<String, Object> scaffoldedDomain(String id, String slug, String storageStatus) {
        Map<String, Object> git = new LinkedHashMap<>();
        git.put("status", "scaffolded");
        git.put("repoType", "REMOTE");
        git.put("commitSha", "abc123");

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("status", storageStatus);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("tenantId", TENANT);
        d.put("domainId", id);
        d.put("domainName", slug);
        d.put("domainSlug", slug);
        // Combined per-domain readiness mirrors DomainReadinessService logic:
        // ready when git is terminal AND storage is terminal.
        d.put("ready", "scaffolded".equals(git.get("status"))
                && (storageStatus.equals("executed")
                    || storageStatus.equals("operator_blocked")
                    || storageStatus.equals("blocked")));
        d.put("gitScaffold", git);
        d.put("storageScaffold", storage);
        return d;
    }

    /** Domain whose git scaffold is not yet complete. */
    private static Map<String, Object> unscaffoldedDomain(String id, String slug,
                                                          String gitStatus, String storageStatus) {
        Map<String, Object> git = new LinkedHashMap<>();
        git.put("status", gitStatus);
        git.put("repoType", "REMOTE");
        if ("blocked".equals(gitStatus)) {
            git.put("blocker", "remote_identity_required");
        }

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("status", storageStatus);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("tenantId", TENANT);
        d.put("domainId", id);
        d.put("domainName", slug);
        d.put("domainSlug", slug);
        d.put("ready", false);
        d.put("gitScaffold", git);
        d.put("storageScaffold", storage);
        return d;
    }

    // Sanity reference: ensure the constructed test fixture exposes a
    // ReadinessCategory record. Without this the @Mock collaborators marked
    // private would trip "unused" warnings; the assertion is trivially true.
    @Test
    @DisplayName("sanity: buildGitScaffold returns a ReadinessCategory")
    void buildGitScaffold_returnsReadinessCategory() {
        ReadinessCategory cat = service.buildGitScaffold(TENANT, readinessMap(true, List.of()));
        assertNotNull(cat);
        assertEquals("gitScaffold", cat.name());
    }
}
