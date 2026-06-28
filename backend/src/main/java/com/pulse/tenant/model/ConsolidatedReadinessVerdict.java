package com.pulse.tenant.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PKT-0015: Consolidated tenant readiness verdict aggregating all
 * platform prerequisite categories into a fail-closed gate.
 *
 * <p>Design rules:
 * <ul>
 *   <li>Overall status is fail-closed: ANY required category not ready
 *       blocks overall readiness.</li>
 *   <li>No secret material (PAT, private key, credential JSON) is ever
 *       included in the verdict.</li>
 *   <li>Package/deploy preflight is NOT included — this is Scenario 0
 *       pipeline-development readiness only.</li>
 *   <li>Stable for downstream UI/runtime consumers.</li>
 * </ul>
 *
 * @param tenantId        tenant being assessed
 * @param overallStatus   "ready" or "blocked"
 * @param checkedAt       when the verdict was computed
 * @param categories      map of category name to category detail
 * @param blockerSummary  flattened list of all blockers across categories
 * @param readyCategoryCount  count of ready categories
 * @param totalCategoryCount  total number of categories
 */
public record ConsolidatedReadinessVerdict(
        String tenantId,
        String overallStatus,
        Instant checkedAt,
        Map<String, ReadinessCategory> categories,
        List<ReadinessBlocker> blockerSummary,
        int readyCategoryCount,
        int totalCategoryCount
) {
    public static ConsolidatedReadinessVerdict build(String tenantId,
                                                      List<ReadinessCategory> categoryList) {
        Map<String, ReadinessCategory> categoryMap = categoryList.stream()
                .collect(Collectors.toMap(
                        ReadinessCategory::name,
                        c -> c,
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));

        List<ReadinessBlocker> allBlockers = categoryList.stream()
                .flatMap(c -> c.blockers().stream())
                .toList();

        int readyCount = (int) categoryList.stream()
                .filter(ReadinessCategory::isReady)
                .count();

        String overall = allBlockers.isEmpty() ? "ready" : "blocked";

        return new ConsolidatedReadinessVerdict(
                tenantId, overall, Instant.now(), categoryMap,
                allBlockers, readyCount, categoryList.size());
    }

    public boolean isReady() {
        return "ready".equals(overallStatus);
    }
}
