package com.pulse.storage.model;

/**
 * Provisioning lifecycle for a storage_backend row. Transitions:
 *
 * <ul>
 *   <li>PENDING (initial) → VALIDATED (after a successful probe)</li>
 *   <li>PENDING / VALIDATED → FAILED (probe error; provisioning_error filled)</li>
 *   <li>any → DISABLED (operator opts the backend out for this tenant)</li>
 * </ul>
 *
 * <p>DeployService gates real-env (integration/uat/prod) deploys on
 * VALIDATED; local-dev seed rows are pre-marked VALIDATED in V96.
 */
public enum ProvisioningStatus {
    PENDING,
    VALIDATED,
    FAILED,
    DISABLED;

    public String dbValue() { return name().toLowerCase(); }

    public static ProvisioningStatus from(String raw) {
        if (raw == null) return null;
        return ProvisioningStatus.valueOf(raw.toUpperCase());
    }
}
