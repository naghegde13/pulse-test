package com.pulse.pipeline.opengine;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A resolved op-entry config (param-refs already substituted by
 * {@link ParamResolver}) with typed accessors. Passed to a {@link SchemaOp}'s
 * {@code apply}.
 *
 * <p>Accessors treat absent/null values as "unset" so an unconfigured op acts as
 * its do-nothing passthrough default (SPEC #1 §A.2).
 */
public final class ResolvedConfig {

    private final Map<String, Object> values;

    public ResolvedConfig(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    public static ResolvedConfig empty() {
        return new ResolvedConfig(Map.of());
    }

    public boolean has(String key) {
        return values.get(key) != null;
    }

    public Object get(String key) {
        return values.get(key);
    }

    public String getString(String key) {
        Object v = values.get(key);
        return v == null ? null : v.toString();
    }

    public boolean getBool(String key, boolean dflt) {
        Object v = values.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return dflt;
        return Boolean.parseBoolean(v.toString());
    }

    /** A list of strings (e.g. column names); empty if absent or not a list. */
    public List<String> getStringList(String key) {
        Object v = values.get(key);
        List<String> out = new ArrayList<>();
        List<?> list = asList(v);
        if (list != null) {
            for (Object o : list) if (o != null) out.add(o.toString());
        }
        return out;
    }

    /** A map of string -> string (e.g. rename_map); empty if absent or not a map. */
    public Map<String, String> getStringMap(String key) {
        Object v = values.get(key);
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Object> m = asStringMap(v);
        if (m != null) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getValue() != null) {
                    out.put(e.getKey(), e.getValue().toString());
                }
            }
        }
        return out;
    }

    /** A list of maps (e.g. aggregations, mask_specs); empty if absent or not a list. */
    public List<Map<String, Object>> getMapList(String key) {
        Object v = values.get(key);
        List<Map<String, Object>> out = new ArrayList<>();
        List<?> list = asList(v);
        if (list != null) {
            for (Object o : list) {
                Map<String, Object> m = asStringMap(o);
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** A nested map value (e.g. a struct spec); empty map if absent. */
    public Map<String, Object> getMap(String key) {
        Object v = values.get(key);
        Map<String, Object> m = asStringMap(v);
        if (m != null) return m;
        return Map.of();
    }

    public Map<String, Object> raw() {
        return values;
    }

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
                        "resolved config uses unsupported list type " + className);
            }
        }
        return null;
    }

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
                        "resolved config uses unsupported map type " + className);
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
}
