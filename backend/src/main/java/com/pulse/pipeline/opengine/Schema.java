package com.pulse.pipeline.opengine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ordered set of {@link ColumnModel} columns — the unit the op-engine's
 * schema-effect rules transform (columns in &rarr; columns out, SPEC #1 §B.1).
 *
 * <p>Order is significant (it is the emit/projection order). A Schema round-trips
 * to/from the legacy {@code {"columns":[ {name,type,...}, ... ]}} wrapper used by
 * {@code SchemaPropagationService} / {@code InstancePortSchema}, so the engine
 * plugs in without changing the persisted JSON shape.
 */
public final class Schema {

    private final List<ColumnModel> columns;

    public Schema(List<ColumnModel> columns) {
        this.columns = columns == null ? new ArrayList<>() : new ArrayList<>(columns);
    }

    public static Schema empty() {
        return new Schema(List.of());
    }

    public static Schema of(ColumnModel... cols) {
        return new Schema(List.of(cols));
    }

    public List<ColumnModel> columns() {
        return columns;
    }

    public int size() {
        return columns.size();
    }

    public boolean isEmpty() {
        return columns.isEmpty();
    }

    public boolean hasColumn(String name) {
        return find(name) != null;
    }

    public ColumnModel find(String name) {
        if (name == null) return null;
        for (ColumnModel c : columns) {
            if (name.equals(c.name())) return c;
        }
        return null;
    }

    public List<String> names() {
        List<String> out = new ArrayList<>(columns.size());
        for (ColumnModel c : columns) out.add(c.name());
        return out;
    }

    // ---- legacy Map round-trip -------------------------------------------

    /**
     * Build a Schema from a persisted schema wrapper.
     *
     * <p>Propagated instance schemas use {@code {"columns":[...]}}. Dataset
     * {@code schemaSnapshot} payloads use {@code {"fields":[...]}}. The op-engine
     * can receive either shape for source-rooted blueprints, so normalize both at
     * this boundary and keep {@link #toMap()} canonical as {@code columns}.
     */
    @SuppressWarnings("unchecked")
    public static Schema fromMap(Map<String, Object> wrapper) {
        if (wrapper == null) return empty();
        Object cols = wrapper.get("columns");
        if (!(cols instanceof List<?>)) {
            cols = wrapper.get("fields");
        }
        List<ColumnModel> out = new ArrayList<>();
        if (cols instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    ColumnModel cm = ColumnModel.fromMap((Map<String, Object>) m);
                    if (cm != null) out.add(cm);
                }
            }
        }
        return new Schema(out);
    }

    /** Serialize to the legacy {@code {"columns":[...]}} wrapper. */
    public Map<String, Object> toMap() {
        List<Map<String, Object>> cols = new ArrayList<>(columns.size());
        for (ColumnModel c : columns) cols.add(c.toMap());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", cols);
        return out;
    }

    @Override
    public String toString() {
        return "Schema" + names();
    }
}
