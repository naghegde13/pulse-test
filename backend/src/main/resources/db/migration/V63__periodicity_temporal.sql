-- V63: Periodicity & Temporal columns on datasets and pipeline_versions.
-- Per Periodicity Analysis decisions 1, 2, 3, 7, 8.

-- =====================================================
-- 1. Datasets: temporal/sensing columns
-- =====================================================

ALTER TABLE datasets ADD COLUMN IF NOT EXISTS time_grain VARCHAR(20);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS file_naming_pattern VARCHAR(255);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS arrival_cron VARCHAR(100);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS arrival_timezone VARCHAR(50);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS sensing_strategy VARCHAR(20);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS readiness_query TEXT;
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS readiness_connection_id VARCHAR(26);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS sensor_config JSONB;

COMMENT ON COLUMN datasets.time_grain IS 'MINUTELY, HOURLY, DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY, CONTINUOUS, AD_HOC, CUSTOM';
COMMENT ON COLUMN datasets.file_naming_pattern IS 'strftime format, e.g. payment_summary_%Y%m%d.csv';
COMMENT ON COLUMN datasets.arrival_cron IS 'Cron expression for expected arrival window';
COMMENT ON COLUMN datasets.arrival_timezone IS 'IANA timezone, e.g. America/New_York';
COMMENT ON COLUMN datasets.sensing_strategy IS 'file, sql_query, trigger, none';
COMMENT ON COLUMN datasets.readiness_query IS 'SQL template with {{ ds }} macros for sql_query sensing';
COMMENT ON COLUMN datasets.readiness_connection_id IS 'FK to connector_instances for sql_query sensing';
COMMENT ON COLUMN datasets.sensor_config IS 'Override poke_interval, timeout, mode for Airflow sensors';

-- =====================================================
-- 2. Pipeline versions: scheduling columns
-- =====================================================

ALTER TABLE pipeline_versions ADD COLUMN IF NOT EXISTS schedule_cron VARCHAR(100);
ALTER TABLE pipeline_versions ADD COLUMN IF NOT EXISTS catchup_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE pipeline_versions ADD COLUMN IF NOT EXISTS max_active_runs INTEGER DEFAULT 1;
ALTER TABLE pipeline_versions ADD COLUMN IF NOT EXISTS depends_on_past BOOLEAN DEFAULT FALSE;

COMMENT ON COLUMN pipeline_versions.schedule_cron IS 'Cron expression or Airflow preset (@daily, @hourly)';
COMMENT ON COLUMN pipeline_versions.catchup_enabled IS 'Whether Airflow should run backfills for missed intervals';
COMMENT ON COLUMN pipeline_versions.max_active_runs IS 'Maximum concurrent DAG runs';
COMMENT ON COLUMN pipeline_versions.depends_on_past IS 'Whether each task depends on the prior run succeeding';

-- =====================================================
-- 3. Seed temporal fields for existing datasets
-- =====================================================

UPDATE datasets SET time_grain = 'DAILY',
                    file_naming_pattern = 'payment_summary_%Y%m%d.parquet',
                    sensing_strategy = 'file'
WHERE id = '01JDS0PAY0DAILY0SUMMARY1';

UPDATE datasets SET time_grain = 'DAILY',
                    file_naming_pattern = 'credit_file_%Y-%m-%d.pgp',
                    sensing_strategy = 'file'
WHERE id = '01JDS0CRD0CREDIT0FILES01';

UPDATE datasets SET time_grain = 'CONTINUOUS',
                    sensing_strategy = 'none'
WHERE id = '01JDS0PAY0EVENTS00000001';

UPDATE datasets SET time_grain = 'DAILY',
                    sensing_strategy = 'none'
WHERE id = '01JDS0LOS0APPLICATIONS01';

UPDATE datasets SET time_grain = 'DAILY',
                    sensing_strategy = 'none'
WHERE id = '01JDS0LOS0UNDERWRITING01';

UPDATE datasets SET time_grain = 'MONTHLY',
                    sensing_strategy = 'none'
WHERE id = '01JDS0INV0LOAN0POOLS0001';

-- =====================================================
-- 4. Seed schedule for existing pipeline versions
-- =====================================================

UPDATE pipeline_versions SET schedule_cron = '@daily',
                             catchup_enabled = FALSE,
                             max_active_runs = 1
WHERE id = '01JVER0SALES0REV0V100001';

UPDATE pipeline_versions SET schedule_cron = '@daily',
                             catchup_enabled = FALSE,
                             max_active_runs = 1
WHERE id = '01JVER0SALES0REV0V110001';

UPDATE pipeline_versions SET schedule_cron = '@daily',
                             catchup_enabled = FALSE,
                             max_active_runs = 1
WHERE id = '01JVER0CUST0360V010PRD01';

UPDATE pipeline_versions SET schedule_cron = '@weekly',
                             catchup_enabled = FALSE,
                             max_active_runs = 1
WHERE id = '01JVER0MKT0ATTR0V100PUB1';
