package com.pulse.git.policy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 6 — minimum branch allowlist by environment.
 *
 * <p>Phase 6 introduces the concept; later phases (per-tenant config)
 * will replace this static map with a tenant-policy-driven version.
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@code local} / {@code dev} — any branch.</li>
 *   <li>{@code integration} — feature/* + integration + main.</li>
 *   <li>{@code uat} — release/* + uat + main.</li>
 *   <li>{@code prod} — release/* + main.</li>
 * </ul>
 */
@Component
public class BranchAllowlistPolicy {

    private static final Map<String, List<String>> DEFAULT_ALLOWLIST = Map.of(
            "local", List.of("*"),
            "dev", List.of("*"),
            "integration", List.of("main", "integration", "feature/*"),
            "uat", List.of("main", "uat", "release/*"),
            "prod", List.of("main", "release/*")
    );

    public Outcome evaluate(String branch, String canonicalEnv) {
        if (branch == null || branch.isBlank()) {
            return Outcome.deny("Branch is blank.");
        }
        if (canonicalEnv == null || canonicalEnv.isBlank()) {
            return Outcome.deny("Environment is required for branch policy.");
        }
        List<String> allowed = DEFAULT_ALLOWLIST.getOrDefault(canonicalEnv.toLowerCase(Locale.ROOT),
                List.of());
        if (allowed.isEmpty()) {
            return Outcome.deny("No branch allowlist configured for env '" + canonicalEnv + "'.");
        }
        for (String pattern : allowed) {
            if ("*".equals(pattern)) return Outcome.allow();
            if (pattern.endsWith("/*")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (branch.startsWith(prefix + "/")) return Outcome.allow();
            } else if (pattern.equals(branch)) {
                return Outcome.allow();
            }
        }
        return Outcome.deny("Branch '" + branch + "' is not in the allowlist for env '"
                + canonicalEnv + "'. Allowed: " + allowed);
    }

    /** Read-only snapshot for evidence/diagnostics. */
    public Map<String, List<String>> snapshot() {
        return DEFAULT_ALLOWLIST;
    }

    public Set<String> envs() {
        return DEFAULT_ALLOWLIST.keySet();
    }

    public record Outcome(boolean allowed, String reason) {
        public static Outcome allow() { return new Outcome(true, null); }
        public static Outcome deny(String reason) { return new Outcome(false, reason); }
    }
}
