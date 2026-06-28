package com.pulse.git;

import com.pulse.git.service.LocalGitService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 3 contract — user-attributed Git commits.
 *
 * <p>Pins:
 * <ul>
 *   <li>{@link LocalGitService#commitAsUser} stamps the JGit author + committer
 *       fields with the supplied actor identity.</li>
 *   <li>{@link LocalGitService#commitAll} (the legacy/system path) is reserved
 *       for scaffold/maintenance and stamps {@code PULSE System} so audit can
 *       distinguish the two flows at the Git layer.</li>
 *   <li>{@link LocalGitService#commitAsUser} rejects null/blank name or email
 *       so callers cannot accidentally hide the actor identity.</li>
 * </ul>
 */
class GitCommitIdentityTest {

    private final LocalGitService service = new LocalGitService();

    @TempDir Path tempDir;
    private Path repoPath;

    @BeforeEach
    void initRepo() {
        repoPath = tempDir.resolve("repo");
        repoPath.toFile().mkdirs();
        service.initRepo(repoPath.toString(), "main");
    }

    @Test
    @DisplayName("commitAsUser stamps the actor name + email on JGit author and committer")
    void userCommitStampsActorNameAndEmail() throws Exception {
        Files.writeString(repoPath.resolve("README.md"), "user-attributed change\n");
        service.commitAsUser(repoPath.toString(), "user change", "Mike Rivera", "mike@home-lending.com");

        RevCommit head = readHead();
        assertEquals("Mike Rivera", head.getAuthorIdent().getName());
        assertEquals("mike@home-lending.com", head.getAuthorIdent().getEmailAddress());
        assertEquals("Mike Rivera", head.getCommitterIdent().getName());
        assertEquals("mike@home-lending.com", head.getCommitterIdent().getEmailAddress());
        assertEquals("user change", head.getFullMessage());
    }

    @Test
    @DisplayName("commitAll (system path) stamps PULSE System for scaffold/maintenance commits")
    void systemCommitStampsPulseSystem() throws Exception {
        Files.writeString(repoPath.resolve("scaffold.txt"), "scaffold artifact\n");
        service.commitAll(repoPath.toString(), "pulse: scaffold tenant repo");

        RevCommit head = readHead();
        assertEquals(LocalGitService.SYSTEM_AUTHOR_NAME, head.getAuthorIdent().getName());
        assertEquals(LocalGitService.SYSTEM_AUTHOR_EMAIL, head.getAuthorIdent().getEmailAddress());
        assertEquals(LocalGitService.SYSTEM_AUTHOR_NAME, head.getCommitterIdent().getName());
        assertEquals(LocalGitService.SYSTEM_AUTHOR_EMAIL, head.getCommitterIdent().getEmailAddress());
    }

    @Test
    @DisplayName("commitAsUser refuses to record a commit without a real actor identity")
    void userCommitRejectsBlankIdentity() throws Exception {
        Files.writeString(repoPath.resolve("a.txt"), "x\n");
        // Blank name is rejected so a caller can't bypass attribution by
        // passing empty strings — that would silently hide the actor.
        assertThrows(IllegalArgumentException.class,
                () -> service.commitAsUser(repoPath.toString(), "msg", null, "x@y"));
        assertThrows(IllegalArgumentException.class,
                () -> service.commitAsUser(repoPath.toString(), "msg", "", "x@y"));
        assertThrows(IllegalArgumentException.class,
                () -> service.commitAsUser(repoPath.toString(), "msg", "X Y", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.commitAsUser(repoPath.toString(), "msg", "X Y", " "));
    }

    @Test
    @DisplayName("User vs system commits are distinguishable by JGit author identity")
    void userAndSystemCommitsAreDistinguishableInGitHistory() throws Exception {
        Files.writeString(repoPath.resolve("scaffold.txt"), "scaffold\n");
        service.commitAll(repoPath.toString(), "pulse: scaffold");
        Files.writeString(repoPath.resolve("user.txt"), "user-edited\n");
        service.commitAsUser(repoPath.toString(), "user edit", "Sarah Chen", "sarah@home-lending.com");

        // Walk the history; the latest commit must be Sarah's, the
        // previous one the system commit.
        try (Git git = Git.open(new File(repoPath.toString()))) {
            var iter = git.log().setMaxCount(2).call().iterator();
            RevCommit user = iter.next();
            RevCommit system = iter.next();
            assertEquals("Sarah Chen", user.getAuthorIdent().getName());
            assertEquals(LocalGitService.SYSTEM_AUTHOR_NAME, system.getAuthorIdent().getName());
        }
    }

    private RevCommit readHead() throws Exception {
        try (Git git = Git.open(new File(repoPath.toString()))) {
            RevCommit head = git.log().setMaxCount(1).call().iterator().next();
            assertNotNull(head, "expected at least one commit");
            return head;
        }
    }
}
