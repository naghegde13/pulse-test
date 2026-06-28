package com.pulse.codegen.opengine;

/**
 * The five emission engines (SPEC #2 §C, §C.1). A blueprint's compute artifact is
 * the deterministic composition of its op-list handlers, each targeting one engine.
 *
 * <ul>
 *   <li>{@link #DBT_SQL} — standard dbt SQL model (most transform/modeling ops).</li>
 *   <li>{@link #PYSPARK} — PySpark DataFrame (read-source / add-audit-columns /
 *       write-sink / DQ checkpoint reads).</li>
 *   <li>{@link #GX} — Great Expectations suite + checkpoint (check-data / emit-report).</li>
 *   <li>{@link #DBT_SNAPSHOT} — dbt {@code {% snapshot %}} (track-history-scd2);
 *       take-periodic-snapshot is dbt-incremental, still DBT_SQL kind.</li>
 *   <li>{@link #DAG_ONLY} — an Airflow DAG element only, no compute artifact
 *       (the 5 control ops).</li>
 * </ul>
 */
public enum EmissionEngine {
    DBT_SQL,
    PYSPARK,
    GX,
    DBT_SNAPSHOT,
    DAG_ONLY
}
