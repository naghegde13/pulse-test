-- V92: params_schema audit for the 4 example-needing blueprints that have
-- date-shaped params or thin path-conflating fields. The other 13 of the 17
-- example-needing blueprints (audited in #43) had adequate schemas; their
-- examples can be authored against the existing fields.
--
-- Closes the #43 schema-upgrade portion. Acts as the gate to un-pause #39
-- (bulk-author the remaining 14 examples).

-- =============================================================================
-- 1) BackfillAndReplay — flag date params accept_mnemonic; add calendar config
-- =============================================================================
UPDATE blueprints
SET params_schema = '[
    {
        "name": "start_date",
        "type": "string",
        "required": true,
        "accepts_mnemonic": true,
        "description": "First date of the replay window. Accepts ISO date (YYYY-MM-DD) or PULSE mnemonic (e.g., BOM-12, BOY, T-365). Resolved at runtime against PULSE_BUSINESS_DATE."
    },
    {
        "name": "end_date",
        "type": "string",
        "required": true,
        "accepts_mnemonic": true,
        "description": "Last date of the replay window. Accepts ISO date or PULSE mnemonic (e.g., EOM-1, T-1, EOQ)."
    },
    {
        "name": "parallelism",
        "type": "integer",
        "default": 1,
        "required": false,
        "description": "Concurrent chunks. Higher values speed replay but increase load on the source."
    },
    {
        "name": "clear_existing",
        "type": "boolean",
        "default": false,
        "required": false,
        "description": "If true, delete existing rows in the window before re-ingesting. If false (default), use replaceWhere idempotency."
    },
    {
        "name": "holiday_calendar_id",
        "type": "enum",
        "options": ["US-FED", "US-NYSE"],
        "default": "US-FED",
        "required": false,
        "description": "Holiday calendar for business-day mnemonic resolution. Inherited from domain.business_date_config when not set."
    },
    {
        "name": "fiscal_offset_months",
        "type": "integer",
        "default": 0,
        "required": false,
        "description": "Months between fiscal year start and calendar year start (0 = aligned, 9 = US fiscal Oct 1 start). Inherited from domain.business_date_config when not set."
    }
]'::jsonb
WHERE blueprint_key = 'BackfillAndReplay';


-- =============================================================================
-- 2) BulkBackfill — same pattern
-- =============================================================================
UPDATE blueprints
SET params_schema = '[
    {
        "name": "source_query",
        "type": "string",
        "required": true,
        "description": "JDBC SELECT to source rows. Should reference the date column for predicate pushdown."
    },
    {
        "name": "date_range_start",
        "type": "string",
        "required": true,
        "accepts_mnemonic": true,
        "description": "First date of the backfill window. Accepts ISO date or PULSE mnemonic (e.g., BOM-12, BOY-1, 2024-01-01)."
    },
    {
        "name": "date_range_end",
        "type": "string",
        "required": true,
        "accepts_mnemonic": true,
        "description": "Last date of the backfill window. Accepts ISO date or PULSE mnemonic (e.g., EOM-1, T-1, EOQ)."
    },
    {
        "name": "chunk_size",
        "type": "integer",
        "default": 100000,
        "required": false,
        "description": "Rows per JDBC fetch chunk. Tune to source row size."
    },
    {
        "name": "chunk_days",
        "type": "integer",
        "default": 31,
        "required": false,
        "description": "Calendar days per processing chunk. Smaller = finer-grained idempotency, more roundtrips. Default ~1 month is balanced."
    },
    {
        "name": "parallelism",
        "type": "integer",
        "default": 4,
        "required": false,
        "description": "JDBC partition count for parallel reads."
    },
    {
        "name": "holiday_calendar_id",
        "type": "enum",
        "options": ["US-FED", "US-NYSE"],
        "default": "US-FED",
        "required": false,
        "description": "Holiday calendar for business-day mnemonic resolution."
    },
    {
        "name": "fiscal_offset_months",
        "type": "integer",
        "default": 0,
        "required": false,
        "description": "Months between fiscal year start and calendar year start."
    }
]'::jsonb
WHERE blueprint_key = 'BulkBackfill';


-- =============================================================================
-- 3) FileArrivalSensor — major rewrite: split path → bucket+prefix+filename;
--    add filename_pattern, pattern_kind, date_value (mnemonic), date_format,
--    expected_size_min, expected_max_age_hours, multiple_files_mode, soft_fail,
--    plus calendar config; add gcs to storage_kind
-- =============================================================================
UPDATE blueprints
SET params_schema = '[
    {
        "name": "storage_kind",
        "type": "enum",
        "options": ["s3", "gcs", "sftp"],
        "required": true,
        "description": "Object-storage backend hosting the file. s3 covers MinIO and AWS S3. gcs covers Google Cloud Storage. sftp covers SFTP servers (no bucket; the bucket field becomes the SFTP host)."
    },
    {
        "name": "bucket",
        "type": "string",
        "required": true,
        "description": "Bucket name (S3/GCS) or SFTP host. PULSE convention: {tenant_slug}_{domain_slug} (e.g., home-lending_servicing). Derived from tenant + domain context if left blank."
    },
    {
        "name": "path_prefix",
        "type": "string",
        "required": true,
        "description": "Path prefix under the bucket (e.g., inbound/msp/). Trailing slash optional; PULSE normalizes."
    },
    {
        "name": "filename_pattern",
        "type": "string",
        "required": true,
        "description": "Filename pattern. For pattern_kind=template, use {date} as the date placeholder (e.g., loan_master_{date}.csv). For glob, use shell-style wildcards (e.g., *.csv). For regex, a Java regex (e.g., ^loan_master_\\\\d{8}\\\\.csv$)."
    },
    {
        "name": "pattern_kind",
        "type": "enum",
        "options": ["template", "glob", "regex"],
        "default": "template",
        "required": false,
        "description": "How to interpret filename_pattern. template substitutes {date} via date_value+date_format. glob matches shell-style wildcards. regex matches a Java regular expression."
    },
    {
        "name": "date_format",
        "type": "string",
        "default": "yyyyMMdd",
        "required": false,
        "description": "Java date format used to substitute {date} in filename_pattern when pattern_kind=template. Common values: yyyyMMdd, yyyy-MM-dd, MMddyyyy."
    },
    {
        "name": "date_value",
        "type": "string",
        "default": "RUN_DATE",
        "required": false,
        "accepts_mnemonic": true,
        "description": "Date used to substitute {date} in filename_pattern. PULSE mnemonic (PBD = previous business day; RUN_DATE = today; T-1 = yesterday; BOM = beginning of month) or ISO date. Resolved at task execution time against PULSE_BUSINESS_DATE."
    },
    {
        "name": "expected_size_min",
        "type": "integer",
        "default": 0,
        "required": false,
        "description": "Minimum file size in bytes to count as arrived. 0 disables the check. Use to guard against partial uploads."
    },
    {
        "name": "expected_max_age_hours",
        "type": "integer",
        "required": false,
        "description": "Optional stale-file guard. If the matched file is older than this many hours, treat as not-arrived."
    },
    {
        "name": "multiple_files_mode",
        "type": "boolean",
        "default": false,
        "required": false,
        "description": "If true, accept any matching file (glob-like multi-file landing). If false (default), succeed when exactly one file matches."
    },
    {
        "name": "soft_fail",
        "type": "boolean",
        "default": false,
        "required": false,
        "description": "If true, sensor timeout marks the task SKIPPED (not FAILED), allowing the rest of the DAG to run. Almost always false for ingestion sensors."
    },
    {
        "name": "poke_interval_seconds",
        "type": "integer",
        "default": 300,
        "required": false,
        "description": "Seconds between checks. 60 for sub-minute landings, 300 for minute-scale, 3600 for hour-scale."
    },
    {
        "name": "timeout_seconds",
        "type": "integer",
        "default": 14400,
        "required": false,
        "description": "Hard fail after this many seconds. Default 4h."
    },
    {
        "name": "mode",
        "type": "enum",
        "default": "reschedule",
        "options": ["poke", "reschedule"],
        "required": false,
        "description": "reschedule frees the worker slot between checks (recommended for long waits). poke holds the slot (for fast polling)."
    },
    {
        "name": "holiday_calendar_id",
        "type": "enum",
        "options": ["US-FED", "US-NYSE"],
        "default": "US-FED",
        "required": false,
        "description": "Holiday calendar for resolving date_value mnemonics like PBD."
    },
    {
        "name": "fiscal_offset_months",
        "type": "integer",
        "default": 0,
        "required": false,
        "description": "Months between fiscal year and calendar year. Used by fiscal mnemonics (BOFY, EOFQ, etc.) in date_value."
    }
]'::jsonb
WHERE blueprint_key = 'FileArrivalSensor';


-- =============================================================================
-- 4) ObjectStoreKeySensor — similar pattern; bucket already separate, but
--    object_key conflates filename + date placeholder. Split similarly.
-- =============================================================================
UPDATE blueprints
SET params_schema = '[
    {
        "name": "storage_kind",
        "type": "enum",
        "options": ["s3", "gcs"],
        "default": "s3",
        "required": false,
        "description": "Object-storage backend (s3 also covers MinIO)."
    },
    {
        "name": "bucket",
        "type": "string",
        "required": true,
        "description": "Bucket name. PULSE convention: {tenant_slug}_{domain_slug}."
    },
    {
        "name": "key_prefix",
        "type": "string",
        "required": false,
        "description": "Path prefix under the bucket. Optional for top-level keys."
    },
    {
        "name": "key_pattern",
        "type": "string",
        "required": true,
        "description": "Object key pattern. With pattern_kind=template, use {date} for date substitution (e.g., daily_export_{date}/_SUCCESS). With glob, shell wildcards. With regex, Java regex."
    },
    {
        "name": "pattern_kind",
        "type": "enum",
        "options": ["template", "glob", "regex"],
        "default": "template",
        "required": false,
        "description": "Interpretation of key_pattern."
    },
    {
        "name": "date_format",
        "type": "string",
        "default": "yyyyMMdd",
        "required": false,
        "description": "Java date format for {date} substitution."
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
        "name": "expected_size_min",
        "type": "integer",
        "default": 0,
        "required": false,
        "description": "Minimum object size in bytes."
    },
    {
        "name": "multiple_files_mode",
        "type": "boolean",
        "default": false,
        "required": false,
        "description": "If true, succeed when ANY matching object exists. If false, succeed when exactly one matches."
    },
    {
        "name": "soft_fail",
        "type": "boolean",
        "default": false,
        "required": false,
        "description": "On timeout: SKIPPED (true) vs FAILED (false)."
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
        "required": false,
        "description": "Holiday calendar for date_value mnemonic resolution."
    },
    {
        "name": "fiscal_offset_months",
        "type": "integer",
        "default": 0,
        "required": false,
        "description": "Fiscal year offset for mnemonic resolution."
    }
]'::jsonb
WHERE blueprint_key = 'ObjectStoreKeySensor';
