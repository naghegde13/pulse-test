package com.pulse.codegen.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the PULSE audit columns appended by every
 * ingestion (bronze) blueprint at runtime. The same set is exposed for
 * design-time schema propagation so the DAG view can show audit columns
 * before runtime, and codegen guarantees the runtime emit matches.
 *
 * If you change the set here, both {@code SchemaPropagationService} bronze
 * derivation AND the PySpark emit will pick it up. There must not be a
 * second copy of these names anywhere else.
 *
 * <p>Audit columns:
 * <ul>
 *   <li>{@code _pulse_ingested_at} — wall-clock timestamp when the row was loaded.</li>
 *   <li>{@code _pulse_processing_ts} — effective business processing timestamp.
 *       Sourced per {@code dataset.processing_datetime_source}.</li>
 *   <li>{@code _pulse_pipeline} — pipeline slug (baked literal).</li>
 *   <li>{@code _pulse_task} — Airflow task id ({@code {{ task.task_id }}}),
 *       surfaced via the {@code PULSE_TASK_ID} env var.</li>
 *   <li>{@code _pulse_run_id} — Airflow DAG run_id, surfaced via the
 *       {@code PULSE_RUN_ID} env var ({@code {{ run_id }}}).</li>
 *   <li>{@code _pulse_source_uri} — source identifier; for file ingestion this is
 *       {@code input_file_name()} per row, for JDBC/Kafka/API it falls back to
 *       the {@code PULSE_SOURCE_URI} env var.</li>
 *   <li>{@code _pulse_business_date} — pipeline business date as a typed DATE
 *       column ({@code PULSE_BUSINESS_DATE} env / {@code {{ ds }}}).</li>
 *   <li>{@code _pulse_dag_id} — Airflow DAG id ({@code {{ dag.dag_id }}}),
 *       surfaced via the {@code PULSE_DAG_ID} env var.</li>
 * </ul>
 *
 * <p>The set is <strong>8 columns</strong> (C-1, locked 2026-06-15). The phantom
 * {@code created_as_timestamp} is intentionally NOT present (it equalled
 * {@code _pulse_ingested_at}; a true source-creation time lives in source data,
 * not in PULSE audit). Three of the eight are <em>live Airflow templates</em>
 * rather than baked literals: {@code _pulse_task} ({@code {{ task.task_id }}}),
 * {@code _pulse_run_id} ({@code {{ run_id }}}), and {@code _pulse_dag_id}
 * ({@code {{ dag.dag_id }}}); {@code _pulse_pipeline} stays the baked pipeline slug.
 */
public final class IngestionAuditColumns {

    public static final String INGESTED_AT    = "_pulse_ingested_at";
    public static final String PROCESSING_TS  = "_pulse_processing_ts";
    public static final String PIPELINE       = "_pulse_pipeline";
    public static final String TASK           = "_pulse_task";
    public static final String RUN_ID         = "_pulse_run_id";
    public static final String SOURCE_URI     = "_pulse_source_uri";
    public static final String BUSINESS_DATE  = "_pulse_business_date";
    public static final String DAG_ID         = "_pulse_dag_id";

    public static final List<String> NAMES = List.of(
            INGESTED_AT, PROCESSING_TS, PIPELINE, TASK,
            RUN_ID, SOURCE_URI, BUSINESS_DATE, DAG_ID);

    /** Where the row physically came from — controls how source_uri / processing_ts emit. */
    public enum SourceContext { FILE, JDBC, STREAM, API, GENERIC }

    private IngestionAuditColumns() {}

    /**
     * Returns the audit-column descriptors in the same shape that
     * SchemaPropagationService uses ({"name":..., "type":...}) plus
     * lineage/tags so the DAG can render them with the audit chip.
     * Order matches the PySpark emit so design-time and runtime line up.
     */
    public static List<Map<String, Object>> asColumnDescriptors() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Def d : DEFS) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", d.name);
            c.put("type", d.type);
            c.put("nullable", false);
            c.put("description", d.description);
            c.put("lineage", "injected:audit");
            c.put("tags", List.of("audit"));
            out.add(c);
        }
        return out;
    }

    /**
     * Appends withColumn() calls for the standard audit set to a PySpark
     * builder. Caller must have already produced a {@code df} variable and
     * imported {@code os} + the env-var setup ({@code PULSE_BUSINESS_DATE},
     * {@code PULSE_PROCESSING_TS}).
     *
     * @param py            builder to append to
     * @param pipelineSlug  pipeline slug literal
     * @param taskSlug      task slug literal
     * @param pdtSource     dataset.processing_datetime_source value
     *                      ('file_arrival_time' | 'filename_segment' | 'airflow_run_time')
     * @param ctx           source context — controls source_uri + processing_ts
     */
    public static void emitPyspark(StringBuilder py,
                                   String pipelineSlug,
                                   String taskSlug,
                                   String pdtSource,
                                   SourceContext ctx) {
        py.append("# PULSE audit columns (single source of truth: IngestionAuditColumns.java).\n");
        py.append("from pyspark.sql.functions import current_timestamp, lit, to_date");
        if (ctx == SourceContext.FILE) {
            py.append(", input_file_name");
            if ("file_arrival_time".equals(pdtSource)) {
                py.append(", input_file_modification_time");
            } else if ("filename_segment".equals(pdtSource)) {
                py.append(", regexp_extract, to_timestamp");
            }
        }
        py.append("\n");

        py.append("df = (df\n");
        py.append(String.format("    .withColumn('%s', current_timestamp())\n", INGESTED_AT));
        // _pulse_pipeline stays the baked pipeline slug.
        py.append(String.format("    .withColumn('%s', lit('%s'))\n", PIPELINE, pipelineSlug));
        // _pulse_task is LIVE: Airflow {{ task.task_id }} via PULSE_TASK_ID env var
        // (same pattern as _pulse_run_id), NOT the baked task slug.
        py.append(String.format(
                "    .withColumn('%s', lit(os.environ.get('PULSE_TASK_ID', '{{ task.task_id }}')))\n",
                TASK));
        py.append(String.format(
                "    .withColumn('%s', lit(os.environ.get('PULSE_RUN_ID', '{{ run_id }}')))\n",
                RUN_ID));
        // _pulse_dag_id is LIVE: Airflow {{ dag.dag_id }} via PULSE_DAG_ID env var.
        py.append(String.format(
                "    .withColumn('%s', lit(os.environ.get('PULSE_DAG_ID', '{{ dag.dag_id }}')))\n",
                DAG_ID));
        py.append(String.format(
                "    .withColumn('%s', to_date(lit(PULSE_BUSINESS_DATE), 'yyyy-MM-dd'))\n",
                BUSINESS_DATE));

        // _pulse_source_uri
        if (ctx == SourceContext.FILE) {
            py.append(String.format("    .withColumn('%s', input_file_name())\n", SOURCE_URI));
        } else {
            py.append(String.format(
                    "    .withColumn('%s', lit(os.environ.get('PULSE_SOURCE_URI', '')))\n",
                    SOURCE_URI));
        }

        // _pulse_processing_ts — branched on processing_datetime_source
        switch (pdtSource == null ? "airflow_run_time" : pdtSource) {
            case "file_arrival_time" -> py.append(String.format(
                    "    .withColumn('%s', input_file_modification_time())\n",
                    PROCESSING_TS));
            case "filename_segment" -> py.append(String.format(
                    "    .withColumn('%s',\n" +
                    "        to_timestamp(regexp_extract(input_file_name(), r'(\\\\d{14})', 1),\n" +
                    "                     'yyyyMMddHHmmss'))\n",
                    PROCESSING_TS));
            default -> py.append(String.format(
                    "    .withColumn('%s', lit(PULSE_PROCESSING_TS))\n",
                    PROCESSING_TS));
        }
        py.append(")\n\n");
    }

    private record Def(String name, String type, String description) {}

    private static final List<Def> DEFS = List.of(
            new Def(INGESTED_AT,   "timestamp",
                    "Wall-clock timestamp when this row was loaded by PULSE."),
            new Def(PROCESSING_TS, "timestamp",
                    "Effective business processing timestamp; sourced per dataset.processing_datetime_source."),
            new Def(PIPELINE,      "string",   "Pipeline slug."),
            new Def(TASK,          "string",
                    "Airflow task id ({{ task.task_id }}); supplied via PULSE_TASK_ID env var."),
            new Def(RUN_ID,        "string",
                    "Airflow DAG run_id ({{ run_id }}); supplied via PULSE_RUN_ID env var."),
            new Def(SOURCE_URI,    "string",
                    "Per-row source identifier — input_file_name() for file ingestion, " +
                    "PULSE_SOURCE_URI env var otherwise."),
            new Def(BUSINESS_DATE, "date",
                    "Pipeline business date ({{ ds }}); supplied via PULSE_BUSINESS_DATE env var."),
            new Def(DAG_ID,        "string",
                    "Airflow DAG id ({{ dag.dag_id }}); supplied via PULSE_DAG_ID env var.")
    );
}
