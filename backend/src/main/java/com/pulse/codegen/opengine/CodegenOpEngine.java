package com.pulse.codegen.opengine;

import com.pulse.codegen.opengine.handlers.AddAuditColumnsPySparkHandler;
import com.pulse.codegen.opengine.handlers.AddColumnDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.AdvanceTimeDagOnlyHandler;
import com.pulse.codegen.opengine.handlers.BuildStructDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.ChangeTypesDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.CheckDataGxHandler;
import com.pulse.codegen.opengine.handlers.DeduplicateDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.DistinctUnionDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.DropColumnsDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.EmitReportGxHandler;
import com.pulse.codegen.opengine.handlers.FilterRowsDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.FlattenJsonDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.GroupAndAggregateDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.InvokeRemoteDagOnlyHandler;
import com.pulse.codegen.opengine.handlers.JoinDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.KeepColumnsDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.MaskColumnsDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.MergeRowsDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.ReadSourcePySparkHandler;
import com.pulse.codegen.opengine.handlers.RenameColumnsDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.RollbackDagOnlyHandler;
import com.pulse.codegen.opengine.handlers.RouteRowsDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.SampleLimitDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.ScheduleAndTriggersDagOnlyHandler;
import com.pulse.codegen.opengine.handlers.SenseDagOnlyHandler;
import com.pulse.codegen.opengine.handlers.SortDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.SqlModelDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.TakePeriodicSnapshotDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.TrackHistoryScd2DbtSnapshotHandler;
import com.pulse.codegen.opengine.handlers.TransformValuesDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.UnionAllDbtSqlHandler;
import com.pulse.codegen.opengine.handlers.WriteSinkPySparkHandler;
import com.pulse.codegen.opengine.handlers.WriteSinkDbtSqlHandler;
import org.springframework.stereotype.Service;

/**
 * The build-time codegen op-engine (SPEC #2 §C): the single assembly point that
 * wires the 32 per-(op,engine) {@link OpEmitHandler}s into a {@link HandlerRegistry}
 * and exposes the 5 engine emitters (dbt-SQL / PySpark / GX / dbt-snapshot /
 * DAG-only). A blueprint's compute artifact = the deterministic composition of
 * its op-list handlers (no LLM, ADR 0013).
 *
 * <p>This service is the seam {@code CodeGenerationService} will call to replace
 * its per-blueprint hand-written codegen branches. It depends only on the pure
 * handler classes + {@link ModeResolver}; it is stateless.
 *
 * <p><b>Two-package split (intentional):</b> design-time schema lives in
 * {@code com.pulse.pipeline.opengine}; this build-time emission lives in
 * {@code com.pulse.codegen.opengine}. They are NOT unified.
 */
@Service
public class CodegenOpEngine {

    private final ModeResolver modeResolver;
    private final HandlerRegistry registry;
    private final DbtSqlEmitter dbtSqlEmitter;
    private final PySparkEmitter pySparkEmitter;
    private final GxEmitter gxEmitter;
    private final DbtSnapshotEmitter dbtSnapshotEmitter;
    private final DagOnlyEmitter dagOnlyEmitter;
    private final DagAssembler dagAssembler;

    public CodegenOpEngine(ModeResolver modeResolver) {
        this.modeResolver = modeResolver;
        this.registry = buildRegistry();
        this.dbtSqlEmitter = new DbtSqlEmitter(registry);
        this.pySparkEmitter = new PySparkEmitter(registry);
        this.gxEmitter = new GxEmitter(registry);
        this.dbtSnapshotEmitter = new DbtSnapshotEmitter(registry);
        this.dagOnlyEmitter = new DagOnlyEmitter(registry);
        this.dagAssembler = new DagAssembler(dagOnlyEmitter);
    }

    public ModeResolver modeResolver() { return modeResolver; }
    public HandlerRegistry registry() { return registry; }
    public DbtSqlEmitter dbtSql() { return dbtSqlEmitter; }
    public PySparkEmitter pySpark() { return pySparkEmitter; }
    public GxEmitter gx() { return gxEmitter; }
    public DbtSnapshotEmitter dbtSnapshot() { return dbtSnapshotEmitter; }
    public DagOnlyEmitter dagOnly() { return dagOnlyEmitter; }
    public DagAssembler dagAssembler() { return dagAssembler; }

    private static HandlerRegistry buildRegistry() {
        HandlerRegistry r = new HandlerRegistry();
        // dbt-SQL column ops
        r.register(new AddColumnDbtSqlHandler());
        r.register(new TransformValuesDbtSqlHandler());
        r.register(new DropColumnsDbtSqlHandler());
        r.register(new KeepColumnsDbtSqlHandler());
        r.register(new RenameColumnsDbtSqlHandler());
        r.register(new ChangeTypesDbtSqlHandler());
        r.register(new MaskColumnsDbtSqlHandler());
        r.register(new FlattenJsonDbtSqlHandler());
        r.register(new BuildStructDbtSqlHandler());
        // dbt-SQL combine / reshape / row / modeling ops
        r.register(new JoinDbtSqlHandler());
        r.register(new GroupAndAggregateDbtSqlHandler());
        r.register(new UnionAllDbtSqlHandler());
        r.register(new DistinctUnionDbtSqlHandler());
        r.register(new SortDbtSqlHandler());
        r.register(new SampleLimitDbtSqlHandler());
        r.register(new FilterRowsDbtSqlHandler());
        r.register(new DeduplicateDbtSqlHandler());
        r.register(new RouteRowsDbtSqlHandler());
        r.register(new MergeRowsDbtSqlHandler());
        r.register(new TakePeriodicSnapshotDbtSqlHandler());
        r.register(new SqlModelDbtSqlHandler());
        r.register(new WriteSinkDbtSqlHandler());
        // dbt-snapshot
        r.register(new TrackHistoryScd2DbtSnapshotHandler());
        // PySpark movement ops
        r.register(new ReadSourcePySparkHandler());
        r.register(new AddAuditColumnsPySparkHandler());
        r.register(new WriteSinkPySparkHandler());
        // GX quality ops
        r.register(new CheckDataGxHandler());
        r.register(new EmitReportGxHandler());
        // DAG-only control ops
        r.register(new SenseDagOnlyHandler());
        r.register(new ScheduleAndTriggersDagOnlyHandler());
        r.register(new RollbackDagOnlyHandler());
        r.register(new AdvanceTimeDagOnlyHandler());
        r.register(new InvokeRemoteDagOnlyHandler());
        // Invariant: the closed vocabulary has 32 ops, but some ops have handlers on
        // multiple engines (for example write-sink is PySpark and dbt publish).
        if (r.size() < 32) {
            throw new IllegalStateException(
                    "CodegenOpEngine expected at least 32 handlers, registered " + r.size());
        }
        return r;
    }
}
