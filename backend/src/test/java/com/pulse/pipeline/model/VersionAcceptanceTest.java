package com.pulse.pipeline.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionAcceptanceTest {

    @Test
    void defaultsPinPhaseOneAcceptanceContract() {
        VersionAcceptance acceptance = new VersionAcceptance();

        assertEquals("MERGED_PR_EXACT_HEAD", acceptance.getAcceptanceKind());
        assertEquals("ACTIVE", acceptance.getAcceptanceStatus());
    }
}
