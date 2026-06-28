package com.pulse.pipeline.opengine;

/**
 * Design-time loud-fail from the op-engine (SPEC #1 §B.3): an op outside the
 * 32-op vocabulary, a malformed op-list, a param-ref to a name absent from the
 * derived surface, an unknown blueprint with no op-list and no rule, or any
 * other condition that must surface clearly and block the blueprint's use rather
 * than silently pass through or guess.
 *
 * <p>There is deliberately no LLM fallback. A blueprint whose metadata is
 * incomplete fails here.
 */
public class OpEngineException extends RuntimeException {

    public OpEngineException(String message) {
        super(message);
    }

    public OpEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
