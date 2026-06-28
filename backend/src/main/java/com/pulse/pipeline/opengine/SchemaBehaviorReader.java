package com.pulse.pipeline.opengine;

import com.pulse.blueprint.model.Blueprint;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Reads a {@link Blueprint}'s {@code schema_behavior} op-list and derives its
 * param surface (SPEC #1 §A, Phase 0 deliverable). Given a blueprint, returns a
 * typed {@link OpList} + {@link ParamSurface}.
 *
 * <p>This is a pure reader/parser — it changes no call sites and applies no
 * schema effect (that is {@code SchemaOpEngine}, Phase 1). It loud-fails (via
 * {@link OpEngineException}) on a malformed op-list, an op outside the 32-op
 * vocabulary, or a param-ref with no {@code params_schema} descriptor.
 */
@Service
public class SchemaBehaviorReader {

    /** The reader's combined result: the parsed op-list + its derived param surface. */
    public record BehaviorView(OpList opList, ParamSurface surface) {}

    /**
     * True iff the blueprint carries a new-shape op-list ({@code schema_behavior}
     * has an {@code ops} array). Used by the transitional shim to decide whether
     * to route to the op-engine or to the legacy switch.
     */
    public boolean hasOpList(Blueprint bp) {
        return bp != null && OpList.isOpList(bp.getSchemaBehavior());
    }

    /**
     * Parse + derive for a blueprint. Loud-fails if the blueprint has no op-list
     * (callers that want to fall back should gate on {@link #hasOpList} first).
     */
    public BehaviorView read(Blueprint bp) {
        if (bp == null) {
            throw new OpEngineException("cannot read schema_behavior of a null blueprint");
        }
        Map<String, Object> sb = bp.getSchemaBehavior();
        if (!OpList.isOpList(sb)) {
            throw new OpEngineException(
                    "blueprint '" + bp.getBlueprintKey()
                    + "' has no op-list in schema_behavior (cannot resolve its schema via the op-engine)");
        }
        OpList opList = OpList.parse(sb);
        ParamSurface surface = ParamSurface.derive(opList, bp.getParamsSchema());
        return new BehaviorView(opList, surface);
    }

    /** Parse only (no param surface) — useful where params_schema is irrelevant. */
    public OpList readOpList(Blueprint bp) {
        if (bp == null) {
            throw new OpEngineException("cannot read schema_behavior of a null blueprint");
        }
        return OpList.parse(bp.getSchemaBehavior());
    }
}
