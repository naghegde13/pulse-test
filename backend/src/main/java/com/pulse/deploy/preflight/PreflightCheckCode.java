package com.pulse.deploy.preflight;

import java.util.Locale;

/**
 * Phase 4 — closed set of stable preflight blocker codes. Mirrors the
 * "Preflight Blocker Matrix" table in
 * {@code docs/architecture/deployment-productization-plan.md}.
 *
 * <p>Adding a new code here is a contract change; downstream UI /
 * audit / fixtures all key off these names.
 */
public enum PreflightCheckCode {
    /** Package row exists and {@code build_status = COMPLETED}. */
    PACKAGE_COMPLETED,
    /** Package metadata carries a complete Phase 2 git provenance block. */
    PACKAGE_PROVENANCE_PRESENT,
    /** Working tree was clean (or dev-policy permitted dirty) at build. */
    PACKAGE_CLEAN_FOR_ENV,
    /** Static deployability assessment reports zero blockers. */
    STATIC_DEPLOYABILITY,
    /** Airflow callback policy stays disabled-by-default and local/dev-only when optional. */
    AIRFLOW_CALLBACK_POLICY_VALID,
    /** Deployment target row resolves for the requested env/type. */
    TARGET_EXISTS,
    /** Resolved target is enabled. */
    TARGET_ENABLED,
    /** Target JSON config validates against its adapter schema. */
    TARGET_SCHEMA_VALID,
    /** Storage backend deploy gate accepts every storage_backend the pipeline uses. */
    STORAGE_BACKEND_VALIDATED,
    /** CredentialReadinessService reports every required dev secret. */
    CREDENTIAL_READINESS,
    /** Package + tracked git tree carry only secret references, never secret values. */
    SECRET_REFERENCES_ONLY,
    /** Required approval policy is satisfied (where applicable). */
    APPROVAL_POLICY,
    /** Branch the package was built from is allowed for the target environment. */
    GIT_BRANCH_ALLOWED,
    /** Required PR state is satisfied for the target environment. */
    PR_POLICY,
    /** Runtime capability profile is recorded on the package. */
    RUNTIME_CAPABILITY,
    /**
     * Phase 7 — RuntimeCapabilityMatrix accepts the requested table
     * format on the resolved target. Distinct from {@code RUNTIME_CAPABILITY},
     * which only verifies the package recorded a profile; this one
     * actually consults the matrix and blocks on a {@code rejected}
     * outcome (and on an unapproved fallback).
     */
    RUNTIME_CAPABILITY_OK,
    /** ARCH-004: Package runtime authority persona matches the active deployment persona. */
    RUNTIME_AUTHORITY_PERSONA_MATCH,
    /** ARCH-004: Target type is legal for the active deployment persona. */
    TARGET_TYPE_PERSONA_LEGAL,
    /** ARCH-006: Active table contracts exist for the pipeline version. */
    TABLE_CONTRACTS_PRESENT,
    /** ARCH-006: Active runtime projection exists and hash has not drifted. */
    RUNTIME_PROJECTION_VALID,
    /** ARCH-007: No active Airflow runs for the logical DAG ID on the target. */
    ACTIVE_RUN_SAFE,
    /** Caller identity, source surface, and correlation id are present + auditable. */
    AGENT_AUDIT_CONTEXT;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
