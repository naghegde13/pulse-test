package com.pulse.tenant.model;

import java.time.Instant;

/**
 * PKT-0015: Structured blocker entry for a readiness category.
 * Each blocker carries machine-stable fields for downstream
 * UI/runtime consumers.
 *
 * <p>No secret material is ever included in any field.
 *
 * @param code            machine-stable blocker code (e.g. "MISSING_GCP_CONFIG")
 * @param message         human-readable description
 * @param sourceSurface   the API/service surface that owns the blocker
 * @param evidenceRef     pointer to evidence (API path, config key, etc.)
 * @param staleCheckTimestamp when the check was performed (null if static)
 * @param safeNextAction  recommended remediation action
 * @param operatorRequired true if remediation requires operator/admin action
 */
public record ReadinessBlocker(
        String code,
        String message,
        String sourceSurface,
        String evidenceRef,
        Instant staleCheckTimestamp,
        String safeNextAction,
        boolean operatorRequired
) {
    public static ReadinessBlocker of(String code, String message, String sourceSurface,
                                       String evidenceRef, String safeNextAction,
                                       boolean operatorRequired) {
        return new ReadinessBlocker(code, message, sourceSurface, evidenceRef,
                Instant.now(), safeNextAction, operatorRequired);
    }
}
