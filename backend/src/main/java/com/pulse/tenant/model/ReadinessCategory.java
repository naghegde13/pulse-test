package com.pulse.tenant.model;

import java.util.List;
import java.util.Map;

/**
 * PKT-0015: A single readiness category in the consolidated verdict.
 *
 * @param name       category key (e.g. "tenantIdentity", "composer")
 * @param status     one of: ready, blocked, not_configured, incomplete
 * @param blockers   list of structured blockers (empty when ready)
 * @param ownership  create-vs-validate ownership evidence (null if not applicable)
 * @param evidence   additional redacted evidence fields
 */
public record ReadinessCategory(
        String name,
        String status,
        List<ReadinessBlocker> blockers,
        ResourceOwnership ownership,
        Map<String, Object> evidence
) {
    /** Convenience for a ready category with evidence. */
    public static ReadinessCategory ready(String name, Map<String, Object> evidence) {
        return new ReadinessCategory(name, "ready", List.of(), null, evidence);
    }

    /** Convenience for a blocked category. */
    public static ReadinessCategory blocked(String name, List<ReadinessBlocker> blockers,
                                             Map<String, Object> evidence) {
        return new ReadinessCategory(name, "blocked", blockers, null, evidence);
    }

    /** Convenience for a not_configured category. */
    public static ReadinessCategory notConfigured(String name, List<ReadinessBlocker> blockers,
                                                    Map<String, Object> evidence) {
        return new ReadinessCategory(name, "not_configured", blockers, null, evidence);
    }

    public boolean isReady() {
        return "ready".equals(status);
    }

    public ReadinessCategory withOwnership(ResourceOwnership own) {
        return new ReadinessCategory(name, status, blockers, own, evidence);
    }

    /**
     * Create-vs-validate ownership evidence for GCP resources.
     *
     * @param resourceKind  e.g. "Composer", "Dataproc", "BigQuery"
     * @param createOwner   who creates the resource ("operator" or "pulse")
     * @param validateOwner who validates readiness ("pulse")
     */
    public record ResourceOwnership(
            String resourceKind,
            String createOwner,
            String validateOwner
    ) {}
}
