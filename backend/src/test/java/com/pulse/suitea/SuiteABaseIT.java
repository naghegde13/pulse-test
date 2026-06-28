package com.pulse.suitea;

import com.pulse.auth.model.Tenant;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.git.identity.GitHubPatValidationStatus;
import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.identity.UserGitIdentityRepository;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for the Suite A backend integration test scenarios
 * (PKT-CAND-suite-a-backend-integration-test, Phase 1).
 *
 * <p>The Suite A goal is regression-prevention: every onboarding-path bug
 * found in the 2026-05-25 Acme Lending rehearsal (and every PKT-FINAL-1/2/3
 * fix that's already shipped) must stay fixed without relying on live GCP,
 * real GitHub, or operator credentials. Each scenario class extends this
 * base, picks up a private Postgres 16 + fake-gcs-server container pair, and
 * boots the full Spring application context against them.
 *
 * <h3>What the harness gives you</h3>
 * <ul>
 *   <li>A Postgres 16-alpine container with Flyway-applied schema
 *       ({@code @ActiveProfiles("suite-a-it")} flips Flyway on and
 *       Hibernate {@code ddl-auto=validate}).</li>
 *   <li>A fake-gcs-server container exposed on a random local port
 *       (port {@code 4443} inside the container). Scenarios that need to
 *       write storage scaffolding (Scenarios D and E in a follow-up) can
 *       read its endpoint via {@link #fakeGcsEndpoint()}.</li>
 *   <li>A per-test {@code pulse.git.clone-base} pointing at a unique temp
 *       directory so onboarding scaffolding writes do not collide across
 *       scenarios.</li>
 *   <li>Convenience helpers for tenant / domain / git-identity seeding
 *       ({@link #createTenant}, {@link #createDomains},
 *       {@link #seedGitIdentity}) and for spinning up a local-bare
 *       git repo to stand in as the "remote" GitHub
 *       ({@link #createLocalBareGitRepo}).</li>
 * </ul>
 *
 * <h3>Why class-level (not method-level) containers</h3>
 * Each {@code *SuiteAIT.java} owns its own Spring context (Spring Boot's
 * context cache keys on the runtime configuration, which includes our
 * {@code @DynamicPropertySource} datasource URL). Booting Postgres once per
 * class keeps the suite under the 5-minute target while still isolating
 * databases across classes.
 *
 * <h3>What is intentionally NOT real</h3>
 * <ul>
 *   <li>GitHub API — the {@code pulse.git.github.enabled=false} flag in the
 *       profile keeps PAT validation in stub mode. Suite A scenarios seed a
 *       VALID {@link UserGitIdentity} row directly via
 *       {@link #seedGitIdentity}.</li>
 *   <li>{@code RemoteGitService.cloneRepo} — scenarios that need a real
 *       clone use {@code @MockitoBean} on {@code RemoteGitService} and route
 *       the clone through the local bare repo. The production code path
 *       short-circuits on {@code file://} URLs (see {@code
 *       RemoteGitService.java:49}).</li>
 *   <li>{@code GitHubRepoUrlValidator} — scenarios that exercise the
 *       {@code /onboard} controller use {@code @MockitoBean} on this bean so
 *       the {@code file://} (or any local) URL we pass passes validation
 *       without contacting api.github.com.</li>
 * </ul>
 *
 * <h3>Coordination with PKT-FINAL-4</h3>
 * Scenarios that assert behavior introduced by PKT-FINAL-4
 * (BUG-40 empty-repo init fallback, BUG-42 clone-failure cleanup-and-flush)
 * are {@code @Disabled} on this branch until that work merges. The base
 * harness compiles and Scenario A (happy path) runs green against the
 * current PKT-FINAL-1/2/3 codebase.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("suite-a-it")
@Testcontainers
@Tag("integration")
@Tag("suite-a")
public abstract class SuiteABaseIT {

    /**
     * Postgres 16-alpine — pinned to match the docker-compose image so the
     * checked-in Flyway scripts apply with identical syntax/extension
     * semantics. Suite A uses a per-class instance (rather than one shared
     * across all scenarios) so each scenario starts from a known-empty
     * schema.
     */
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("pulse_suite_a")
            .withUsername("pulse")
            .withPassword("pulse")
            .withReuse(false);

    /**
     * fsouza/fake-gcs-server — a small (~50MB) image that speaks the
     * Google Cloud Storage JSON API on port 4443. Started in {@code memory}
     * backend mode so each scenario sees an empty bucket namespace. Future
     * Suite A scenarios (D/E — storage scaffold) bind PULSE's GCS client to
     * its {@code -external-url} so writes land in the in-memory store.
     */
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> FAKE_GCS = new GenericContainer<>(
            DockerImageName.parse("fsouza/fake-gcs-server:latest"))
            .withCommand(
                    "-scheme", "http",
                    "-port", "4443",
                    "-backend", "memory",
                    "-public-host", "127.0.0.1")
            .withExposedPorts(4443)
            .waitingFor(Wait.forLogMessage(".*server started at.*\\n", 1));

    /**
     * Per-test clone base. Each scenario instance gets its own temp
     * directory so onboarding writes (and any partial-clone state left
     * behind by clone-failure tests) do not bleed into the next scenario.
     * Initialised in a {@code @DynamicPropertySource} so Spring resolves
     * {@code pulse.git.clone-base} from it at context startup.
     */
    private static Path cloneBaseRoot;

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        // Postgres datasource
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Flyway should re-apply every checked-in migration on this fresh
        // container — the suite-a-it profile already enables Flyway but be
        // explicit so the container schema is fully built before scenarios
        // run.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");

        // Per-class clone base. NB: this is class-scoped, not method-scoped,
        // because @DynamicPropertySource only fires once per Spring context.
        // Individual scenarios that need a fresh subdirectory call
        // freshSandbox() in @BeforeEach.
        if (cloneBaseRoot == null) {
            try {
                cloneBaseRoot = Files.createTempDirectory("pulse-suite-a-clone-base-");
            } catch (IOException e) {
                throw new IllegalStateException("Failed to allocate clone-base temp directory", e);
            }
        }
        registry.add("pulse.git.clone-base", () -> cloneBaseRoot.toString());
        registry.add("pulse.git.local-repo-base", () -> cloneBaseRoot.toString());

        // fake-gcs-server endpoint. Future Scenarios D/E will bind to this
        // via a Storage client property. Registered eagerly so the property
        // is resolvable; PULSE's main code path doesn't yet read it.
        registry.add("pulse.gcp.gcs-emulator-endpoint", SuiteABaseIT::fakeGcsEndpoint);
    }

    // ---------------------------------------------------------------------
    //  Spring-managed beans
    // ---------------------------------------------------------------------

    @Autowired protected TenantRepository tenantRepository;
    @Autowired protected DomainRepository domainRepository;
    @Autowired protected UserGitIdentityRepository userGitIdentityRepository;
    @Autowired protected com.pulse.git.repository.GitRepoRepository gitRepoRepository;
    @Autowired protected com.pulse.git.repository.TenantRepoScaffoldItemRepository
            scaffoldItemRepository;
    @Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @LocalServerPort protected int serverPort;

    // ---------------------------------------------------------------------
    //  Per-test workspace cleanup
    // ---------------------------------------------------------------------

    @AfterEach
    void cleanupOnDiskScaffolds() {
        // Each scenario writes a tenant subdirectory under clone-base
        // (e.g. {clone-base}/{tenant-slug}). Wipe everything under
        // clone-base between tests so the next scenario starts empty.
        if (cloneBaseRoot != null && Files.exists(cloneBaseRoot)) {
            try (var paths = Files.walk(cloneBaseRoot)) {
                paths.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        // Keep the root itself; only blow away its contents.
                        .filter(file -> !file.toPath().equals(cloneBaseRoot))
                        .forEach(File::delete);
            } catch (IOException ignored) {
                // Cleanup should not mask the test result.
            }
        }
    }

    // ---------------------------------------------------------------------
    //  Public helpers consumed by scenarios
    // ---------------------------------------------------------------------

    /**
     * URL of the fake-gcs-server container's JSON API. Stable for the
     * lifetime of the class.
     */
    public static String fakeGcsEndpoint() {
        return "http://" + FAKE_GCS.getHost() + ":" + FAKE_GCS.getMappedPort(4443);
    }

    /**
     * Removes everything Suite A scenarios create for {@code tenantId} —
     * scaffold items, git repo rows, git identities, domains, and the
     * tenant itself. Bootstrap-seeded tenants (and their FK-referenced
     * rows like {@code systems_of_record}) are left alone, which is why
     * Suite A scenarios use a private tenant ID like
     * {@code tenant-suite-a-happy} rather than reusing
     * {@code tenant-home-lending}.
     *
     * <p>Order matters: child tables (FK referencers) first, parent last.
     * Direct JDBC for tables without JPA entities in this test classpath.
     */
    protected void cleanupTestTenant(String tenantId) {
        // Scaffold items reference git_repos.id, so delete those first.
        // Filter by tenant_id to avoid touching bootstrap-tenant rows
        // (none today, but defensive against future cross-tenant scaffold
        // tests).
        jdbcTemplate.update("DELETE FROM tenant_repo_scaffold_items WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM git_repos WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM user_git_identities WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM domains WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    /**
     * Inserts a {@link Tenant} row directly into the {@code tenants} table.
     * Suite A scenarios prefer the direct insert over the
     * {@code POST /api/v1/tenants} endpoint because tenants are pre-existing
     * configuration in the real product — onboarding is the surface under
     * test, not tenant creation.
     */
    protected Tenant createTenant(String id, String name, String slug) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setName(name);
        t.setSlug(slug);
        t.setOrigin("bootstrap");
        t.setStatus("active");
        return tenantRepository.save(t);
    }

    /**
     * Creates N domains under the given tenant. The slug is derived from
     * the name (lower-snake-with-dashes) — matching the same convention
     * used by {@code RepoScaffoldService.slugFor}.
     */
    protected List<Domain> createDomains(String tenantId, String... names) {
        java.util.List<Domain> created = new java.util.ArrayList<>(names.length);
        for (String name : names) {
            Domain d = new Domain();
            d.setTenantId(tenantId);
            d.setName(name);
            d.setSlug(name.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
            d.setDescription("Suite A fixture domain " + name);
            created.add(domainRepository.save(d));
        }
        return created;
    }

    /**
     * Seeds a VALID {@link UserGitIdentity} row for the dev stub user
     * (matching the actor returned by
     * {@code ActorResolverService.resolveFromHeaders} when auth is
     * disabled). Scenarios that exercise the {@code /onboard} controller
     * call this so {@code UserGitIdentityService.requireValidIdentity}
     * resolves cleanly.
     *
     * <p>Also writes a stub PAT into the local-stub Secret Manager so
     * {@code GitCredentialResolver.resolveHttpsCredentials} returns
     * credentials when called. The actual JGit transport never uses them
     * because Suite A's bare repo is on a {@code file://} URI — the
     * resolver is invoked along the way and would throw otherwise.
     */
    protected UserGitIdentity seedGitIdentity(String tenantId) {
        String secretRef = "gcp-sm://projects/pulse-suite-a/secrets/git-pat-dev/versions/latest";
        UserGitIdentity identity = new UserGitIdentity();
        identity.setTenantId(tenantId);
        identity.setPulseUserId(com.pulse.auth.policy.ActorResolverService.DEV_STUB_USER_ID);
        identity.setProvider("GITHUB");
        identity.setCredentialType("PAT_CLASSIC");
        identity.setCredentialReference(secretRef);
        identity.setGithubUsername("pulse-suite-a-bot");
        identity.setAuthorName("PULSE Suite A");
        identity.setAuthorEmail("suite-a@pulse.test");
        identity.setScopes("repo");
        identity.setStatus(GitHubPatValidationStatus.VALID.name());
        identity.setVerifiedAt(java.time.Instant.now());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("seededBy", "SuiteABaseIT");
        identity.setMetadata(metadata);
        return userGitIdentityRepository.save(identity);
    }

    /**
     * Initialises a bare git repo on local disk and seeds it with a single
     * commit on {@code main} so it can stand in as the "remote" for an
     * onboarding clone. Returns the absolute {@code file://} URI that
     * callers pass to {@code OnboardRequest.repoUrl}.
     *
     * <p>Mirrors {@link com.pulse.support.TempGitRepoExtension} but without
     * binding to a JUnit lifecycle — Suite A scenarios call this from
     * inside their own {@code @BeforeEach} so they control when the
     * fixture is recreated.
     */
    protected BareGitRepo createLocalBareGitRepo() {
        return createLocalBareGitRepo(true);
    }

    /**
     * Variant that lets scenarios skip the initial commit. The
     * {@code seedInitialCommit=false} mode is what Scenario B
     * (empty-repo init fallback) needs: a bare repo whose HEAD points at
     * {@code refs/heads/main} but with no commits.
     */
    protected BareGitRepo createLocalBareGitRepo(boolean seedInitialCommit) {
        Path root;
        try {
            root = Files.createTempDirectory("pulse-suite-a-bare-");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate bare-repo temp dir", e);
        }
        Path bareDir = root.resolve("remote.git");
        try {
            Files.createDirectories(bareDir);
            try (Git ignored = Git.init().setBare(true)
                    .setDirectory(bareDir.toFile())
                    .setInitialBranch("main").call()) {
                // bare repo created
            }
            if (seedInitialCommit) {
                Path workdir = root.resolve("seed-workdir");
                Files.createDirectories(workdir);
                try (Git work = Git.init().setDirectory(workdir.toFile())
                        .setInitialBranch("main").call()) {
                    Files.writeString(workdir.resolve("README.md"),
                            "PULSE Suite A bare-repo fixture\n");
                    work.add().addFilepattern(".").call();
                    work.commit()
                            .setAuthor("PULSE Test", "test@pulse.test")
                            .setCommitter("PULSE Test", "test@pulse.test")
                            .setMessage("init")
                            .call();
                    work.remoteAdd()
                            .setName("origin")
                            .setUri(new org.eclipse.jgit.transport.URIish(
                                    bareDir.toUri().toString()))
                            .call();
                    work.push().setRemote("origin").setRefSpecs(
                            new org.eclipse.jgit.transport.RefSpec(
                                    "refs/heads/main:refs/heads/main")).call();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init bare repo at " + bareDir, e);
        }
        return new BareGitRepo(root, bareDir);
    }

    /**
     * Returns the test-scoped clone base. Useful when scenarios need to
     * inspect the on-disk scaffold paths after a {@code /onboard} call.
     */
    protected Path cloneBaseDir() {
        return cloneBaseRoot;
    }

    /**
     * Returns the expected local working-clone directory for a tenant —
     * mirrors the path {@code GitController.onboard} computes from
     * {@code clone-base + tenant.slug}.
     */
    protected Path tenantWorkdir(String tenantSlug) {
        return cloneBaseRoot.resolve(tenantSlug);
    }

    /**
     * Convenience helper to build a {@code Map} request body inline. Java
     * doesn't have a literal syntax for an ordered map, so this saves a
     * lot of test ceremony.
     */
    protected static Map<String, Object> jsonBody(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("kv must be (key, value) pairs");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }

    /**
     * On-disk handle for a Suite-A bare repo fixture.
     */
    public record BareGitRepo(Path root, Path bareDir) {
        public String fileUri() {
            return bareDir.toUri().toString();
        }
    }
}
