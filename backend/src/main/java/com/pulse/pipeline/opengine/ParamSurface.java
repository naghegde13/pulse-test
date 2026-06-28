package com.pulse.pipeline.opengine;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A blueprint's derived param surface (SPEC #1 §A.4): the <b>union</b> of
 * (1) every param referenced by a {@code {"param":"&lt;name&gt;"}} in any op-entry's
 * {@code config}, plus (2) every name in {@code schema_behavior.blueprint_params}.
 * Each name in the union MUST have a matching descriptor in {@code params_schema}
 * (carrying type / tier / presentation metadata) or the blueprint loud-fails
 * (incomplete metadata, SPEC #1 §A.4).
 *
 * <p>Tiering (ADR 0023, SPEC #1 §A.3): each descriptor carries {@code tier}
 * (user|derived) and, iff derived, {@code derivedFrom}. An absent {@code tier}
 * defaults to {@code user} (G-A3, back-compat for pre-V153 rows).
 */
public final class ParamSurface {

    public static final String TIER_USER = "user";
    public static final String TIER_DERIVED = "derived";

    /** A single param descriptor (the merged op-ref/blueprint-param + its params_schema entry). */
    public record ParamDescriptor(String name, String tier, String derivedFrom,
                                  Map<String, Object> raw) {
        public boolean isDerived() { return TIER_DERIVED.equals(tier); }
    }

    private final Map<String, ParamDescriptor> byName;

    private ParamSurface(Map<String, ParamDescriptor> byName) {
        this.byName = byName;
    }

    public Set<String> names() {
        return byName.keySet();
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    public ParamDescriptor get(String name) {
        return byName.get(name);
    }

    public int size() {
        return byName.size();
    }

    /**
     * Derive the param surface from a parsed op-list and the blueprint's
     * {@code params_schema} array. Loud-fails if a name in the derived union has
     * no matching descriptor in {@code params_schema}.
     *
     * @param opList       the parsed op-list (op-ref params + blueprint_params)
     * @param paramsSchema the blueprint's {@code params_schema} array (descriptors)
     */
    public static ParamSurface derive(OpList opList, List<Map<String, Object>> paramsSchema) {
        // 1. Collect the union of referenced names (op-config param-refs + blueprint_params).
        Set<String> union = new LinkedHashSet<>();
        for (OpList.OpEntry entry : opList.ops()) {
            for (Object v : entry.config().values()) {
                if (ParamRef.isParamRef(v)) {
                    union.add(ParamRef.from(v).name());
                }
            }
        }
        union.addAll(opList.blueprintParams());

        // 2. Index params_schema by name.
        Map<String, Map<String, Object>> descriptors = new LinkedHashMap<>();
        if (paramsSchema != null) {
            for (Map<String, Object> d : paramsSchema) {
                if (d == null) continue;
                Object name = d.get("name");
                if (name != null) descriptors.put(name.toString(), d);
            }
        }

        // 3. Match each union name to a descriptor or loud-fail.
        Map<String, ParamDescriptor> out = new LinkedHashMap<>();
        for (String name : union) {
            Map<String, Object> d = descriptors.get(name);
            if (d == null) {
                throw new OpEngineException(
                        "param '" + name + "' is referenced by the op-list but has no descriptor "
                        + "in params_schema (the blueprint's metadata is incomplete)");
            }
            out.put(name, toDescriptor(name, d));
        }
        return new ParamSurface(out);
    }

    private static ParamDescriptor toDescriptor(String name, Map<String, Object> raw) {
        Object tierVal = raw.get("tier");
        // Absent tier => "user" (G-A3, back-compat default).
        String tier = tierVal == null || tierVal.toString().isBlank()
                ? TIER_USER : tierVal.toString();
        Object derivedFromVal = raw.get("derivedFrom");
        String derivedFrom = derivedFromVal == null ? null : derivedFromVal.toString();
        return new ParamDescriptor(name, tier, derivedFrom, raw);
    }
}
