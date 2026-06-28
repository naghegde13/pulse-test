package com.pulse.codegen.opengine;

import com.pulse.runtime.model.RuntimePersona;

/**
 * The active runtime Mode for emission (ADR 0001/0006/0007). A thin
 * build-time alias over {@link RuntimePersona} so the codegen op-engine does not
 * import the runtime model everywhere.
 *
 * <ul>
 *   <li>{@link #GCP_PULSE} — Composer + Dataproc Serverless; bronze/silver
 *       Iceberg-on-GCS (interim) &rarr; BQ-managed Iceberg; gold BigQuery-native.
 *       <b>This is the only built path today.</b></li>
 *   <li>{@link #DPC_PULSE} — plain Airflow + Apache Livy; Hive + Parquet all
 *       layers. <b>Stubbed + flagged (P2): the DPC build "remains to be built".</b></li>
 * </ul>
 */
public enum Mode {
    GCP_PULSE,
    DPC_PULSE;

    public static Mode from(RuntimePersona persona) {
        if (persona == null) return GCP_PULSE;
        return persona == RuntimePersona.DPC_PULSE ? DPC_PULSE : GCP_PULSE;
    }

    public boolean isGcp() { return this == GCP_PULSE; }
    public boolean isDpc() { return this == DPC_PULSE; }
}
