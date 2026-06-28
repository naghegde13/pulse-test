package com.pulse.auth.policy;

/**
 * Identifies whether a deployment-productization request came from the
 * UI (controllers / frontend) or from the agent (chat tool executor).
 *
 * <p>Phase 3 contract: both surfaces MUST go through the same
 * {@link AuthorizationPolicyService} — chat tools are not privileged
 * bypasses. This enum is recorded on every {@link CallerContext} so
 * downstream audit (Phase 4 deployment events) can attribute the
 * decision to the originating surface without changing the policy
 * outcome.
 */
public enum CallerSurface {
    /** REST controller request (UI / API client). */
    UI,
    /** Chat-tool invocation routed through {@code ChatToolExecutor}. */
    AGENT,
    /** Internal scaffold / maintenance call (system commits, bootstrap). */
    SYSTEM
}
