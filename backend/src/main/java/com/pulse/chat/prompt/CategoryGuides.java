package com.pulse.chat.prompt;

import com.pulse.blueprint.model.BlueprintCategory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The five per-blueprint-category best-practice guides (the PULSE analogue of
 * n8n's {@code get_documentation} content), consolidated from
 * {@code docs/ui/chat-prompts/03-best-practices.md} §1-§5 + the live
 * {@code DBT_BEST_PRACTICE_CARDS} / {@code BLUEPRINT_EXAMPLE_PACKETS}
 * (IMPL Phase 8 task 3). One guide per category — Ingestion · Transform ·
 * Modeling · Data Quality · Orchestration — folding SINK -> Transform (the
 * writer/sink tail) and CONTROL -> Orchestration.
 *
 * <p>The relevant guide is injected when the Composer / Discovery stage selects a
 * category (mapped from {@link BlueprintCategory} via {@link #forCategory}); the
 * assembler may inject all five for a fresh build, or the targeted subset for a
 * focused turn (f22 — TARGETED, not corpus-wide).</p>
 */
public final class CategoryGuides {

    private CategoryGuides() {}

    // ==================================================================
    // §1. Ingestion
    // ==================================================================
    public static final String INGESTION = """
            # Best Practices: Ingestion

            ## Workflow Design
            Every ingestion step lands bronze: a faithful, append-friendly copy of the source with PULSE audit
            columns added and NO business logic. Do not clean, dedupe, mask, or join in an ingestion step — that is
            silver/gold work. Keep bronze as close to the source as the format allows so you can always re-derive
            downstream layers. PULSE appends the 8-column audit set (`_pulse_ingested_at`, `_pulse_processing_ts`,
            `_pulse_pipeline`, `_pulse_task`, `_pulse_run_id`, `_pulse_source_uri`, `_pulse_business_date`,
            `_pulse_dag_id`) automatically via `add-audit-columns` — never add your own ingestion-timestamp columns.

            **CRITICAL:** never hand-type storage or connector plumbing. `storage_backend`, `lake_layer` (bronze for
            ingestion), `lake_format`, and connector refs are system-derived — PULSE resolves them at codegen/deploy
            from the chosen connector and the pipeline's storage backend. The engineer selects the connector; the
            refs follow. PULSE carries SecretRefs only, never secret values.
            **CRITICAL:** one ingestion blueprint per source dataset (CDCIngestion's `tables[]` is the exception).
            **CRITICAL:** make the run idempotent and re-runnable for a business date — date-driven sources use a
            mnemonic (`date_value`, default `RUN_DATE`) so a re-run for the same business date reproduces the same
            bronze partition. Do not hardcode an absolute date.

            ## Choosing the right ingestion blueprint
            - A file lands on a schedule (CSV/Parquet on object store / SFTP) -> `FileIngestion`.
            - A full point-in-time copy of a source table each period -> `SnapshotIngestion`.
            - Changed rows only from an operational DB (insert/update/delete) -> `CDCIngestion`.
            - A bounded historical reload over a date range -> `BulkBackfill`.
            - A streaming topic (Kafka and similar), micro-batched -> `StreamIngestion`.
            - A REST/HTTP API with pagination/watermark -> `ApiIngestion`.

            ## Common Pitfalls to Avoid
            - Cleaning in bronze (erases re-derivability — push it into `BronzeToSilverCleaning`).
            - Hand-typed storage/credentials (system-derived, surfaced read-only).
            - Non-idempotent dates (absolute dates land the wrong partition on re-run — use a mnemonic).
            - Small-file storms (over-frequent stream/API micro-batches — size windows to the SLA).
            - No upstream sensor (ingesting before the source is ready — gate behind the matching sensor).
            """;

    // ==================================================================
    // §2. Transform  (folds SINK / writers)
    // ==================================================================
    public static final String TRANSFORM = """
            # Best Practices: Transform

            ## Workflow Design
            The transform layer produces silver: cleaned, conformed, deduplicated, contract-shaped data the modeling
            layer can trust. Build it in this order — clean -> conform (normalize) -> mask -> join/aggregate ->
            route / flatten — so each step's INPUT contract is the previous step's OUTPUT contract.

            **CRITICAL:** rely on schema propagation, not hope. Every transform op has a declared schema effect (the
            closed 32-op vocabulary; ADR 0012/0013). Configure a step against the ACTUAL upstream columns Chat can
            read (`get_step_schema`), never against assumed column names.
            **CRITICAL:** collision/type rules are owned by the schema engine — do not work around them in SQL. Join
            collisions prefix the right side `right_<name>`. Aggregate output types are fixed (COUNT->long, SUM
            int->long/decimal->double, AVG->double, MIN/MAX->source). Masking is type-preserving except hash->string.
            **CRITICAL:** keep one transform = one concern (chain single-concern steps).
            **CRITICAL:** mask PII BEFORE it can leak into a join key, an aggregate, or a sink — put masking as early
            as the lineage allows.

            ## Schema & contract discipline
            silver is where dataset contracts start to matter (required for promotion to integration+). Make silver
            column names and types stable: rename/cast in `SchemaNormalization` once, then keep them fixed. Prefer
            non-breaking changes (additive columns) over renames/drops of consumed columns.

            ## Recommended sink (writer) blueprints — the terminal of a transform/model chain
            The 4 SINK blueprints write the curated result out; one input port (role gold), no output port, compute
            PySpark; `write_mode=merge_on_pk` requires `merge_keys`.
            - WarehouseWriter — write gold to a warehouse target; `merge_on_pk` for upsert, `overwrite_partition` for refresh.
            - LakeWriter — write gold to a Delta/Iceberg lake target; enable `optimize_after_write` for large BI tables.
            - DatabaseWriter — write gold to a JDBC database target (`upsert_keys`).
            - StreamWriter — publish gold to a Kafka topic; `checkpoint_location` is system-derived, unique per pipeline.

            ## Common Pitfalls to Avoid
            - Configuring against assumed columns (read the real upstream schema with `get_step_schema`).
            - Hand-casting around the schema engine (desyncs the propagated schema from reality).
            - Renaming/dropping a consumed silver column (breaking change — prefer additive).
            - Masking too late (PII already flowed into a join/aggregate — mask first).
            - Dropping rows silently in a router (`include_default=false` discards unmatched rows).
            """;

    // ==================================================================
    // §3. Modeling
    // ==================================================================
    public static final String MODELING = """
            # Best Practices: Modeling

            ## Workflow Design
            Modeling turns conformed silver into gold: the dimensional/serving layer BI queries. Decide the GRAIN of
            every model first — a fact is "one row per ___", a dimension is "one row per business key" — and let the
            grain drive the blueprint choice. Gold reads from silver (and from other gold for marts), never bronze.

            **CRITICAL:** choose the history strategy deliberately (it is hard to change later):
            - Track changes to a dimension over time (valid-from/valid-to) -> `SCD2Dimension`.
            - A periodic full picture (point-in-time snapshots) -> `SnapshotModel`.
            - Upsert the latest state (no history) -> `IncrementalMerge`.
            Mixing these on one entity duplicates history — pick one per entity.
            **CRITICAL:** SCD2Dimension adds `dbt_valid_from`/`dbt_valid_to`/`dbt_scd_id`/`dbt_updated_at`;
            SnapshotModel adds `ds` + `_pulse_*`. Do not add your own versioning columns — the blueprint owns them.
            **CRITICAL:** declare keys explicitly — SCD2 needs `business_key[]` + `tracked_columns[]`; facts need
            `dimension_keys[]` + `measures[]`; merges need `merge_key[]`. A wrong/missing key silently corrupts
            history or fans out rows. Leave `partition_by`/`cluster_by` system-derived unless measured.

            ## Recommended Blueprints (highlights)
            - SCD2Dimension — track only business-meaningful columns (volatile columns explode version churn).
            - SnapshotModel — "the state at each period"; set `retention_days` to your audit requirement.
            - FactBuild — state the `grain` precisely (most fact bugs are grain bugs); make it `incremental` + `time_column` when large.
            - WideDenormalizedMart — denormalize for read performance only when consumers need it.
            - AggregateMaterialization — when the aggregate LANDS gold with a refresh strategy (vs GenericAggregate at silver).
            - IncrementalMerge — define `late_data_policy`; `soft_delete` over hard delete when downstream needs the tombstone.
            - ReferenceDataPublish — keep `versioned=true` for reference sets consumers pin to.
            - FeatureTablePublish — always set `point_in_time_column` (feature leakage is the classic feature-store bug).

            ## Common Pitfalls to Avoid
            - Wrong grain (a fan-out join duplicates measures — state and verify the grain).
            - Missing/incorrect business or merge key (silently corrupts history).
            - Adding your own version columns (collides with the blueprint).
            - Reading from bronze (gold reads conformed silver, not raw bronze).
            - Over-denormalizing (wide marts are costly — build for real consumer demand).
            """;

    // ==================================================================
    // §4. Data Quality
    // ==================================================================
    public static final String DATA_QUALITY = """
            # Best Practices: Data Quality

            ## Workflow Design
            Data quality is a gate and a signal, not a transform. Place DQ where a contract must hold: at the
            bronze->silver boundary (is the raw feed usable?) and at the silver/gold boundary (does curated output
            meet the contract before publish/promotion?). Validate the inputs you are about to trust, not every
            intermediate step.

            **CRITICAL:** decide the failure outcome explicitly. `DQValidator.on_failure` is the canonical control —
            quarantine (route failing rows aside, keep good rows flowing), block (FAIL raises and fails the run), or
            warn (record but continue).
            **CRITICAL:** a FAIL with `on_failure=block` fails the Airflow run — wire it where a failed run is the
            correct response (before a publish), not where it needlessly halts a recoverable pipeline.
            **CRITICAL:** reports are append-only. `FreshnessChecks`/`SchemaDriftDetection`/`AnomalyDetection` emit
            an append-only report table — trend analysis depends on history; never design around an overwriting report.
            Default-good: quarantine at the ingestion boundary; block at the promotion boundary.

            ## Recommended Blueprints (highlights)
            - DQValidator — the workhorse gate (validated + quarantine ports); start with a few high-value
              expectations (not-null on keys, accepted-values on enums, range on amounts); set `mostly` for noisy data.
            - FreshnessChecks — choose the SLA dimension matching the cadence (`max_age_business_days` for business
              feeds, sub-hour `max_age_minutes` for streaming).
            - SchemaDriftDetection — `allow_extra_columns=true` for additive sources, `false` for strict contracts;
              route a drift to a migration proposal via OpenMetadata.
            - AnomalyDetection — give it enough `lookback_runs` to learn a baseline; `volume_monitoring` catches
              "feed ran but landed 0 rows".

            ## Common Pitfalls to Avoid
            - No explicit failure outcome (fail-vs-quarantine surprise at the worst moment — choose per check).
            - Blocking where you should quarantine (and vice-versa).
            - Designing around overwriting reports (reports are append-only by intent).
            - Alert storms / over-gating (gate at contract boundaries, not every step).
            - Too-tight expectations (use `mostly`/`threshold_percent` on noisy real data).
            """;

    // ==================================================================
    // §5. Orchestration  (folds CONTROL)
    // ==================================================================
    public static final String ORCHESTRATION = """
            # Best Practices: Orchestration

            ## Workflow Design
            Orchestration decides WHEN a pipeline runs and WHAT it waits for — the data steps decide what it does.
            Gate data work behind the readiness signals it depends on: don't ingest before the file lands, don't model
            before the source DB is loaded. One DAG per Business Pipeline with TaskGroups per sub-pipeline; PULSE
            triggers/observes Airflow, Airflow callbacks POST to PULSE.

            **CRITICAL — the activation analogue:** every Business Pipeline needs exactly one `ScheduleAndTriggers` to
            run on a cadence. Without a schedule/trigger, the DAG only runs on manual invoke. `ScheduleAndTriggers` is
            portless — a DAG-level schedule/trigger block, not a step in the data flow.
            **CRITICAL — timezone and business calendar:** `timezone` and holiday/fiscal calendar refs are
            system-derived from the domain calendar — do not hand-type them.
            **CRITICAL — sensors are guards, not data:** `FileArrivalSensor`/`DatabaseReadinessSensor`/
            `ExternalEventSensor` emit a `ready_signal` (a DAG dependency), not a dataset — wire the `ready_signal` to
            the data step it gates; the data step pulls the source itself.
            **CRITICAL — make re-runs safe:** set `catchup_enabled`/`depends_on_past` deliberately — a backfill with
            catchup on can launch many historical runs at once; pair with idempotent ingestion. `max_active_runs` is
            system-derived (=1) to serialize by default.

            ## Sensor selection
            - A file/object must exist (right size/age) -> `FileArrivalSensor`.
            - A source DB must be loaded (a probe query returns the expected count) -> `DatabaseReadinessSensor`.
            - An external system signals done (an endpoint returns success) -> `ExternalEventSensor`.

            ## Recommended Blueprints (highlights)
            - ScheduleAndTriggers — use `schedule_type=event` `trigger_dataset` for data-dependency runs; validate cron.
            - FileArrivalSensor — set `expected_max_age_hours` so a leftover old file doesn't satisfy the sensor.
            - DatabaseReadinessSensor — use `expected_count_max` to catch a runaway load; keep the probe cheap.
            - ExternalEventSensor — set a realistic timeout so a never-arriving event doesn't hold the DAG.
            - AdvanceTimeDimension — wire its `trigger` to the LAST processing step (advance only after a successful
              run); leave `advance_to` blank for normal daily advance. You MUST ask `target_scope` (dataset/domain).
            - RollbackOnFailure — keep `keep_failed_artifacts=true` while stabilizing; rollback = redeploy last-good.
            - RemotePipelineInvocation — chain Business Pipelines rather than cramming two domains into one DAG.

            ## Common Pitfalls to Avoid
            - No schedule/trigger (the pipeline only runs on manual invoke — add exactly one `ScheduleAndTriggers`).
            - Hand-typed timezone/calendar (domain-derived; hand-typing desyncs business-day logic).
            - Unguarded data steps (gate behind the matching sensor).
            - Catchup surprises (`catchup_enabled=true` launches many historical runs — pair with idempotent ingestion).
            - Advancing the time dimension before success (wire `AdvanceTimeDimension.trigger` to the last step).
            - Stale-file false positives (a `FileArrivalSensor` without `expected_max_age_hours`).
            """;

    /** Category-name -> guide, keyed by the canonical fragment-03 category label. */
    private static final Map<String, String> BY_NAME = buildByName();

    private static Map<String, String> buildByName() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("INGESTION", INGESTION);
        m.put("TRANSFORM", TRANSFORM);
        m.put("MODELING", MODELING);
        m.put("DATA_QUALITY", DATA_QUALITY);
        m.put("ORCHESTRATION", ORCHESTRATION);
        return m;
    }

    /** All five guides, joined, for a fresh build that may span all categories. */
    public static String all() {
        return String.join("\n", BY_NAME.values());
    }

    /** The guide for a category name (INGESTION/TRANSFORM/MODELING/DATA_QUALITY/ORCHESTRATION), or empty. */
    public static String forCategoryName(String categoryName) {
        if (categoryName == null) return "";
        return BY_NAME.getOrDefault(categoryName.toUpperCase(), "");
    }

    /**
     * Map a {@link BlueprintCategory} to its guide, folding SINK/CONTROL:
     * DESTINATION (sinks) -> Transform; the persisted ORCHESTRATION already covers
     * CONTROL. INGESTION/TRANSFORM/MODELING/DATA_QUALITY map straight through.
     */
    public static String forCategory(BlueprintCategory category) {
        if (category == null) return "";
        return switch (category) {
            case INGESTION -> INGESTION;
            case TRANSFORM -> TRANSFORM;
            case MODELING -> MODELING;
            case DATA_QUALITY -> DATA_QUALITY;
            case ORCHESTRATION -> ORCHESTRATION;
            case DESTINATION -> TRANSFORM; // SINK/writer guidance folds into Transform's writer tail
        };
    }
}
