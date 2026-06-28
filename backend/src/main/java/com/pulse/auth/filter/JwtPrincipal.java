package com.pulse.auth.filter;

import java.util.List;

/**
 * Authenticated principal derived from a validated JWT token.
 * Stored in the Spring Security context as the authentication principal.
 *
 * @param userId      stable Pulse user id (JWT subject)
 * @param email       user email from JWT claims
 * @param displayName display name from JWT claims
 * @param tenantId    tenant the user belongs to (from JWT claims)
 * @param role        legacy UserRole name (DATA_ENGINEER, ADMIN, etc.)
 * @param permissions flat permission strings (pipeline:read, etc.)
 */
public record JwtPrincipal(
        String userId,
        String email,
        String displayName,
        String tenantId,
        String role,
        List<String> permissions
) {}
