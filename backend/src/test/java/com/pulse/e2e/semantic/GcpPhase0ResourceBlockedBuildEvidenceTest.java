package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GcpPhase0ResourceBlockedBuildEvidenceTest {

    @Test
    void writesComposerMissingPhaseZeroEvidenceUnderBuildDirectory() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Path root = Path.of("build/e2e-semantic-hardening");

        Path readiness = new GcpPhase0ReadinessReport(objectMapper)
                .writeResourceStateBlockedReport(
                        root.resolve("gcp-environment"),
                        "pulse-proof-composer",
                        "us-central1",
                        "Composer environment was not found during read-only Phase 0 preflight."
                );
        Path ledger = new SemanticHardeningLaneLedger()
                .writeComposerMissingLedger(
                        objectMapper,
                        root.resolve("progress/semantic-hardening-lane-ledger.json"),
                        List.of("contract-adapter", "semantic-pack", "comparator", "semantic-oracle", "evidence-contract", "ledger-checks")
                );

        assertTrue(Files.exists(readiness));
        assertTrue(Files.exists(ledger));
    }
}
