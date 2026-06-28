package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code track-history-scd2} (SPEC #1 §B.1 rule 20) — Type-2 slowly-changing
 * dimension via a dbt snapshot.
 *
 * <p>OUT = IN followed by the dbt-snapshot system columns, in this exact order:
 * <ol>
 *   <li>{@code dbt_valid_from} — timestamp</li>
 *   <li>{@code dbt_valid_to} — timestamp</li>
 *   <li>{@code dbt_scd_id} — string</li>
 *   <li>{@code dbt_updated_at} — timestamp</li>
 * </ol>
 *
 * <p><b>FIX #2</b>: the legacy rule was transposed — it emitted
 * {@code valid_from}/{@code valid_to}/{@code is_current}. dbt snapshots produce
 * the four {@code dbt_*} columns above; {@code is_current} is NOT emitted.
 */
public final class TrackHistoryScd2Op implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.TRACK_HISTORY_SCD2;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        List<ColumnModel> out = new ArrayList<>();
        if (in != null) {
            out.addAll(in.columns());
        }
        // FIX #2: dbt-snapshot system columns in this exact order.
        out.add(ColumnModel.simple("dbt_valid_from", "timestamp"));
        out.add(ColumnModel.simple("dbt_valid_to", "timestamp"));
        out.add(ColumnModel.simple("dbt_scd_id", "string"));
        out.add(ColumnModel.simple("dbt_updated_at", "timestamp"));
        return new Schema(out);
    }
}
