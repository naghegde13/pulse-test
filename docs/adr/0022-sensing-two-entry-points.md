# Sensing is a first-class DAG capability with two entry points (dataset-implicit + explicit), one shared emitter; implicitly-generated DAG elements must be surfaced

Status: accepted

PULSE senses readiness two ways, and both are kept because they cover genuinely different needs:

1. **Implicit, dataset-bound.** An `INGESTION` step whose source dataset declares a `sensingStrategy` (`file`/`sql_query`) auto-generates the arrival sensor from the dataset's own fields (`Dataset.sensingStrategy`/`sensorConfig`/`fileNamingPattern`/`readinessQuery`, `Dataset.java:88,98,71,91`). The connector only chooses the transport (`SFTPSensor` vs `S3KeySensor`), not whether sensing happens.
2. **Explicit, draggable.** A sensor blueprint placed anywhere in the DAG to wait on something that is **not a modeled dataset** — an egress file for downstream distribution, an unrelated external object-storage event, or a mid-DAG readiness gate against an external DB. The implicit path provably cannot express these (it fires only for an INGESTION source dataset).

Both entry points MUST share **one** sensor-emitting implementation, so keeping both adds a surface, not a duplicated mechanism.

## Consequences

- **Keep** `FileArrivalSensor`, `DatabaseReadinessSensor`, `ExternalEventSensor` as the explicit/general sensors.
- **Deprecate** `ObjectStoreKeySensor` (→ `FileArrivalSensor`, of which it is a strict subset) and `DatasetDependencySensor` (→ `ScheduleAndTriggers`, ADR 0021). No other sensor is deprecated.
- **Transparency (mandatory).** Anything PULSE generates implicitly onto the DAG — the dataset-derived sensor, and the data-aware-scheduling edges from ADR 0021 — MUST be visible on the design surface: a derived/read-only node on the visual editor and surfaced in the step GUI. A surface that hides generated behavior can't be trusted, and visibility stops a developer from dragging a duplicate sensor over the implicit one. (Today the implicit sensor is invisible — a confirmed UI gap.)
- **Dataset GUI + Chat.** Sensing config is a dataset property (the connector has no naming/sensing field), so the dataset GUI must surface sensing next to `fileNamingPattern`, and the Chat agent must elicit sensing strategy when configuring a dataset.

## The boundary that keeps it un-fuzzy

- **Implicit** = sensing a modeled dataset's own arrival/readiness (ingestion source).
- **Explicit** = sensing anything that is not a modeled dataset, anywhere in the DAG.
- **Connector** = the wire (host/bucket/credentials, reusable). **Dataset** = what data + how it physically shows up (file naming, sensing, arrival, grain). Both naming and sensing belong to the dataset.
