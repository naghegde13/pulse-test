package com.pulse.storage.model;

/**
 * Where a lake-resident table physically lives. Two cases:
 *
 * <ul>
 *   <li>{@link ObjectStorePath} — table data is in object storage at a
 *       fully-qualified URI (s3a:// or gs://). Used for Delta,
 *       external Iceberg, BQ-managed Iceberg (where BQ owns the layout
 *       BELOW the prefix but PULSE owns the prefix), and plain Parquet.</li>
 *   <li>{@link CatalogIdentifier} — table data is NOT in object storage;
 *       it lives in the warehouse's internal store and is identified
 *       by {@code project.dataset.table}. Used for BQ-native (the only
 *       legal lake_format for {GCP, GOLD} per the locked spec).</li>
 * </ul>
 *
 * <p>Codegen substitution layer dispatches on the variant: ObjectStorePath
 * fills {@code __BRONZE_PATH__} / {@code __SILVER_PATH__} /
 * {@code __GOLD_PATH__}; CatalogIdentifier fills
 * {@code __GOLD_BQ_TABLE__} as a single string {@code project.dataset.table}.
 */
public sealed interface TableLocation
        permits TableLocation.ObjectStorePath, TableLocation.CatalogIdentifier {

    /** Object-storage URI: {@code s3a://bucket/key/} or {@code gs://bucket/key/}.
     * Always ends with {@code /} (it points at a directory, not a file). */
    record ObjectStorePath(String uri) implements TableLocation {
        public ObjectStorePath {
            if (uri == null || uri.isBlank()) {
                throw new IllegalArgumentException("uri must be non-blank");
            }
            if (!uri.endsWith("/")) {
                throw new IllegalArgumentException(
                        "ObjectStorePath uri must end with '/' (table directory): " + uri);
            }
        }
    }

    /** Catalog identifier: {@code project.dataset.table} for BQ-native. */
    record CatalogIdentifier(String project, String dataset, String table)
            implements TableLocation {
        public CatalogIdentifier {
            if (project == null || project.isBlank()
                    || dataset == null || dataset.isBlank()
                    || table == null || table.isBlank()) {
                throw new IllegalArgumentException(
                        "project, dataset, and table must all be non-blank");
            }
        }

        /** Render as {@code project.dataset.table} — the form codegen
         * substitutes for {@code __*_BQ_TABLE__} placeholders and the
         * form BQ SQL accepts in DDL/DML. */
        public String fullyQualified() {
            return project + "." + dataset + "." + table;
        }
    }
}
