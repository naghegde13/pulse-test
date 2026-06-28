-- V163: Repair remaining Delta lake_format leaks in active builder metadata.
--
-- V160 attempted this repair but matched status = 'ACTIVE'; the live catalog
-- stores active rows as lowercase 'active', so table-producing blueprints such
-- as BronzeToSilverCleaning still exposed lake_format default/options including
-- delta. Runtime authority rejects delta for both DPC and GCP, so the catalog
-- must not seed or offer it.

UPDATE blueprints
SET params_schema = (
    SELECT coalesce(jsonb_agg(
        CASE
            WHEN elem ->> 'name' = 'lake_format'
            THEN (
                elem
                - 'options'
                || jsonb_build_object(
                    'options',
                    (
                        SELECT coalesce(jsonb_agg(option_value), '[]'::jsonb)
                        FROM jsonb_array_elements_text(
                            coalesce(elem -> 'options',
                                     '["delta","iceberg_external","iceberg_bq_managed","bq_native","parquet"]'::jsonb)
                        ) AS options(option_value)
                        WHERE option_value <> 'delta'
                    )
                )
                || CASE
                    WHEN elem ->> 'default' = 'delta'
                    THEN jsonb_build_object('default', 'iceberg_bq_managed')
                    ELSE '{}'::jsonb
                END
                || jsonb_build_object(
                    'description',
                    'Resolved from pipeline storage authority. DPC uses Hive-managed Parquet on S3; GCP bronze/silver uses BigQuery-managed Iceberg; GCP gold uses BigQuery native storage.'
                )
            )
            ELSE elem
        END
        ORDER BY ord
    ), '[]'::jsonb)
    FROM jsonb_array_elements(params_schema) WITH ORDINALITY AS t(elem, ord)
)
WHERE lower(status) = 'active'
  AND params_schema @> '[{"name":"lake_format"}]'::jsonb;

-- Repair existing canonical instance columns that still carry delta. Keep this
-- conservative and authority-based by backend/layer.
UPDATE sub_pipeline_instances
SET lake_format = CASE
    WHEN storage_backend = 'GCP' AND lake_layer = 'gold' THEN 'bq_native'
    WHEN storage_backend = 'GCP' THEN 'iceberg_bq_managed'
    WHEN storage_backend = 'DPC' THEN 'parquet'
    ELSE lake_format
END
WHERE lake_format = 'delta';

-- Mirrored storage keys in params are legacy and are stripped by the service on
-- write. Remove stale mirrored delta values so they cannot override canonical
-- columns when old rows are edited through the /params transition endpoint.
UPDATE sub_pipeline_instances
SET params = params - 'lake_format'
WHERE params ->> 'lake_format' = 'delta';
