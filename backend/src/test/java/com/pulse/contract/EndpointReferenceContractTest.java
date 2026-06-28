package com.pulse.contract;

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
 * Layer 2.5 contract test (BUG-2026-05-26-67 META-packet, BUG-58 regression
 * guard).
 *
 * <p>Scans {@code ConsolidatedTenantReadinessService} for every endpoint
 * string it hands operators as a remediation hint (the {@code "GET /api/v1/…"}
 * literals it stuffs into readiness-blocker {@code actions} lists) and asserts
 * that EACH path resolves to a real {@code @RequestMapping} on a real
 * controller in {@code com.pulse.**.controller}.
 *
 * <p>Why this matters: BUG-58 was "{@code TenantSecretManagerController} was
 * referenced in five places by the readiness service but the file did not
 * exist." The MockMvc tests in Suite A do not catch this because they never
 * try to invoke the missing route. This contract test does, statically.
 *
 * <h3>Scope</h3>
 *
 * <p>This test deliberately only inspects the strings that {@code
 * ConsolidatedTenantReadinessService} emits because those are the strings
 * operators (and the chat agent) see in the UI as "fix this by calling X."
 * Hand-written {@code api.get("/api/v1/foo")} calls in TypeScript components
 * are out of scope for this file; the frontend orphan-type test
 * ({@code frontend/src/test/contract/orphan-type.test.ts}) is the symmetric
 * frontend-side check.
 *
 * <h3>EXPECTED-PASS-AFTER status</h3>
 *
 * <p>As of the SU-8 META-packet commit, this test FAILS against {@code main}
 * with one unresolved hint:
 * <ul>
 *   <li>{@code GET /api/v1/tenants/{tenantId}/gcp-runtime-topology/iam-manifest}
 *       — the readiness service emits this path but
 *       {@link com.pulse.tenant.controller.TenantGcpRuntimeTopologyController}
 *       maps it as {@code GET /api/v1/tenants/{tenantId}/gcp-iam-manifest}
 *       (no {@code /gcp-runtime-topology} prefix). One of the two strings is
 *       wrong; the operator's chat hint cannot succeed today.
 *       Track resolution under a follow-up BUG-2026-05-26-67-followup-iam-manifest
 *       or fold into BUG-69 scope.</li>
 * </ul>
 *
 * <p>The test is intentionally NOT {@code @Disabled} — it fails loudly so the
 * gate-flip is visible the moment the readiness-service string or the
 * controller mapping is reconciled.
 */
@Tag("contract")
@DisplayName("Layer 2.5 / BUG-67 — endpoint references in ConsolidatedTenantReadinessService must resolve")
class EndpointReferenceContractTest {

    /**
     * Repo root resolved relative to this class's working directory at test
     * time. Gradle's test JVM runs with {@code working-directory = backend/}
     * so {@code Paths.get("src/main/java")} is the right anchor for the
     * source-scan.
     */
    private static final Path BACKEND_SRC_MAIN = Paths.get("src", "main", "java");

    /**
     * The single source file we scan for emitted endpoint strings. Tightening
     * scope keeps the test fast and intent-narrow. If a future readiness
     * service joins the family, add it to {@link #READINESS_SERVICE_FILES}.
     */
    private static final List<Path> READINESS_SERVICE_FILES = List.of(
            BACKEND_SRC_MAIN.resolve("com/pulse/tenant/service/ConsolidatedTenantReadinessService.java")
    );

    /**
     * Matches the canonical {@code "VERB /api/v1/…"} literal as it appears in
     * the readiness service. We accept the path either as a plain literal
     * (e.g. {@code "GET /api/v1/users/me/git-identity"}) OR as a literal
     * stitched to a {@code + tenantId +} variable (which is how every
     * tenant-scoped string is built).
     *
     * <p>The regex captures: group(1) = HTTP verb, group(2) = the path
     * literal up to (but not including) the closing quote or the {@code +}.
     * We then normalise variable-stitched paths by treating {@code "} + {@code
     * + tenantId +} + path-suffix as a single template.
     */
    private static final Pattern EMITTED_ENDPOINT = Pattern.compile(
            "\"(GET|POST|PUT|PATCH|DELETE)\\s+(/api/v1/[^\"]*?)\""
    );

    /**
     * Matches any {@code @GetMapping(...)} / {@code @PostMapping(...)} etc.
     * Group(1) = HTTP verb. Group(2) = the (possibly empty) path string inside
     * the parens; absent / empty for the parameterless form
     * ({@code @GetMapping} with no args). An empty path means "use the
     * class-level @RequestMapping value verbatim."
     *
     * <p>{@code (?![A-Za-z])} after the suffix prevents partial matches against
     * a hypothetical {@code @GetMappingExtension}.
     */
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Patch|Delete)Mapping(?:\\(\\s*(?:value\\s*=\\s*)?(?:\"([^\"]*)\")?[^)]*\\))?(?![A-Za-z])"
    );

    /**
     * Matches a class-level {@code @RequestMapping("…")}. Group(1) = path.
     */
    private static final Pattern CLASS_REQUEST_MAPPING = Pattern.compile(
            "@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\""
    );

    @Test
    @DisplayName("every emitted /api/v1/* string in ConsolidatedTenantReadinessService resolves to a controller mapping")
    void everyEmittedEndpointResolves() throws IOException {
        Set<String> emitted = collectEmittedEndpoints();
        assertThat(emitted)
                .as("readiness service should emit at least a handful of endpoint hints; if this is empty the scanner is broken")
                .isNotEmpty();

        Set<String> registered = collectRegisteredEndpoints();
        assertThat(registered)
                .as("controller scan should find dozens of @*Mapping annotations across com.pulse.**.controller")
                .hasSizeGreaterThan(50);

        List<String> unresolved = new ArrayList<>();
        for (String hint : emitted) {
            if (!isResolved(hint, registered)) {
                unresolved.add(hint);
            }
        }

        assertThat(unresolved)
                .as("Every endpoint string emitted by ConsolidatedTenantReadinessService must resolve to a real "
                        + "@*Mapping on some controller under com.pulse.**.controller.*. Unresolved hints below indicate "
                        + "a regression of the BUG-58 / BUG-70 / BUG-71 shape (readiness service tells the operator "
                        + "to call X, but X has no controller). Either add the missing @*Mapping or fix the emitted "
                        + "string in ConsolidatedTenantReadinessService.")
                .isEmpty();
    }

    // ---------------------------------------------------------------------
    //  Source-scan helpers
    // ---------------------------------------------------------------------

    /**
     * Reads each readiness-service file and pulls every {@code "VERB /api/v1/…"}
     * literal out. Tenant-id template variables ({@code "+tenantId+"}) collapse
     * to a wildcard token so the test matches the controller-side mapping by
     * shape rather than by literal text.
     */
    private static Set<String> collectEmittedEndpoints() throws IOException {
        Set<String> emitted = new LinkedHashSet<>();
        for (Path file : READINESS_SERVICE_FILES) {
            if (!Files.exists(file)) {
                throw new AssertionError("Readiness service source missing: " + file.toAbsolutePath()
                        + " — the contract test cannot run. Did the file move?");
            }
            String body = Files.readString(file);
            // Normalize the readiness-service style of `"GET /api/v1/tenants/" + tenantId + "/foo"`
            // into a single literal `"GET /api/v1/tenants/{tenantId}/foo"` for matching.
            String normalised = normaliseTemplate(body);
            Matcher m = EMITTED_ENDPOINT.matcher(normalised);
            while (m.find()) {
                String verb = m.group(1);
                String path = m.group(2).trim();
                // Strip trailing words like "with active credential" that
                // appear after the actual path in some hints — the path
                // ends at the first space-after-/api/v1.
                int firstSpace = path.indexOf(' ');
                if (firstSpace > 0) {
                    path = path.substring(0, firstSpace);
                }
                emitted.add(verb + " " + path);
            }
        }
        return emitted;
    }

    /**
     * Replaces the {@code "..." + variable + "..."} string-concat idiom with a
     * single {@code "..." } literal containing a {@code {variable}} placeholder
     * so the regex above can pick the whole thing up.
     *
     * <p>Concrete example:
     * <pre>
     *   "GET /api/v1/tenants/" + tenantId + "/gcp-config"
     *      → "GET /api/v1/tenants/{tenantId}/gcp-config"
     * </pre>
     */
    private static String normaliseTemplate(String source) {
        // 1. Collapse "..." + identifier + "..." → "...{identifier}..."
        Pattern concat = Pattern.compile("\"\\s*\\+\\s*([A-Za-z_][A-Za-z0-9_.]*?(?:\\.get\\(\"[^\"]+\"\\))?)\\s*\\+\\s*\"");
        Matcher cm = concat.matcher(source);
        StringBuilder out = new StringBuilder();
        while (cm.find()) {
            String ident = cm.group(1);
            // d.get("domainId") → {domainId}
            if (ident.contains(".get(\"")) {
                int q1 = ident.indexOf("\"") + 1;
                int q2 = ident.indexOf("\"", q1);
                cm.appendReplacement(out, "{" + Matcher.quoteReplacement(ident.substring(q1, q2)) + "}");
            } else {
                cm.appendReplacement(out, "{" + Matcher.quoteReplacement(ident) + "}");
            }
        }
        cm.appendTail(out);
        // 2. Some emitted hints end the literal at a `+` only (no second "),
        //    e.g. `"GET /api/v1/tenants/" + tenantId` followed by `,`. Patch
        //    those into a closed literal so the EMITTED_ENDPOINT regex picks
        //    them up.
        Pattern halfOpen = Pattern.compile("\"\\s*\\+\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?=[,)\\s\\n])");
        Matcher hm = halfOpen.matcher(out.toString());
        StringBuilder out2 = new StringBuilder();
        while (hm.find()) {
            hm.appendReplacement(out2, "{" + Matcher.quoteReplacement(hm.group(1)) + "}\"");
        }
        hm.appendTail(out2);
        return out2.toString();
    }

    /**
     * Walks every {@code com/pulse/**\/controller/} directory and assembles
     * the full set of registered {@code "VERB /api/…"} routes by combining
     * class-level {@code @RequestMapping} prefixes with each method-level
     * {@code @*Mapping}.
     */
    private static Set<String> collectRegisteredEndpoints() throws IOException {
        Set<String> registered = new LinkedHashSet<>();
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
                            extractMappings(p, registered);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed reading " + p, e);
                        }
                    });
        }
        return registered;
    }

    /**
     * Parses one controller file. Finds the class-level {@code @RequestMapping}
     * prefix (or empty) and joins it with every method-level {@code @*Mapping}
     * path to build the full {@code "VERB /api/…"} tuple.
     */
    private static void extractMappings(Path file, Set<String> sink) throws IOException {
        String body = Files.readString(file);
        Matcher classRm = CLASS_REQUEST_MAPPING.matcher(body);
        String prefix = classRm.find() ? classRm.group(1) : "";
        Matcher methods = METHOD_MAPPING.matcher(body);
        while (methods.find()) {
            String verb = methods.group(1).toUpperCase(Locale.ROOT);
            String pathSuffix = methods.group(2);
            if (pathSuffix == null) pathSuffix = "";
            String joined = joinPath(prefix, pathSuffix);
            sink.add(verb + " " + joined);
        }
    }

    private static String joinPath(String prefix, String suffix) {
        String a = prefix == null ? "" : prefix.trim();
        String b = suffix == null ? "" : suffix.trim();
        if (b.isEmpty()) return a;
        if (a.isEmpty()) return b;
        boolean ae = a.endsWith("/");
        boolean bs = b.startsWith("/");
        if (ae && bs) return a + b.substring(1);
        if (!ae && !bs) return a + "/" + b;
        return a + b;
    }

    /**
     * A registered controller mapping matches an emitted readiness hint iff:
     * (a) the verbs match exactly, AND (b) the path matches treating every
     * Spring {@code {pathVar}} segment and every readiness-service
     * {@code {javaIdentifier}} placeholder as equivalent wildcards.
     */
    private static boolean isResolved(String hint, Set<String> registered) {
        String[] parts = hint.split(" ", 2);
        if (parts.length != 2) return false;
        String hintVerb = parts[0];
        String hintPath = parts[1];
        String hintNormalised = normalisePlaceholders(hintPath);
        for (String reg : registered) {
            String[] rp = reg.split(" ", 2);
            if (rp.length != 2 || !rp[0].equals(hintVerb)) continue;
            String regNormalised = normalisePlaceholders(rp[1]);
            if (regNormalised.equals(hintNormalised)) return true;
        }
        return false;
    }

    /**
     * Reduces both Spring path-var syntax ({@code {pathVar}}) and our
     * normalised readiness-service placeholders ({@code {tenantId}}) to a
     * single canonical wildcard {@code *}. This lets the contract match by
     * SHAPE — "any path-variable here" — without forcing the readiness
     * service and the controller to use identical variable names.
     */
    private static String normalisePlaceholders(String path) {
        // Strip trailing slashes for canonical form
        String s = path.replaceAll("\\{[^}]+}", "*");
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
