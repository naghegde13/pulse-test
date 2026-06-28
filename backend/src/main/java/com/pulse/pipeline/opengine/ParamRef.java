package com.pulse.pipeline.opengine;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A param-ref token in an op-entry's {@code config} (SPEC #1 §A.1):
 * the exact object {@code {"param":"&lt;name&gt;"}}. The only legal non-literal
 * config-value form.
 *
 * <p>{@link #isParamRef(Object)} detects the token; {@link #from(Object)} parses
 * it. Any other object (string/number/bool/array/non-{@code param} map) is a
 * literal and is used as-is.
 */
public record ParamRef(String name) {

    public static final String KEY = "param";

    /** True iff {@code value} is exactly the {@code {"param":"<name>"}} token. */
    public static boolean isParamRef(Object value) {
        Map<String, Object> m = asStringMap(value);
        if (m == null) return false;
        // The token has exactly one key, "param", whose value is a non-null string.
        if (m.size() != 1) return false;
        Object pv = m.get(KEY);
        return pv != null && (pv instanceof String);
    }

    /** Parse a param-ref token; null if {@code value} is not a param-ref. */
    public static ParamRef from(Object value) {
        if (!isParamRef(value)) return null;
        Map<String, Object> m = asStringMap(value);
        return new ParamRef(m.get(KEY).toString());
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
                        "param-ref uses unsupported map type " + className);
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
