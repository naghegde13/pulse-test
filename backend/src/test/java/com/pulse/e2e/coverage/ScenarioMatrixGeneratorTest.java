package com.pulse.e2e.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioMatrixGeneratorTest {

    private final ScenarioMatrixGenerator generator = new ScenarioMatrixGenerator();

    @Test
    void pairwiseGenerationCoversEveryPairWithFewerCasesThanCartesianProduct() {
        var dimensions = List.of(
                new ScenarioDimension("family", List.of("INGESTION", "TRANSFORM", "DESTINATION")),
                new ScenarioDimension("backend", List.of("DPC", "GCP")),
                new ScenarioDimension("layer", List.of("bronze", "silver", "gold")),
                new ScenarioDimension("mode", List.of("manual", "cron"))
        );

        var cases = generator.generate(dimensions, 2);
        var covered = generator.coveredTuples(cases, 2);
        var exhaustive = generator.generate(dimensions, dimensions.size());
        var exhaustiveCovered = generator.coveredTuples(exhaustive, 2);

        assertEquals(exhaustiveCovered, covered);
        assertTrue(cases.size() < exhaustive.size(), "pairwise matrix should be smaller than cartesian coverage");
    }

    @Test
    void threeWayGenerationCoversEveryTriple() {
        var dimensions = List.of(
                new ScenarioDimension("family", List.of("INGESTION", "TRANSFORM")),
                new ScenarioDimension("backend", List.of("DPC", "GCP")),
                new ScenarioDimension("layer", List.of("bronze", "silver")),
                new ScenarioDimension("mode", List.of("manual", "cron"))
        );

        var cases = generator.generate(dimensions, 3);
        var covered = generator.coveredTuples(cases, 3);
        var exhaustive = generator.generate(dimensions, dimensions.size());
        var exhaustiveCovered = generator.coveredTuples(exhaustive, 3);

        assertEquals(exhaustiveCovered, covered);
        assertTrue(cases.size() <= exhaustive.size());
    }
}
