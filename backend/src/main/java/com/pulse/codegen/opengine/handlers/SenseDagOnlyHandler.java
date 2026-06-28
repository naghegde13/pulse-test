package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

/**
 * DAG-only emission for the {@code sense} control op (SPEC #2 §C.1, control-ops-split).
 *
 * <p>A control op emits NO compute artifact — only its distinct Airflow DAG element.
 * For {@code sense} that element is an Airflow <b>sensor</b>, selected by
 * {@code sense_type} (the 3 sensing patterns, ADR 0022):
 * <ul>
 *   <li>{@code file} &rarr; on GCP a {@code GCSObjectExistenceSensor} (Composer/GCS
 *       flavor); on DPC a plain {@code FileSensor}.</li>
 *   <li>{@code sql_query} &rarr; a {@code SqlSensor}.</li>
 *   <li>{@code trigger} &rarr; an {@code ExternalTaskSensor}.</li>
 * </ul>
 * With no/unknown {@code sense_type} it degrades to a {@code FileSensor} (the
 * file-sensing default). Mode-aware (Composer/GCS vs plain-Airflow flavors); fully
 * deterministic (ADR 0009).
 */
public final class SenseDagOnlyHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.SENSE;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DAG_ONLY;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String taskId = orDefault(config.getString("task_id"), "sense");
        String senseType = config.getString("sense_type");
        if (senseType == null) {
            senseType = "file";
        }
        return switch (senseType) {
            case "sql_query" -> sqlSensor(taskId, config);
            case "trigger" -> externalTaskSensor(taskId, config);
            default -> fileSensor(taskId, config, ctx.mode());
        };
    }

    private String fileSensor(String taskId, ResolvedConfig config, Mode mode) {
        if (mode.isGcp()) {
            String bucket = orDefault(config.getString("bucket"), "");
            String object = orDefault(config.getString("object"), "");
            return taskId + " = GCSObjectExistenceSensor("
                    + "task_id='" + taskId + "', "
                    + "bucket='" + bucket + "', "
                    + "object='" + object + "')";
        }
        String filepath = orDefault(config.getString("filepath"), "");
        return taskId + " = FileSensor("
                + "task_id='" + taskId + "', "
                + "filepath='" + filepath + "')";
    }

    private String sqlSensor(String taskId, ResolvedConfig config) {
        String connId = orDefault(config.getString("conn_id"), "");
        String sql = orDefault(config.getString("sql"), "");
        return taskId + " = SqlSensor("
                + "task_id='" + taskId + "', "
                + "conn_id='" + connId + "', "
                + "sql='" + sql + "')";
    }

    private String externalTaskSensor(String taskId, ResolvedConfig config) {
        String externalDagId = orDefault(config.getString("external_dag_id"), "");
        String externalTaskId = orDefault(config.getString("external_task_id"), "");
        return taskId + " = ExternalTaskSensor("
                + "task_id='" + taskId + "', "
                + "external_dag_id='" + externalDagId + "', "
                + "external_task_id='" + externalTaskId + "')";
    }

    private static String orDefault(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
