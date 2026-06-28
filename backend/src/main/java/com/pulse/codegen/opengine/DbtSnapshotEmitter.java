package com.pulse.codegen.opengine;

import com.pulse.pipeline.opengine.OpList;

/**
 * Composes a dbt {@code {% snapshot %}} block for a {@code track-history-scd2} op
 * (SPEC #2 §C.1): the dbt-snapshot kind. The snapshot strategy manages the
 * {@code dbt_valid_from/dbt_valid_to/dbt_scd_id/dbt_updated_at} system columns;
 * FIX #10 drops the redundant custom {@code effective_from/to} columns.
 *
 * <p>Mode-aware (snapshot materializes Iceberg/BQ on GCP, Parquet on DPC) via the
 * handler's {@link EmitContext}.
 */
public final class DbtSnapshotEmitter {

    private final HandlerRegistry registry;

    public DbtSnapshotEmitter(HandlerRegistry registry) {
        this.registry = registry;
    }

    /** Emit the snapshot block for the {@code track-history-scd2} op. */
    public String emit(OpList.OpEntry scd2Op, EmitContext ctx) {
        OpEmitHandler handler = registry.get(scd2Op.op(), EmissionEngine.DBT_SNAPSHOT);
        return handler.emit(ctx);
    }
}
