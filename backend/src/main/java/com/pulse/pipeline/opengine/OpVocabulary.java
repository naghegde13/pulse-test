package com.pulse.pipeline.opengine;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The closed 32-op vocabulary (ADR 0012 §2; SPEC #1 §B.1). An {@code op} value
 * outside this set is a design-time loud-fail (SPEC #1 §A.1, §B.3).
 *
 * <p>The names are the canonical hyphenated op names used inside an op-list's
 * {@code ops[].op}. (NOTE: op names are distinct from LLM tool names — tools are
 * snake_case; ops are hyphenated per ADR 0012 / OP-VOCAB doc.)
 */
public final class OpVocabulary {

    private OpVocabulary() {}

    // Column ops (1-9)
    public static final String ADD_COLUMN = "add-column";
    public static final String TRANSFORM_VALUES = "transform-values";
    public static final String DROP_COLUMNS = "drop-columns";
    public static final String KEEP_COLUMNS = "keep-columns";
    public static final String RENAME_COLUMNS = "rename-columns";
    public static final String CHANGE_TYPES = "change-types";
    public static final String MASK_COLUMNS = "mask-columns";
    public static final String FLATTEN_JSON = "flatten-json";
    public static final String BUILD_STRUCT = "build-struct";

    // Combine / reshape ops (10-15)
    public static final String JOIN = "join";
    public static final String GROUP_AND_AGGREGATE = "group-and-aggregate";
    public static final String UNION_ALL = "union-all";
    public static final String DISTINCT_UNION = "distinct-union";
    public static final String SORT = "sort";
    public static final String SAMPLE_LIMIT = "sample-limit";

    // Row ops (16-19)
    public static final String FILTER_ROWS = "filter-rows";
    public static final String DEDUPLICATE = "deduplicate";
    public static final String ROUTE_ROWS = "route-rows";
    public static final String MERGE_ROWS = "merge-rows";

    // History ops (20-21)
    public static final String TRACK_HISTORY_SCD2 = "track-history-scd2";
    public static final String TAKE_PERIODIC_SNAPSHOT = "take-periodic-snapshot";

    // Quality ops (22-23)
    public static final String CHECK_DATA = "check-data";
    public static final String EMIT_REPORT = "emit-report";

    // Movement ops (24-26)
    public static final String READ_SOURCE = "read-source";
    public static final String ADD_AUDIT_COLUMNS = "add-audit-columns";
    public static final String WRITE_SINK = "write-sink";

    // Power-user (27)
    public static final String SQL_MODEL = "sql-model";

    // Control ops (28-32, portless, no schema effect)
    public static final String SENSE = "sense";
    public static final String SCHEDULE_AND_TRIGGERS = "schedule-and-triggers";
    public static final String ROLLBACK = "rollback";
    public static final String ADVANCE_TIME = "advance-time";
    public static final String INVOKE_REMOTE = "invoke-remote";

    /** The closed set, in canonical (numbered) order. Size MUST be 32. */
    public static final Set<String> ALL;

    static {
        Set<String> s = new LinkedHashSet<>();
        s.add(ADD_COLUMN); s.add(TRANSFORM_VALUES); s.add(DROP_COLUMNS); s.add(KEEP_COLUMNS);
        s.add(RENAME_COLUMNS); s.add(CHANGE_TYPES); s.add(MASK_COLUMNS); s.add(FLATTEN_JSON);
        s.add(BUILD_STRUCT);
        s.add(JOIN); s.add(GROUP_AND_AGGREGATE); s.add(UNION_ALL); s.add(DISTINCT_UNION);
        s.add(SORT); s.add(SAMPLE_LIMIT);
        s.add(FILTER_ROWS); s.add(DEDUPLICATE); s.add(ROUTE_ROWS); s.add(MERGE_ROWS);
        s.add(TRACK_HISTORY_SCD2); s.add(TAKE_PERIODIC_SNAPSHOT);
        s.add(CHECK_DATA); s.add(EMIT_REPORT);
        s.add(READ_SOURCE); s.add(ADD_AUDIT_COLUMNS); s.add(WRITE_SINK);
        s.add(SQL_MODEL);
        s.add(SENSE); s.add(SCHEDULE_AND_TRIGGERS); s.add(ROLLBACK); s.add(ADVANCE_TIME);
        s.add(INVOKE_REMOTE);
        ALL = Set.copyOf(s);
    }

    /** The 5 portless control ops — no schema effect (SPEC #1 §B.1 rules 28-32). */
    public static final Set<String> CONTROL_OPS = Set.of(
            SENSE, SCHEDULE_AND_TRIGGERS, ROLLBACK, ADVANCE_TIME, INVOKE_REMOTE);

    public static boolean isValid(String op) {
        return op != null && ALL.contains(op);
    }

    public static boolean isControl(String op) {
        return CONTROL_OPS.contains(op);
    }
}
