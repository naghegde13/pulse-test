package com.pulse.expression.service;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

/**
 * Single source of the PULSE Babel SQL parser configuration, shared between
 * {@link ExpressionValidationService} (parse-only, Phase 1) and
 * {@link CalciteSqlModelValidator} (schema-deriving, Phase 2 — SPEC #6 §A.2).
 *
 * <p>The configuration is the EXACT one proven in
 * {@code ExpressionValidationService.java:99-113}: the Babel parser factory +
 * {@code BABEL} conformance (lenient, Spark-SQL-leaning — accepts backtick
 * identifiers and dbt/Spark idioms), back-tick quoting, case-insensitive name
 * resolution, and unchanged casing so user-typed identifiers round-trip
 * verbatim into diagnostics/derived column names.
 *
 * <p>SPEC #6 §A.2 (RESOLVED 2026-06-16) mandates this config be COPIED verbatim
 * from the existing parse-only validator — no new dialect / {@code Lex} choice.
 * Extracting it here (rather than duplicating) keeps the two services in lockstep
 * (IMPL-calcite-sql.md R7) without changing either service's parse behavior.
 */
final class SqlParserConfigFactory {

    private SqlParserConfigFactory() {
    }

    /**
     * The shared Babel parser config — verbatim from
     * {@code ExpressionValidationService.java:99-113}.
     */
    static SqlParser.Config babelConfig() {
        return SqlParser.config()
                // Babel parser + BABEL conformance accepts a wider dialect than
                // ANSI — backtick identifiers (via Quoting.BACK_TICK) and dialect
                // quirks (dbt / Spark idioms).
                .withParserFactory(SqlBabelParserImpl.FACTORY)
                .withCaseSensitive(false)
                .withConformance(SqlConformanceEnum.BABEL)
                .withQuoting(Quoting.BACK_TICK)
                // Preserve the user's original identifier casing (Calcite default
                // uppercases) so derived column names quote what they typed.
                .withUnquotedCasing(Casing.UNCHANGED)
                .withQuotedCasing(Casing.UNCHANGED);
    }

    /** Convenience: a parser over {@code text} using {@link #babelConfig()}. */
    static SqlParser parserFor(String text) {
        return SqlParser.create(text, babelConfig());
    }
}
