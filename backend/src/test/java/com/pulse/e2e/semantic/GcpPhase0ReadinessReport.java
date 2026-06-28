package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GcpPhase0ReadinessReport {

    public static final String APPROVED_PROJECT_ID = "pulse-proof-04261847";
    public static final String APPROVED_REGION = "us-central1";

    private final ObjectMapper objectMapper;

    public GcpPhase0ReadinessReport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path writeCredentialBlockedReport(Path gcpEnvironmentRoot,
                                             String activeProject,
                                             String credentialFailure) throws IOException {
        Files.createDirectories(gcpEnvironmentRoot);
        Path output = gcpEnvironmentRoot.resolve("gcp-readiness-report.json");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", Instant.now().toString());
        payload.put("project", Map.of(
                "expectedProjectId", APPROVED_PROJECT_ID,
                "activeProjectId", activeProject,
                "verdict", APPROVED_PROJECT_ID.equals(activeProject) ? "PASS" : "FAIL_STALE_LOCAL_DEFAULT"
        ));
        payload.put("region", APPROVED_REGION);
        payload.put("credential", Map.of(
                "verdict", "FAIL",
                "reasonCode", "NEEDS_CREDENTIAL_INPUT",
                "failureClass", "CREDENTIAL",
                "message", credentialFailure
        ));
        payload.put("phase0Verdict", "QUARANTINED");
        payload.put("blockedProofShapes", List.of("gcp-golden", "gcp-full"));
        payload.put("allowedContinuationLanes", List.of(
                "contract-adapter",
                "semantic-pack",
                "comparator",
                "semantic-oracle",
                "evidence-contract",
                "ledger-checks"
        ));
        payload.put("cloudResourceCreationAttempted", false);
        payload.put("destructiveCloudActionAttempted", false);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), payload);
        return output;
    }

    public Path writeResourceStateBlockedReport(Path gcpEnvironmentRoot,
                                                String composerEnvironment,
                                                String composerLocation,
                                                String resourceFailure) throws IOException {
        Files.createDirectories(gcpEnvironmentRoot);
        Path output = gcpEnvironmentRoot.resolve("gcp-readiness-report.json");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", Instant.now().toString());
        payload.put("project", Map.of(
                "expectedProjectId", APPROVED_PROJECT_ID,
                "activeProjectId", APPROVED_PROJECT_ID,
                "verdict", "PASS"
        ));
        payload.put("region", APPROVED_REGION);
        payload.put("authMode", "GCLOUD_SUBPROCESS");
        payload.put("credential", Map.of(
                "verdict", "PASS",
                "localSubmitterPrincipal", "user:aamer@aamer.net",
                "adc", "MISSING"
        ));
        payload.put("billing", Map.of("billingEnabled", true, "verdict", "PASS"));
        payload.put("apis", Map.of("requiredApis", "PASS"));
        payload.put("gcs", Map.of("requiredBucketsReachable", true, "verdict", "PASS"));
        payload.put("dataproc", Map.of("batchListReadable", true, "verdict", "PASS"));
        payload.put("composer", Map.of(
                "environment", composerEnvironment,
                "location", composerLocation,
                "verdict", "FAIL_NOT_FOUND",
                "message", resourceFailure
        ));
        payload.put("phase0Verdict", "QUARANTINED");
        payload.put("failureClass", "GCP_RESOURCE_STATE");
        payload.put("reasonCode", "COMPOSER_ENVIRONMENT_NOT_FOUND");
        payload.put("blockedProofShapes", List.of("gcp-golden", "gcp-full"));
        payload.put("allowedContinuationLanes", List.of(
                "contract-adapter",
                "semantic-pack",
                "comparator",
                "semantic-oracle",
                "evidence-contract",
                "ledger-checks"
        ));
        payload.put("cloudResourceCreationAttempted", false);
        payload.put("destructiveCloudActionAttempted", false);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), payload);
        return output;
    }
}
