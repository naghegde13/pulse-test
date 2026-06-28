package com.pulse.chat.plan;

/**
 * ARCH-018 contract impact hint values. A {@code plan_*} or read tool may
 * surface one of these to advise the LLM about which downstream contracts
 * are likely stale after a write.
 *
 * <p>Derivation rules (used by {@link ContractImpactDerivation}):
 * <ul>
 *   <li>{@link #NONE} - no downstream contract is stale.</li>
 *   <li>{@link #SCHEMA_STALE} - upstream schema changed but no contract row.</li>
 *   <li>{@link #TABLE_CONTRACT_STALE} - a {@code TableContract} for the version is
 *       marked {@code stale} or {@code StorageAuthorityFacade.getContractReadiness}
 *       reports {@code no_active_contracts} / version blockers.</li>
 *   <li>{@link #RUNTIME_PROJECTION_STALE} - the active
 *       {@code RuntimeProjection} drifted relative to its stored hash, or no
 *       projection exists for the target env.</li>
 *   <li>{@link #READINESS_RECHECK_REQUIRED} - deploy preflight needs to be
 *       re-run because at least one of the above is true and the package was
 *       previously {@code passed=true}.</li>
 * </ul>
 */
public enum ContractImpactCode {
    NONE,
    SCHEMA_STALE,
    TABLE_CONTRACT_STALE,
    RUNTIME_PROJECTION_STALE,
    READINESS_RECHECK_REQUIRED;

    public String wire() {
        return name();
    }
}
