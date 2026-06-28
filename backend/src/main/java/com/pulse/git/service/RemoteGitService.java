package com.pulse.git.service;

import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.model.GitRepo;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * JGit wrapper for remote operations (clone / push / pull) on a {@link GitRepo}.
 *
 * Credential resolution is delegated to {@link GitCredentialResolver}. Auth
 * method is inferred from the URL prefix per ARCH-002:
 * <ul>
 *   <li>{@code file://} — no auth, skip resolution.</li>
 *   <li>{@code https://} — current user's GitHub PAT via {@code UsernamePasswordCredentialsProvider}.</li>
 *   <li>{@code git@} or {@code ssh://} — unsupported in this phase.</li>
 * </ul>
 */
@Service
public class RemoteGitService {

    private static final Logger log = LoggerFactory.getLogger(RemoteGitService.class);

    private final GitCredentialResolver credentialResolver;

    public RemoteGitService(GitCredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    /**
     * Clones {@code repo.repoUrl} into {@code repo.localPath} on
     * {@code repo.currentBranch} (falling back to {@code repo.defaultBranch}).
     */
    public void cloneRepo(GitRepo repo, UserGitIdentity identity) {
        String url = repo.getRepoUrl();
        if (url == null || url.startsWith("file://")) {
            log.debug("cloneRepo: file:// or null URL, skipping remote clone");
            return;
        }
        requireHttps(url);
        String branch = repo.getCurrentBranch() != null ? repo.getCurrentBranch() : repo.getDefaultBranch();
        File target = new File(repo.getLocalPath());
        if (!target.exists() && !target.mkdirs()) {
            // PKT-FINAL-4 (BUG-36): use the typed onboarding exception so
            // GlobalExceptionHandler can preserve the actionable message
            // instead of collapsing to "An unexpected error occurred".
            throw new GitOnboardingException(
                    "Failed to create clone target " + repo.getLocalPath()
                            + ". Set PULSE_GIT_CLONE_BASE to a writable path and restart the backend.");
        }

        // PKT-FINAL-4 (BUG-40): pre-flight check for empty/unborn remote.
        // JGit's CloneCommand cannot determine HEAD against a remote with
        // no refs, so we fall back to an init-and-push flow that creates
        // the initial commit on the requested branch.
        if (isRemoteEmpty(url, identity)) {
            log.info("Remote repo {} is empty; falling back to init-and-push flow.", url);
            initializeAndPushEmptyRepo(repo, identity, branch);
            return;
        }

        CloneCommand clone = Git.cloneRepository()
                .setURI(url)
                .setDirectory(target)
                .setBranch(branch);

        applyHttpsCredentials(identity, clone);
        runTransport(url, () -> {
            Git git = clone.call();
            git.close();
        });
    }

    /**
     * PKT-FINAL-4 (BUG-40): probes the remote with {@code ls-remote --heads}
     * to detect an empty/unborn repository before {@code CloneCommand} is
     * invoked. Returns {@code true} when the remote responds with zero
     * head refs. Network/auth failures during the probe are NOT treated
     * as "empty" — the caller continues to the normal clone path so the
     * existing JGit error surfaces through {@link GitAuthenticationException}.
     */
    boolean isRemoteEmpty(String url, UserGitIdentity identity) {
        LsRemoteCommand command = Git.lsRemoteRepository().setRemote(url).setHeads(true);
        applyHttpsCredentials(identity, command);
        try {
            var refs = command.call();
            return refs == null || refs.isEmpty();
        } catch (GitAPIException e) {
            log.debug("lsRemote check failed for {}: {}; proceeding to clone attempt",
                    url, e.getMessage());
            return false;
        }
    }

    /**
     * PKT-FINAL-4 (BUG-40): init-and-push fallback used when the remote
     * is empty (unborn HEAD). Creates the local clone on the requested
     * branch with the operator's verified identity, then pushes the
     * initial commit upstream. Any partial-clone state left behind by
     * an earlier failed attempt is recursively cleared first.
     */
    void initializeAndPushEmptyRepo(GitRepo repo, UserGitIdentity identity, String requestedBranch) {
        String finalBranch = (requestedBranch != null && !requestedBranch.isBlank())
                ? requestedBranch
                : (repo.getDefaultBranch() != null ? repo.getDefaultBranch() : "main");
        File target = new File(repo.getLocalPath());

        if (new File(target, ".git").exists()) {
            try {
                org.eclipse.jgit.util.FileUtils.delete(target,
                        org.eclipse.jgit.util.FileUtils.RECURSIVE | org.eclipse.jgit.util.FileUtils.IGNORE_ERRORS);
            } catch (IOException cleanupErr) {
                log.warn("Failed to clean partial clone dir {}: {}", target, cleanupErr.getMessage());
            }
            if (!target.exists() && !target.mkdirs()) {
                throw new GitOnboardingException(
                        "Failed to re-create clone target after cleanup: " + target);
            }
        }

        // Init with the correct initial branch so HEAD never points at
        // JGit's default `master`.
        try (Git ignored = Git.init().setDirectory(target).setInitialBranch(finalBranch).call()) {
            // no-op; resource closes itself
        } catch (GitAPIException e) {
            throw new GitOnboardingException(
                    "Failed to init local repo at " + target
                            + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        }

        try (Git git = Git.open(target)) {
            git.remoteAdd()
                    .setName("origin")
                    .setUri(new org.eclipse.jgit.transport.URIish(repo.getRepoUrl()))
                    .call();

            // Make an empty initial commit so push has something to send.
            String authorName = firstNonBlank(identity == null ? null : identity.getAuthorName(),
                    identity == null ? null : identity.getGithubUsername(),
                    "pulse");
            String authorEmail = firstNonBlank(identity == null ? null : identity.getAuthorEmail(),
                    identity != null && identity.getGithubUsername() != null
                            ? identity.getGithubUsername() + "@users.noreply.github.com"
                            : null,
                    "pulse@users.noreply.github.com");

            git.commit()
                    .setAllowEmpty(true)
                    .setMessage("Initial commit from PULSE tenant scaffold")
                    .setAuthor(authorName, authorEmail)
                    .setCommitter(authorName, authorEmail)
                    .call();

            org.eclipse.jgit.api.PushCommand push = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(finalBranch + ":" + finalBranch));
            applyHttpsCredentials(identity, push);
            runTransport(repo.getRepoUrl(), () -> { push.call(); });
            log.info("Initial commit pushed to origin/{} for repo {}", finalBranch, repo.getRepoUrl());
        } catch (IOException e) {
            throw new GitOnboardingException(
                    "Failed to open initialized repo at " + target
                            + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        } catch (GitAPIException e) {
            throw new GitOnboardingException(
                    "Failed to push initial commit for " + repo.getRepoUrl()
                            + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        } catch (java.net.URISyntaxException e) {
            throw new GitOnboardingException(
                    "Invalid remote URL " + repo.getRepoUrl()
                            + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }

    /** Pushes the current branch of the local clone back to {@code origin}. */
    public void pushToRemote(GitRepo repo, UserGitIdentity identity) {
        String url = repo.getRepoUrl();
        if (url == null || url.startsWith("file://")) {
            log.debug("pushToRemote: local repo, push is a no-op");
            return;
        }
        requireHttps(url);
        try (Git git = Git.open(new File(repo.getLocalPath()))) {
            PushCommand push = git.push();
            applyHttpsCredentials(identity, push);
            runTransport(url, () -> { push.call(); });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open local clone at " + repo.getLocalPath(), e);
        }
    }

    /** Pulls the current branch of the local clone from {@code origin}. */
    public void pullFromRemote(GitRepo repo, UserGitIdentity identity) {
        String url = repo.getRepoUrl();
        if (url == null || url.startsWith("file://")) {
            log.debug("pullFromRemote: local repo, pull is a no-op");
            return;
        }
        requireHttps(url);
        try (Git git = Git.open(new File(repo.getLocalPath()))) {
            PullCommand pull = git.pull();
            applyHttpsCredentials(identity, pull);
            runTransport(url, () -> { pull.call(); });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open local clone at " + repo.getLocalPath(), e);
        }
    }

    public void verifyRepoAccess(String repoUrl, UserGitIdentity identity) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }
        if (repoUrl.startsWith("file://")) {
            return;
        }
        requireHttps(repoUrl);
        LsRemoteCommand command = Git.lsRemoteRepository().setRemote(repoUrl).setHeads(true);
        applyHttpsCredentials(identity, command);
        try {
            command.call();
        } catch (TransportException e) {
            throw new GitRepoAccessDeniedException("GitHub repository access denied for " + repoUrl, e);
        } catch (GitAPIException e) {
            throw new GitAuthenticationException("Git repository verification failed for " + repoUrl, e);
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private static boolean isHttps(String url) {
        return url.startsWith("https://") || url.startsWith("http://");
    }

    private static boolean isSsh(String url) {
        return url.startsWith("git@") || url.startsWith("ssh://");
    }

    private static void requireHttps(String url) {
        if (isSsh(url)) {
            throw new UnsupportedGitAuthModeException(
                    "SSH git URLs are not supported in this phase; use a GitHub HTTPS URL and user PAT identity.");
        }
        if (!isHttps(url)) {
            throw new UnsupportedGitAuthModeException(
                    "Only GitHub HTTPS URLs are supported for remote tenant repos.");
        }
    }

    private void applyHttpsCredentials(UserGitIdentity identity, Object command) {
        Optional<UsernamePasswordCredentialsProvider> provider = credentialResolver.resolveHttpsCredentials(identity);
        provider.ifPresent(p -> {
            if (command instanceof CloneCommand c) c.setCredentialsProvider(p);
            else if (command instanceof PushCommand c) c.setCredentialsProvider(p);
            else if (command instanceof PullCommand c) c.setCredentialsProvider(p);
            else if (command instanceof LsRemoteCommand c) c.setCredentialsProvider(p);
        });
    }

    private void runTransport(String url, TransportAction action) {
        try {
            action.run();
        } catch (TransportException e) {
            // PKT-FINAL-4 (BUG-41): append the underlying JGit message so the
            // operator-visible envelope distinguishes auth-rejection from
            // empty-repo from network failure even if the global handler
            // never walks the cause chain.
            throw new GitAuthenticationException(
                    "Git server rejected credentials for " + url
                            + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        } catch (GitAPIException e) {
            throw new GitAuthenticationException(
                    "Git operation failed for " + url
                            + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        }
    }
    @FunctionalInterface
    private interface TransportAction {
        void run() throws GitAPIException;
    }
}
