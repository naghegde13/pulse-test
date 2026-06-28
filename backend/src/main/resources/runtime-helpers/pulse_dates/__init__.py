"""
pulse_dates — runtime mnemonic resolver for PULSE-generated pipelines.

Business-day resolution is bundle-backed. Promoted runtime helpers must not read
Pulse Postgres runtime settings.
"""

from __future__ import annotations

import calendar as _calendar
import hashlib
import json
import os
import re
from datetime import date, datetime, timedelta
from functools import lru_cache
from pathlib import Path
from urllib.parse import urlparse
from typing import Iterable, Optional


def resolve_mnemonic(
    token: str,
    as_of: Optional[date] = None,
    calendar_id: str = "US-FED",
    fiscal_offset_months: int = 0,
    previous_run_date: Optional[date] = None,
    calendar_bundle_uri: Optional[str] = None,
    calendar_bundle_hash: Optional[str] = None,
) -> date:
    if token is None:
        raise ValueError("token is required")

    token = token.strip()
    if not token:
        raise ValueError("token must not be blank")

    if _ISO_DATE_RE.match(token):
        return date.fromisoformat(token)

    if as_of is None:
        as_of = date.today()

    upper = token.upper()
    head, offset = _split_offset(upper)
    head = _ALIAS.get(head, head)

    if head == "TODAY":
        _require_no_offset(token, offset, allow=False)
        return as_of
    if head == "T":
        return as_of + timedelta(days=offset)
    if head == "RUN_DATE":
        _require_no_offset(token, offset, allow=False)
        return as_of
    if head == "PREVIOUS_RUN_DATE":
        _require_no_offset(token, offset, allow=False)
        if previous_run_date is None:
            raise ValueError("PREVIOUS_RUN_DATE requested but previous_run_date was not provided")
        return previous_run_date
    if head == "BOW":
        return _add_days(_start_of_week(as_of), offset * 7)
    if head == "EOW":
        return _add_days(_end_of_week(as_of), offset * 7)
    if head == "SAME_DAY_LAST_WEEK":
        _require_no_offset(token, offset, allow=False)
        return as_of - timedelta(days=7)
    if head == "BOM":
        return _start_of_month(_add_months(as_of, offset))
    if head == "EOM":
        return _end_of_month(_add_months(as_of, offset))
    if head == "SAME_DAY_LAST_MONTH":
        _require_no_offset(token, offset, allow=False)
        return _add_months(as_of, -1)
    if head == "BOQ":
        return _start_of_quarter(_add_months(as_of, offset * 3))
    if head == "EOQ":
        return _end_of_quarter(_add_months(as_of, offset * 3))
    if head == "SAME_DAY_LAST_QUARTER":
        _require_no_offset(token, offset, allow=False)
        return _add_months(as_of, -3)
    if head == "BOH":
        return _start_of_half_year(_add_months(as_of, offset * 6))
    if head == "EOH":
        return _end_of_half_year(_add_months(as_of, offset * 6))
    if head == "BOY":
        return date(as_of.year + offset, 1, 1)
    if head == "EOY":
        return date(as_of.year + offset, 12, 31)
    if head == "SAME_DAY_LAST_YEAR":
        _require_no_offset(token, offset, allow=False)
        try:
            return as_of.replace(year=as_of.year - 1)
        except ValueError:
            return as_of.replace(year=as_of.year - 1, day=28)
    if head in ("BOFY", "EOFY"):
        fiscal_anchor = _add_months(as_of, -fiscal_offset_months)
        fiscal_year_start_calendar = date(fiscal_anchor.year, 1, 1)
        if head == "BOFY":
            return _add_months(fiscal_year_start_calendar, fiscal_offset_months).replace(day=1) + _years_offset(offset)
        fy_end = date(fiscal_anchor.year, 12, 31)
        return _add_months(fy_end, fiscal_offset_months) + _years_offset(offset)
    if head in ("BOFQ", "EOFQ"):
        fiscal_anchor = _add_months(as_of, -fiscal_offset_months)
        fq_start = _start_of_quarter(_add_months(fiscal_anchor, offset * 3))
        fq_end = _end_of_quarter(_add_months(fiscal_anchor, offset * 3))
        return _add_months(fq_start if head == "BOFQ" else fq_end, fiscal_offset_months)
    if head in ("BOFM", "EOFM"):
        m = _add_months(as_of, offset)
        return _start_of_month(m) if head == "BOFM" else _end_of_month(m)

    if head in {"FBOM", "LBOM", "PBD", "NBD"} or _NBDOM_RE.match(token.upper()):
        business_days = _business_days(
            calendar_id=calendar_id,
            calendar_bundle_uri=calendar_bundle_uri,
            calendar_bundle_hash=calendar_bundle_hash,
            as_of=as_of,
        )
        if head == "FBOM":
            _require_no_offset(token, offset, allow=False)
            return _first_business_day_of_month(as_of, business_days, calendar_id)
        if head == "LBOM":
            _require_no_offset(token, offset, allow=False)
            return _last_business_day_of_month(as_of, business_days, calendar_id)
        if head == "PBD":
            n = max(1, -offset) if offset < 0 else 1
            return _n_business_days_back(as_of, n, business_days, calendar_id)
        if head == "NBD":
            n = max(1, offset) if offset > 0 else 1
            return _n_business_days_forward(as_of, n, business_days, calendar_id)

        nbdom_match = _NBDOM_RE.match(token.upper())
        if nbdom_match:
            n = int(nbdom_match.group(1))
            return _nth_business_day_of_month(as_of, n, business_days, calendar_id)

    raise ValueError(f"Unknown mnemonic: {token!r}")


def business_days_between(
    start: date | str,
    end: date | str,
    calendar_id: str = "US-FED",
    calendar_bundle_uri: Optional[str] = None,
    calendar_bundle_hash: Optional[str] = None,
) -> int:
    start_date = _coerce_date(start)
    end_date = _coerce_date(end)
    if end_date < start_date:
        start_date, end_date = end_date, start_date
    business_days = _business_days(
        calendar_id=calendar_id,
        calendar_bundle_uri=calendar_bundle_uri,
        calendar_bundle_hash=calendar_bundle_hash,
        as_of=end_date,
    )
    return sum(1 for day in business_days if start_date < day <= end_date)


_ISO_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_OFFSET_RE = re.compile(r"^([A-Z_]+)([+-]\d+)?$")
_NBDOM_RE = re.compile(r"^NBDOM\((\d+)\)$")

_ALIAS = {
    "WTD_START": "BOW",
    "MTD_START": "BOM",
    "QTD_START": "BOQ",
    "YTD_START": "BOY",
    "FYTD_START": "BOFY",
    "LAST_COMPLETED_MONTH_START": "BOM-1",
    "LAST_COMPLETED_MONTH_END": "EOM-1",
    "LAST_COMPLETED_QUARTER_START": "BOQ-1",
    "LAST_COMPLETED_QUARTER_END": "EOQ-1",
}


def _split_offset(token: str) -> tuple[str, int]:
    if token in _ALIAS:
        token = _ALIAS[token]
    match = _OFFSET_RE.match(token)
    if not match:
        return token, 0
    head, off = match.group(1), match.group(2)
    return head, int(off) if off else 0


def _require_no_offset(token: str, offset: int, allow: bool) -> None:
    if not allow and offset != 0:
        raise ValueError(f"Mnemonic {token!r} does not accept an offset")


def _add_days(d: date, n: int) -> date:
    return d + timedelta(days=n)


def _add_months(d: date, n: int) -> date:
    total = d.year * 12 + (d.month - 1) + n
    new_year, new_month = divmod(total, 12)
    new_month += 1
    last_day = _calendar.monthrange(new_year, new_month)[1]
    return date(new_year, new_month, min(d.day, last_day))


def _years_offset(n: int) -> timedelta:
    return timedelta(days=365 * n)


def _start_of_week(d: date) -> date:
    return d - timedelta(days=d.isoweekday() - 1)


def _end_of_week(d: date) -> date:
    return _start_of_week(d) + timedelta(days=6)


def _start_of_month(d: date) -> date:
    return d.replace(day=1)


def _end_of_month(d: date) -> date:
    return d.replace(day=_calendar.monthrange(d.year, d.month)[1])


def _start_of_quarter(d: date) -> date:
    qstart_month = ((d.month - 1) // 3) * 3 + 1
    return date(d.year, qstart_month, 1)


def _end_of_quarter(d: date) -> date:
    return _end_of_month(_add_months(_start_of_quarter(d), 2))


def _start_of_half_year(d: date) -> date:
    return date(d.year, 1 if d.month <= 6 else 7, 1)


def _end_of_half_year(d: date) -> date:
    return date(d.year, 6 if d.month <= 6 else 12, 30 if d.month <= 6 else 31)


def _n_business_days_back(as_of: date, n: int, business_days: list[date], calendar_id: str) -> date:
    prior = [day for day in business_days if day < as_of]
    if len(prior) < n:
        raise ValueError(f"No business day found {n} BD before {as_of} on {calendar_id}")
    return prior[-n]


def _n_business_days_forward(as_of: date, n: int, business_days: list[date], calendar_id: str) -> date:
    future = [day for day in business_days if day > as_of]
    if len(future) < n:
        raise ValueError(f"No business day found {n} BD after {as_of} on {calendar_id}")
    return future[n - 1]


def _first_business_day_of_month(as_of: date, business_days: list[date], calendar_id: str) -> date:
    for day in business_days:
        if day.year == as_of.year and day.month == as_of.month:
            return day
    raise ValueError(f"No business day found in month of {as_of} on {calendar_id}")


def _last_business_day_of_month(as_of: date, business_days: list[date], calendar_id: str) -> date:
    days = [day for day in business_days if day.year == as_of.year and day.month == as_of.month]
    if not days:
        raise ValueError(f"No business day found in month of {as_of} on {calendar_id}")
    return days[-1]


def _nth_business_day_of_month(as_of: date, n: int, business_days: list[date], calendar_id: str) -> date:
    days = [day for day in business_days if day.year == as_of.year and day.month == as_of.month]
    if len(days) < n:
        raise ValueError(f"Month of {as_of} on {calendar_id} has fewer than {n} business days")
    return days[n - 1]


def _coerce_date(value: date | str) -> date:
    if isinstance(value, date):
        return value
    return date.fromisoformat(value.strip())


@lru_cache(maxsize=16)
def _load_bundle(bundle_uri: str, expected_hash: str) -> dict:
    raw_bytes = _read_bytes(bundle_uri)
    actual_hash = "sha256:" + hashlib.sha256(raw_bytes).hexdigest()
    if expected_hash and actual_hash != expected_hash:
        raise ValueError(
            f"Calendar bundle hash mismatch for {bundle_uri}: expected {expected_hash}, got {actual_hash}"
        )
    payload = json.loads(raw_bytes.decode("utf-8"))
    calendars = payload.get("calendars") or {}
    normalized = {}
    if isinstance(calendars, dict):
        for calendar_id, config in calendars.items():
            if isinstance(config, dict):
                raw_days = config.get("businessDays") or config.get("business_days") or config.get("dates") or []
            else:
                raw_days = config or []
            normalized[calendar_id] = sorted(_coerce_date(day) for day in raw_days)
    elif isinstance(calendars, list):
        for config in calendars:
            if not isinstance(config, dict):
                continue
            calendar_id = str(config.get("calendarId") or config.get("calendar_id") or "").strip()
            if not calendar_id:
                continue
            raw_days = config.get("businessDays") or config.get("business_days") or config.get("dates") or []
            normalized[calendar_id] = sorted(_coerce_date(day) for day in raw_days)
    payload["calendars"] = normalized
    return payload


def _business_days(
    calendar_id: str,
    calendar_bundle_uri: Optional[str],
    calendar_bundle_hash: Optional[str],
    as_of: date,
) -> list[date]:
    bundle_uri = _bundle_uri(calendar_bundle_uri)
    bundle_hash = _bundle_hash(calendar_bundle_hash)
    if not bundle_uri:
        raise ValueError(
            f"Business-day mnemonic requires a calendar bundle for calendar {calendar_id}; no bundle URI was provided"
        )
    bundle = _load_bundle(bundle_uri, bundle_hash)
    business_days = bundle.get("calendars", {}).get(calendar_id)
    if not business_days:
        raise ValueError(f"Calendar bundle {bundle_uri} does not contain calendar {calendar_id}")
    coverage_start = bundle.get("coverageStart")
    coverage_end = bundle.get("coverageEnd")
    if coverage_start and as_of < _coerce_date(coverage_start):
        raise ValueError(f"Calendar bundle coverage starts at {coverage_start}, cannot resolve {as_of}")
    if coverage_end and as_of > _coerce_date(coverage_end):
        raise ValueError(f"Calendar bundle coverage ends at {coverage_end}, cannot resolve {as_of}")
    return business_days


def _bundle_uri(explicit: Optional[str]) -> str:
    return (explicit or os.environ.get("PULSE_CALENDAR_BUNDLE_URI") or os.environ.get("PULSE_CALENDAR_BUNDLE_PATH") or "").strip()


def _bundle_hash(explicit: Optional[str]) -> str:
    return (explicit or os.environ.get("PULSE_CALENDAR_BUNDLE_HASH") or "").strip()


def _read_bytes(uri: str) -> bytes:
    parsed = urlparse(uri)
    if parsed.scheme in ("", "file"):
        path = Path(parsed.path if parsed.scheme == "file" else uri)
        return path.read_bytes()
    if parsed.scheme == "gs":
        from google.cloud import storage  # type: ignore

        client = storage.Client()
        blob = client.bucket(parsed.netloc).blob(parsed.path.lstrip("/"))
        return blob.download_as_bytes()
    if parsed.scheme == "s3":
        import boto3  # type: ignore

        body = boto3.client("s3").get_object(Bucket=parsed.netloc, Key=parsed.path.lstrip("/"))["Body"]
        return body.read()
    raise ValueError(f"Unsupported calendar bundle URI scheme: {uri}")
