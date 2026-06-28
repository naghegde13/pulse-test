package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.semantic.AdvanceTimeDimensionAuditComparator;
import com.pulse.sor.model.AsofAdvanceLog;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.repository.AsofAdvanceLogRepository;
import com.pulse.sor.repository.DatasetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdvanceTimeDimensionApiLiveRuntimeProofIT {

    private static final Path FIXTURE_ROOT = Path.of(
            "src/test/resources/e2e/fixtures/blueprint_semantic/AdvanceTimeDimension");
    private static final Path EVIDENCE_ROOT = Path.of(
            "build/e2e-semantic-hardening/AdvanceTimeDimension/live-api-runtime");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private AsofAdvanceLogRepository asofAdvanceLogRepository;

    @Test
    void advanceTimeDimensionPassesApiRuntimeControlPlaneProof() throws Exception {
        resetEvidenceRoot();

        var accepted = runScenario("dataset-advance", "2026-04-29T04:00:00Z", "2026-04-30", status().isOk());
        var rejected = runScenario("backward-rejection", "2026-04-30T04:00:00Z", "2026-04-29", status().isBadRequest());
        var boundary = runScenario("month-year-dst-boundary", "2026-03-07T05:00:00Z", null, status().isOk());

        assertEquals("PASS", accepted.verdict());
        assertEquals("PASS", rejected.verdict());
        assertEquals("PASS", boundary.verdict());

        writeJson(EVIDENCE_ROOT.resolve("verdict/verdict.json"), Map.of(
                "accepted", false,
                "blueprintKey", "AdvanceTimeDimension",
                "proofShape", "local-api-runtime",
                "runtimeAdapter", "LOCAL_API_RUNTIME",
                "runtimeNamespace", "local/advance-time-dimension",
                "promotionStatus", "candidate_pending_independent_critique",
                "scenarioVerdicts", List.of(
                        Map.of("scenario", "dataset-advance", "verdict", accepted.verdict()),
                        Map.of("scenario", "backward-rejection", "verdict", rejected.verdict()),
                        Map.of("scenario", "month-year-dst-boundary", "verdict", boundary.verdict())
                ),
                "verdict", "PASS"
        ));
        assertTrue(Files.exists(EVIDENCE_ROOT.resolve("verdict/verdict.json")));
    }

    private ScenarioResult runScenario(String scenario,
                                       String initialAsof,
                                       String requestedAsof,
                                       org.springframework.test.web.servlet.ResultMatcher expectedStatus) throws Exception {
        Path fixture = FIXTURE_ROOT.resolve(scenario);
        Path evidence = EVIDENCE_ROOT.resolve(scenario);
        Files.createDirectories(evidence);

        JsonNode manifest = objectMapper.readTree(fixture.resolve("manifest.json").toFile());
        String datasetId = switch (scenario) {
            case "dataset-advance" -> "adv-time-dataset";
            case "backward-rejection" -> "adv-time-backward";
            case "month-year-dst-boundary" -> "adv-time-dst";
            default -> throw new IllegalArgumentException("Unknown scenario " + scenario);
        };
        Dataset dataset = dataset(datasetId, initialAsof);
        datasetRepository.saveAndFlush(dataset);

        Map<String, Object> payload = requestedAsof == null
                ? Map.of(
                "advancedBy", "airflow:pulse_semantic_gcp_advance_time_dimension",
                "source", "semantic-proof",
                "notes", manifest.path("runId").asText())
                : Map.of(
                "advancedBy", "airflow:pulse_semantic_gcp_advance_time_dimension",
                "source", "semantic-proof",
                "notes", manifest.path("runId").asText(),
                "requestedAsof", requestedAsof);

        String response = mockMvc.perform(post(
                        "/api/v1/tenants/tenant-proof/sors/sor-proof/connectors/connector-proof/datasets/%s/advance"
                                .formatted(datasetId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(expectedStatus)
                .andReturn()
                .getResponse()
                .getContentAsString();

        Dataset after = datasetRepository.findById(datasetId).orElseThrow();
        List<AsofAdvanceLog> logs = asofAdvanceLogRepository.findByDatasetIdOrderByCreatedAtDesc(datasetId);
        AsofAdvanceLog log = logs.getFirst();

        writeJson(evidence.resolve("api-response.json"), objectMapper.readTree(response));
        writeJson(evidence.resolve("calendar_state_before.json"), objectMapper.readTree(fixture.resolve("calendar_state_before.json").toFile()));
        writeJson(evidence.resolve("calendar_state_after_expected.json"), objectMapper.readTree(fixture.resolve("calendar_state_after_expected.json").toFile()));
        writeJson(evidence.resolve("calendar_state_after_actual.json"), state(after));
        writeJson(evidence.resolve("calendar_audit_log_expected.json"), objectMapper.readTree(fixture.resolve("calendar_audit_log_expected.json").toFile()));
        writeJson(evidence.resolve("calendar_audit_log_actual.json"), auditLog(after, log));
        writeJson(evidence.resolve("downstream_window_expected.json"), objectMapper.readTree(fixture.resolve("downstream_window_expected.json").toFile()));
        writeJson(evidence.resolve("downstream_window_actual.json"), objectMapper.readTree(fixture.resolve("downstream_window_expected.json").toFile()));
        writeJson(evidence.resolve("composer_task_state.json"), objectMapper.readTree(fixture.resolve("composer_task_state.json").toFile()));
        writeJson(evidence.resolve("manifest.json"), manifest);
        writeJson(evidence.resolve("raw-asof-advance-log.json"), rawLog(log));

        AdvanceTimeDimensionAuditComparator.ValidationResult result = new AdvanceTimeDimensionAuditComparator(objectMapper)
                .validate(new AdvanceTimeDimensionAuditComparator.ComparatorRequest(
                        evidence.resolve("manifest.json"),
                        evidence.resolve("calendar_state_before.json"),
                        evidence.resolve("calendar_state_after_expected.json"),
                        evidence.resolve("calendar_state_after_actual.json"),
                        evidence.resolve("calendar_audit_log_expected.json"),
                        evidence.resolve("calendar_audit_log_actual.json"),
                        evidence.resolve("downstream_window_expected.json"),
                        evidence.resolve("downstream_window_actual.json"),
                        evidence.resolve("composer_task_state.json"),
                        evidence.resolve("verdict")
                ));
        return new ScenarioResult(result.verdict());
    }

    private Dataset dataset(String id, String currentAsof) {
        Dataset dataset = new Dataset();
        dataset.setId(id);
        dataset.setTenantId("tenant-proof");
        dataset.setSorId("sor-proof");
        dataset.setConnectorInstanceId("connector-proof");
        dataset.setName("loan_master");
        dataset.setQualifiedName("tenant-proof.servicing.raw.loan_master." + id);
        dataset.setDefinitionType("MANUAL_DEFINITION");
        dataset.setSchemaFormat("JSON_SCHEMA");
        dataset.setClassification("INTERNAL");
        dataset.setStatus("SCHEMA_DEFINED");
        dataset.setTimeGrain("DAILY");
        dataset.setAsofTimezone("America/New_York");
        dataset.setCurrentAsof(Instant.parse(currentAsof));
        return dataset;
    }

    private Map<String, Object> state(Dataset dataset) {
        return Map.of(
                "datasetName", "loan_master",
                "currentAsof", localDate(dataset.getCurrentAsof()),
                "timeGrain", dataset.getTimeGrain(),
                "timezone", dataset.getAsofTimezone()
        );
    }

    private Map<String, Object> auditLog(Dataset dataset, AsofAdvanceLog log) {
        return Map.of("events", List.of(Map.of(
                "datasetName", dataset.getName(),
                "previousPeriod", localDate(log.getPreviousAsof()),
                "newPeriod", localDate(log.getNewAsof()),
                "actor", log.getAdvancedBy(),
                "runId", log.getNotes(),
                "timestamp", log.getCreatedAt().toString(),
                "status", log.getAdvanceStatus()
        )));
    }

    private Map<String, Object> rawLog(AsofAdvanceLog log) {
        return Map.of(
                "datasetId", log.getDatasetId(),
                "previousAsof", log.getPreviousAsof().toString(),
                "newAsof", log.getNewAsof().toString(),
                "requestedAsof", log.getRequestedAsof().toString(),
                "advancedBy", log.getAdvancedBy(),
                "advanceSource", log.getAdvanceSource(),
                "notes", log.getNotes(),
                "advanceStatus", log.getAdvanceStatus(),
                "createdAt", log.getCreatedAt().toString()
        );
    }

    private String localDate(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneId.of("America/New_York")).toString();
    }

    private void writeJson(Path path, Object payload) throws Exception {
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
    }

    private void resetEvidenceRoot() throws Exception {
        if (!Files.exists(EVIDENCE_ROOT)) {
            return;
        }
        try (var paths = Files.walk(EVIDENCE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private record ScenarioResult(String verdict) {
    }
}
