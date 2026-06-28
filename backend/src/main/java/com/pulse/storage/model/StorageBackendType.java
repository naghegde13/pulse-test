package com.pulse.storage.model;

/**
 * Storage backend choice — locked to two values per #30 spec.
 *
 * <p>DPC: on-prem Cloudera object storage; supports both s3a (Ozone S3 API,
 * MinIO-emulatable locally) and hdfs schemes.
 *
 * <p>GCP: Google Cloud Storage + BigQuery. Gold layer is BigQuery-native
 * (lake_format=bq_native locked); bronze/silver use GCS-resident
 * Delta/Iceberg.
 */
public enum StorageBackendType {
    DPC,
    GCP;

    public String dbValue() { return name(); }

    public static StorageBackendType from(String raw) {
        if (raw == null) return null;
        return StorageBackendType.valueOf(raw.toUpperCase());
    }
}
