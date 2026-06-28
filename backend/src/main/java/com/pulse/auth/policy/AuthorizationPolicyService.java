package com.pulse.auth.policy;

import com.pulse.deploy.environment.DeploymentEnvironment;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3 authorization policy service.
 *
 * <p>Single source of truth for the deployment-productization role/action
 * matrix. All UI controllers and chat tools must route through
 * {@link #check(CallerContext, PulseAction, ActionContext)} before
 * mutating state — there are no "agent bypass" code paths.
 *
 * <p>Policy rules:
 * <ol>
 *   <li>{@link CallerContext#userId()} must be present.</li>
 *   <li>{@link CallerContext#tenantId()} must equal {@link ActionContext#tenantId()}
 *       unless the caller holds {@link PulseRole#PLATFORM_ADMIN}.</li>
 *   <li>The caller must hold one of the roles allowed for the action in
 *       the {@link #ROLE_MATRIX}. {@link PulseRole#PLATFORM_ADMIN} is
 *       allowed for every action.</li>
 *   <li>For env-scoped actions ({@link PulseAction#DEPLOY},
 *       {@link PulseAction#PROMOTE}), {@link ActionContext#environment()}
 *       must be a canonical {@link DeploymentEnvironment} key (legacy
 *       aliases are normalized) AND the caller's role must be allowed
 *       for that environment per {@link #ENV_MATRIX}.</li>
 * </ol>
 *
 * <p>System callers ({@link CallerSurface#SYSTEM}) are restricted to
 * {@link PulseAction#COMMIT} and require {@link PulseRole#PLATFORM_ADMIN}
 * — system commits are reserved for scaffold/maintenance paths and any
 * other action from a SYSTEM surface is denied.
 */
@Service
public class AuthorizationPolicyService {

    /** Static role-allow matrix. Keyed by action, valued as the role set permitted to invoke it. */
    static final Map<PulseAction, Set<PulseRole>> ROLE_MATRIX;
    static {
        Map<PulseAction, Set<PulseRole>> m = new EnumMap<>(PulseAction.class);
        m.put(PulseAction.COMMIT, EnumSet.of(
                PulseRole.PIPELINE_DEVELOPER, PulseRole.TENANT_ADMIN, PulseRole.PLATFORM_ADMIN));
        m.put(PulseAction.PACKAGE_BUILD, EnumSet.of(
                PulseRole.DEPLOYMENT_OPERATOR, PulseRole.TENANT_ADMIN, PulseRole.PLATFORM_ADMIN));
        m.put(PulseAction.DEPLOY, EnumSet.of(
                PulseRole.DEPLOYMENT_OPERATOR, PulseRole.TENANT_ADMIN, PulseRole.PLATFORM_ADMIN));
        m.put(PulseAction.APPROVE, EnumSet.of(
                PulseRole.PULL_REQUEST_APPROVER, PulseRole.TENANT_ADMIN, PulseRole.PLATFORM_ADMIN));
        m.put(PulseAction.PROMOTE, EnumSet.of(
                PulseRole.DEPLOYMENT_OPERATOR, PulseRole.TENANT_ADMIN, PulseRole.PLATFORM_ADMIN));
        m.put(PulseAction.TARGET_CONFIG, EnumSet.of(
                PulseRole.TENANT_ADMIN, PulseRole.PLATFORM_ADMIN));
        m.put(PulseAction.SECRET_METADATA, EnumSet.of(
                PulseRole.TENANT_ADMIN, PulseRole.PLATFORM_ADMIN));
        ROLE_MATRIX = Map.copyOf(m);
    }

    /**
     * Per-role environment allow-list for env-scoped actions. Phase 3
     * default: DEPLOYMENT_OPERATOR is permitted to deploy/promote in
     * every canonical env (Phase 4 will narrow this through tenant
     * policy + segregation-of-duties config).
     *
     * <p>{@code null} value here means "no env restriction" (admin-style
     * roles).
     */
    static final Map<PulseRole, Set<String>> ENV_MATRIX;
    static {
        Map<PulseRole, Set<String>> m = new EnumMap<>(PulseRole.class);
        m.put(PulseRole.DEPLOYMENT_OPERATOR, Set.of("local", "dev", "integration", "uat", "prod"));
        m.put(PulseRole.TENANT_ADMIN,        null);
        m.put(PulseRole.PLATFORM_ADMIN,      null);
        ENV_MATRIX = m;
    }

    /** Actions whose decision depends on {@link ActionContext#environment()}. */
    static final Set<PulseAction> ENV_SCOPED = EnumSet.of(PulseAction.DEPLOY, PulseAction.PROMOTE);

    public PolicyDecision check(CallerContext caller, PulseAction action, ActionContext target) {
        if (caller == null || caller.userId() == null || caller.userId().isBlank()) {
            return PolicyDecision.deny("missing_actor");
        }
        if (action == null) {
            return PolicyDecision.deny("unknown_action");
        }
        if (target == null) {
            return PolicyDecision.deny("missing_action_context");
        }

        // SYSTEM surface restriction — scaffold/maintenance only.
        if (caller.surface() == CallerSurface.SYSTEM) {
            if (action != PulseAction.COMMIT) {
                return PolicyDecision.deny("system_surface_action_not_allowed");
            }
            if (!caller.isPlatformAdmin()) {
                return PolicyDecision.deny("system_commit_requires_platform_admin");
            }
            // System commits are tenant-scope agnostic (scaffold runs
            // before any tenant context is fully wired); skip tenant
            // membership and env checks.
            return PolicyDecision.allow();
        }

        // Tenant membership gate.
        if (!caller.isPlatformAdmin()) {
            if (caller.tenantId() == null || !caller.tenantId().equals(target.tenantId())) {
                return PolicyDecision.deny("tenant_membership");
            }
        }

        // Role gate.
        Set<PulseRole> allowedRoles = ROLE_MATRIX.get(action);
        if (allowedRoles == null) {
            return PolicyDecision.deny("unknown_action");
        }
        boolean roleMatch = caller.roles().stream().anyMatch(allowedRoles::contains);
        if (!roleMatch) {
            return PolicyDecision.deny("missing_role");
        }

        // Env gate (DEPLOY / PROMOTE only).
        if (ENV_SCOPED.contains(action)) {
            String env = target.environment();
            if (env == null || env.isBlank()) {
                return PolicyDecision.deny("missing_environment");
            }
            String canonical;
            try {
                canonical = DeploymentEnvironment.normalize(env);
            } catch (IllegalArgumentException badEnv) {
                return PolicyDecision.deny("unknown_environment");
            }
            // Take the most-permissive env allow-list across the caller's
            // matching roles. Roles not in ENV_MATRIX (e.g. TENANT_USER)
            // do not contribute env permissions.
            boolean envOk = false;
            for (PulseRole role : caller.roles()) {
                if (!allowedRoles.contains(role)) continue;
                if (!ENV_MATRIX.containsKey(role)) {
                    // Role allows the action but isn't in the env matrix
                    // → treat as no env restriction (mirrors null entry).
                    envOk = true;
                    break;
                }
                Set<String> envs = ENV_MATRIX.get(role);
                if (envs == null || envs.contains(canonical)) {
                    envOk = true;
                    break;
                }
            }
            if (!envOk) {
                return PolicyDecision.deny("env_not_allowed");
            }
        }

        return PolicyDecision.allow();
    }

    /**
     * Snapshot of the current policy matrix in the shape consumed by
     * {@code authorization-matrix.json} fixtures and tests. Keys are
     * action names, values are role-name → allow boolean maps.
     */
    public Map<String, Map<String, Boolean>> matrixSnapshot() {
        Map<String, Map<String, Boolean>> snapshot = new LinkedHashMap<>();
        for (PulseAction action : PulseAction.values()) {
            Map<String, Boolean> row = new LinkedHashMap<>();
            Set<PulseRole> allowed = ROLE_MATRIX.get(action);
            for (PulseRole role : PulseRole.values()) {
                row.put(role.matrixKey(), allowed != null && allowed.contains(role));
            }
            snapshot.put(action.matrixKey(), row);
        }
        return snapshot;
    }

    /**
     * Per-role environment allow-list snapshot for env-scoped actions.
     * Roles with no entry are reported with the explicit value
     * {@code "any"} when the role is permitted action-wide, or omitted
     * when the role doesn't grant any env-scoped action.
     */
    public Map<String, Object> envMatrixSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (PulseRole role : PulseRole.values()) {
            if (!ENV_MATRIX.containsKey(role)) continue;
            Set<String> envs = ENV_MATRIX.get(role);
            snapshot.put(role.matrixKey(), envs == null ? "any" : envs);
        }
        return snapshot;
    }
}
