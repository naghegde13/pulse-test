package com.pulse.auth.policy;

import com.pulse.auth.model.UserRole;

import java.util.Locale;
import java.util.Set;

/**
 * Phase 3 — minimum role model for deployment-productization
 * authorization. These are the roles the plan calls out under
 * "Tenant, User, And Role Model" and they exist alongside the legacy
 * {@link UserRole} enum (which is baked into the DB schema, JWT
 * payload, chat-tool contracts, and frontend) for backward
 * compatibility.
 *
 * <p>Use {@link #fromLegacy(UserRole)} when adapting JWT/auth-stub
 * subjects to the policy-service caller context — that mapping is
 * intentionally conservative; legacy {@code DATA_ENGINEER} for
 * example confers {@code PIPELINE_DEVELOPER} only, not deployment
 * rights, even though some legacy permissions strings would imply
 * dev-deploy.
 */
public enum PulseRole {
    /** Read-only tenant member; can view but not mutate. */
    TENANT_USER,
    /** Designs/edits pipelines and runs codegen; can author user-attributed commits. */
    PIPELINE_DEVELOPER,
    /** Triggers package builds, deploys, and rollbacks within allowed envs. */
    DEPLOYMENT_OPERATOR,
    /** Approves pull requests + code-promotion policy decisions. */
    PULL_REQUEST_APPROVER,
    /** Manages tenant config: users, repo settings, target config, approver lists. */
    TENANT_ADMIN,
    /** Cross-tenant platform/operations role. Full access regardless of tenant scope. */
    PLATFORM_ADMIN;

    /**
     * Conservative mapping from the legacy {@link UserRole} enum (CITIZEN,
     * DATA_ENGINEER, DEPLOYER, ADMIN) to the Phase 3 policy roles. The
     * goal is "what is this user's MINIMUM intended capability" — never
     * over-grant.
     */
    public static Set<PulseRole> fromLegacy(UserRole legacy) {
        if (legacy == null) {
            return Set.of();
        }
        return switch (legacy) {
            case CITIZEN -> Set.of(TENANT_USER);
            case DATA_ENGINEER -> Set.of(TENANT_USER, PIPELINE_DEVELOPER);
            case DEPLOYER -> Set.of(TENANT_USER, DEPLOYMENT_OPERATOR, PULL_REQUEST_APPROVER);
            case ADMIN -> Set.of(TENANT_USER, PIPELINE_DEVELOPER,
                    DEPLOYMENT_OPERATOR, PULL_REQUEST_APPROVER, TENANT_ADMIN);
        };
    }

    /**
     * Returns true if the given JWT role string resolves to PLATFORM_ADMIN
     * through either the legacy mapping or a direct PulseRole name.
     *
     * <p>Legacy UserRole.ADMIN maps to TENANT_ADMIN (not PLATFORM_ADMIN),
     * so legacy ADMIN users do NOT get cross-tenant bypass. Only an
     * explicit "PLATFORM_ADMIN" role claim in the JWT grants cross-tenant.
     */
    public static boolean isPlatformAdmin(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        try {
            UserRole legacy = UserRole.valueOf(roleName);
            return fromLegacy(legacy).contains(PLATFORM_ADMIN);
        } catch (IllegalArgumentException e) {
            try {
                return PulseRole.valueOf(roleName.toUpperCase(Locale.ROOT)) == PLATFORM_ADMIN;
            } catch (IllegalArgumentException e2) {
                return false;
            }
        }
    }

    /**
     * Returns true if the given JWT role string resolves to at least
     * TENANT_ADMIN (or PLATFORM_ADMIN) through legacy mapping or direct
     * PulseRole name. Used for management actions that require tenant
     * admin authority but are scoped to a single tenant.
     */
    public static boolean isTenantAdminOrHigher(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        try {
            UserRole legacy = UserRole.valueOf(roleName);
            Set<PulseRole> roles = fromLegacy(legacy);
            return roles.contains(TENANT_ADMIN) || roles.contains(PLATFORM_ADMIN);
        } catch (IllegalArgumentException e) {
            try {
                PulseRole role = PulseRole.valueOf(roleName.toUpperCase(Locale.ROOT));
                return role == TENANT_ADMIN || role == PLATFORM_ADMIN;
            } catch (IllegalArgumentException e2) {
                return false;
            }
        }
    }

    /** Lower-snake-case key used in the {@code authorization-matrix.json} fixture. */
    public String matrixKey() {
        return name().toLowerCase(Locale.ROOT);
    }
}
