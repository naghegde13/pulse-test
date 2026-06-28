package com.pulse.codegen.opengine;

import com.pulse.pipeline.opengine.OpList;

import java.util.List;

/**
 * Composes a dbt-SQL model from the dbt-SQL ops of a blueprint's op-list (SPEC
 * #2 §C, §C.1). Consecutive same-engine, same-layer ops are fused into chained
 * CTEs (the §C "fuse consecutive same-engine ops into ephemeral CTEs" rule);
 * each CTE is one op's handler fragment reading from the previous step.
 *
 * <p>The emitter is Mode-aware via {@link EmitContext#mode()} (handlers branch
 * GCP vs DPC); it is deterministic (ordered ops, no map iteration).
 */
public final class DbtSqlEmitter {

    private final HandlerRegistry registry;

    public DbtSqlEmitter(HandlerRegistry registry) {
        this.registry = registry;
    }

    /**
     * Emit the dbt-SQL body as a chain of CTEs, one per dbt-SQL op, ending in a
     * final {@code SELECT * FROM <last_cte>}.
     *
     * @param dbtOps   the op-list's dbt-SQL ops in order (already filtered to DBT_SQL engine)
     * @param baseRef  the relation the first op reads from (e.g. {@code ref('upstream')})
     * @param ctxFor   a function producing the per-op EmitContext (with the running upstreamRef)
     */
    public String emit(List<OpList.OpEntry> dbtOps, String baseRef, ContextFactory ctxFor) {
        if (dbtOps == null || dbtOps.isEmpty()) {
            return "SELECT *\nFROM " + baseRef + "\n";
        }
        StringBuilder sql = new StringBuilder();
        String prev = baseRef;
        for (int i = 0; i < dbtOps.size(); i++) {
            OpList.OpEntry op = dbtOps.get(i);
            String cte = "step_" + (i + 1) + "_" + op.op().replace('-', '_');
            EmitContext ctx = ctxFor.create(op, prev);
            OpEmitHandler handler = registry.get(op.op(), EmissionEngine.DBT_SQL);
            String fragment = handler.emit(ctx);
            sql.append(i == 0 ? "WITH " : ",\n").append(cte).append(" AS (\n")
               .append(indent(fragment))
               .append("\n)");
            prev = cte;
        }
        sql.append("\nSELECT *\nFROM ").append(prev).append("\n");
        return sql.toString();
    }

    private String indent(String fragment) {
        StringBuilder out = new StringBuilder();
        for (String line : fragment.split("\n", -1)) {
            out.append("    ").append(line).append("\n");
        }
        // drop the trailing newline added by the loop
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /** Produces the per-op EmitContext given the op and the running upstream ref. */
    @FunctionalInterface
    public interface ContextFactory {
        EmitContext create(OpList.OpEntry op, String upstreamRef);
    }
}
