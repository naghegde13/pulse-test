package com.pulse.contract;

import com.pulse.auth.model.UserRole;
import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.PulseRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 2.5 contract test (BUG-2026-05-26-67 META-packet, BUG-66 regression
 * guard).
 *
 * <p>BUG-66 was "the Runtime Bindings UI exposes a Save button whose
 * controller is gated by {@code @PreAuthorize("hasRole('ADMIN') or
 * hasRole('PLATFORM_ADMIN')")}; in stub-auth mode the stub user happens to
 * hold every role so the call succeeds, but a tiny typo in the role string —
 * or a future restriction on stub roles — would return 403 and the UI would
 * render the generic "Unhandled exception" toast."
 *
 * <p>This test catches the static-half of that contract: every role mentioned
 * in a {@code @PreAuthorize} expression on a controller method MUST be a real
 * role known to the platform (a {@link PulseRole} enum constant, a legacy
 * {@link UserRole} constant, or one of the small allowlist of Spring-magic
 * role names like {@code ANONYMOUS}). A typo'd role string slips through
 * compilation because {@code @PreAuthorize} is a String SpEL expression; this
 * test makes the typo fail loudly at build time instead of at runtime in the
 * browser.
 *
 * <p>The second half of the BUG-66 contract — "the calling React component
 * holds a matching role-gate so it doesn't render the action when the actor
 * lacks the role" — needs a TSX AST walker and is out of scope for the source
 * scan. See {@code DEVIATIONS.md §4} for the explicit decision.
 *
 * <h3>Stub-mode safety contract</h3>
 *
 * <p>BUG-66 also surfaces in stub mode if {@link ActorResolverService} is
 * ever tightened: the test asserts that
 * {@link ActorResolverService#defaultRoles()} continues to be the full
 * {@code EnumSet.allOf(PulseRole.class)} so dev/test flows do not start
 * failing 403 silently. If we ever DELIBERATELY shrink the stub role set,
 * this test will fail and the operator decision will need to be re-confirmed
 * (and this test updated).
 */
@Tag("contract")
@DisplayName("Layer 2.5 / BUG-67 — every @PreAuthorize role on a controller method must be a real platform role")
class UiActionRoleContractTest {

    private static final Path BACKEND_SRC_MAIN = Paths.get("src", "main", "java");

    /**
     * Roles that are NOT enum members but are legal Spring SpEL role names
     * recognised by the security expression handler. Empty by default; add
     * here if we ever start using {@code hasRole('ANONYMOUS')} or similar.
     */
    private static final Set<String> SPRING_MAGIC_ROLES = Set.of(
            "ANONYMOUS",
            "AUTHENTICATED"
    );

    /**
     * Matches {@code hasRole('FOO')} / {@code hasAuthority('FOO')} /
     * {@code hasAnyRole('FOO','BAR')} inside a @PreAuthorize SpEL string.
     * Group(1) = role name (quotes already stripped).
     *
     * <p>Single-quote and double-quote forms both accepted because SpEL
     * accepts both, and the Java string literal can use either depending on
     * escaping convenience.
     */
    private static final Pattern ROLE_USAGE = Pattern.compile(
            "has(?:Any)?(?:Role|Authority)\\(\\s*['\"]([A-Z_][A-Z0-9_]*)['\"]"
    );

    /**
     * Matches a @PreAuthorize annotation block, capturing the SpEL string
     * argument. Handles single-line annotations and multi-line ones (the
     * SpEL expression can be concatenated across lines with {@code +}).
     */
    private static final Pattern PRE_AUTHORIZE = Pattern.compile(
            "@PreAuthorize\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
    );

    @Test
    @DisplayName("every @PreAuthorize role in a controller method resolves to a real platform role")
    void everyPreAuthorizeRoleResolves() throws IOException {
        Set<String> knownRoles = buildKnownRoleSet();
        assertThat(knownRoles)
                .as("known role set should contain the PulseRole + legacy UserRole enums; "
                        + "if empty the reflective load failed")
                .contains("PLATFORM_ADMIN", "TENANT_ADMIN", "ADMIN", "CITIZEN");

        List<RoleUsage> usages = collectPreAuthorizeUsages();
        assertThat(usages)
                .as("expected at least a handful of @PreAuthorize annotations on controllers; "
                        + "if empty the scanner is broken")
                .isNotEmpty();

        List<String> unknown = new ArrayList<>();
        for (RoleUsage u : usages) {
            if (!knownRoles.contains(u.role)) {
                unknown.add(u.toString());
            }
        }

        assertThat(unknown)
                .as("Every role name used inside a @PreAuthorize expression on a controller "
                        + "method must be a real platform role (PulseRole enum constant, legacy "
                        + "UserRole constant, or a Spring-magic name listed in SPRING_MAGIC_ROLES). "
                        + "An unknown role is BUG-66 in source: the typo compiles because "
                        + "@PreAuthorize is a String SpEL, but at runtime the call returns 403 "
                        + "and the UI shows an unhandled-exception toast. Fix the typo, or add "
                        + "the role to the platform enum.")
                .isEmpty();
    }

    @Test
    @DisplayName("stub-mode actor resolver still grants every role (BUG-66 dev-mode contract)")
    void stubRolesUnchanged() {
        Set<PulseRole> stubRoles = ActorResolverService.defaultRoles();
        // EnumSet.allOf(PulseRole.class) is the documented stub contract; if
        // a future change tightens this, the dev/test stub user starts
        // failing 403 on UI actions like the Runtime Bindings Save button
        // (the BUG-66 reproduction surface). When this test fails, do NOT
        // simply update the assertion — read DEVIATIONS.md §4 first and
        // confirm the operator is aware that dev/test will start
        // exercising the same 403 path production sees.
        assertThat(stubRoles)
                .as("Dev/test stub actor must hold every PulseRole so @PreAuthorize'd UI "
                        + "actions in stub-auth mode do not regress to 403 silently. See "
                        + "BUG-2026-05-26-66.")
                .containsExactlyInAnyOrderElementsOf(java.util.EnumSet.allOf(PulseRole.class));
    }

    // ---------------------------------------------------------------------
    //  Source-scan + role-catalog helpers
    // ---------------------------------------------------------------------

    private static Set<String> buildKnownRoleSet() {
        Set<String> known = new LinkedHashSet<>();
        for (PulseRole r : PulseRole.values()) {
            known.add(r.name());
        }
        for (UserRole r : UserRole.values()) {
            known.add(r.name());
        }
        known.addAll(SPRING_MAGIC_ROLES);
        return known;
    }

    /**
     * Walks every {@code *Controller.java} under {@code com.pulse.**.controller}
     * and pulls out every {@code @PreAuthorize("hasRole('FOO') or hasRole('BAR')")}
     * expression, decomposing it into one {@link RoleUsage} per role name.
     */
    private static List<RoleUsage> collectPreAuthorizeUsages() throws IOException {
        List<RoleUsage> usages = new ArrayList<>();
        if (!Files.exists(BACKEND_SRC_MAIN)) {
            throw new AssertionError("Backend source root missing: " + BACKEND_SRC_MAIN.toAbsolutePath());
        }
        try (Stream<Path> files = Files.walk(BACKEND_SRC_MAIN)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("Controller.java"))
                    .filter(p -> p.toString().contains("/controller/")
                            || p.toString().contains("\\controller\\"))
                    .forEach(p -> {
                        try {
                            extractPreAuthorize(p, usages);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed reading " + p, e);
                        }
                    });
        }
        return usages;
    }

    private static void extractPreAuthorize(Path file, List<RoleUsage> sink) throws IOException {
        String body = Files.readString(file);
        Matcher pre = PRE_AUTHORIZE.matcher(body);
        while (pre.find()) {
            String spel = pre.group(1);
            Matcher rm = ROLE_USAGE.matcher(spel);
            while (rm.find()) {
                String role = rm.group(1).toUpperCase(Locale.ROOT);
                sink.add(new RoleUsage(file.getFileName().toString(), spel, role));
            }
        }
    }

    private record RoleUsage(String controllerFile, String preAuthorizeSpel, String role) {
        @Override
        public String toString() {
            return controllerFile + ": @PreAuthorize(\"" + preAuthorizeSpel + "\") references unknown role '" + role + "'";
        }
    }
}
