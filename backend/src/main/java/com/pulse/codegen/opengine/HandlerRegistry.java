package com.pulse.codegen.opengine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps (op, engine) to its {@link OpEmitHandler}. The codegen op-engine looks up
 * a handler by the op's name and the active engine; a missing handler is a
 * loud-fail ({@link EmissionException}) — codegen never guesses (ADR 0013).
 *
 * <p>Handlers are pure, stateless string emitters, so single shared instances
 * are safe. The registry is populated once at construction.
 */
public final class HandlerRegistry {

    /** Composite key: op-name + engine. */
    private record Key(String op, EmissionEngine engine) {}

    private final Map<Key, OpEmitHandler> handlers = new LinkedHashMap<>();

    public HandlerRegistry register(OpEmitHandler handler) {
        Key k = new Key(handler.opName(), handler.engine());
        if (handlers.containsKey(k)) {
            throw new IllegalStateException(
                    "duplicate handler for (" + k.op() + ", " + k.engine() + ")");
        }
        handlers.put(k, handler);
        return this;
    }

    /** The handler for (op, engine); loud-fail if none registered. */
    public OpEmitHandler get(String op, EmissionEngine engine) {
        OpEmitHandler h = handlers.get(new Key(op, engine));
        if (h == null) {
            throw new EmissionException(
                    "no emission handler for op '" + op + "' on engine " + engine);
        }
        return h;
    }

    public boolean has(String op, EmissionEngine engine) {
        return handlers.containsKey(new Key(op, engine));
    }

    public int size() {
        return handlers.size();
    }
}
