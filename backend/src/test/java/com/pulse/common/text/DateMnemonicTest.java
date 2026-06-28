package com.pulse.common.text;

import org.junit.jupiter.api.Test;

import static com.pulse.common.text.DateMnemonic.isMnemonic;
import static com.pulse.common.text.DateMnemonic.isValid;
import static com.pulse.common.text.DateMnemonic.validateOrThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors the {@code test_pulse_dates.py} acceptance set. Any change here must
 * be reflected in both the Python resolver and the agent-prompt vocabulary.
 */
class DateMnemonicTest {

    @Test
    void isoDateLiteralIsValid() {
        assertTrue(isValid("2026-04-15"));
        assertTrue(isValid("  2026-04-15  "));     // whitespace tolerated
        assertTrue(isValid("1900-01-01"));
        assertTrue(isValid("2100-12-31"));
    }

    @Test
    void isoDateMustBeParseable() {
        assertFalse(isValid("2026-13-01"));         // bad month
        assertFalse(isValid("2026-02-30"));         // bad day for Feb
        assertFalse(isValid("not-a-date"));
        assertFalse(isValid("20260415"));           // wrong shape
    }

    @Test
    void isoDateIsNotAMnemonic() {
        assertFalse(isMnemonic("2026-04-15"));
    }

    @Test
    void todayRelativeMnemonics() {
        assertTrue(isMnemonic("TODAY"));
        assertTrue(isMnemonic("T-1"));
        assertTrue(isMnemonic("T+30"));
        assertTrue(isMnemonic("RUN_DATE"));
        assertTrue(isMnemonic("PREVIOUS_RUN_DATE"));
        assertFalse(isMnemonic("TODAY-1"));
        assertFalse(isMnemonic("RUN_DATE+1"));
    }

    @Test
    void weekMnemonics() {
        assertTrue(isMnemonic("BOW"));
        assertTrue(isMnemonic("BOW-2"));
        assertTrue(isMnemonic("EOW"));
        assertTrue(isMnemonic("EOW+1"));
        assertTrue(isMnemonic("SAME_DAY_LAST_WEEK"));
        assertFalse(isMnemonic("SAME_DAY_LAST_WEEK-1"));
    }

    @Test
    void monthMnemonics() {
        assertTrue(isMnemonic("BOM"));
        assertTrue(isMnemonic("BOM-1"));
        assertTrue(isMnemonic("BOM-12"));
        assertTrue(isMnemonic("EOM"));
        assertTrue(isMnemonic("EOM+3"));
        assertTrue(isMnemonic("FBOM"));
        assertTrue(isMnemonic("LBOM"));
        assertFalse(isMnemonic("FBOM-1"));
        assertFalse(isMnemonic("LBOM+1"));
    }

    @Test
    void nbdomParameterized() {
        assertTrue(isMnemonic("NBDOM(1)"));
        assertTrue(isMnemonic("NBDOM(5)"));
        assertTrue(isMnemonic("NBDOM(20)"));
        assertFalse(isMnemonic("NBDOM"));
        assertFalse(isMnemonic("NBDOM()"));
        assertFalse(isMnemonic("NBDOM(abc)"));
    }

    @Test
    void quarterMnemonics() {
        assertTrue(isMnemonic("BOQ"));
        assertTrue(isMnemonic("BOQ-1"));
        assertTrue(isMnemonic("EOQ"));
        assertTrue(isMnemonic("EOQ+2"));
        assertTrue(isMnemonic("SAME_DAY_LAST_QUARTER"));
    }

    @Test
    void halfYearMnemonics() {
        assertTrue(isMnemonic("BOH"));
        assertTrue(isMnemonic("BOH-1"));
        assertTrue(isMnemonic("EOH"));
        assertTrue(isMnemonic("EOH+1"));
    }

    @Test
    void yearMnemonics() {
        assertTrue(isMnemonic("BOY"));
        assertTrue(isMnemonic("BOY-3"));
        assertTrue(isMnemonic("EOY"));
        assertTrue(isMnemonic("EOY+1"));
        assertTrue(isMnemonic("SAME_DAY_LAST_YEAR"));
    }

    @Test
    void fiscalMnemonics() {
        assertTrue(isMnemonic("BOFY"));
        assertTrue(isMnemonic("BOFY-1"));
        assertTrue(isMnemonic("EOFY"));
        assertTrue(isMnemonic("BOFQ"));
        assertTrue(isMnemonic("EOFQ-1"));
        assertTrue(isMnemonic("BOFM"));
        assertTrue(isMnemonic("EOFM+1"));
    }

    @Test
    void businessDayMnemonics() {
        assertTrue(isMnemonic("PBD"));
        assertTrue(isMnemonic("PBD-3"));
        assertTrue(isMnemonic("NBD"));
        assertTrue(isMnemonic("NBD+5"));
    }

    @Test
    void aliasesAccepted() {
        assertTrue(isMnemonic("WTD_START"));
        assertTrue(isMnemonic("MTD_START"));
        assertTrue(isMnemonic("QTD_START"));
        assertTrue(isMnemonic("YTD_START"));
        assertTrue(isMnemonic("FYTD_START"));
        assertTrue(isMnemonic("LAST_COMPLETED_MONTH_START"));
        assertTrue(isMnemonic("LAST_COMPLETED_MONTH_END"));
        assertTrue(isMnemonic("LAST_COMPLETED_QUARTER_START"));
        assertTrue(isMnemonic("LAST_COMPLETED_QUARTER_END"));
        // Aliases hardcode their own offset; further suffix not allowed.
        assertFalse(isMnemonic("LAST_COMPLETED_MONTH_START-1"));
    }

    @Test
    void unknownMnemonicsRejected() {
        assertFalse(isMnemonic("MAGIC_DATE"));
        assertFalse(isMnemonic("YESTERDAY"));        // not in vocabulary
        assertFalse(isMnemonic("EOQ_PLUS_ONE"));     // wrong syntax
    }

    @Test
    void mnemonicMatchingIsCaseInsensitive() {
        // The validator uppercases before matching so config-time errors stay
        // helpful — typing "bom" or "BoM-1" doesn't punish the user.
        assertTrue(isMnemonic("bom"));
        assertTrue(isMnemonic("BoM-1"));
        assertTrue(isMnemonic("eoq+2"));
    }

    @Test
    void blankAndNullRejected() {
        assertFalse(isValid(null));
        assertFalse(isValid(""));
        assertFalse(isValid("   "));
        assertFalse(isMnemonic(null));
        assertFalse(isMnemonic(""));
    }

    @Test
    void validateOrThrowOnInvalid() {
        assertThrows(IllegalArgumentException.class, () -> validateOrThrow("MAGIC_DATE"));
        assertThrows(IllegalArgumentException.class, () -> validateOrThrow(""));
        assertThrows(IllegalArgumentException.class, () -> validateOrThrow(null));
        assertThrows(IllegalArgumentException.class, () -> validateOrThrow("2026-13-01"));
    }

    @Test
    void validateOrThrowDoesNotThrowOnValid() {
        validateOrThrow("2026-04-15");
        validateOrThrow("BOM");
        validateOrThrow("EOM-1");
        validateOrThrow("PBD-3");
        validateOrThrow("NBDOM(5)");
        validateOrThrow("LAST_COMPLETED_MONTH_START");
    }
}
