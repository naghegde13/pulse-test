package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.scenarios.LoanMasterRuntimeProofLedger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SemanticProofDenominatorReporter {

    private static final String ACTIVE_CATALOG_RESOURCE = "e2e/coverage/active-blueprint-catalog.json";

    private final ObjectMapper objectMapper;
    private final LoanMasterRuntimeProofLedger proofLedger;

    public SemanticProofDenominatorReporter(ObjectMapper objectMapper, LoanMasterRuntimeProofLedger proofLedger) {
        this.objectMapper = objectMapper;
        this.proofLedger = proofLedger;
    }

    public DenominatorReport build() throws IOException {
        List<String> activeBlueprintKeys = loadActiveBlueprintKeys();
        var ledger = proofLedger.load();
        List<String> representativeBlueprintKeys = ledger.entries().stream()
                .map(LoanMasterRuntimeProofLedger.ProofLedgerEntry::representativeBlueprintKey)
                .toList();

        Map<String, Object> sourceArtifacts = new LinkedHashMap<>();
        sourceArtifacts.put("activeCatalog", ACTIVE_CATALOG_RESOURCE);
        sourceArtifacts.put("representativeLedger", LoanMasterRuntimeProofLedger.RESOURCE_PATH);
        sourceArtifacts.put("approvedPlan", "docs/verification/blueprint-semantic-hardening-gcp-runtime-proof-plan.md");

        return new DenominatorReport(
                42,
                activeBlueprintKeys.size(),
                29,
                representativeBlueprintKeys.size(),
                0,
                activeBlueprintKeys.size() - representativeBlueprintKeys.size(),
                activeBlueprintKeys,
                representativeBlueprintKeys,
                sourceArtifacts
        );
    }

    public Path write(Path output) throws IOException {
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), build());
        return output;
    }

    private List<String> loadActiveBlueprintKeys() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(ACTIVE_CATALOG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + ACTIVE_CATALOG_RESOURCE);
            }
            return objectMapper.readTree(in).path("blueprints").findValuesAsText("blueprintKey");
        }
    }

    public record DenominatorReport(
            int activeCatalogTotal,
            int observedActiveCatalogTotal,
            int representativeLedgerTotal,
            int observedRepresentativeLedgerTotal,
            int hardenedRepresentativeTotal,
            int activeCatalogDebt,
            List<String> activeBlueprintKeys,
            List<String> representativeBlueprintKeys,
            Map<String, Object> sourceArtifacts
    ) {
    }
}
