package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code take-periodic-snapshot} (SPEC #1 §B.1 rule 21) — append a
 * point-in-time partition + run-provenance columns to the source.
 *
 * <p>OUT = IN (source) followed by, in this exact order:
 * <ol>
 *   <li>{@code ds} — date (the snapshot partition)</li>
 *   <li>{@code _pulse_processing_ts} — timestamp</li>
 *   <li>{@code _pulse_run_id} — string</li>
 *   <li>{@code _pulse_snapshot_model} — string</li>
 * </ol>
 *
 * <p><b>FIX #3</b>: the legacy rule was transposed — it emitted the
 * {@code dbt_valid_*} SCD-2 columns. A periodic snapshot is not an SCD-2 history;
 * the {@code dbt_valid_*} columns are NOT emitted here.
 */
public final class TakePeriodicSnapshotOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.TAKE_PERIODIC_SNAPSHOT;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        List<ColumnModel> out = new ArrayList<>();
        if (in != null) {
            out.addAll(in.columns());
        }
        // FIX #3: periodic-snapshot system columns in this exact order.
        out.add(ColumnModel.simple("ds", "date"));
        out.add(ColumnModel.simple("_pulse_processing_ts", "timestamp"));
        out.add(ColumnModel.simple("_pulse_run_id", "string"));
        out.add(ColumnModel.simple("_pulse_snapshot_model", "string"));
        return new Schema(out);
    }
}
