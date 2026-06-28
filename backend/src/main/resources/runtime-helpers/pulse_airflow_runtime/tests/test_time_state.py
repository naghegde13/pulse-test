import json
from pathlib import Path

import pytest

from pulse_airflow_runtime.time_state import advance_time_dimension


def _calendar_bundle(tmp_path: Path) -> Path:
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
                ]
            }
        },
    }
    path = tmp_path / "calendar-bundle.json"
    path.write_text(json.dumps(bundle), encoding="utf-8")
    return path


def _variable_accessors(store):
    return store.get, lambda key, payload: store.__setitem__(key, payload)


def _context(run_id="manual__2026-03-06", try_number=1):
    def resolver(_):
        return {
            "dag_id": "pulse_test",
            "run_id": run_id,
            "task_id": "advance_time",
            "map_index": -1,
            "try_number": try_number,
            "run_key": "pulse_test_manual_2026_03_06_advance_time_-1",
            "attempt_key": f"pulse_test_manual_2026_03_06_advance_time_-1_{try_number}",
            "observed_at": "2026-03-06T12:00:00+00:00",
        }

    return resolver


def _base_kwargs(tmp_path, store, **overrides):
    getter, setter = _variable_accessors(store)
    kwargs = {
        "state_binding_ref": "time_state:dataset:loan-master",
        "variable_key": "pulse.time_state.tenant.loan_master",
        "calendar_binding_ref": "calendar:servicing",
        "calendar_bundle_uri": str(_calendar_bundle(tmp_path)),
        "calendar_bundle_hash": "",
        "calendar_id": "US-FED",
        "advance_mode": "next_interval",
        "replay_policy": "reject_backward",
        "evidence_prefix": str(tmp_path / "evidence"),
        "initialization_policy": "require_existing",
        "concurrency_policy": "serialized_airflow",
        "target_scope": "dataset",
        "grain": "DAILY_BUSINESS_DAY",
        "timezone": "America/New_York",
        "evidence_required": True,
        "source": "AdvanceTimeDimension",
        "advanced_by": "airflow:{{ dag.dag_id }}",
        "variable_getter": getter,
        "variable_setter": setter,
        "context_resolver": _context(),
    }
    kwargs.update(overrides)
    return kwargs


def test_forward_advance_updates_state_and_writes_evidence(tmp_path):
    store = {
        "pulse.time_state.tenant.loan_master": {
            "stateBindingRef": "time_state:dataset:loan-master",
            "variableKey": "pulse.time_state.tenant.loan_master",
            "currentValue": "2026-03-06",
        }
    }

    result = advance_time_dimension(**_base_kwargs(tmp_path, store))

    assert result["action"] == "advanced"
    assert result["newValue"] == "2026-03-09"
    assert store["pulse.time_state.tenant.loan_master"]["currentValue"] == "2026-03-09"
    assert Path(result["evidencePath"]).exists()


def test_missing_current_state_requires_explicit_initialization(tmp_path):
    store = {}

    with pytest.raises(ValueError, match="missing current state"):
        advance_time_dimension(**_base_kwargs(tmp_path, store))


def test_projected_initialization_is_allowed(tmp_path):
    store = {}

    result = advance_time_dimension(
        **_base_kwargs(
            tmp_path,
            store,
            initialization_policy="allow_projected_initial_value",
            initial_value="2026-03-06",
        )
    )

    assert result["action"] == "initialized"
    assert store["pulse.time_state.tenant.loan_master"]["currentValue"] == "2026-03-06"


def test_backward_requested_asof_is_rejected(tmp_path):
    store = {
        "pulse.time_state.tenant.loan_master": {
            "stateBindingRef": "time_state:dataset:loan-master",
            "variableKey": "pulse.time_state.tenant.loan_master",
            "currentValue": "2026-03-10",
        }
    }

    with pytest.raises(ValueError, match="rejected backward advance"):
        advance_time_dimension(
            **_base_kwargs(
                tmp_path,
                store,
                requested_asof_expr="2026-03-09",
            )
        )


def test_explicit_replay_is_supported_when_enabled(tmp_path):
    store = {
        "pulse.time_state.tenant.loan_master": {
            "stateBindingRef": "time_state:dataset:loan-master",
            "variableKey": "pulse.time_state.tenant.loan_master",
            "currentValue": "2026-03-10",
        }
    }

    result = advance_time_dimension(
        **_base_kwargs(
            tmp_path,
            store,
            replay_policy="allow_backward",
            requested_asof_expr="2026-03-09",
        )
    )

    assert result["action"] == "replayed"
    assert store["pulse.time_state.tenant.loan_master"]["currentValue"] == "2026-03-09"


def test_duplicate_retry_returns_existing_result_when_state_matches(tmp_path):
    store = {
        "pulse.time_state.tenant.loan_master": {
            "stateBindingRef": "time_state:dataset:loan-master",
            "variableKey": "pulse.time_state.tenant.loan_master",
            "currentValue": "2026-03-06",
        }
    }
    first = advance_time_dimension(**_base_kwargs(tmp_path, store))
    assert first["newValue"] == "2026-03-09"

    second = advance_time_dimension(
        **_base_kwargs(
            tmp_path,
            store,
            context_resolver=_context(try_number=2),
        )
    )

    assert second["duplicateAttempt"] is True
    assert second["newValue"] == "2026-03-09"
    assert second["attemptKey"].endswith("_2")


def test_duplicate_retry_fails_closed_when_state_disagrees_with_evidence(tmp_path):
    store = {
        "pulse.time_state.tenant.loan_master": {
            "stateBindingRef": "time_state:dataset:loan-master",
            "variableKey": "pulse.time_state.tenant.loan_master",
            "currentValue": "2026-03-10",
        }
    }
    evidence_root = tmp_path / "evidence" / "time_state_dataset_loan-master"
    evidence_root.mkdir(parents=True, exist_ok=True)
    (evidence_root / "pulse_test_manual_2026_03_06_advance_time_-1.json").write_text(
        json.dumps(
            {
                "stateBindingRef": "time_state:dataset:loan-master",
                "variableKey": "pulse.time_state.tenant.loan_master",
                "newValue": "2026-03-09",
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(RuntimeError, match="evidence/state mismatch"):
        advance_time_dimension(**_base_kwargs(tmp_path, store))


def test_calendar_hash_mismatch_fails(tmp_path):
    store = {
        "pulse.time_state.tenant.loan_master": {
            "stateBindingRef": "time_state:dataset:loan-master",
            "variableKey": "pulse.time_state.tenant.loan_master",
            "currentValue": "2026-03-06",
        }
    }

    with pytest.raises(ValueError, match="hash mismatch"):
        advance_time_dimension(
            **_base_kwargs(
                tmp_path,
                store,
                calendar_bundle_hash="sha256:bad-hash",
            )
        )


def test_evidence_write_failure_raises(tmp_path):
    store = {
        "pulse.time_state.tenant.loan_master": {
            "stateBindingRef": "time_state:dataset:loan-master",
            "variableKey": "pulse.time_state.tenant.loan_master",
            "currentValue": "2026-03-06",
        }
    }

    def failing_writer(_uri, _payload):
        raise OSError("disk full")

    with pytest.raises(OSError, match="disk full"):
        advance_time_dimension(
            **_base_kwargs(
                tmp_path,
                store,
                evidence_writer=failing_writer,
            )
        )
