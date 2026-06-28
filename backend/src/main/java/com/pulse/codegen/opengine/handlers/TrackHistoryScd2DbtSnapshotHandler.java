package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;

/**
 * dbt-SNAPSHOT emission for {@code track-history-scd2} (SPEC #2 §C.1).
 *
 * <p>Emits a dbt {@code {% snapshot <name> %} ... {% endsnapshot %}} block. The
 * snapshot strategy manages {@code dbt_valid_from} / {@code dbt_valid_to} /
 * {@code dbt_scd_id} / {@code dbt_updated_at} automatically.
 *
 * <p>Strategy selection: when {@code tracked_columns} is configured the block uses
 * {@code strategy='check'} with {@code check_cols=[...]}; otherwise
 * {@code strategy='timestamp'} with {@code updated_at} (default
 * {@code updated_at}). {@code unique_key} comes from {@code business_key}.
 *
 * <p><b>FIX #10:</b> does NOT emit redundant custom {@code effective_from} /
 * {@code effective_to} columns — the inner select is a plain
 * {@code SELECT * FROM <ref>}; dbt manages SCD2 validity columns.
 */
public final class TrackHistoryScd2DbtSnapshotHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.TRACK_HISTORY_SCD2;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SNAPSHOT;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();

        String snapshotName = config.getString("snapshot_name");
        if (snapshotName == null || snapshotName.isBlank()) {
            snapshotName = "scd2_snapshot";
        }
        String targetSchema = config.getString("target_schema");
        if (targetSchema == null || targetSchema.isBlank()) {
            targetSchema = "snapshots";
        }
        List<String> businessKey = config.getStringList("business_key");
        String uniqueKey = businessKey.isEmpty() ? "id" : String.join(", ", businessKey);
        List<String> trackedColumns = config.getStringList("tracked_columns");

        StringBuilder sql = new StringBuilder();
        sql.append("{% snapshot ").append(snapshotName).append(" %}\n\n");
        sql.append("{{ config(\n");
        sql.append("    target_schema='").append(targetSchema).append("',\n");
        sql.append("    unique_key='").append(uniqueKey).append("',\n");
        String fileFormat = config.getString("file_format");
        if (fileFormat != null && !fileFormat.isBlank()) {
            sql.append("    file_format='").append(fileFormat).append("',\n");
        }
        if (!trackedColumns.isEmpty()) {
            sql.append("    strategy='check',\n");
            sql.append("    check_cols=[");
            for (int i = 0; i < trackedColumns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("'").append(trackedColumns.get(i)).append("'");
            }
            sql.append("]\n");
        } else {
            String updatedAt = config.getString("updated_at");
            if (updatedAt == null || updatedAt.isBlank()) {
                updatedAt = "updated_at";
            }
            sql.append("    strategy='timestamp',\n");
            sql.append("    updated_at='").append(updatedAt).append("'\n");
        }
        List<String> tags = config.getStringList("tags");
        if (!tags.isEmpty()) {
            if (sql.charAt(sql.length() - 1) != '\n') {
                sql.append(",\n");
            } else {
                int previous = sql.length() - 2;
                if (previous >= 0 && sql.charAt(previous) != ',') {
                    sql.setLength(sql.length() - 1);
                    sql.append(",\n");
                }
            }
            sql.append("    tags=[");
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("'").append(tags.get(i)).append("'");
            }
            sql.append("]\n");
        }
        sql.append(") }}\n\n");
        // FIX #10: no custom effective_from/effective_to; dbt manages SCD2 validity.
        sql.append("SELECT * FROM ").append(ref).append("\n\n");
        sql.append("{% endsnapshot %}");
        return sql.toString();
    }
}
