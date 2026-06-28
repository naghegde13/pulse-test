-- V65: Business time dimension tracking on datasets and domains with advance audit log.

-- =====================================================
-- 1. Extended time grain values + as-of tracking on datasets
-- =====================================================

-- Update time_grain comment with new supported values
COMMENT ON COLUMN datasets.time_grain IS
  'DAILY, DAILY_BUSINESS_DAY, WEEKLY, BEG_OF_MONTH, END_OF_MONTH, BEG_OF_QUARTER, END_OF_QUARTER, EVERY_N_HOURS, CONTINUOUS';

ALTER TABLE datasets ADD COLUMN IF NOT EXISTS current_asof TIMESTAMPTZ;
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS asof_timezone VARCHAR(50) DEFAULT 'America/New_York';
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS time_grain_config JSONB DEFAULT '{}';

COMMENT ON COLUMN datasets.current_asof IS 'Current business as-of date/time for this dataset';
COMMENT ON COLUMN datasets.asof_timezone IS 'IANA timezone for the business date (e.g. America/New_York)';
COMMENT ON COLUMN datasets.time_grain_config IS
  'Parameters for the time grain. Examples:
   WEEKLY: {"days": ["MON","WED","FRI"]}
   EVERY_N_HOURS: {"interval_hours": 2}
   DAILY_BUSINESS_DAY: {"calendar": "US"} (skip weekends/holidays)';

-- =====================================================
-- 2. Domain-level business date
-- =====================================================

ALTER TABLE domains ADD COLUMN IF NOT EXISTS current_business_date DATE;
ALTER TABLE domains ADD COLUMN IF NOT EXISTS business_date_grain VARCHAR(20) DEFAULT 'DAILY';
ALTER TABLE domains ADD COLUMN IF NOT EXISTS business_date_timezone VARCHAR(50) DEFAULT 'America/New_York';
ALTER TABLE domains ADD COLUMN IF NOT EXISTS business_date_config JSONB DEFAULT '{}';

COMMENT ON COLUMN domains.current_business_date IS 'Global business date for the domain (shared across all datasets in this domain)';
COMMENT ON COLUMN domains.business_date_grain IS 'Time grain for domain business date (typically DAILY or DAILY_BUSINESS_DAY)';

-- =====================================================
-- 3. Dataset as-of advance audit log
-- =====================================================

CREATE TABLE asof_advance_log (
    id              VARCHAR(26) PRIMARY KEY,
    dataset_id      VARCHAR(26) NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
    previous_asof   TIMESTAMPTZ,
    new_asof        TIMESTAMPTZ NOT NULL,
    advanced_by     VARCHAR(100) NOT NULL,
    advance_source  VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_asof_log_dataset ON asof_advance_log(dataset_id);
CREATE INDEX idx_asof_log_created ON asof_advance_log(dataset_id, created_at DESC);

COMMENT ON COLUMN asof_advance_log.advanced_by IS 'Who/what triggered the advance: user email, pipeline name, or blueprint instance';
COMMENT ON COLUMN asof_advance_log.advance_source IS 'MANUAL, PIPELINE, BLUEPRINT, API';

-- =====================================================
-- 4. Domain business date advance audit log
-- =====================================================

CREATE TABLE domain_advance_log (
    id                  VARCHAR(26) PRIMARY KEY,
    domain_id           VARCHAR(26) NOT NULL REFERENCES domains(id) ON DELETE CASCADE,
    previous_date       DATE,
    new_date            DATE NOT NULL,
    advanced_by         VARCHAR(100) NOT NULL,
    advance_source      VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_domain_log_domain ON domain_advance_log(domain_id);
CREATE INDEX idx_domain_log_created ON domain_advance_log(domain_id, created_at DESC);

-- =====================================================
-- 5. Seed as-of dates for existing datasets
-- =====================================================

UPDATE datasets SET current_asof = '2026-03-02T00:00:00-05:00', asof_timezone = 'America/New_York'
WHERE id = '01JDS0LOS0APPLICATIONS01';

UPDATE datasets SET current_asof = '2026-03-02T00:00:00-05:00', asof_timezone = 'America/New_York'
WHERE id = '01JDS0LOS0UNDERWRITING01';

UPDATE datasets SET current_asof = '2026-03-02T00:00:00-05:00', asof_timezone = 'America/New_York'
WHERE id = '01JDS0PAY0DAILY0SUMMARY1';

UPDATE datasets SET current_asof = '2026-03-02T00:00:00-05:00', asof_timezone = 'America/New_York'
WHERE id = '01JDS0CRD0CREDIT0FILES01';

UPDATE datasets SET current_asof = '2026-02-28T00:00:00-05:00', asof_timezone = 'America/New_York'
WHERE id = '01JDS0INV0LOAN0POOLS0001';

-- Seed domain business dates
UPDATE domains SET current_business_date = '2026-03-02', business_date_grain = 'DAILY_BUSINESS_DAY', business_date_timezone = 'America/New_York'
WHERE tenant_id = 'tenant-home-lending';

-- =====================================================
-- 6. AdvanceTimeDimension blueprint
-- =====================================================

INSERT INTO blueprints (id, blueprint_key, version, name, description, category, input_ports, output_ports, params_schema, deferred)
VALUES (
    '01JBPADVANCETIME00000001',
    'AdvanceTimeDimension',
    '1.0.0',
    'Advance Time Dimension',
    'Advances the business as-of date/time of a dataset to the next interval based on its configured time grain. Place this at the end of a pipeline to automatically move the dataset forward after successful processing. Can also advance the domain-level business date.',
    'ORCHESTRATION',
    '[{"name":"trigger","type":"any","description":"Connect to the last processing step so advance happens after data is ready"}]',
    '[{"name":"status","type":"status","description":"Advance result with previous and new as-of values"}]',
    '[{"name":"dataset_name","type":"string","required":true,"description":"Name of the dataset to advance"},{"name":"advance_domain","type":"boolean","required":false,"default":false,"description":"Also advance the domain business date"},{"name":"notes","type":"string","required":false,"description":"Optional note to include in the audit log"}]',
    FALSE
);
