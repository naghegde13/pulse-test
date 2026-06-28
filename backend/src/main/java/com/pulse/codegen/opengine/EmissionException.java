package com.pulse.codegen.opengine;

/**
 * Build-time loud-fail from the codegen op-engine (ADR 0013): a missing handler
 * for an (op, engine), an op-list that cannot be composed for an engine, or any
 * other emission condition that must fail rather than guess. There is no LLM
 * fallback in codegen.
 */
public class EmissionException extends RuntimeException {
    public EmissionException(String message) {
        super(message);
    }
    public EmissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
