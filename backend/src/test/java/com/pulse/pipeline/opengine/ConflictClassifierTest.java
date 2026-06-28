package com.pulse.pipeline.opengine;

import com.pulse.pipeline.opengine.ConflictClassifier.Tier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Phase 1 — 3-tier conflict classification (SPEC #1 §B.2). */
class ConflictClassifierTest {

    private final ConflictClassifier classifier = new ConflictClassifier();

    @Test
    void missingRequiredColumnIsBreaking() {
        ConflictClassifier.Classification c = classifier.classify(
                ConflictClassifier.MISSING_COLUMN, null, null, true, List.of("instA"));
        assertEquals(Tier.BREAKING, c.tier());
        assertEquals(List.of("instA"), List.copyOf(c.impactRadius()));
    }

    @Test
    void missingNonRequiredColumnIsPartial() {
        ConflictClassifier.Classification c = classifier.classify(
                ConflictClassifier.MISSING_COLUMN, null, null, false, List.of());
        assertEquals(Tier.PARTIAL, c.tier());
    }

    @Test
    void incompatibleTypeChangeOnRequiredColumnIsBreaking() {
        ConflictClassifier.Classification c = classifier.classify(
                ConflictClassifier.TYPE_MISMATCH, "string", "integer", true, List.of());
        assertEquals(Tier.BREAKING, c.tier());
    }

    @Test
    void wideningTypeChangeIsPartial() {
        // integer -> long is a widening: the op still resolves, coverage changes => partial.
        ConflictClassifier.Classification c = classifier.classify(
                ConflictClassifier.TYPE_MISMATCH, "integer", "long", true, List.of());
        assertEquals(Tier.PARTIAL, c.tier());
    }

    @Test
    void sameTypeIsNonBreaking() {
        ConflictClassifier.Classification c = classifier.classify(
                ConflictClassifier.TYPE_MISMATCH, "string", "string", true, List.of());
        assertEquals(Tier.NON_BREAKING, c.tier());
    }

    @Test
    void renamedColumnIsPartial() {
        ConflictClassifier.Classification c = classifier.classify(
                ConflictClassifier.RENAMED_COLUMN, null, null, true, List.of());
        assertEquals(Tier.PARTIAL, c.tier());
    }

    @Test
    void addedColumnIsNonBreaking() {
        ConflictClassifier.Classification c = classifier.classify(
                ConflictClassifier.ADDED_COLUMN, null, null, false, List.of());
        assertEquals(Tier.NON_BREAKING, c.tier());
    }
}
