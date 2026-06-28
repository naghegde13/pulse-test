package com.pulse.auth.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Systemic regression guard installed by BUG-2026-05-26-71 (and prior partial-ship
 * bugs BUG-58 / BUG-66-path-shape).
 *
 * <p>Scans the four in-scope service packages
 * ({@code com.pulse.{auth,tenant,storage,secret}.service.*}) via classpath reflection
 * for PUBLIC instance methods whose name starts with {@code submit}, {@code register},
 * {@code upsert}, or {@code create} followed by an uppercase letter. Each match must
 * have a corresponding HTTP route ({@code @PostMapping}, {@code @PutMapping}, or
 * {@code @PatchMapping}) somewhere in {@code com.pulse.*.controller.*}, OR be present
 * in the {@link #INTERNAL_UTILITY_ALLOWLIST} (documented internal-only utilities).
 *
 * <p>The controller side of the check is a source-file scan rather than a runtime
 * {@code RequestMappingHandlerMapping} dump because we want to assert that the
 * SERVICE METHOD is actually invoked from a mutation handler — a route that exists
 * but never calls the service method is still an orphan. We therefore grep the
 * controller {@code .java} files for both the mutation mapping annotation and the
 * literal method-name token; both must appear in the same file. Source-file scan
 * keeps the test deterministic and free of full-context startup cost.
 *
 * <p><strong>How to satisfy a failure:</strong> if this test fails, either
 * (a) add the controller route that delegates to the new service method, OR
 * (b) explicitly document the method as internal-only by adding its fully-qualified
 *     name ({@code com.pulse.x.service.FooService.barMethod}) to
 *     {@link #INTERNAL_UTILITY_ALLOWLIST} with a one-line comment explaining why
 *     there is no HTTP surface. The allowlist forces an explicit human decision so
 *     a future "service shipped but controller missing" regression cannot land silently.
 */
class OrphanServiceMethodContractTest {

    /**
     * Packages whose public submit/register/upsert/create methods are required to
     * have a corresponding mutation HTTP endpoint. These are the four packages that
     * own user-facing onboarding / credential / topology state — exactly the surfaces
     * where a missing endpoint produces the kind of silent partial-ship that
     * BUG-58, BUG-66, and BUG-71 each represented.
     */
    private static final List<String> IN_SCOPE_SERVICE_PACKAGES = List.of(
            "com.pulse.auth.service",
            "com.pulse.tenant.service",
            "com.pulse.storage.service",
            "com.pulse.secret.service"
    );

    /**
     * Mutation method-name prefixes that imply a user-driven state change worth
     * exposing over HTTP. Matched as {@code prefix + UpperCaseLetter}, so
     * {@code submitCredential} matches but {@code submit} (no suffix) does not.
     */
    private static final List<String> MUTATION_PREFIXES = List.of(
            "submit", "register", "upsert", "create"
    );

    /**
     * Documented internal utilities — service methods that intentionally have NO
     * HTTP surface because they are called from other backend code paths
     * (credential resolution, contract lifecycle, etc.). Adding an entry here is
     * an explicit declaration that the absence of an endpoint is by design.
     * Format: fully-qualified {@code package.Class.method}.
     */
    private static final Set<String> INTERNAL_UTILITY_ALLOWLIST = Set.of(
            // Secret-Manager bridge — used by UserGitIdentityService to persist
            // PATs/SSH keys after the parent flow has already authenticated. No
            // standalone HTTP endpoint by design (the user-facing secret upsert
            // route lives on TenantSecretManagerController and forwards through
            // TenantSecretManagerBindingService.upsert, which IS in-scope).
            "com.pulse.secret.service.GcpSecretManagerService.createOrUpdateSecret"
    );

    /**
     * Mutation mapping annotations whose presence in a controller source file
     * signals an HTTP write route. We intentionally exclude {@code @GetMapping}
     * (reads cannot satisfy a mutation contract) and {@code @DeleteMapping}
     * (a delete cannot satisfy a submit/register/upsert/create method).
     */
    private static final Pattern MUTATION_ANNOTATION = Pattern.compile(
            "@(PostMapping|PutMapping|PatchMapping)\\b");

    @Test
    @DisplayName("Every public submit*/register*/upsert*/create* method in {auth,tenant,storage,secret}.service.* must have a mutation HTTP endpoint or be allowlisted")
    void allPublicMutationServiceMethodsHaveControllerEndpoint() throws Exception {
        List<Class<?>> serviceClasses = scanServiceClasses();
        assertTrue(!serviceClasses.isEmpty(),
                "classpath scan returned zero service classes — scope packages may have moved");

        List<String> controllerSources = loadControllerSources();
        assertTrue(!controllerSources.isEmpty(),
                "no controller source files found — backend src tree may have moved");

        List<String> orphans = new ArrayList<>();

        for (Class<?> serviceClass : serviceClasses) {
            for (Method method : serviceClass.getDeclaredMethods()) {
                if (!isPublicInstanceMethod(method)) continue;
                if (!nameMatchesMutationPrefix(method.getName())) continue;

                String fqMethod = serviceClass.getName() + "." + method.getName();
                if (INTERNAL_UTILITY_ALLOWLIST.contains(fqMethod)) continue;

                if (!hasControllerEndpoint(controllerSources, method.getName())) {
                    orphans.add(fqMethod
                            + " (no controller source file references the method "
                            + "alongside @PostMapping/@PutMapping/@PatchMapping; "
                            + "either add the route or add the method to "
                            + "INTERNAL_UTILITY_ALLOWLIST with a documenting comment)");
                }
            }
        }

        if (!orphans.isEmpty()) {
            fail("Found " + orphans.size() + " orphan mutation service method(s):\n  "
                    + String.join("\n  ", orphans));
        }
    }

    /**
     * Sanity check: the known in-scope mutation methods we DO want endpoints for
     * are actually being matched by the scanner. Prevents a silent regression
     * where the package-scan misses everything (e.g. classpath misconfigured) and
     * the main test passes vacuously.
     */
    @Test
    @DisplayName("Scanner sanity: the canonical in-scope mutation methods are picked up by the reflection scan")
    void scannerPicksUpKnownInScopeMethods() throws Exception {
        List<Class<?>> serviceClasses = scanServiceClasses();
        List<String> mutationFqNames = serviceClasses.stream()
                .flatMap(c -> Stream.of(c.getDeclaredMethods())
                        .filter(this::isPublicInstanceMethod)
                        .filter(m -> nameMatchesMutationPrefix(m.getName()))
                        .map(m -> c.getName() + "." + m.getName()))
                .toList();

        // The three canonical examples from BUG-71's adjacent-scan section. If any
        // of these disappears from the scan output, the test scope has drifted and
        // we should investigate before trusting the orphan check.
        assertTrue(mutationFqNames.contains(
                "com.pulse.auth.service.TenantGcpCredentialService.submitCredential"),
                "scanner missed submitCredential; scope: " + mutationFqNames);
        assertTrue(mutationFqNames.contains(
                "com.pulse.auth.service.TenantGcpCredentialService.submitImpersonationCredential"),
                "scanner missed submitImpersonationCredential (the BUG-71 method); scope: "
                        + mutationFqNames);
        assertTrue(mutationFqNames.contains(
                "com.pulse.auth.service.TenantService.createTenant"),
                "scanner missed createTenant; scope: " + mutationFqNames);
    }

    // ── helpers ────────────────────────────────────────────────

    private List<Class<?>> scanServiceClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // Match every concrete class under the service package — we filter to
        // public-non-abstract via reflection below. Anonymous/synthetic classes
        // get filtered out naturally because they live under a $ in the FQN and
        // the source-file scanner ignores those.
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*Service$")));

        List<Class<?>> classes = new ArrayList<>();
        for (String pkg : IN_SCOPE_SERVICE_PACKAGES) {
            for (var bean : scanner.findCandidateComponents(pkg)) {
                try {
                    Class<?> cls = Class.forName(bean.getBeanClassName());
                    if (cls.isInterface()) continue;
                    if (Modifier.isAbstract(cls.getModifiers())) continue;
                    classes.add(cls);
                } catch (ClassNotFoundException ignored) {
                    // Skip — non-loadable candidate, not relevant for the contract test.
                }
            }
        }
        return classes;
    }

    private List<String> loadControllerSources() throws IOException {
        // Source-file scan, not classpath: we want to match the method-name token
        // exactly as it appears in the controller body, alongside the mutation
        // annotation. Walking the FILESYSTEM keeps the test independent of how
        // controllers are wired (DI vs. explicit instantiation, etc.).
        Path controllerRoot = Paths.get("src", "main", "java", "com", "pulse")
                .toAbsolutePath();
        // Tests run with cwd = backend/, so the path above resolves under the
        // backend module. If a future tree restructure breaks this, the empty-list
        // guard in the main test will fail loudly.
        if (!Files.isDirectory(controllerRoot)) {
            return List.of();
        }
        List<String> sources = new ArrayList<>();
        MetadataReaderFactory unused = new SimpleMetadataReaderFactory(); // kept for future use
        try (Stream<Path> stream = Files.walk(controllerRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/controller/"))
                    .forEach(p -> {
                        try {
                            sources.add(Files.readString(p));
                        } catch (IOException ignored) {
                            // Skip unreadable file — main test enforces non-empty result set.
                        }
                    });
        }
        return sources;
    }

    private boolean isPublicInstanceMethod(Method m) {
        int mods = m.getModifiers();
        return Modifier.isPublic(mods)
                && !Modifier.isStatic(mods)
                && !m.isSynthetic()
                && !m.isBridge();
    }

    private boolean nameMatchesMutationPrefix(String name) {
        for (String prefix : MUTATION_PREFIXES) {
            if (name.length() > prefix.length()
                    && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasControllerEndpoint(List<String> controllerSources, String methodName) {
        // A controller satisfies the contract iff: same source file contains both
        // a mutation mapping annotation AND the method-name token used as a call
        // (foo.methodName( or this::methodName). We match the token followed by
        // an open-paren to avoid false positives on identically-named getters.
        Pattern callPattern = Pattern.compile(
                "\\b" + Pattern.quote(methodName) + "\\s*\\(");
        for (String src : controllerSources) {
            if (MUTATION_ANNOTATION.matcher(src).find()
                    && callPattern.matcher(src).find()) {
                return true;
            }
        }
        return false;
    }
}
