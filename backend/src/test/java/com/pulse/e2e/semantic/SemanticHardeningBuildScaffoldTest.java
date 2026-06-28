package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.scenarios.LoanMasterRuntimeProofLedger;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticHardeningBuildScaffoldTest {

    @Test
    void writesCanonicalPhaseZeroScaffoldArtifactsUnderBuildDirectory() throws Exception {
        Path root = Path.of("build/e2e-semantic-hardening");
        var artifacts = new SemanticHardeningScaffoldWriter(
                new ObjectMapper().findAndRegisterModules(),
                new LoanMasterRuntimeProofLedger()
        ).write(root);

        assertTrue(Files.exists(artifacts.denominatorReport()));
        assertTrue(Files.exists(artifacts.semanticProofCatalog()));
        assertTrue(Files.exists(artifacts.selectionReport()));
        assertTrue(Files.exists(artifacts.laneLedger()));
        assertTrue(Files.exists(artifacts.phase0Manifest()));
    }
}
