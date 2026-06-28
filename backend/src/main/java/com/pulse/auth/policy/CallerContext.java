package com.pulse.auth.policy;

import java.util.Set;

/**
 * Server-resolved identity of a request actor, supplied to
 * {@link AuthorizationPolicyService}.
 *
 * <p>Phase 3 contract: this MUST be derived from the server-side request
 * context (session / JWT / chat-tool tenant scope), never accepted from a
 * request body. Until production auth is fully wired (later phase),
 * test fixtures construct {@code CallerContext} directly to drive
 * deterministic authorization tests.
 *
 * @param userId   stable Pulse user id
 * @param tenantId tenant the actor is currently scoped to
 * @param roles    Phase 3 policy roles the actor holds in {@code tenantId}
 * @param surface  whether the request originated from UI, agent, or system
 */
public record CallerContext(
        String userId,
        String tenantId,
        Set<PulseRole> roles,
        CallerSurface surface
) {
    public CallerContext {
        if (roles == null) {
            roles = Set.of();
        } else {
            roles = Set.copyOf(roles);
        }
    }

    /** True when the actor holds {@link PulseRole#PLATFORM_ADMIN}. */
    public boolean isPlatformAdmin() {
        return roles.contains(PulseRole.PLATFORM_ADMIN);
    }
}
