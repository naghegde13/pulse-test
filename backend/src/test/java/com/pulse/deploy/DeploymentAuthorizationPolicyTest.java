package com.pulse.deploy;

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
 * Phase 3 contract — deployment-action authorization, including
 * env-scoped {@link PulseAction#DEPLOY} / {@link PulseAction#PROMOTE},
 * normalization of legacy uppercase env inputs, and target-config /
 * secret-metadata gates.
 */
class DeploymentAuthorizationPolicyTest {

    private final AuthorizationPolicyService policy = new AuthorizationPolicyService();

    @Test
    @DisplayName("DEPLOY normalizes legacy env input and gates on caller's env allow-list")
    void deployNormalizesLegacyEnvAndGatesAllowList() {
        CallerContext operator = new CallerContext(
                "user-priya", "tenant-A",
                Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.UI);

        // DEPLOYMENT_OPERATOR Phase 3 default: allowed in every canonical env.
        for (String env : java.util.List.of("local", "dev", "integration", "uat", "prod")) {
            assertTrue(policy.check(operator, PulseAction.DEPLOY,
                            ActionContext.forTenantAndEnv("tenant-A", env)).allowed(),
                    "DEPLOYMENT_OPERATOR must be allowed for canonical env=" + env);
        }
        // Legacy uppercase forms normalize through DeploymentEnvironment.
        assertTrue(policy.check(operator, PulseAction.DEPLOY,
                ActionContext.forTenantAndEnv("tenant-A", "PRODUCTION")).allowed());
        assertTrue(policy.check(operator, PulseAction.DEPLOY,
                ActionContext.forTenantAndEnv("tenant-A", "INT")).allowed());
        // Unknown env → deny with stable code.
        assertEquals("unknown_environment",
                policy.check(operator, PulseAction.DEPLOY,
                        ActionContext.forTenantAndEnv("tenant-A", "STAGING")).denyReason());
        // Missing env → deny with stable code (DEPLOY is env-scoped).
        assertEquals("missing_environment",
                policy.check(operator, PulseAction.DEPLOY,
                        ActionContext.forTenant("tenant-A")).denyReason());
    }

    @Test
    @DisplayName("PROMOTE follows the same env allow-list as DEPLOY")
    void promoteUsesSameEnvAllowListAsDeploy() {
        CallerContext operator = new CallerContext(
                "user-priya", "tenant-A",
                Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.UI);
        assertTrue(policy.check(operator, PulseAction.PROMOTE,
                ActionContext.forTenantAndEnv("tenant-A", "uat")).allowed());
        // Approver alone cannot promote.
        CallerContext approver = new CallerContext(
                "user-approver", "tenant-A",
                Set.of(PulseRole.PULL_REQUEST_APPROVER), CallerSurface.UI);
        PolicyDecision denied = policy.check(approver, PulseAction.PROMOTE,
                ActionContext.forTenantAndEnv("tenant-A", "prod"));
        assertFalse(denied.allowed());
        assertEquals("missing_role", denied.denyReason());
    }

    @Test
    @DisplayName("TARGET_CONFIG and SECRET_METADATA require tenant_admin or platform_admin")
    void targetConfigAndSecretMetadataRequireAdminRoles() {
        CallerContext developer = new CallerContext(
                "user-mike", "tenant-A",
                Set.of(PulseRole.PIPELINE_DEVELOPER, PulseRole.DEPLOYMENT_OPERATOR),
                CallerSurface.UI);
        assertEquals("missing_role",
                policy.check(developer, PulseAction.TARGET_CONFIG,
                        ActionContext.forTenant("tenant-A")).denyReason());
        assertEquals("missing_role",
                policy.check(developer, PulseAction.SECRET_METADATA,
                        ActionContext.forTenant("tenant-A")).denyReason());

        CallerContext tenantAdmin = new CallerContext(
                "user-admin", "tenant-A",
                Set.of(PulseRole.TENANT_ADMIN), CallerSurface.UI);
        assertTrue(policy.check(tenantAdmin, PulseAction.TARGET_CONFIG,
                ActionContext.forTenant("tenant-A")).allowed());
        assertTrue(policy.check(tenantAdmin, PulseAction.SECRET_METADATA,
                ActionContext.forTenant("tenant-A")).allowed());
    }

    @Test
    @DisplayName("APPROVE allowed for pull_request_approver but not for deployment_operator")
    void approveSeparatedFromDeployBySegregationOfDuties() {
        CallerContext approver = new CallerContext(
                "user-approver", "tenant-A",
                Set.of(PulseRole.PULL_REQUEST_APPROVER), CallerSurface.UI);
        assertTrue(policy.check(approver, PulseAction.APPROVE,
                ActionContext.forTenant("tenant-A")).allowed());

        CallerContext operator = new CallerContext(
                "user-priya", "tenant-A",
                Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.UI);
        // Operator can deploy but cannot self-approve their own deployments.
        // Standard SoD check.
        assertEquals("missing_role",
                policy.check(operator, PulseAction.APPROVE,
                        ActionContext.forTenant("tenant-A")).denyReason());
    }

    @Test
    @DisplayName("Agent-surface deploy is gated identically to UI-surface deploy")
    void agentDeployHitsSamePolicyAsUiDeploy() {
        // Phase 3 contract: chat tools are not privileged bypasses.
        CallerContext uiDeveloper = new CallerContext(
                "user-mike", "tenant-A",
                Set.of(PulseRole.PIPELINE_DEVELOPER), CallerSurface.UI);
        CallerContext agentDeveloper = new CallerContext(
                "user-mike", "tenant-A",
                Set.of(PulseRole.PIPELINE_DEVELOPER), CallerSurface.AGENT);
        // Pipeline developer cannot deploy regardless of caller surface.
        assertFalse(policy.check(uiDeveloper, PulseAction.DEPLOY,
                ActionContext.forTenantAndEnv("tenant-A", "dev")).allowed());
        assertFalse(policy.check(agentDeveloper, PulseAction.DEPLOY,
                ActionContext.forTenantAndEnv("tenant-A", "dev")).allowed());

        // And conversely an operator can deploy from either surface.
        CallerContext uiOperator = new CallerContext(
                "user-priya", "tenant-A",
                Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.UI);
        CallerContext agentOperator = new CallerContext(
                "user-priya", "tenant-A",
                Set.of(PulseRole.DEPLOYMENT_OPERATOR), CallerSurface.AGENT);
        assertTrue(policy.check(uiOperator, PulseAction.DEPLOY,
                ActionContext.forTenantAndEnv("tenant-A", "dev")).allowed());
        assertTrue(policy.check(agentOperator, PulseAction.DEPLOY,
                ActionContext.forTenantAndEnv("tenant-A", "dev")).allowed());
    }

    @Test
    @DisplayName("Role with multiple grants takes the most-permissive applicable env")
    void mostPermissiveEnvAcrossRoles() {
        // A user holds both PIPELINE_DEVELOPER (no deploy rights) and
        // DEPLOYMENT_OPERATOR (allowed in all envs). The operator role
        // wins for DEPLOY; the developer role doesn't subtract anything.
        CallerContext combined = new CallerContext(
                "user-multi", "tenant-A",
                Set.of(PulseRole.PIPELINE_DEVELOPER, PulseRole.DEPLOYMENT_OPERATOR),
                CallerSurface.UI);
        assertTrue(policy.check(combined, PulseAction.DEPLOY,
                ActionContext.forTenantAndEnv("tenant-A", "prod")).allowed());
        // And COMMIT (gated by PIPELINE_DEVELOPER) still works.
        assertTrue(policy.check(combined, PulseAction.COMMIT,
                ActionContext.forTenant("tenant-A")).allowed());
    }
}
