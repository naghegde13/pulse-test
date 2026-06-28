package com.pulse.expression.service;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete, deterministic, bidirectional PULSE &harr; Calcite type map
 * (SPEC #6 §A.3). Recursive for nested {@code struct} / {@code list} types.
 *
 * <p>PULSE column-descriptor encoding (THE contract — SPEC-schema-op-engine.md
 * :251,257-259; PULSE uses {@code List<Map<String,Object>>}):
 * <ul>
 *   <li><b>simple</b> — {@code {"name":..,"type":"<one of 8>","nullable":<bool>}}
 *       (no {@code fields}/{@code element}).</li>
 *   <li><b>struct</b> — {@code {"name":..,"type":"struct","nullable":<bool>,
 *       "fields":[<descriptor>,..]}}.</li>
 *   <li><b>list</b> — {@code {"name":..,"type":"list","nullable":<bool>,
 *       "element":<type-encoding>}} where the element encoding is a bare simple
 *       String, or a nested {@code {"type":"struct","fields":[..]}} /
 *       {@code {"type":"list","element":..}} map (element encodings carry NO
 *       name / nullable at top — only {@code type} + its {@code fields}/{@code
 *       element}).</li>
 * </ul>
 * {@code fields} present <b>iff</b> struct; {@code element} present <b>iff</b>
 * list. {@link LinkedHashMap} preserves key/column order (determinism, ADR 0009).
 *
 * <p>The 8 simple types: {@code string, integer, long, double, decimal,
 * boolean, date, timestamp}. {@code nullable} round-trips both directions.
 */
final class PulseCalciteTypeMap {

    private PulseCalciteTypeMap() {
    }

    /** Thrown when a type cannot be mapped in either direction (§A.3). */
    static final class UnmappableTypeException extends RuntimeException {
        UnmappableTypeException(String message) {
            super(message);
        }
    }

    // ---- key + value constants (the contract strings) ----
    static final String K_NAME = "name";
    static final String K_TYPE = "type";
    static final String K_NULLABLE = "nullable";
    static final String K_FIELDS = "fields";
    static final String K_ELEMENT = "element";

    static final String T_STRING = "string";
    static final String T_INTEGER = "integer";
    static final String T_LONG = "long";
    static final String T_DOUBLE = "double";
    static final String T_DECIMAL = "decimal";
    static final String T_BOOLEAN = "boolean";
    static final String T_DATE = "date";
    static final String T_TIMESTAMP = "timestamp";
    static final String T_STRUCT = "struct";
    static final String T_LIST = "list";

    // ============================================================
    // FORWARD: PULSE column-descriptor list -> Calcite ROW RelDataType
    // ============================================================

    /**
     * Builds a Calcite ROW type (a relation row-type) from an ordered PULSE
     * column-descriptor list. Field order is preserved.
     */
    static RelDataType columnsToRowType(List<Map<String, Object>> columns,
                                        RelDataTypeFactory typeFactory) {
        RelDataTypeFactory.Builder b = typeFactory.builder();
        if (columns != null) {
            for (Map<String, Object> col : columns) {
                String name = String.valueOf(col.get(K_NAME));
                RelDataType fieldType = columnToRelType(col, typeFactory);
                b.add(name, fieldType);
            }
        }
        return b.build();
    }

    /**
     * Forward-maps a single PULSE column descriptor (or struct field descriptor)
     * to a Calcite {@link RelDataType}, honoring {@code nullable} and recursing
     * into nested {@code struct}/{@code list}.
     */
    static RelDataType columnToRelType(Map<String, Object> descriptor,
                                       RelDataTypeFactory typeFactory) {
        Object typeObj = descriptor.get(K_TYPE);
        if (typeObj == null) {
            throw new UnmappableTypeException("column descriptor has no 'type': " + descriptor);
        }
        boolean nullable = asNullable(descriptor.get(K_NULLABLE));
        RelDataType base = typeEncodingToRelType(typeObj, descriptor, typeFactory);
        return typeFactory.createTypeWithNullability(base, nullable);
    }

    /**
     * Forward-maps a "type encoding" — either a simple-type String, or a nested
     * map carrying {@code fields}/{@code element}. {@code carrier} supplies the
     * {@code fields}/{@code element} keys when {@code typeObj} is a bare String
     * (top-level descriptor case); for element encodings the carrier IS the
     * encoding map itself.
     */
    private static RelDataType typeEncodingToRelType(Object typeObj,
                                                     Map<String, Object> carrier,
                                                     RelDataTypeFactory typeFactory) {
        String type = String.valueOf(typeObj);
        switch (type) {
            case T_STRING:
                return typeFactory.createSqlType(SqlTypeName.VARCHAR);
            case T_INTEGER:
                return typeFactory.createSqlType(SqlTypeName.INTEGER);
            case T_LONG:
                return typeFactory.createSqlType(SqlTypeName.BIGINT);
            case T_DOUBLE:
                return typeFactory.createSqlType(SqlTypeName.DOUBLE);
            case T_DECIMAL:
                return typeFactory.createSqlType(SqlTypeName.DECIMAL);
            case T_BOOLEAN:
                return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
            case T_DATE:
                return typeFactory.createSqlType(SqlTypeName.DATE);
            case T_TIMESTAMP:
                return typeFactory.createSqlType(SqlTypeName.TIMESTAMP);
            case T_STRUCT:
                return structToRowType(carrier, typeFactory);
            case T_LIST:
                return listToArrayType(carrier, typeFactory);
            default:
                throw new UnmappableTypeException("unmappable PULSE type: '" + type + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private static RelDataType structToRowType(Map<String, Object> carrier,
                                               RelDataTypeFactory typeFactory) {
        Object fieldsObj = carrier.get(K_FIELDS);
        if (!(fieldsObj instanceof List<?> fields)) {
            throw new UnmappableTypeException("struct without 'fields': " + carrier);
        }
        RelDataTypeFactory.Builder b = typeFactory.builder();
        for (Object f : fields) {
            if (!(f instanceof Map<?, ?>)) {
                throw new UnmappableTypeException("struct field is not a descriptor: " + f);
            }
            Map<String, Object> field = (Map<String, Object>) f;
            String name = String.valueOf(field.get(K_NAME));
            b.add(name, columnToRelType(field, typeFactory));
        }
        return b.build();
    }

    private static RelDataType listToArrayType(Map<String, Object> carrier,
                                               RelDataTypeFactory typeFactory) {
        Object element = carrier.get(K_ELEMENT);
        if (element == null) {
            throw new UnmappableTypeException("list without 'element': " + carrier);
        }
        RelDataType elementType = elementEncodingToRelType(element, typeFactory);
        // ARRAY of elementType; list elements are nullable by convention.
        return typeFactory.createArrayType(elementType, -1L);
    }

    /**
     * Forward-maps an element encoding (no name/nullable at top): a bare simple
     * String, or a nested {@code {type:struct,fields:..}} / {@code
     * {type:list,element:..}} map.
     */
    @SuppressWarnings("unchecked")
    private static RelDataType elementEncodingToRelType(Object element,
                                                        RelDataTypeFactory typeFactory) {
        if (element instanceof String s) {
            RelDataType base = typeEncodingToRelType(s, Map.of(), typeFactory);
            return typeFactory.createTypeWithNullability(base, true);
        }
        if (element instanceof Map<?, ?>) {
            Map<String, Object> enc = (Map<String, Object>) element;
            Object t = enc.get(K_TYPE);
            if (t == null) {
                throw new UnmappableTypeException("element encoding has no 'type': " + enc);
            }
            RelDataType base = typeEncodingToRelType(t, enc, typeFactory);
            return typeFactory.createTypeWithNullability(base, true);
        }
        throw new UnmappableTypeException("unmappable list element encoding: " + element);
    }

    // ============================================================
    // REVERSE: Calcite RelDataType -> PULSE column-descriptor list
    // ============================================================

    /**
     * Reverse-maps a Calcite ROW type's fields to an ordered PULSE
     * column-descriptor list (top-level, with name + nullable). Field order is
     * preserved (LinkedHashMap; ADR 0009).
     */
    static List<Map<String, Object>> rowTypeToColumns(RelDataType rowType) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (RelDataTypeField f : rowType.getFieldList()) {
            out.add(relTypeToColumn(f.getName(), f.getType()));
        }
        return out;
    }

    /**
     * Reverse-maps a single Calcite type to a top-level PULSE column descriptor
     * ({@code name} + {@code nullable} + {@code type}[+{@code fields}/{@code
     * element}]).
     */
    static Map<String, Object> relTypeToColumn(String name, RelDataType type) {
        Map<String, Object> col = new LinkedHashMap<>();
        col.put(K_NAME, name);
        Object typeEncoding = relTypeToTypeEncoding(type);
        if (typeEncoding instanceof String s) {
            col.put(K_TYPE, s);
            col.put(K_NULLABLE, type.isNullable());
        } else {
            // nested: typeEncoding is a map carrying type + fields/element.
            @SuppressWarnings("unchecked")
            Map<String, Object> enc = (Map<String, Object>) typeEncoding;
            col.put(K_TYPE, enc.get(K_TYPE));
            col.put(K_NULLABLE, type.isNullable());
            if (enc.containsKey(K_FIELDS)) {
                col.put(K_FIELDS, enc.get(K_FIELDS));
            }
            if (enc.containsKey(K_ELEMENT)) {
                col.put(K_ELEMENT, enc.get(K_ELEMENT));
            }
        }
        return col;
    }

    /**
     * Reverse-maps a Calcite type to a PULSE "type encoding": a bare simple
     * String, or a nested {@code {type:struct,fields:[..]}} / {@code
     * {type:list,element:..}} map (no name/nullable at top — that is the
     * element-encoding shape and the inner shape of a top-level descriptor).
     */
    static Object relTypeToTypeEncoding(RelDataType type) {
        SqlTypeName sqlType = type.getSqlTypeName();
        if (sqlType == null) {
            throw new UnmappableTypeException("Calcite type has no SqlTypeName: " + type);
        }
        switch (sqlType) {
            case VARCHAR:
            case CHAR:
                return T_STRING;
            case INTEGER:
            case SMALLINT:
            case TINYINT:
                return T_INTEGER;
            case BIGINT:
                return T_LONG;
            case DOUBLE:
            case FLOAT:
            case REAL:
                return T_DOUBLE;
            case DECIMAL:
                return T_DECIMAL;
            case BOOLEAN:
                return T_BOOLEAN;
            case DATE:
                return T_DATE;
            case TIMESTAMP:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                return T_TIMESTAMP;
            case ROW:
                return rowTypeToStructEncoding(type);
            case ARRAY:
            case MULTISET:
                return arrayTypeToListEncoding(type);
            default:
                throw new UnmappableTypeException(
                        "unmappable Calcite type: " + sqlType + " (" + type + ")");
        }
    }

    /** Calcite ROW -> {@code {type:"struct", fields:[<descriptor>...]}}. */
    private static Map<String, Object> rowTypeToStructEncoding(RelDataType rowType) {
        List<Map<String, Object>> fields = new java.util.ArrayList<>();
        for (RelDataTypeField f : rowType.getFieldList()) {
            fields.add(relTypeToColumn(f.getName(), f.getType()));
        }
        Map<String, Object> enc = new LinkedHashMap<>();
        enc.put(K_TYPE, T_STRUCT);
        enc.put(K_FIELDS, fields);
        return enc;
    }

    /** Calcite ARRAY -> {@code {type:"list", element:<element-encoding>}}. */
    private static Map<String, Object> arrayTypeToListEncoding(RelDataType arrayType) {
        RelDataType component = arrayType.getComponentType();
        if (component == null) {
            throw new UnmappableTypeException("ARRAY without component type: " + arrayType);
        }
        Object elementEncoding = relTypeToTypeEncoding(component);
        Map<String, Object> enc = new LinkedHashMap<>();
        enc.put(K_TYPE, T_LIST);
        // element is a bare String (simple) or a nested map — exactly the
        // SPEC-schema-op-engine.md:256-259 element encoding (no name/nullable).
        enc.put(K_ELEMENT, elementEncoding);
        return enc;
    }

    // ---- helpers ----

    private static boolean asNullable(Object v) {
        if (v == null) {
            return true; // PULSE default: absent nullable => nullable.
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
