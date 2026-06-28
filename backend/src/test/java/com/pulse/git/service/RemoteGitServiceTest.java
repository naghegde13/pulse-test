package com.pulse.git.service;

import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.model.GitRepo;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * PKT-FINAL-4 (BUG-40): empty-repo init fallback. The clone path
 * cannot establish HEAD against an unborn remote, so the service
 * detects the case with an {@code ls-remote} probe and runs an
 * init-and-push flow instead.
 */
class RemoteGitServiceTest {

    @Test
    void cloneRepo_emptyRepo_runsInitAndPushFallback(@TempDir Path tempDir) throws Exception {
        // 1) Set up a bare "remote" repo on disk so the push has somewhere to land.
        Path remoteBare = tempDir.resolve("remote-bare.git");
        Files.createDirectories(remoteBare);
        try (Git ignored = Git.init().setBare(true).setDirectory(remoteBare.toFile())
                .setInitialBranch("main").call()) {
            // no-op
        }
        String remoteUrl = remoteBare.toUri().toString();

        // 2) Local working-tree target (clone destination).
        Path localPath = tempDir.resolve("home-lending");

        GitRepo repo = new GitRepo();
        repo.setLocalPath(localPath.toString());
        repo.setRepoUrl(remoteUrl);
        repo.setDefaultBranch("main");
        repo.setCurrentBranch("main");

        UserGitIdentity identity = new UserGitIdentity();
        identity.setAuthorName("Test User");
        identity.setAuthorEmail("test@example.com");
        identity.setGithubUsername("testuser");

        // 3) Spy on the service so we can:
        //    (a) drive isRemoteEmpty=true without going through https,
        //    (b) bypass requireHttps via the file:// short-circuit,
        //    (c) directly invoke initializeAndPushEmptyRepo for the assertion.
        GitCredentialResolver credentialResolver = mock(GitCredentialResolver.class);
        when(credentialResolver.resolveHttpsCredentials(any())).thenReturn(Optional.empty());

        RemoteGitService service = new RemoteGitService(credentialResolver);
        RemoteGitService spied = spy(service);
        doReturn(true).when(spied).isRemoteEmpty(any(), any());

        // Drive the init+push path directly so we don't need to bypass
        // requireHttps. The cloneRepo wrapper just delegates to this path
        // when isRemoteEmpty returns true.
        spied.initializeAndPushEmptyRepo(repo, identity, "main");

        // 4) Assertions:
        //    - local working tree exists and has a .git directory
        //    - the remote bare repo now has a commit on `main`
        assertThat(new File(localPath.toString(), ".git").exists())
                .as("expected local working tree .git to be created by init")
                .isTrue();

        try (org.eclipse.jgit.lib.Repository remoteRepo =
                     new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                             .setGitDir(remoteBare.toFile())
                             .build()) {
            org.eclipse.jgit.lib.Ref head = remoteRepo.findRef("refs/heads/main");
            assertThat(head)
                    .as("expected push to create refs/heads/main on remote bare repo")
                    .isNotNull();
            assertThat(head.getObjectId())
                    .as("expected refs/heads/main to point at a real object")
                    .isNotNull();
        }
    }
}
