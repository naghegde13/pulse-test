package com.pulse.expression.service;

/**
 * Structured failure returned by {@link CalciteSqlModelValidator} when a
 * {@code sql-model} step cannot be validated / its schema cannot be derived
 * (SPEC #6 §A.5). There is <b>no silent pass and no AI fallback</b> (ADR 0011) —
 * a {@code BuildFailure} is the only non-schema outcome.
 *
 * @param cause       one of the {@link Cause} canonical names (a String per the
 *                    SPEC #6 contract).
 * @param sqlPosition Calcite source position (line/column) when available;
 *                    {@code null} when Calcite gives no position.
 * @param message     human-readable summary (first line of the Calcite message).
 */
public record BuildFailure(String cause, SqlPosition sqlPosition, String message) {

    /**
     * Calcite-reported source position. Both fields are nullable — Calcite does
     * not always attach a position (e.g. duplicate-output-name and
     * unaliased-expression checks PULSE performs on the AST itself).
     */
    public record SqlPosition(Integer line, Integer column) {
    }

    /**
     * The closed set of failure causes (SPEC #6 §A.5 + the §A.4 author-error
     * cases). The record's {@code cause} String is one of these names.
     *
     * <p><b>Calcite limitation (documented honestly):</b> Calcite's
     * {@code SqlValidator} collapses "column not found" and "object/table not
     * found" into the same {@code CalciteContextException} family with messages
     * that are not reliably machine-distinguishable across versions. We therefore
     * map an unresolved identifier to {@link #UNKNOWN_COLUMN} when the message
     * mentions a column-shaped token and to {@link #UNKNOWN_RELATION} only when
     * the message clearly names a not-found table/object; ambiguous cases default
     * to {@link #UNKNOWN_COLUMN}. See {@code classifyValidationError} in the
     * validator. This is a real limitation, not a faked distinction.
     */
    public enum Cause {
        PARSE_ERROR,
        UNKNOWN_COLUMN,
        UNKNOWN_RELATION,
        UNRESOLVED_TYPE,
        UNREGISTERED_FUNCTION,
        UNALIASED_EXPRESSION,
        DUPLICATE_OUTPUT_NAME,
        UNMAPPABLE_TYPE,
        STEP_NAME_COLLISION
    }

    public static BuildFailure of(Cause cause, SqlPosition pos, String message) {
        return new BuildFailure(cause.name(), pos, message);
    }

    public static BuildFailure of(Cause cause, String message) {
        return new BuildFailure(cause.name(), null, message);
    }
}
