package com.pulse.expression.service;

import java.util.regex.Pattern;

/**
 * Tiny reusable scanner/substituter for the inline {@code [[ <mnemonic> ]]}
 * date token (SPEC #6 §D). This does NOT fork the 3-way-synced mnemonic
 * vocabulary ({@code DateMnemonic.java}); it only handles the embedded-token
 * scan for Calcite typing.
 *
 * <p>SPEC #6 §D.3: when the validator parses SQL containing {@code [[ … ]]}, it
 * treats each token as a <b>DATE-typed placeholder</b> purely for
 * parsing/typing/schema-derivation — it does NOT resolve the date value, and it
 * does NOT call {@code DateMnemonic} here (config-time mnemonic validation is a
 * codegen concern, deferred). The token must type as DATE in ANY position (bare
 * predicate operand AND function argument, and DATE for overload resolution),
 * so we substitute a typed {@code DATE} literal {@code DATE '1970-01-01'}.
 */
final class DateMnemonicTokenScanner {

    private DateMnemonicTokenScanner() {
    }

    /** Matches {@code [[ <anything> ]]} (non-greedy inner). */
    static final Pattern TOKEN = Pattern.compile("\\[\\[\\s*(.*?)\\s*\\]\\]");

    /**
     * A typed DATE value so Calcite types the placeholder as DATE in ANY position
     * (§D.3). Uses {@code CAST('…' AS DATE)} rather than the {@code DATE '…'}
     * typed-literal form, which the Babel/Spark-conformance parser rejects in some
     * operand positions (e.g. inside a function-call argument list); the
     * {@code CAST} form parses and types as DATE uniformly everywhere.
     *
     * <p><b>Known Babel parser limitation (honest):</b> the Babel grammar defines
     * a <b>special {@code DATEDIFF}</b> form that accepts only a simple identifier
     * as its FIRST operand — it rejects ANY non-identifier expression there,
     * including both this {@code CAST(…)} placeholder AND a {@code DATE '…'}
     * literal (verified: {@code DATEDIFF(d1, d2)} parses; {@code DATEDIFF(CAST('…'
     * AS DATE), d)} does not). This is a parser-grammar limitation, not a typing
     * one — the placeholder types as DATE correctly in every position the parser
     * admits (predicate operand, and other function args such as {@code DATE_SUB}
     * / {@code DATE_ADD}). An inline {@code [[ … ]]} used as the FIRST
     * {@code DATEDIFF} argument therefore surfaces as a loud PARSE_ERROR.
     */
    static final String DATE_PLACEHOLDER = "CAST('1970-01-01' AS DATE)";

    /**
     * Replaces every {@code [[ … ]]} token with a typed DATE literal so Calcite
     * types it as DATE for parse / overload resolution / schema derivation.
     * The real mnemonic value is never resolved here (§D.3).
     */
    static String substituteDatePlaceholders(String sql) {
        if (sql == null || sql.indexOf("[[") < 0) {
            return sql;
        }
        return TOKEN.matcher(sql).replaceAll(java.util.regex.Matcher.quoteReplacement(DATE_PLACEHOLDER));
    }
}
