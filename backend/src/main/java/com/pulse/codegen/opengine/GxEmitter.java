package com.pulse.codegen.opengine;

import com.pulse.pipeline.opengine.OpList;

import java.util.List;

/**
 * Composes a Great Expectations checkpoint body from the GX ops of a blueprint's
 * op-list (SPEC #2 §C.1, §C.5): {@code check-data} (suite + checkpoint that
 * raises on failure when {@code on_failure=block}; quarantine &rarr; managed
 * side-table) and {@code emit-report} (append-only report table by default —
 * FIX #7). Each op's handler emits its GX fragment.
 *
 * <p>Mode-aware via {@link EmitContext} (side-tables written Iceberg-on-GCS for
 * GCP, Hive+Parquet for DPC); deterministic.
 */
public final class GxEmitter {

    private final HandlerRegistry registry;

    public GxEmitter(HandlerRegistry registry) {
        this.registry = registry;
    }

    public String emit(List<OpList.OpEntry> gxOps, ContextFactory ctxFor) {
        StringBuilder out = new StringBuilder();
        if (gxOps == null) return out.toString();
        for (OpList.OpEntry op : gxOps) {
            OpEmitHandler handler = registry.get(op.op(), EmissionEngine.GX);
            out.append(handler.emit(ctxFor.create(op)));
            if (out.length() == 0 || out.charAt(out.length() - 1) != '\n') out.append("\n");
        }
        return out.toString();
    }

    @FunctionalInterface
    public interface ContextFactory {
        EmitContext create(OpList.OpEntry op);
    }
}
