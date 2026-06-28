package com.pulse.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.auth.policy.ActionContext;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PulseAction;
import com.pulse.auth.policy.PulseRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 contract — every (role, action) cell has a deterministic
 * allow/deny outcome that matches the published
 * {@code authorization-matrix.json} evidence fixture.
 *
 * <p>Also enforces:
 * <ul>
 *   <li>UI and AGENT caller surfaces resolve to the same decision for
 *       the same (role, action) pair.</li>
 *   <li>SYSTEM caller surface is restricted to {@code COMMIT} and only
 *       when the actor holds {@link PulseRole#PLATFORM_ADMIN}.</li>
 * </ul>
 */
class DeploymentActionAuthorizationTest {

    private final AuthorizationPolicyService policy = new AuthorizationPolicyService();

    @Test
    @DisplayName("Every (role, action) cell from the matrix has explicit allow/deny behavior")
    void everyRoleActionCellHasExplicitOutcome() {
        Map<PulseRole, Map<PulseAction, Boolean>> expected = expectedMatrix();
        for (PulseRole role : PulseRole.values()) {
            // PLATFORM_ADMIN always allowed regardless of role-action matrix.
            CallerContext caller = new CallerContext(
                    "user-" + role.name(), "tenant-A",
                    Set.of(role),
                    CallerSurface.UI);
            for (PulseAction action : PulseAction.values()) {
                ActionContext ctx = action == PulseAction.DEPLOY || action == PulseAction.PROMOTE
                        ? ActionContext.forTenantAndEnv("tenant-A", "dev")
                        : ActionContext.forTenant("tenant-A");
                boolean shouldAllow = expected.get(role).get(action);
                boolean actuallyAllowed = policy.check(caller, action, ctx).allowed();
                assertEquals(shouldAllow, actuallyAllowed,
                        "Role " + role + " for action " + action
                                + " expected allow=" + shouldAllow
                                + " but got " + actuallyAllowed);
            }
        }
    }

    @Test
    @DisplayName("UI and AGENT surfaces produce identical decisions for the same role/action")
    void uiAndAgentSurfacesRouteThroughSamePolicy() {
        for (PulseRole role : PulseRole.values()) {
            CallerContext ui = new CallerContext(
                    "user-1", "tenant-A", Set.of(role), CallerSurface.UI);
            CallerContext agent = new CallerContext(
                    "user-1", "tenant-A", Set.of(role), CallerSurface.AGENT);
            for (PulseAction action : PulseAction.values()) {
                ActionContext ctx = action == PulseAction.DEPLOY || action == PulseAction.PROMOTE
                        ? ActionContext.forTenantAndEnv("tenant-A", "dev")
                        : ActionContext.forTenant("tenant-A");
                assertEquals(
                        policy.check(ui, action, ctx).allowed(),
                        policy.check(agent, action, ctx).allowed(),
                        "Surface drift detected for role=" + role + " action=" + action
                                + " — UI and AGENT must hit the same policy gate");
            }
        }
    }

    @Test
    @DisplayName("SYSTEM surface only authorizes COMMIT and only for PLATFORM_ADMIN")
    void systemSurfaceRestrictedToPlatformAdminCommits() {
        CallerContext systemPlatform = new CallerContext(
                "user-system", "tenant-platform",
                Set.of(PulseRole.PLATFORM_ADMIN),
                CallerSurface.SYSTEM);
        // COMMIT allowed for system platform admin (scaffold/maintenance).
        assertTrue(policy.check(systemPlatform, PulseAction.COMMIT,
                ActionContext.forTenant("tenant-A")).allowed());
        // Every other action denied even with PLATFORM_ADMIN.
        for (PulseAction action : PulseAction.values()) {
            if (action == PulseAction.COMMIT) continue;
            assertFalse(policy.check(systemPlatform, action,
                            ActionContext.forTenantAndEnv("tenant-A", "dev")).allowed(),
                    "SYSTEM surface must not authorize " + action + " even for PLATFORM_ADMIN");
        }
        // Without PLATFORM_ADMIN, SYSTEM surface cannot commit.
        CallerContext systemTenantAdmin = new CallerContext(
                "user-system", "tenant-platform",
                Set.of(PulseRole.TENANT_ADMIN),
                CallerSurface.SYSTEM);
        assertFalse(policy.check(systemTenantAdmin, PulseAction.COMMIT,
                ActionContext.forTenant("tenant-A")).allowed());
    }

    @Test
    @DisplayName("Unauthorized callers are denied BEFORE any state mutation")
    void unauthorizedCallersDeniedBeforeMutation() {
        // The policy service is a pure function — denying here proves the
        // gate fires before a controller would touch a repository.
        CallerContext readOnlyUser = new CallerContext(
                "user-readonly", "tenant-A",
                Set.of(PulseRole.TENANT_USER), CallerSurface.UI);
        for (PulseAction action : PulseAction.values()) {
            assertFalse(policy.check(readOnlyUser, action,
                            ActionContext.forTenantAndEnv("tenant-A", "dev")).allowed(),
                    "TENANT_USER must be denied for " + action);
        }
    }

    @Test
    @DisplayName("authorization-matrix.json fixture matches runtime policy snapshot")
    void evidenceFixtureMatchesRuntimePolicy() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream(
                "/auth/authorization-matrix.json")) {
            assertNotNull(in, "authorization-matrix.json missing from test resources");
            JsonNode root = mapper.readTree(in);
            JsonNode cells = root.path("rolePermissions");
            for (PulseAction action : PulseAction.values()) {
                JsonNode actionRow = cells.path(action.matrixKey());
                assertFalse(actionRow.isMissingNode(),
                        "matrix is missing action: " + action.matrixKey());
                for (PulseRole role : PulseRole.values()) {
                    boolean expected = actionRow.path(role.matrixKey()).asBoolean();
                    boolean snapshot = policy.matrixSnapshot()
                            .get(action.matrixKey()).get(role.matrixKey());
                    assertEquals(expected, snapshot,
                            "matrix mismatch for role=" + role.matrixKey()
                                    + " action=" + action.matrixKey());
                }
            }
        }
    }

    /**
     * Hand-authored expected truth table — must match
     * {@link AuthorizationPolicyService}'s ROLE_MATRIX. Two independent
     * sources (policy code + this test) catch silent matrix drift.
     */
    private Map<PulseRole, Map<PulseAction, Boolean>> expectedMatrix() {
        Map<PulseRole, Map<PulseAction, Boolean>> matrix = new EnumMap<>(PulseRole.class);
        for (PulseRole role : PulseRole.values()) {
            EnumMap<PulseAction, Boolean> row = new EnumMap<>(PulseAction.class);
            for (PulseAction action : PulseAction.values()) row.put(action, false);
            matrix.put(role, row);
        }
        // pipeline_developer
        matrix.get(PulseRole.PIPELINE_DEVELOPER).put(PulseAction.COMMIT, true);
        // deployment_operator
        matrix.get(PulseRole.DEPLOYMENT_OPERATOR).put(PulseAction.PACKAGE_BUILD, true);
        matrix.get(PulseRole.DEPLOYMENT_OPERATOR).put(PulseAction.DEPLOY, true);
        matrix.get(PulseRole.DEPLOYMENT_OPERATOR).put(PulseAction.PROMOTE, true);
        // pull_request_approver
        matrix.get(PulseRole.PULL_REQUEST_APPROVER).put(PulseAction.APPROVE, true);
        // tenant_admin (everything)
        for (PulseAction action : PulseAction.values()) {
            matrix.get(PulseRole.TENANT_ADMIN).put(action, true);
        }
        // platform_admin (everything)
        for (PulseAction action : PulseAction.values()) {
            matrix.get(PulseRole.PLATFORM_ADMIN).put(action, true);
        }
        return matrix;
    }
}
