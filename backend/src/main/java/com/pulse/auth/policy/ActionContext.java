package com.pulse.auth.policy;

/**
 * Per-action context the {@link AuthorizationPolicyService} needs to
 * decide allow/deny. Kept tiny on purpose: tenant id is mandatory,
 * environment is optional and only meaningful for env-scoped actions
 * ({@link PulseAction#DEPLOY}, {@link PulseAction#PROMOTE}).
 *
 * @param tenantId    tenant the action targets (must match
 *                    {@link CallerContext#tenantId()} unless caller is
 *                    {@link PulseRole#PLATFORM_ADMIN})
 * @param environment canonical lowercase env key when the action is
 *                    env-scoped (one of {@code local|dev|integration|uat|prod}),
 *                    null otherwise
 */
public record ActionContext(String tenantId, String environment) {
    public static ActionContext forTenant(String tenantId) {
        return new ActionContext(tenantId, null);
    }

    public static ActionContext forTenantAndEnv(String tenantId, String environment) {
        return new ActionContext(tenantId, environment);
    }
}
