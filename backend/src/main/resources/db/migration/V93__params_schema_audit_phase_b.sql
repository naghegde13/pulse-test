-- V93: params_schema upgrades from #44 Phase A audit (7 blueprints).
--
-- V92 covered the 4 blueprints whose date-shaped params required the mnemonic
-- system (BulkBackfill, BackfillAndReplay, FileArrivalSensor, ObjectStoreKeySensor).
-- V93 covers the remaining 7 the audit flagged as UPGRADE — most don't have
-- date params but were missing fields that the production-grade examples need.
--
-- After V93 the only schema work outstanding is the DEFERRED set (LakeWriter
-- via #30, plus a handful with note-level concerns). #44 Phase C (example
-- file rewrites) lands in subsequent commits, against the upgraded schemas.

-- =============================================================================
-- 1) FreshnessChecks — add holiday-aware max-age + calendar config
-- =============================================================================
UPDATE blueprints
SET params_schema = '[
    {
        "name": "timestamp_column",
        "type": "string",
        "required": true,
        "description": "Column on the dataset that records ingestion or update time."
    },
    {
        "name": "max_age_minutes",
        "type": "integer",
        "required": false,
        "description": "Hard ceiling in calendar minutes. Use for sub-hour SLAs."
    },
    {
        "name": "max_age_hours",
        "type": "integer",
        "default": 24,
        "required": false,
        "description": "Hard ceiling in calendar hours. Use for daily SLAs that don''t care about weekends."
    },
    {
        "name": "max_age_business_days",
        "type": "integer",
        "required": false,
        "description": "Hard ceiling in business days (skips weekends + holidays in the configured calendar). Use for batch SLAs that span the weekend."
    },
    {
        "name": "holiday_calendar_id",
        "type": "enum",
        "options": ["US-FED", "US-NYSE"],
        "default": "US-FED",
        "required": false,
        "description": "Calendar for max_age_business_days computation."
    },
    {
        "name": "fiscal_offset_months",
        "type": "integer",
        "default": 0,
        "required": false,
        "description": "Fiscal offset (unused for FreshnessChecks; included for resolver compatibility)."
    }
]'::jsonb
WHERE blueprint_key = 'FreshnessChecks';


-- =============================================================================
-- 2) ApiIngestion — secret reference + retry / timeout fields
-- =============================================================================
UPDATE blueprints
SET params_schema = '[
    {
        "name": "api_url",
        "type": "string",
        "required": true,
        "description": "REST endpoint. May contain Airflow-style {{ ds }} templating for date-driven endpoints."
    },
    {
        "name": "auth_type",
        "type": "enum",
        "options": ["none", "bearer", "basic", "oauth2_client_credentials", "api_key_header"],
        "default": "bearer",
        "required": true,
        "description": "Authentication scheme."
    },
    {
        "name": "auth_credential_ref",
        "type": "string",
        "required": false,
        "description": "gcp-sm:// reference to the credential. Resolved at runtime by the secret manager. Required for any auth_type other than none."
    },
    {
        "name": "pagination_type",
        "type": "enum",
        "options": ["none", "offset_limit", "cursor", "link_header", "page_number"],
        "default": "offset_limit",
        "required": false
    },
    {
        "name": "rate_limit_rpm",
        "type": "integer",
        "default": 60,
        "required": false,
        "description": "Max requests per minute. Client throttles to stay under this."
    },
    {
        "name": "incremental_field",
        "type": "string",
        "required": false,
        "description": "Column / response field that drives incremental ingestion (the watermark column). Empty = full pull each run."
    },
    {
        "name": "response_json_path",
        "type": "string",
        "default": "$.data",
        "required": false,
        "description": "JSONPath to the rows array within the response payload."
    },
    {
        "name": "retry_count",
        "type": "integer",
        "default": 3,
        "required": false,
        "description": "Bounded retries on 5xx / network error before failing the task."
    },
    {
        "name": "timeout_seconds",
        "type": "integer",
        "default": 60,
        "required": false,
        "description": "Per-request timeout."
    }
]'::jsonb
WHERE blueprint_key = 'ApiIngestion';


-- =============================================================================
-- 3) FileIngestion — mirror FileArrivalSensor's filename-pattern + date-mnemonic
--    structure. Most static config inherits from connector (kept).
-- =============================================================================
UPDATE blueprints
SET params_schema = '[
    {
        "name": "_inherited_from_connector",
        "type": "string[]",
        "required": false,
        "description": "Fields inherited from the linked connector instance. Read-only at this surface; configure on the connector. Typically: bucket, path_prefix, auth, region."
    },
    {
        "name": "filename_pattern",
        "type": "string",
        "required": true,
        "description": "Filename pattern at the connector path. With pattern_kind=template, use {date} placeholder (e.g., loan_master_{date}.csv). With glob, shell wildcards. With regex, Java regex."
    },
    {
        "name": "pattern_kind",
        "type": "enum",
        "options": ["template", "glob", "regex"],
        "default": "template",
        "required": false
    },
    {
        "name": "date_format",
        "type": "string",
        "default": "yyyyMMdd",
        "required": false,
        "description": "Java date format for {date} substitution when pattern_kind=template."
    },
    {
        "name": "date_value",
        "type": "string",
        "default": "RUN_DATE",
        "required": false,
        "accepts_mnemonic": true,
        "description": "Date for {date} substitution. PULSE mnemonic (PBD = previous business day, RUN_DATE = today, T-1 = yesterday) or ISO date."
    },
    {
        "name": "delimiter",
        "type": "string",
        "default": ",",
        "required": false,
        "description": "CSV delimiter; ignored for non-CSV formats."
    },
    {
        "name": "has_header",
        "type": "boolean",
        "default": true,
        "required": false,
        "description": "Skip the header row from the file."
    },
    {
        "name": "partition_by",
        "type": "string[]",
        "required": false,
        "description": "Bronze partition columns (typically [\"ds\"])."
    },
    {
        "name": "expected_size_min",
        "type": "integer",
        "default": 0,
        "required": false,
        "description": "Minimum file size in bytes to accept; 0 disables. Guards against partial uploads."
    },
    {
        "name": "holiday_calendar_id",
        "type": "enum",
        "options": ["US-FED", "US-NYSE"],
        "default": "US-FED",
        "required": false
    },
    {
        "name": "fiscal_offset_months",
        "type": "integer",
        "default": 0,
        "required": false
    }
]'::jsonb
WHERE blueprint_key = 'FileIngestion';


-- =============================================================================
-- 4) DatabaseReadinessSensor — date mnemonic for SQL substitution + count guards
-- =============================================================================
UPDATE blueprints
SET params_schema = '[
    {
        "name": "sql",
        "type": "string",
        "required": true,
        "description": "Probe SQL. May contain {date} placeholder (substituted via date_value+date_format) for date-relative readiness checks. Example: SELECT COUNT(*) FROM landing WHERE ds = ''{date}''."
    },
    {
        "name": "connection_id",
        "type": "string",
        "default": "pulse_sql_default",
        "required": false,
        "description": "Airflow connection_id pointing at the source DB."
    },
    {
        "name": "date_format",
        "type": "string",
        "default": "yyyy-MM-dd",
        "required": false,
        "description": "Java date format for {date} substitution in sql."
    },
    {
        "name": "date_value",
        "type": "string",
        "default": "RUN_DATE",
        "required": false,
        "accepts_mnemonic": true,
        "description": "Date for {date} substitution. PULSE mnemonic or ISO date."
    },
    {
        "name": "expected_count_min",
        "type": "integer",
        "default": 1,
        "required": false,
        "description": "Minimum row count to consider source ready. Default 1 (any rows)."
    },
    {
        "name": "expected_count_max",
        "type": "integer",
        "required": false,
        "description": "Optional ceiling. Use to detect runaway loads (e.g., 10x normal volume signals upstream defect)."
    },
    {
        "name": "poke_interval_seconds",
        "type": "integer",
        "default": 300,
        "required": false
    },
    {
        "name": "timeout_seconds",
        "type": "integer",
        "default": 14400,
        "required": false
    },
    {
        "name": "mode",
        "type": "enum",
        "default": "reschedule",
        "options": ["poke", "reschedule"],
        "required": false
    },
    {
        "name": "holiday_calendar_id",
        "type": "enum",
        "options": ["US-FED", "US-NYSE"],
        "default": "US-FED",
        "required": false
    },
    {
        "name": "fiscal_offset_months",
        "type": "integer",
        "default": 0,
        "required": false
    }
]'::jsonb
WHERE blueprint_key = 'DatabaseReadinessSensor';


-- =============================================================================
-- 5) ScheduleAndTriggers — add max_active_runs, depends_on_past, catchup_enabled
--    (per #28's locked orchestration show-your-work rule)
-- =============================================================================
UPDATE blueprints
SET params_schema = '[
    {
        "name": "schedule_type",
        "type": "enum",
        "options": ["cron", "event", "manual"],
        "required": true
    },
    {
        "name": "cron_expression",
        "type": "string",
        "required": false,
        "description": "Standard 5-field cron (e.g., 0 6 * * 1-5 = 6am Mon-Fri). Required when schedule_type=cron."
    },
    {
        "name": "trigger_dataset",
        "type": "string",
        "required": false,
        "description": "Dataset whose update triggers this pipeline. Required when schedule_type=event."
    },
    {
        "name": "timezone",
        "type": "string",
        "default": "UTC",
        "required": false,
        "description": "IANA timezone for the cron expression (e.g., America/New_York)."
    },
    {
        "name": "max_active_runs",
        "type": "integer",
        "default": 1,
        "required": false,
        "description": "Max concurrent DAG runs. 1 prevents overlap (recommended for SCD2 / ordering-sensitive pipelines)."
    },
    {
        "name": "catchup_enabled",
        "type": "boolean",
        "default": false,
        "required": false,
        "description": "If true, on first deploy Airflow schedules a run for every interval from start_date to today. DEFAULT FALSE — only enable when historical backfill is explicitly desired (per #28 locked rule)."
    },
    {
        "name": "depends_on_past",
        "type": "boolean",
        "default": false,
        "required": false,
        "description": "If true, today''s run waits for yesterday''s run to succeed. Use for incremental pipelines where today''s output depends on yesterday''s state."
    },
    {
        "name": "retry_count",
        "type": "integer",
        "default": 3,
        "required": false,
        "description": "Per-task retries before the DAG fails."
    }
]'::jsonb
WHERE blueprint_key = 'ScheduleAndTriggers';


-- =============================================================================
-- 6) BronzeToSilverCleaning — fields actually reflect what the dbt model does
-- =============================================================================
UPDATE blueprints
SET params_schema = '[
    {
        "name": "type_coercions",
        "type": "object",
        "required": false,
        "description": "Map of {column: target_type} casts. Example: {\"created_at\": \"timestamp\", \"amount\": \"decimal(18,2)\"}."
    },
    {
        "name": "columns_to_trim",
        "type": "string[]",
        "required": false,
        "description": "String columns to trim leading/trailing whitespace."
    },
    {
        "name": "rename_map",
        "type": "object",
        "required": false,
        "description": "Map of {old_column_name: new_column_name}. Renames must be reflected in downstream contracts."
    },
    {
        "name": "null_handling",
        "type": "object",
        "required": false,
        "description": "Map of {column: action} where action is \"keep\" / \"coerce_to:<value>\" / \"drop_row\". Default: keep."
    },
    {
        "name": "dedup_key",
        "type": "string[]",
        "required": false,
        "description": "Optional dedup-on-key (uses ROW_NUMBER over the key, keeps latest). For full-fledged dedup use the DedupeAndMerge blueprint."
    },
    {
        "name": "drop_columns",
        "type": "string[]",
        "required": false,
        "description": "Columns to omit from silver output."
    },
    {
        "name": "partition_by",
        "type": "string[]",
        "default": ["ds"],
        "required": false,
        "description": "Silver partition columns. Default partitions by ds (the bronze ingest date)."
    }
]'::jsonb
WHERE blueprint_key = 'BronzeToSilverCleaning';


-- =============================================================================
-- 7) DedupeAndMerge — deterministic ranking + partition
-- =============================================================================
UPDATE blueprints
SET params_schema = '[
    {
        "name": "match_keys",
        "type": "string[]",
        "required": true,
        "description": "Business-key columns that define the dedup partition. ROW_NUMBER partitions by these."
    },
    {
        "name": "order_by_columns",
        "type": "object[]",
        "required": true,
        "description": "Ordered ranking columns: [{column, direction}]. Direction = \"asc\" | \"desc\". REQUIRED to be deterministic — include a tiebreaker (typically the surrogate key) at the end so re-runs produce identical output."
    },
    {
        "name": "match_strategy",
        "type": "enum",
        "default": "exact",
        "options": ["exact", "fuzzy", "composite"],
        "required": false,
        "description": "exact = string equality. fuzzy = soundex / Levenshtein (for name dedup). composite = AND across multiple match_keys."
    },
    {
        "name": "merge_priority",
        "type": "string",
        "required": false,
        "description": "Optional explicit priority column when ordering ties. If unset, the last column of order_by_columns acts as the tiebreaker."
    },
    {
        "name": "partition_by",
        "type": "string[]",
        "default": ["ds"],
        "required": false,
        "description": "Silver partition columns."
    },
    {
        "name": "dedup_method",
        "type": "enum",
        "default": "row_number",
        "options": ["row_number", "qualify", "distinct_on"],
        "required": false,
        "description": "row_number = portable Spark idiom. qualify = compact dialect-specific (Snowflake/Spark). distinct_on = Postgres-only."
    }
]'::jsonb
WHERE blueprint_key = 'DedupeAndMerge';
