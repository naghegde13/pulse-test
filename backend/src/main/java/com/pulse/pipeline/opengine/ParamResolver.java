package com.pulse.pipeline.opengine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves an op-entry's {@code config} against a running instance's params
 * (SPEC #1 §A.1). Each config value is either a literal (used as-is) or a
 * param-ref {@code {"param":"&lt;name&gt;"}} substituted with the instance's value
 * for that param.
 *
 * <p>The same resolver is used at design time (before applying a schema-effect
 * rule) and at build time (before emitting a code fragment) — "one op-list, two
 * readers".
 *
 * <p>Loud-fails when a config value references a param name absent from the
 * blueprint's derived param surface (SPEC #1 §A.1, §A.4): the metadata is
 * incomplete and must be fixed, never guessed.
 */
public final class ParamResolver {

    private ParamResolver() {}

    /**
     * Resolve every value in {@code config}, substituting param-refs from
     * {@code instanceParams}. {@code surface} is the blueprint's derived param
     * surface (the set of legal param names) used to loud-fail on an unknown ref.
     *
     * @param opName        the op whose config is being resolved (for error messages)
     * @param config        the op-entry's static config (literals + param-refs)
     * @param instanceParams the running instance's params (name &rarr; value)
     * @param surface       the blueprint's derived param surface (legal names)
     * @return a fully-resolved config map (param-refs replaced by instance values)
     */
    public static Map<String, Object> resolve(String opName,
                                              Map<String, Object> config,
                                              Map<String, Object> instanceParams,
                                              ParamSurface surface) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (config == null) return out;
        Map<String, Object> params = instanceParams == null ? Map.of() : instanceParams;
        for (Map.Entry<String, Object> e : config.entrySet()) {
            Object value = e.getValue();
            if (ParamRef.isParamRef(value)) {
                ParamRef ref = ParamRef.from(value);
                if (surface != null && !surface.contains(ref.name())) {
                    throw new OpEngineException(
                            "op '" + opName + "' config key '" + e.getKey()
                            + "' references param '" + ref.name()
                            + "' which is absent from the blueprint's derived param surface");
                }
                // Resolve from the instance's params; absent => null (the op's rule
                // treats absent/unset as its do-nothing default, SPEC #1 §A.2).
                out.put(e.getKey(), params.get(ref.name()));
            } else {
                out.put(e.getKey(), value);
            }
        }
        return out;
    }
}
