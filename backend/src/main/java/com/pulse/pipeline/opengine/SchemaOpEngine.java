package com.pulse.pipeline.opengine;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The design-time op-engine (SPEC #1 §B intro): walks a blueprint's
 * {@code schema_behavior.ops} in order and applies each op's schema-effect rule
 * to the running column set, producing the blueprint's output schema. This is
 * the design-time column authority that replaces the hardcoded
 * {@code switch (key)} in {@code SchemaPropagationService.deriveBaseOutputSchema}.
 *
 * <p>No LLM. An op outside the 32 vocabulary, a malformed op-list, or an op with
 * no rule is a loud-fail ({@link OpEngineException}, SPEC #1 §B.3) — never a
 * silent passthrough, never a guess.
 *
 * <p>The engine is stateless; the {@link OpRegistry} of pure op-rule instances
 * is shared.
 */
@Service
public class SchemaOpEngine {

    private final OpRegistry registry;

    /** Calcite-primary: {@code sql-model} resolves via {@link SqlModelSchemaService}. */
    public SchemaOpEngine(SqlModelSchemaService sqlModelSchemaService) {
        this.registry = new OpRegistry(sqlModelSchemaService);
    }

    public OpRegistry registry() {
        return registry;
    }

    /**
     * Apply a parsed op-list to the input schema(s), walking ops in order.
     *
     * <p>The "running schema" starts as {@code primaryInput} and each op's output
     * becomes the next op's input. The secondary input ({@code secondaryInput})
     * is consumed only by a {@code join} op (its rule reads {@code in2}); other
     * ops ignore it. For a control op-list (no data ops) the result is an empty
     * schema.
     *
     * @param opList         the parsed op-list
     * @param surface        the blueprint's derived param surface (legal param names)
     * @param instanceParams the running instance's params (name -> value)
     * @param primaryInput   the primary input schema (never null; may be empty)
     * @param secondaryInput the secondary input schema (for join; may be null)
     * @return the blueprint's output schema after applying all ops in order
     */
    public Schema applyOpList(OpList opList,
                              ParamSurface surface,
                              Map<String, Object> instanceParams,
                              Schema primaryInput,
                              Schema secondaryInput) {
        if (opList == null) {
            throw new OpEngineException("cannot apply a null op-list");
        }
        Schema running = primaryInput == null ? Schema.empty() : primaryInput;
        for (OpList.OpEntry entry : opList.ops()) {
            SchemaOp op = registry.get(entry.op());
            Map<String, Object> resolved =
                    ParamResolver.resolve(entry.op(), entry.config(), instanceParams, surface);
            ResolvedConfig cfg = new ResolvedConfig(resolved);
            // Only join consumes the secondary input; pass it for every op (others ignore it).
            running = op.apply(running, secondaryInput, cfg);
            if (running == null) {
                throw new OpEngineException(
                        "op '" + entry.op() + "' produced a null schema");
            }
        }
        return running;
    }

    /**
     * Convenience overload that resolves the input/secondary from legacy
     * {@code Map<String,Object>} wrappers and returns the legacy wrapper, so
     * {@code SchemaPropagationService} can call the engine without converting
     * the column model itself.
     */
    public Map<String, Object> applyOpListAsMap(OpList opList,
                                                ParamSurface surface,
                                                Map<String, Object> instanceParams,
                                                Map<String, Object> primaryWrapper,
                                                Map<String, Object> secondaryWrapper) {
        Schema primary = Schema.fromMap(primaryWrapper);
        Schema secondary = secondaryWrapper == null ? null : Schema.fromMap(secondaryWrapper);
        Schema out = applyOpList(opList, surface, instanceParams, primary, secondary);
        return out.toMap();
    }
}
