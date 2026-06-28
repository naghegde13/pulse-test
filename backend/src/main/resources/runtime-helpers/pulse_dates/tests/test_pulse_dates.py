"""
Tests for pulse_dates.resolve_mnemonic — pure-calendar mnemonics only.
Business-day mnemonics (PBD/NBD/FBOM/LBOM/NBDOM) require a live Postgres with
date_dim_holiday_calendar populated; those are tested in the integration suite.

Run: python -m pytest test_pulse_dates.py -v
"""

import json
from datetime import date

import pytest

from pulse_dates import business_days_between, resolve_mnemonic


# ---------------------------------------------------------------------------
# ISO-date pass-through
# ---------------------------------------------------------------------------

class TestIsoPassthrough:
    def test_iso_date_returns_unchanged(self):
        assert resolve_mnemonic("2026-04-15") == date(2026, 4, 15)

    def test_iso_date_ignores_as_of(self):
        # Literal must be returned regardless of as_of.
        assert resolve_mnemonic("2024-01-01", as_of=date(2099, 12, 31)) == date(2024, 1, 1)

    def test_iso_date_with_whitespace_trimmed(self):
        assert resolve_mnemonic("  2026-04-15  ") == date(2026, 4, 15)


# ---------------------------------------------------------------------------
# Today-relative
# ---------------------------------------------------------------------------

class TestTodayRelative:
    AS_OF = date(2026, 4, 15)

    def test_today(self):
        assert resolve_mnemonic("TODAY", as_of=self.AS_OF) == self.AS_OF

    def test_run_date(self):
        assert resolve_mnemonic("RUN_DATE", as_of=self.AS_OF) == self.AS_OF

    def test_t_minus_n(self):
        assert resolve_mnemonic("T-1", as_of=self.AS_OF) == date(2026, 4, 14)
        assert resolve_mnemonic("T-7", as_of=self.AS_OF) == date(2026, 4, 8)

    def test_t_plus_n(self):
        assert resolve_mnemonic("T+1", as_of=self.AS_OF) == date(2026, 4, 16)
        assert resolve_mnemonic("T+30", as_of=self.AS_OF) == date(2026, 5, 15)

    def test_today_rejects_offset(self):
        with pytest.raises(ValueError):
            resolve_mnemonic("TODAY-1", as_of=self.AS_OF)

    def test_previous_run_date_required(self):
        with pytest.raises(ValueError, match="PREVIOUS_RUN_DATE"):
            resolve_mnemonic("PREVIOUS_RUN_DATE", as_of=self.AS_OF)

    def test_previous_run_date_supplied(self):
        prev = date(2026, 4, 14)
        assert resolve_mnemonic("PREVIOUS_RUN_DATE", as_of=self.AS_OF, previous_run_date=prev) == prev


# ---------------------------------------------------------------------------
# Week
# ---------------------------------------------------------------------------

class TestWeek:
    # 2026-04-15 is a Wednesday
    AS_OF = date(2026, 4, 15)

    def test_bow(self):
        # Week start = Monday 2026-04-13
        assert resolve_mnemonic("BOW", as_of=self.AS_OF) == date(2026, 4, 13)

    def test_eow(self):
        # Week end = Sunday 2026-04-19
        assert resolve_mnemonic("EOW", as_of=self.AS_OF) == date(2026, 4, 19)

    def test_bow_minus_1(self):
        # Previous week's Monday = 2026-04-06
        assert resolve_mnemonic("BOW-1", as_of=self.AS_OF) == date(2026, 4, 6)

    def test_eow_plus_1(self):
        # Next week's Sunday = 2026-04-26
        assert resolve_mnemonic("EOW+1", as_of=self.AS_OF) == date(2026, 4, 26)

    def test_same_day_last_week(self):
        assert resolve_mnemonic("SAME_DAY_LAST_WEEK", as_of=self.AS_OF) == date(2026, 4, 8)

    def test_wtd_start_alias(self):
        assert resolve_mnemonic("WTD_START", as_of=self.AS_OF) == date(2026, 4, 13)


# ---------------------------------------------------------------------------
# Month
# ---------------------------------------------------------------------------

class TestMonth:
    AS_OF = date(2026, 4, 15)

    def test_bom(self):
        assert resolve_mnemonic("BOM", as_of=self.AS_OF) == date(2026, 4, 1)

    def test_eom(self):
        assert resolve_mnemonic("EOM", as_of=self.AS_OF) == date(2026, 4, 30)

    def test_bom_minus_1(self):
        assert resolve_mnemonic("BOM-1", as_of=self.AS_OF) == date(2026, 3, 1)

    def test_bom_minus_12(self):
        assert resolve_mnemonic("BOM-12", as_of=self.AS_OF) == date(2025, 4, 1)

    def test_eom_minus_1(self):
        # Last day of March 2026
        assert resolve_mnemonic("EOM-1", as_of=self.AS_OF) == date(2026, 3, 31)

    def test_eom_plus_1(self):
        # End of May 2026
        assert resolve_mnemonic("EOM+1", as_of=self.AS_OF) == date(2026, 5, 31)

    def test_eom_february_non_leap(self):
        as_of = date(2025, 2, 15)
        assert resolve_mnemonic("EOM", as_of=as_of) == date(2025, 2, 28)

    def test_eom_february_leap(self):
        as_of = date(2024, 2, 15)
        assert resolve_mnemonic("EOM", as_of=as_of) == date(2024, 2, 29)

    def test_same_day_last_month(self):
        assert resolve_mnemonic("SAME_DAY_LAST_MONTH", as_of=self.AS_OF) == date(2026, 3, 15)

    def test_same_day_last_month_clamps_short(self):
        # March 31 → February has 28 days; clamp to Feb 28.
        as_of = date(2026, 3, 31)
        assert resolve_mnemonic("SAME_DAY_LAST_MONTH", as_of=as_of) == date(2026, 2, 28)

    def test_mtd_start_alias(self):
        assert resolve_mnemonic("MTD_START", as_of=self.AS_OF) == date(2026, 4, 1)

    def test_last_completed_month_start(self):
        assert resolve_mnemonic("LAST_COMPLETED_MONTH_START", as_of=self.AS_OF) == date(2026, 3, 1)

    def test_last_completed_month_end(self):
        assert resolve_mnemonic("LAST_COMPLETED_MONTH_END", as_of=self.AS_OF) == date(2026, 3, 31)


# ---------------------------------------------------------------------------
# Quarter
# ---------------------------------------------------------------------------

class TestQuarter:
    AS_OF = date(2026, 4, 15)  # Q2

    def test_boq(self):
        # Q2 starts April 1
        assert resolve_mnemonic("BOQ", as_of=self.AS_OF) == date(2026, 4, 1)

    def test_eoq(self):
        # Q2 ends June 30
        assert resolve_mnemonic("EOQ", as_of=self.AS_OF) == date(2026, 6, 30)

    def test_boq_minus_1(self):
        # Q1 starts January 1
        assert resolve_mnemonic("BOQ-1", as_of=self.AS_OF) == date(2026, 1, 1)

    def test_eoq_minus_1(self):
        assert resolve_mnemonic("EOQ-1", as_of=self.AS_OF) == date(2026, 3, 31)

    def test_boq_plus_1(self):
        # Q3 starts July 1
        assert resolve_mnemonic("BOQ+1", as_of=self.AS_OF) == date(2026, 7, 1)

    def test_qtd_start_alias(self):
        assert resolve_mnemonic("QTD_START", as_of=self.AS_OF) == date(2026, 4, 1)

    def test_last_completed_quarter_end(self):
        assert resolve_mnemonic("LAST_COMPLETED_QUARTER_END", as_of=self.AS_OF) == date(2026, 3, 31)

    def test_same_day_last_quarter(self):
        assert resolve_mnemonic("SAME_DAY_LAST_QUARTER", as_of=self.AS_OF) == date(2026, 1, 15)


# ---------------------------------------------------------------------------
# Half-year
# ---------------------------------------------------------------------------

class TestHalfYear:
    def test_boh_first_half(self):
        assert resolve_mnemonic("BOH", as_of=date(2026, 4, 15)) == date(2026, 1, 1)

    def test_boh_second_half(self):
        assert resolve_mnemonic("BOH", as_of=date(2026, 8, 15)) == date(2026, 7, 1)

    def test_eoh_first_half(self):
        assert resolve_mnemonic("EOH", as_of=date(2026, 4, 15)) == date(2026, 6, 30)

    def test_eoh_second_half(self):
        assert resolve_mnemonic("EOH", as_of=date(2026, 8, 15)) == date(2026, 12, 31)


# ---------------------------------------------------------------------------
# Year
# ---------------------------------------------------------------------------

class TestYear:
    AS_OF = date(2026, 4, 15)

    def test_boy(self):
        assert resolve_mnemonic("BOY", as_of=self.AS_OF) == date(2026, 1, 1)

    def test_eoy(self):
        assert resolve_mnemonic("EOY", as_of=self.AS_OF) == date(2026, 12, 31)

    def test_boy_minus_3(self):
        assert resolve_mnemonic("BOY-3", as_of=self.AS_OF) == date(2023, 1, 1)

    def test_ytd_start_alias(self):
        assert resolve_mnemonic("YTD_START", as_of=self.AS_OF) == date(2026, 1, 1)

    def test_same_day_last_year(self):
        assert resolve_mnemonic("SAME_DAY_LAST_YEAR", as_of=self.AS_OF) == date(2025, 4, 15)

    def test_same_day_last_year_leap_to_non_leap(self):
        # Feb 29 2024 → Feb 28 2023 (clamp).
        as_of = date(2024, 2, 29)
        assert resolve_mnemonic("SAME_DAY_LAST_YEAR", as_of=as_of) == date(2023, 2, 28)


# ---------------------------------------------------------------------------
# Fiscal (offset = 9 → fiscal year starts October 1, US federal style)
# ---------------------------------------------------------------------------

class TestFiscal:
    AS_OF = date(2026, 4, 15)
    FISCAL_OFFSET = 9  # FY starts Oct 1

    def test_bofq(self):
        # The "fiscal anchor" for as_of=2026-04-15, offset=9 is 2025-07-15;
        # quarter start of that = 2025-07-01; shifted forward by 9 months = 2026-04-01.
        assert resolve_mnemonic(
            "BOFQ", as_of=self.AS_OF, fiscal_offset_months=self.FISCAL_OFFSET
        ) == date(2026, 4, 1)

    def test_bofm(self):
        # BOFM is calendar-month boundary for the fiscal_offset-shifted month (which
        # in 12-month aligned fiscal years equals the calendar month).
        assert resolve_mnemonic(
            "BOFM", as_of=self.AS_OF, fiscal_offset_months=self.FISCAL_OFFSET
        ) == date(2026, 4, 1)


# ---------------------------------------------------------------------------
# Bad input
# ---------------------------------------------------------------------------

class TestBadInput:
    def test_unknown_mnemonic(self):
        with pytest.raises(ValueError, match="Unknown mnemonic"):
            resolve_mnemonic("MAGIC_DATE", as_of=date(2026, 4, 15))

    def test_blank(self):
        with pytest.raises(ValueError, match="must not be blank"):
            resolve_mnemonic("", as_of=date(2026, 4, 15))

    def test_none(self):
        with pytest.raises(ValueError, match="required"):
            resolve_mnemonic(None, as_of=date(2026, 4, 15))


class TestBundleBackedBusinessDays:
    @staticmethod
    def _bundle(tmp_path):
        bundle = {
            "schemaVersion": "pulse.calendar_bundle.v1",
            "coverageStart": "2026-03-01",
            "coverageEnd": "2026-03-31",
            "calendars": {
                "US-FED": {
                    "businessDays": [
                        "2026-03-02",
                        "2026-03-03",
                        "2026-03-04",
                        "2026-03-05",
                        "2026-03-06",
                        "2026-03-09",
                        "2026-03-10",
                        "2026-03-11",
                        "2026-03-12",
                    ]
                }
            },
        }
        path = tmp_path / "calendar-bundle.json"
        path.write_text(json.dumps(bundle), encoding="utf-8")
        return path

    def test_previous_business_day_uses_bundle(self, tmp_path):
        bundle = self._bundle(tmp_path)
        assert resolve_mnemonic(
            "PBD",
            as_of=date(2026, 3, 10),
            calendar_bundle_uri=str(bundle),
        ) == date(2026, 3, 9)

    def test_next_business_day_offset_uses_bundle(self, tmp_path):
        bundle = self._bundle(tmp_path)
        assert resolve_mnemonic(
            "NBD+2",
            as_of=date(2026, 3, 6),
            calendar_bundle_uri=str(bundle),
        ) == date(2026, 3, 10)

    def test_bundle_hash_is_enforced(self, tmp_path):
        bundle = self._bundle(tmp_path)
        with pytest.raises(ValueError, match="hash mismatch"):
            resolve_mnemonic(
                "NBD",
                as_of=date(2026, 3, 6),
                calendar_bundle_uri=str(bundle),
                calendar_bundle_hash="sha256:not-the-real-hash",
            )

    def test_business_days_between_uses_bundle(self, tmp_path):
        bundle = self._bundle(tmp_path)
        assert business_days_between(
            "2026-03-02",
            "2026-03-06",
            calendar_bundle_uri=str(bundle),
        ) == 4
