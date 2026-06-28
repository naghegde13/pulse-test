package com.pulse.git.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JGit wrapper for local filesystem operations. Operates on the working tree
 * at {@code path} (either a PULSE-managed LOCAL repo or the local clone of a
 * REMOTE repo). No network I/O.
 */
@Service
public class LocalGitService {

    /** Author/committer used by {@link #commitAll(String, String)} for system commits. */
    public static final String SYSTEM_AUTHOR_NAME = "PULSE System";
    public static final String SYSTEM_AUTHOR_EMAIL = "pulse@system";

    /** @deprecated use {@link #SYSTEM_AUTHOR_NAME}. Kept for back-compat. */
    @Deprecated
    private static final String AUTHOR_NAME = SYSTEM_AUTHOR_NAME;
    @Deprecated
    private static final String AUTHOR_EMAIL = SYSTEM_AUTHOR_EMAIL;

    /**
     * Creates a new init at {@code path} on the given initial branch, including any missing
     * parent directories. {@code initialBranch} is required so the repo doesn't start on
     * JGit's default {@code master} — otherwise a later {@code checkoutBranch("main")} on the
     * unborn HEAD fails with {@code RefNotFoundException}.
     */
    public void initRepo(String path, String initialBranch) {
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Failed to create directory " + path);
        }
        String branch = initialBranch == null || initialBranch.isBlank() ? "main" : initialBranch;
        try (Git ignored = Git.init().setDirectory(dir).setInitialBranch(branch).call()) {
            // no-op, handle closed by try-with-resources
        } catch (GitAPIException e) {
            throw new IllegalStateException("git init failed at " + path, e);
        }
    }

    /**
     * <b>System / scaffold commit path.</b> Stages all tracked + untracked
     * changes and creates a commit attributed to the {@code PULSE System}
     * author. Reserved for scaffold/maintenance flows
     * (see {@code RepoScaffoldService}, {@code GitCommitService}).
     * No-op when there are no changes.
     *
     * <p>User-initiated commits (chat-tool / UI mutations of pipeline code)
     * MUST go through {@link #commitAsUser(String, String, String, String)}
     * so the JGit author/committer fields carry the actual actor's
     * name/email and downstream audit can distinguish system commits from
     * user commits at the Git layer.
     */
    public void commitAll(String path, String message) {
        commit(path, message, SYSTEM_AUTHOR_NAME, SYSTEM_AUTHOR_EMAIL);
    }

    /**
     * <b>User-attributed commit path (Phase 3).</b> Same staging behavior
     * as {@link #commitAll} but stamps the JGit author + committer fields
     * with the supplied {@code authorName} / {@code authorEmail} so the
     * actual user's identity is recorded in Git history. No-op when there
     * are no changes.
     *
     * <p>{@code authorName} and {@code authorEmail} must be non-blank;
     * callers should resolve them from the server-side actor context
     * (Phase 3 contract — never accept identity from the request body).
     *
     * @throws IllegalArgumentException when name or email is null/blank
     */
    public void commitAsUser(String path, String message, String authorName, String authorEmail) {
        if (authorName == null || authorName.isBlank()) {
            throw new IllegalArgumentException(
                    "authorName is required for user-attributed commits");
        }
        if (authorEmail == null || authorEmail.isBlank()) {
            throw new IllegalArgumentException(
                    "authorEmail is required for user-attributed commits");
        }
        commit(path, message, authorName, authorEmail);
    }

    private void commit(String path, String message, String authorName, String authorEmail) {
        try (Git git = Git.open(new File(path))) {
            git.add().addFilepattern(".").call();
            boolean hasChanges = !git.status().call().isClean();
            if (!hasChanges) {
                return;
            }
            git.commit()
                    .setAll(true)
                    .setAuthor(authorName, authorEmail)
                    .setCommitter(authorName, authorEmail)
                    .setMessage(message)
                    .call();
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException("git commit failed at " + path, e);
        }
    }

    /** Returns all local branch names (short names). */
    public List<String> listBranches(String path) {
        try (Git git = Git.open(new File(path))) {
            List<Ref> refs = git.branchList().call();
            List<String> names = new ArrayList<>(refs.size());
            for (Ref ref : refs) {
                String full = ref.getName();
                names.add(full.startsWith("refs/heads/") ? full.substring("refs/heads/".length()) : full);
            }
            return names;
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException("git branch --list failed at " + path, e);
        }
    }

    /**
     * Checks out an existing branch or creates it at HEAD when it does not yet exist.
     * Short-circuits when HEAD is unborn (no commits yet) and we're already symbolically on
     * {@code branchName} — JGit cannot create a branch from an unborn HEAD, and doing so is
     * also unnecessary since {@link #initRepo(String, String)} sets the initial branch.
     */
    public void checkoutBranch(String path, String branchName) {
        try (Git git = Git.open(new File(path))) {
            var repository = git.getRepository();
            boolean unborn = repository.resolve("HEAD") == null;
            if (unborn && branchName.equals(repository.getBranch())) {
                return;
            }
            boolean exists = git.branchList().call().stream()
                    .map(Ref::getName)
                    .anyMatch(name -> name.equals("refs/heads/" + branchName));
            git.checkout()
                    .setName(branchName)
                    .setCreateBranch(!exists)
                    .call();
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException("git checkout failed at " + path, e);
        }
    }

    /**
     * Returns the current branch name, or {@code null} when the repo has no
     * commits yet and HEAD is unborn.
     */
    public String getCurrentBranch(String path) {
        try (Git git = Git.open(new File(path))) {
            return git.getRepository().getBranch();
        } catch (IOException e) {
            throw new IllegalStateException("git read HEAD failed at " + path, e);
        }
    }

    /** Returns the HEAD commit SHA, or {@code null} when HEAD is unborn. */
    public String getHeadSha(String path) {
        try (Git git = Git.open(new File(path))) {
            var head = git.getRepository().resolve("HEAD");
            return head == null ? null : head.getName();
        } catch (IOException e) {
            throw new IllegalStateException("git resolve HEAD failed at " + path, e);
        }
    }

    /**
     * Returns the tree SHA of HEAD's commit, or {@code null} when HEAD is
     * unborn. The tree SHA is the deterministic content fingerprint of the
     * committed working tree at HEAD; two HEAD commits with identical
     * content (and identical paths/permissions) share the same tree SHA
     * even if their commit SHAs differ.
     *
     * <p>Used by {@code PackageService} for Phase 2 package provenance —
     * stamping a deployable package with both commitSha and treeSha lets
     * downstream verify that the same code-tree is being promoted across
     * environments without depending on commit metadata.
     */
    public String getHeadTreeSha(String path) {
        try (Git git = Git.open(new File(path))) {
            var head = git.getRepository().resolve("HEAD^{tree}");
            return head == null ? null : head.getName();
        } catch (IOException e) {
            throw new IllegalStateException("git resolve HEAD tree failed at " + path, e);
        }
    }

    /** Working-tree status snapshot used for Phase 2 package provenance. */
    public record WorkingTreeStatus(String status, int dirtyFileCount) {
        /** Repo HEAD has commits and the working tree matches HEAD. */
        public static WorkingTreeStatus clean() { return new WorkingTreeStatus("clean", 0); }
        /** Repo HEAD has commits but the working tree has uncommitted changes. */
        public static WorkingTreeStatus dirty(int n) { return new WorkingTreeStatus("dirty", n); }
        /** Repo exists on disk but has no commits yet (HEAD is unborn). */
        public static WorkingTreeStatus unborn() { return new WorkingTreeStatus("unborn", 0); }
    }

    /**
     * Returns a structured working-tree status for the repo at {@code path}.
     * {@code clean} when the working tree exactly matches HEAD,
     * {@code dirty} when JGit reports any uncommitted changes (with a count
     * of touched paths so downstream evidence is actionable), or
     * {@code unborn} when the repo has no commits yet.
     */
    public WorkingTreeStatus getWorkingTreeStatus(String path) {
        try (Git git = Git.open(new File(path))) {
            if (git.getRepository().resolve("HEAD") == null) {
                return WorkingTreeStatus.unborn();
            }
            var status = git.status().call();
            if (status.isClean()) {
                return WorkingTreeStatus.clean();
            }
            int dirtyCount = status.getAdded().size()
                    + status.getChanged().size()
                    + status.getRemoved().size()
                    + status.getModified().size()
                    + status.getMissing().size()
                    + status.getUntracked().size();
            return WorkingTreeStatus.dirty(dirtyCount);
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException("git status failed at " + path, e);
        }
    }
}
