package com.pulse.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlugNormalizerTest {

    @Test
    void toBqIdentifier_kebabToSnake() {
        assertEquals("home_lending", SlugNormalizer.toBqIdentifier("home-lending"));
        assertEquals("acme", SlugNormalizer.toBqIdentifier("acme"));
        assertEquals("a_b_c", SlugNormalizer.toBqIdentifier("a-b-c"));
    }

    @Test
    void toBqIdentifier_idempotent() {
        String once = SlugNormalizer.toBqIdentifier("home-lending");
        String twice = SlugNormalizer.toBqIdentifier(once);
        assertEquals(once, twice);
    }

    @Test
    void toBqIdentifier_lowercases() {
        assertEquals("acme_co", SlugNormalizer.toBqIdentifier("ACME-Co"));
    }

    @Test
    void toBqIdentifier_collapsesNonAlphanumericRuns() {
        assertEquals("foo_bar", SlugNormalizer.toBqIdentifier("foo--bar"));
        assertEquals("foo_bar", SlugNormalizer.toBqIdentifier("foo  bar"));
        assertEquals("foo_bar", SlugNormalizer.toBqIdentifier("foo&&bar"));
    }

    @Test
    void toBqIdentifier_stripsTrailingUnderscore() {
        assertEquals("foo", SlugNormalizer.toBqIdentifier("foo-"));
        assertEquals("foo", SlugNormalizer.toBqIdentifier("foo--"));
    }

    @Test
    void toBqIdentifier_throwsOnEmptyOrAllPunctuation() {
        assertThrows(IllegalArgumentException.class,
                () -> SlugNormalizer.toBqIdentifier(""));
        assertThrows(IllegalArgumentException.class,
                () -> SlugNormalizer.toBqIdentifier("---"));
        assertThrows(IllegalArgumentException.class,
                () -> SlugNormalizer.toBqIdentifier(null));
    }

    @Test
    void validatePathSlug_acceptsKebab() {
        SlugNormalizer.validatePathSlug("home-lending");
        SlugNormalizer.validatePathSlug("acme");
        SlugNormalizer.validatePathSlug("loan-master-2024");
    }

    @Test
    void validatePathSlug_rejectsLeadingTrailingDash() {
        assertThrows(IllegalArgumentException.class,
                () -> SlugNormalizer.validatePathSlug("-foo"));
        assertThrows(IllegalArgumentException.class,
                () -> SlugNormalizer.validatePathSlug("foo-"));
    }

    @Test
    void validatePathSlug_rejectsDoubleDash() {
        assertThrows(IllegalArgumentException.class,
                () -> SlugNormalizer.validatePathSlug("foo--bar"));
    }

    @Test
    void validatePathSlug_rejectsUppercase() {
        assertThrows(IllegalArgumentException.class,
                () -> SlugNormalizer.validatePathSlug("Foo"));
    }

    @Test
    void validatePathSlug_rejectsSpecialChars() {
        assertThrows(IllegalArgumentException.class,
                () -> SlugNormalizer.validatePathSlug("foo bar"));
        assertThrows(IllegalArgumentException.class,
                () -> SlugNormalizer.validatePathSlug("foo_bar"));
        assertThrows(IllegalArgumentException.class,
                () -> SlugNormalizer.validatePathSlug("foo.bar"));
    }
}
