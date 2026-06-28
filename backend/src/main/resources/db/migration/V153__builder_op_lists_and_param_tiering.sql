-- V153: Builder op-lists + param tiering (ADR 0012/0013/0020/0023/0024).
--
-- Serializes the INTENT-CANONICAL closed-32-op vocabulary op-lists into each
-- blueprint's `schema_behavior` JSONB and writes `tier`/`derivedFrom` into every
-- `params_schema` element, for the 41 gate blueprints (39 survivors + the 2 new
-- SQL blueprints SqlModel + SourceSQL). Also:
--   * INSERTs the 2 new SQL blueprints (SqlModel, SourceSQL) — ADR 0024.
--   * Deprecates the 4 dead blueprints (V81 idempotent shape).
--   * Corrects SnapshotModel artifact_types ["dbt_snapshot"] -> ["incremental"].
--
-- CONTRACT (SPEC #1 §A.1/§A.3, SPEC #5, SPEC #2 §D):
--   schema_behavior = {"version":1,"ops":[{"op","ui_label","config"}...],
--                      "blueprint_params":[...],"emission":{orchestration,compute}}
--   every ops[].op is one of the 32 closed ops; every config value is a literal
--   or the exact param-ref {"param":"<name>"}; every param-ref resolves to a
--   params_schema descriptor.
--   params_schema element gains tier:"user"|"derived" (NEVER "system-derived")
--   + derivedFrom present IFF tier=="derived".
--
-- RULES: does NOT touch the `category` column (DESTINATION/ORCHESTRATION preserved).
--   AggregateMaterialization is MERGED into GenericAggregate (one row seeded).
--   Derive (V102) is OUT OF SCOPE. All idempotent: UPDATE...WHERE key is a no-op
--   if absent; the 2 INSERTs guard ON CONFLICT (blueprint_key) DO NOTHING.

-- =============================================================================
-- INGESTION (6) — read-source -> add-audit-columns -> write-sink(bronze); pyspark
-- =============================================================================

-- ApiIngestion
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"read-source","ui_label":"Read source","config":{"api_url":{"param":"api_url"},"auth_type":{"param":"auth_type"},"pagination_type":{"param":"pagination_type"},"incremental_field":{"param":"incremental_field"},"response_json_path":{"param":"response_json_path"}}},{"op":"add-audit-columns","ui_label":"Add audit columns","config":{}},{"op":"write-sink","ui_label":"Write to bronze","config":{"target":"bronze","mode":"overwrite"}}],"blueprint_params":["storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"pyspark"}}'::jsonb,
    params_schema = '[
  {"name":"api_url","type":"string","required":true,"description":"Base URL of the API endpoint to ingest.","tier":"user"},
  {"name":"auth_type","type":"enum","required":true,"default":"bearer","description":"Authentication scheme used to call the API.","tier":"user"},
  {"name":"auth_credential_ref","type":"string","required":false,"description":"Reference to the stored credential used for API authentication.","tier":"derived","derivedFrom":"connector"},
  {"name":"pagination_type","type":"enum","required":false,"default":"offset_limit","description":"Pagination strategy for traversing API result pages.","tier":"user"},
  {"name":"rate_limit_rpm","type":"integer","required":false,"default":60,"description":"Maximum requests per minute against the API.","tier":"user"},
  {"name":"incremental_field","type":"string","required":false,"description":"Field used to fetch only new/changed records incrementally.","tier":"user"},
  {"name":"response_json_path","type":"string","required":false,"default":"$.data","description":"JSONPath to the records array within the API response.","tier":"user"},
  {"name":"retry_count","type":"integer","required":false,"default":3,"description":"Number of retry attempts on transient API failures.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"timeout_seconds","type":"integer","required":false,"default":60,"description":"Per-request timeout in seconds.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the lake.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"bronze","description":"Lake layer this data lands in.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format used in the lake.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'ApiIngestion';

-- BulkBackfill
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"read-source","ui_label":"Read source","config":{"source_query":{"param":"source_query"},"date_range_start":{"param":"date_range_start"},"date_range_end":{"param":"date_range_end"}}},{"op":"add-audit-columns","ui_label":"Add audit columns","config":{}},{"op":"write-sink","ui_label":"Write to bronze","config":{"target":"bronze","mode":"overwrite"}}],"blueprint_params":["storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"pyspark"}}'::jsonb,
    params_schema = '[
  {"name":"source_query","type":"string","required":true,"description":"Source query that selects the records to backfill.","tier":"user","accepts_mnemonic":true},
  {"name":"date_range_start","type":"string","required":true,"description":"Start of the backfill date range.","tier":"user","accepts_mnemonic":true},
  {"name":"date_range_end","type":"string","required":true,"description":"End of the backfill date range.","tier":"user","accepts_mnemonic":true},
  {"name":"chunk_size","type":"integer","required":false,"default":100000,"description":"Number of rows processed per chunk.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"chunk_days","type":"integer","required":false,"default":31,"description":"Number of days processed per time chunk.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"parallelism","type":"integer","required":false,"default":4,"description":"Number of chunks processed in parallel.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"holiday_calendar_id","type":"enum","required":false,"default":"US-FED","description":"Holiday calendar used for date arithmetic.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"fiscal_offset_months","type":"integer","required":false,"default":0,"description":"Fiscal-year offset in months for date calculations.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the lake.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"bronze","description":"Lake layer this data lands in.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format used in the lake.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'BulkBackfill';

-- CDCIngestion
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"read-source","ui_label":"Read source","config":{"source_type":{"param":"source_type"},"tables":{"param":"tables"},"primary_key":{"param":"primary_key"},"cdc_mode":{"param":"cdc_mode"},"incremental_column":{"param":"incremental_column"}}},{"op":"add-audit-columns","ui_label":"Add audit columns","config":{}},{"op":"write-sink","ui_label":"Write to bronze","config":{"target":"bronze","mode":"overwrite"}}],"blueprint_params":["storage_backend","lake_layer","lake_format","partition_by"],"emission":{"orchestration":"airflow","compute":"pyspark"}}'::jsonb,
    params_schema = '[
  {"name":"source_type","type":"enum","required":true,"description":"Type of source system the CDC stream originates from.","tier":"derived","derivedFrom":"connector"},
  {"name":"tables","type":"string[]","required":true,"description":"Source tables to capture changes from.","tier":"user"},
  {"name":"primary_key","type":"string[]","required":true,"description":"Primary key column(s) used to match change records.","tier":"user"},
  {"name":"cdc_mode","type":"enum","required":false,"default":"debezium","options":["debezium","incremental_poll"],"description":"Change-data-capture mechanism.","tier":"user"},
  {"name":"incremental_column","type":"string","required":false,"description":"Column used for incremental polling when not using log-based CDC.","tier":"user"},
  {"name":"watermark_column","type":"string","required":false,"description":"Column used to track the high-water mark across runs.","tier":"user"},
  {"name":"initial_snapshot","type":"boolean","required":false,"default":true,"description":"Whether to take a full initial snapshot before streaming changes.","tier":"user"},
  {"name":"delete_handling","type":"enum","required":false,"default":"soft_delete","options":["soft_delete","hard_delete","ignore"],"description":"How deletes from the source are applied to the target.","tier":"user"},
  {"name":"ordering_field","type":"string","required":false,"description":"Field used to order change events for correct application.","tier":"user"},
  {"name":"partition_by","type":"string[]","required":false,"description":"Columns to partition the target table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the lake.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"bronze","description":"Lake layer this data lands in.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format used in the lake.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'CDCIngestion';

-- FileIngestion
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"read-source","ui_label":"Read source","config":{"filename_pattern":{"param":"filename_pattern"},"pattern_kind":{"param":"pattern_kind"},"date_value":{"param":"date_value"},"delimiter":{"param":"delimiter"},"has_header":{"param":"has_header"}}},{"op":"add-audit-columns","ui_label":"Add audit columns","config":{}},{"op":"write-sink","ui_label":"Write to bronze","config":{"target":"bronze","mode":"overwrite"}}],"blueprint_params":["storage_backend","lake_layer","lake_format","partition_by"],"emission":{"orchestration":"airflow","compute":"pyspark"}}'::jsonb,
    params_schema = '[
  {"name":"_inherited_from_connector","type":"string[]","required":false,"description":"Connection settings inherited from the bound connector.","tier":"derived","derivedFrom":"connector"},
  {"name":"filename_pattern","type":"string","required":true,"description":"Pattern matching the source files to ingest.","tier":"user"},
  {"name":"pattern_kind","type":"enum","required":false,"default":"template","description":"How the filename pattern is interpreted.","tier":"user"},
  {"name":"date_format","type":"string","required":false,"default":"yyyyMMdd","description":"Date format used within the filename pattern.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"date_value","type":"string","required":false,"default":"RUN_DATE","description":"Date value substituted into the filename pattern.","tier":"user","accepts_mnemonic":true},
  {"name":"delimiter","type":"string","required":false,"default":",","description":"Field delimiter for delimited files.","tier":"user"},
  {"name":"has_header","type":"boolean","required":false,"default":true,"description":"Whether the source file has a header row.","tier":"user"},
  {"name":"partition_by","type":"string[]","required":false,"description":"Columns to partition the target table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"expected_size_min","type":"integer","required":false,"default":0,"description":"Minimum expected file size used as a sanity check.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"holiday_calendar_id","type":"enum","required":false,"default":"US-FED","description":"Holiday calendar used for date arithmetic.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"fiscal_offset_months","type":"integer","required":false,"default":0,"description":"Fiscal-year offset in months for date calculations.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the lake.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"bronze","description":"Lake layer this data lands in.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format used in the lake.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'FileIngestion';

-- SnapshotIngestion
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"read-source","ui_label":"Read source","config":{"source_table":{"param":"source_table"}}},{"op":"add-audit-columns","ui_label":"Add audit columns","config":{}},{"op":"write-sink","ui_label":"Write to bronze","config":{"target":"bronze","mode":"overwrite"}}],"blueprint_params":["storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"pyspark"}}'::jsonb,
    params_schema = '[
  {"name":"source_table","type":"string","required":false,"description":"Source table to snapshot.","tier":"derived","derivedFrom":"connector"},
  {"name":"snapshot_frequency","type":"enum","required":false,"default":"daily","options":["daily","weekly","monthly"],"description":"How often a snapshot is taken.","tier":"user"},
  {"name":"compare_key","type":"string","required":false,"description":"Key used to compare snapshots for change detection.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the lake.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"bronze","description":"Lake layer this data lands in.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format used in the lake.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'SnapshotIngestion';

-- StreamIngestion
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"read-source","ui_label":"Read source","config":{"stream_type":{"param":"stream_type"},"topic":{"param":"topic"},"consumer_group":{"param":"consumer_group"},"deserialization_format":{"param":"deserialization_format"},"starting_offsets":{"param":"starting_offsets"}}},{"op":"add-audit-columns","ui_label":"Add audit columns","config":{}},{"op":"write-sink","ui_label":"Write to bronze","config":{"target":"bronze","mode":"overwrite"}}],"blueprint_params":["storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"pyspark"}}'::jsonb,
    params_schema = '[
  {"name":"stream_type","type":"enum","required":false,"description":"Type of streaming source.","tier":"derived","derivedFrom":"connector"},
  {"name":"topic","type":"string","required":false,"description":"Stream topic to consume from.","tier":"derived","derivedFrom":"connector"},
  {"name":"consumer_group","type":"string","required":false,"description":"Consumer group used to read the stream.","tier":"derived","derivedFrom":"connector"},
  {"name":"batch_window_seconds","type":"integer","required":false,"default":60,"description":"Micro-batch window length in seconds.","tier":"user"},
  {"name":"deserialization_format","type":"enum","required":false,"default":"json","description":"Format used to deserialize stream messages.","tier":"user"},
  {"name":"starting_offsets","type":"string","required":false,"description":"Offset position to start consuming from.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the lake.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"bronze","description":"Lake layer this data lands in.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format used in the lake.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'StreamIngestion';

-- =============================================================================
-- TRANSFORM (10) — dbt
-- =============================================================================

-- BronzeToSilverCleaning (SUPERSEDES the legacy null_handling enum with the locked decomposed surface)
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"transform-values","ui_label":"Trim & fill nulls","config":{"trim_columns":{"param":"trim_columns"},"fill_null_map":{"param":"fill_null_map"}}},{"op":"rename-columns","ui_label":"Rename columns","config":{"rename_map":{"param":"rename_map"}}},{"op":"change-types","ui_label":"Cast types","config":{"type_coercions":{"param":"type_coercions"}}},{"op":"drop-columns","ui_label":"Drop columns","config":{"drop_columns":{"param":"drop_columns"}}},{"op":"filter-rows","ui_label":"Drop null rows","config":{"drop_when_null":{"param":"drop_null_columns"}}},{"op":"deduplicate","ui_label":"Remove duplicates","config":{"dedup_key":{"param":"dedup_key"}}}],"blueprint_params":["partition_by","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"trim_columns","type":"string[]","required":false,"default":[],"description":"Columns to trim of leading/trailing whitespace. Empty = no trimming.","tier":"user"},
  {"name":"fill_null_map","type":"object","required":false,"default":{},"description":"Map of column -> fill value for null replacement. Empty = no fill.","tier":"user"},
  {"name":"rename_map","type":"object","required":false,"default":{},"description":"Map of old column name -> new column name. Empty = no rename.","tier":"user"},
  {"name":"type_coercions","type":"object","required":false,"default":{},"description":"Map of column -> target type to cast. Empty = no casts.","tier":"user"},
  {"name":"drop_columns","type":"string[]","required":false,"default":[],"description":"Columns to drop. Empty = drop nothing.","tier":"user"},
  {"name":"drop_null_columns","type":"string[]","required":false,"default":[],"description":"Drop rows where any of these columns is null. Empty = drop no rows.","tier":"user"},
  {"name":"dedup_key","type":"string[]","required":false,"default":[],"description":"Columns forming the dedup key. Empty = no deduplication.","tier":"user"},
  {"name":"partition_by","type":"string[]","required":false,"default":[],"description":"Columns to partition the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the output.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"silver","description":"Lake layer for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'BronzeToSilverCleaning';

-- SchemaNormalization
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"rename-columns","ui_label":"Map fields","config":{"rename_map":{"param":"mapping_rules"}}},{"op":"change-types","ui_label":"Conform types","config":{"target_schema":{"param":"target_schema"}}},{"op":"drop-columns","ui_label":"Drop unmapped (strict)","config":{"strict_mode":{"param":"strict_mode"}}}],"blueprint_params":["storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"target_schema","type":"string","required":true,"description":"The target schema to conform the data to.","tier":"user"},
  {"name":"mapping_rules","type":"object","required":false,"default":{},"description":"Map of source field -> target field. Empty = identity mapping.","tier":"user"},
  {"name":"strict_mode","type":"boolean","required":false,"default":false,"description":"When true, drop any columns not present in the target schema.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the output.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"silver","description":"Lake layer for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'SchemaNormalization';

-- DedupeAndMerge
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"deduplicate","ui_label":"Deduplicate","config":{"dedup_key":{"param":"match_keys"},"order_by":{"param":"order_by_columns"}}}],"blueprint_params":["match_strategy","merge_priority","dedup_method","partition_by","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"match_keys","type":"string[]","required":true,"description":"Columns forming the dedup match key.","tier":"user"},
  {"name":"order_by_columns","type":"object[]","required":true,"description":"Ordering used to pick the surviving row within each match group.","tier":"user"},
  {"name":"match_strategy","type":"enum","required":false,"default":"exact","description":"How match keys are compared.","tier":"user"},
  {"name":"merge_priority","type":"string","required":false,"description":"Expression/column determining which record wins on merge.","tier":"user"},
  {"name":"dedup_method","type":"enum","required":false,"default":"row_number","description":"Deduplication implementation method.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"partition_by","type":"string[]","required":false,"default":[],"description":"Columns to partition the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the output.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"silver","description":"Lake layer for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'DedupeAndMerge';

-- PIIMasking
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"mask-columns","ui_label":"Mask columns","config":{"columns":{"param":"columns_to_mask"},"strategy":{"param":"masking_strategy"}}}],"blueprint_params":["storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"columns_to_mask","type":"string[]","required":true,"description":"Columns containing sensitive data to mask.","tier":"user"},
  {"name":"masking_strategy","type":"enum","required":true,"default":"hash","options":["hash","redact","tokenize","encrypt"],"description":"Masking strategy applied to the columns.","tier":"user"},
  {"name":"preserve_format","type":"boolean","required":false,"default":false,"description":"When true, preserve the original value format/shape after masking.","tier":"user"},
  {"name":"hash_algorithm","type":"enum","required":false,"default":"sha256","options":["sha256","sha512","md5"],"description":"Hash algorithm used when masking_strategy is hash.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the output.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"silver","description":"Lake layer for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'PIIMasking';

-- GenericJoin
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"join","ui_label":"Join inputs","config":{"join_type":{"param":"join_type"},"join_keys":{"param":"join_keys"},"select_columns":{"param":"select_columns"}}}],"blueprint_params":["alias_left","alias_right","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"join_type","type":"enum","required":true,"default":"left","options":["inner","left","right","full_outer","cross"],"description":"Type of join between left and right inputs.","tier":"user"},
  {"name":"join_keys","type":"object[]","required":true,"description":"Key-pair mappings joining left and right inputs.","tier":"user"},
  {"name":"select_columns","type":"string[]","required":false,"default":[],"description":"Columns to keep in the joined output. Empty = all columns.","tier":"user"},
  {"name":"alias_left","type":"string","required":false,"description":"Alias for the left input.","tier":"user"},
  {"name":"alias_right","type":"string","required":false,"description":"Alias for the right input.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the output.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"silver","description":"Lake layer for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'GenericJoin';

-- GenericAggregate (the surviving MERGE of AggregateMaterialization)
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"group-and-aggregate","ui_label":"Group & aggregate","config":{"group_by":{"param":"group_by_columns"},"aggregations":{"param":"aggregations"},"having":{"param":"having_clause"}}}],"blueprint_params":["window_functions","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"group_by_columns","type":"string[]","required":false,"default":[],"description":"Columns to group by. Empty = aggregate over the whole input.","tier":"user"},
  {"name":"aggregations","type":"object[]","required":false,"default":[],"description":"List of aggregation specs (function, source column, output alias).","tier":"user"},
  {"name":"having_clause","type":"string","required":false,"description":"HAVING filter applied after aggregation.","tier":"user","accepts_mnemonic":true},
  {"name":"window_functions","type":"object[]","required":false,"default":[],"description":"Optional window-function specs.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the output.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"silver","description":"Lake layer for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'GenericAggregate';

-- GenericFilter
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"filter-rows","ui_label":"Filter rows","config":{"mode":{"param":"filter_mode"},"conditions":{"param":"conditions"},"raw_sql":{"param":"raw_sql"}}}],"blueprint_params":["storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"filter_mode","type":"enum","required":false,"default":"visual","options":["visual","sql"],"description":"Whether to filter via visual conditions or raw SQL.","tier":"user"},
  {"name":"conditions","type":"object[]","required":false,"default":[],"description":"Visual filter conditions (used when filter_mode is visual).","tier":"user"},
  {"name":"raw_sql","type":"string","required":false,"default":"1 = 1","description":"Raw SQL predicate (used when filter_mode is sql).","tier":"user","accepts_mnemonic":true},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the output.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"silver","description":"Lake layer for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'GenericFilter';

-- GenericRouter
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"route-rows","ui_label":"Route rows","config":{"routes":{"param":"routes"},"include_default":{"param":"include_default"}}}],"blueprint_params":["storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"routes","type":"object[]","required":false,"default":[],"description":"Ordered route definitions (name + predicate). Each emits a per-route output port at runtime.","tier":"user"},
  {"name":"include_default","type":"boolean","required":false,"default":true,"description":"When true, unmatched rows flow to the default output port.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the output.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"silver","description":"Lake layer for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'GenericRouter';

-- JsonFlatten
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"flatten-json","ui_label":"Flatten JSON","config":{"source_columns":{"param":"source_columns"},"separator":{"param":"separator"},"max_depth":{"param":"max_depth"},"explode_arrays":{"param":"explode_arrays"}}}],"blueprint_params":["keep_original","prefix","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"source_columns","type":"string[]","required":false,"default":[],"description":"JSON/struct columns to flatten. Empty = auto-detect.","tier":"user"},
  {"name":"separator","type":"string","required":false,"default":"_","description":"Separator joining nested key paths into flattened column names.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"max_depth","type":"integer","required":false,"default":3,"description":"Maximum nesting depth to flatten.","tier":"user"},
  {"name":"explode_arrays","type":"boolean","required":false,"default":false,"description":"When true, explode array elements into rows.","tier":"user"},
  {"name":"keep_original","type":"boolean","required":false,"default":false,"description":"When true, retain the original JSON/struct columns alongside flattened ones.","tier":"user"},
  {"name":"prefix","type":"string","required":false,"default":"","description":"Prefix prepended to flattened column names.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the output.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"silver","description":"Lake layer for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'JsonFlatten';

-- JsonStruct
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"build-struct","ui_label":"Build struct","config":{"mappings":{"param":"mappings"},"output_format":{"param":"output_format"}}},{"op":"drop-columns","ui_label":"Drop source columns","config":{"drop_source":{"param":"drop_source_columns"}}}],"blueprint_params":["passthrough_columns","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"output_format","type":"enum","required":false,"default":"struct","options":["struct","json"],"description":"Whether to emit a nested struct column or a JSON string column.","tier":"user"},
  {"name":"mappings","type":"object[]","required":false,"default":[],"description":"Field mappings defining the struct shape (source column -> struct field).","tier":"user"},
  {"name":"drop_source_columns","type":"boolean","required":false,"default":false,"description":"When true, drop the source columns consumed by the struct.","tier":"user"},
  {"name":"passthrough_columns","type":"string[]","required":false,"default":[],"description":"Columns to carry through unchanged alongside the struct.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Physical storage backend for the output.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"silver","description":"Lake layer for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'JsonStruct';

-- =============================================================================
-- MODELING (7 live; AggregateMaterialization merged into GenericAggregate) — dbt
-- =============================================================================

-- SCD2Dimension
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"track-history-scd2","ui_label":"Track SCD2 history","config":{"business_key":{"param":"business_key"},"tracked_columns":{"param":"tracked_columns"},"effective_date_column":{"param":"effective_date_column"}}}],"blueprint_params":["partition_by","cluster_by","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"business_key","type":"string[]","required":true,"description":"Business/natural key columns that identify a dimension entity across versions.","tier":"user"},
  {"name":"tracked_columns","type":"string[]","required":true,"description":"Columns whose changes trigger a new SCD2 version row.","tier":"user"},
  {"name":"effective_date_column","type":"string","required":false,"default":"effective_from","description":"Source column used to derive SCD2 effective-from timestamps.","tier":"user"},
  {"name":"partition_by","type":"string[]","required":false,"description":"Columns to partition the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"cluster_by","type":"string[]","required":false,"description":"Columns to cluster the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Lake storage backend for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"gold","description":"Lake layer the output dataset is written to.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format of the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'SCD2Dimension';

-- SnapshotModel
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"take-periodic-snapshot","ui_label":"Periodic snapshot","config":{"unique_key":{"param":"unique_key"},"strategy":{"param":"strategy"},"snapshot_frequency":{"param":"snapshot_frequency"}}}],"blueprint_params":["retention_days","partition_by","cluster_by","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"snapshot_frequency","type":"enum","required":false,"default":"daily","options":["daily","weekly","monthly"],"description":"How often a snapshot of the source is captured.","tier":"user"},
  {"name":"retention_days","type":"integer","required":false,"default":365,"description":"Number of days of snapshots to retain.","tier":"user"},
  {"name":"unique_key","type":"string[]","required":false,"description":"Columns that uniquely identify a row within a snapshot.","tier":"user"},
  {"name":"strategy","type":"enum","required":false,"default":"timestamp","description":"Snapshot change-detection strategy.","tier":"user"},
  {"name":"partition_by","type":"string[]","required":false,"description":"Columns to partition the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"cluster_by","type":"string[]","required":false,"description":"Columns to cluster the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Lake storage backend for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"gold","description":"Lake layer the output dataset is written to.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format of the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'SnapshotModel';

-- FactBuild
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"join","ui_label":"Join dimensions","config":{"dimension_keys":{"param":"dimension_keys"}}},{"op":"keep-columns","ui_label":"Keep fact columns","config":{"measures":{"param":"measures"}}}],"blueprint_params":["grain","incremental","time_column","partition_by","cluster_by","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"grain","type":"string","required":true,"description":"The grain (one row per ...) of the fact table.","tier":"user"},
  {"name":"measures","type":"string[]","required":true,"description":"Measure/metric columns retained on the fact table.","tier":"user"},
  {"name":"dimension_keys","type":"string[]","required":true,"description":"Foreign-key columns joining the fact to its dimensions.","tier":"user"},
  {"name":"incremental","type":"boolean","required":false,"default":true,"description":"Whether the fact table is built incrementally.","tier":"user"},
  {"name":"time_column","type":"string","required":false,"description":"Time column used to drive incremental fact builds.","tier":"user"},
  {"name":"partition_by","type":"string[]","required":false,"description":"Columns to partition the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"cluster_by","type":"string[]","required":false,"description":"Columns to cluster the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Lake storage backend for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"gold","description":"Lake layer the output dataset is written to.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format of the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'FactBuild';

-- WideDenormalizedMart
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"join","ui_label":"Join dimensions","config":{"dimension_joins":{"param":"dimension_joins"}}},{"op":"group-and-aggregate","ui_label":"Pre-aggregate","config":{"aggregations":{"param":"pre_aggregations"}}},{"op":"keep-columns","ui_label":"Project mart columns","config":{}}],"blueprint_params":["fact_source","partition_by","cluster_by","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"fact_source","type":"string","required":true,"description":"The fact dataset the wide mart is built from.","tier":"user"},
  {"name":"dimension_joins","type":"object[]","required":true,"description":"Dimension join specs used to denormalize attributes onto the mart.","tier":"user"},
  {"name":"pre_aggregations","type":"object[]","required":false,"description":"Pre-aggregation specs applied before projecting the mart columns.","tier":"user"},
  {"name":"partition_by","type":"string[]","required":false,"description":"Columns to partition the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"cluster_by","type":"string[]","required":false,"description":"Columns to cluster the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Lake storage backend for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"gold","description":"Lake layer the output dataset is written to.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format of the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'WideDenormalizedMart';

-- IncrementalMerge
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"merge-rows","ui_label":"Merge (upsert)","config":{"merge_key":{"param":"merge_key"},"merge_strategy":{"param":"merge_strategy"},"soft_delete":{"param":"soft_delete"}}}],"blueprint_params":["late_data_policy","late_threshold_hours","partition_by","cluster_by","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"merge_key","type":"string[]","required":true,"description":"Columns used to match incoming rows against the target for upsert.","tier":"user"},
  {"name":"merge_strategy","type":"enum","required":false,"default":"upsert","description":"How matched/unmatched rows are reconciled into the target.","tier":"user"},
  {"name":"soft_delete","type":"boolean","required":false,"default":false,"description":"Whether deletions are applied as soft deletes rather than physical removal.","tier":"user"},
  {"name":"late_data_policy","type":"string","required":false,"description":"Policy for handling late-arriving rows during the merge.","tier":"user"},
  {"name":"late_threshold_hours","type":"integer","required":false,"description":"Lateness threshold, in hours, governing the late-data policy.","tier":"user"},
  {"name":"partition_by","type":"string[]","required":false,"description":"Columns to partition the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"cluster_by","type":"string[]","required":false,"description":"Columns to cluster the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Lake storage backend for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"gold","description":"Lake layer the output dataset is written to.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format of the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'IncrementalMerge';

-- ReferenceDataPublish
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"keep-columns","ui_label":"Select reference columns","config":{}},{"op":"deduplicate","ui_label":"Deduplicate","config":{}},{"op":"filter-rows","ui_label":"Filter active rows","config":{}},{"op":"write-sink","ui_label":"Publish reference","config":{"target":"gold","mode":"overwrite"}}],"blueprint_params":["reference_type","publish_frequency","versioned","partition_by","cluster_by","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"reference_type","type":"string","required":true,"description":"The kind of reference/lookup data being published.","tier":"user"},
  {"name":"publish_frequency","type":"enum","required":false,"default":"on_change","description":"How often the reference dataset is republished.","tier":"user"},
  {"name":"versioned","type":"boolean","required":false,"default":true,"description":"Whether published reference data retains versioned history.","tier":"user"},
  {"name":"partition_by","type":"string[]","required":false,"description":"Columns to partition the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"cluster_by","type":"string[]","required":false,"description":"Columns to cluster the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Lake storage backend for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"gold","description":"Lake layer the output dataset is written to.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format of the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'ReferenceDataPublish';

-- FeatureTablePublish
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"join","ui_label":"Join entities","config":{"entity_key":{"param":"entity_key"}}},{"op":"group-and-aggregate","ui_label":"Compute features","config":{"features":{"param":"features"}}},{"op":"keep-columns","ui_label":"Project features","config":{}},{"op":"write-sink","ui_label":"Publish features","config":{"target":"gold","mode":"overwrite"}}],"blueprint_params":["point_in_time_column","output_format","partition_by","cluster_by","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    params_schema = '[
  {"name":"entity_key","type":"string","required":true,"description":"Entity identifier column the feature table is keyed on.","tier":"user"},
  {"name":"features","type":"object[]","required":true,"description":"Feature definitions (name + aggregation/expression) computed per entity.","tier":"user"},
  {"name":"point_in_time_column","type":"string","required":true,"description":"Event-time column enforcing point-in-time correctness of features.","tier":"user"},
  {"name":"output_format","type":"enum","required":false,"default":"delta","description":"Table format of the published feature output.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"partition_by","type":"string[]","required":false,"description":"Columns to partition the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"cluster_by","type":"string[]","required":false,"description":"Columns to cluster the output table by.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Lake storage backend for the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"gold","description":"Lake layer the output dataset is written to.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format of the output dataset.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'FeatureTablePublish';

-- =============================================================================
-- DATA_QUALITY (4) — gx (carry only storage_backend from the storage block)
-- =============================================================================

-- DQValidator
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"check-data","ui_label":"Validate expectations","config":{"expectations":{"param":"expectations"},"on_failure":{"param":"on_failure"},"threshold_percent":{"param":"threshold_percent"},"mostly":{"param":"mostly"}}}],"blueprint_params":["storage_backend"],"emission":{"orchestration":"airflow","compute":"gx"}}'::jsonb,
    params_schema = '[
  {"name":"expectations","type":"object[]","required":true,"description":"The set of data-quality expectations to validate against the input dataset.","tier":"user"},
  {"name":"on_failure","type":"enum","required":false,"default":"quarantine","options":["quarantine","block","warn"],"description":"Action to take when an expectation fails.","tier":"user"},
  {"name":"threshold_percent","type":"number","required":false,"default":99.0,"description":"Minimum percent of rows that must pass for the validation run to succeed.","tier":"user"},
  {"name":"mostly","type":"number","required":false,"default":1.0,"description":"Per-expectation pass fraction (0.0-1.0) required for that expectation to be met.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend, derived from the pipeline storage configuration.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'DQValidator';

-- FreshnessChecks
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"check-data","ui_label":"Check freshness","config":{"timestamp_column":{"param":"timestamp_column"}}},{"op":"emit-report","ui_label":"Emit freshness report","config":{"mode":"append"}}],"blueprint_params":["max_age_minutes","max_age_hours","max_age_business_days","holiday_calendar_id","fiscal_offset_months","storage_backend"],"emission":{"orchestration":"airflow","compute":"gx"}}'::jsonb,
    params_schema = '[
  {"name":"timestamp_column","type":"string","required":true,"description":"Column holding the record timestamp used to measure data freshness.","tier":"user"},
  {"name":"max_age_minutes","type":"integer","required":false,"description":"Maximum allowed data age in minutes before the dataset is considered stale.","tier":"user"},
  {"name":"max_age_hours","type":"integer","required":false,"default":24,"description":"Maximum allowed data age in hours before the dataset is considered stale.","tier":"user"},
  {"name":"max_age_business_days","type":"integer","required":false,"description":"Maximum allowed data age in business days (calendar-aware) before stale.","tier":"user"},
  {"name":"holiday_calendar_id","type":"enum","required":false,"default":"US-FED","description":"Holiday calendar used for business-day freshness calculations.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"fiscal_offset_months","type":"integer","required":false,"default":0,"description":"Fiscal-year offset in months applied to calendar-aware freshness calculations.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend, derived from the pipeline storage configuration.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'FreshnessChecks';

-- SchemaDriftDetection
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"check-data","ui_label":"Detect drift","config":{"expected_columns":{"param":"expected_columns"},"strict_order":{"param":"strict_order"},"allow_extra_columns":{"param":"allow_extra_columns"}}},{"op":"emit-report","ui_label":"Emit drift report","config":{"mode":"append"}}],"blueprint_params":["drift_policy","storage_backend"],"emission":{"orchestration":"airflow","compute":"gx"}}'::jsonb,
    params_schema = '[
  {"name":"expected_columns","type":"object[]","required":true,"description":"The expected column set (name/type) the incoming data is checked against for drift.","tier":"user"},
  {"name":"strict_order","type":"boolean","required":false,"default":false,"description":"Whether column ordering must match the expected schema exactly.","tier":"user"},
  {"name":"allow_extra_columns","type":"boolean","required":false,"default":true,"description":"Whether columns present in the data but not in the expected schema are permitted.","tier":"user"},
  {"name":"drift_policy","type":"enum","required":false,"default":"warn","options":["warn","block"],"description":"Action to take when schema drift is detected.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend, derived from the pipeline storage configuration.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'SchemaDriftDetection';

-- AnomalyDetection
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"check-data","ui_label":"Detect anomalies","config":{"monitored_columns":{"param":"monitored_columns"},"detection_method":{"param":"detection_method"},"sensitivity_percent":{"param":"sensitivity_percent"}}},{"op":"emit-report","ui_label":"Emit anomaly report","config":{"mode":"append"}}],"blueprint_params":["lookback_runs","volume_monitoring","storage_backend"],"emission":{"orchestration":"airflow","compute":"gx"}}'::jsonb,
    params_schema = '[
  {"name":"monitored_columns","type":"string[]","required":true,"description":"Columns to monitor for statistical anomalies.","tier":"user"},
  {"name":"sensitivity_percent","type":"number","required":false,"default":2.0,"description":"Sensitivity threshold (percent) controlling how aggressively anomalies are flagged.","tier":"user"},
  {"name":"detection_method","type":"enum","required":false,"default":"z_score","description":"Statistical method used to detect anomalies.","tier":"user"},
  {"name":"lookback_runs","type":"integer","required":false,"default":10,"description":"Number of prior runs used to establish the baseline for anomaly detection.","tier":"user"},
  {"name":"volume_monitoring","type":"boolean","required":false,"default":false,"description":"Whether to monitor row-volume anomalies in addition to column-value anomalies.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend, derived from the pipeline storage configuration.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'AnomalyDetection';

-- =============================================================================
-- DESTINATION / SINK (4) — write-sink(target, mode); pyspark. category UNCHANGED.
-- =============================================================================

-- WarehouseWriter
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"write-sink","ui_label":"Write to target","config":{"target_id":{"param":"target_id"},"write_mode":{"param":"write_mode"},"target_table":{"param":"target_table"}}}],"blueprint_params":["connector_instance_id","connector_name","target_credential_ref","merge_keys","batch_size","clustering_columns","storage_backend"],"emission":{"orchestration":"airflow","compute":"pyspark"}}'::jsonb,
    params_schema = '[
  {"name":"target_id","type":"string","required":true,"description":"Identifier of the warehouse target to write to.","tier":"user"},
  {"name":"connector_instance_id","type":"string","required":true,"description":"Warehouse connector instance providing the connection.","tier":"derived","derivedFrom":"connector"},
  {"name":"connector_name","type":"string","required":false,"description":"Human-readable name of the warehouse connector.","tier":"derived","derivedFrom":"connector"},
  {"name":"target_credential_ref","type":"string","required":false,"description":"Credential profile reference for the warehouse target.","tier":"derived","derivedFrom":"connector"},
  {"name":"target_table","type":"string","required":true,"description":"Fully-qualified destination table in the warehouse.","tier":"user"},
  {"name":"write_mode","type":"enum","required":false,"default":"overwrite_partition","description":"How rows are written to the target table.","tier":"user"},
  {"name":"merge_keys","type":"string[]","required":false,"description":"Key columns used to match rows when merging.","tier":"user"},
  {"name":"batch_size","type":"integer","required":false,"default":10000,"description":"Number of rows written per batch.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"clustering_columns","type":"string[]","required":false,"description":"Columns used to cluster the destination table.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend for the pipeline.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'WarehouseWriter';

-- LakeWriter
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"write-sink","ui_label":"Write to target","config":{"target_id":{"param":"target_id"},"write_mode":{"param":"write_mode"},"output_path":{"param":"output_path"}}}],"blueprint_params":["connector_instance_id","connector_name","target_credential_ref","lake_format","merge_keys","optimize_after_write","z_order_columns","storage_backend"],"emission":{"orchestration":"airflow","compute":"pyspark"}}'::jsonb,
    params_schema = '[
  {"name":"target_id","type":"string","required":true,"description":"Identifier of the lake target to write to.","tier":"user"},
  {"name":"connector_instance_id","type":"string","required":true,"description":"Lake connector instance providing the connection.","tier":"derived","derivedFrom":"connector"},
  {"name":"connector_name","type":"string","required":false,"description":"Human-readable name of the lake connector.","tier":"derived","derivedFrom":"connector"},
  {"name":"target_credential_ref","type":"string","required":false,"description":"Credential profile reference for the lake target.","tier":"derived","derivedFrom":"connector"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the lake destination.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"output_path","type":"string","required":true,"description":"Destination path in the lake.","tier":"user"},
  {"name":"write_mode","type":"enum","required":false,"default":"merge_on_pk","description":"How rows are written to the lake table.","tier":"user"},
  {"name":"merge_keys","type":"string[]","required":false,"description":"Key columns used to match rows when merging.","tier":"user"},
  {"name":"optimize_after_write","type":"boolean","required":false,"default":false,"description":"Run table optimization after writing.","tier":"user"},
  {"name":"z_order_columns","type":"string[]","required":false,"description":"Columns to Z-order the table by during optimization.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend for the pipeline.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'LakeWriter';

-- DatabaseWriter
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"write-sink","ui_label":"Write to target","config":{"target_id":{"param":"target_id"},"write_mode":{"param":"write_mode"},"target_table":{"param":"target_table"}}}],"blueprint_params":["connector_instance_id","connector_name","target_credential_ref","upsert_keys","batch_size","connection_pool_size","partition_by","storage_backend"],"emission":{"orchestration":"airflow","compute":"pyspark"}}'::jsonb,
    params_schema = '[
  {"name":"target_id","type":"string","required":true,"description":"Identifier of the database target to write to.","tier":"user"},
  {"name":"connector_instance_id","type":"string","required":true,"description":"Database connector instance providing the connection.","tier":"derived","derivedFrom":"connector"},
  {"name":"connector_name","type":"string","required":false,"description":"Human-readable name of the database connector.","tier":"derived","derivedFrom":"connector"},
  {"name":"target_credential_ref","type":"string","required":false,"description":"Credential profile reference for the database target.","tier":"derived","derivedFrom":"connector"},
  {"name":"target_table","type":"string","required":true,"description":"Destination table in the database.","tier":"user"},
  {"name":"write_mode","type":"enum","required":false,"default":"append","description":"How rows are written to the target table.","tier":"user"},
  {"name":"upsert_keys","type":"string[]","required":false,"description":"Key columns used to match rows on upsert.","tier":"user"},
  {"name":"batch_size","type":"integer","required":false,"default":5000,"description":"Number of rows written per batch.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"connection_pool_size","type":"integer","required":false,"default":5,"description":"Number of connections in the write pool.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"partition_by","type":"string[]","required":false,"description":"Destination-table partitioning columns.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend for the pipeline.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'DatabaseWriter';

-- StreamWriter
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"write-sink","ui_label":"Write to target","config":{"target_id":{"param":"target_id"},"write_mode":{"param":"publish_mode"},"topic":{"param":"topic"}}}],"blueprint_params":["connector_instance_id","connector_name","target_credential_ref","schema_strategy","key_columns","delivery_guarantee","checkpoint_location","storage_backend"],"emission":{"orchestration":"airflow","compute":"pyspark"}}'::jsonb,
    params_schema = '[
  {"name":"target_id","type":"string","required":true,"description":"Identifier of the stream target to publish to.","tier":"user"},
  {"name":"connector_instance_id","type":"string","required":true,"description":"Stream connector instance providing the connection.","tier":"derived","derivedFrom":"connector"},
  {"name":"connector_name","type":"string","required":false,"description":"Human-readable name of the stream connector.","tier":"derived","derivedFrom":"connector"},
  {"name":"target_credential_ref","type":"string","required":false,"description":"Credential profile reference for the stream target.","tier":"derived","derivedFrom":"connector"},
  {"name":"topic","type":"string","required":true,"description":"Destination topic to publish messages to.","tier":"user"},
  {"name":"publish_mode","type":"enum","required":false,"default":"batch_publish","options":["batch_publish","streaming_publish"],"description":"How messages are published to the topic.","tier":"user"},
  {"name":"schema_strategy","type":"enum","required":false,"default":"json_envelope","options":["json_envelope","avro_schema_registry"],"description":"Serialization/schema strategy for published messages.","tier":"user"},
  {"name":"key_columns","type":"string[]","required":false,"description":"Columns used to derive the message key.","tier":"user"},
  {"name":"delivery_guarantee","type":"enum","required":false,"default":"at_least_once","options":["at_least_once","exactly_once"],"description":"Delivery semantics for published messages.","tier":"user"},
  {"name":"checkpoint_location","type":"string","required":false,"description":"Checkpoint location for streaming writes (unique per pipeline).","tier":"derived","derivedFrom":"pipeline.id"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend for the pipeline.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'StreamWriter';

-- =============================================================================
-- ORCHESTRATION / CONTROL (7) — compute:null. category UNCHANGED (ORCHESTRATION).
-- =============================================================================

-- FileArrivalSensor (absorbs the deprecating ObjectStoreKeySensor)
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"sense","ui_label":"Sense file arrival","config":{"storage_kind":{"param":"storage_kind"},"bucket":{"param":"bucket"},"path_prefix":{"param":"path_prefix"},"filename_pattern":{"param":"filename_pattern"},"date_value":{"param":"date_value"}}}],"blueprint_params":["pattern_kind","date_format","expected_size_min","expected_max_age_hours","multiple_files_mode","soft_fail","poke_interval_seconds","timeout_seconds","mode","holiday_calendar_id","fiscal_offset_months","storage_backend"],"emission":{"orchestration":"airflow","compute":null}}'::jsonb,
    params_schema = '[
  {"name":"storage_kind","type":"enum","required":true,"description":"Kind of storage being sensed for file arrival.","tier":"user"},
  {"name":"bucket","type":"string","required":true,"description":"Bucket / container to watch for the arriving file.","tier":"user"},
  {"name":"path_prefix","type":"string","required":true,"description":"Path prefix under the bucket where the file is expected.","tier":"user"},
  {"name":"filename_pattern","type":"string","required":true,"description":"Filename pattern the arriving file must match.","tier":"user"},
  {"name":"pattern_kind","type":"enum","required":false,"default":"template","description":"How the filename pattern is interpreted.","tier":"user"},
  {"name":"date_format","type":"string","required":false,"default":"yyyyMMdd","description":"Date format used when expanding date tokens in the filename pattern.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"date_value","type":"string","required":false,"default":"RUN_DATE","description":"Date value substituted into the filename pattern.","tier":"user","accepts_mnemonic":true},
  {"name":"expected_size_min","type":"integer","required":false,"default":0,"description":"Minimum acceptable file size in bytes.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"expected_max_age_hours","type":"integer","required":false,"description":"Maximum acceptable file age in hours before it is considered stale.","tier":"user"},
  {"name":"multiple_files_mode","type":"boolean","required":false,"default":false,"description":"Whether to sense for multiple matching files rather than a single file.","tier":"user"},
  {"name":"soft_fail","type":"boolean","required":false,"default":false,"description":"If true, mark the sensor as skipped rather than failed on timeout.","tier":"user"},
  {"name":"poke_interval_seconds","type":"integer","required":false,"default":300,"description":"Interval in seconds between sensor pokes.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"timeout_seconds","type":"integer","required":false,"default":14400,"description":"Sensor timeout in seconds.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"mode","type":"enum","required":false,"default":"reschedule","description":"Sensor execution mode.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"holiday_calendar_id","type":"enum","required":false,"default":"US-FED","description":"Holiday calendar used for business-date resolution.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"fiscal_offset_months","type":"integer","required":false,"default":0,"description":"Fiscal-period month offset applied to date resolution.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend, derived from pipeline storage configuration.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'FileArrivalSensor';

-- DatabaseReadinessSensor
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"sense","ui_label":"Sense DB readiness","config":{"sql":{"param":"sql"},"date_value":{"param":"date_value"}}}],"blueprint_params":["connection_id","date_format","expected_count_min","expected_count_max","poke_interval_seconds","timeout_seconds","mode","holiday_calendar_id","fiscal_offset_months","storage_backend"],"emission":{"orchestration":"airflow","compute":null}}'::jsonb,
    params_schema = '[
  {"name":"sql","type":"string","required":true,"description":"SQL probe query whose result determines DB readiness.","tier":"user","accepts_mnemonic":true},
  {"name":"connection_id","type":"string","required":false,"default":"pulse_sql_default","description":"Connection used to run the readiness probe.","tier":"derived","derivedFrom":"connector"},
  {"name":"date_format","type":"string","required":false,"default":"yyyy-MM-dd","description":"Date format used when expanding date tokens in the probe SQL.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"date_value","type":"string","required":false,"default":"RUN_DATE","description":"Date value substituted into the probe SQL.","tier":"user","accepts_mnemonic":true},
  {"name":"expected_count_min","type":"integer","required":false,"default":1,"description":"Minimum row count the probe must return to be considered ready.","tier":"user"},
  {"name":"expected_count_max","type":"integer","required":false,"description":"Maximum row count the probe may return to be considered ready.","tier":"user"},
  {"name":"poke_interval_seconds","type":"integer","required":false,"default":300,"description":"Interval in seconds between sensor pokes.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"timeout_seconds","type":"integer","required":false,"default":14400,"description":"Sensor timeout in seconds.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"mode","type":"enum","required":false,"default":"reschedule","description":"Sensor execution mode.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"holiday_calendar_id","type":"enum","required":false,"default":"US-FED","description":"Holiday calendar used for business-date resolution.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"fiscal_offset_months","type":"integer","required":false,"default":0,"description":"Fiscal-period month offset applied to date resolution.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend, derived from pipeline storage configuration.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'DatabaseReadinessSensor';

-- ExternalEventSensor
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"sense","ui_label":"Sense external event","config":{"event_url":{"param":"event_url"}}}],"blueprint_params":["success_status_code","poke_interval_seconds","timeout_seconds","storage_backend"],"emission":{"orchestration":"airflow","compute":null}}'::jsonb,
    params_schema = '[
  {"name":"event_url","type":"string","required":true,"description":"URL polled to detect the external event.","tier":"user"},
  {"name":"success_status_code","type":"integer","required":false,"default":200,"description":"HTTP status code that signals the event has occurred.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"poke_interval_seconds","type":"integer","required":false,"default":300,"description":"Interval in seconds between sensor pokes.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"timeout_seconds","type":"integer","required":false,"default":3600,"description":"Sensor timeout in seconds.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend, derived from pipeline storage configuration.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'ExternalEventSensor';

-- ScheduleAndTriggers (absorbs the deprecating DatasetDependencySensor triggers)
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"schedule-and-triggers","ui_label":"Schedule & triggers","config":{"schedule_type":{"param":"schedule_type"},"cron_expression":{"param":"cron_expression"},"trigger_dataset":{"param":"trigger_dataset"}}}],"blueprint_params":["timezone","max_active_runs","catchup_enabled","depends_on_past","retry_count","storage_backend"],"emission":{"orchestration":"airflow","compute":null}}'::jsonb,
    params_schema = '[
  {"name":"schedule_type","type":"enum","required":true,"description":"How the pipeline is scheduled (e.g. cron vs dataset-triggered).","tier":"user"},
  {"name":"cron_expression","type":"string","required":false,"description":"Cron expression used when scheduling by time.","tier":"user"},
  {"name":"trigger_dataset","type":"string","required":false,"description":"Dataset whose update triggers the pipeline.","tier":"user"},
  {"name":"timezone","type":"string","required":false,"default":"UTC","description":"Timezone in which the schedule is evaluated.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"max_active_runs","type":"integer","required":false,"default":1,"description":"Maximum number of concurrent active DAG runs.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"catchup_enabled","type":"boolean","required":false,"default":false,"description":"Whether Airflow backfills missed schedule intervals.","tier":"user"},
  {"name":"depends_on_past","type":"boolean","required":false,"default":false,"description":"Whether a run depends on the success of the prior interval.","tier":"user"},
  {"name":"retry_count","type":"integer","required":false,"default":3,"description":"Number of task retries on failure.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend, derived from pipeline storage configuration.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'ScheduleAndTriggers';

-- RollbackOnFailure
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"rollback","ui_label":"Rollback on failure","config":{"rollback_trigger":{"param":"rollback_trigger"},"keep_failed_artifacts":{"param":"keep_failed_artifacts"}}}],"blueprint_params":["storage_backend"],"emission":{"orchestration":"airflow","compute":null}}'::jsonb,
    params_schema = '[
  {"name":"rollback_trigger","type":"enum","required":false,"default":"deploy_failure","description":"Condition under which rollback is initiated.","tier":"user"},
  {"name":"keep_failed_artifacts","type":"boolean","required":false,"default":true,"description":"Whether to retain artifacts from the failed run for inspection.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend, derived from pipeline storage configuration.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'RollbackOnFailure';

-- AdvanceTimeDimension (WHOLESALE rewrite to the V132 + ADR-0023 intent surface:
-- 2 user [target_scope, advance_to] + 17 derived; advance_to consolidates the V132
-- advance_mode + requested_asof_expr).
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"advance-time","ui_label":"Advance time dimension","config":{"target_scope":{"param":"target_scope"},"advance_to":{"param":"advance_to"}}}],"blueprint_params":["state_binding_ref","variable_key","calendar_binding_ref","calendar_bundle_uri","calendar_bundle_hash","calendar_id","grain","timezone","replay_policy","initial_value","initialization_policy","concurrency_policy","evidence_prefix","evidence_required","notes_template","advanced_by","source"],"emission":{"orchestration":"airflow","compute":null}}'::jsonb,
    params_schema = '[
  {"name":"target_scope","type":"enum","required":true,"options":["dataset","domain"],"default":"dataset","description":"Whether the time dimension is advanced for a single dataset or an entire domain.","tier":"user"},
  {"name":"advance_to","type":"string","required":false,"description":"Target as-of value or mnemonic to advance to; blank = next interval per grain.","tier":"user","accepts_mnemonic":true},
  {"name":"state_binding_ref","type":"string","required":true,"description":"Reference to the state binding holding the advanceable time variable.","tier":"derived","derivedFrom":"target_dataset.state_binding"},
  {"name":"variable_key","type":"string","required":true,"description":"Key of the time variable within the state binding.","tier":"derived","derivedFrom":"target_dataset.state_binding"},
  {"name":"calendar_binding_ref","type":"string","required":true,"description":"Reference to the calendar binding governing valid advance steps.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"calendar_bundle_uri","type":"string","required":true,"description":"URI of the resolved calendar bundle.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"calendar_bundle_hash","type":"string","required":false,"description":"Content hash of the resolved calendar bundle for evidence.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"calendar_id","type":"string","required":false,"default":"US-FED","description":"Identifier of the calendar used for advancement.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"grain","type":"string","required":false,"default":"DAILY_BUSINESS_DAY","description":"Temporal grain by which time is advanced.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"timezone","type":"string","required":false,"default":"America/New_York","description":"Timezone in which the advancement is computed.","tier":"derived","derivedFrom":"domain.calendar"},
  {"name":"replay_policy","type":"enum","required":false,"options":["reject_backward","allow_backward","allow_same_value"],"default":"reject_backward","description":"Policy governing backward or repeated advancement values.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"initial_value","type":"string","required":false,"description":"Initial time value used when no prior state exists.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"initialization_policy","type":"enum","required":false,"options":["require_existing","allow_projected_initial_value"],"default":"require_existing","description":"Policy for initializing the time variable when state is absent.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"concurrency_policy","type":"enum","required":false,"options":["serialized_airflow","runtime_sql_lock"],"default":"serialized_airflow","description":"Mechanism ensuring serialized advancement under concurrency.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"evidence_prefix","type":"string","required":true,"description":"Storage prefix where advancement evidence is written.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"evidence_required","type":"boolean","required":false,"default":true,"description":"Whether evidence must be emitted for each advancement.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"notes_template","type":"string","required":false,"description":"Template for human-readable advancement notes.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"advanced_by","type":"string","required":false,"description":"Actor/principal recorded as performing the advancement.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"source","type":"string","required":false,"description":"Source system or process recorded for the advancement.","tier":"derived","derivedFrom":"platform_default"}
]'::jsonb
WHERE blueprint_key = 'AdvanceTimeDimension';

-- RemotePipelineInvocation (39th survivor; sourced from V131)
UPDATE blueprints SET schema_behavior = '{"version":1,"ops":[{"op":"invoke-remote","ui_label":"Invoke remote pipeline","config":{"remote_target_ref":{"param":"remote_target_ref"},"remote_dag_id":{"param":"remote_dag_id"}}}],"blueprint_params":["environment","federated_tenant_key","airflow_connection_id","poll_interval_seconds","timeout_seconds","payload_template","storage_backend"],"emission":{"orchestration":"airflow","compute":null}}'::jsonb,
    params_schema = '[
  {"name":"remote_target_ref","type":"string","required":true,"description":"Reference to the remote pipeline/target to invoke.","tier":"user"},
  {"name":"remote_dag_id","type":"string","required":false,"description":"DAG id of the remote pipeline to trigger.","tier":"user"},
  {"name":"environment","type":"string","required":true,"description":"Target environment of the remote invocation.","tier":"derived","derivedFrom":"pipeline"},
  {"name":"federated_tenant_key","type":"string","required":true,"description":"Federated tenant key authorizing the cross-tenant invocation.","tier":"derived","derivedFrom":"connector"},
  {"name":"airflow_connection_id","type":"string","required":true,"description":"Airflow connection used to reach the remote scheduler.","tier":"derived","derivedFrom":"connector"},
  {"name":"poll_interval_seconds","type":"integer","required":false,"description":"Interval in seconds between remote-completion polls.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"timeout_seconds","type":"integer","required":false,"description":"Timeout in seconds for the remote invocation to complete.","tier":"derived","derivedFrom":"platform_default"},
  {"name":"payload_template","type":"object","required":false,"description":"Template for the payload passed to the remote pipeline.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend, derived from pipeline storage configuration.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb
WHERE blueprint_key = 'RemotePipelineInvocation';

-- =============================================================================
-- Phase C — the 2 NEW SQL blueprints (ADR 0024). INSERT with ON CONFLICT guard.
-- =============================================================================

-- SqlModel (TRANSFORM, dbt). Required reserved input port "input"; output "sql_output".
INSERT INTO blueprints (
    id, blueprint_key, name, description, category, version,
    schema_behavior, params_schema, input_ports, output_ports, deferred
) VALUES (
    '01JBP0TRANSFORM0SQLMODEL01',
    'SqlModel',
    'SQL Model',
    'A power-user/DE multi-statement dbt SQL chain; the last step''s SELECT defines the output schema (Calcite-derived or declared). Supports inline date mnemonics.',
    'TRANSFORM',
    '1.0.0',
    '{"version":1,"ops":[{"op":"sql-model","ui_label":"SQL model chain","config":{"steps":{"param":"steps"}}}],"blueprint_params":["declared_output_schema","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"dbt"}}'::jsonb,
    '[
  {"name":"steps","type":"object[]","required":true,"accepts_mnemonic":true,"description":"Ordered chain of SQL model steps, each {name, sql, materialize}; the last step SELECT defines the output schema. Supports inline [[ ]] date mnemonics.","tier":"user"},
  {"name":"declared_output_schema","type":"object[]","required":false,"description":"Fallback declared output columns used when Calcite cannot parse the final step SELECT.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend for the output relation.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"silver","description":"Lake layer the SQL model output lands in.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the output relation.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb,
    '[{"name":"input","description":"Upstream relation the SQL references (reserved relation name)."}]'::jsonb,
    '[{"name":"sql_output","description":"The Calcite-derived (or declared) result schema of the final step."}]'::jsonb,
    false
)
ON CONFLICT (blueprint_key) DO NOTHING;

-- SourceSQL (INGESTION, pyspark). No input port; output "source_output".
INSERT INTO blueprints (
    id, blueprint_key, name, description, category, version,
    schema_behavior, params_schema, input_ports, output_ports, deferred
) VALUES (
    '01JBP0INGESTION0SOURCESQL1',
    'SourceSQL',
    'Source SQL',
    'A connector-bound ingestion blueprint that reads a single source SELECT from a bound JDBC connector and lands it in bronze with audit columns; output schema is source-derived (JDBC metadata) or declared. Supports inline date mnemonics.',
    'INGESTION',
    '1.0.0',
    '{"version":1,"ops":[{"op":"read-source","ui_label":"Read source SQL","config":{"source_query":{"param":"source_query"},"connector_instance_id":{"param":"connector_instance_id"}}},{"op":"add-audit-columns","ui_label":"Add audit columns","config":{}},{"op":"write-sink","ui_label":"Write to bronze","config":{"target":"bronze","mode":"overwrite"}}],"blueprint_params":["declared_output_schema","storage_backend","lake_layer","lake_format"],"emission":{"orchestration":"airflow","compute":"pyspark"}}'::jsonb,
    '[
  {"name":"source_query","type":"string","required":true,"accepts_mnemonic":true,"description":"Single source-SELECT executed against the bound JDBC connector. Supports inline [[ ]] date mnemonics.","tier":"user"},
  {"name":"connector_instance_id","type":"string","required":true,"description":"The JDBC connector instance the source SELECT is run against.","tier":"derived","derivedFrom":"connector"},
  {"name":"declared_output_schema","type":"object[]","required":false,"description":"Fallback declared output columns used when the source is unreachable at design time.","tier":"user"},
  {"name":"storage_backend","type":"enum","required":true,"description":"Storage backend for the landed bronze relation.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_layer","type":"enum","required":true,"default":"bronze","description":"Lake layer the source data lands in.","tier":"derived","derivedFrom":"pipeline.storage"},
  {"name":"lake_format","type":"enum","required":true,"default":"delta","description":"Table format for the landed relation.","tier":"derived","derivedFrom":"pipeline.storage"}
]'::jsonb,
    '[]'::jsonb,
    '[{"name":"source_output","description":"The source-derived (JDBC metadata) or declared result schema, landed in bronze."}]'::jsonb,
    false
)
ON CONFLICT (blueprint_key) DO NOTHING;

-- =============================================================================
-- Phase D — Deprecate the 4 dead blueprints (idempotent, V81 shape). add_surface
-- = 'none' prevents instantiation. Each UPDATE is a no-op if the key is absent.
-- =============================================================================

UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = 'FileArrivalSensor', add_surface = 'none'
    WHERE blueprint_key = 'ObjectStoreKeySensor';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = 'ScheduleAndTriggers', add_surface = 'none'
    WHERE blueprint_key = 'DatasetDependencySensor';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = 'BulkBackfill', add_surface = 'none'
    WHERE blueprint_key = 'BackfillAndReplay';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = NULL, add_surface = 'none'
    WHERE blueprint_key = 'CostMonitoringHook';

-- =============================================================================
-- Phase E — Correct SnapshotModel artifact_types mis-tag (FIX #9 / GAP1).
-- SCD2Dimension stays ["dbt_snapshot"] (correct) and is NOT touched here.
-- =============================================================================

UPDATE blueprints SET artifact_types = '["incremental"]'::jsonb
    WHERE blueprint_key = 'SnapshotModel';
