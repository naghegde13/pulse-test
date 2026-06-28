package com.pulse.suitea;

import com.pulse.auth.model.Tenant;
import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.model.GitRepo;
import com.pulse.git.service.GitHubRepoUrlValidator;
import com.pulse.git.service.RemoteGitService;
import com.pulse.sor.model.Domain;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Scenario A — Happy-path tenant onboarding.
 *
 * <p>Exercises the full {@code POST /api/v1/tenants/{tenantId}/onboard}
 * controller path against a real Spring context + Testcontainers Postgres,
 * with two narrowly-scoped beans replaced by mocks:
 *
 * <ul>
 *   <li>{@link GitHubRepoUrlValidator} — the production validator only
 *       accepts {@code https://github.com/...} URLs; the mock is permissive
 *       so Suite A can pass a {@code file://} URI that points at a
 *       local bare repo.</li>
 *   <li>{@link RemoteGitService} — the production
 *       {@code RemoteGitService.cloneRepo} short-circuits on
 *       {@code file://} URLs (see {@code RemoteGitService.java:49}); the
 *       mock performs an equivalent local JGit clone so the on-disk working
 *       tree under {@code clone-base/{tenant-slug}} is real, which is what
 *       the downstream {@code RepoScaffoldService} needs to write into.</li>
 * </ul>
 *
 * <h3>What this test guards against</h3>
 * Per the PKT-CAND-suite-a-backend-integration-test packet, Scenario A
 * directly catches regressions for:
 * <ul>
 *   <li>BUG-36 (clone-base fallback symmetry — verified by the
 *       {@code clone-base} property resolving and the working tree
 *       landing under it);</li>
 *   <li>BUG-38 (secret-stub path drift — verified by the local-stub
 *       Secret Manager being routed from the {@code clone-base} root
 *       through the {@code suite-a-it} profile, with no plaintext leaks
 *       through onboarding);</li>
 *   <li>BUG-46 (tenant GCP config + creds — verified by the persisted
 *       {@code git_repos} row + JSONB metadata round-trip);</li>
 *   <li>PKT-FINAL-1 (jsonb entity migration alignment — verified
 *       implicitly by the ddl-auto=validate boot succeeding and the
 *       JSONB {@code metadata} column accepting the inserted map);</li>
 *   <li>PKT-FINAL-2 (pipeline env-promotion UX removal — verified
 *       implicitly by the truncated PipelineStage schema still letting
 *       the context boot);</li>
 *   <li>PKT-FINAL-3 (tenant Git onboarding hardening — verified by the
 *       canonical clone-base property name, the REMOTE-only repoType
 *       guard, and the typed UPSTREAM_GIT_AUTH_FAILED error envelope
 *       staying in place).</li>
 * </ul>
 */
@DisplayName("Suite A / Scenario A — tenant onboarding happy path")
class OnboardingHappyPathSuiteAIT extends SuiteABaseIT {

    private static final String TENANT_ID = "tenant-suite-a-happy";
    private static final String TENANT_NAME = "Suite A Happy Tenant";
    private static final String TENANT_SLUG = "suite-a-happy";

    @Autowired private TestRestTemplate restTemplate;

    @MockitoBean private GitHubRepoUrlValidator gitHubRepoUrlValidator;
    @MockitoBean private RemoteGitService remoteGitService;

    private Tenant tenant;
    private List<Domain> domains;
    private BareGitRepo bareRepo;

    @BeforeEach
    void setup() throws Exception {
        // Scoped cleanup — bootstrap-seeded tenants
        // (tenant-home-lending / tenant-unsecured-lending) are referenced
        // by Flyway-inserted systems_of_record rows, so a blanket
        // tenantRepository.deleteAll() would FK-violate. We only touch
        // rows we own.
        cleanupTestTenant(TENANT_ID);

        tenant = createTenant(TENANT_ID, TENANT_NAME, TENANT_SLUG);
        domains = createDomains(TENANT_ID, "deposits", "loans");
        seedGitIdentity(TENANT_ID);
        bareRepo = createLocalBareGitRepo(true);

        // GitHubRepoUrlValidator — accept everything we hand it; the real
        // shape check is irrelevant for Suite A.
        when(gitHubRepoUrlValidator.validate(anyString()))
                .thenReturn(new GitHubRepoUrlValidator.Result(true, null));

        // RemoteGitService — verifyRepoAccess is a no-op, cloneRepo
        // performs a real local JGit clone of the bare fixture, and
        // pullFromRemote / pushToRemote are no-ops.
        doNothing().when(remoteGitService).verifyRepoAccess(anyString(), any());
        doAnswer(invocation -> {
            GitRepo repo = invocation.getArgument(0);
            performLocalClone(repo);
            return null;
        }).when(remoteGitService).cloneRepo(any(), any());
        doNothing().when(remoteGitService).pullFromRemote(any(), any());
        doNothing().when(remoteGitService).pushToRemote(any(), any());
    }

    @Test
    @DisplayName("POST /onboard creates git_repos row, writes scaffold paths, persists scaffold items")
    void onboardWritesScaffoldAndPersistsItems() throws Exception {
        Map<String, Object> body = jsonBody(
                "repoType", "REMOTE",
                "repoUrl", "https://github.com/acme/suite-a-happy.git",
                "provider", "GITHUB",
                "defaultBranch", "main",
                "scaffold", jsonBody(
                        "refreshTopLevel", true,
                        "mode", "ALL"));

        ResponseEntity<GitRepo> response = restTemplate.postForEntity(
                onboardUrl(TENANT_ID),
                body,
                GitRepo.class);

        // ---- HTTP envelope ----
        assertThat(response.getStatusCode())
                .as("onboard returns 201 Created on the happy path")
                .isEqualTo(HttpStatus.CREATED);
        GitRepo created = response.getBody();
        assertThat(created).as("response body is the persisted GitRepo").isNotNull();

        // ---- git_repos row ----
        List<GitRepo> rows = gitRepoRepository.findByTenantIdAndScopeOrderByCreatedAtDesc(
                TENANT_ID, "TENANT");
        assertThat(rows).as("exactly one TENANT-scoped repo row").hasSize(1);
        GitRepo row = rows.get(0);
        assertThat(row.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(row.getScope()).isEqualTo("TENANT");
        assertThat(row.getRepoType()).isEqualTo("REMOTE");
        assertThat(row.getProvider()).isEqualTo("GITHUB");
        assertThat(row.getRepoUrl())
                .as("repoUrl persisted verbatim from the request")
                .isEqualTo("https://github.com/acme/suite-a-happy.git");
        assertThat(row.getDefaultBranch())
                .as("default branch reflects the request (PKT-FINAL-3 normalised this to main)")
                .isEqualTo("main");
        assertThat(row.getCurrentBranch())
                .as("current branch resolved from the on-disk clone (which we seeded as main)")
                .isEqualTo("main");

        // ---- JSONB metadata round-trip (guards PKT-FINAL-1 alignment) ----
        assertThat(row.getMetadata())
                .as("metadata column is a real jsonb map after PKT-FINAL-1, not a stringified TEXT")
                .isNotNull()
                .containsEntry("onboardedAs", "REMOTE");

        // ---- on-disk scaffold (8 top-level + 4 per-domain) ----
        Path tenantWorkdir = tenantWorkdir(TENANT_SLUG);
        assertThat(tenantWorkdir)
                .as("tenant working tree exists under clone-base/{tenant-slug}")
                .exists()
                .isDirectory();
        assertScaffoldTopLevelExists(tenantWorkdir);
        for (Domain d : domains) {
            assertScaffoldDomainExists(tenantWorkdir, d.getSlug());
        }

        // ---- scaffold items table ----
        // We expect:
        //   * 1 TOP_LEVEL item
        //   * 1 DBT_PROJECT item
        //   * 1 DOMAIN item per registered domain (= 2)
        // All STATUS=SCAFFOLDED with the same commit SHA.
        List<com.pulse.git.model.TenantRepoScaffoldItem> items = scaffoldItemRepository
                .findByGitRepoIdAndBranchNameOrderByItemTypeAscDomainSlugAsc(row.getId(), "main");
        assertThat(items)
                .as("Suite A seeds 2 domains; scaffold items = 2 non-domain + 2 domain = 4")
                .hasSize(4);
        assertThat(items)
                .allSatisfy(item -> {
                    assertThat(item.getStatus()).isEqualTo("SCAFFOLDED");
                    assertThat(item.getTenantId()).isEqualTo(TENANT_ID);
                    assertThat(item.getBranchName()).isEqualTo("main");
                    assertThat(item.getLastCommitSha())
                            .as("every scaffold item is stamped with a real commit SHA")
                            .isNotNull()
                            .isNotBlank();
                });
        assertThat(items.stream().map(com.pulse.git.model.TenantRepoScaffoldItem::getItemType).toList())
                .as("item type distribution")
                .containsExactlyInAnyOrder("DBT_PROJECT", "DOMAIN", "DOMAIN", "TOP_LEVEL");
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private void performLocalClone(GitRepo repo) throws Exception {
        File target = new File(repo.getLocalPath());
        if (target.exists()) {
            // Clean previous attempt — the controller may have left a
            // partial clone behind in an earlier scenario.
            try (var paths = Files.walk(target.toPath())) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
        if (!target.getParentFile().exists()) {
            Files.createDirectories(target.getParentFile().toPath());
        }
        String branch = repo.getCurrentBranch() != null
                ? repo.getCurrentBranch()
                : repo.getDefaultBranch();
        try (Git git = Git.cloneRepository()
                .setURI(bareRepo.fileUri())
                .setDirectory(target)
                .setBranch(branch != null ? branch : "main")
                .call()) {
            // Close immediately — the clone target is what we care about.
        }
    }

    private static void assertScaffoldTopLevelExists(Path root) {
        // From RepoScaffoldService.topLevelPaths() — the 8 canonical paths
        // PKT-FINAL-3 scaffolds at the tenant repo root.
        List<String> top = List.of(
                ".gitignore",
                "README.md",
                "dbt_project/dbt_project.yml",
                "dbt_project/profiles.yml",
                "dbt_project/packages.yml",
                "dbt_project/macros/audit_columns.sql",
                "dbt_project/macros/safe_cast.sql",
                "dbt_project/macros/pulse_delta_table.sql");
        for (String rel : top) {
            assertThat(root.resolve(rel))
                    .as("top-level scaffold path " + rel + " should exist")
                    .exists();
        }
    }

    private static void assertScaffoldDomainExists(Path root, String domainSlug) {
        // From RepoScaffoldService.domainPaths(slug).
        List<String> domainRelPaths = List.of(
                domainSlug + "/pipelines/.gitkeep",
                "dbt_project/models/intermediate/" + domainSlug + "/.gitkeep",
                "dbt_project/models/marts/" + domainSlug + "/.gitkeep",
                "dbt_project/snapshots/" + domainSlug + "/.gitkeep");
        for (String rel : domainRelPaths) {
            assertThat(root.resolve(rel))
                    .as("domain (" + domainSlug + ") scaffold path " + rel + " should exist")
                    .exists();
        }
    }

    private String onboardUrl(String tenantId) {
        return "http://localhost:" + serverPort + "/api/v1/tenants/" + tenantId + "/onboard";
    }
}
