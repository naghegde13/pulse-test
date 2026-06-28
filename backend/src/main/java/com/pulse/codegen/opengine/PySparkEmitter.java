package com.pulse.codegen.opengine;

import com.pulse.pipeline.opengine.OpList;

import java.util.List;

/**
 * Composes a PySpark job body from the PySpark ops of a blueprint's op-list
 * (SPEC #2 §C.1): {@code read-source} &rarr; {@code add-audit-columns} &rarr;
 * {@code write-sink}, plus any chained DataFrame ops. Each op's handler appends
 * to the running {@code df} variable (chained DataFrame transforms).
 *
 * <p>Mode-aware via {@link EmitContext} (handlers branch GCP vs DPC); deterministic.
 */
public final class PySparkEmitter {

    private final HandlerRegistry registry;

    public PySparkEmitter(HandlerRegistry registry) {
        this.registry = registry;
    }

    /**
     * Emit the PySpark op fragments in order, each operating on / reassigning the
     * running {@code df} variable.
     *
     * @param pysparkOps the op-list's PySpark ops in order (filtered to PYSPARK engine)
     * @param ctxFor     produces each op's EmitContext
     */
    public String emit(List<OpList.OpEntry> pysparkOps, ContextFactory ctxFor) {
        StringBuilder py = new StringBuilder();
        if (pysparkOps == null) return py.toString();
        for (OpList.OpEntry op : pysparkOps) {
            OpEmitHandler handler = registry.get(op.op(), EmissionEngine.PYSPARK);
            EmitContext ctx = ctxFor.create(op);
            py.append(handler.emit(ctx));
            if (py.length() == 0 || py.charAt(py.length() - 1) != '\n') py.append("\n");
        }
        return py.toString();
    }

    @FunctionalInterface
    public interface ContextFactory {
        EmitContext create(OpList.OpEntry op);
    }
}
