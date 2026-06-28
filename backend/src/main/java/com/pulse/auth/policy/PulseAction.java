package com.pulse.auth.policy;

import java.util.Locale;

/**
 * Phase 3 — set of state-changing deployment-productization actions
 * the {@link AuthorizationPolicyService} gates at the service layer.
 *
 * <p>Mirrors the "Actions to gate at service layer" list in
 * {@code docs/architecture/deployment-productization-plan.md} Phase 3:
 * commit, package build, deploy, approve, promote, target
 * configuration, secret metadata management.
 */
public enum PulseAction {
    /** User-initiated Git commit through {@code LocalGitService.commitAsUser}. */
    COMMIT,
    /** Package build via {@code DeployController.buildPackage}. */
    PACKAGE_BUILD,
    /** Deploy a built package to a specific tenant/environment/target. */
    DEPLOY,
    /** Approve / reject a deployment via {@code ApprovalRequest}. */
    APPROVE,
    /** Promote an existing package across environments. */
    PROMOTE,
    /** Create / mutate {@code DeploymentTarget} configuration rows. */
    TARGET_CONFIG,
    /** Mutate {@code CredentialProfile} metadata + Secret Manager references. */
    SECRET_METADATA;

    /** Lower-snake-case key used in the {@code authorization-matrix.json} fixture. */
    public String matrixKey() {
        return name().toLowerCase(Locale.ROOT);
    }
}
