package com.pulse.expression.service;

/**
 * Loud build-failure thrown by
 * {@link CalciteSqlModelValidator#resolveSqlModelSchema} when a {@code sql-model}
 * chain fails to validate AND no {@code declared_output_schema} fallback is
 * present (SPEC #6 §A.6). ADR 0011 — unknown / unresolvable schema is a loud
 * fail, never a guess and never an LLM fallback.
 */
public class BuildFailureException extends RuntimeException {

    private final transient BuildFailure buildFailure;

    public BuildFailureException(BuildFailure buildFailure) {
        super(message(buildFailure));
        this.buildFailure = buildFailure;
    }

    public BuildFailure buildFailure() {
        return buildFailure;
    }

    private static String message(BuildFailure bf) {
        if (bf == null) {
            return "sql-model build failed (no detail)";
        }
        StringBuilder sb = new StringBuilder("sql-model build failed: ")
                .append(bf.cause());
        if (bf.message() != null && !bf.message().isBlank()) {
            sb.append(" — ").append(bf.message());
        }
        if (bf.sqlPosition() != null && bf.sqlPosition().line() != null) {
            sb.append(" (line ").append(bf.sqlPosition().line())
                    .append(", col ").append(bf.sqlPosition().column()).append(')');
        }
        return sb.toString();
    }
}
