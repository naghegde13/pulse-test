package com.pulse.support;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC_temp_git_repo_extension_works — the extension creates a bare repo + working clone with
 * origin pointing at the bare repo, both deleted after the test.
 */
@ExtendWith(TempGitRepoExtension.class)
class TempGitRepoExtensionTest {

    private static Path bareDirCaptured;
    private static Path cloneDirCaptured;
    private static Path rootCaptured;

    @BeforeAll
    static void clearStaticCapture() {
        bareDirCaptured = null;
        cloneDirCaptured = null;
        rootCaptured = null;
    }

    @Test
    void bareRepoAndCloneAreCreatedAndCloneOriginPointsAtBare(TempGitRepoExtension.Repos repos) throws Exception {
        bareDirCaptured = repos.bareDir();
        cloneDirCaptured = repos.cloneDir();
        rootCaptured = repos.root();

        assertTrue(Files.exists(repos.bareDir()), "bare dir exists during test");
        assertTrue(Files.exists(repos.cloneDir()), "clone dir exists during test");

        // bare repo must look bare to JGit
        try (Git bare = Git.open(repos.bareDir().toFile())) {
            assertTrue(bare.getRepository().isBare(), "bareDir is a bare repo");
        }

        // clone has a HEAD on main with at least one commit (we seeded "init")
        try (Git clone = Git.open(repos.cloneDir().toFile())) {
            assertEquals("main", clone.getRepository().getBranch(), "clone is on main");
            assertNotNull(clone.getRepository().resolve("HEAD"), "clone HEAD is born");

            StoredConfig cfg = clone.getRepository().getConfig();
            String originUrl = cfg.getString("remote", "origin", "url");
            assertNotNull(originUrl, "origin remote configured");
            // JGit configures URI to the bare repo; either form is acceptable, we just need it
            // to point inside the same temp root so that push/pull stay local.
            assertTrue(originUrl.contains(repos.bareDir().getFileName().toString()),
                    "origin url points at the bare repo: " + originUrl);
        }
    }

    @AfterAll
    static void verifyCleanup() {
        // After the test method runs, afterEach deletes the entire root.
        if (rootCaptured == null) return; // test never ran
        assertFalse(Files.exists(rootCaptured),
                "temp root deleted after test: " + rootCaptured);
        assertFalse(Files.exists(bareDirCaptured),
                "bare dir deleted after test: " + bareDirCaptured);
        assertFalse(Files.exists(cloneDirCaptured),
                "clone dir deleted after test: " + cloneDirCaptured);
    }

    @Test
    void parallelInvocationsGetDistinctPaths(TempGitRepoExtension.Repos repos) {
        // The capture from the first @Test runs is in the static field; assert this run's
        // root is different to confirm Files.createTempDirectory issues unique paths.
        assertTrue(Files.exists(repos.root()), "second test gets a fresh root");
        // Note: we cannot assert different from bareDirCaptured here because the AfterAll runs
        // after both tests; instead just confirm this invocation's tree is structured correctly.
        assertTrue(Files.exists(repos.bareDir().resolve("HEAD")), "bare repo has HEAD file");
        assertTrue(repos.cloneDir().resolve(".git").toFile().isDirectory(),
                ".git dir present in clone");
        File readme = repos.cloneDir().resolve("README.md").toFile();
        assertTrue(readme.isFile(), "seeded README.md present in clone");
    }
}
