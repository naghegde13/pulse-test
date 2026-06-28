package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GcpPhase0CredentialBlockedBuildEvidenceTest {

    @Test
    void writesCredentialBlockedPhaseZeroEvidenceUnderBuildDirectory() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Path root = Path.of("build/e2e-semantic-hardening");
        String activeProject = System.getProperty("pulse.gcp.phase0.activeProject", "UNKNOWN");
        String credentialFailure = System.getProperty("pulse.gcp.phase0.credentialFailure", "Phase 0 GCP probe was not executed.");

        Path readiness = new GcpPhase0ReadinessReport(objectMapper)
                .writeCredentialBlockedReport(root.resolve("gcp-environment"), activeProject, credentialFailure);
        Path ledger = new SemanticHardeningLaneLedger()
                .writeCredentialBlockedLedger(
                        objectMapper,
                        root.resolve("progress/semantic-hardening-lane-ledger.json"),
                        "NEEDS_CREDENTIAL_INPUT",
                        List.of("contract-adapter", "semantic-pack", "comparator", "semantic-oracle", "evidence-contract", "ledger-checks")
                );

        assertTrue(Files.exists(readiness));
        assertTrue(Files.exists(ledger));
    }
}
