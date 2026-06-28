package com.pulse.git;

import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.service.CodeGenerationService;
import com.pulse.git.model.GitRepo;
import com.pulse.git.policy.BranchAllowlistPolicy;
import com.pulse.git.provider.GitHubApiClient;
import com.pulse.git.provider.GitHubProviderAdapter;
import com.pulse.git.provider.GitProviderAdapter;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.LocalGitService;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.secret.service.GcpSecretManagerService;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.support.NonBlueprintCompositionFixture;
import com.pulse.support.SeedFixtures;
import com.pulse.support.TempGitRepoExtension;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK_P1_codegen_to_git_commit_e2e — verify that codegen artifacts land in a
 * real on-disk Git repo on the expected branch with the expected files and
 * commit message, that PR creation invokes the provider adapter with the right
 * payload shape, and that the branch-allowlist policy rejects out-of-policy
 * branches.
 *
 * <p>This is the cross-module integration test: {@link CodeGenerationService}
 * triggers commit via {@link com.pulse.git.service.GitCommitService}, which
 * writes to a {@link com.pulse.git.service.LocalGitService} working tree backed
 * by the {@link TempGitRepoExtension}'s temp on-disk repo. PR creation is
 * exercised through {@link GitHubProviderAdapter} with a capturing
 * {@link GitHubApiClient} so no live GitHub calls are made.
 *
 * <p>Lane: integration_pr (class ends in {@code IT}). Profile {@code test}
 * keeps the Spring context light (H2 + Flyway off) — the same profile used by
 * {@code RepresentativeStaticDeployabilityProofIT} which exercises the same
 * codegen-to-git path under @SpringBootTest.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(TempGitRepoExtension.class)
@Tag("integration")
class CodegenToGitCommitE2EIT {

    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired DomainRepository domainRepository;
    @Autowired SystemOfRecordRepository sorRepository;
    @Autowired ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired ConnectorInstanceRepository connectorInstanceRepository;
    @Autowired DatasetRepository datasetRepository;
    @Autowired BlueprintRepository blueprintRepository;
    @Autowired PipelineRepository pipelineRepository;
    @Autowired PipelineVersionRepository pipelineVersionRepository;
    @Autowired SubPipelineInstanceRepository subPipelineInstanceRepository;
    @Autowired PortWiringRepository portWiringRepository;

    @Autowired CodeGenerationService codeGenerationService;
    @Autowired GitRepoRepository gitRepoRepository;
    @Autowired GeneratedArtifactRepository generatedArtifactRepository;
    @Autowired LocalGitService localGitService;
    @Autowired BranchAllowlistPolicy branchAllowlistPolicy;
    @Autowired com.pulse.git.service.GitCommitService gitCommitService;

    private SeedFixtures seedFixtures() {
        return new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                subPipelineInstanceRepository, portWiringRepository);
    }

    private NonBlueprintCompositionFixture compositionFixture(SeedFixtures fx) {
        return new NonBlueprintCompositionFixture(fx, blueprintRepository);
    }

    // -------------------------------------------------------------------
    //  TC_codegen_git_happy_path_commit
    // -------------------------------------------------------------------

    @Test
    @DisplayName("TC_codegen_git_happy_path_commit: codegen artifacts are committed to expected branch with expected files + message")
    void codegenArtifactsCommittedOnExpectedBranchWithExpectedFiles(TempGitRepoExtension.Repos repos) throws Exception {
        SeedFixtures fx = seedFixtures();
        NonBlueprintCompositionFixture.Result seeded = compositionFixture(fx).createWithSeededPipeline();
        String tenantId = seeded.seed().tenantId();
        String pipelineId = seeded.composition().pipelineId();
        String versionId = seeded.composition().versionId();

        // Wire the working clone from the TempGitRepoExtension as the
        // tenant-scoped GitRepo. GitCommitService writes artifacts into
        // localPath and commits via JGit — the bare repo in repos.bareDir()
        // is the "remote" but never pushed to in LOCAL repoType.
        GitRepo tenantRepo = new GitRepo();
        tenantRepo.setTenantId(tenantId);
        tenantRepo.setScope("TENANT");
        tenantRepo.setRepoType("LOCAL");
        tenantRepo.setLocalPath(repos.cloneDir().toString());
        tenantRepo.setRepoUrl("file://" + repos.bareDir());
        tenantRepo.setProvider("LOCAL");
        tenantRepo.setDefaultBranch("main");
        tenantRepo.setCurrentBranch("main");
        tenantRepo.setMetadata(new LinkedHashMap<>(Map.of("scope", "TENANT")));
        gitRepoRepository.save(tenantRepo);

        // Confirm baseline: the working clone has the seed commit on main.
        assertEquals("main", localGitService.getCurrentBranch(repos.cloneDir().toString()));
        String seedHead = localGitService.getHeadSha(repos.cloneDir().toString());
        assertNotNull(seedHead, "TempGitRepoExtension must seed an initial commit");

        GenerationRun run = codeGenerationService.generate(pipelineId, versionId, tenantId, "tester");
        assertEquals("COMPLETED", run.getStatus(),
                "codegen must succeed (so the gitCommitService.commitGeneratedCode hook fires)");

        List<GeneratedArtifact> artifacts = generatedArtifactRepository
                .findByGenerationRunIdOrderByFilePathAsc(run.getId());
        assertFalse(artifacts.isEmpty(), "codegen must emit at least one artifact");

        // Pull a representative subset and assert each file exists at the
        // expected path inside the working clone. This is the cross-module
        // assertion: codegen → git working tree, no fakes in the middle.
        for (GeneratedArtifact a : artifacts) {
            Path target = repos.cloneDir().resolve(a.getFilePath()).normalize();
            assertTrue(target.startsWith(repos.cloneDir()),
                    "artifact path must not escape repo root: " + a.getFilePath());
            assertTrue(Files.exists(target),
                    "artifact must exist on disk at " + a.getFilePath() + " under " + repos.cloneDir());
        }

        // Phase 3 commit-message contract — GitCommitService stamps
        // "pulse: generate run <runId>" so audit can correlate commit ↔ run.
        try (Git git = Git.open(new File(repos.cloneDir().toString()))) {
            RevCommit head = git.log().setMaxCount(1).call().iterator().next();
            assertNotNull(head, "commit must be present after codegen");
            assertEquals("pulse: generate run " + run.getId(), head.getFullMessage().trim(),
                    "commit message follows the locked '<verb> run <id>' shape");
            // The codegen path is user-attributed (commitAsUser, not the
            // legacy system identity). Auth is disabled in this profile so
            // we expect the dev-stub author identity from ActorResolverService.
            assertEquals("Dev Builder", head.getAuthorIdent().getName(),
                    "commitGeneratedCode must use commitAsUser (not commitAll/system)");
            assertEquals("builder@pulse.dev", head.getAuthorIdent().getEmailAddress());
        }

        // Branch correctness — we never left main.
        assertEquals("main", localGitService.getCurrentBranch(repos.cloneDir().toString()),
                "codegen must commit to the configured default branch");
        // HEAD moved off the seed commit.
        String postHead = localGitService.getHeadSha(repos.cloneDir().toString());
        assertNotNull(postHead);
        assertFalse(seedHead.equals(postHead), "HEAD must advance after the codegen commit");

        // Edge case from task spec — path-traversal artifact path is rejected.
        // The dedicated test below (pathTraversalArtifactPathIsRejected)
        // asserts the explicit GitCommitService guard; kept separate so a
        // failure lands on a focused name.
    }

    /**
     * Path-traversal edge case from {@code TC_codegen_git_happy_path_commit}.
     * Asserted via a direct on-disk artifact whose {@code filePath} attempts
     * to escape the repo root. GitCommitService normalizes against
     * {@code repo.localPath} and throws IllegalStateException when the
     * normalized path is outside.
     */
    @Test
    @DisplayName("TC_codegen_git_happy_path_commit (edge): path-traversal artifact path is rejected")
    void pathTraversalArtifactPathIsRejected(TempGitRepoExtension.Repos repos) {
        SeedFixtures fx = seedFixtures();
        var seed = fx.seedFullPipelineContext();
        String tenantId = seed.tenantId();

        GitRepo tenantRepo = new GitRepo();
        tenantRepo.setTenantId(tenantId);
        tenantRepo.setScope("TENANT");
        tenantRepo.setRepoType("LOCAL");
        tenantRepo.setLocalPath(repos.cloneDir().toString());
        tenantRepo.setRepoUrl("file://" + repos.bareDir());
        tenantRepo.setProvider("LOCAL");
        tenantRepo.setDefaultBranch("main");
        tenantRepo.setCurrentBranch("main");
        tenantRepo.setMetadata(new LinkedHashMap<>(Map.of("scope", "TENANT")));
        gitRepoRepository.save(tenantRepo);

        // Seed a malicious artifact directly via the repository to bypass
        // the codegen pipeline (which itself never emits ../ paths). This
        // is the explicit GitCommitService boundary check.
        GeneratedArtifact bad = new GeneratedArtifact();
        bad.setFilePath("../escape.txt");
        bad.setFileType("ESCAPE");
        bad.setContent("should never be written");
        bad.setContentHash("0".repeat(64));
        bad.setGenerationRunId("run-traversal");
        generatedArtifactRepository.save(bad);

        // Invoke the commit service the same way CodeGenerationService does:
        // commitGeneratedCode normalizes each artifact's filePath against
        // the repo root and throws when the result escapes the root.
        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> gitCommitService.commitGeneratedCode(tenantId, "run-traversal"),
                "GitCommitService must reject artifact paths that escape the repo root");
        assertTrue(ex.getMessage().contains("escape") || ex.getMessage().contains("Artifact path escapes"),
                "deny message should mention path escape: " + ex.getMessage());

        // Sanity: nothing was written outside the clone dir.
        assertFalse(Files.exists(repos.cloneDir().getParent().resolve("escape.txt")),
                "the escape file must not exist outside the repo root");
    }

    // -------------------------------------------------------------------
    //  TC_codegen_git_pr_payload_shape
    // -------------------------------------------------------------------

    @Test
    @DisplayName("TC_codegen_git_pr_payload_shape: PR creation invokes GitHub adapter with the expected payload (title/head/base)")
    void prCreationInvokesGitHubAdapterWithExpectedPayload() {
        // Use a capturing GitHubApiClient instead of the production stub
        // (which would 503). We assert on the exact JSON body that the
        // adapter posts so payload-shape drift is caught.
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<Map<String, Object>> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedToken = new AtomicReference<>();

        GitHubApiClient capturing = new GitHubApiClient() {
            @Override
            public Response get(String path, String token) {
                return new Response(200, Map.of(), Map.of(), null);
            }

            @Override
            public Response post(String path, String token, Map<String, Object> body) {
                capturedPath.set(path);
                capturedToken.set(token);
                capturedBody.set(body);
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("number", 7);
                resp.put("title", body.get("title"));
                resp.put("state", "open");
                resp.put("html_url", "https://github.com/acme/loans/pull/7");
                resp.put("head", Map.of("ref", body.get("head")));
                resp.put("base", Map.of("ref", body.get("base")));
                return new Response(201, Map.of(), resp, null);
            }
        };

        GcpSecretManagerService secretManager = mock(GcpSecretManagerService.class);
        when(secretManager.getSecretValueByReference(anyString())).thenReturn("ghp_test_token");

        GitHubProviderAdapter adapter = new GitHubProviderAdapter(capturing, secretManager);

        String repoUrl = "https://github.com/acme/loans";
        String tokenRef = "gcp-sm://projects/pulse-test/secrets/pat/versions/latest";
        String head = "pulse/feature/run-xyz";
        String base = "main";

        GitProviderAdapter.PullRequestInfo info = adapter.createPullRequest(
                new GitProviderAdapter.CreatePullRequest(
                        repoUrl,
                        "Pulse: generated code for run xyz",
                        "Auto-generated PR body",
                        head,
                        base,
                        tokenRef,
                        List.of()));

        // Adapter routed the call to the right repo path.
        assertEquals("/repos/acme/loans/pulls", capturedPath.get(),
                "adapter must POST to /repos/<owner>/<repo>/pulls");
        assertEquals("ghp_test_token", capturedToken.get(),
                "adapter must resolve the token reference via GcpSecretManagerService");

        Map<String, Object> body = capturedBody.get();
        assertNotNull(body, "adapter must post a request body");
        assertEquals("Pulse: generated code for run xyz", body.get("title"),
                "title is non-empty and forwarded verbatim");
        assertEquals("Auto-generated PR body", body.get("body"));
        assertEquals(head, body.get("head"),
                "head branch matches the supplied pipeline branch");
        assertEquals(base, body.get("base"),
                "base branch matches the configured default");

        // Response mapped through to PullRequestInfo.
        assertEquals(7, info.number());
        assertEquals("OPEN", info.state());
        assertEquals(head, info.sourceBranch());
        assertEquals(base, info.targetBranch());

        // Edge case from task spec: when a PR already exists, the
        // adapter should not double-create. createPullRequest is a thin
        // wrapper over POST /pulls; idempotence is enforced at the call
        // site (PullRequestService dedupes by (repoId, versionId)). We
        // exercise the lower-level guarantee here: when the API returns
        // a 422 (GitHub's "PR already exists" status), the adapter
        // throws GitProviderException rather than silently re-posting.
        GitHubApiClient unprocessable = new GitHubApiClient() {
            @Override
            public Response get(String path, String token) {
                return new Response(200, Map.of(), Map.of(), null);
            }

            @Override
            public Response post(String path, String token, Map<String, Object> body) {
                return new Response(422, Map.of(), Map.of("message", "Validation Failed"), null);
            }
        };
        GitHubProviderAdapter dupeAdapter = new GitHubProviderAdapter(unprocessable, secretManager);
        org.junit.jupiter.api.Assertions.assertThrows(
                GitHubProviderAdapter.GitProviderException.class,
                () -> dupeAdapter.createPullRequest(
                        new GitProviderAdapter.CreatePullRequest(
                                repoUrl, "Pulse: generated code for run xyz", "body",
                                head, base, tokenRef, List.of())),
                "adapter must surface duplicate-PR errors (422) rather than silently re-creating");
    }

    // -------------------------------------------------------------------
    //  TC_codegen_git_branch_allowlist_blocks
    // -------------------------------------------------------------------

    @Test
    @DisplayName("TC_codegen_git_branch_allowlist_blocks: disallowed branch is rejected by the allowlist policy with branch + allowlist in the reason")
    void branchAllowlistBlocksDisallowedBranch() {
        // The policy is the single source of truth for branch-environment
        // gating today (DeploymentPreflightService surfaces it as a 403
        // when a package targets a disallowed branch). We pin the policy
        // contract here so a regression — e.g. a future caller skipping
        // the policy in the commit path — has an integration-lane test
        // that fails fast.

        // 1. Disallowed branch on prod returns deny with both the branch
        //    name AND the allowlist in the reason.
        String disallowed = "experiment/random-branch";
        BranchAllowlistPolicy.Outcome prodOutcome = branchAllowlistPolicy.evaluate(disallowed, "prod");
        assertFalse(prodOutcome.allowed(),
                "prod must reject '" + disallowed + "' (only main + release/* are allowed)");
        assertNotNull(prodOutcome.reason(), "deny outcomes must carry an actionable reason");
        assertTrue(prodOutcome.reason().contains(disallowed),
                "deny reason should name the branch: " + prodOutcome.reason());
        assertTrue(prodOutcome.reason().contains("main")
                        && prodOutcome.reason().contains("release/*"),
                "deny reason should enumerate the allowlist (main + release/*): "
                        + prodOutcome.reason());

        // 2. release/* pattern allows release branches on prod.
        BranchAllowlistPolicy.Outcome release = branchAllowlistPolicy.evaluate("release/2026-Q2", "prod");
        assertTrue(release.allowed(),
                "release/* pattern must allow 'release/<x>' on prod");

        // 3. Dev allows any branch (* wildcard) — happy-path commit on
        //    main passes here too.
        assertTrue(branchAllowlistPolicy.evaluate("main", "dev").allowed());
        assertTrue(branchAllowlistPolicy.evaluate("anything/at/all", "dev").allowed());

        // 4. Integration env: feature/* allowed, random branches denied.
        assertTrue(branchAllowlistPolicy.evaluate("feature/PULSE-123", "integration").allowed());
        BranchAllowlistPolicy.Outcome intDeny = branchAllowlistPolicy.evaluate(disallowed, "integration");
        assertFalse(intDeny.allowed());
        assertTrue(intDeny.reason().contains("integration"),
                "deny reason should name the env: " + intDeny.reason());
    }
}
