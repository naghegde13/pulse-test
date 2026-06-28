# A blueprint's user-facing params are a minimal surface; deep runtime plumbing is system-derived, not typed

Status: accepted

A blueprint's parameter schema has two tiers: a **minimal user-facing surface** (the genuine choices a citizen or engineer makes) and a **system-derived runtime contract** (state bindings, packaged calendars, concurrency, evidence, broker targets — everything a deployed artifact needs but that PULSE resolves from context at build/package time). A user must never be asked to hand-type deep runtime plumbing.

Context: `AdvanceTimeDimension`'s `V132` schema is 20 fields, 6 required — state bindings, a packaged calendar bundle URI + hash, concurrency policy, evidence prefixes — because a promoted DAG must advance the business date offline, with no PULSE callback (`V132:3-5`). The fields are legitimate at runtime, but the blueprint's job is "bump the date," and no one can hand-fill a `calendar_bundle_uri`+hash or `state_binding_ref`. `RemotePipelineInvocation`'s broker params (`federated_tenant_key`, `remote_target_ref`) are the same shape.

## Consequences

- **`AdvanceTimeDimension`** — user-facing (**2 fields**): `target_scope` (dataset/domain; default `dataset`, but the **Chat must explicitly ask** during config, never silently default) and `advance_to` (a date mnemonic or ISO date; **blank = next interval per grain** — consolidates the old `advance_mode` + `requested_asof_expr`). System-derived (everything else): `state_binding_ref`/`variable_key` (from the target dataset/domain); the calendar contract `calendar_binding_ref`/`calendar_bundle_uri`/`calendar_bundle_hash`/`calendar_id` (from the domain's calendar); `grain`/`timezone` (from the domain); `concurrency_policy`/`evidence_*`/`advanced_by`/`source`/`initialization_policy`/`initial_value`/`replay_policy` (platform defaults). `target_scope` is **genuinely implemented** (scope-keyed state, `CompilePlanService:408,416`), so it is kept, not collapsed. **Runtime verified real and Composer-safe** (`time_state.py`): state persists in **Airflow Variables** (`Variable.get/set`, `:508-517`), audit evidence in object storage (`gs://`/`s3://`, `:537-547`), calendar resolved offline from a packaged bundle — **no custom tables, no phone-home**; works on Composer and DPC. This **resolves the last INTENT-UNCLEAR**: a real, user-facing blueprint with a 2-field surface; the 20-field schema is its derived runtime layer.
- **Prerequisite gap (calendar at domain creation).** The derived calendar assumes the domain *has* one, but the chat `create_domain` tool (`ChatToolExecutor:349-389`) sets only name/date/`grain`(default `DAILY`)/`timezone`(hardcoded `UTC`) and **never sets `businessDateConfig`** — so a new domain has **no holiday calendar**; the runtime silently falls back to `calendar_id=US-FED`. Close by eliciting the calendar (+ grain, fiscal offset) at domain creation, or inheriting a tenant-level default. Tracked as a gap.
- **`RemotePipelineInvocation`** — the user picks the peer target / remote pipeline; the broker refs (`federated_tenant_key`, `remote_target_ref`, `airflow_connection_id`) are derived from that selection, not typed.
- The derived contract is **inspectable, not editable** — surfaced read-only for transparency (consistent with ADR 0022), never hidden.

## Trade-off

PULSE must resolve the derived params at build/package time (more Builder/packager work) instead of offloading them to the user. Accepted because a 20-field hand-entry form makes the blueprint unusable — exactly the slop this rationalization exists to remove.

## Mechanism (2026-06-15)

The user-vs-derived split is expressed as a **per-param `tier: user|derived` flag in `params_schema`** (the only param field backend code actually reads), plus a **`derivedFrom`** (the resolution source) on each `derived` param. This single, code-read mechanism serves both consumers: the **build** (the packager resolves every `derived` param from its `derivedFrom` at build/package time) and the **UI** (params marked `derived` render read-only/inspectable — consistent with the "inspectable, not editable" consequence above). Rejected alternatives: a separate `ui_schema` read-only hint (tiering is a build-time contract concern, not a UI concern, and `ui_schema` is currently dead/unread) and a separate derived-params list (two lists to keep in sync → drift).
