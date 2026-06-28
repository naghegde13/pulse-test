package com.pulse.suitea;

import com.pulse.git.model.GitRepo;
import com.pulse.git.service.GitAuthenticationException;
import com.pulse.git.service.GitHubRepoUrlValidator;
import com.pulse.git.service.RemoteGitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Scenario C — Clone failure cleanup (BUG-42 regression guard).
 *
 * <p>Real-world reproduction from the 2026-05-25 Acme Lending rehearsal:
 * after an empty-repo clone failure (BUG-40), the {@code GitController}
 * catch block called {@code repoRepo.delete(saved)}, but under the live
 * transactional configuration the delete was not flushed before the
 * exception unwound — the row stayed in {@code git_repos}, and every
 * subsequent {@code Initialize} click hit the line-111 idempotency
 * check and 409-CONFLICTed. The operator had to manually
 * {@code DELETE FROM git_repos WHERE id = '...'} +
 * {@code rm -rf /tmp/pulse/repos/{slug}} to recover.
 *
 * <p>PKT-FINAL-4 changes the cleanup to:
 * <ol>
 *   <li>{@code repoRepo.deleteAndFlush(saved)} so the DB row is gone
 *       before the exception is re-thrown;</li>
 *   <li>Recursive {@code Files.delete} on the partial-clone directory
 *       (any half-written working tree under
 *       {@code clone-base/{tenant-slug}}).</li>
 * </ol>
 *
 * <h3>Assertion shape</h3>
 * <ol>
 *   <li>{@code POST /onboard} returns 5xx (the underlying clone error
 *       surfaces as an upstream Git failure).</li>
 *   <li>No row exists in {@code git_repos} for the tenant — the catch
 *       block flushed the delete.</li>
 *   <li>No directory exists under
 *       {@code clone-base/{tenant-slug}} — the catch block recursively
 *       removed any partial-clone state.</li>
 *   <li>A retry ({@code POST /onboard} again) does NOT hit the line-111
 *       409 CONFLICT — the idempotency check sees a clean slate.</li>
 * </ol>
 *
 * <h3>Why this WAS {@code @Disabled} (historical context)</h3>
 * The {@code repoRepo.delete(saved)} call in {@code GitController.onboard}
 * was in place when this test was authored, but the flush + filesystem
 * cleanup behaviour PKT-FINAL-4 promised was not. Under the test profile
 * (H2-like in-memory Postgres transactional semantics) the delete usually
 * landed, which meant this scenario sometimes passed its DB assertions by
 * accident. The filesystem-cleanup assertion would still fail until
 * PKT-FINAL-4 shipped, so the entire scenario was held back for a clean
 * green signal.
 *
 * <h3>Re-enabled by the SU-8 META-packet (2026-05-26)</h3>
 * PKT-FINAL-4 (BUG-42) shipped — see {@code GitController.java:174-178}
 * for the {@code deleteAndFlush} that writes the row removal to the DB
 * before the next request lands, and {@code :503} for the explicit rollback
 * helper. The {@code @Disabled} annotation was removed by the SU-8 BUG-67
 * commit; this test now runs as part of the standard Suite A lane.
 */
@DisplayName("Suite A / Scenario C — clone failure cleanup (BUG-42)")
class CloneFailureCleanupSuiteAIT extends SuiteABaseIT {

    private static final String TENANT_ID = "tenant-suite-a-clone-fail";
    private static final String TENANT_SLUG = "suite-a-clone-fail";

    @Autowired private TestRestTemplate restTemplate;

    @MockitoBean private GitHubRepoUrlValidator gitHubRepoUrlValidator;
    @MockitoBean private RemoteGitService remoteGitService;

    @BeforeEach
    void setup() {
        cleanupTestTenant(TENANT_ID);

        createTenant(TENANT_ID, "Suite A Clone-Fail Tenant", TENANT_SLUG);
        createDomains(TENANT_ID, "policy");
        seedGitIdentity(TENANT_ID);

        when(gitHubRepoUrlValidator.validate(anyString()))
                .thenReturn(new GitHubRepoUrlValidator.Result(true, null));
        doNothing().when(remoteGitService).verifyRepoAccess(anyString(), any());

        // Simulate a clone failure that ALSO leaves a partial-clone
        // directory behind on disk — the exact failure mode BUG-42 was
        // about. Production JGit would leave behind an unborn .git
        // directory; we replicate that here so the post-FINAL-4 cleanup
        // assertion has something to verify against.
        doAnswer(invocation -> {
            GitRepo repo = invocation.getArgument(0);
            Path local = Path.of(repo.getLocalPath());
            Files.createDirectories(local.resolve(".git"));
            Files.writeString(local.resolve(".git").resolve("HEAD"),
                    "ref: refs/heads/main\n");
            throw new GitAuthenticationException(
                    "Suite A simulated clone failure (BUG-42 fixture)");
        }).when(remoteGitService).cloneRepo(any(), any());
    }

    @Test
    @DisplayName("clone failure deletes the git_repos row AND removes the partial-clone directory")
    void cloneFailureRollsBackBothDbAndFilesystem() {
        Map<String, Object> body = jsonBody(
                "repoType", "REMOTE",
                "repoUrl", "https://github.com/acme/suite-a-clone-fail.git",
                "provider", "GITHUB",
                "defaultBranch", "main");

        ResponseEntity<String> first = restTemplate.postForEntity(
                onboardUrl(TENANT_ID), body, String.class);
        assertThat(first.getStatusCode().is2xxSuccessful())
                .as("clone failure should NOT return 2xx")
                .isFalse();

        // DB: git_repos row was flushed away.
        List<GitRepo> rows = gitRepoRepository.findByTenantIdAndScopeOrderByCreatedAtDesc(
                TENANT_ID, "TENANT");
        assertThat(rows)
                .as("BUG-42: catch-block deleteAndFlush should have removed the orphan row")
                .isEmpty();

        // Filesystem: partial-clone directory removed.
        Path tenantWorkdir = tenantWorkdir(TENANT_SLUG);
        assertThat(tenantWorkdir)
                .as("BUG-42: catch-block should recursively delete the partial-clone directory")
                .doesNotExist();

        // Retry without manual cleanup: the line-111 idempotency check should
        // see a clean slate.
        ResponseEntity<String> second = restTemplate.postForEntity(
                onboardUrl(TENANT_ID), body, String.class);
        assertThat(second.getStatusCode())
                .as("retry after clean rollback must NOT 409 — the orphan row was cleaned up")
                .isNotEqualTo(HttpStatus.CONFLICT);
    }

    private String onboardUrl(String tenantId) {
        return "http://localhost:" + serverPort + "/api/v1/tenants/" + tenantId + "/onboard";
    }
}
