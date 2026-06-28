"""Airflow-native time-state helper for AdvanceTimeDimension."""

from __future__ import annotations

import hashlib
import json
from datetime import date, datetime, time, timedelta
from pathlib import Path
from urllib.parse import urlparse
from zoneinfo import ZoneInfo

from pulse_dates import resolve_mnemonic

try:
    from airflow.models import BaseOperator
except Exception:  # pragma: no cover - unit tests run without Airflow installed.
    BaseOperator = None


if BaseOperator is not None:
    class AdvanceTimeDimensionOperator(BaseOperator):
        """Airflow operator wrapper for the portable AdvanceTimeDimension helper."""

        template_fields = (
            "variable_key",
            "calendar_bundle_uri",
            "calendar_bundle_hash",
            "evidence_prefix",
            "requested_asof_expr",
            "initial_value",
            "notes_template",
            "advanced_by",
        )

        def __init__(self, **kwargs):
            task_id = kwargs.pop("task_id")
            pool = kwargs.pop("pool", None)
            pool_slots = kwargs.pop("pool_slots", 1)
            super().__init__(task_id=task_id, pool=pool, pool_slots=pool_slots)
            self.advance_kwargs = kwargs
            for key, value in kwargs.items():
                setattr(self, key, value)

        def execute(self, context):
            return advance_time_dimension(**self.advance_kwargs, **context)
else:
    class AdvanceTimeDimensionOperator:
        """Import-safe placeholder used when Airflow is unavailable in unit tests."""

        def __init__(self, *args, **kwargs):
            raise RuntimeError("AdvanceTimeDimensionOperator requires Apache Airflow")


def advance_time_dimension(
    *,
    state_binding_ref,
    variable_key,
    calendar_binding_ref,
    calendar_bundle_uri,
    calendar_bundle_hash="",
    calendar_id="US-FED",
    advance_mode="next_interval",
    requested_asof_expr=None,
    replay_policy="reject_backward",
    evidence_prefix="",
    initial_value=None,
    initialization_policy="require_existing",
    concurrency_policy="serialized_airflow",
    target_scope="dataset",
    grain="DAILY_BUSINESS_DAY",
    timezone="America/New_York",
    evidence_required=True,
    notes_template="",
    source="AdvanceTimeDimension",
    advanced_by="airflow:{{ dag.dag_id }}",
    advance_config=None,
    variable_getter=None,
    variable_setter=None,
    evidence_reader=None,
    evidence_writer=None,
    context_resolver=None,
    **context,
):
    if not variable_key:
        raise ValueError("AdvanceTimeDimension requires variable_key")
    if not state_binding_ref:
        raise ValueError("AdvanceTimeDimension requires state_binding_ref")
    if not evidence_prefix:
        raise ValueError("AdvanceTimeDimension requires evidence_prefix")

    variable_getter = variable_getter or _variable_getter
    variable_setter = variable_setter or _variable_setter
    evidence_reader = evidence_reader or _read_json_uri
    evidence_writer = evidence_writer or _write_json_uri
    context_resolver = context_resolver or _context_metadata
    advance_config = advance_config or {}

    metadata = context_resolver(context)
    run_key = metadata["run_key"]
    attempt_key = metadata["attempt_key"]
    actor = _render_template(advanced_by, metadata)
    notes = _render_template(notes_template or "", metadata)
    run_evidence_path = _evidence_path(evidence_prefix, state_binding_ref, f"{run_key}.json")
    attempt_evidence_path = _evidence_path(evidence_prefix, state_binding_ref, f"attempts/{attempt_key}.json")

    existing_state = _parse_state(variable_getter(variable_key))
    prior_evidence = evidence_reader(run_evidence_path)
    if prior_evidence is not None:
        if _state_matches_evidence(existing_state, prior_evidence):
            duplicate = dict(prior_evidence)
            duplicate["attemptKey"] = attempt_key
            duplicate["duplicateAttempt"] = True
            if evidence_required:
                evidence_writer(attempt_evidence_path, duplicate)
            return duplicate
        raise RuntimeError(
            f"AdvanceTimeDimension evidence/state mismatch for {state_binding_ref}; refusing duplicate replay"
        )

    current_value = _current_value(existing_state, timezone)
    action = "advanced"
    if current_value is None:
        if initialization_policy != "allow_projected_initial_value" or initial_value in (None, ""):
            rejection = _rejection_payload(
                state_binding_ref,
                variable_key,
                target_scope,
                grain,
                timezone,
                calendar_binding_ref,
                concurrency_policy,
                run_key,
                attempt_key,
                "MISSING_CURRENT_STATE",
                source,
                actor,
                notes,
            )
            if evidence_required:
                evidence_writer(attempt_evidence_path, rejection)
            raise ValueError(
                f"AdvanceTimeDimension missing current state for {state_binding_ref}; initialization policy is {initialization_policy}"
            )
        target_value = _coerce_value(initial_value, timezone)
        action = "initialized"
    else:
        target_value = _resolve_target_value(
            current_value=current_value,
            requested_asof_expr=requested_asof_expr,
            advance_mode=advance_mode,
            grain=grain,
            timezone=timezone,
            calendar_id=calendar_id,
            calendar_bundle_uri=calendar_bundle_uri,
            calendar_bundle_hash=calendar_bundle_hash,
            advance_config=advance_config,
        )
        if target_value < current_value and replay_policy != "allow_backward":
            rejection = _rejection_payload(
                state_binding_ref,
                variable_key,
                target_scope,
                grain,
                timezone,
                calendar_binding_ref,
                concurrency_policy,
                run_key,
                attempt_key,
                "BACKWARD_ADVANCE_REJECTED",
                source,
                actor,
                notes,
                previous_value=_format_value(current_value, grain, timezone),
                requested_value=_format_value(target_value, grain, timezone),
            )
            if evidence_required:
                evidence_writer(run_evidence_path, rejection)
                evidence_writer(attempt_evidence_path, rejection)
            raise ValueError(
                f"AdvanceTimeDimension rejected backward advance for {state_binding_ref}: "
                f"{_format_value(target_value, grain, timezone)} <= {_format_value(current_value, grain, timezone)}"
            )
        if target_value == current_value and replay_policy != "allow_same_value":
            rejection = _rejection_payload(
                state_binding_ref,
                variable_key,
                target_scope,
                grain,
                timezone,
                calendar_binding_ref,
                concurrency_policy,
                run_key,
                attempt_key,
                "NON_FORWARD_ADVANCE_REJECTED",
                source,
                actor,
                notes,
                previous_value=_format_value(current_value, grain, timezone),
                requested_value=_format_value(target_value, grain, timezone),
            )
            if evidence_required:
                evidence_writer(run_evidence_path, rejection)
                evidence_writer(attempt_evidence_path, rejection)
            raise ValueError(
                f"AdvanceTimeDimension rejected non-forward advance for {state_binding_ref}: "
                f"{_format_value(target_value, grain, timezone)}"
            )
        if target_value < current_value:
            action = "replayed"

    next_state = {
        "stateBindingRef": state_binding_ref,
        "variableKey": variable_key,
        "calendarBindingRef": calendar_binding_ref,
        "targetScope": target_scope,
        "grain": grain,
        "timezone": timezone,
        "currentValue": _format_value(target_value, grain, timezone),
        "updatedAt": metadata["observed_at"],
        "runKey": run_key,
        "attemptKey": attempt_key,
        "source": source,
        "advancedBy": actor,
        "notes": notes,
    }
    payload = {
        "stateBindingRef": state_binding_ref,
        "variableKey": variable_key,
        "calendarBindingRef": calendar_binding_ref,
        "calendarBundleUri": calendar_bundle_uri,
        "calendarBundleHash": calendar_bundle_hash,
        "targetScope": target_scope,
        "grain": grain,
        "timezone": timezone,
        "concurrencyPolicy": concurrency_policy,
        "action": action,
        "previousValue": _format_value(current_value, grain, timezone) if current_value else None,
        "newValue": _format_value(target_value, grain, timezone),
        "runKey": run_key,
        "attemptKey": attempt_key,
        "source": source,
        "advancedBy": actor,
        "notes": notes,
        "evidencePath": run_evidence_path,
        "attemptEvidencePath": attempt_evidence_path,
        "observedAt": metadata["observed_at"],
    }
    variable_setter(variable_key, next_state)
    if evidence_required:
        try:
            evidence_writer(run_evidence_path, payload)
            evidence_writer(attempt_evidence_path, payload)
        except Exception:
            variable_setter(variable_key, existing_state or {})
            raise
    return payload


def _resolve_target_value(
    *,
    current_value,
    requested_asof_expr,
    advance_mode,
    grain,
    timezone,
    calendar_id,
    calendar_bundle_uri,
    calendar_bundle_hash,
    advance_config,
):
    if requested_asof_expr not in (None, ""):
        return _resolve_requested_value(
            requested_asof_expr,
            current_value,
            timezone,
            calendar_id,
            calendar_bundle_uri,
            calendar_bundle_hash,
        )
    if advance_mode != "next_interval":
        raise ValueError(f"Unsupported advance_mode: {advance_mode}")
    return _compute_next_value(
        current_value=current_value,
        grain=grain,
        timezone=timezone,
        calendar_id=calendar_id,
        calendar_bundle_uri=calendar_bundle_uri,
        calendar_bundle_hash=calendar_bundle_hash,
        advance_config=advance_config,
    )


def _resolve_requested_value(expr, current_value, timezone, calendar_id, calendar_bundle_uri, calendar_bundle_hash):
    if isinstance(expr, (date, datetime)):
        return _coerce_value(expr, timezone)
    text = str(expr).strip()
    try:
        return _coerce_value(text, timezone)
    except ValueError:
        resolved = resolve_mnemonic(
            text,
            as_of=current_value.astimezone(ZoneInfo(timezone)).date(),
            calendar_id=calendar_id,
            calendar_bundle_uri=calendar_bundle_uri,
            calendar_bundle_hash=calendar_bundle_hash,
        )
        return datetime.combine(resolved, time.min, tzinfo=ZoneInfo(timezone))


def _compute_next_value(*, current_value, grain, timezone, calendar_id, calendar_bundle_uri, calendar_bundle_hash, advance_config):
    current_local = current_value.astimezone(ZoneInfo(timezone))
    grain_upper = (grain or "DAILY").upper()
    if grain_upper == "DAILY":
        return current_local + timedelta(days=1)
    if grain_upper == "DAILY_BUSINESS_DAY":
        next_day = resolve_mnemonic(
            "NBD",
            as_of=current_local.date(),
            calendar_id=calendar_id,
            calendar_bundle_uri=calendar_bundle_uri,
            calendar_bundle_hash=calendar_bundle_hash,
        )
        return datetime.combine(next_day, current_local.timetz().replace(tzinfo=ZoneInfo(timezone)), tzinfo=ZoneInfo(timezone))
    if grain_upper == "WEEKLY":
        days = advance_config.get("days") or ["MON"]
        return _next_weekly_day(current_local, days)
    if grain_upper == "BEG_OF_MONTH":
        month = _add_months(current_local.date(), 1)
        return datetime.combine(month.replace(day=1), time.min, tzinfo=ZoneInfo(timezone))
    if grain_upper == "END_OF_MONTH":
        month = _add_months(current_local.date(), 1)
        end = month.replace(day=_month_last_day(month.year, month.month))
        return datetime.combine(end, time.min, tzinfo=ZoneInfo(timezone))
    if grain_upper == "EVERY_N_HOURS":
        hours = int(advance_config.get("interval_hours", 1))
        return current_local + timedelta(hours=hours)
    return current_local + timedelta(days=1)


def _next_weekly_day(current_local, day_names):
    normalized = [_expand_day(name) for name in day_names]
    candidate = current_local + timedelta(days=1)
    for _ in range(8):
        if candidate.strftime("%A").upper() in normalized:
            return candidate
        candidate += timedelta(days=1)
    return current_local + timedelta(days=7)


def _expand_day(name):
    lookup = {
        "MON": "MONDAY",
        "TUE": "TUESDAY",
        "WED": "WEDNESDAY",
        "THU": "THURSDAY",
        "FRI": "FRIDAY",
        "SAT": "SATURDAY",
        "SUN": "SUNDAY",
    }
    upper = str(name).strip().upper()
    return lookup.get(upper, upper)


def _add_months(value, delta):
    total = value.year * 12 + (value.month - 1) + delta
    year, month = divmod(total, 12)
    month += 1
    day = min(value.day, _month_last_day(year, month))
    return value.replace(year=year, month=month, day=day)


def _month_last_day(year, month):
    if month == 12:
        return 31
    return (date(year, month + 1, 1) - timedelta(days=1)).day


def _coerce_value(value, timezone):
    zone = ZoneInfo(timezone)
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=zone)
    if isinstance(value, date):
        return datetime.combine(value, time.min, tzinfo=zone)
    text = str(value).strip()
    if "T" in text:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=zone)
    return datetime.combine(date.fromisoformat(text), time.min, tzinfo=zone)


def _format_value(value, grain, timezone):
    if value is None:
        return None
    local = value.astimezone(ZoneInfo(timezone))
    if (grain or "").upper() in {"DAILY", "DAILY_BUSINESS_DAY", "WEEKLY", "BEG_OF_MONTH", "END_OF_MONTH"}:
        return local.date().isoformat()
    return local.isoformat()


def _parse_state(raw):
    if raw in (None, ""):
        return {}
    if isinstance(raw, dict):
        return dict(raw)
    return json.loads(raw)


def _current_value(state, timezone):
    current_value = state.get("currentValue")
    if current_value in (None, ""):
        return None
    return _coerce_value(current_value, timezone)


def _context_metadata(context):
    ti = context.get("ti") or context.get("task_instance")
    task = context.get("task")
    dag = context.get("dag")
    dag_id = context.get("dag_id") or getattr(dag, "dag_id", None) or "unknown_dag"
    run_id = context.get("run_id") or getattr(context.get("dag_run"), "run_id", None) or "manual__unknown"
    task_id = getattr(task, "task_id", None) or getattr(ti, "task_id", None) or "unknown_task"
    map_index = getattr(ti, "map_index", -1) if ti is not None else -1
    try_number = getattr(ti, "try_number", 1) if ti is not None else 1
    observed_at = datetime.now(tz=ZoneInfo("UTC")).isoformat()
    run_key = _slug(f"{dag_id}/{run_id}/{task_id}/{map_index}")
    attempt_key = _slug(f"{dag_id}/{run_id}/{task_id}/{map_index}/{try_number}")
    return {
        "dag_id": dag_id,
        "run_id": run_id,
        "task_id": task_id,
        "map_index": map_index,
        "try_number": try_number,
        "run_key": run_key,
        "attempt_key": attempt_key,
        "observed_at": observed_at,
    }


def _render_template(value, metadata):
    rendered = str(value or "")
    replacements = {
        "{{ dag.dag_id }}": metadata["dag_id"],
        "{{ run_id }}": metadata["run_id"],
        "{{ task.task_id }}": metadata["task_id"],
    }
    for marker, replacement in replacements.items():
        rendered = rendered.replace(marker, replacement)
    return rendered


def _rejection_payload(
    state_binding_ref,
    variable_key,
    target_scope,
    grain,
    timezone,
    calendar_binding_ref,
    concurrency_policy,
    run_key,
    attempt_key,
    code,
    source,
    actor,
    notes,
    previous_value=None,
    requested_value=None,
):
    return {
        "stateBindingRef": state_binding_ref,
        "variableKey": variable_key,
        "targetScope": target_scope,
        "grain": grain,
        "timezone": timezone,
        "calendarBindingRef": calendar_binding_ref,
        "concurrencyPolicy": concurrency_policy,
        "action": "rejected",
        "rejectionCode": code,
        "previousValue": previous_value,
        "requestedValue": requested_value,
        "runKey": run_key,
        "attemptKey": attempt_key,
        "source": source,
        "advancedBy": actor,
        "notes": notes,
    }


def _state_matches_evidence(state, evidence):
    if not state:
        return False
    return (
        state.get("stateBindingRef") == evidence.get("stateBindingRef")
        and state.get("currentValue") == evidence.get("newValue")
        and state.get("variableKey") == evidence.get("variableKey")
    )


def _evidence_path(prefix, state_binding_ref, suffix):
    normalized_prefix = prefix.rstrip("/")
    normalized_binding = _slug(state_binding_ref)
    return f"{normalized_prefix}/{normalized_binding}/{suffix.lstrip('/')}"


def _slug(value):
    return "".join(ch if ch.isalnum() or ch in {"-", "_"} else "_" for ch in str(value))


def _variable_getter(variable_key):
    from airflow.models import Variable  # type: ignore

    return Variable.get(variable_key, default_var=None)


def _variable_setter(variable_key, payload):
    from airflow.models import Variable  # type: ignore

    Variable.set(variable_key, json.dumps(payload, sort_keys=True))


def _read_json_uri(uri):
    try:
        return json.loads(_read_bytes(uri).decode("utf-8"))
    except FileNotFoundError:
        return None


def _write_json_uri(uri, payload):
    _write_bytes(uri, json.dumps(payload, sort_keys=True, indent=2).encode("utf-8"))
    return uri


def _read_bytes(uri):
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
    raise ValueError(f"Unsupported URI scheme: {uri}")


def _write_bytes(uri, payload):
    parsed = urlparse(uri)
    if parsed.scheme in ("", "file"):
        path = Path(parsed.path if parsed.scheme == "file" else uri)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)
        return
    if parsed.scheme == "gs":
        from google.cloud import storage  # type: ignore

        client = storage.Client()
        blob = client.bucket(parsed.netloc).blob(parsed.path.lstrip("/"))
        blob.upload_from_string(payload, content_type="application/json")
        return
    if parsed.scheme == "s3":
        import boto3  # type: ignore

        boto3.client("s3").put_object(Bucket=parsed.netloc, Key=parsed.path.lstrip("/"), Body=payload)
        return
    raise ValueError(f"Unsupported URI scheme: {uri}")
