package com.pulse.pipeline.opengine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The recursive column / field descriptor (SPEC #1 §B.0, G-4 LOCKED).
 *
 * <p>A descriptor is {@code {name, type, nullable, ...tags}}. The {@code type}
 * value is encoded recursively as one of three forms, distinguished by which
 * key is present (no separate {@code kind} discriminator):
 * <ul>
 *   <li><b>simple</b> — {@code type} is a scalar string
 *       ({@code string|integer|long|double|decimal|boolean|date|timestamp});
 *       neither {@code fields} nor {@code element}.</li>
 *   <li><b>struct</b> — {@code type=="struct"} <em>plus</em> {@code fields:[...]}
 *       (recursive named sub-fields).</li>
 *   <li><b>list</b> — {@code type=="list"} <em>plus</em> {@code element:&lt;type-encoding&gt;}
 *       (the element is itself one of the three forms).</li>
 * </ul>
 *
 * <p><b>Encoding rule:</b> {@code fields} is present <em>iff</em> {@code type=="struct"};
 * {@code element} is present <em>iff</em> {@code type=="list"}; a simple type carries
 * neither. This single recursive shape backs {@code flatten-json}, {@code build-struct},
 * and schema discovery.
 *
 * <p>This model supersedes the legacy flat {@code {name,type}} helper in
 * {@code SchemaPropagationService}. It round-trips to/from the legacy
 * {@code Map<String,Object>} column representation so the design-time engine can
 * adopt it without breaking the persisted JSON shape ({@link #toMap()} /
 * {@link #fromMap(Map)}). Arbitrary extra tags (lineage, tags, transform, …) are
 * carried verbatim in {@link #extras()} so masking / audit / derived metadata is
 * not lost across the round-trip.
 */
public final class ColumnModel {

    public static final String TYPE_STRUCT = "struct";
    public static final String TYPE_LIST = "list";

    private final String name;
    private final String type;
    private final boolean nullable;
    /** Present iff type == "struct". Ordered, recursive sub-fields. */
    private final List<ColumnModel> fields;
    /** Present iff type == "list". The element's type encoding (recursive). */
    private final ColumnModel element;
    /** Verbatim non-structural keys (lineage, tags, transform, description, …). */
    private final Map<String, Object> extras;

    private ColumnModel(String name, String type, boolean nullable,
                        List<ColumnModel> fields, ColumnModel element,
                        Map<String, Object> extras) {
        this.name = name;
        this.type = type;
        this.nullable = nullable;
        this.fields = fields;
        this.element = element;
        this.extras = extras == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extras);
    }

    // ---- factories -------------------------------------------------------

    public static ColumnModel simple(String name, String type) {
        return new ColumnModel(name, type, true, null, null, null);
    }

    public static ColumnModel simple(String name, String type, boolean nullable) {
        return new ColumnModel(name, type, nullable, null, null, null);
    }

    public static ColumnModel struct(String name, List<ColumnModel> fields, boolean nullable) {
        return new ColumnModel(name, TYPE_STRUCT, nullable,
                fields == null ? List.of() : List.copyOf(fields), null, null);
    }

    public static ColumnModel list(String name, ColumnModel element, boolean nullable) {
        return new ColumnModel(name, TYPE_LIST, nullable, null,
                Objects.requireNonNull(element, "list element"), null);
    }

    // ---- accessors -------------------------------------------------------

    public String name() { return name; }
    public String type() { return type; }
    public boolean nullable() { return nullable; }
    public boolean isStruct() { return TYPE_STRUCT.equals(type); }
    public boolean isList() { return TYPE_LIST.equals(type); }
    public boolean isSimple() { return !isStruct() && !isList(); }
    public List<ColumnModel> fields() { return fields == null ? List.of() : fields; }
    public ColumnModel element() { return element; }
    public Map<String, Object> extras() { return extras; }

    // ---- derivations (immutable copies) ----------------------------------

    /** A copy with a new name, preserving type/nullable/fields/element/extras. */
    public ColumnModel withName(String newName) {
        return new ColumnModel(newName, type, nullable, fields, element, extras);
    }

    /** A copy with a new simple type (only meaningful for simple columns). */
    public ColumnModel withType(String newType) {
        return new ColumnModel(name, newType, nullable, fields, element, extras);
    }

    /** A copy with an extra key set (returns a fresh instance). */
    public ColumnModel withExtra(String key, Object value) {
        Map<String, Object> e = new LinkedHashMap<>(extras);
        e.put(key, value);
        return new ColumnModel(name, type, nullable, fields, element, e);
    }

    // ---- legacy Map round-trip -------------------------------------------

    /**
     * Build a ColumnModel from the legacy {@code {name,type,...}} map shape used
     * by {@code SchemaPropagationService} and persisted in {@code InstancePortSchema}.
     */
    @SuppressWarnings("unchecked")
    public static ColumnModel fromMap(Map<String, Object> map) {
        if (map == null) return null;
        String name = map.get("name") == null ? null : map.get("name").toString();
        String type = map.get("type") == null ? "string" : map.get("type").toString();
        boolean nullable = !Boolean.FALSE.equals(map.get("nullable"));
        List<ColumnModel> childFields = null;
        ColumnModel elem = null;
        if (TYPE_STRUCT.equals(type) && map.get("fields") instanceof List<?> fl) {
            childFields = new ArrayList<>();
            for (Object o : fl) {
                if (o instanceof Map<?, ?> m) childFields.add(fromMap((Map<String, Object>) m));
            }
        } else if (TYPE_LIST.equals(type) && map.get("element") != null) {
            Object el = map.get("element");
            if (el instanceof Map<?, ?> m) {
                elem = fromMap((Map<String, Object>) m);
            } else {
                // element may be a bare scalar type string, e.g. "string"
                elem = simple(null, el.toString());
            }
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String k = e.getKey();
            if ("name".equals(k) || "type".equals(k) || "nullable".equals(k)
                    || "fields".equals(k) || "element".equals(k)) continue;
            extras.put(k, e.getValue());
        }
        return new ColumnModel(name, type, nullable, childFields, elem, extras);
    }

    /**
     * Serialize back to the legacy {@code {name,type,...}} ordered map. {@code fields}
     * is emitted iff struct; {@code element} iff list; extras are appended verbatim.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (name != null) out.put("name", name);
        out.put("type", type);
        out.put("nullable", nullable);
        if (isStruct()) {
            List<Map<String, Object>> fl = new ArrayList<>();
            for (ColumnModel f : fields()) fl.add(f.toMap());
            out.put("fields", fl);
        } else if (isList() && element != null) {
            // A simple-scalar element with no name serializes as a bare type string
            // (matches the spec's {"element":"string"} compact form).
            if (element.isSimple() && element.name() == null) {
                out.put("element", element.type());
            } else {
                out.put("element", element.toMap());
            }
        }
        out.putAll(extras);
        return out;
    }

    @Override
    public String toString() {
        return "ColumnModel{" + name + ":" + type + (nullable ? "?" : "") + "}";
    }
}
