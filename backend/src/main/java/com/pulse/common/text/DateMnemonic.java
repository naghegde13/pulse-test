package com.pulse.common.text;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codegen-time validator for PULSE date-mnemonic strings. Mirrors the vocabulary
 * accepted by the {@code pulse_dates.resolve_mnemonic} Python helper at runtime.
 *
 * <p>Used by services that persist blueprint param values: when a param's
 * {@code accepts_mnemonic} flag is {@code true} (set by V91), the value can be
 * either an ISO date literal {@code YYYY-MM-DD} or a mnemonic from the locked
 * vocabulary. This util rejects typos / unknown mnemonics at config time so
 * runtime resolution doesn't fail in the Airflow container.
 *
 * <p>Vocabulary kept in sync with {@code pulse_dates/__init__.py}. Any change
 * here MUST be matched in the Python resolver and the agent-prompt vocabulary
 * table in {@code PulseSystemPrompt}.
 */
public final class DateMnemonic {

    private DateMnemonic() {}

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern OFFSET = Pattern.compile("^([A-Z_]+)([+-]\\d+)?$");
    private static final Pattern NBDOM = Pattern.compile("^NBDOM\\(\\d+\\)$");

    /** Mnemonics that DO NOT accept an ±N offset suffix. */
    private static final Set<String> OFFSET_FORBIDDEN = Set.of(
            "TODAY", "RUN_DATE", "PREVIOUS_RUN_DATE",
            "SAME_DAY_LAST_WEEK", "SAME_DAY_LAST_MONTH",
            "SAME_DAY_LAST_QUARTER", "SAME_DAY_LAST_YEAR",
            "FBOM", "LBOM"
    );

    /** Aliases that include a hardcoded offset; the alias itself takes no further offset. */
    private static final Set<String> ALIASES_NO_OFFSET = Set.of(
            "WTD_START", "MTD_START", "QTD_START", "YTD_START", "FYTD_START",
            "LAST_COMPLETED_MONTH_START", "LAST_COMPLETED_MONTH_END",
            "LAST_COMPLETED_QUARTER_START", "LAST_COMPLETED_QUARTER_END"
    );

    /** Heads that accept an ±N offset (and may also be used without one). */
    private static final Set<String> HEADS_WITH_OFFSET = Set.of(
            "T", "BOW", "EOW",
            "BOM", "EOM", "BOQ", "EOQ", "BOH", "EOH", "BOY", "EOY",
            "BOFY", "EOFY", "BOFQ", "EOFQ", "BOFM", "EOFM",
            "PBD", "NBD"
    );

    /**
     * Returns true if the token is a valid ISO date literal OR a recognized mnemonic.
     */
    public static boolean isValid(String token) {
        if (token == null) return false;
        String trimmed = token.trim();
        if (trimmed.isEmpty()) return false;
        if (ISO_DATE.matcher(trimmed).matches()) {
            // Validate the date is parseable (not just shape-correct, e.g. 2026-13-99).
            try {
                LocalDate.parse(trimmed);
                return true;
            } catch (DateTimeParseException e) {
                return false;
            }
        }
        return isMnemonic(trimmed);
    }

    /**
     * Returns true if the token is a recognized mnemonic. Returns false for ISO date
     * literals — use {@link #isValid} when either form is acceptable.
     */
    public static boolean isMnemonic(String token) {
        if (token == null) return false;
        String upper = token.trim().toUpperCase();
        if (upper.isEmpty()) return false;

        // Aliases with no offset suffix accepted.
        if (ALIASES_NO_OFFSET.contains(upper)) return true;

        // NBDOM(N) — parameterized form.
        if (NBDOM.matcher(upper).matches()) return true;

        Matcher m = OFFSET.matcher(upper);
        if (!m.matches()) return false;
        String head = m.group(1);
        boolean hasOffset = m.group(2) != null;

        if (OFFSET_FORBIDDEN.contains(head)) {
            return !hasOffset;
        }
        return HEADS_WITH_OFFSET.contains(head);
    }

    /**
     * Validates the token and throws if it's neither a valid ISO date nor a mnemonic.
     * Use this at the boundary where blueprint params get persisted so the user gets
     * a helpful error at config time instead of a runtime failure inside the Airflow
     * container.
     */
    public static void validateOrThrow(String token) {
        if (!isValid(token)) {
            throw new IllegalArgumentException(
                    "Invalid date input: " + token
                            + ". Accepted: ISO date 'YYYY-MM-DD' or one of the PULSE date mnemonics "
                            + "(TODAY, T±N, BOW±N, EOW±N, BOM±N, EOM±N, BOQ±N, EOQ±N, BOH±N, EOH±N, "
                            + "BOY±N, EOY±N, BOFY±N, EOFY±N, BOFQ±N, EOFQ±N, BOFM±N, EOFM±N, "
                            + "PBD±N, NBD+N, FBOM, LBOM, NBDOM(N), "
                            + "SAME_DAY_LAST_WEEK/MONTH/QUARTER/YEAR, "
                            + "WTD_START, MTD_START, QTD_START, YTD_START, FYTD_START, "
                            + "LAST_COMPLETED_MONTH_START/END, LAST_COMPLETED_QUARTER_START/END, "
                            + "RUN_DATE, PREVIOUS_RUN_DATE).");
        }
    }
}
