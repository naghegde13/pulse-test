package com.pulse.e2e.scenarios;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.contract.ScenarioDsl;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class LoanMasterScenarioFamilyCatalog {

    private static final String FIXTURE_MANIFEST = "e2e/fixtures/loan_master/fixture-manifest.json";
    private static final String DATA_ORACLE = "e2e/oracle/loan_master/data-oracle.json";
    private static final String LOCAL_DOCKER_FEASIBILITY = "DOCKER_COMPOSE_LOCAL";
    private static final Set<String> LOCAL_DOCKER_RUNTIME_CAPABILITIES = Set.of(
            "airflow",
            "spark",
            "dbt",
            "gx",
            "minio",
            "postgres"
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final ObjectMapper PLAIN_OBJECT_MAPPER = new ObjectMapper();
    private final LoanMasterRuntimeProofLedger proofLedger = new LoanMasterRuntimeProofLedger();

    public ScenarioFamilyCatalog build(List<ActiveBlueprintCatalogRecord> activeBlueprints,
                                       Set<String> fixtureDerivativeIds) {
        Map<String, ActiveBlueprintCatalogRecord> byKey = activeBlueprints.stream()
                .collect(Collectors.toMap(
                        ActiveBlueprintCatalogRecord::blueprintKey,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        Set<String> normalizedDerivativeIds = Set.copyOf(fixtureDerivativeIds);
        Map<String, Map<String, Object>> derivativeOracleOverrides = loadDerivativeOracleOverrides();
        Map<String, LoanMasterRuntimeProofLedger.ProofLedgerEntry> proofEntriesByBlueprintKey = proofLedger.loadByBlueprintKey();
        List<ScenarioFamilySpec> scenarios = new ArrayList<>();
        int index = 1;
        for (ScenarioTemplate template : templates()) {
            ActiveBlueprintCatalogRecord blueprint = byKey.get(template.representativeBlueprintKey());
            if (blueprint == null) {
                throw new IllegalArgumentException("Missing active blueprint for scenario template: "
                        + template.representativeBlueprintKey());
            }
            if (!template.blueprintFamily().equals(blueprint.family())) {
                throw new IllegalArgumentException("Blueprint family mismatch for "
                        + template.representativeBlueprintKey()
                        + ": expected "
                        + template.blueprintFamily()
                        + " but found "
                        + blueprint.family());
            }
            if (!normalizedDerivativeIds.contains(template.fixtureDerivativeId())) {
                throw new IllegalArgumentException("Unknown fixture derivative id: " + template.fixtureDerivativeId());
            }
            Map<String, Object> oracleOverrides = derivativeOracleOverrides.get(template.fixtureDerivativeId());
            if (oracleOverrides == null) {
                throw new IllegalArgumentException("Missing derivative oracle overrides for " + template.fixtureDerivativeId());
            }
            LoanMasterRuntimeProofLedger.ProofLedgerEntry proofEntry = proofEntriesByBlueprintKey.get(template.representativeBlueprintKey());
            if (proofEntry == null) {
                throw new IllegalArgumentException("Missing hard-proof ledger entry for " + template.representativeBlueprintKey());
            }

            ScenarioDsl.ScenarioDefinition scenario = toScenarioDefinition(template, index, oracleOverrides, proofEntry);
            scenarios.add(new ScenarioFamilySpec(
                    template.scenarioId(),
                    template.displayName(),
                    template.blueprintFamily(),
                    template.representativeBlueprintKey(),
                    template.fixtureDerivativeId(),
                    template.storageBackend(),
                    template.lakeLayer(),
                    template.orchestrationMode(),
                    template.requiredRuntimeCapabilities(),
                    template.localExecutionFeasibility(),
                    scenario
            ));
            index++;
        }

        return new ScenarioFamilyCatalog(List.copyOf(scenarios));
    }

    private ScenarioDsl.ScenarioDefinition toScenarioDefinition(ScenarioTemplate template,
                                                                int index,
                                                                Map<String, Object> oracleOverrides,
                                                                LoanMasterRuntimeProofLedger.ProofLedgerEntry proofEntry) {
        RuntimePromotionDecision promotionDecision = deriveRuntimePromotion(template, proofEntry);
        ScenarioDsl.ProofMode effectiveProofMode = promotionDecision.effectiveProofMode();
        List<String> featureTags = new ArrayList<>();
        featureTags.add("phase3");
        featureTags.add("loan_master");
        featureTags.add("scenario_family");
        featureTags.add(template.blueprintFamily().toLowerCase(Locale.ROOT));
        featureTags.add(template.fixtureDerivativeId());
        featureTags.add(effectiveProofMode.name().toLowerCase(Locale.ROOT));
        featureTags.add(template.storageBackend().toLowerCase(Locale.ROOT));
        featureTags.add(template.orchestrationMode().toLowerCase(Locale.ROOT));
        featureTags.add(template.localExecutionFeasibility().toLowerCase(Locale.ROOT));

        Map<String, Object> fixtureRefs = new LinkedHashMap<>();
        fixtureRefs.put("fixture_manifest", FIXTURE_MANIFEST);
        fixtureRefs.put("data_oracle", DATA_ORACLE);
        fixtureRefs.put("data_oracle_derivative_id", template.fixtureDerivativeId());
        fixtureRefs.put("data_oracle_overrides", oracleOverrides);
        fixtureRefs.put("fixture_derivative_id", template.fixtureDerivativeId());
        fixtureRefs.put("blueprint_family", template.blueprintFamily());
        fixtureRefs.put("representative_blueprint_key", template.representativeBlueprintKey());
        fixtureRefs.put("storage_backend", template.storageBackend());
        fixtureRefs.put("lake_layer", template.lakeLayer());
        fixtureRefs.put("orchestration_mode", template.orchestrationMode());
        fixtureRefs.put("required_runtime_capabilities", template.requiredRuntimeCapabilities());
        fixtureRefs.put("local_execution_feasibility", template.localExecutionFeasibility());
        fixtureRefs.put("runtime_promotion_status", promotionDecision.status());
        fixtureRefs.put("runtime_promotion_source_proof_mode", template.proofMode().name());
        fixtureRefs.put("hard_proof_status", proofEntry.hardProofStatus());
        fixtureRefs.put("proof_ledger_source", LoanMasterRuntimeProofLedger.RESOURCE_PATH);
        if (proofEntry.proofTest() != null) {
            fixtureRefs.put("hard_proof_test", proofEntry.proofTest());
        }
        if (proofEntry.notes() != null) {
            fixtureRefs.put("hard_proof_notes", proofEntry.notes());
        }
        fixtureRefs.put("hard_proof_artifacts", hardProofArtifacts(proofEntry));
        if (promotionDecision.blockerReason() != null) {
            fixtureRefs.put("runtime_promotion_blocker", promotionDecision.blockerReason());
        }
        fixtureRefs.put("scenario_index", index);

        return new ScenarioDsl.ScenarioDefinition(
                template.scenarioId(),
                template.displayName(),
                effectiveProofMode,
                ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE,
                featureTags,
                new ScenarioDsl.BuilderPlan(
                        "tenant-e2e-" + template.scenarioId(),
                        "servicing",
                        "loan_master",
                        template.executionBlueprintKeys(),
                        "loan_master"
                ),
                evidenceExpectation(effectiveProofMode, template.representativeBlueprintKey()),
                fixtureRefs
        );
    }

    private RuntimePromotionDecision deriveRuntimePromotion(ScenarioTemplate template,
                                                            LoanMasterRuntimeProofLedger.ProofLedgerEntry proofEntry) {
        if (!LOCAL_DOCKER_FEASIBILITY.equals(template.localExecutionFeasibility())) {
            return new RuntimePromotionDecision(
                    fallbackProofMode(template),
                    "blocked",
                    "Scenario is not runnable on the local docker-compose runtime."
            );
        }
        Set<String> missingLocalCapabilities = template.requiredRuntimeCapabilities().stream()
                .filter(capability -> !LOCAL_DOCKER_RUNTIME_CAPABILITIES.contains(capability))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!missingLocalCapabilities.isEmpty()) {
            return new RuntimePromotionDecision(
                    fallbackProofMode(template),
                    "blocked",
                    "Local docker-compose runtime does not provide required capabilities: "
                            + String.join(", ", missingLocalCapabilities)
            );
        }
        if ("PASS".equals(proofEntry.hardProofStatus())) {
            return new RuntimePromotionDecision(ScenarioDsl.ProofMode.LIVE_RUNTIME, "hard_proven_live_runtime", null);
        }
        if ("BLOCKED".equals(proofEntry.hardProofStatus())) {
            return new RuntimePromotionDecision(
                    fallbackProofMode(template),
                    "blocked",
                    proofEntry.blockerReason()
            );
        }
        return new RuntimePromotionDecision(
                fallbackProofMode(template),
                "pending_runtime_proof",
                null
        );
    }

    private ScenarioDsl.ProofMode fallbackProofMode(ScenarioTemplate template) {
        return template.proofMode() == ScenarioDsl.ProofMode.ARTIFACT_ONLY
                ? ScenarioDsl.ProofMode.ARTIFACT_ONLY
                : ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY;
    }

    private List<Map<String, Object>> hardProofArtifacts(LoanMasterRuntimeProofLedger.ProofLedgerEntry proofEntry) {
        if (!"PASS".equals(proofEntry.hardProofStatus())) {
            return List.of();
        }
        List<Map<String, Object>> artifacts = new ArrayList<>();
        if (proofEntry.outputProbeArtifactType() != null && proofEntry.outputProbePathPattern() != null) {
            artifacts.add(Map.of(
                    "artifact_role", "output_probe",
                    "artifact_type", proofEntry.outputProbeArtifactType(),
                    "path_pattern", proofEntry.outputProbePathPattern(),
                    "producer_test", proofEntry.proofTest()
            ));
        }
        if (proofEntry.oracleArtifactType() != null && proofEntry.oracleArtifactPathPattern() != null) {
            artifacts.add(Map.of(
                    "artifact_role", "oracle_comparison",
                    "artifact_type", proofEntry.oracleArtifactType(),
                    "path_pattern", proofEntry.oracleArtifactPathPattern(),
                    "producer_test", proofEntry.proofTest(),
                    "required_verdict", proofEntry.requiredVerdict()
            ));
        }
        return List.copyOf(artifacts);
    }

    private Map<String, Map<String, Object>> loadDerivativeOracleOverrides() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE_MANIFEST)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + FIXTURE_MANIFEST);
            }
            Map<String, Object> payload = PLAIN_OBJECT_MAPPER.readValue(in, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> derivatives = (List<Map<String, Object>>) payload.get("derivatives");
            if (derivatives == null) {
                throw new IllegalStateException("fixture-manifest.json missing derivatives array");
            }
            return derivatives.stream()
                    .collect(Collectors.toMap(
                            derivative -> String.valueOf(derivative.get("derivative_id")),
                            this::toOracleOverrideMap,
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load derivative oracle overrides", e);
        }
    }

    private Map<String, Object> toOracleOverrideMap(Map<String, Object> derivative) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("derivative_id", derivative.get("derivative_id"));
        copyIfPresent(derivative, overrides, "output_file");
        copyIfPresent(derivative, overrides, "row_count");
        copyIfPresent(derivative, overrides, "column_count");
        copyIfPresent(derivative, overrides, "canonical_csv_sha256");
        copyIfPresent(derivative, overrides, "schema_signature");
        copyIfPresent(derivative, overrides, "required_field_nulls");
        copyIfPresent(derivative, overrides, "business_keys");
        copyIfPresent(derivative, overrides, "partition_expectations");
        copyIfPresent(derivative, overrides, "aggregate_expectations", "aggregates");
        copyIfPresent(derivative, overrides, "dq_expectations", "dq_counts");
        copyIfPresent(derivative, overrides, "pii_assertions", "masked_pii_assertions");
        Object schema = derivative.get("schema");
        if (schema instanceof Map<?, ?> schemaMap) {
            copyIfPresent(schemaMap, overrides, "column_order_sha256");
            overrides.put("schema", schemaMap);
        }
        return Map.copyOf(overrides);
    }

    private void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String sourceKey) {
        copyIfPresent(source, target, sourceKey, sourceKey);
    }

    private void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    private ScenarioDsl.EvidenceExpectation evidenceExpectation(ScenarioDsl.ProofMode proofMode, String representativeBlueprintKey) {
        return switch (proofMode) {
            case ARTIFACT_ONLY -> new ScenarioDsl.EvidenceExpectation(
                    List.of("api-build-summary.json", "scenario-catalog.json", "scenario-coverage-plan.json",
                            "coverage.json", "verdict.json", "evidence-index.json"),
                    List.of("API_BUILD_SUMMARY", "SCENARIO_CATALOG", "SCENARIO_COVERAGE_PLAN",
                            "COVERAGE", "VERDICT", "EVIDENCE_INDEX"),
                    "verdict.json",
                    List.of()
            );
            case STATIC_DEPLOYABILITY -> new ScenarioDsl.EvidenceExpectation(
                    List.of("api-build-summary.json", "scenario-catalog.json", "scenario-coverage-plan.json",
                            "coverage.json", "verdict.json", "evidence-index.json"),
                    List.of("API_BUILD_SUMMARY", "SCENARIO_CATALOG", "SCENARIO_COVERAGE_PLAN",
                            "COVERAGE", "VERDICT", "EVIDENCE_INDEX"),
                    "verdict.json",
                    List.of()
            );
            case LIVE_RUNTIME -> liveRuntimeEvidenceExpectation(representativeBlueprintKey);
        };
    }

    private ScenarioDsl.EvidenceExpectation liveRuntimeEvidenceExpectation(String representativeBlueprintKey) {
        return new ScenarioDsl.EvidenceExpectation(
                List.of("dag-state.json", "task-state.json", "minio-output-probe.json", "data-oracle-comparison.json",
                        "scenario-catalog.json", "scenario-coverage-plan.json", "coverage.json", "verdict.json",
                        "evidence-index.json"),
                List.of("AIRFLOW_DAG_STATE", "AIRFLOW_TASK_STATE", "SCENARIO_CATALOG",
                        "SCENARIO_COVERAGE_PLAN", "COVERAGE", "VERDICT", "EVIDENCE_INDEX",
                        "MINIO_OUTPUT_PROBE", "DATA_ORACLE_COMPARISON"),
                "verdict.json",
                List.of("build", "runtime", "data")
        );
    }

    private List<ScenarioTemplate> templates() {
        return List.of(
                new ScenarioTemplate(
                        "ingestion-file-current-live-runtime",
                        "Ingestion — File current loans live runtime",
                        "INGESTION",
                        "FileIngestion",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "current_loans",
                        "DPC",
                        "bronze",
                        "manual",
                        List.of("airflow", "spark", "dbt", "gx", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "GenericFilter", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "ingestion-backfill-delinquent-static-deployability",
                        "Ingestion — Bulk backfill delinquent loans static deployability",
                        "INGESTION",
                        "BulkBackfill",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "delinquent_loans",
                        "DPC",
                        "bronze",
                        "cron",
                        List.of("airflow", "spark", "dbt", "gx", "postgres", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("BulkBackfill", "GenericFilter", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "ingestion-snapshot-full-static-deployability",
                        "Ingestion — Postgres snapshot loan master static deployability",
                        "INGESTION",
                        "SnapshotIngestion",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "current_loans",
                        "POSTGRES",
                        "bronze",
                        "cron",
                        List.of("airflow", "spark", "dbt", "gx", "postgres", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("SnapshotIngestion", "GenericFilter", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "transform-filter-current-live-runtime",
                        "Transform — Generic filter current loans live runtime",
                        "TRANSFORM",
                        "GenericFilter",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "current_loans",
                        "DPC",
                        "silver",
                        "manual",
                        List.of("airflow", "spark", "dbt", "gx", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "GenericFilter", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "transform-schema-risk-band-static-deployability",
                        "Transform — Schema normalization risk-band live runtime",
                        "TRANSFORM",
                        "SchemaNormalization",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "schema_add_servicing_risk_band",
                        "DPC",
                        "silver",
                        "cron",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "SchemaNormalization", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "transform-bronze-cleaning-full-static-deployability",
                        "Transform — bronze-to-silver cleaning static deployability",
                        "TRANSFORM",
                        "BronzeToSilverCleaning",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "current_loans",
                        "DPC",
                        "silver",
                        "manual",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "BronzeToSilverCleaning", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "transform-generic-aggregate-state-static-deployability",
                        "Transform — aggregate loan balances by state static deployability",
                        "TRANSFORM",
                        "GenericAggregate",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "investor_state_partitions",
                        "DPC",
                        "gold",
                        "cron",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "GenericAggregate", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "transform-generic-join-investor-state-static-deployability",
                        "Transform — join investor and state attributes static deployability",
                        "TRANSFORM",
                        "GenericJoin",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "investor_state_partitions",
                        "DPC",
                        "silver",
                        "manual",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "GenericJoin", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "transform-generic-router-investor-state-static-deployability",
                        "Transform — route investor/state partitions live runtime",
                        "TRANSFORM",
                        "GenericRouter",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "investor_state_partitions",
                        "DPC",
                        "silver",
                        "event",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "GenericRouter", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "transform-json-flatten-servicing-static-deployability",
                        "Transform — flatten servicing JSON attributes static deployability",
                        "TRANSFORM",
                        "JsonFlatten",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "schema_add_servicing_risk_band",
                        "DPC",
                        "silver",
                        "manual",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "JsonFlatten", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "transform-json-struct-borrower-static-deployability",
                        "Transform — build borrower JSON struct static deployability",
                        "TRANSFORM",
                        "JsonStruct",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "schema_add_servicing_risk_band",
                        "DPC",
                        "silver",
                        "manual",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "JsonStruct", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "transform-pii-masking-borrower-static-deployability",
                        "Transform — borrower PII masking live runtime",
                        "TRANSFORM",
                        "PIIMasking",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "current_loans",
                        "DPC",
                        "silver",
                        "manual",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "PIIMasking", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "transform-dedupe-merge-current-static-deployability",
                        "Transform — dedupe and merge current loans static deployability",
                        "TRANSFORM",
                        "DedupeAndMerge",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "current_loans",
                        "DPC",
                        "silver",
                        "cron",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "DedupeAndMerge", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "modeling-snapshot-late-arriving-artifact-only",
                        "Modeling — Snapshot late-arriving artifact proof",
                        "MODELING",
                        "SnapshotModel",
                        ScenarioDsl.ProofMode.ARTIFACT_ONLY,
                        "late_arriving_boarding",
                        "DPC",
                        "gold",
                        "cron",
                        List.of("dbt"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "GenericFilter", "SnapshotModel", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "modeling-aggregate-materialization-state-static-deployability",
                        "Modeling — aggregate materialization by state static deployability",
                        "MODELING",
                        "AggregateMaterialization",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "investor_state_partitions",
                        "DPC",
                        "gold",
                        "cron",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "AggregateMaterialization", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "modeling-feature-table-current-static-deployability",
                        "Modeling — current-loan feature table static deployability",
                        "MODELING",
                        "FeatureTablePublish",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "current_loans",
                        "DPC",
                        "gold",
                        "cron",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "FeatureTablePublish", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "modeling-incremental-merge-late-arriving-static-deployability",
                        "Modeling — late-arriving incremental merge live runtime",
                        "MODELING",
                        "IncrementalMerge",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "late_arriving_boarding",
                        "DPC",
                        "gold",
                        "cron",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "IncrementalMerge", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "modeling-reference-data-state-static-deployability",
                        "Modeling — state reference data publish static deployability",
                        "MODELING",
                        "ReferenceDataPublish",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "investor_state_partitions",
                        "DPC",
                        "gold",
                        "manual",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "ReferenceDataPublish", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "reuse-capable-fact-delinquent-static-deployability",
                        "Reuse-capable — Fact build delinquent loans static deployability",
                        "REUSE_CAPABLE",
                        "FactBuild",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "delinquent_loans",
                        "DPC",
                        "gold",
                        "manual",
                        List.of("dbt"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "GenericFilter", "FactBuild", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "reuse-capable-scd2-current-static-deployability",
                        "Reuse-capable — SCD2 loan dimension static deployability",
                        "REUSE_CAPABLE",
                        "SCD2Dimension",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "investor_state_partitions",
                        "DPC",
                        "gold",
                        "cron",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "BronzeToSilverCleaning", "SCD2Dimension", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "reuse-capable-wide-mart-delinquent-static-deployability",
                        "Reuse-capable — wide denormalized mart static deployability",
                        "REUSE_CAPABLE",
                        "WideDenormalizedMart",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "delinquent_loans",
                        "DPC",
                        "gold",
                        "manual",
                        List.of("spark", "dbt", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "WideDenormalizedMart", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "data-quality-required-fields-live-runtime",
                        "Data quality — required fields live runtime",
                        "DATA_QUALITY",
                        "DQValidator",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "investor_state_partitions",
                        "DPC",
                        "silver",
                        "manual",
                        List.of("airflow", "spark", "gx", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "GenericFilter", "DQValidator", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "data-quality-anomaly-current-artifact-only",
                        "Data quality — anomaly detection current loans live runtime",
                        "DATA_QUALITY",
                        "AnomalyDetection",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "current_loans",
                        "DPC",
                        "gold",
                        "cron",
                        List.of("gx"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "GenericFilter", "AnomalyDetection", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "data-quality-freshness-current-static-deployability",
                        "Data quality — current-loan freshness live runtime",
                        "DATA_QUALITY",
                        "FreshnessChecks",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "current_loans",
                        "DPC",
                        "silver",
                        "cron",
                        List.of("spark", "gx", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "FreshnessChecks", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "data-quality-schema-drift-full-static-deployability",
                        "Data quality — schema drift detection live runtime",
                        "DATA_QUALITY",
                        "SchemaDriftDetection",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "schema_add_servicing_risk_band",
                        "DPC",
                        "silver",
                        "cron",
                        List.of("spark", "gx", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "SchemaDriftDetection", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "orchestration-sensor-file-arrival-current-static-deployability",
                        "Orchestration sensor — file arrival current loans live runtime",
                        "ORCHESTRATION_SENSOR",
                        "FileArrivalSensor",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "current_loans",
                        "DPC",
                        "control_plane",
                        "event",
                        List.of("airflow", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileArrivalSensor", "FileIngestion", "GenericFilter", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "orchestration-policy-advance-time-late-arriving-artifact-only",
                        "Orchestration policy — advance time late-arriving artifact proof",
                        "ORCHESTRATION_POLICY",
                        "AdvanceTimeDimension",
                        ScenarioDsl.ProofMode.ARTIFACT_ONLY,
                        "late_arriving_boarding",
                        "DPC",
                        "control_plane",
                        "event",
                        List.of("airflow"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("AdvanceTimeDimension", "FileIngestion", "GenericFilter", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "destination-lake-current-live-runtime",
                        "Destination — lake writer current loans live runtime",
                        "DESTINATION",
                        "LakeWriter",
                        ScenarioDsl.ProofMode.LIVE_RUNTIME,
                        "current_loans",
                        "DPC",
                        "gold",
                        "manual",
                        List.of("airflow", "spark", "dbt", "gx", "minio"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "GenericFilter", "LakeWriter")
                ),
                new ScenarioTemplate(
                        "destination-database-delinquent-static-deployability",
                        "Destination — Postgres database writer delinquent loans static deployability",
                        "DESTINATION",
                        "DatabaseWriter",
                        ScenarioDsl.ProofMode.STATIC_DEPLOYABILITY,
                        "delinquent_loans",
                        "POSTGRES",
                        "gold",
                        "cron",
                        List.of("spark", "postgres"),
                        "DOCKER_COMPOSE_LOCAL",
                        List.of("FileIngestion", "GenericFilter", "DatabaseWriter")
                )
        );
    }

    public record ScenarioFamilyCatalog(List<ScenarioFamilySpec> scenarios) {
        public ScenarioFamilyCatalog {
            scenarios = List.copyOf(scenarios);
        }
    }

    public record ScenarioFamilySpec(
            String scenarioId,
            String displayName,
            String blueprintFamily,
            String representativeBlueprintKey,
            String fixtureDerivativeId,
            String storageBackend,
            String lakeLayer,
            String orchestrationMode,
            List<String> requiredRuntimeCapabilities,
            String localExecutionFeasibility,
            ScenarioDsl.ScenarioDefinition scenario
    ) {
        public ScenarioFamilySpec {
            requiredRuntimeCapabilities = List.copyOf(requiredRuntimeCapabilities);
            Objects.requireNonNull(scenario, "scenario");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActiveBlueprintCatalogRecord(
            String blueprintKey,
            String name,
            String category,
            String family,
            boolean pipelineConfig,
            String status,
            boolean deferred,
            String replacementBlueprintKey,
            String computeBackend,
            String compositionRole,
            List<String> validLayers,
            List<String> artifactTypes
    ) {
        public ActiveBlueprintCatalogRecord {
            validLayers = validLayers == null ? List.of() : List.copyOf(validLayers);
            artifactTypes = artifactTypes == null ? List.of() : List.copyOf(artifactTypes);
        }
    }

    private record ScenarioTemplate(
            String scenarioId,
            String displayName,
            String blueprintFamily,
            String representativeBlueprintKey,
            ScenarioDsl.ProofMode proofMode,
            String fixtureDerivativeId,
            String storageBackend,
            String lakeLayer,
            String orchestrationMode,
            List<String> requiredRuntimeCapabilities,
            String localExecutionFeasibility,
            List<String> executionBlueprintKeys
    ) {
        private ScenarioTemplate {
            requiredRuntimeCapabilities = List.copyOf(new LinkedHashSet<>(requiredRuntimeCapabilities));
            executionBlueprintKeys = List.copyOf(executionBlueprintKeys);
        }
    }

    private record RuntimePromotionDecision(
            ScenarioDsl.ProofMode effectiveProofMode,
            String status,
            String blockerReason
    ) {}
}
