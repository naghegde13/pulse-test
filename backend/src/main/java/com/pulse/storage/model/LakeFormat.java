package com.pulse.storage.model;

/**
 * Physical table format on a lake-resident table. Drives codegen
 * substitution variant selection (Delta vs Iceberg vs BQ-native vs
 * plain Parquet).
 *
 * <p>BQ_NATIVE is the only legal format for {GCP, GOLD} per the locked
 * spec section 5; ICEBERG_BQ_MANAGED is GCP-only (BQ manages the
 * Iceberg metadata under a GCS prefix); PARQUET is DPC-only (plain
 * parquet without ACID metadata, for downstream consumers that don't
 * need it).
 */
public enum LakeFormat {
    DELTA,
    ICEBERG_EXTERNAL,
    ICEBERG_BQ_MANAGED,
    BQ_NATIVE,
    PARQUET;

    public String dbValue() { return name().toLowerCase(); }

    /** True when this format physically stores data IN object storage
     * (so PathConventionService produces an ObjectStorePath). False when
     * data lives in the warehouse's internal store (BQ_NATIVE). */
    public boolean isObjectStoreResident() { return this != BQ_NATIVE; }

    public static LakeFormat from(String raw) {
        if (raw == null) return null;
        return LakeFormat.valueOf(raw.toUpperCase());
    }
}
