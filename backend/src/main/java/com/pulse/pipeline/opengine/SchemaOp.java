package com.pulse.pipeline.opengine;

import java.util.List;

/**
 * The schema-effect rule for one op (SPEC #1 §B.1): given the input column
 * set(s) and the op's resolved config, produce the output column set. One
 * implementation per op under {@code com.pulse.pipeline.opengine.ops}.
 *
 * <p>Rules are pure functions of (input columns, config). They are deterministic
 * (no LLM, no I/O) — the design-time column authority. An op that cannot be
 * applied (e.g. a required config absent in a way the spec does not make a
 * passthrough default) throws {@link OpEngineException}.
 *
 * <p>Most ops are single-input ({@code in2} is null). Only {@code join} consumes
 * a secondary input. {@code route-rows} produces the same schema on each of N
 * output ports — its primary {@link #apply} returns that single carried schema
 * (the per-port fan-out is the engine's concern).
 */
public interface SchemaOp {

    /** The canonical op name (one of the 32, see {@link OpVocabulary}). */
    String opName();

    /**
     * Apply the schema-effect rule.
     *
     * @param in  the primary input schema (never null; may be empty)
     * @param in2 the secondary input schema (only for {@code join}; null otherwise)
     * @param cfg the op's resolved config (param-refs already substituted)
     * @return the output schema
     */
    Schema apply(Schema in, Schema in2, ResolvedConfig cfg);

    /**
     * For an op that fans out to N output ports (only {@code route-rows} today),
     * the per-port schemas. Default: the single {@link #apply} result, repeated
     * for as many ports as the engine asks for. Most ops do not override this.
     */
    default List<Schema> applyMulti(Schema in, Schema in2, ResolvedConfig cfg, int ports) {
        Schema out = apply(in, in2, cfg);
        return java.util.Collections.nCopies(Math.max(1, ports), out);
    }
}
