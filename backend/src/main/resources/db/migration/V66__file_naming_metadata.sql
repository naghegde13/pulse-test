-- V66: Structured file naming convention with time dimension extraction.
-- Replaces the plain-string file_naming_pattern with a structured JSONB
-- that identifies which segments of the filename carry time dimensions.

ALTER TABLE datasets ADD COLUMN IF NOT EXISTS file_naming_metadata JSONB;

COMMENT ON COLUMN datasets.file_naming_metadata IS
  'Structured breakdown of the file naming convention. Example:
   {
     "pattern": "employees_YYYYMMDD_YYYYMMDDHH24MISS.csv",
     "example": "employees_20260303_20260303143022.csv",
     "extension": "csv",
     "segments": [
       {"position": 1, "literal": "employees"},
       {"position": 2, "type": "business_date", "format": "YYYYMMDD",
        "description": "Business as-of date"},
       {"position": 3, "type": "processing_datetime", "format": "YYYYMMDDHH24MISS",
        "description": "File processing/generation timestamp"}
     ],
     "delimiter": "_",
     "time_dimensions": {
       "business_date": {"segment_position": 2, "format": "YYYYMMDD",
                         "maps_to": "current_asof"},
       "processing_datetime": {"segment_position": 3, "format": "YYYYMMDDHH24MISS",
                               "maps_to": "processing_timestamp"}
     }
   }';

-- Seed for existing datasets that have file patterns
UPDATE datasets SET file_naming_metadata = '{
  "pattern": "payment_summary_YYYYMMDD.parquet",
  "example": "payment_summary_20260302.parquet",
  "extension": "parquet",
  "segments": [
    {"position": 1, "literal": "payment"},
    {"position": 2, "literal": "summary"},
    {"position": 3, "type": "business_date", "format": "YYYYMMDD", "description": "Business as-of date"}
  ],
  "delimiter": "_",
  "time_dimensions": {
    "business_date": {"segment_position": 3, "format": "YYYYMMDD", "maps_to": "current_asof"}
  }
}'
WHERE id = '01JDS0PAY0DAILY0SUMMARY1';

UPDATE datasets SET file_naming_metadata = '{
  "pattern": "credit_file_YYYY-MM-DD.pgp",
  "example": "credit_file_2026-03-02.pgp",
  "extension": "pgp",
  "segments": [
    {"position": 1, "literal": "credit"},
    {"position": 2, "literal": "file"},
    {"position": 3, "type": "business_date", "format": "YYYY-MM-DD", "description": "Business as-of date"}
  ],
  "delimiter": "_",
  "time_dimensions": {
    "business_date": {"segment_position": 3, "format": "YYYY-MM-DD", "maps_to": "current_asof"}
  }
}'
WHERE id = '01JDS0CRD0CREDIT0FILES01';
