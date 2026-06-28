package com.pulse.codegen.opengine;

/**
 * Emits one op's code fragment for one engine (SPEC #2 §C.1). One implementation
 * per (op, applicable engine) cell of the §C.1 table. A blueprint's compute
 * artifact = the deterministic composition of its op-list handlers (no LLM,
 * ADR 0013).
 *
 * <p>Fragments are pure deterministic strings (ADR 0009 byte-exactness): no
 * map-iteration order, ordered schema from {@link EmitContext}, explicit ORDER BY
 * tiebreakers on any dedup/rank.
 */
public interface OpEmitHandler {

    /** The op this handler emits (one of the 32, see {@code OpVocabulary}). */
    String opName();

    /** The engine this handler targets. */
    EmissionEngine engine();

    /** Emit the op's code fragment for the given context (Mode-aware). */
    String emit(EmitContext ctx);
}
