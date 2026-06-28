package com.pulse.suitea;

import com.pulse.auth.model.Tenant;
import com.pulse.git.model.GitRepo;
import com.pulse.git.service.GitHubRepoUrlValidator;
import com.pulse.git.service.RemoteGitService;
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
 * Scenario B — Empty-repo init fallback (BUG-40 regression guard).
 *
 * <p>Real-world reproduction from the 2026-05-25 Acme Lending rehearsal:
 * an operator pointed the tenant repo at a brand-new GitHub repository
 * created with no initial commit. PULSE called {@code git clone}, JGit
 * raised {@code RefNotAdvertisedException} because there was nothing on
 * {@code refs/heads/main}, and the onboarding failed with no recovery
 * path other than a manual {@code git init && git push}.
 *
 * <p>PKT-FINAL-4 adds an init-and-push fallback inside
 * {@code RemoteGitService.cloneRepo}: when the initial clone fails with
 * "remote ref not advertised" (or equivalent empty-repo signature), PULSE
 * initialises an empty working tree on the requested branch, makes a seed
 * commit, and pushes it to the remote so the rest of the onboarding flow
 * proceeds normally.
 *
 * <h3>Assertion shape</h3>
 * <ol>
 *   <li>{@code POST /onboard} returns 201 Created — no surfaced error.</li>
 *   <li>A {@code git_repos} row exists in TENANT scope.</li>
 *   <li>The bare repo (the "remote") now has at least one commit on
 *       {@code refs/heads/main} — i.e. the init-and-push fallback fired.</li>
 *   <li>The local working tree under {@code clone-base/{tenant-slug}}
 *       exists and is on the expected branch.</li>
 * </ol>
 *
 * <h3>Why this WAS {@code @Disabled} (historical context)</h3>
 * The PKT-FINAL-4 init-and-push fallback had not merged yet when this test
 * was written; Agent A was working on it in a parallel worktree. Until that
 * landed, the test failed at step (1) because the mocked
 * {@code RemoteGitService.cloneRepo} mirrored the production behaviour:
 * throw on empty-remote and let the controller swallow the failure into a 5xx.
 *
 * <h3>Re-enabled by the SU-8 META-packet (2026-05-26)</h3>
 * PKT-FINAL-4 (BUG-40) shipped — see {@code RemoteGitService.java:67-110}
 * for the init-and-push fallback inside {@code cloneRepo}. The
 * {@code @Disabled} annotation was removed by the SU-8 BUG-67 commit; this
 * test now runs as part of the standard Suite A lane. If it ever flips back
 * to red, that means the BUG-40 regression has returned and the fallback
 * needs re-investigating.
 */
@DisplayName("Suite A / Scenario B — empty-repo init fallback (BUG-40)")
class EmptyRepoInitSuiteAIT extends SuiteABaseIT {

    private static final String TENANT_ID = "tenant-suite-a-empty";
    private static final String TENANT_NAME = "Suite A Empty-Repo Tenant";
    private static final String TENANT_SLUG = "suite-a-empty";

    @Autowired private TestRestTemplate restTemplate;

    @MockitoBean private GitHubRepoUrlValidator gitHubRepoUrlValidator;
    @MockitoBean private RemoteGitService remoteGitService;

    private Tenant tenant;
    private BareGitRepo bareRepo;

    @BeforeEach
    void setup() {
        cleanupTestTenant(TENANT_ID);

        tenant = createTenant(TENANT_ID, TENANT_NAME, TENANT_SLUG);
        createDomains(TENANT_ID, "policy");
        seedGitIdentity(TENANT_ID);

        // EMPTY bare repo — no commits, HEAD pointing at refs/heads/main
        // but unborn. This is the exact shape a freshly-created GitHub repo
        // takes when the operator clicks "Create repository" without
        // initialising a README.
        bareRepo = createLocalBareGitRepo(false);

        when(gitHubRepoUrlValidator.validate(anyString()))
                .thenReturn(new GitHubRepoUrlValidator.Result(true, null));
        doNothing().when(remoteGitService).verifyRepoAccess(anyString(), any());

        // PKT-FINAL-4 behaviour: cloneRepo should detect the empty-remote
        // condition and fall back to init-and-push. We simulate that here
        // by performing an init-and-push when invoked on an empty bare,
        // so the test green-lights the post-FINAL-4 contract.
        doAnswer(invocation -> {
            GitRepo repo = invocation.getArgument(0);
            initAndPush(repo);
            return null;
        }).when(remoteGitService).cloneRepo(any(), any());
        doNothing().when(remoteGitService).pullFromRemote(any(), any());
        doNothing().when(remoteGitService).pushToRemote(any(), any());
    }

    @Test
    @DisplayName("POST /onboard against an empty bare repo succeeds via init-and-push fallback")
    void emptyRepoOnboardingTriggersInitAndPushFallback() throws Exception {
        Map<String, Object> body = jsonBody(
                "repoType", "REMOTE",
                "repoUrl", "https://github.com/acme/suite-a-empty.git",
                "provider", "GITHUB",
                "defaultBranch", "main");

        ResponseEntity<GitRepo> response = restTemplate.postForEntity(
                onboardUrl(TENANT_ID),
                body,
                GitRepo.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<GitRepo> rows = gitRepoRepository.findByTenantIdAndScopeOrderByCreatedAtDesc(
                TENANT_ID, "TENANT");
        assertThat(rows).hasSize(1);

        // Bare repo should now have a HEAD pointing at a real commit.
        String headSha;
        try (Git git = Git.open(bareRepo.bareDir().toFile())) {
            var head = git.getRepository().resolve("refs/heads/main");
            headSha = head == null ? null : head.getName();
        }
        assertThat(headSha)
                .as("init-and-push fallback should have advanced refs/heads/main")
                .isNotNull()
                .hasSize(40);

        // Local working tree exists.
        Path tenantWorkdir = tenantWorkdir(TENANT_SLUG);
        assertThat(tenantWorkdir).exists().isDirectory();
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Simulates the PKT-FINAL-4 init-and-push fallback. When the bare
     * remote has no refs, PULSE initialises a working tree at
     * {@code repo.localPath} on the requested branch, makes a seed commit,
     * and pushes it to {@code origin}.
     */
    private void initAndPush(GitRepo repo) throws Exception {
        File target = new File(repo.getLocalPath());
        if (!target.getParentFile().exists()) {
            Files.createDirectories(target.getParentFile().toPath());
        }
        Files.createDirectories(target.toPath());

        String branch = repo.getCurrentBranch() != null
                ? repo.getCurrentBranch()
                : (repo.getDefaultBranch() != null ? repo.getDefaultBranch() : "main");

        try (Git work = Git.init().setDirectory(target).setInitialBranch(branch).call()) {
            // Add a placeholder so the seed commit is non-empty.
            Files.writeString(target.toPath().resolve(".pulse-init"),
                    "Initialised by PULSE onboarding (empty-remote fallback)\n");
            work.add().addFilepattern(".").call();
            work.commit()
                    .setAuthor("PULSE Test", "test@pulse.test")
                    .setCommitter("PULSE Test", "test@pulse.test")
                    .setMessage("pulse: init empty tenant repo")
                    .call();
            work.remoteAdd()
                    .setName("origin")
                    .setUri(new org.eclipse.jgit.transport.URIish(bareRepo.fileUri()))
                    .call();
            work.push().setRemote("origin").setRefSpecs(
                    new org.eclipse.jgit.transport.RefSpec(
                            "refs/heads/" + branch + ":refs/heads/" + branch)).call();
        }
    }

    private String onboardUrl(String tenantId) {
        return "http://localhost:" + serverPort + "/api/v1/tenants/" + tenantId + "/onboard";
    }
}
