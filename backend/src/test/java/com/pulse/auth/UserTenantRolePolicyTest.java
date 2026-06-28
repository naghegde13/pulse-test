package com.pulse.auth;

import com.pulse.auth.model.UserRole;
import com.pulse.auth.policy.ActionContext;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PolicyDecision;
import com.pulse.auth.policy.PulseAction;
import com.pulse.auth.policy.PulseRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 contract — minimum role / tenant model.
 *
 * <p>Pins the locked role list, the legacy → policy role mapping, and
 * tenant-membership enforcement. Every Phase 3 plan-required role must
 * exist; the cross-tenant gate must reject non-platform-admin actors.
 */
class UserTenantRolePolicyTest {

    private final AuthorizationPolicyService policy = new AuthorizationPolicyService();

    @Test
    @DisplayName("All Phase 3 roles exist in the locked enum order")
    void allPhase3RolesExist() {
        Set<String> required = Set.of(
                "TENANT_USER",
                "PIPELINE_DEVELOPER",
                "DEPLOYMENT_OPERATOR",
                "PULL_REQUEST_APPROVER",
                "TENANT_ADMIN",
                "PLATFORM_ADMIN");
        Set<String> actual = java.util.Arrays.stream(PulseRole.values())
                .map(Enum::name).collect(java.util.stream.Collectors.toSet());
        assertEquals(required, actual,
                "PulseRole enum must contain exactly the Phase 3 role set");
    }

    @Test
    @DisplayName("Legacy UserRole maps conservatively to Phase 3 PulseRole")
    void legacyUserRoleMappingIsConservative() {
        // CITIZEN (legacy) → tenant_user only — never confers writes.
        assertEquals(Set.of(PulseRole.TENANT_USER), PulseRole.fromLegacy(UserRole.CITIZEN));
        // DATA_ENGINEER → tenant_user + pipeline_developer (NOT deployment_operator,
        // even though legacy permissions imply pipeline:deploy:dev).
        assertEquals(
                Set.of(PulseRole.TENANT_USER, PulseRole.PIPELINE_DEVELOPER),
                PulseRole.fromLegacy(UserRole.DATA_ENGINEER));
        // DEPLOYER → tenant_user + deployment_operator + pull_request_approver
        // (legacy DEPLOYER had both deploy + approve permissions).
        assertEquals(
                Set.of(PulseRole.TENANT_USER, PulseRole.DEPLOYMENT_OPERATOR, PulseRole.PULL_REQUEST_APPROVER),
                PulseRole.fromLegacy(UserRole.DEPLOYER));
        // ADMIN → all tenant-scoped roles, but NOT platform_admin (cross-tenant
        // is intentionally separate so legacy ADMINs don't get implicit
        // platform-wide access).
        Set<PulseRole> adminRoles = PulseRole.fromLegacy(UserRole.ADMIN);
        assertTrue(adminRoles.contains(PulseRole.TENANT_ADMIN));
        assertFalse(adminRoles.contains(PulseRole.PLATFORM_ADMIN),
                "Legacy ADMIN must not implicitly become PLATFORM_ADMIN");
    }

    @Test
    @DisplayName("Cross-tenant action denied for non-platform-admin even when role allows the action")
    void crossTenantDeniedForNonPlatformAdmin() {
        CallerContext alice = new CallerContext(
                "user-alice", "tenant-A",
                Set.of(PulseRole.TENANT_USER, PulseRole.PIPELINE_DEVELOPER),
                CallerSurface.UI);
        // Alice is a pipeline developer in tenant-A. COMMIT against tenant-A → allow.
        assertTrue(policy.check(alice, PulseAction.COMMIT, ActionContext.forTenant("tenant-A")).allowed());
        // Same action against tenant-B → deny with stable reason.
        PolicyDecision cross = policy.check(alice, PulseAction.COMMIT, ActionContext.forTenant("tenant-B"));
        assertFalse(cross.allowed());
        assertEquals("tenant_membership", cross.denyReason());
    }

    @Test
    @DisplayName("Platform admin bypasses tenant membership but still uses the same policy gate")
    void platformAdminCrossesTenantBoundaries() {
        CallerContext platformOps = new CallerContext(
                "user-ops", "tenant-platform",
                Set.of(PulseRole.PLATFORM_ADMIN),
                CallerSurface.UI);
        // Platform admin can act on tenant-A and tenant-B without being a member.
        assertTrue(policy.check(platformOps, PulseAction.PACKAGE_BUILD,
                ActionContext.forTenant("tenant-A")).allowed());
        assertTrue(policy.check(platformOps, PulseAction.PACKAGE_BUILD,
                ActionContext.forTenant("tenant-B")).allowed());
    }

    @Test
    @DisplayName("Missing actor or unknown action denies with a stable reason")
    void missingActorAndUnknownActionDenied() {
        PolicyDecision noActor = policy.check(null, PulseAction.COMMIT, ActionContext.forTenant("tenant-A"));
        assertFalse(noActor.allowed());
        assertEquals("missing_actor", noActor.denyReason());

        CallerContext blank = new CallerContext("", "tenant-A",
                Set.of(PulseRole.PIPELINE_DEVELOPER), CallerSurface.UI);
        assertEquals("missing_actor",
                policy.check(blank, PulseAction.COMMIT, ActionContext.forTenant("tenant-A")).denyReason());

        CallerContext alice = new CallerContext("user-alice", "tenant-A",
                Set.of(PulseRole.PIPELINE_DEVELOPER), CallerSurface.UI);
        assertEquals("missing_action_context",
                policy.check(alice, PulseAction.COMMIT, null).denyReason());
    }
}
