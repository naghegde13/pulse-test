package com.pulse.deploy.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 7 — outcome of a {@code RuntimeCapabilityMatrix} consult.
 * Held by {@link AdapterPlan#capability()} for evidence and consumed
 * by the {@code RUNTIME_CAPABILITY_OK} preflight blocker.
 *
 * @param approved              {@code true} when the matrix accepts
 *                              the requested format on the requested
 *                              target
 * @param requestedFormat       table format the package wants
 *                              ({@code ICEBERG}, {@code DELTA},
 *                              {@code PARQUET}, {@code UNSPECIFIED})
 * @param resolvedFormat        format the runtime will actually use
 *                              after applying any fallback
 * @param targetType            canonical target type key
 * @param fallbackMode          non-{@code null} when an explicit
 *                              temporary fallback was applied
 *                              (e.g. {@code DPC_PARQUET_LIMITED})
 * @param reasons               human-readable explanations — sorted,
 *                              stable for fixture diffs
 * @param matrixVersion         {@code v1} for now
 */
public record CapabilityCheckResult(
        boolean approved,
        String requestedFormat,
        String resolvedFormat,
        String targetType,
        String fallbackMode,
        List<String> reasons,
        String matrixVersion
) {
    public static final String MATRIX_VERSION = "v1";

    public CapabilityCheckResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public Map<String, Object> toCanonicalJson() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("approved", approved);
        doc.put("requestedFormat", requestedFormat);
        doc.put("resolvedFormat", resolvedFormat);
        doc.put("targetType", targetType);
        doc.put("fallbackMode", fallbackMode);
        doc.put("reasons", reasons);
        doc.put("matrixVersion", matrixVersion);
        return Collections.unmodifiableMap(doc);
    }

    public static CapabilityCheckResult approved(String targetType,
                                                 String requestedFormat,
                                                 List<String> reasons) {
        return new CapabilityCheckResult(true, requestedFormat, requestedFormat, targetType,
                null, reasons, MATRIX_VERSION);
    }

    public static CapabilityCheckResult rejected(String targetType,
                                                 String requestedFormat,
                                                 List<String> reasons) {
        return new CapabilityCheckResult(false, requestedFormat, null, targetType,
                null, reasons, MATRIX_VERSION);
    }

    public static CapabilityCheckResult fallback(String targetType,
                                                 String requestedFormat,
                                                 String resolvedFormat,
                                                 String fallbackMode,
                                                 List<String> reasons) {
        return new CapabilityCheckResult(true, requestedFormat, resolvedFormat, targetType,
                fallbackMode, reasons, MATRIX_VERSION);
    }
}
