# Cross-pipeline dependency is a trigger inside the composition (data-aware scheduling), not a pull-sensor or off-surface config

Status: accepted

When a pipeline must run after another, the developer expresses that dependency **on the composition surface** — their only design surface — as a **trigger**, via the `ScheduleAndTriggers` Pipeline Setting (`schedule_type=event`, `trigger_dataset=X`: "run when dataset X updates", `V93:316-319`). On a single Airflow this compiles to Airflow **data-aware scheduling**: the consumer is `schedule=[Dataset(X)]`; the producer's `outlets=[Dataset(X)]` is derived from the output dataset it already declares. The dependency flows through the **dataset** — PULSE's universal I/O unit — which is loose coupling (neither pipeline names the other) and matches how the platform already models readiness *on the dataset itself* (`Dataset.sensingStrategy`/`sensorConfig`/`readinessQuery`, `Dataset.java:88,98,91`).

## Consequences

- **`DatasetDependencySensor` → deprecated** (replacement `ScheduleAndTriggers`). Its name ("dataset dependency") was right, but its mechanism — `external_task_sensor` polling a hard-coded `upstream_dag_id` (`V75:120,137`) — was tightly coupled and works only within one Airflow. The dependency becomes a trigger, not a pull-sensor.
- **No `LocalPipelineInvocation` blueprint.** Explicit same-Airflow push (`TriggerDagRunOperator`) builds the tight coupling data-aware scheduling exists to avoid.
- **`RemotePipelineInvocation` → kept and is user-facing** (resolves draft §F-c). It is the *only* path for a genuinely cross-Airflow dependency (e.g. waiting on the external DPC Airflow), because data-aware scheduling does not span separate Airflow instances — reaching the other one stays an explicit API invocation (`DefaultDpcAirflowClient.triggerDagRun`). Add an `invocation_mode` param: `async` (fire-and-forget) vs `sync` (invoke then poll to completion).
- **Event-based chaining within one Airflow is the default**, via native Airflow Datasets — no bespoke blueprint.

## Considered options

- **Pull-sensor (`external_task_sensor`)** — rejected: tight, schedule-alignment-finicky coupling, same-Airflow only.
- **Explicit local invocation (`TriggerDagRunOperator`)** — rejected: the upstream must name its downstreams; tight coupling, against PULSE's dataset-centric model.
- **A separate, off-surface dependency-configuration architecture** — rejected: the composition is the developer's only design surface, and the on-surface trigger (`ScheduleAndTriggers` event mode) already expresses the dependency, so no second surface is warranted.

## Prerequisites / risks (data model & runtime, not current codegen)

- Auto-deriving the producer `outlet` depends on PULSE resolving **"dataset X is produced by business pipeline A"** across pipelines. Within a pipeline, instances declare `outputDatasets` (`SubPipelineInstance.outputDatasets:48`); the cross-pipeline producer link must be confirmed before building.
- Data-aware scheduling needs Airflow ≥2.4 (conditional multi-dataset logic ≥2.9). Composer is fine; the on-prem **DPC Airflow version must be confirmed** or a fallback chosen (Mode-specific).
