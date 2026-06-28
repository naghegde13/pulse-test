package com.pulse.deploy.projection.model;

import java.util.Locale;

/**
 * ARCH-006 — Closed set of deploy-readiness blocker codes.
 *
 * <p>These codes are surfaced by the runtime projection layer and the
 * enhanced preflight checks. Each code carries a stable machine key
 * (lowercase enum name) and a human-readable message for UI / audit
 * surfaces.
 *
 * <p>Adding a new code is a contract change; downstream UI, audit,
 * evidence, and test fixtures all key off these names.
 */
public enum DeployReadinessBlockerCode {

    // ── Package-level ───────────────────────────────────────────────
    PACKAGE_MISSING("Package not found or has been deleted"),
    PACKAGE_REGENERATION_REQUIRED("Package artifacts are stale and must be regenerated"),

    // ── Runtime authority ───────────────────────────────────────────
    RUNTIME_AUTHORITY_MISMATCH("Package runtime authority does not match the active deployment persona"),

    // ── Contract readiness ──────────────────────────────────────────
    CONTRACTS_MISSING("Required table or landing contracts have not been generated"),
    CONTRACTS_STALE("Table or landing contracts are out of date with the current schema"),

    // ── Projection readiness ────────────────────────────────────────
    PROJECTION_MISSING("Runtime projection has not been created for the target environment"),
    PROJECTION_STALE("Runtime projection is outdated and needs to be refreshed"),
    PROJECTION_DRIFT_DETECTED("Runtime projection has drifted from the current contract state"),

    // ── Entrypoint catalog ──────────────────────────────────────────
    ENTRYPOINT_AMBIGUOUS("Multiple entrypoints resolve for the same execution stage"),

    // ── DDL plan ────────────────────────────────────────────────────
    DDL_PLAN_MISSING("DDL plan has not been generated for the target environment"),
    DDL_PLAN_INVALID("DDL plan contains statements that cannot be executed on the target"),

    // ── Target binding ──────────────────────────────────────────────
    TARGET_BINDING_INCOMPLETE("Deployment target configuration is incomplete"),
    PERSONA_TARGET_INCOMPATIBLE("Active persona does not permit the requested target type"),

    // ── Legacy migration ────────────────────────────────────────────
    LEGACY_PACKAGE_AMBIGUOUS("Package was built under a legacy manifest and cannot be unambiguously projected"),

    // ── Namespace ───────────────────────────────────────────────────
    NAMESPACE_MISSING("Required namespace has not been provisioned on the target"),
    NAMESPACE_COLLISION("Namespace collision detected with an existing deployment on the target"),

    // ── Active run guards ───────────────────────────────────────────
    ACTIVE_AIRFLOW_RUN_EXISTS("An active Airflow DAG run exists for this pipeline on the target"),
    ACTIVE_RUN_STATUS_UNAVAILABLE("Unable to determine whether an active run exists on the target");

    private final String humanMessage;

    DeployReadinessBlockerCode(String humanMessage) {
        this.humanMessage = humanMessage;
    }

    public String humanMessage() {
        return humanMessage;
    }

    /**
     * Stable lowercase key for serialization, matching the convention
     * used by {@link com.pulse.deploy.preflight.PreflightCheckCode#key()}.
     */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
