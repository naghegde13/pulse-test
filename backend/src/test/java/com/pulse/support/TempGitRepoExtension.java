package com.pulse.support;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * JUnit 5 extension that creates a per-test bare Git repo and a fresh working clone, both
 * cleaned up after the test. Backed by JGit (matching {@link com.pulse.git.service.LocalGitService},
 * which is also JGit) so tests stay portable across CI runners that may not have a system
 * {@code git} binary on PATH.
 *
 * <p>Usage — annotate the test class with {@code @ExtendWith(TempGitRepoExtension.class)} and
 * declare a parameter of type {@link Repos} on a {@code @BeforeEach} or {@code @Test} method:
 * <pre>
 * {@literal @ExtendWith(TempGitRepoExtension.class)}
 * class GitFlowTest {
 *     {@literal @Test}
 *     void pushPull(TempGitRepoExtension.Repos repos) {
 *         // repos.bareDir() — bare repo, suitable as the "remote"
 *         // repos.cloneDir() — working clone with origin pointing at bareDir()
 *     }
 * }
 * </pre>
 *
 * <p>Concurrency: each test gets its own {@link Files#createTempDirectory} root, so parallel
 * test execution does not collide.
 */
public class TempGitRepoExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(TempGitRepoExtension.class);
    private static final String REPOS_KEY = "repos";

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        Path root = Files.createTempDirectory("pulse-temp-git-");
        Path bareDir = root.resolve("remote.git");
        Path cloneDir = root.resolve("workdir");

        // 1. Create bare repo with HEAD symbolic ref pointing at refs/heads/main (the
        //    setInitialBranch on a bare repo writes the symbolic HEAD; without this, a
        //    fresh-from-clone working tree would default to refs/heads/master).
        Files.createDirectories(bareDir);
        try (Git ignored = Git.init().setBare(true).setDirectory(bareDir.toFile()).setInitialBranch("main").call()) {
            // bare repo created; close immediately
        } catch (GitAPIException e) {
            cleanupQuietly(root);
            throw new IllegalStateException("Failed to init bare repo at " + bareDir, e);
        }

        // 2. Init the working clone directly on 'main', wire origin -> bareDir, seed an initial
        //    commit, push. This avoids JGit's clone-from-empty-bare ref-resolution path which
        //    would otherwise leave the working tree on 'master' even when the bare HEAD is
        //    refs/heads/main.
        Files.createDirectories(cloneDir);
        try (Git clone = Git.init().setDirectory(cloneDir.toFile()).setInitialBranch("main").call()) {
            RemoteConfig remote = clone.remoteAdd()
                    .setName("origin")
                    .setUri(new URIish(bareDir.toUri().toString()))
                    .call();
            // remote is a value object; we don't need to retain it past add()
            assert remote != null;

            Path readme = cloneDir.resolve("README.md");
            Files.writeString(readme, "PULSE TempGitRepoExtension fixture\n");
            clone.add().addFilepattern(".").call();
            clone.commit()
                    .setAuthor("PULSE Test", "test@pulse")
                    .setCommitter("PULSE Test", "test@pulse")
                    .setMessage("init")
                    .call();
            clone.push().setRemote("origin").setRefSpecs(
                    new org.eclipse.jgit.transport.RefSpec("refs/heads/main:refs/heads/main")).call();
        } catch (GitAPIException | IOException | java.net.URISyntaxException e) {
            cleanupQuietly(root);
            throw new IllegalStateException("Failed to init and seed working tree at " + cloneDir, e);
        }

        Repos repos = new Repos(root, bareDir, cloneDir);
        context.getStore(NAMESPACE).put(REPOS_KEY, repos);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Repos repos = context.getStore(NAMESPACE).remove(REPOS_KEY, Repos.class);
        if (repos != null) {
            cleanupQuietly(repos.root());
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == Repos.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return extensionContext.getStore(NAMESPACE).get(REPOS_KEY, Repos.class);
    }

    private static void cleanupQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            // Test cleanup should not mask the test result; emit nothing and move on.
        }
    }

    /**
     * Per-test git artifacts: the temp root, the bare repo, and the working clone. The working
     * clone has {@code origin} set to {@code bareDir} so pull/push round-trips work without
     * any network I/O.
     */
    public record Repos(Path root, Path bareDir, Path cloneDir) {
        public String bareUri() {
            return bareDir.toUri().toString();
        }
    }
}
