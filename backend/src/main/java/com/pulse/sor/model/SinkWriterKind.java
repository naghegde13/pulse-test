package com.pulse.sor.model;

/**
 * LCT-045/048: Declared sink-writer family for a DESTINATION connector.
 * <p>
 * Drives sink-blueprint selection and legal write dispositions from connector
 * capability metadata rather than name heuristics. {@code null} for SOURCE-only
 * connector definitions (no sink semantics).
 * <ul>
 *   <li>{@link #LAKE} — object/lake storage (S3/GCS/Iceberg/Delta) → LakeWriter.</li>
 *   <li>{@link #WAREHOUSE} — Snowflake/BigQuery/Redshift → WarehouseWriter.</li>
 *   <li>{@link #RELATIONAL} — JDBC databases (Postgres/MySQL/Oracle/SQL Server) → DatabaseWriter.</li>
 *   <li>{@link #DOCUMENT} — document stores (MongoDB) → DatabaseWriter with a
 *       database/collection target rather than a lake path.</li>
 *   <li>{@link #STREAM} — message buses (Kafka) → StreamWriter.</li>
 * </ul>
 */
public enum SinkWriterKind {
    LAKE,
    WAREHOUSE,
    RELATIONAL,
    DOCUMENT,
    STREAM
}
