package com.pulse.e2e.scenarios;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LoanMasterRuntimeProofLedger {

    public static final String RESOURCE_PATH = "e2e/coverage/loan-master-runtime-proof-ledger.json";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public ProofLedger load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + RESOURCE_PATH);
            }
            return objectMapper.readValue(in, ProofLedger.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + RESOURCE_PATH, e);
        }
    }

    public Map<String, ProofLedgerEntry> loadByBlueprintKey() {
        return load().entries().stream()
                .collect(Collectors.toMap(
                        ProofLedgerEntry::representativeBlueprintKey,
                        entry -> entry,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    public record ProofLedger(
            String dataset,
            String contractVersion,
            String liveRuntimeStandard,
            List<ProofLedgerEntry> entries
    ) {
        public ProofLedger {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    public record ProofLedgerEntry(
            String representativeBlueprintKey,
            String scenarioId,
            String hardProofStatus,
            String proofTest,
            String outputProbeArtifactType,
            String outputProbePathPattern,
            String oracleArtifactType,
            String oracleArtifactPathPattern,
            String requiredVerdict,
            String blockerReason,
            String notes
    ) {
    }
}
