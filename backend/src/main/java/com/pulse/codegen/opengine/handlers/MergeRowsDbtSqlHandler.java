package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;

/**
 * dbt-SQL emission for {@code merge-rows} (SPEC #2 §C.1, incremental kind).
 *
 * <p>Emits a dbt incremental merge model body: a
 * {@code {{ config(materialized='incremental', incremental_strategy='merge',
 * unique_key=[...]) }}} header followed by {@code SELECT * FROM <ref>}. When an
 * {@code incremental_field} is configured an
 * {@code {% if is_incremental() %} WHERE ... {% endif %}} guard restricts the
 * incremental run to newer rows.
 *
 * <p>{@code unique_key} comes from {@code merge_keys}, rendered as a Python list
 * literal in declared order for byte-stability (ADR 0009).
 */
public final class MergeRowsDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.MERGE_ROWS;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        List<String> mergeKeys = config.getStringList("merge_keys");
        if (mergeKeys.isEmpty()) {
            mergeKeys = config.getStringList("merge_key");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("{{ config(\n");
        sql.append("    materialized='incremental',\n");
        sql.append("    incremental_strategy='merge',\n");
        sql.append("    on_schema_change='append_new_columns',\n");
        sql.append("    unique_key=").append(pythonList(mergeKeys));
        appendOptional(sql, "alias", config.getString("alias"));
        appendOptional(sql, "file_format", config.getString("file_format"));
        appendStringList(sql, "partition_by", config.getStringList("partition_by"));
        appendStringList(sql, "cluster_by", config.getStringList("cluster_by"));
        appendStringList(sql, "tags", config.getStringList("tags"));
        sql.append("\n) }}\n\n");
        sql.append("SELECT * FROM ").append(ref);

        String incField = config.getString("incremental_field");
        if (incField != null && !incField.isBlank()) {
            sql.append("\n{% if is_incremental() %}\n")
                    .append("WHERE ").append(incField.trim())
                    .append(" > (SELECT MAX(").append(incField.trim()).append(") FROM {{ this }})\n")
                    .append("{% endif %}");
        }
        return sql.toString();
    }

    private void appendOptional(StringBuilder sql, String key, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(",\n    ").append(key).append("='").append(value).append("'");
        }
    }

    private void appendStringList(StringBuilder sql, String key, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sql.append(",\n    ").append(key).append("=").append(pythonList(items));
    }

    private String pythonList(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("'").append(items.get(i)).append("'");
        }
        sb.append("]");
        return sb.toString();
    }
}
