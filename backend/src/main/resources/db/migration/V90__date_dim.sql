-- V90: date_dim — calendar dimension table backing the PULSE date-mnemonic system.
--
-- Two tables, by design:
--   date_dim                 — calendar-agnostic facts (one row per date 1900-01-01..2100-12-31)
--   date_dim_holiday_calendar — per-calendar (holiday_calendar_id, calendar_date) flags
--                               (US-FED, US-NYSE seeded; per-tenant calendar selection
--                               via domain.business_date_config.holiday_calendar_id)
--
-- Design choices:
--   - Fiscal columns are NOT in date_dim — fiscal year is per-tenant
--     (domain.business_date_config.fiscal_offset_months) and computed at runtime.
--   - prev_business_date / next_business_date / first_business_day_of_month /
--     last_business_day_of_month / business_day_of_month_seq / business_day_of_year_seq
--     are NOT pre-computed. They are derived at runtime by the pulse_dates resolver
--     via the idx_ddhc_business_day partial index (sub-ms per query). Pre-computing
--     them in V90 is too slow on a clean DB (correlated/window ops over 146k rows
--     run >7 min) and the on-demand cost during pipeline generation is negligible.

-- =============================================================================
-- 1. date_dim — calendar facts
-- =============================================================================

CREATE TABLE date_dim (
    date_key             INT          PRIMARY KEY,           -- YYYYMMDD surrogate
    calendar_date        DATE         NOT NULL UNIQUE,
    iso_date             VARCHAR(10)  NOT NULL,              -- "YYYY-MM-DD"

    year                 SMALLINT     NOT NULL,
    quarter              SMALLINT     NOT NULL,              -- 1..4
    half_year            SMALLINT     NOT NULL,              -- 1 (Jan-Jun) | 2 (Jul-Dec)
    month                SMALLINT     NOT NULL,              -- 1..12
    month_name           VARCHAR(9)   NOT NULL,
    month_short_name     VARCHAR(3)   NOT NULL,
    day_of_month         SMALLINT     NOT NULL,
    day_of_year          SMALLINT     NOT NULL,
    day_of_week          SMALLINT     NOT NULL,              -- 1=Mon..7=Sun (ISO)
    day_name             VARCHAR(9)   NOT NULL,
    week_of_year_iso     SMALLINT     NOT NULL,

    week_start_date      DATE         NOT NULL,
    week_end_date        DATE         NOT NULL,
    month_start_date     DATE         NOT NULL,
    month_end_date       DATE         NOT NULL,
    quarter_start_date   DATE         NOT NULL,
    quarter_end_date     DATE         NOT NULL,
    half_year_start_date DATE         NOT NULL,
    half_year_end_date   DATE         NOT NULL,
    year_start_date      DATE         NOT NULL,
    year_end_date        DATE         NOT NULL,

    same_day_last_week     DATE       NOT NULL,
    same_day_last_month    DATE       NOT NULL,
    same_day_last_quarter  DATE       NOT NULL,
    same_day_last_year     DATE       NOT NULL,

    is_leap_year         BOOLEAN      NOT NULL,
    is_weekend           BOOLEAN      NOT NULL,
    season               VARCHAR(6)   NOT NULL,

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_date_dim_year_month ON date_dim(year, month);
CREATE INDEX idx_date_dim_year ON date_dim(year);


INSERT INTO date_dim (
    date_key, calendar_date, iso_date,
    year, quarter, half_year, month, month_name, month_short_name,
    day_of_month, day_of_year, day_of_week, day_name, week_of_year_iso,
    week_start_date, week_end_date,
    month_start_date, month_end_date,
    quarter_start_date, quarter_end_date,
    half_year_start_date, half_year_end_date,
    year_start_date, year_end_date,
    same_day_last_week, same_day_last_month, same_day_last_quarter, same_day_last_year,
    is_leap_year, is_weekend, season
)
SELECT
    EXTRACT(YEAR FROM d)::INT * 10000
        + EXTRACT(MONTH FROM d)::INT * 100
        + EXTRACT(DAY FROM d)::INT,
    d,
    TO_CHAR(d, 'YYYY-MM-DD'),
    EXTRACT(YEAR FROM d)::SMALLINT,
    EXTRACT(QUARTER FROM d)::SMALLINT,
    CASE WHEN EXTRACT(MONTH FROM d) <= 6 THEN 1 ELSE 2 END::SMALLINT,
    EXTRACT(MONTH FROM d)::SMALLINT,
    TO_CHAR(d, 'FMMonth'),
    TO_CHAR(d, 'Mon'),
    EXTRACT(DAY FROM d)::SMALLINT,
    EXTRACT(DOY FROM d)::SMALLINT,
    EXTRACT(ISODOW FROM d)::SMALLINT,
    TO_CHAR(d, 'FMDay'),
    EXTRACT(WEEK FROM d)::SMALLINT,
    DATE_TRUNC('week', d)::DATE,
    (DATE_TRUNC('week', d) + INTERVAL '6 days')::DATE,
    DATE_TRUNC('month', d)::DATE,
    (DATE_TRUNC('month', d) + INTERVAL '1 month' - INTERVAL '1 day')::DATE,
    DATE_TRUNC('quarter', d)::DATE,
    (DATE_TRUNC('quarter', d) + INTERVAL '3 months' - INTERVAL '1 day')::DATE,
    MAKE_DATE(EXTRACT(YEAR FROM d)::INT,
              CASE WHEN EXTRACT(MONTH FROM d) <= 6 THEN 1 ELSE 7 END, 1),
    MAKE_DATE(EXTRACT(YEAR FROM d)::INT,
              CASE WHEN EXTRACT(MONTH FROM d) <= 6 THEN 6 ELSE 12 END,
              CASE WHEN EXTRACT(MONTH FROM d) <= 6 THEN 30 ELSE 31 END),
    DATE_TRUNC('year', d)::DATE,
    (DATE_TRUNC('year', d) + INTERVAL '1 year' - INTERVAL '1 day')::DATE,
    (d - INTERVAL '7 days')::DATE,
    (d - INTERVAL '1 month')::DATE,
    (d - INTERVAL '3 months')::DATE,
    (d - INTERVAL '1 year')::DATE,
    ((EXTRACT(YEAR FROM d)::INT % 4 = 0 AND EXTRACT(YEAR FROM d)::INT % 100 <> 0)
     OR EXTRACT(YEAR FROM d)::INT % 400 = 0),
    EXTRACT(ISODOW FROM d) IN (6, 7),
    CASE
        WHEN EXTRACT(MONTH FROM d) IN (3, 4, 5)   THEN 'Spring'
        WHEN EXTRACT(MONTH FROM d) IN (6, 7, 8)   THEN 'Summer'
        WHEN EXTRACT(MONTH FROM d) IN (9, 10, 11) THEN 'Fall'
        ELSE 'Winter'
    END
FROM generate_series('1900-01-01'::DATE, '2100-12-31'::DATE, INTERVAL '1 day') AS d;


-- =============================================================================
-- 2. date_dim_holiday_calendar — per-calendar holiday + business-day flags only.
--    Business-day-RELATIVE columns (prev/next BD, first/last BD of month, BD seq)
--    are computed on-demand by the resolver. The partial index makes those
--    queries sub-ms.
-- =============================================================================

CREATE TABLE date_dim_holiday_calendar (
    calendar_date         DATE         NOT NULL,
    holiday_calendar_id   VARCHAR(20)  NOT NULL,        -- 'US-FED' | 'US-NYSE'
    is_holiday            BOOLEAN      NOT NULL DEFAULT FALSE,
    is_early_close        BOOLEAN      NOT NULL DEFAULT FALSE,  -- NYSE 1pm-close days
    holiday_name          VARCHAR(100),
    is_business_day       BOOLEAN      NOT NULL,

    PRIMARY KEY (calendar_date, holiday_calendar_id),
    CONSTRAINT fk_ddhc_calendar_date
        FOREIGN KEY (calendar_date) REFERENCES date_dim(calendar_date) ON DELETE CASCADE,
    CONSTRAINT ck_ddhc_holiday_calendar_id
        CHECK (holiday_calendar_id IN ('US-FED', 'US-NYSE'))
);

-- Partial index used by the resolver for prev/next-BD lookups.
CREATE INDEX idx_ddhc_business_day
    ON date_dim_holiday_calendar(holiday_calendar_id, calendar_date)
    WHERE is_business_day = TRUE;


-- -----------------------------------------------------------------------------
-- Holiday computation helpers
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION pulse_easter_date(year_in INT) RETURNS DATE
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    a INT; b INT; c INT; d INT; e INT; f INT; g INT;
    h INT; i INT; k INT; l INT; m INT; n INT;
    month_out INT; day_out INT;
BEGIN
    -- Anonymous Gregorian (Meeus/Jones/Butcher) algorithm.
    a := year_in % 19;
    b := year_in / 100;
    c := year_in % 100;
    d := b / 4;
    e := b % 4;
    f := (b + 8) / 25;
    g := (b - f + 1) / 3;
    h := (19 * a + b - d - g + 15) % 30;
    i := c / 4;
    k := c % 4;
    l := (32 + 2 * e + 2 * i - h - k) % 7;
    m := (a + 11 * h + 22 * l) / 451;
    n := h + l - 7 * m + 114;
    month_out := n / 31;
    day_out := (n % 31) + 1;
    RETURN MAKE_DATE(year_in, month_out, day_out);
END;
$$;

CREATE OR REPLACE FUNCTION pulse_observed_date(d DATE) RETURNS DATE
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    -- US-FED weekend observance: Sat → Fri prev, Sun → Mon next.
    IF EXTRACT(ISODOW FROM d) = 6 THEN RETURN d - INTERVAL '1 day';
    ELSIF EXTRACT(ISODOW FROM d) = 7 THEN RETURN d + INTERVAL '1 day';
    ELSE RETURN d; END IF;
END;
$$;

CREATE OR REPLACE FUNCTION pulse_nth_weekday_of_month(
    year_in INT, month_in INT, weekday_iso INT, nth INT
) RETURNS DATE
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    first_of_month DATE;
    first_target DATE;
    offset_days INT;
BEGIN
    first_of_month := MAKE_DATE(year_in, month_in, 1);
    offset_days := (weekday_iso - EXTRACT(ISODOW FROM first_of_month)::INT + 7) % 7;
    first_target := first_of_month + offset_days * INTERVAL '1 day';
    RETURN first_target + (nth - 1) * INTERVAL '7 days';
END;
$$;

CREATE OR REPLACE FUNCTION pulse_last_weekday_of_month(
    year_in INT, month_in INT, weekday_iso INT
) RETURNS DATE
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    last_of_month DATE;
    offset_days INT;
BEGIN
    last_of_month := (MAKE_DATE(year_in, month_in, 1) + INTERVAL '1 month' - INTERVAL '1 day')::DATE;
    offset_days := (EXTRACT(ISODOW FROM last_of_month)::INT - weekday_iso + 7) % 7;
    RETURN last_of_month - offset_days * INTERVAL '1 day';
END;
$$;


-- -----------------------------------------------------------------------------
-- Build holiday rows for both calendars in a temp table first, then bulk-insert.
-- ~3,200 holiday rows total (200 years × ~16 holidays per calendar avg).
-- -----------------------------------------------------------------------------

CREATE TEMP TABLE tmp_holidays (
    calendar_date        DATE NOT NULL,
    holiday_calendar_id  VARCHAR(20) NOT NULL,
    is_holiday           BOOLEAN NOT NULL DEFAULT FALSE,
    is_early_close       BOOLEAN NOT NULL DEFAULT FALSE,
    holiday_name         VARCHAR(100),
    PRIMARY KEY (calendar_date, holiday_calendar_id)
);

DO $$
DECLARE
    yr INT;
    h DATE;
BEGIN
    FOR yr IN 1900..2100 LOOP
        -- ===== US-FED =====
        INSERT INTO tmp_holidays VALUES (pulse_observed_date(MAKE_DATE(yr,1,1)), 'US-FED', TRUE, FALSE, 'New Year''s Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_nth_weekday_of_month(yr,1,1,3),    'US-FED', TRUE, FALSE, 'Martin Luther King Jr. Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_nth_weekday_of_month(yr,2,1,3),    'US-FED', TRUE, FALSE, 'Washington''s Birthday') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_last_weekday_of_month(yr,5,1),     'US-FED', TRUE, FALSE, 'Memorial Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_observed_date(MAKE_DATE(yr,6,19)), 'US-FED', TRUE, FALSE, 'Juneteenth') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_observed_date(MAKE_DATE(yr,7,4)),  'US-FED', TRUE, FALSE, 'Independence Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_nth_weekday_of_month(yr,9,1,1),    'US-FED', TRUE, FALSE, 'Labor Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_nth_weekday_of_month(yr,10,1,2),   'US-FED', TRUE, FALSE, 'Columbus Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_observed_date(MAKE_DATE(yr,11,11)),'US-FED', TRUE, FALSE, 'Veterans Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_nth_weekday_of_month(yr,11,4,4),   'US-FED', TRUE, FALSE, 'Thanksgiving') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_observed_date(MAKE_DATE(yr,12,25)),'US-FED', TRUE, FALSE, 'Christmas Day') ON CONFLICT DO NOTHING;

        -- ===== US-NYSE =====
        -- Same federal set EXCEPT Columbus Day, Veterans Day. Plus Good Friday.
        INSERT INTO tmp_holidays VALUES (pulse_observed_date(MAKE_DATE(yr,1,1)),  'US-NYSE', TRUE, FALSE, 'New Year''s Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_nth_weekday_of_month(yr,1,1,3),    'US-NYSE', TRUE, FALSE, 'Martin Luther King Jr. Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_nth_weekday_of_month(yr,2,1,3),    'US-NYSE', TRUE, FALSE, 'Washington''s Birthday') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_easter_date(yr) - INTERVAL '2 days','US-NYSE', TRUE, FALSE, 'Good Friday') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_last_weekday_of_month(yr,5,1),     'US-NYSE', TRUE, FALSE, 'Memorial Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_observed_date(MAKE_DATE(yr,6,19)), 'US-NYSE', TRUE, FALSE, 'Juneteenth') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_observed_date(MAKE_DATE(yr,7,4)),  'US-NYSE', TRUE, FALSE, 'Independence Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_nth_weekday_of_month(yr,9,1,1),    'US-NYSE', TRUE, FALSE, 'Labor Day') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_nth_weekday_of_month(yr,11,4,4),   'US-NYSE', TRUE, FALSE, 'Thanksgiving') ON CONFLICT DO NOTHING;
        INSERT INTO tmp_holidays VALUES (pulse_observed_date(MAKE_DATE(yr,12,25)),'US-NYSE', TRUE, FALSE, 'Christmas Day') ON CONFLICT DO NOTHING;

        -- NYSE early-close days (still business days, just close at 1pm).
        h := pulse_nth_weekday_of_month(yr,11,4,4) + INTERVAL '1 day';
        INSERT INTO tmp_holidays VALUES (h, 'US-NYSE', FALSE, TRUE, 'Day after Thanksgiving (early close)') ON CONFLICT DO NOTHING;
        h := MAKE_DATE(yr,12,24);
        IF EXTRACT(ISODOW FROM h) BETWEEN 1 AND 5 THEN
            INSERT INTO tmp_holidays VALUES (h, 'US-NYSE', FALSE, TRUE, 'Christmas Eve (early close)') ON CONFLICT DO NOTHING;
        END IF;
    END LOOP;
END;
$$;


-- -----------------------------------------------------------------------------
-- Build the full date_dim_holiday_calendar in one INSERT.
-- For each (date, calendar_id) pair: pull holiday/early-close from tmp_holidays
-- if present, else mark as a non-holiday weekday/weekend. is_business_day is
-- TRUE if not weekend AND not full-close holiday (early-close days are still BD).
-- -----------------------------------------------------------------------------

INSERT INTO date_dim_holiday_calendar (
    calendar_date, holiday_calendar_id, is_holiday, is_early_close,
    holiday_name, is_business_day
)
SELECT
    dd.calendar_date,
    cal.holiday_calendar_id,
    COALESCE(h.is_holiday, FALSE) AS is_holiday,
    COALESCE(h.is_early_close, FALSE) AS is_early_close,
    h.holiday_name,
    -- Business day: not weekend AND not a full-close holiday.
    -- Early-close days remain business days.
    NOT dd.is_weekend AND NOT COALESCE(h.is_holiday, FALSE) AS is_business_day
FROM date_dim dd
CROSS JOIN (VALUES ('US-FED'), ('US-NYSE')) AS cal(holiday_calendar_id)
LEFT JOIN tmp_holidays h
    ON h.calendar_date = dd.calendar_date
   AND h.holiday_calendar_id = cal.holiday_calendar_id;

DROP TABLE tmp_holidays;
