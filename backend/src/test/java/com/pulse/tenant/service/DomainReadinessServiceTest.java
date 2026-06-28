package com.pulse.tenant.service;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.git.model.GitRepo;
import com.pulse.git.model.TenantRepoScaffoldItem;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.repository.TenantRepoScaffoldItemRepository;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.storage.model.StorageScaffoldStatus;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * PKT-0013 — DomainReadinessService contract tests.
 *
 * Covers: domain create/read readback, Git scaffold preview with
 * missing/scaffolded/blocked states, storage scaffold integration,
 * combined readiness aggregation, idempotency, negative domain-row-only,
 * and remote Git PAT blocker.
 */
@ExtendWith(MockitoExtension.class)
class DomainReadinessServiceTest {

    private static final String TENANT_ID = "tenant-acme-lending";
    private static final String DOMAIN_ID = "domain-lending-1";
    private static final String DOMAIN_SLUG = "lending";
    private static final String GIT_REPO_ID = "repo-1";
    private static final String BRANCH = "main";
    private static final String GCP_PROJECT = "acme-lending-prod";
    private static final String SA_EMAIL = "sa@acme-lending-prod.iam.gserviceaccount.com";

    @Mock private DomainRepository domainRepository;
    @Mock private GitRepoRepository gitRepoRepository;
    @Mock private TenantRepoScaffoldItemRepository scaffoldItemRepository;
    @Mock private StorageScaffoldStatusRepository storageScaffoldStatusRepository;

    private DomainReadinessService service;

    @BeforeEach
    void setUp() {
        service = new DomainReadinessService(
                domainRepository, gitRepoRepository,
                scaffoldItemRepository, storageScaffoldStatusRepository);
    }

    // ================================================================
    //  1. Domain create/read — domain exists and is addressable
    // ================================================================

    @Test
    void buildDomainReadiness_domainExists_returnsDomainMetadata() {
        Domain domain = makeDomain();
        when(domainRepository.findById(DOMAIN_ID)).thenReturn(Optional.of(domain));
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.empty());
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.empty());

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        assertEquals(TENANT_ID, result.get("tenantId"));
        assertEquals(DOMAIN_ID, result.get("domainId"));
        assertEquals("Lending", result.get("domainName"));
        assertEquals(DOMAIN_SLUG, result.get("domainSlug"));
    }

    @Test
    void buildDomainReadiness_domainNotFound_throws() {
        when(domainRepository.findById("bogus")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.buildDomainReadiness(TENANT_ID, "bogus"));
    }

    @Test
    void buildDomainReadiness_domainWrongTenant_throws() {
        Domain domain = makeDomain();
        domain.setTenantId("other-tenant");
        when(domainRepository.findById(DOMAIN_ID)).thenReturn(Optional.of(domain));

        assertThrows(ResourceNotFoundException.class,
                () -> service.buildDomainReadiness(TENANT_ID, DOMAIN_ID));
    }

    // ================================================================
    //  2. Negative: domain row alone is NOT ready
    // ================================================================

    @Test
    void domainRowAlone_noGitRepo_noStorageScaffold_notReady() {
        setupDomain();
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.empty());
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.empty());

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        assertFalse((Boolean) result.get("ready"));

        @SuppressWarnings("unchecked")
        var git = (Map<String, Object>) result.get("gitScaffold");
        assertEquals("missing", git.get("status"));
        assertEquals("no_git_repo", git.get("reason"));

        @SuppressWarnings("unchecked")
        var storage = (Map<String, Object>) result.get("storageScaffold");
        assertEquals("not_scaffolded", storage.get("status"));
    }

    @Test
    void domainRowAlone_localRepoButNotScaffolded_notReady() {
        setupDomain();
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeLocalRepo()));
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.empty());
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.empty());

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        assertFalse((Boolean) result.get("ready"));

        @SuppressWarnings("unchecked")
        var git = (Map<String, Object>) result.get("gitScaffold");
        assertEquals("missing", git.get("status"));
        assertEquals("domain_not_scaffolded", git.get("reason"));
    }

    // ================================================================
    //  3. Git scaffold preview: missing / scaffolded / blocked / error
    // ================================================================

    @Test
    void gitScaffold_scaffolded_reportsScaffoldedWithCommitSha() {
        setupDomain();
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeLocalRepo()));
        TenantRepoScaffoldItem item = makeScaffoldItem("SCAFFOLDED", "abc123");
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.of(item));
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.empty());

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        @SuppressWarnings("unchecked")
        var git = (Map<String, Object>) result.get("gitScaffold");
        assertEquals("scaffolded", git.get("status"));
        assertEquals("abc123", git.get("commitSha"));
        assertNotNull(git.get("paths"));
    }

    @Test
    void gitScaffold_error_reportsErrorWithLastError() {
        setupDomain();
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeLocalRepo()));
        TenantRepoScaffoldItem item = makeScaffoldItem("ERROR", null);
        item.setLastError("disk full");
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.of(item));
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.empty());

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        @SuppressWarnings("unchecked")
        var git = (Map<String, Object>) result.get("gitScaffold");
        assertEquals("error", git.get("status"));
        assertEquals("disk full", git.get("lastError"));
    }

    @Test
    void gitScaffold_missingDomainPaths_alwaysIncluded() {
        setupDomain();
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.empty());
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.empty());

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        @SuppressWarnings("unchecked")
        var git = (Map<String, Object>) result.get("gitScaffold");
        @SuppressWarnings("unchecked")
        var paths = (List<String>) git.get("paths");
        assertNotNull(paths);
        assertFalse(paths.isEmpty());
        assertTrue(paths.stream().anyMatch(p -> p.contains(DOMAIN_SLUG)));
    }

    // ================================================================
    //  4. Remote Git blocked when PAT missing/unvalidated
    // ================================================================

    @Test
    void remoteGitBlocked_domainNotScaffolded_reportsBlocked() {
        setupDomain();
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeRemoteRepo()));
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.empty());
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.empty());

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        @SuppressWarnings("unchecked")
        var git = (Map<String, Object>) result.get("gitScaffold");
        assertEquals("blocked", git.get("status"));
        assertEquals("remote_identity_required", git.get("blocker"));
        assertTrue(((String) git.get("reason")).contains("PAT/identity"));
        // Preview paths still available even when blocked
        assertNotNull(git.get("paths"));
    }

    @Test
    void remoteGitBlocked_butPreviewPathsAvailable() {
        setupDomain();
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeRemoteRepo()));
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.empty());
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.empty());

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        @SuppressWarnings("unchecked")
        var git = (Map<String, Object>) result.get("gitScaffold");
        @SuppressWarnings("unchecked")
        var paths = (List<String>) git.get("paths");
        // Preview paths are always available regardless of blocked state
        assertEquals(4, paths.size());
        assertTrue(paths.stream().anyMatch(p -> p.contains(DOMAIN_SLUG + "/pipelines/")));
        assertTrue(paths.stream().anyMatch(p -> p.contains("intermediate/" + DOMAIN_SLUG)));
        assertTrue(paths.stream().anyMatch(p -> p.contains("marts/" + DOMAIN_SLUG)));
        assertTrue(paths.stream().anyMatch(p -> p.contains("snapshots/" + DOMAIN_SLUG)));
    }

    @Test
    void remoteGitScaffolded_notBlocked() {
        setupDomain();
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeRemoteRepo()));
        TenantRepoScaffoldItem item = makeScaffoldItem("SCAFFOLDED", "def456");
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.of(item));
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.empty());

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        @SuppressWarnings("unchecked")
        var git = (Map<String, Object>) result.get("gitScaffold");
        assertEquals("scaffolded", git.get("status"));
        assertEquals("REMOTE", git.get("repoType"));
    }

    // ================================================================
    //  5. Storage scaffold status integration
    // ================================================================

    @Test
    void storageScaffold_previewed_notTerminal() {
        setupDomain();
        setupGitScaffolded();
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.of(makeStorageStatus("previewed")));

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        @SuppressWarnings("unchecked")
        var storage = (Map<String, Object>) result.get("storageScaffold");
        assertEquals("previewed", storage.get("status"));
        assertEquals(GCP_PROJECT, storage.get("gcpProjectId"));

        // Git ready + Storage previewed = NOT ready (previewed is not terminal)
        assertFalse((Boolean) result.get("ready"));
    }

    @Test
    void storageScaffold_operatorBlocked_isTerminal() {
        setupDomain();
        setupGitScaffolded();
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.of(makeStorageStatus("operator_blocked")));

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        @SuppressWarnings("unchecked")
        var storage = (Map<String, Object>) result.get("storageScaffold");
        assertEquals("operator_blocked", storage.get("status"));

        // Git scaffolded + Storage operator_blocked = ready (both terminal)
        assertTrue((Boolean) result.get("ready"));
    }

    @Test
    void storageScaffold_executed_isTerminal() {
        setupDomain();
        setupGitScaffolded();
        StorageScaffoldStatus status = makeStorageStatus("executed");
        status.setLastExecutedAt(Instant.now());
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.of(status));

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        assertTrue((Boolean) result.get("ready"));
    }

    // ================================================================
    //  6. Combined domain readiness aggregation
    // ================================================================

    @Test
    void readiness_gitScaffolded_storageOperatorBlocked_ready() {
        setupDomain();
        setupGitScaffolded();
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.of(makeStorageStatus("operator_blocked")));

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);
        assertTrue((Boolean) result.get("ready"));
    }

    @Test
    void readiness_gitBlocked_storageBlocked_ready() {
        setupDomain();
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeRemoteRepo()));
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.empty());
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.of(makeStorageStatus("operator_blocked")));

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);
        // Git blocked + Storage blocked = ready (both terminal)
        assertTrue((Boolean) result.get("ready"));
    }

    @Test
    void readiness_gitScaffolded_storageNotScaffolded_notReady() {
        setupDomain();
        setupGitScaffolded();
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.empty());

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);
        assertFalse((Boolean) result.get("ready"));
    }

    @Test
    void readiness_gitMissing_storageExecuted_notReady() {
        setupDomain();
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeLocalRepo()));
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.empty());
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.of(makeStorageStatus("executed")));

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);
        // Git missing + Storage executed = NOT ready (git not terminal)
        assertFalse((Boolean) result.get("ready"));
    }

    // ================================================================
    //  7. All-domain readiness aggregation
    // ================================================================

    @Test
    void allDomains_noDomains_notReady() {
        when(domainRepository.findByTenantIdOrderByNameAsc(TENANT_ID))
                .thenReturn(Collections.emptyList());

        var result = service.buildAllDomainReadiness(TENANT_ID);

        assertFalse((Boolean) result.get("ready"));
        assertEquals(0, result.get("domainCount"));
        assertEquals("PKT-0013", result.get("packet"));
    }

    @Test
    void allDomains_allReady_overallReady() {
        Domain lending = makeDomain();
        Domain insurance = makeDomain("domain-ins-2", "insurance", "Insurance");
        when(domainRepository.findByTenantIdOrderByNameAsc(TENANT_ID))
                .thenReturn(List.of(lending, insurance));

        // Git scaffolded for both
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeLocalRepo()));
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.of(makeScaffoldItem("SCAFFOLDED", "sha1")));
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, "domain-ins-2"))
                .thenReturn(Optional.of(makeScaffoldItem("SCAFFOLDED", "sha2")));

        // Storage terminal for both
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.of(makeStorageStatus("operator_blocked")));
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, "insurance"))
                .thenReturn(Optional.of(makeStorageStatus("executed")));

        var result = service.buildAllDomainReadiness(TENANT_ID);

        assertTrue((Boolean) result.get("ready"));
        assertEquals(2, result.get("domainCount"));

        @SuppressWarnings("unchecked")
        var domains = (List<Map<String, Object>>) result.get("domains");
        assertEquals(2, domains.size());
        assertTrue((Boolean) domains.get(0).get("ready"));
        assertTrue((Boolean) domains.get(1).get("ready"));
    }

    @Test
    void allDomains_oneNotReady_overallNotReady() {
        Domain lending = makeDomain();
        Domain insurance = makeDomain("domain-ins-2", "insurance", "Insurance");
        when(domainRepository.findByTenantIdOrderByNameAsc(TENANT_ID))
                .thenReturn(List.of(lending, insurance));

        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeLocalRepo()));
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.of(makeScaffoldItem("SCAFFOLDED", "sha1")));
        // Insurance not scaffolded in git
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, "domain-ins-2"))
                .thenReturn(Optional.empty());

        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.of(makeStorageStatus("operator_blocked")));
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, "insurance"))
                .thenReturn(Optional.of(makeStorageStatus("operator_blocked")));

        var result = service.buildAllDomainReadiness(TENANT_ID);

        // Lending ready, Insurance not (git missing) → overall not ready
        assertFalse((Boolean) result.get("ready"));
    }

    // ================================================================
    //  8. Idempotency — repeated calls return same result
    // ================================================================

    @Test
    void readiness_repeatedCalls_returnIdenticalResults() {
        setupDomain();
        setupGitScaffolded();
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.of(makeStorageStatus("operator_blocked")));

        var result1 = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);
        var result2 = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        assertEquals(result1.get("ready"), result2.get("ready"));
        assertEquals(result1.get("gitScaffold"), result2.get("gitScaffold"));
        assertEquals(result1.get("storageScaffold"), result2.get("storageScaffold"));
    }

    @Test
    void allDomainReadiness_repeatedCalls_deterministic() {
        Domain lending = makeDomain();
        when(domainRepository.findByTenantIdOrderByNameAsc(TENANT_ID))
                .thenReturn(List.of(lending));
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeLocalRepo()));
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.of(makeScaffoldItem("SCAFFOLDED", "sha")));
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.of(makeStorageStatus("operator_blocked")));

        var r1 = service.buildAllDomainReadiness(TENANT_ID);
        var r2 = service.buildAllDomainReadiness(TENANT_ID);

        assertEquals(r1.get("ready"), r2.get("ready"));
        assertEquals(r1.get("domainCount"), r2.get("domainCount"));
    }

    // ================================================================
    //  9. Security: no secret material in readback
    // ================================================================

    @Test
    void readiness_noSecretMaterialInOutput() {
        setupDomain();
        setupGitScaffolded();
        StorageScaffoldStatus status = makeStorageStatus("operator_blocked");
        when(storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, DOMAIN_SLUG))
                .thenReturn(Optional.of(status));

        var result = service.buildDomainReadiness(TENANT_ID, DOMAIN_ID);

        String serialized = result.toString();
        assertFalse(serialized.contains("private_key"), "Must not contain private_key");
        assertFalse(serialized.contains("BEGIN RSA"), "Must not contain RSA key material");
        assertFalse(serialized.contains("BEGIN PRIVATE"), "Must not contain private key PEM");
        assertFalse(serialized.contains("encrypted_credential"),
                "Must not contain encrypted credential");
        assertFalse(serialized.contains("pat_token"), "Must not contain PAT token");
    }

    // ================================================================
    //  Fixture Helpers
    // ================================================================

    private void setupDomain() {
        when(domainRepository.findById(DOMAIN_ID)).thenReturn(Optional.of(makeDomain()));
    }

    private void setupGitScaffolded() {
        when(gitRepoRepository.findByTenantIdAndScope(TENANT_ID, "TENANT"))
                .thenReturn(Optional.of(makeLocalRepo()));
        when(scaffoldItemRepository.findByGitRepoIdAndBranchNameAndDomainId(
                GIT_REPO_ID, BRANCH, DOMAIN_ID))
                .thenReturn(Optional.of(makeScaffoldItem("SCAFFOLDED", "abc123")));
    }

    private Domain makeDomain() {
        return makeDomain(DOMAIN_ID, DOMAIN_SLUG, "Lending");
    }

    private Domain makeDomain(String id, String slug, String name) {
        Domain d = new Domain();
        d.setId(id);
        d.setTenantId(TENANT_ID);
        d.setName(name);
        d.setSlug(slug);
        return d;
    }

    private GitRepo makeLocalRepo() {
        GitRepo r = new GitRepo();
        r.setId(GIT_REPO_ID);
        r.setTenantId(TENANT_ID);
        r.setScope("TENANT");
        r.setRepoType("LOCAL");
        r.setCurrentBranch(BRANCH);
        r.setDefaultBranch(BRANCH);
        r.setLocalPath("/data/pulse/repos/acme-lending");
        return r;
    }

    private GitRepo makeRemoteRepo() {
        GitRepo r = makeLocalRepo();
        r.setRepoType("REMOTE");
        r.setRepoUrl("https://github.com/acme/lending-repo.git");
        r.setProvider("GITHUB");
        return r;
    }

    private TenantRepoScaffoldItem makeScaffoldItem(String status, String commitSha) {
        TenantRepoScaffoldItem item = new TenantRepoScaffoldItem();
        item.setTenantId(TENANT_ID);
        item.setGitRepoId(GIT_REPO_ID);
        item.setBranchName(BRANCH);
        item.setItemType("DOMAIN");
        item.setDomainId(DOMAIN_ID);
        item.setDomainSlug(DOMAIN_SLUG);
        item.setStatus(status);
        item.setLastCommitSha(commitSha);
        item.setLastScaffoldedAt(Instant.now());
        return item;
    }

    private StorageScaffoldStatus makeStorageStatus(String status) {
        StorageScaffoldStatus s = new StorageScaffoldStatus();
        s.setTenantId(TENANT_ID);
        s.setDomainSlug(DOMAIN_SLUG);
        s.setStatus(status);
        s.setGcpProjectId(GCP_PROJECT);
        s.setServiceAccountEmail(SA_EMAIL);
        s.setCredentialSource("tenant_postgres");
        s.setEntryCount(13);
        s.setLastPreviewedAt(Instant.now());
        return s;
    }
}
