package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.contract.ScenarioDsl;
import com.pulse.e2e.scenarios.LoanMasterRuntimeProofLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticHardeningScaffoldTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final LoanMasterRuntimeProofLedger proofLedger = new LoanMasterRuntimeProofLedger();

    @TempDir Path tempDir;

    @Test
    void denominatorReportKeepsActiveAndRepresentativeBoundariesVisible() throws Exception {
        Path report = new SemanticProofDenominatorReporter(objectMapper, proofLedger)
                .write(tempDir.resolve("catalog/semantic-proof-denominator-report.json"));

        JsonNode json = objectMapper.readTree(report.toFile());
        assertEquals(42, json.get("activeCatalogTotal").asInt());
        assertEquals(42, json.get("observedActiveCatalogTotal").asInt());
        assertEquals(29, json.get("representativeLedgerTotal").asInt());
        assertEquals(29, json.get("observedRepresentativeLedgerTotal").asInt());
        assertEquals(0, json.get("hardenedRepresentativeTotal").asInt());
        assertEquals(13, json.get("activeCatalogDebt").asInt());
        assertTrue(textArray(json.get("representativeBlueprintKeys")).contains("GenericFilter"));
    }

    @Test
    void initialLaneLedgerContainsRequiredStateFieldsAndBlocksCloudPromotionUntilReadinessPasses() throws Exception {
        Path ledgerPath = new SemanticHardeningLaneLedger()
                .writeInitialLedger(objectMapper, tempDir.resolve("progress/semantic-hardening-lane-ledger.json"));

        JsonNode root = objectMapper.readTree(ledgerPath.toFile());
        JsonNode lanes = root.get("lanes");
        assertFalse(lanes.isEmpty());

        JsonNode readiness = lane(lanes, "gcp-readiness-smoke");
        assertEquals("READY_TO_RUN", readiness.get("laneState").asText());
        assertTrue(readiness.get("requiresGcp").asBoolean());
        assertEquals("NONE", readiness.get("failureClass").asText());
        assertEquals("PENDING", readiness.get("cleanupStatus").asText());
        assertTrue(textArray(readiness.get("blockedProofShapes")).contains("gcp-golden"));
        assertTrue(textArray(readiness.get("blockedProofShapes")).contains("gcp-full"));

        JsonNode contract = lane(lanes, "contract-adapter");
        assertEquals("READY_TO_RUN", contract.get("laneState").asText());
        assertFalse(contract.get("requiresGcp").asBoolean());

        JsonNode destination = lane(lanes, "databasewriter-destination-selection");
        assertEquals("PASS", destination.get("laneState").asText());
        assertEquals("NONE", destination.get("failureClass").asText());
        assertEquals("PASS", destination.get("cleanupStatus").asText());
        assertTrue(destination.get("terminalStopReason").isNull());

        for (JsonNode lane : lanes) {
            assertRequiredLaneFields(lane);
        }
    }

    @Test
    void semanticProofCatalogRecordsDatabaseWriterBigQueryDestinationDecision() throws Exception {
        Path catalogPath = new SemanticProofCatalog()
                .writeRepresentativeTargets(
                        objectMapper,
                        proofLedger.load(),
                        tempDir.resolve("catalog/semantic-proof-catalog.json")
                );

        JsonNode rows = objectMapper.readTree(catalogPath.toFile());
        assertEquals(29, rows.size());
        JsonNode databaseWriter = StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> row.get("blueprintKey").asText().equals("DatabaseWriter"))
                .findFirst()
                .orElseThrow();
        assertEquals("PENDING_SEMANTIC_HARDENING", databaseWriter.get("semanticHardeningStatus").asText());
        assertEquals("blocked-semantic-dev", databaseWriter.get("proofShape").asText());
        assertTrue(databaseWriter.get("representativeLedgerMember").asBoolean());
        assertTrue(databaseWriter.get("activeCatalogMember").asBoolean());
        assertEquals("draft", databaseWriter.get("promotionStatus").asText());
        assertEquals("semantic-DatabaseWriter", databaseWriter.get("scenarioGroupId").asText());
        assertEquals("delinquent_loans", databaseWriter.get("fixtureDerivativeId").asText());
        assertFalse(databaseWriter.get("fixtureSha256").asText().isBlank());
    }

    @Test
    void semanticProofCatalogFindsDatabaseWriterHyphenatedGcpGoldenCandidateArtifacts() throws Exception {
        Path buildRoot = tempDir.resolve("backend/build/e2e-semantic-hardening");
        Path runRoot = buildRoot.resolve("gcp-golden/database-writer/run-20260506-1225");
        writeJson(runRoot.resolve("evidence-index.json"), Map.of(
                "blueprintKey", "DatabaseWriter",
                "schemaVersion", "1",
                "verdict", "PASS"
        ));
        writeJson(runRoot.resolve("critique-verdict.json"), Map.of(
                "blueprintKey", "DatabaseWriter",
                "verdict", "PASS",
                "promotionAllowed", true
        ));

        Path catalogPath = new SemanticProofCatalog(buildRoot)
                .writeRepresentativeTargets(
                        objectMapper,
                        proofLedger.load(),
                        tempDir.resolve("catalog/semantic-proof-catalog.json")
                );

        JsonNode rows = objectMapper.readTree(catalogPath.toFile());
        JsonNode databaseWriter = StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> row.get("blueprintKey").asText().equals("DatabaseWriter"))
                .findFirst()
                .orElseThrow();
        assertEquals("CANDIDATE_SEMANTIC_HARDENING", databaseWriter.get("semanticHardeningStatus").asText());
        assertEquals("candidate", databaseWriter.get("promotionStatus").asText());
        assertTrue(databaseWriter.get("evidenceIndexPath").asText()
                .endsWith("gcp-golden/database-writer/run-20260506-1225/evidence-index.json"));
    }

    @Test
    void semanticProofCatalogPromotesCritiqueAndReceiptMetadataIntoSelectorFields() throws Exception {
        Path buildRoot = tempDir.resolve("backend/build/e2e-semantic-hardening");
        seedPromotionArtifacts(buildRoot, "SnapshotModel");

        Path catalogPath = new SemanticProofCatalog(buildRoot)
                .writeRepresentativeTargets(
                        objectMapper,
                        proofLedger.load(),
                        tempDir.resolve("catalog/semantic-proof-catalog.json")
                );

        JsonNode rows = objectMapper.readTree(catalogPath.toFile());
        JsonNode snapshotModel = StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> row.get("blueprintKey").asText().equals("SnapshotModel"))
                .findFirst()
                .orElseThrow();

        assertEquals("PROMOTED_SEMANTIC_HARDENING", snapshotModel.get("semanticHardeningStatus").asText());
        assertEquals("approved", snapshotModel.get("comparatorApprovalStatus").asText());
        assertEquals("approved", snapshotModel.get("promotionStatus").asText());
        assertTrue(snapshotModel.get("evidenceIndexPath").asText().endsWith("SnapshotModel/verdict/evidence-index.json"));
        assertTrue(snapshotModel.get("promotionReceiptPath").asText().endsWith("SnapshotModel/verdict/promotion-receipt.json"));
        assertFalse(snapshotModel.get("promotionReceiptSha256").asText().isBlank());
    }

    @Test
    void ciSelectionReportEnumeratesEveryProofShapeAndSkipsCloudGroupsByDefault() throws Exception {
        var representativeTargets = new SemanticProofCatalog().buildRepresentativeTargets(proofLedger.load());
        Path selectionReport = new SemanticProofCiSelectionReport()
                .write(objectMapper, representativeTargets, tempDir.resolve("catalog/semantic-proof-ci-selection-report.json"));

        JsonNode root = objectMapper.readTree(selectionReport.toFile());
        JsonNode targets = root.get("targets");
        JsonNode rerunSequences = root.get("rerunSequences");
        assertFalse(targets.isEmpty());
        assertFalse(rerunSequences.isEmpty());

        Set<String> proofShapes = StreamSupport.stream(targets.spliterator(), false)
                .filter(row -> "proofShape".equals(row.get("targetType").asText()))
                .map(row -> row.get("proofShape").asText())
                .collect(Collectors.toSet());
        assertEquals(SemanticHardeningEvidenceContracts.PROOF_SHAPES, proofShapes);

        JsonNode generator = StreamSupport.stream(targets.spliterator(), false)
                .filter(row -> "proofShape".equals(row.get("targetType").asText()))
                .filter(row -> "generator".equals(row.get("proofShape").asText()))
                .findFirst()
                .orElseThrow();
        assertTrue(generator.get("selected").asBoolean());
        assertEquals("PASS", generator.get("status").asText());

        JsonNode gcpGolden = StreamSupport.stream(targets.spliterator(), false)
                .filter(row -> "proofShape".equals(row.get("targetType").asText()))
                .filter(row -> "gcp-golden".equals(row.get("proofShape").asText()))
                .findFirst()
                .orElseThrow();
        assertTrue(gcpGolden.get("skipped").asBoolean());
        assertEquals("GCP_PROOF_DISABLED", gcpGolden.get("skipReason").asText());

        JsonNode sameInputRerun = StreamSupport.stream(rerunSequences.spliterator(), false)
                .filter(row -> "same_input_rerun_after_pass".equals(row.get("sequenceKind").asText()))
                .filter(row -> row.get("targetId").asText().startsWith("FileIngestion:"))
                .findFirst()
                .orElseThrow();
        assertEquals("semantic-FileIngestion", sameInputRerun.get("scenarioGroupId").asText());
        assertEquals("FAIL_MISSING_RERUN_COVERAGE", sameInputRerun.get("status").asText());
        assertFalse(sameInputRerun.get("fixtureSha256").asText().isBlank());

        JsonNode gcpFullComposerRerun = StreamSupport.stream(rerunSequences.spliterator(), false)
                .filter(row -> "composer_rerun_same_logical_date".equals(row.get("sequenceKind").asText()))
                .filter(row -> "gcp-full".equals(row.get("targetId").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals("FAIL_MISSING_RERUN_COVERAGE", gcpFullComposerRerun.get("status").asText());
    }

    @Test
    void ciSelectionReportUnlocksBlockedSemanticDevRowsAfterApprovedPromotionReceiptsExist() throws Exception {
        Path buildRoot = tempDir.resolve("backend/build/e2e-semantic-hardening");
        seedPromotionArtifacts(buildRoot, "SnapshotModel");
        seedPromotionArtifacts(buildRoot, "AdvanceTimeDimension");

        var representativeTargets = new SemanticProofCatalog(buildRoot).buildRepresentativeTargets(proofLedger.load());
        Path selectionReport = new SemanticProofCiSelectionReport()
                .write(objectMapper, representativeTargets, tempDir.resolve("catalog/semantic-proof-ci-selection-report.json"));

        JsonNode targets = objectMapper.readTree(selectionReport.toFile()).get("targets");
        JsonNode blockedSemanticDev = StreamSupport.stream(targets.spliterator(), false)
                .filter(row -> "proofShape".equals(row.get("targetType").asText()))
                .filter(row -> "blocked-semantic-dev".equals(row.get("proofShape").asText()))
                .findFirst()
                .orElseThrow();
        assertTrue(blockedSemanticDev.get("selected").asBoolean());
        assertFalse(blockedSemanticDev.get("skipped").asBoolean());

        JsonNode snapshotModel = StreamSupport.stream(targets.spliterator(), false)
                .filter(row -> "blueprint".equals(row.get("targetType").asText()))
                .filter(row -> "SnapshotModel".equals(row.get("blueprintKey").asText()))
                .findFirst()
                .orElseThrow();
        assertTrue(snapshotModel.get("selected").asBoolean());
        assertFalse(snapshotModel.get("skipped").asBoolean());
        assertTrue(snapshotModel.get("evidenceIndexPath").asText().endsWith("SnapshotModel/verdict/evidence-index.json"));
    }

    @Test
    void ciSelectionReportEnumeratesRequiredRerunSequencesAndFailsClosedWithoutCoverageArtifacts() {
        var report = new SemanticProofCiSelectionReport().build(new SemanticProofCatalog().buildRepresentativeTargets(proofLedger.load()));

        assertFalse(report.rerunSequences().isEmpty());

        var sameInputRerun = report.rerunSequences().stream()
                .filter(row -> row.targetId().equals("GenericFilter:blocked-semantic-dev"))
                .filter(row -> row.sequenceKind().equals("same_input_rerun_after_pass"))
                .findFirst()
                .orElseThrow();
        assertEquals("FAIL_MISSING_RERUN_COVERAGE", sameInputRerun.status());
        assertEquals("semantic-GenericFilter", sameInputRerun.scenarioGroupId());

        var dqValidatorRow = report.targets().stream()
                .filter(row -> "blueprint".equals(row.targetType()))
                .filter(row -> "DQValidator".equals(row.blueprintKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("FAIL_MISSING_RERUN_COVERAGE", dqValidatorRow.status());
    }

    @Test
    void evidenceContractsSeparateLocalDockerGcpRuntimeAndPhaseZeroArtifacts() {
        assertTrue(Set.copyOf(SemanticHardeningEvidenceContracts.LOCAL_DOCKER_ARTIFACTS)
                .contains("minio-output-probe.json"));
        assertTrue(Set.copyOf(SemanticHardeningEvidenceContracts.LOCAL_DOCKER_ARTIFACTS)
                .contains("promotion-receipt.json"));
        assertTrue(Set.copyOf(SemanticHardeningEvidenceContracts.LOCAL_DOCKER_ARTIFACTS)
                .contains("rerun-idempotency-coverage.json"));
        assertFalse(SemanticHardeningEvidenceContracts.GCP_RUNTIME_ARTIFACTS
                .contains("minio-output-probe.json"));
        assertTrue(SemanticHardeningEvidenceContracts.GCP_RUNTIME_ARTIFACTS
                .contains("gcs-output-probe.json"));
        assertTrue(SemanticHardeningEvidenceContracts.GCP_SPARK_RUNTIME_ARTIFACTS
                .contains("dataproc-batch-state.json"));
        assertTrue(SemanticHardeningEvidenceContracts.GCP_RUNTIME_ARTIFACTS
                .contains("critique-packet.json"));
        assertTrue(SemanticHardeningEvidenceContracts.GCP_RUNTIME_ARTIFACTS
                .contains("critique-verdict.json"));
        assertTrue(SemanticHardeningEvidenceContracts.GCP_RUNTIME_ARTIFACTS
                .contains("promotion-receipt.json"));
        assertTrue(SemanticHardeningEvidenceContracts.GCP_RUNTIME_ARTIFACTS
                .contains("rerun-idempotency-coverage.json"));
        assertTrue(SemanticHardeningEvidenceContracts.PHASE_0_GCP_ENVIRONMENT_ARTIFACTS
                .contains("gcp-environment-smoke-verdict.json"));
        assertTrue(SemanticHardeningEvidenceContracts.PHASE_0_GCP_ENVIRONMENT_ARTIFACTS
                .contains("cleanup/composer-idle-policy.json"));
        assertTrue(SemanticHardeningEvidenceContracts.PHASE_0_GCP_ENVIRONMENT_ARTIFACTS
                .contains("cleanup/staging-cleanup.json"));
        assertEquals(10, SemanticHardeningEvidenceContracts.PROOF_SHAPES.size());
    }

    @Test
    void scenarioDslDefinesGcpRuntimeAdapterWithoutRemovingLocalAdapter() {
        assertEquals(ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE,
                ScenarioDsl.RuntimeAdapter.valueOf("LOCAL_AIRFLOW_BRIDGE"));
        assertEquals(ScenarioDsl.RuntimeAdapter.GCP_COMPOSER_DATAPROC_BRIDGE,
                ScenarioDsl.RuntimeAdapter.valueOf("GCP_COMPOSER_DATAPROC_BRIDGE"));
    }

    @Test
    void scaffoldWriterMaterializesPhaseZeroCatalogAndProgressArtifacts() throws Exception {
        var artifacts = new SemanticHardeningScaffoldWriter(objectMapper, proofLedger)
                .write(tempDir.resolve("e2e-semantic-hardening"));

        assertTrue(artifacts.denominatorReport().endsWith("semantic-proof-denominator-report.json"));
        assertTrue(artifacts.semanticProofCatalog().endsWith("semantic-proof-catalog.json"));
        assertTrue(artifacts.selectionReport().endsWith("semantic-proof-ci-selection-report.json"));
        assertTrue(artifacts.laneLedger().endsWith("semantic-hardening-lane-ledger.json"));
        assertTrue(artifacts.phase0Manifest().endsWith("phase0-required-artifacts.json"));

        JsonNode manifest = objectMapper.readTree(artifacts.phase0Manifest().toFile());
        assertFalse(manifest.get("cloudResourceCreationApproved").asBoolean());
        assertFalse(manifest.get("destructiveCloudActionsApproved").asBoolean());
        assertTrue(textArray(manifest.get("requiredArtifacts")).contains("gcp-environment-smoke-verdict.json"));
    }

    @Test
    void credentialBlockedReadinessReportQuarantinesOnlyCloudProofShapes() throws Exception {
        Path report = new GcpPhase0ReadinessReport(objectMapper)
                .writeCredentialBlockedReport(
                        tempDir.resolve("e2e-semantic-hardening/gcp-environment"),
                        "pulse-489421",
                        "Reauthentication failed. cannot prompt during non-interactive execution."
                );
        Path ledger = new SemanticHardeningLaneLedger()
                .writeCredentialBlockedLedger(
                        objectMapper,
                        tempDir.resolve("e2e-semantic-hardening/progress/semantic-hardening-lane-ledger.json"),
                        "NEEDS_CREDENTIAL_INPUT",
                        List.of("contract-adapter", "semantic-pack", "comparator", "semantic-oracle", "evidence-contract", "ledger-checks")
                );

        JsonNode readiness = objectMapper.readTree(report.toFile());
        assertEquals("pulse-proof-04261847", readiness.get("project").get("expectedProjectId").asText());
        assertEquals("FAIL_STALE_LOCAL_DEFAULT", readiness.get("project").get("verdict").asText());
        assertEquals("QUARANTINED", readiness.get("phase0Verdict").asText());

        JsonNode cloudLane = lane(objectMapper.readTree(ledger.toFile()).get("lanes"), "gcp-readiness-smoke");
        assertEquals("QUARANTINED", cloudLane.get("laneState").asText());
        assertEquals("CREDENTIAL", cloudLane.get("failureClass").asText());
        assertTrue(textArray(cloudLane.get("allowedContinuationLanes")).contains("contract-adapter"));
        assertTrue(textArray(cloudLane.get("blockedProofShapes")).contains("gcp-full"));
    }

    private void seedPromotionArtifacts(Path buildRoot, String blueprintKey) throws Exception {
        Path verdictRoot = buildRoot.resolve(blueprintKey).resolve("verdict");
        Files.createDirectories(verdictRoot);
        writeJson(verdictRoot.resolve("evidence-index.json"), Map.of(
                "blueprintKey", blueprintKey,
                "schemaVersion", "1"
        ));
        writeJson(verdictRoot.resolve("critique-verdict.json"), Map.of(
                "blueprintKey", blueprintKey,
                "verdict", "APPROVE",
                "promotionAllowed", true,
                "confirmedComparatorFitness", true
        ));
        writeJson(verdictRoot.resolve("promotion-receipt.json"), Map.of(
                "blueprintKey", blueprintKey,
                "verdict", "PROMOTED"
        ));
    }

    private void writeJson(Path path, Map<String, Object> payload) throws Exception {
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
    }

    private JsonNode lane(JsonNode lanes, String laneId) {
        return StreamSupport.stream(lanes.spliterator(), false)
                .filter(lane -> lane.get("lane").asText().equals(laneId))
                .findFirst()
                .orElseThrow();
    }

    private void assertRequiredLaneFields(JsonNode lane) {
        Set<String> fields = new HashSet<>();
        Iterator<String> fieldNames = lane.fieldNames();
        while (fieldNames.hasNext()) {
            fields.add(fieldNames.next());
        }
        assertTrue(fields.contains("lane"));
        assertTrue(fields.contains("laneState"));
        assertTrue(fields.contains("requiresGcp"));
        assertTrue(fields.contains("failureClass"));
        assertTrue(fields.contains("failureFingerprint"));
        assertTrue(fields.contains("failureHistory"));
        assertTrue(fields.contains("blockers"));
        assertTrue(fields.contains("taskIds"));
        assertTrue(fields.contains("prerequisiteLanes"));
        assertTrue(fields.contains("ownedPaths"));
        assertTrue(fields.contains("sharedPaths"));
        assertTrue(fields.contains("allowedContinuationLanes"));
        assertTrue(fields.contains("blockedProofShapes"));
        assertTrue(fields.contains("nextRetryAt"));
        assertTrue(fields.contains("retryCount"));
        assertTrue(fields.contains("retryCeiling"));
        assertTrue(fields.contains("evidenceRoot"));
        assertTrue(fields.contains("cleanupStatus"));
        assertTrue(fields.contains("terminalStopReason"));
        assertTrue(fields.contains("promotionPrivilegeStatus"));
    }

    private Set<String> textArray(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
    }
}
