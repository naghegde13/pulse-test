package com.pulse.codegen.opengine;

import com.pulse.runtime.service.RuntimeAuthorityService;
import org.springframework.stereotype.Service;

/**
 * Resolves the active {@link Mode} for emission via {@link RuntimeAuthorityService}
 * (SPEC #2 §C.2). Every Mode-aware handler asks this resolver, then branches its
 * fragment GCP vs DPC per the §C.2 Mode&times;engine table.
 *
 * <p><b>GCP is the only built path today.</b> The DPC half (Apache Livy submit +
 * Hive+Parquet write + plain-Airflow orchestration) is P2-gated — "remains to be
 * built" (ADR 0006 §Scope, SPEC #2 §C.2 G-13). Handlers ship a stubbed-and-flagged
 * DPC branch (see {@link #assertDpcBuilt}); they must not silently emit a wrong
 * (e.g. Delta / SparkSubmitOperator) artifact for DPC.
 */
@Service
public class ModeResolver {

    private final RuntimeAuthorityService runtimeAuthority;

    public ModeResolver(RuntimeAuthorityService runtimeAuthority) {
        this.runtimeAuthority = runtimeAuthority;
    }

    /** The active Mode (GCP_PULSE / DPC_PULSE). */
    public Mode activeMode() {
        return Mode.from(runtimeAuthority.getActivePersona());
    }

    /**
     * Per-layer file format for the active Mode (SPEC #2 §C.2 table):
     * <ul>
     *   <li>GCP: bronze/silver = {@code iceberg}; gold = {@code bq_native}.</li>
     *   <li>DPC: all layers = {@code parquet} (Hive). [P2-flagged]</li>
     * </ul>
     * Kills the old Mode-blind hardcoded {@code USING DELTA} — Delta is the format
     * for NEITHER Mode (C-2, ADR 0007 corrected).
     */
    public String fileFormatFor(Mode mode, String lakeLayer) {
        if (mode == Mode.DPC_PULSE) {
            return "parquet";
        }
        // GCP: gold is BigQuery-native; bronze/silver are Iceberg-on-GCS / BQ-managed Iceberg.
        return "gold".equalsIgnoreCase(lakeLayer) ? "bq_native" : "iceberg";
    }

    /**
     * The Spark-submit Airflow operator for the active Mode:
     * GCP &rarr; {@code DataprocCreateBatchOperator} (Dataproc Serverless);
     * DPC &rarr; Apache Livy batch submit [P2-flagged]. Plain {@code SparkSubmitOperator}
     * is the target for NEITHER Mode.
     */
    public String sparkSubmitOperatorFor(Mode mode) {
        if (mode == Mode.DPC_PULSE) {
            return "LivyOperator"; // P2: Apache Livy submit — remains to be built.
        }
        return "DataprocCreateBatchOperator";
    }

    /**
     * Guard for any DPC emission that depends on the not-yet-built Livy/Hive path.
     * Throws {@link DpcNotBuiltException} so a DPC build attempt fails loudly rather
     * than silently emitting a wrong artifact. GCP callers never hit this.
     */
    public void assertDpcBuilt(String what) {
        throw new DpcNotBuiltException(
                "DPC_PULSE emission for " + what + " is not built yet (P2: Apache Livy submit "
                + "+ Hive+Parquet write + plain-Airflow orchestration remains to be built, "
                + "ADR 0006 §Scope / SPEC #2 §C.2 G-13)");
    }

    /** Loud-fail for an attempted-but-unbuilt DPC emission path (P2). */
    public static class DpcNotBuiltException extends RuntimeException {
        public DpcNotBuiltException(String message) {
            super(message);
        }
    }
}
