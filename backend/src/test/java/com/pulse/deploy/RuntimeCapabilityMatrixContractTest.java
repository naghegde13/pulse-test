package com.pulse.deploy;

import com.pulse.deploy.adapter.CapabilityCheckResult;
import com.pulse.deploy.capability.RuntimeCapabilityMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 — RuntimeCapabilityMatrix contract.
 *
 * <p>Pins the v1 matrix decisions called out in
 * {@code docs/architecture/deployment-productization-plan.md}
 * §Iceberg Portability:
 *
 * <ul>
 *   <li>LOCAL is runtime-agnostic and approves every format.</li>
 *   <li>GCP approves Iceberg / Parquet / Delta under matrix v1.</li>
 *   <li>DPC approves Parquet unconditionally; Iceberg requires the
 *       platform-confirmed {@code dpcIcebergSupported} hint and
 *       otherwise applies the {@code DPC_PARQUET_LIMITED} fallback;
 *       Delta is rejected outright.</li>
 *   <li>Unknown targetType is rejected.</li>
 * </ul>
 */
class RuntimeCapabilityMatrixContractTest {

    private final RuntimeCapabilityMatrix matrix = new RuntimeCapabilityMatrix();

    @Test
    @DisplayName("LOCAL approves every format (runtime-agnostic)")
    void localApprovesAllFormats() {
        for (String format : new String[]{"ICEBERG", "DELTA", "PARQUET", "UNSPECIFIED"}) {
            CapabilityCheckResult result = matrix.evaluate(
                    new RuntimeCapabilityMatrix.Request(
                            RuntimeCapabilityMatrix.LOCAL, format, false));
            assertTrue(result.approved(),
                    "LOCAL must approve " + format + ", got reasons: " + result.reasons());
            assertNull(result.fallbackMode(), "LOCAL never applies a fallback");
            assertEquals(format, result.requestedFormat());
            assertEquals(format, result.resolvedFormat());
        }
    }

    @Test
    @DisplayName("GCP accepts Iceberg + Delta + Parquet under matrix v1")
    void gcpAcceptsAllThreeFormats() {
        for (String format : new String[]{"ICEBERG", "DELTA", "PARQUET"}) {
            CapabilityCheckResult result = matrix.evaluate(
                    new RuntimeCapabilityMatrix.Request(
                            RuntimeCapabilityMatrix.GCP, format, false));
            assertTrue(result.approved(), "GCP must approve " + format);
            assertNull(result.fallbackMode());
            assertEquals(CapabilityCheckResult.MATRIX_VERSION, result.matrixVersion());
        }
    }

    @Test
    @DisplayName("DPC accepts Parquet unconditionally")
    void dpcAcceptsParquetUnconditionally() {
        CapabilityCheckResult result = matrix.evaluate(
                new RuntimeCapabilityMatrix.Request(
                        RuntimeCapabilityMatrix.DPC, "PARQUET", false));
        assertTrue(result.approved());
        assertNull(result.fallbackMode());
        assertEquals("PARQUET", result.resolvedFormat());
    }

    @Test
    @DisplayName("DPC accepts Iceberg only when dpcIcebergSupported=true")
    void dpcIcebergRequiresHint() {
        CapabilityCheckResult withoutHint = matrix.evaluate(
                new RuntimeCapabilityMatrix.Request(
                        RuntimeCapabilityMatrix.DPC, "ICEBERG", false));
        // Without the hint the matrix applies a fallback to Parquet.
        assertTrue(withoutHint.approved(),
                "fallback variant returns approved=true but with a fallbackMode");
        assertEquals(RuntimeCapabilityMatrix.FALLBACK_DPC_PARQUET_LIMITED,
                withoutHint.fallbackMode());
        assertEquals("ICEBERG", withoutHint.requestedFormat());
        assertEquals("PARQUET", withoutHint.resolvedFormat());

        CapabilityCheckResult withHint = matrix.evaluate(
                new RuntimeCapabilityMatrix.Request(
                        RuntimeCapabilityMatrix.DPC, "ICEBERG", true));
        assertTrue(withHint.approved());
        assertNull(withHint.fallbackMode(),
                "with platform hint DPC must approve Iceberg directly, no fallback");
        assertEquals("ICEBERG", withHint.resolvedFormat());
    }

    @Test
    @DisplayName("DPC rejects Delta outright (matrix v1)")
    void dpcRejectsDelta() {
        CapabilityCheckResult result = matrix.evaluate(
                new RuntimeCapabilityMatrix.Request(
                        RuntimeCapabilityMatrix.DPC, "DELTA", true));
        assertFalse(result.approved(), "DPC must reject Delta in matrix v1");
        assertNotNull(result.reasons());
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("DPC_DELTA_LIMITED")
                        || r.toLowerCase().contains("delta")),
                "rejection reason should mention Delta or the explicit fallback name");
    }

    @Test
    @DisplayName("Unknown targetType rejected with a clear reason")
    void unknownTargetRejected() {
        CapabilityCheckResult result = matrix.evaluate(
                new RuntimeCapabilityMatrix.Request("NEW_RUNTIME_X", "PARQUET", false));
        assertFalse(result.approved());
        assertTrue(result.reasons().stream().anyMatch(r -> r.toLowerCase().contains("unknown targettype")));
    }

    @Test
    @DisplayName("Result envelope is canonical-JSON-friendly with v1 matrix version")
    void envelopeShape() {
        CapabilityCheckResult result = matrix.evaluate(
                new RuntimeCapabilityMatrix.Request(
                        RuntimeCapabilityMatrix.GCP, "ICEBERG", false));
        var doc = result.toCanonicalJson();
        assertEquals(true, doc.get("approved"));
        assertEquals("ICEBERG", doc.get("requestedFormat"));
        assertEquals("ICEBERG", doc.get("resolvedFormat"));
        assertEquals(RuntimeCapabilityMatrix.GCP, doc.get("targetType"));
        assertEquals(CapabilityCheckResult.MATRIX_VERSION, doc.get("matrixVersion"));
    }
}
