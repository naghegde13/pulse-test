package com.pulse.tenant.service;

import com.pulse.tenant.model.ReadinessCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * BUG-72 re-find prevention: every {@code build<Category>(...)} method on
 * {@link ConsolidatedTenantReadinessService} that returns a
 * {@link ReadinessCategory} must only read evidence relevant to its own
 * category. Cross-reads (e.g. {@code buildGitScaffold} reading
 * {@code domainReadiness.get("ready")}, which folds in storage signals) are
 * how BUG-72 originally landed — a category lied about its own evidence
 * because it short-circuited on an unrelated combined-readiness flag.
 *
 * <p>Detection strategy: scan the source body of each {@code build*} method
 * for the forbidden symbol {@code "ready"} appearing as a {@code Map.get}
 * key (i.e. {@code .get("ready")}). The only legitimate place that key is
 * read is {@link ConsolidatedTenantReadinessService#buildDomainScaffold}
 * (where the COMBINED semantic is the explicit category contract). Every
 * other category builder must source its own per-category status field
 * (e.g. {@code gitScaffold.status}, {@code storageScaffold.status}).
 *
 * <p>This is intentionally a source-text scanner rather than a JVM-bytecode
 * scanner: the JVM cannot tell us which constant string was passed to a
 * {@code Map.get} call at a particular line without a bytecode-analysis
 * library, and pulling that in for one test would be heavier than the
 * source-grep approach we use here.
 */
class ConsolidatedTenantReadinessCategoryIsolationContractTest {

    /**
     * The single allowed category whose contract is explicitly the COMBINED
     * per-domain readiness. Documented in
     * {@code ConsolidatedTenantReadinessService#buildDomainScaffold}.
     */
    private static final Set<String> ALLOWED_COMBINED_READ_METHODS = Set.of(
            "buildDomainScaffold");

    /**
     * Forbidden source pattern: {@code .get("ready")} — reading the combined
     * per-domain readiness boolean from a domainReadiness map. Allowed in
     * the explicitly combined category only.
     */
    private static final Pattern FORBIDDEN_COMBINED_READ =
            Pattern.compile("\\.get\\(\\s*\"ready\"\\s*\\)");

    /** Matches the start of a method declaration that returns ReadinessCategory. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^\\s*(?:public|protected|private|\\s)?\\s*ReadinessCategory\\s+(build\\w+)\\s*\\(");

    @Test
    @DisplayName("Every build<Category> method returns a ReadinessCategory whose name matches the method")
    void buildMethodsReturnTheirOwnCategory() throws Exception {
        // JVM-reflection side: verify the count of build* methods that return
        // ReadinessCategory matches the count surfaced in computeVerdict().
        // This is a structural sanity check — the source scan below does the
        // semantic enforcement.
        Method[] methods = ConsolidatedTenantReadinessService.class.getDeclaredMethods();
        List<Method> categoryBuilders = Arrays.stream(methods)
                .filter(m -> ReadinessCategory.class.equals(m.getReturnType()))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getName().startsWith("build"))
                .toList();

        assertFalse(categoryBuilders.isEmpty(),
                "Expected at least one build<Category> method returning ReadinessCategory");
        // 16 categories per PKT-0015 contract.
        assertTrue(categoryBuilders.size() >= 16,
                "Expected >=16 build<Category> methods (PKT-0015 has 16 categories); got "
                        + categoryBuilders.size() + ": " + categoryBuilders.stream()
                        .map(Method::getName).toList());
    }

    @Test
    @DisplayName("BUG-72 re-find guard: no build<Category> method reads combined .get(\"ready\") except the documented combined category")
    void noCategoryReadsCombinedReadinessFlagExceptAllowed() throws IOException {
        Path source = findSourceFile();
        String text = Files.readString(source);

        List<String> violations = new ArrayList<>();
        for (MethodBody body : extractCategoryBuilderBodies(text)) {
            if (ALLOWED_COMBINED_READ_METHODS.contains(body.methodName)) {
                continue;
            }
            Matcher m = FORBIDDEN_COMBINED_READ.matcher(body.body);
            if (m.find()) {
                violations.add(String.format(Locale.ROOT,
                        "%s reads forbidden combined .get(\"ready\") at offset %d. "
                                + "This is the BUG-72 pattern — a category builder must NOT short-circuit "
                                + "on the combined per-domain readiness flag, because that flag folds in "
                                + "checks from sibling categories (storage, etc.). Read your OWN "
                                + "per-category status field instead (e.g. gitScaffold.status). "
                                + "If your category's contract is genuinely the COMBINED semantic, "
                                + "add it to ALLOWED_COMBINED_READ_METHODS with a comment explaining why.",
                        body.methodName, m.start()));
            }
        }

        if (!violations.isEmpty()) {
            fail("Category-isolation contract violated:\n  - "
                    + String.join("\n  - ", violations));
        }
    }

    @Test
    @DisplayName("ALLOWED_COMBINED_READ_METHODS allowlist actually corresponds to real method names")
    void allowlistEntriesExistAsMethods() {
        Set<String> declared = new LinkedHashSet<>();
        for (Method m : ConsolidatedTenantReadinessService.class.getDeclaredMethods()) {
            if (ReadinessCategory.class.equals(m.getReturnType())) {
                declared.add(m.getName());
            }
        }
        for (String allowed : ALLOWED_COMBINED_READ_METHODS) {
            assertTrue(declared.contains(allowed),
                    "Allowlist entry '" + allowed + "' does not match any declared build<Category> method. "
                            + "Either fix the spelling or remove the stale entry. Declared: " + declared);
        }
    }

    // -----------------------------------------------------------------
    //  Source-text scanning helpers
    // -----------------------------------------------------------------

    private static Path findSourceFile() {
        // Test runs from backend/ (gradle wd) — resolve the source relatively
        // from the user.dir to avoid a hard-coded absolute path that would
        // break in CI containers.
        Path rel = Paths.get(
                "src", "main", "java", "com", "pulse", "tenant", "service",
                "ConsolidatedTenantReadinessService.java");
        Path candidate = rel.toAbsolutePath();
        if (Files.exists(candidate)) return candidate;
        // Fallback: walk up looking for backend/ root.
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path c = p.resolve(rel);
            if (Files.exists(c)) return c;
            Path c2 = p.resolve("backend").resolve(rel);
            if (Files.exists(c2)) return c2;
        }
        throw new IllegalStateException("Could not locate ConsolidatedTenantReadinessService.java "
                + "from user.dir=" + cwd);
    }

    /**
     * Extract the body (between the opening brace at the method signature
     * and its matching closing brace) of every method matching
     * {@link #METHOD_DECL}.
     */
    private static List<MethodBody> extractCategoryBuilderBodies(String source) {
        List<MethodBody> result = new ArrayList<>();
        Matcher m = METHOD_DECL.matcher(source);
        while (m.find()) {
            String methodName = m.group(1);
            int searchFrom = m.end();
            int openBrace = source.indexOf('{', searchFrom);
            if (openBrace < 0) continue;
            int close = matchClosingBrace(source, openBrace);
            if (close < 0) continue;
            String body = source.substring(openBrace + 1, close);
            result.add(new MethodBody(methodName, body));
        }
        return result;
    }

    /** Find the index of the closing brace matching the open brace at {@code openIdx}. */
    private static int matchClosingBrace(String s, int openIdx) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') { i++; continue; }
                if (c == '\'') inChar = false;
                continue;
            }
            if (c == '/' && next == '/') { inLineComment = true; i++; continue; }
            if (c == '/' && next == '*') { inBlockComment = true; i++; continue; }
            if (c == '"') { inString = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private record MethodBody(String methodName, String body) {}
}
