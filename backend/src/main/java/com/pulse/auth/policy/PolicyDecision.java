package com.pulse.auth.policy;

/**
 * Outcome of {@link AuthorizationPolicyService#check}. {@code allowed=true}
 * carries no reason; {@code allowed=false} carries a stable, human-readable
 * deny code suitable for error responses, audit logs, and test fixtures.
 *
 * @param allowed       whether the action is authorized
 * @param denyReason    short stable reason ({@code "tenant_membership"},
 *                      {@code "missing_role"}, {@code "env_not_allowed"},
 *                      {@code "unknown_action"}, {@code null} when allowed)
 */
public record PolicyDecision(boolean allowed, String denyReason) {
    public static PolicyDecision allow() {
        return new PolicyDecision(true, null);
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(false, reason);
    }
}
