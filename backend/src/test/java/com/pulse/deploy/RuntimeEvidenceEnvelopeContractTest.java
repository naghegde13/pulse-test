package com.pulse.deploy;

import com.pulse.deploy.evidence.EvidenceProofLevel;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.BigQueryEvidence;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.CleanupPolicy;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.ComposerEvidence;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.DataprocEvidence;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.EvidenceIndexEntry;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.OracleVerdictEvidence;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.OutputEntry;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.OutputProofEvidence;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.PromotionDecision;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope.TableProbe;
import com.pulse.deploy.evidence.RuntimeEvidenceService;
import com.pulse.deploy.model.DeploymentEvidence;
import com.pulse.deploy.repository.DeploymentEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PKT-0005 contract tests — validates the runtime evidence envelope,
 * proof level transitions, and negative constraints that prevent
 * static/local/preflight evidence from satisfying runtime output proof.
 */
class RuntimeEvidenceEnvelopeContractTest {

    @Nested
    @DisplayName("EvidenceProofLevel enum invariants")
    class ProofLevelInvariants {

        @Test
        @DisplayName("STATIC_PACKAGE, PREFLIGHT, LOCAL_SYNTHETIC are not runtime proof")
        void nonRuntimeLevelsAreNotRuntimeProof() {
            assertFalse(EvidenceProofLevel.STATIC_PACKAGE.isRuntimeProof());
            assertFalse(EvidenceProofLevel.PREFLIGHT.isRuntimeProof());
            assertFalse(EvidenceProofLevel.LOCAL_SYNTHETIC.isRuntimeProof());
        }

        @Test
        @DisplayName("LIVE_RUNTIME, ORACLE_VERDICT, PROMOTION_READINESS are runtime proof")
        void runtimeLevelsAreRuntimeProof() {
            assertTrue(EvidenceProofLevel.LIVE_RUNTIME.isRuntimeProof());
            assertTrue(EvidenceProofLevel.ORACLE_VERDICT.isRuntimeProof());
            assertTrue(EvidenceProofLevel.PROMOTION_READINESS.isRuntimeProof());
        }

        @Test
        @DisplayName("Only PROMOTION_READINESS is promotionReady")
        void onlyPromotionReadinessIsPromotionReady() {
            for (EvidenceProofLevel level : EvidenceProofLevel.values()) {
                if (level == EvidenceProofLevel.PROMOTION_READINESS) {
                    assertTrue(level.isPromotionReady());
                } else {
                    assertFalse(level.isPromotionReady(),
                            level + " should not be promotionReady");
                }
            }
        }

        @Test
        @DisplayName("isAtLeast enforces ordinal ordering")
        void isAtLeastUsesOrdinalOrdering() {
            assertTrue(EvidenceProofLevel.isAtLeast(
                    EvidenceProofLevel.LIVE_RUNTIME, EvidenceProofLevel.PREFLIGHT));
            assertFalse(EvidenceProofLevel.isAtLeast(
                    EvidenceProofLevel.PREFLIGHT, EvidenceProofLevel.LIVE_RUNTIME));
            assertTrue(EvidenceProofLevel.isAtLeast(
                    EvidenceProofLevel.LIVE_RUNTIME, EvidenceProofLevel.LIVE_RUNTIME));
        }

        @Test
        @DisplayName("hasRealRuntimeEvidence returns false for non-runtime levels")
        void hasRealRuntimeEvidenceContract() {
            assertFalse(EvidenceProofLevel.hasRealRuntimeEvidence(EvidenceProofLevel.STATIC_PACKAGE));
            assertFalse(EvidenceProofLevel.hasRealRuntimeEvidence(EvidenceProofLevel.PREFLIGHT));
            assertFalse(EvidenceProofLevel.hasRealRuntimeEvidence(EvidenceProofLevel.LOCAL_SYNTHETIC));
            assertTrue(EvidenceProofLevel.hasRealRuntimeEvidence(EvidenceProofLevel.LIVE_RUNTIME));
            assertTrue(EvidenceProofLevel.hasRealRuntimeEvidence(EvidenceProofLevel.ORACLE_VERDICT));
            assertTrue(EvidenceProofLevel.hasRealRuntimeEvidence(EvidenceProofLevel.PROMOTION_READINESS));
            assertFalse(EvidenceProofLevel.hasRealRuntimeEvidence(null));
        }

        @Test
        @DisplayName("Proof levels are ordered: STATIC < PREFLIGHT < LOCAL < LIVE < ORACLE < PROMOTION")
        void proofLevelOrdering() {
            EvidenceProofLevel[] expected = {
                    EvidenceProofLevel.STATIC_PACKAGE,
                    EvidenceProofLevel.PREFLIGHT,
                    EvidenceProofLevel.LOCAL_SYNTHETIC,
                    EvidenceProofLevel.LIVE_RUNTIME,
                    EvidenceProofLevel.ORACLE_VERDICT,
                    EvidenceProofLevel.PROMOTION_READINESS,
            };
            assertArrayEquals(expected, EvidenceProofLevel.values());
        }
    }

    @Nested
    @DisplayName("Envelope construction constraints")
    class EnvelopeConstructionConstraints {

        @Test
        @DisplayName("Cannot set runtimeProof=true with STATIC_PACKAGE proof level")
        void staticPackageCannotHaveRuntimeProof() {
            assertThrows(IllegalArgumentException.class, () ->
                    new RuntimeEvidenceEnvelope(
                            RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                            EvidenceProofLevel.STATIC_PACKAGE,
                            RuntimeEvidenceEnvelope.TYPE_STATIC_PACKAGE,
                            null, "pkg-1", "t1", null, null,
                            Instant.now(), "test", null,
                            true, // runtimeProof — ILLEGAL for STATIC_PACKAGE
                            false,
                            null, null, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("Cannot set runtimeProof=true with PREFLIGHT proof level")
        void preflightCannotHaveRuntimeProof() {
            assertThrows(IllegalArgumentException.class, () ->
                    new RuntimeEvidenceEnvelope(
                            RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                            EvidenceProofLevel.PREFLIGHT,
                            RuntimeEvidenceEnvelope.TYPE_PREFLIGHT,
                            "run-1", "pkg-1", "t1", "dev", "LOCAL",
                            Instant.now(), "test", null,
                            true, // runtimeProof — ILLEGAL for PREFLIGHT
                            false,
                            null, null, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("Cannot set runtimeProof=true with LOCAL_SYNTHETIC proof level")
        void localSyntheticCannotHaveRuntimeProof() {
            assertThrows(IllegalArgumentException.class, () ->
                    new RuntimeEvidenceEnvelope(
                            RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                            EvidenceProofLevel.LOCAL_SYNTHETIC,
                            RuntimeEvidenceEnvelope.TYPE_LOCAL_SYNTHETIC,
                            "run-1", "pkg-1", "t1", "dev", "LOCAL_MATERIALIZATION",
                            Instant.now(), "test", null,
                            true, // runtimeProof — ILLEGAL for LOCAL_SYNTHETIC
                            false,
                            null, null, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("Cannot set promotionReady=true with non-PROMOTION_READINESS level")
        void onlyPromotionReadinessCanBePromotionReady() {
            for (EvidenceProofLevel level : EvidenceProofLevel.values()) {
                if (level == EvidenceProofLevel.PROMOTION_READINESS) continue;
                assertThrows(IllegalArgumentException.class, () ->
                                new RuntimeEvidenceEnvelope(
                                        RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                                        level, "TEST", "run-1", "pkg-1", "t1", "dev", null,
                                        Instant.now(), "test", null,
                                        level.isRuntimeProof(), true, // promotionReady — ILLEGAL
                                        null, null, null, null, null, null, null, null),
                        "promotionReady=true should be rejected for " + level);
            }
        }

        @Test
        @DisplayName("LIVE_RUNTIME envelope can have runtimeProof=true")
        void liveRuntimeCanHaveRuntimeProof() {
            RuntimeEvidenceEnvelope envelope = new RuntimeEvidenceEnvelope(
                    RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                    EvidenceProofLevel.LIVE_RUNTIME,
                    RuntimeEvidenceEnvelope.TYPE_COMPOSER_RUN,
                    "run-1", "pkg-1", "t1", "dev", "GCP_COMPOSER_DATAPROC",
                    Instant.now(), "test", "corr-1",
                    true, false,
                    null, null, null, null, null, null, null, null);
            assertTrue(envelope.runtimeProof());
            assertFalse(envelope.promotionReady());
        }

        @Test
        @DisplayName("PROMOTION_READINESS envelope can have both runtimeProof and promotionReady")
        void promotionReadinessCanHaveBothFlags() {
            RuntimeEvidenceEnvelope envelope = new RuntimeEvidenceEnvelope(
                    RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                    EvidenceProofLevel.PROMOTION_READINESS,
                    RuntimeEvidenceEnvelope.TYPE_PROMOTION_READINESS,
                    "run-1", "pkg-1", "t1", "dev", "GCP_COMPOSER_DATAPROC",
                    Instant.now(), "test", "corr-1",
                    true, true,
                    null, null, null, null, null, null, null, null);
            assertTrue(envelope.runtimeProof());
            assertTrue(envelope.promotionReady());
        }
    }

    @Nested
    @DisplayName("Composer/Dataproc/BigQuery runtime evidence fields")
    class RuntimeEvidenceFields {

        @Test
        @DisplayName("ComposerEvidence carries DAG run identifiers and task states")
        void composerEvidenceContract() {
            ComposerEvidence composer = new ComposerEvidence(
                    "prod", "pipeline_daily_dag", "dag_run_2026_05_25T00_00_00",
                    Map.of("extract_task", "SUCCESS", "transform_task", "SUCCESS"),
                    List.of("https://console.cloud.google.com/logs/dag_run_123"),
                    Instant.parse("2026-05-25T12:00:00Z"));
            Map<String, Object> json = composer.toCanonicalJson();
            assertEquals("prod", json.get("environment"));
            assertEquals("pipeline_daily_dag", json.get("dagId"));
            assertEquals("dag_run_2026_05_25T00_00_00", json.get("dagRunId"));
            @SuppressWarnings("unchecked")
            Map<String, String> tasks = (Map<String, String>) json.get("taskStates");
            assertEquals("SUCCESS", tasks.get("extract_task"));
            assertEquals(1, ((List<?>) json.get("logLinks")).size());
        }

        @Test
        @DisplayName("DataprocEvidence carries batch ID and Spark job evidence")
        void dataprocEvidenceContract() {
            DataprocEvidence dataproc = new DataprocEvidence(
                    "batch-abc-123", "SUCCEEDED",
                    "gs://logs/driver.log", "gs://logs/app.log",
                    Map.of("stages", 4, "tasks", 128),
                    Instant.parse("2026-05-25T12:30:00Z"));
            Map<String, Object> json = dataproc.toCanonicalJson();
            assertEquals("batch-abc-123", json.get("batchId"));
            assertEquals("SUCCEEDED", json.get("batchStatus"));
            assertEquals("gs://logs/driver.log", json.get("driverLogLocation"));
        }

        @Test
        @DisplayName("BigQueryEvidence carries job IDs, target datasets/tables, and table probes")
        void bigqueryEvidenceContract() {
            TableProbe probe = new TableProbe(
                    "project.dataset.table", "bq://project.dataset.table",
                    50000L, "sha256:abc123", "VERIFIED",
                    Instant.parse("2026-05-25T12:45:00Z"));
            BigQueryEvidence bq = new BigQueryEvidence(
                    List.of("job_1", "job_2"),
                    List.of("my_dataset"),
                    List.of("my_dataset.output_table"),
                    Map.of("create_table_ddl", "DONE"),
                    Map.of("insert_query", "DONE"),
                    List.of(probe),
                    Instant.parse("2026-05-25T12:45:00Z"));
            Map<String, Object> json = bq.toCanonicalJson();
            assertEquals(List.of("job_1", "job_2"), json.get("jobIds"));
            assertEquals(List.of("my_dataset"), json.get("targetDatasets"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> probes = (List<Map<String, Object>>) json.get("tableProbes");
            assertEquals(1, probes.size());
            assertEquals(50000L, probes.get(0).get("rowCount"));
            assertEquals("VERIFIED", probes.get(0).get("probeStatus"));
        }
    }

    @Nested
    @DisplayName("Oracle verdict and evidence-index contract")
    class OracleVerdictContract {

        @Test
        @DisplayName("OracleVerdictEvidence carries semantic comparison and verdict")
        void oracleVerdictFields() {
            OracleVerdictEvidence oracle = new OracleVerdictEvidence(
                    "row_count_and_schema_match",
                    "Expected: 50000 rows, schema v2.1",
                    "Actual: 50000 rows, schema v2.1",
                    "PASS",
                    List.of("evidence/composer-run.json", "evidence/output-probe.json"),
                    Map.of("rowCountDelta", 0, "schemaDrift", false),
                    Instant.parse("2026-05-25T13:00:00Z"));
            Map<String, Object> json = oracle.toCanonicalJson();
            assertEquals("PASS", json.get("verdict"));
            assertEquals("row_count_and_schema_match", json.get("semanticComparison"));
            assertEquals(2, ((List<?>) json.get("evidenceIndexLinks")).size());
        }
    }

    @Nested
    @DisplayName("Cleanup policy and promotion-readiness decision")
    class CleanupAndPromotionContract {

        @Test
        @DisplayName("CleanupPolicy carries retention and status fields")
        void cleanupPolicyFields() {
            CleanupPolicy cleanup = new CleanupPolicy(
                    "RETAIN_UNTIL_PROMOTION", true, true, 30,
                    "SCHEDULED", Instant.parse("2026-06-25T00:00:00Z"));
            Map<String, Object> json = cleanup.toCanonicalJson();
            assertEquals("RETAIN_UNTIL_PROMOTION", json.get("policy"));
            assertEquals(true, json.get("retainArtifacts"));
            assertEquals(30, json.get("retentionDays"));
            assertEquals("SCHEDULED", json.get("cleanupStatus"));
        }

        @Test
        @DisplayName("PromotionDecision carries gate satisfaction and readiness")
        void promotionDecisionFields() {
            PromotionDecision decision = new PromotionDecision(
                    true, "All gates satisfied",
                    List.of("RUNTIME_PROVED", "APPROVAL_GATE", "ORACLE_VERDICT"),
                    List.of(),
                    "integration",
                    Instant.parse("2026-05-25T14:00:00Z"));
            Map<String, Object> json = decision.toCanonicalJson();
            assertTrue((Boolean) json.get("ready"));
            assertEquals(3, ((List<?>) json.get("satisfiedGates")).size());
            assertEquals(0, ((List<?>) json.get("unsatisfiedGates")).size());
        }
    }

    @Nested
    @DisplayName("RuntimeEvidenceService assembly")
    class ServiceAssembly {

        private DeploymentEvidenceRepository evidenceRepo;
        private RuntimeEvidenceService service;

        @BeforeEach
        void setUp() {
            evidenceRepo = mock(DeploymentEvidenceRepository.class);
            service = new RuntimeEvidenceService(evidenceRepo);
        }

        @Test
        @DisplayName("buildLocalSyntheticEnvelope produces LOCAL_SYNTHETIC with runtimeProof=false")
        void localSyntheticEnvelope() {
            RuntimeEvidenceEnvelope envelope = service.buildLocalSyntheticEnvelope(
                    "run-1", "pkg-1", "t1", "dev", "corr-1", List.of());
            assertEquals(EvidenceProofLevel.LOCAL_SYNTHETIC, envelope.proofLevel());
            assertEquals(RuntimeEvidenceEnvelope.TYPE_LOCAL_SYNTHETIC, envelope.evidenceType());
            assertFalse(envelope.runtimeProof());
            assertFalse(envelope.promotionReady());
            assertEquals("LOCAL_MATERIALIZATION", envelope.adapter());
        }

        @Test
        @DisplayName("buildStaticPackageEnvelope produces STATIC_PACKAGE with runtimeProof=false")
        void staticPackageEnvelope() {
            RuntimeEvidenceEnvelope envelope = service.buildStaticPackageEnvelope(
                    "pkg-1", "t1", "corr-1", List.of());
            assertEquals(EvidenceProofLevel.STATIC_PACKAGE, envelope.proofLevel());
            assertFalse(envelope.runtimeProof());
            assertFalse(envelope.promotionReady());
        }

        @Test
        @DisplayName("buildPreflightEnvelope produces PREFLIGHT with runtimeProof=false")
        void preflightEnvelope() {
            RuntimeEvidenceEnvelope envelope = service.buildPreflightEnvelope(
                    "run-1", "pkg-1", "t1", "dev", "LOCAL", "corr-1", List.of());
            assertEquals(EvidenceProofLevel.PREFLIGHT, envelope.proofLevel());
            assertFalse(envelope.runtimeProof());
            assertFalse(envelope.promotionReady());
        }

        @Test
        @DisplayName("buildLiveRuntimeEnvelope produces LIVE_RUNTIME with runtimeProof=true")
        void liveRuntimeEnvelope() {
            ComposerEvidence composer = new ComposerEvidence(
                    "prod", "dag_1", "run_1", Map.of(), List.of(), Instant.now());
            RuntimeEvidenceEnvelope envelope = service.buildLiveRuntimeEnvelope(
                    "run-1", "pkg-1", "t1", "prod", "GCP_COMPOSER_DATAPROC",
                    "corr-1", composer, null, null, null, List.of());
            assertEquals(EvidenceProofLevel.LIVE_RUNTIME, envelope.proofLevel());
            assertTrue(envelope.runtimeProof());
            assertFalse(envelope.promotionReady());
            assertNotNull(envelope.composer());
        }

        @Test
        @DisplayName("buildOracleVerdictEnvelope produces ORACLE_VERDICT with runtimeProof=true")
        void oracleVerdictEnvelope() {
            OracleVerdictEvidence oracle = new OracleVerdictEvidence(
                    "match", "expected", "actual", "PASS", List.of(), Map.of(), Instant.now());
            RuntimeEvidenceEnvelope envelope = service.buildOracleVerdictEnvelope(
                    "run-1", "pkg-1", "t1", "prod", "GCP_COMPOSER_DATAPROC",
                    "corr-1", oracle, List.of());
            assertEquals(EvidenceProofLevel.ORACLE_VERDICT, envelope.proofLevel());
            assertTrue(envelope.runtimeProof());
            assertFalse(envelope.promotionReady());
        }

        @Test
        @DisplayName("buildPromotionReadinessEnvelope produces PROMOTION_READINESS with both flags true")
        void promotionReadinessEnvelope() {
            CleanupPolicy cleanup = new CleanupPolicy(
                    "RETAIN", true, true, 30, "PENDING", null);
            PromotionDecision decision = new PromotionDecision(
                    true, "ready", List.of("GATE1"), List.of(), "integration", Instant.now());
            RuntimeEvidenceEnvelope envelope = service.buildPromotionReadinessEnvelope(
                    "run-1", "pkg-1", "t1", "integration", "GCP_COMPOSER_DATAPROC",
                    "corr-1", cleanup, decision, List.of());
            assertEquals(EvidenceProofLevel.PROMOTION_READINESS, envelope.proofLevel());
            assertTrue(envelope.runtimeProof());
            assertTrue(envelope.promotionReady());
        }

        @Test
        @DisplayName("assembleForRun with local adapter returns LOCAL_SYNTHETIC")
        void assembleForLocalRun() {
            DeploymentEvidence preflight = makeEvidence("PREFLIGHT_RESULT");
            DeploymentEvidence materialization = makeEvidence("MATERIALIZATION_MANIFEST");
            DeploymentEvidence runtimeStatus = makeEvidence("RUNTIME_STATUS");
            when(evidenceRepo.findByDeploymentRunIdOrderByCreatedAtAsc("run-1"))
                    .thenReturn(List.of(preflight, materialization, runtimeStatus));

            RuntimeEvidenceEnvelope envelope = service.assembleForRun(
                    "run-1", "dep-1", "pkg-1", "t1", "dev", "LOCAL_MATERIALIZATION");
            assertEquals(EvidenceProofLevel.LOCAL_SYNTHETIC, envelope.proofLevel());
            assertFalse(envelope.runtimeProof(),
                    "Local adapter runs must not produce runtime proof");
            assertFalse(envelope.promotionReady());
            assertEquals(3, envelope.evidenceIndex().size());
        }

        @Test
        @DisplayName("assembleForRun with GCP adapter and runtime status returns LIVE_RUNTIME")
        void assembleForGcpRun() {
            DeploymentEvidence preflight = makeEvidence("PREFLIGHT_RESULT");
            DeploymentEvidence runtimeStatus = makeEvidence("RUNTIME_STATUS");
            when(evidenceRepo.findByDeploymentRunIdOrderByCreatedAtAsc("run-1"))
                    .thenReturn(List.of(preflight, runtimeStatus));

            RuntimeEvidenceEnvelope envelope = service.assembleForRun(
                    "run-1", "dep-1", "pkg-1", "t1", "prod", "GCP_COMPOSER_DATAPROC");
            assertEquals(EvidenceProofLevel.LIVE_RUNTIME, envelope.proofLevel());
            assertTrue(envelope.runtimeProof());
        }

        private DeploymentEvidence makeEvidence(String type) {
            DeploymentEvidence ev = new DeploymentEvidence();
            ev.setId("ev-" + type.toLowerCase());
            ev.setArtifactId("artifact-" + type.toLowerCase());
            ev.setType(type);
            ev.setPath("test/" + type.toLowerCase() + ".json");
            ev.setSha256("abc123");
            ev.setCorrelationId("corr-1");
            // BaseEntity.createdAt is set by @PrePersist; in mocked repos
            // the entity won't have been persisted, so getCreatedAt() may
            // be null. The EvidenceIndexEntry handles null gracefully.
            return ev;
        }
    }

    @Nested
    @DisplayName("Canonical JSON serialization")
    class CanonicalJsonSerialization {

        @Test
        @DisplayName("Envelope toCanonicalJson includes proofLevel and runtimeProof fields")
        void envelopeJsonIncludesProofFields() {
            RuntimeEvidenceEnvelope envelope = new RuntimeEvidenceEnvelope(
                    RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                    EvidenceProofLevel.LOCAL_SYNTHETIC,
                    RuntimeEvidenceEnvelope.TYPE_LOCAL_SYNTHETIC,
                    "run-1", "pkg-1", "t1", "dev", "LOCAL_MATERIALIZATION",
                    Instant.parse("2026-05-25T10:00:00Z"), "test", "corr-1",
                    false, false,
                    null, null, null, null, null, null, null,
                    List.of(new EvidenceIndexEntry(
                            "art-1", "PREFLIGHT_RESULT", EvidenceProofLevel.PREFLIGHT,
                            "test/preflight.json", "hash1",
                            Instant.parse("2026-05-25T10:00:00Z"))));
            Map<String, Object> json = envelope.toCanonicalJson();
            assertEquals("runtime-evidence-envelope.v1", json.get("schemaVersion"));
            assertEquals("LOCAL_SYNTHETIC", json.get("proofLevel"));
            assertEquals("LOCAL_SYNTHETIC_RUN", json.get("evidenceType"));
            assertEquals(false, json.get("runtimeProof"));
            assertEquals(false, json.get("promotionReady"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> index = (List<Map<String, Object>>) json.get("evidenceIndex");
            assertEquals(1, index.size());
            assertEquals("PREFLIGHT", index.get(0).get("proofLevel"));
        }

        @Test
        @DisplayName("Schema version is stable at runtime-evidence-envelope.v1")
        void schemaVersionIsStable() {
            assertEquals("runtime-evidence-envelope.v1", RuntimeEvidenceEnvelope.SCHEMA_VERSION);
        }
    }

    @Nested
    @DisplayName("Negative evidence: local/static/preflight cannot satisfy runtime proof")
    class NegativeEvidenceConstraints {

        @Test
        @DisplayName("Sample local evidence envelope has proofLevel=LOCAL_SYNTHETIC and runtimeProof=false")
        void sampleLocalEvidenceIsNotRuntimeProof() {
            RuntimeEvidenceEnvelope localEnvelope = new RuntimeEvidenceEnvelope(
                    RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                    EvidenceProofLevel.LOCAL_SYNTHETIC,
                    RuntimeEvidenceEnvelope.TYPE_LOCAL_SYNTHETIC,
                    "run-local-1", "pkg-1", "t1", "dev", "LOCAL_MATERIALIZATION",
                    Instant.now(), "LocalDeploymentTargetAdapter", "corr-1",
                    false, false,
                    null, null, null, null, null, null, null, List.of());
            assertFalse(localEnvelope.runtimeProof(),
                    "Local synthetic envelope must not claim runtime proof");
            assertFalse(localEnvelope.promotionReady(),
                    "Local synthetic envelope must not claim promotion ready");
            assertEquals(EvidenceProofLevel.LOCAL_SYNTHETIC, localEnvelope.proofLevel());
            assertFalse(localEnvelope.proofLevel().isRuntimeProof());
        }

        @Test
        @DisplayName("Static package evidence cannot mark runtimeProof=true")
        void staticPackageCannotBeRuntimeProof() {
            RuntimeEvidenceEnvelope staticEnvelope = new RuntimeEvidenceEnvelope(
                    RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                    EvidenceProofLevel.STATIC_PACKAGE,
                    RuntimeEvidenceEnvelope.TYPE_STATIC_PACKAGE,
                    null, "pkg-1", "t1", null, null,
                    Instant.now(), "PackageService", null,
                    false, false,
                    null, null, null, null, null, null, null, List.of());
            assertFalse(staticEnvelope.runtimeProof());
            assertFalse(staticEnvelope.promotionReady());
            // Attempting to force runtimeProof=true throws
            assertThrows(IllegalArgumentException.class, () ->
                    new RuntimeEvidenceEnvelope(
                            RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                            EvidenceProofLevel.STATIC_PACKAGE,
                            RuntimeEvidenceEnvelope.TYPE_STATIC_PACKAGE,
                            null, "pkg-1", "t1", null, null,
                            Instant.now(), "PackageService", null,
                            true, false,
                            null, null, null, null, null, null, null, List.of()));
        }

        @Test
        @DisplayName("Preflight evidence cannot mark promotionReady=true")
        void preflightCannotBePromotionReady() {
            assertThrows(IllegalArgumentException.class, () ->
                    new RuntimeEvidenceEnvelope(
                            RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                            EvidenceProofLevel.PREFLIGHT,
                            RuntimeEvidenceEnvelope.TYPE_PREFLIGHT,
                            "run-1", "pkg-1", "t1", "dev", "LOCAL",
                            Instant.now(), "test", null,
                            false, true,
                            null, null, null, null, null, null, null, List.of()));
        }

        @Test
        @DisplayName("LIVE_RUNTIME cannot mark promotionReady=true")
        void liveRuntimeCannotBePromotionReady() {
            assertThrows(IllegalArgumentException.class, () ->
                    new RuntimeEvidenceEnvelope(
                            RuntimeEvidenceEnvelope.SCHEMA_VERSION,
                            EvidenceProofLevel.LIVE_RUNTIME,
                            RuntimeEvidenceEnvelope.TYPE_COMPOSER_RUN,
                            "run-1", "pkg-1", "t1", "prod", "GCP",
                            Instant.now(), "test", null,
                            true, true, // promotionReady — ILLEGAL for LIVE_RUNTIME
                            null, null, null, null, null, null, null, List.of()));
        }
    }
}
