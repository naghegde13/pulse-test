package com.pulse.deploy.adapter;

import java.util.List;

/**
 * Phase 5 closeout — thrown by {@code LocalMaterializationAdapter}
 * when one or more generated artifact paths violate the package
 * forbidden-path policy ({@code .git/}, {@code target/},
 * {@code dbt_packages/}, {@code build/}, {@code node_modules/},
 * {@code *.env}, {@code *.secret}, parent-traversal, absolute paths,
 * drive letters).
 *
 * <p>The plan requires the package builder to FAIL on forbidden file
 * matches; previous versions silently excluded them. The adapter's
 * own catch block converts this exception into a stable
 * {@code failure_reason} on the {@code DeploymentRun}.
 */
public class ForbiddenArtifactPathException extends RuntimeException {

    private final List<Violation> violations;

    public ForbiddenArtifactPathException(List<Violation> violations) {
        super("Materialization rejected: " + violations.size()
                + " forbidden artifact path(s): " + summarize(violations));
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }

    /** Stable failure-reason string suitable for {@code DeploymentRun.failure_reason}. */
    public String stableFailureReason() {
        StringBuilder sb = new StringBuilder("forbidden_artifact_paths:");
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) sb.append(",");
            Violation v = violations.get(i);
            sb.append(v.reason()).append("=").append(v.path());
        }
        return sb.toString();
    }

    public record Violation(String path, String reason) {}

    private static String summarize(List<Violation> violations) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) sb.append(", ");
            Violation v = violations.get(i);
            sb.append(v.path()).append(" (").append(v.reason()).append(")");
        }
        sb.append("]");
        return sb.toString();
    }
}
