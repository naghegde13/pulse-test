package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.scenarios.LoanMasterRuntimeProofLedger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SemanticHardeningScaffoldWriter {

    private final ObjectMapper objectMapper;
    private final LoanMasterRuntimeProofLedger proofLedger;

    public SemanticHardeningScaffoldWriter(ObjectMapper objectMapper, LoanMasterRuntimeProofLedger proofLedger) {
        this.objectMapper = objectMapper;
        this.proofLedger = proofLedger;
    }

    public ScaffoldArtifacts write(Path root) throws IOException {
        Path catalogRoot = root.resolve("catalog");
        Path progressRoot = root.resolve("progress");
        Path gcpEnvironmentRoot = root.resolve("gcp-environment");

        Path denominatorReport = new SemanticProofDenominatorReporter(objectMapper, proofLedger)
                .write(catalogRoot.resolve("semantic-proof-denominator-report.json"));
        SemanticProofCatalog semanticProofCatalogBuilder = new SemanticProofCatalog();
        var representativeTargets = semanticProofCatalogBuilder.buildRepresentativeTargets(proofLedger.load());
        Path semanticProofCatalog = semanticProofCatalogBuilder
                .writeRepresentativeTargets(objectMapper, proofLedger.load(), catalogRoot.resolve("semantic-proof-catalog.json"));
        Path selectionReport = new SemanticProofCiSelectionReport()
                .write(objectMapper, representativeTargets, catalogRoot.resolve("semantic-proof-ci-selection-report.json"));
        Path laneLedger = new SemanticHardeningLaneLedger()
                .writeInitialLedger(objectMapper, progressRoot.resolve("semantic-hardening-lane-ledger.json"));
        Path phase0Manifest = writePhase0Manifest(gcpEnvironmentRoot.resolve("phase0-required-artifacts.json"));

        return new ScaffoldArtifacts(denominatorReport, semanticProofCatalog, selectionReport, laneLedger, phase0Manifest);
    }

    private Path writePhase0Manifest(Path output) throws IOException {
        Files.createDirectories(output.getParent());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("evidenceRoot", SemanticHardeningEvidenceContracts.GCP_ENVIRONMENT_ROOT);
        payload.put("artifactReferencesAreRelativeToEvidenceRoot", true);
        payload.put("requiredArtifacts", SemanticHardeningEvidenceContracts.PHASE_0_GCP_ENVIRONMENT_ARTIFACTS.stream().sorted().toList());
        payload.put("cloudResourceCreationApproved", false);
        payload.put("destructiveCloudActionsApproved", false);
        payload.put("promotionRequiresVerdictPass", true);
        payload.put("notes", List.of(
                "This manifest is scaffold only; it is not a GCP readiness verdict.",
                "Cloud runtime proof lanes remain blocked until gcp-environment-smoke-verdict.json exists with PASS fields required by the approved plan."
        ));

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), payload);
        return output;
    }

    public record ScaffoldArtifacts(
            Path denominatorReport,
            Path semanticProofCatalog,
            Path selectionReport,
            Path laneLedger,
            Path phase0Manifest
    ) {
    }
}
