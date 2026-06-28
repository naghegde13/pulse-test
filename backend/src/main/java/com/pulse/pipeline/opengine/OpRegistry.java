package com.pulse.pipeline.opengine;

import com.pulse.pipeline.opengine.ops.AddAuditColumnsOp;
import com.pulse.pipeline.opengine.ops.AddColumnOp;
import com.pulse.pipeline.opengine.ops.AdvanceTimeOp;
import com.pulse.pipeline.opengine.ops.BuildStructOp;
import com.pulse.pipeline.opengine.ops.ChangeTypesOp;
import com.pulse.pipeline.opengine.ops.CheckDataOp;
import com.pulse.pipeline.opengine.ops.DeduplicateOp;
import com.pulse.pipeline.opengine.ops.DistinctUnionOp;
import com.pulse.pipeline.opengine.ops.DropColumnsOp;
import com.pulse.pipeline.opengine.ops.EmitReportOp;
import com.pulse.pipeline.opengine.ops.FilterRowsOp;
import com.pulse.pipeline.opengine.ops.FlattenJsonOp;
import com.pulse.pipeline.opengine.ops.GroupAndAggregateOp;
import com.pulse.pipeline.opengine.ops.InvokeRemoteOp;
import com.pulse.pipeline.opengine.ops.JoinOp;
import com.pulse.pipeline.opengine.ops.KeepColumnsOp;
import com.pulse.pipeline.opengine.ops.MaskColumnsOp;
import com.pulse.pipeline.opengine.ops.MergeRowsOp;
import com.pulse.pipeline.opengine.ops.ReadSourceOp;
import com.pulse.pipeline.opengine.ops.RenameColumnsOp;
import com.pulse.pipeline.opengine.ops.RollbackOp;
import com.pulse.pipeline.opengine.ops.RouteRowsOp;
import com.pulse.pipeline.opengine.ops.SampleLimitOp;
import com.pulse.pipeline.opengine.ops.ScheduleAndTriggersOp;
import com.pulse.pipeline.opengine.ops.SenseOp;
import com.pulse.pipeline.opengine.ops.SortOp;
import com.pulse.pipeline.opengine.ops.SqlModelOp;
import com.pulse.pipeline.opengine.ops.TakePeriodicSnapshotOp;
import com.pulse.pipeline.opengine.ops.TrackHistoryScd2Op;
import com.pulse.pipeline.opengine.ops.TransformValuesOp;
import com.pulse.pipeline.opengine.ops.UnionAllOp;
import com.pulse.pipeline.opengine.ops.WriteSinkOp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps each op name to its {@link SchemaOp} schema-effect rule instance. The
 * registry is closed: it MUST contain exactly the 32-op vocabulary
 * ({@link OpVocabulary#ALL}). A request for an op not in the registry is a
 * loud-fail ({@link OpEngineException}, SPEC #1 §B.3).
 *
 * <p>The op rules are pure, stateless functions, so single shared instances are
 * safe. The registry is plain (constructed once, used by {@code SchemaOpEngine}).
 */
public final class OpRegistry {

    private final Map<String, SchemaOp> ops = new LinkedHashMap<>();

    /** Legacy/test path: sql-model resolves declared-only (no Calcite seam). */
    public OpRegistry() {
        this(null);
    }

    /**
     * Calcite-primary path: {@code sql-model} delegates OUT-schema resolution to
     * {@code sqlModelSchemaService} (Calcite derive → declared §A.6 fallback →
     * loud-fail, ADR 0011). All other ops are pure dependency-free instances.
     */
    public OpRegistry(SqlModelSchemaService sqlModelSchemaService) {
        register(new AddColumnOp());
        register(new TransformValuesOp());
        register(new DropColumnsOp());
        register(new KeepColumnsOp());
        register(new RenameColumnsOp());
        register(new ChangeTypesOp());
        register(new MaskColumnsOp());
        register(new FlattenJsonOp());
        register(new BuildStructOp());
        register(new JoinOp());
        register(new GroupAndAggregateOp());
        register(new UnionAllOp());
        register(new DistinctUnionOp());
        register(new SortOp());
        register(new SampleLimitOp());
        register(new FilterRowsOp());
        register(new DeduplicateOp());
        register(new RouteRowsOp());
        register(new MergeRowsOp());
        register(new TrackHistoryScd2Op());
        register(new TakePeriodicSnapshotOp());
        register(new CheckDataOp());
        register(new EmitReportOp());
        register(new ReadSourceOp());
        register(new AddAuditColumnsOp());
        register(new WriteSinkOp());
        register(new SqlModelOp(sqlModelSchemaService));
        register(new SenseOp());
        register(new ScheduleAndTriggersOp());
        register(new RollbackOp());
        register(new AdvanceTimeOp());
        register(new InvokeRemoteOp());
        // Invariant: the registry covers exactly the 32-op closed vocabulary.
        if (ops.size() != OpVocabulary.ALL.size() || !ops.keySet().equals(OpVocabulary.ALL)) {
            throw new IllegalStateException(
                    "OpRegistry does not cover exactly the 32-op vocabulary (have "
                    + ops.keySet() + ")");
        }
    }

    private void register(SchemaOp op) {
        if (!OpVocabulary.isValid(op.opName())) {
            throw new IllegalStateException(
                    "op '" + op.opName() + "' is not in the 32-op vocabulary");
        }
        if (ops.containsKey(op.opName())) {
            throw new IllegalStateException("duplicate op registration: " + op.opName());
        }
        ops.put(op.opName(), op);
    }

    /** The schema-effect rule for an op; loud-fail if there is no rule. */
    public SchemaOp get(String opName) {
        SchemaOp op = ops.get(opName);
        if (op == null) {
            throw new OpEngineException(
                    "no schema rule registered for op '" + opName + "'");
        }
        return op;
    }

    public boolean has(String opName) {
        return ops.containsKey(opName);
    }

    public int size() {
        return ops.size();
    }
}
