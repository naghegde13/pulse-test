package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code route-rows} (SPEC #1 §B.1 rule 18) — split ROWS (not columns) across N
 * branch output ports based on row-level predicates.
 *
 * <p><b>FIX #1</b>: routing partitions rows, so the schema is unchanged on every
 * branch. OUT = the INPUT schema carried verbatim to EACH dynamic output port
 * (one port per branch). The legacy rule wrongly treated this as a
 * column-splitting op.
 *
 * <p>{@link #apply} returns the input schema unchanged (passthrough — the single
 * carried per-port schema). {@link #applyMulti} returns the same input schema
 * repeated once per branch. The branch count comes from {@code config.branches}
 * (a list of branch specs) when present; otherwise the engine-supplied
 * {@code ports} count is used. At least one copy is always returned.
 */
public final class RouteRowsOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.ROUTE_ROWS;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        // Routing splits rows, not columns — the schema is carried unchanged.
        return in == null ? Schema.empty() : in;
    }

    @Override
    public List<Schema> applyMulti(Schema in, Schema in2, ResolvedConfig cfg, int ports) {
        Schema carried = apply(in, in2, cfg);
        int branchCount = resolveBranchCount(cfg, ports);
        List<Schema> out = new ArrayList<>(branchCount);
        for (int i = 0; i < branchCount; i++) {
            out.add(carried);
        }
        return out;
    }

    /**
     * Branch count from {@code config.branches} when present, else the
     * engine-supplied {@code ports}. Never less than 1.
     */
    private static int resolveBranchCount(ResolvedConfig cfg, int ports) {
        int branches = cfg == null ? 0 : cfg.getMapList("branches").size();
        int count = branches > 0 ? branches : ports;
        return Math.max(1, count);
    }
}
