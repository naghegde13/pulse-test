package com.pulse.pipeline.opengine;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A typed view of a blueprint's {@code schema_behavior} op-list (SPEC #1 §A.1).
 *
 * <p>The pinned top-level shape (G-1 LOCKED):
 * <pre>
 * { "version": 1,
 *   "ops": [ &lt;op-entry&gt;, ... ],
 *   "blueprint_params": [ "&lt;param-name&gt;", ... ],
 *   "emission": { "orchestration": "airflow", "compute": "&lt;engine&gt;|null" } }
 * </pre>
 * Each op-entry: {@code {"op": "&lt;one-of-32&gt;", "ui_label": "...", "config": {...}}}.
 *
 * <p>{@link #parse(Map)} loud-fails (SPEC #1 §A.1, §B.3) on a malformed shape,
 * a missing/blank {@code op} or {@code ui_label}, or an {@code op} outside the
 * 32-op closed vocabulary.
 */
public final class OpList {

    private final int version;
    private final List<OpEntry> ops;
    private final List<String> blueprintParams;
    private final EmissionDecl emission;

    private OpList(int version, List<OpEntry> ops, List<String> blueprintParams, EmissionDecl emission) {
        this.version = version;
        this.ops = ops;
        this.blueprintParams = blueprintParams;
        this.emission = emission;
    }

    public int version() { return version; }
    public List<OpEntry> ops() { return ops; }
    public List<String> blueprintParams() { return blueprintParams; }
    public EmissionDecl emission() { return emission; }

    public OpList withoutOp(String opName) {
        if (opName == null || opName.isBlank()) return this;
        List<OpEntry> filtered = ops.stream()
                .filter(entry -> !opName.equals(entry.op()))
                .toList();
        if (filtered.size() == ops.size()) return this;
        return new OpList(version, List.copyOf(filtered), blueprintParams, emission);
    }

    /**
     * True if the given {@code schema_behavior} map carries a new-shape op-list
     * (has an {@code ops} array). The legacy throwaway content
     * {@code {effect_type, conflict_policy}} has no {@code ops} key, so this is
     * the discriminator the transitional shim uses to decide engine-vs-legacy.
     */
    public static boolean isOpList(Map<String, Object> schemaBehavior) {
        return schemaBehavior != null && asList(schemaBehavior.get("ops")) != null;
    }

    /** One op-entry: op name (validated against the 32 vocabulary) + ui_label + config. */
    public record OpEntry(String op, String uiLabel, Map<String, Object> config) {
        public OpEntry {
            config = config == null ? Map.of() : config;
        }
    }

    /** The emission declaration (SPEC #1 §A.5). {@code compute} is null for control blueprints. */
    public record EmissionDecl(String orchestration, String compute) {
        public boolean isControl() { return compute == null; }
    }

    /**
     * Parse the pinned op-list shape; loud-fail on a malformed shape or an op
     * outside the 32 closed vocabulary.
     */
    @SuppressWarnings("unchecked")
    public static OpList parse(Map<String, Object> schemaBehavior) {
        if (schemaBehavior == null) {
            throw new OpEngineException("schema_behavior is null — no op-list to parse");
        }
        Object opsRaw = schemaBehavior.get("ops");
        List<?> opsList = asList(opsRaw);
        if (opsList == null) {
            throw new OpEngineException(
                    "schema_behavior has no 'ops' array (got: " + describe(opsRaw) + ")");
        }

        int version = schemaBehavior.get("version") instanceof Number n ? n.intValue() : 1;

        List<OpEntry> entries = new ArrayList<>();
        int idx = 0;
        for (Object o : opsList) {
            Map<String, Object> m = asStringMap(o);
            if (m == null) {
                throw new OpEngineException("op-entry #" + idx + " is not an object (got: "
                        + describe(o) + ")");
            }
            Object opVal = m.get("op");
            String op = opVal == null ? null : opVal.toString();
            if (op == null || op.isBlank()) {
                throw new OpEngineException("op-entry #" + idx + " has a missing/blank 'op'");
            }
            if (!OpVocabulary.isValid(op)) {
                throw new OpEngineException(
                        "op-entry #" + idx + " uses op '" + op
                        + "' which is outside the 32-op closed vocabulary");
            }
            Object labelVal = m.get("ui_label");
            String label = labelVal == null ? null : labelVal.toString();
            if (label == null || label.isBlank()) {
                throw new OpEngineException(
                        "op-entry #" + idx + " (op '" + op + "') has a missing/blank 'ui_label'");
            }
            Map<String, Object> config = asStringMap(m.get("config"));
            entries.add(new OpEntry(op, label, config));
            idx++;
        }

        List<String> bpParams = new ArrayList<>();
        List<?> bp = asList(schemaBehavior.get("blueprint_params"));
        if (bp != null) {
            for (Object p : bp) if (p != null) bpParams.add(p.toString());
        }

        EmissionDecl emission = parseEmission(schemaBehavior.get("emission"));

        return new OpList(version, List.copyOf(entries), List.copyOf(bpParams), emission);
    }

    private static EmissionDecl parseEmission(Object raw) {
        Map<String, Object> m = asStringMap(raw);
        if (m == null) {
            // Emission is optional structurally; default to airflow / no-compute.
            return new EmissionDecl("airflow", null);
        }
        Object orch = m.get("orchestration");
        Object compute = m.get("compute");
        String computeStr = compute == null ? null : compute.toString();
        // JSON null and the literal string "null" both mean "no compute" (control blueprint).
        if ("null".equalsIgnoreCase(computeStr)) computeStr = null;
        return new EmissionDecl(orch == null ? "airflow" : orch.toString(), computeStr);
    }

    private static String describe(Object o) {
        if (o == null) return "null";
        return o.getClass().getSimpleName();
    }

    /**
     * Hibernate's JSON mapping may deserialize JSON arrays as Java Lists in H2 and as Scala
     * immutable collections in the Postgres/Spark classpath. Treat both as the same JSON array
     * shape so the real Flyway catalog cannot fall back to legacy code just because the runtime
     * collection implementation differs.
     */
    private static List<?> asList(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            return list;
        }
        if (raw instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            iterable.forEach(out::add);
            return out;
        }
        if (raw instanceof Object[] array) {
            return Arrays.asList(array);
        }
        String className = raw.getClass().getName();
        if (className.startsWith("scala.collection.")) {
            try {
                Method iteratorMethod = raw.getClass().getMethod("iterator");
                Object iterator = iteratorMethod.invoke(raw);
                Method hasNext = iterator.getClass().getMethod("hasNext");
                Method next = iterator.getClass().getMethod("next");
                List<Object> out = new ArrayList<>();
                while (Boolean.TRUE.equals(hasNext.invoke(iterator))) {
                    out.add(next.invoke(iterator));
                }
                return out;
            } catch (ReflectiveOperationException e) {
                throw new OpEngineException(
                        "schema_behavior JSON array uses unsupported collection type " + className);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    out.put(k.toString(), v);
                }
            });
            return out;
        }
        String className = raw.getClass().getName();
        if (className.startsWith("scala.collection.")) {
            try {
                Method iteratorMethod = raw.getClass().getMethod("iterator");
                Object iterator = iteratorMethod.invoke(raw);
                Method hasNext = iterator.getClass().getMethod("hasNext");
                Method next = iterator.getClass().getMethod("next");
                Map<String, Object> out = new LinkedHashMap<>();
                while (Boolean.TRUE.equals(hasNext.invoke(iterator))) {
                    Object entry = next.invoke(iterator);
                    Object key = tupleElement(entry, 0);
                    Object value = tupleElement(entry, 1);
                    if (key != null) {
                        out.put(key.toString(), value);
                    }
                }
                return out;
            } catch (ReflectiveOperationException e) {
                throw new OpEngineException(
                        "schema_behavior JSON object uses unsupported map type " + className);
            }
        }
        return null;
    }

    private static Object tupleElement(Object tuple, int index) throws ReflectiveOperationException {
        if (tuple == null) {
            return null;
        }
        try {
            Method method = tuple.getClass().getMethod(index == 0 ? "_1" : "_2");
            return method.invoke(tuple);
        } catch (NoSuchMethodException ignored) {
            Method productElement = tuple.getClass().getMethod("productElement", int.class);
            return productElement.invoke(tuple, index);
        }
    }

    /** Convenience for tests / callers building op-lists in code. */
    public static Map<String, Object> opEntryMap(String op, String uiLabel, Map<String, Object> config) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("ui_label", uiLabel);
        m.put("config", config == null ? Map.of() : config);
        return m;
    }
}
