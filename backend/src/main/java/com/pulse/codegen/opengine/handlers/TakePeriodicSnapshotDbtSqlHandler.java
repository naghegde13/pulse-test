package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;

/**
 * dbt-SQL emission for {@code take-periodic-snapshot} (SPEC #2 §C.1, incremental).
 *
 * <p>A point-in-time incremental model (NOT a dbt {@code {% snapshot %}} block —
 * that is {@code track-history-scd2}). Emits a
 * {@code {{ config(materialized='incremental', ...) }}} header then a SELECT that
 * stamps the snapshot columns onto the source rows.
 *
 * <p><b>FIX #3 (snapshot column set):</b> adds exactly four columns —
 * {@code ds} (the business date partition), {@code _pulse_processing_ts},
 * {@code _pulse_run_id}, {@code _pulse_snapshot_model} — matching the legacy
 * {@code generateSnapshotModelSql} column set.
 */
public final class TakePeriodicSnapshotDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.TAKE_PERIODIC_SNAPSHOT;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();

        String partitionColumn = config.getString("snapshot_partition_column");
        if (partitionColumn == null || partitionColumn.isBlank()) {
            partitionColumn = "ds";
        }
        List<String> businessKeys = config.getStringList("business_key");
        if (businessKeys.isEmpty()) {
            businessKeys = config.getStringList("unique_key");
        }

        StringBuilder uniqueKey = new StringBuilder("[");
        for (String k : businessKeys) {
            uniqueKey.append("'").append(k).append("', ");
        }
        uniqueKey.append("'").append(partitionColumn).append("']");

        StringBuilder sql = new StringBuilder();
        sql.append("{{ config(\n");
        sql.append("    materialized='incremental',\n");
        sql.append("    incremental_strategy='merge',\n");
        sql.append("    unique_key=").append(uniqueKey).append(",\n");
        sql.append("    partition_by=['").append(partitionColumn).append("'],\n");
        sql.append("    on_schema_change='fail'");
        appendOptional(sql, "alias", config.getString("alias"));
        appendOptional(sql, "file_format", config.getString("file_format"));
        appendStringList(sql, "cluster_by", config.getStringList("cluster_by"));
        appendStringList(sql, "tags", config.getStringList("tags"));
        sql.append("\n");
        sql.append(") }}\n\n");
        sql.append("SELECT\n");
        sql.append("    *,\n");
        sql.append("    CAST('{{ var(\"pulse_business_date\") }}' AS DATE) AS ").append(partitionColumn).append(",\n");
        sql.append("    current_timestamp() AS _pulse_processing_ts,\n");
        sql.append("    '{{ run_id }}' AS _pulse_run_id,\n");
        sql.append("    '{{ this.identifier }}' AS _pulse_snapshot_model\n");
        sql.append("FROM ").append(ref);
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
        sql.append(",\n    ").append(key).append("=[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("'").append(items.get(i)).append("'");
        }
        sql.append("]");
    }
}
