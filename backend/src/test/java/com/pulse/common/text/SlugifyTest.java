package com.pulse.common.text;

import org.junit.jupiter.api.Test;

import static com.pulse.common.text.Slugify.slugify;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SlugifyTest {

    @Test
    void simpleSingleWord_returnsLowercase() {
        assertEquals("servicing", slugify("Servicing"));
    }

    @Test
    void specialCharsAndAmpersand_collapseToSingleDash() {
        assertEquals("loss-mitigation", slugify("Loss & Mitigation"));
        assertEquals("home-lending-d-i", slugify("Home Lending D&I"));
    }

    @Test
    void leadingAndTrailingWhitespace_isStripped() {
        assertEquals("capital-markets", slugify("  Capital  Markets  "));
    }

    @Test
    void onlyNonAlphanumericInput_returnsEmpty() {
        assertEquals("", slugify("---"));
        assertEquals("", slugify("   "));
        assertEquals("", slugify("&&&"));
    }

    @Test
    void nullInput_returnsEmpty() {
        assertEquals("", slugify(null));
    }

    @Test
    void alreadySlugified_isIdempotent() {
        // Re-slugging a slug must return the same slug; guards against double-application.
        String once = slugify("Capital Markets");
        String twice = slugify(once);
        assertEquals(once, twice);
        assertEquals("capital-markets", twice);
    }

    @Test
    void numbersArePreserved() {
        assertEquals("region-1-east", slugify("Region 1 East"));
        assertEquals("v2-pipeline", slugify("V2 Pipeline"));
    }

    @Test
    void underscoresAreCollapsedToDashes() {
        // Convention is kebab-case; underscores are normalized.
        assertEquals("snake-case-input", slugify("snake_case_input"));
    }

    @Test
    void unicodeFallsThroughAsNonAlphanumeric() {
        // Non-ASCII letters are NOT preserved; they become dashes. This matches the V83
        // regex which is `[^a-zA-Z0-9]+`. If we want unicode-aware slugs we need a
        // dedicated transliteration library — out of scope for the V83 compatibility goal.
        assertEquals("caf", slugify("café"));
    }
}
