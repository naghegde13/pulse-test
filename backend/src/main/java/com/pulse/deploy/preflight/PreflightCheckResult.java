package com.pulse.deploy.preflight;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 4 — machine-readable preflight result. Stable schema envelope
 * {@code deployment-preflight-result.v1} so UI / evidence / fixtures
 * can read the same payload.
 *
 * @param schemaVersion          {@code deployment-preflight-result.v1}
 * @param packageId              package the preflight ran against
 * @param tenantId               tenant scope
 * @param environment            canonical env scope
 * @param targetId               deployment target id
 * @param status                 {@code PASS} when every check passed
 * @param checks                 ordered per-check status (each check
 *                               appears exactly once, in matrix order)
 * @param blockers               filtered list of failing check codes
 * @param createdAt              deterministic capture instant
 */
public record PreflightCheckResult(
        String schemaVersion,
        String packageId,
        String tenantId,
        String environment,
        String targetId,
        String status,
        List<CheckOutcome> checks,
        List<String> blockers,
        String createdAt
) {
    public static final String SCHEMA_VERSION = "deployment-preflight-result.v1";
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";

    public PreflightCheckResult {
        checks = List.copyOf(checks);
        blockers = List.copyOf(blockers);
    }

    /** Whether the run can proceed past preflight. */
    public boolean passed() {
        return PASS.equals(status);
    }

    /** Per-check outcome row. */
    public record CheckOutcome(
            String code,
            String status,
            String message,
            Map<String, Object> evidence
    ) {
        public CheckOutcome {
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        }

        public static CheckOutcome pass(PreflightCheckCode code) {
            return new CheckOutcome(code.name(), PASS, null, Map.of());
        }

        public static CheckOutcome pass(PreflightCheckCode code, String message) {
            return new CheckOutcome(code.name(), PASS, message, Map.of());
        }

        public static CheckOutcome fail(PreflightCheckCode code, String message) {
            return new CheckOutcome(code.name(), FAIL, message, Map.of());
        }

        public static CheckOutcome fail(PreflightCheckCode code, String message, Map<String, Object> evidence) {
            return new CheckOutcome(code.name(), FAIL, message, evidence);
        }
    }

    /**
     * Canonical-JSON-friendly map representation matching the documented
     * {@code preflight-result.json} envelope. Insertion order is stable
     * for hash determinism.
     */
    public Map<String, Object> toCanonicalJson() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", schemaVersion);
        doc.put("packageId", packageId);
        doc.put("tenantId", tenantId);
        doc.put("environment", environment);
        doc.put("targetId", targetId);
        doc.put("status", status);
        doc.put("blockers", blockers);
        List<Map<String, Object>> rows = new java.util.ArrayList<>(checks.size());
        for (CheckOutcome check : checks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", check.code());
            row.put("status", check.status());
            row.put("message", check.message());
            row.put("evidence", check.evidence());
            rows.add(row);
        }
        doc.put("checks", rows);
        doc.put("createdAt", createdAt);
        return doc;
    }

    public static PreflightCheckResult of(String packageId,
                                          String tenantId,
                                          String environment,
                                          String targetId,
                                          List<CheckOutcome> checks,
                                          Instant capturedAt) {
        List<String> blockers = checks.stream()
                .filter(c -> FAIL.equals(c.status()))
                .map(CheckOutcome::code)
                .toList();
        String status = blockers.isEmpty() ? PASS : FAIL;
        return new PreflightCheckResult(
                SCHEMA_VERSION, packageId, tenantId, environment, targetId,
                status, checks, blockers, capturedAt.toString());
    }
}
