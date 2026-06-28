package com.pulse.e2e.builder;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.deploy.model.Package;
import com.pulse.e2e.LoanMasterFixture;
import com.pulse.e2e.api.ApiScenarioClient;
import com.pulse.e2e.contract.ScenarioDsl;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.LocalGitService;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.service.SchemaPropagationService.PropagationSummary;
import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorType;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.ReleaseStage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ApiScenarioBuilder {

    private final ApiScenarioClient client;
    private final BlueprintRepository blueprintRepository;
    private final com.pulse.sor.repository.ConnectorDefinitionRepository connectorDefinitionRepository;
    private final GitRepoRepository gitRepoRepository;
    private final LocalGitService localGitService;
    private final Path tenantGitRoot;

    public ApiScenarioBuilder(ApiScenarioClient client,
                              BlueprintRepository blueprintRepository,
                              com.pulse.sor.repository.ConnectorDefinitionRepository connectorDefinitionRepository) {
        this(client, blueprintRepository, connectorDefinitionRepository, null, null, null);
    }

    public ApiScenarioBuilder(ApiScenarioClient client,
                              BlueprintRepository blueprintRepository,
                              com.pulse.sor.repository.ConnectorDefinitionRepository connectorDefinitionRepository,
                              GitRepoRepository gitRepoRepository,
                              LocalGitService localGitService,
                              Path tenantGitRoot) {
        this.client = client;
        this.blueprintRepository = blueprintRepository;
        this.connectorDefinitionRepository = connectorDefinitionRepository;
        this.gitRepoRepository = gitRepoRepository;
        this.localGitService = localGitService;
        this.tenantGitRoot = tenantGitRoot;
    }

    public ScenarioExecution execute(ScenarioDsl.ScenarioDefinition scenario, LoanMasterFixture fixture) throws Exception {
        seedBlueprints(scenario.builderPlan().blueprintKeys());
        ConnectorDefinition sourceDefinition = ensureConnectorDefinition(
                "S3-compatible Object Storage", ConnectorType.SOURCE, "pulse/source-s3", List.of("full_refresh"));
        ConnectorDefinition lakeDestinationDefinition = ensureConnectorDefinition(
                "Lake Destination", ConnectorType.DESTINATION, "pulse/destination-lake", List.of());
        ConnectorDefinition databaseDestinationDefinition = ensureConnectorDefinition(
                "PostgreSQL", ConnectorType.DESTINATION, "pulse/destination-postgres", List.of("append", "merge_on_pk"));

        client.createTenant(scenario.builderPlan().tenantId(), title(scenario.builderPlan().tenantId()), scenario.builderPlan().tenantId().replace("tenant-", ""));
        ensureTenantGitRepo(scenario.builderPlan().tenantId());
        Domain domain = client.createDomain(
                scenario.builderPlan().tenantId(),
                title(scenario.builderPlan().domainSlug()),
                "API-built domain for " + scenario.displayName());

        Set<String> activeBlueprintKeys = client.listActiveBlueprints().stream()
                .map(Blueprint::getBlueprintKey)
                .collect(Collectors.toSet());

        Map<String, Object> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put("fixturePath", fixture.path().toString());
        sourceMetadata.put("fixtureSha256", fixture.sha256());
        sourceMetadata.put("fixtureRows", fixture.rowCount());
        sourceMetadata.put("fixtureColumns", fixture.columnCount());
        sourceMetadata.put("fixtureId", scenario.builderPlan().fixtureId());

        var sor = client.createSor(
                scenario.builderPlan().tenantId(),
                title(scenario.builderPlan().sourceDataset()) + " Source",
                "API-built source for " + scenario.displayName(),
                domain.getId(),
                sourceMetadata);

        var source = client.createConnector(
                sor.getId(),
                sourceDefinition.getId(),
                title(scenario.builderPlan().sourceDataset()) + " Landing",
                "Reads canonical loan_master fixture",
                Map.of(
                        "bucket", "loan-master-fixtures",
                        "path_prefix", "loan_master/",
                        "filename_pattern", fixture.path().getFileName().toString()));
        Dataset sourceDataset = client.createDataset(source.getId(), loanMasterDatasetRequest(fixture));

        boolean usesDatabaseWriter = scenario.builderPlan().blueprintKeys().contains("DatabaseWriter");
        ConnectorDefinition destinationDefinition = usesDatabaseWriter
                ? databaseDestinationDefinition
                : lakeDestinationDefinition;
        Map<String, Object> destinationConfig = usesDatabaseWriter
                ? Map.of(
                        "host", "postgres",
                        "port", "5432",
                        "database", "pulse",
                        "schema", "public")
                : Map.of(
                        "bucket", "pulse-dpc-home-lending-dev-lake",
                        "path_prefix", "servicing/loan_master/",
                        "format", "delta");

        var destination = client.createConnector(
                sor.getId(),
                destinationDefinition.getId(),
                usesDatabaseWriter ? "PostgreSQL Destination" : "Lake Destination",
                "Writes scenario outputs",
                destinationConfig);

        client.upsertCredential(source.getId(), "DEV", Map.of(
                "metadata", Map.of("region", "us-east-1"),
                "secretValues", Map.of(
                        "aws_access_key_id", "test-access-key",
                        "aws_secret_access_key", "test-secret-key"
                )
        ));
        client.upsertCredential(destination.getId(), "DEV", usesDatabaseWriter
                ? Map.of(
                        "metadata", Map.of(
                                "host", "postgres",
                                "port", "5432",
                                "database", "pulse",
                                "schema", "public"
                        ),
                        "secretValues", Map.of(
                                "username", "pulse",
                                "password", "pulse"
                        )
                )
                : Map.of(
                        "metadata", Map.of("region", "us-east-1"),
                        "secretValues", Map.of(
                                "aws_access_key_id", "test-access-key",
                                "aws_secret_access_key", "test-secret-key"
                        )
                )
        );

        Pipeline pipeline = client.createPipeline(
                scenario.builderPlan().tenantId(),
                scenario.displayName(),
                "API-built scenario: " + scenario.scenarioId(),
                domain.getId(),
                canonicalPipelineBackend(scenario.fixtureRefs().get("storage_backend")));
        PipelineVersion version = client.getVersion(
                scenario.builderPlan().tenantId(),
                pipeline.getId(),
                pipeline.getActiveVersionId());

        var instances = addInstances(
                scenario.builderPlan().blueprintKeys(), version.getId(), pipeline.getId(),
                scenario.builderPlan().tenantId(), sor.getId(), source.getId(), source.getName(), sourceDataset.getId(),
                destination.getId(), destination.getName());
        wireLinearPipeline(version.getId(), instances);

        PropagationSummary propagation = client.recomputeSchema(version.getId());
        GenerationRun generationRun = client.generate(version.getId(), pipeline.getId(), scenario.builderPlan().tenantId(), "worker-2");
        Package builtPackage = client.buildPackage(version.getId(), pipeline.getId(), scenario.builderPlan().tenantId(), "worker-2");
        List<GeneratedArtifact> artifacts = client.getArtifacts(generationRun.getId());

        return new ScenarioExecution(
                scenario,
                fixture,
                activeBlueprintKeys,
                domain,
                pipeline,
                version,
                List.copyOf(instances),
                propagation,
                generationRun,
                builtPackage,
                artifacts
        );
    }

    private List<SubPipelineInstance> addInstances(List<String> blueprintKeys,
                                                   String versionId,
                                                   String pipelineId,
                                                   String tenantId,
                                                   String sorId,
                                                   String sourceConnectorId,
                                                   String sourceConnectorName,
                                                   String sourceDatasetId,
                                                   String destinationConnectorId,
                                                   String destinationConnectorName) throws Exception {
        java.util.ArrayList<SubPipelineInstance> instances = new java.util.ArrayList<>();
        for (String blueprintKey : blueprintKeys) {
            Map<String, Object> params = switch (blueprintKey) {
                case "FileIngestion" -> Map.of(
                        "connector_instance_id", sourceConnectorId,
                        "connector_name", sourceConnectorName,
                        "file_format", "csv",
                        "header", "true",
                        "infer_schema", "true",
                        "storage_backend", "DPC",
                        "lake_layer", "bronze",
                        "lake_format", "delta");
                case "BulkBackfill" -> Map.of(
                        "source_query", "select * from loan_master where loan_status = 'Delinquent'",
                        "date_range_start", "2025-01-01",
                        "date_range_end", "2025-12-31",
                        "chunk_size", 5000,
                        "chunk_days", 7,
                        "parallelism", 2,
                        "storage_backend", "DPC",
                        "lake_layer", "bronze",
                        "lake_format", "delta");
                case "SnapshotIngestion" -> Map.of(
                        "source_table", "public.loan_master",
                        "snapshot_frequency", "daily",
                        "compare_key", "loan_id",
                        "storage_backend", "DPC",
                        "lake_layer", "bronze",
                        "lake_format", "delta");
                case "FileArrivalSensor" -> Map.of(
                        "storage_kind", "s3",
                        "bucket", "pulse-dpc-home-lending-dev-files",
                        "path_prefix", "servicing/loan_master/",
                        "filename_pattern", "loan_master.csv",
                        "pattern_kind", "exact",
                        "soft_fail", false,
                        "poke_interval_seconds", 30,
                        "timeout_seconds", 300,
                        "mode", "reschedule",
                        "storage_backend", "DPC");
                case "AdvanceTimeDimension" -> advanceTimeDimensionDatasetAdvanceParams(
                        tenantId, sorId, sourceConnectorId, sourceDatasetId);
                case "GenericFilter" -> Map.of(
                        "filter_mode", "sql",
                        "raw_sql", "loan_status = 'Current'",
                        "storage_backend", "DPC",
                        "lake_layer", "silver",
                        "lake_format", "delta");
                case "BronzeToSilverCleaning" -> Map.of(
                        "storage_backend", "DPC",
                        "lake_layer", "silver",
                        "lake_format", "delta");
                case "GenericAggregate" -> Map.of(
                        "group_by_columns", List.of("property_state", "loan_status"),
                        "aggregations", List.of(Map.of(
                                "column", "current_upb",
                                "function", "sum",
                                "alias", "total_current_upb"
                        )),
                        "storage_backend", "DPC",
                        "lake_layer", "gold",
                        "lake_format", "delta");
                case "GenericJoin" -> Map.of(
                        "join_type", "left",
                        "join_keys", List.of(Map.of(
                                "left_column", "loan_id",
                                "right_column", "loan_id"
                        )),
                        "left_join_keys", List.of("loan_id"),
                        "right_join_keys", List.of("loan_id"),
                        "select_columns", List.of("loans.loan_id", "loans.loan_status", "loans.property_state", "loans.current_upb"),
                        "alias_left", "loans",
                        "alias_right", "loan_attrs",
                        "storage_backend", "DPC",
                        "lake_layer", "silver",
                        "lake_format", "delta");
                case "GenericRouter" -> Map.of(
                        "routes", List.of(Map.of(
                                "name", "current_loans",
                                "condition", "loan_status = 'Current'",
                                "description", "Route current loans"
                        )),
                        "include_default", true,
                        "storage_backend", "DPC",
                        "lake_layer", "silver",
                        "lake_format", "delta");
                case "JsonFlatten" -> Map.of(
                        "source_columns", List.of("servicing_payload"),
                        "separator", "_",
                        "max_depth", 2,
                        "explode_arrays", false,
                        "keep_original", true,
                        "prefix", "servicing",
                        "storage_backend", "DPC",
                        "lake_layer", "silver",
                        "lake_format", "delta");
                case "JsonStruct" -> Map.of(
                        "output_format", "struct",
                        "mappings", Map.of(
                                "borrower", List.of("borrower_first_name", "borrower_last_name", "borrower_email"),
                                "property", List.of("property_state", "property_zip")
                        ),
                        "drop_source_columns", false,
                        "passthrough_columns", List.of("loan_id", "loan_number"),
                        "storage_backend", "DPC",
                        "lake_layer", "silver",
                        "lake_format", "delta");
                case "PIIMasking" -> Map.of(
                        "columns_to_mask", List.of("borrower_first_name", "borrower_last_name", "borrower_email"),
                        "masking_strategy", "hash",
                        "preserve_format", false,
                        "hash_algorithm", "sha256",
                        "storage_backend", "DPC",
                        "lake_layer", "silver",
                        "lake_format", "delta");
                case "DedupeAndMerge" -> Map.of(
                        "match_keys", List.of("loan_id"),
                        "order_by_columns", List.of(
                                Map.of("column", "last_payment_date", "direction", "desc"),
                                Map.of("column", "loan_number", "direction", "asc")
                        ),
                        "match_strategy", "exact",
                        "merge_priority", "latest",
                        "dedup_method", "row_number",
                        "storage_backend", "DPC",
                        "lake_layer", "silver",
                        "lake_format", "delta");
                case "SchemaNormalization" -> Map.of(
                        "target_schema", "loan_master_canonical",
                        "mapping_rules", Map.of("loan_id", "loan_id", "loan_number", "loan_number"),
                        "strict_mode", false,
                        "storage_backend", "DPC",
                        "lake_layer", "silver",
                        "lake_format", "delta");
                case "SnapshotModel" -> Map.of(
                        "snapshot_frequency", "daily",
                        "retention_days", 30,
                        "unique_key", "loan_id",
                        "strategy", "timestamp",
                        "storage_backend", "DPC",
                        "lake_layer", "gold",
                        "lake_format", "delta");
                case "FactBuild" -> Map.of(
                        "grain", List.of("loan_id"),
                        "measures", List.of("current_upb", "interest_rate"),
                        "dimension_keys", List.of("loan_status", "state"),
                        "incremental", true,
                        "time_column", "as_of_date",
                        "storage_backend", "DPC",
                        "lake_layer", "gold",
                        "lake_format", "delta");
                case "AggregateMaterialization" -> Map.of(
                        "group_by", List.of("property_state", "loan_status"),
                        "aggregations", List.of(Map.of(
                                "column", "current_upb",
                                "function", "sum",
                                "alias", "total_current_upb"
                        )),
                        "refresh_strategy", "full_refresh",
                        "storage_backend", "DPC",
                        "lake_layer", "gold",
                        "lake_format", "delta");
                case "FeatureTablePublish" -> Map.of(
                        "entity_key", "loan_id",
                        "features", List.of("current_upb", "interest_rate", "borrower_credit_score"),
                        "point_in_time_column", "as_of_date",
                        "output_format", "delta",
                        "storage_backend", "DPC",
                        "lake_layer", "gold",
                        "lake_format", "delta");
                case "IncrementalMerge" -> Map.of(
                        "merge_keys", List.of("loan_id"),
                        "merge_strategy", "upsert",
                        "soft_delete", false,
                        "late_data_policy", "merge_anyway",
                        "late_threshold_hours", 48,
                        "storage_backend", "DPC",
                        "lake_layer", "gold",
                        "lake_format", "delta");
                case "ReferenceDataPublish" -> Map.of(
                        "reference_type", "property_state",
                        "publish_frequency", "daily",
                        "versioned", true,
                        "storage_backend", "DPC",
                        "lake_layer", "gold",
                        "lake_format", "delta");
                case "SCD2Dimension" -> Map.of(
                        "business_key", List.of("loan_id"),
                        "tracked_columns", List.of("loan_status", "current_upb", "interest_rate"),
                        "effective_date_column", "as_of_date",
                        "storage_backend", "DPC",
                        "lake_layer", "gold",
                        "lake_format", "delta");
                case "WideDenormalizedMart" -> Map.of(
                        "fact_source", "loan_master",
                        "dimension_joins", List.of(Map.of(
                                "dimension", "loan_status",
                                "join_key", "loan_status"
                        )),
                        "pre_aggregations", List.of("state_balance"),
                        "storage_backend", "DPC",
                        "lake_layer", "gold",
                        "lake_format", "delta");
                case "DQValidator" -> Map.of(
                        "expectations", List.of(Map.of(
                                "type", "ExpectColumnValuesToNotBeNull",
                                "kwargs", Map.of("column", "loan_id"),
                                "severity", "error"
                        )),
                        "on_failure", "quarantine",
                        "threshold_percent", 99.0,
                        "mostly", 1.0,
                        "storage_backend", "DPC");
                case "AnomalyDetection" -> Map.of(
                        "monitored_columns", List.of("current_upb", "interest_rate"),
                        "sensitivity_percent", 95,
                        "detection_method", "z_score",
                        "lookback_runs", 5,
                        "volume_monitoring", true,
                        "storage_backend", "DPC");
                case "FreshnessChecks" -> Map.of(
                        "timestamp_column", "as_of_date",
                        "max_age_hours", 24,
                        "max_age_minutes", 1440,
                        "storage_backend", "DPC");
                case "SchemaDriftDetection" -> Map.of(
                        "expected_columns", List.of("loan_id", "loan_number", "loan_status", "current_upb"),
                        "strict_order", false,
                        "allow_extra_columns", true,
                        "drift_policy", "warn",
                        "storage_backend", "DPC");
                case "LakeWriter" -> Map.of(
                        "connector_instance_id", destinationConnectorId,
                        "connector_name", destinationConnectorName,
                        "target_id", "loan-master-target",
                        "storage_backend", "DPC",
                        "lake_layer", "silver",
                        "lake_format", "delta",
                        "output_path", "s3a://pulse-dpc-home-lending-dev-lake/servicing/loan_master",
                        "write_mode", "overwrite");
                case "DatabaseWriter" -> Map.of(
                        "connector_instance_id", destinationConnectorId,
                        "connector_name", destinationConnectorName,
                        "target_id", "loan-master-target",
                        "target_table", "public.loan_master_e2e",
                        "write_mode", "append",
                        "batch_size", 5000);
                default -> throw new IllegalArgumentException("Unsupported blueprint in API builder: " + blueprintKey);
            };
            instances.add(client.addInstance(versionId, pipelineId, blueprintKey, defaultNameFor(blueprintKey), params));
        }
        return instances;
    }

    static Map<String, Object> advanceTimeDimensionDatasetAdvanceParams(String tenantId,
                                                                        String sorId,
                                                                        String connectorId,
                                                                        String datasetId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dataset_name", "loan_master");
        params.put("target_scope", "dataset");
        params.put("state_binding_ref", "time_state:dataset:%s".formatted(datasetId));
        params.put("variable_key", "pulse.time_state.%s.time_state_dataset_%s"
                .formatted(tenantId.replaceAll("[^a-zA-Z0-9]+", "_"), datasetId.replaceAll("[^a-zA-Z0-9-]+", "_")));
        params.put("calendar_binding_ref", "calendar:%s:%s".formatted(tenantId, sorId));
        params.put("calendar_bundle_uri", "runtime/calendar-bundles/%s.json".formatted(datasetId));
        params.put("calendar_bundle_hash", "sha256:fixture-%s".formatted(datasetId));
        params.put("calendar_id", "US-FED");
        params.put("grain", "DAILY_BUSINESS_DAY");
        params.put("timezone", "America/New_York");
        params.put("advance_mode", "requested_asof");
        params.put("requested_asof_expr", "2026-03-03");
        params.put("initialization_policy", "require_existing");
        params.put("concurrency_policy", "serialized_airflow");
        params.put("evidence_prefix", "runtime-evidence/time-advances");
        params.put("advanced_by", "airflow:advance-time-dimension");
        params.put("source", "semantic-proof");
        params.put("notes_template", "Advance loan_master as-of date after deterministic fixture processing");
        params.put("storage_backend", "DPC");
        return params;
    }

    private static String canonicalPipelineBackend(Object fixtureBackend) {
        if (fixtureBackend instanceof String raw) {
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if ("DPC".equals(normalized) || "GCP".equals(normalized)) {
                return normalized;
            }
        }
        return "DPC";
    }

    private void ensureTenantGitRepo(String tenantId) throws Exception {
        if (gitRepoRepository == null || localGitService == null || tenantGitRoot == null) {
            return;
        }
        if (gitRepoRepository.findByTenantIdAndScope(tenantId, "TENANT").isPresent()) {
            return;
        }
        Path repoPath = tenantGitRoot.resolve(tenantId);
        Files.createDirectories(repoPath);
        localGitService.initRepo(repoPath.toString(), "main");
        Files.writeString(repoPath.resolve("README.md"), "seed repo for " + tenantId + "\n");
        localGitService.commitAll(repoPath.toString(), "Seed tenant repo for E2E package provenance");

        GitRepo repo = new GitRepo();
        repo.setTenantId(tenantId);
        repo.setScope("TENANT");
        repo.setRepoType("LOCAL");
        repo.setProvider("LOCAL");
        repo.setLocalPath(repoPath.toString());
        repo.setRepoUrl("file://" + repoPath);
        repo.setDefaultBranch("main");
        repo.setCurrentBranch("main");
        repo.setMetadata(Map.of("scope", "TENANT", "fixture", "api-scenario-builder"));
        gitRepoRepository.save(repo);
    }

    private Map<String, Object> loanMasterDatasetRequest(LoanMasterFixture fixture) throws Exception {
        return Map.of(
                "name", "loan_master",
                "description", "Canonical loan_master fixture dataset for API-built semantic proof scenarios",
                "definitionType", "OBJECT_SELECTION",
                "classification", "INTERNAL",
                "schemaFormat", "JSON_SCHEMA",
                "schemaSnapshot", loanMasterSchemaSnapshot(fixture),
                "sourceTables", List.of(fixture.path().getFileName().toString()),
                "timeGrain", "DAILY",
                "currentAsof", "2026-03-02T05:00:00Z",
                "asofTimezone", "America/New_York"
        );
    }

    private Map<String, Object> loanMasterSchemaSnapshot(LoanMasterFixture fixture) throws Exception {
        String header = Files.readAllLines(fixture.path()).get(0);
        List<Map<String, Object>> columns = java.util.Arrays.stream(header.split(",", -1))
                .map(String::trim)
                .map(name -> Map.<String, Object>of("name", name, "type", "string"))
                .toList();
        return Map.of(
                "columns", columns,
                "rowCount", fixture.rowCount(),
                "columnCount", fixture.columnCount(),
                "fixtureSha256", fixture.sha256()
        );
    }

    private void wireLinearPipeline(String versionId, List<SubPipelineInstance> instances) throws Exception {
        if (instances.size() < 2) {
            return;
        }
        for (int i = 0; i < instances.size() - 1; i++) {
            String sourcePort = switch (instances.get(i).getBlueprintKey()) {
                case "AdvanceTimeDimension" -> "status";
                case "AggregateMaterialization" -> "aggregate_output";
                case "AnomalyDetection" -> "anomaly_report";
                case "BronzeToSilverCleaning" -> "cleaned_output";
                case "BulkBackfill" -> "backfill_output";
                case "DedupeAndMerge" -> "deduped_output";
                case "DQValidator" -> "validated_output";
                case "FactBuild" -> "fact_output";
                case "FeatureTablePublish" -> "feature_output";
                case "FileArrivalSensor" -> "ready_signal";
                case "FileIngestion" -> "raw_output";
                case "FreshnessChecks" -> "freshness_result";
                case "GenericAggregate" -> "aggregated_output";
                case "GenericFilter" -> "filtered_output";
                case "GenericJoin" -> "joined_output";
                case "GenericRouter" -> "default_output";
                case "IncrementalMerge" -> "merged_output";
                case "JsonFlatten" -> "flat_output";
                case "JsonStruct" -> "struct_output";
                case "LakeWriter", "DatabaseWriter" -> null;
                case "PIIMasking" -> "masked_output";
                case "ReferenceDataPublish" -> "published_reference";
                case "SCD2Dimension" -> "scd2_output";
                case "SchemaNormalization" -> "normalized_output";
                case "SchemaDriftDetection" -> "drift_report";
                case "SnapshotIngestion" -> "snapshot_output";
                case "SnapshotModel" -> "snapshot_output";
                case "WideDenormalizedMart" -> "mart_output";
                default -> throw new IllegalArgumentException("Unsupported source blueprint: " + instances.get(i).getBlueprintKey());
            };
            String targetPort = switch (instances.get(i + 1).getBlueprintKey()) {
                case "AdvanceTimeDimension" -> "trigger";
                case "AggregateMaterialization" -> "detail_data";
                case "AnomalyDetection" -> "data_to_monitor";
                case "BronzeToSilverCleaning" -> "raw_input";
                case "DatabaseWriter", "GenericFilter", "LakeWriter" -> "data_input";
                case "DedupeAndMerge" -> "input_data";
                case "DQValidator" -> "data_to_validate";
                case "FactBuild" -> "transaction_data";
                case "FeatureTablePublish" -> "source_data";
                case "FileIngestion", "BulkBackfill", "FileArrivalSensor" -> null;
                case "FreshnessChecks" -> "monitored_dataset";
                case "GenericAggregate", "GenericRouter", "JsonFlatten", "JsonStruct" -> "data_input";
                case "GenericJoin" -> "left_input";
                case "IncrementalMerge" -> "incremental_data";
                case "PIIMasking" -> "sensitive_data";
                case "ReferenceDataPublish" -> "reference_source";
                case "SCD2Dimension" -> "source_data";
                case "SchemaNormalization", "SnapshotModel" -> "source_data";
                case "SchemaDriftDetection" -> "incoming_data";
                case "SnapshotIngestion" -> null;
                case "WideDenormalizedMart" -> "fact_data";
                default -> throw new IllegalArgumentException("Unsupported target blueprint: " + instances.get(i + 1).getBlueprintKey());
            };
            if (sourcePort == null || targetPort == null) {
                continue;
            }
            client.wire(versionId, instances.get(i).getId(), sourcePort, instances.get(i + 1).getId(), targetPort);
            for (String additionalTargetPort : additionalTargetPorts(instances.get(i + 1).getBlueprintKey(), targetPort)) {
                client.wire(versionId, instances.get(i).getId(), sourcePort, instances.get(i + 1).getId(), additionalTargetPort);
            }
        }
    }

    private List<String> additionalTargetPorts(String blueprintKey, String alreadyWiredPort) {
        List<String> ports = switch (blueprintKey) {
            case "FactBuild" -> List.of("dimension_refs");
            case "GenericJoin" -> List.of("right_input");
            case "WideDenormalizedMart" -> List.of("dimension_data");
            default -> List.of();
        };
        return ports.stream()
                .filter(port -> !port.equals(alreadyWiredPort))
                .toList();
    }

    private void seedBlueprints(List<String> blueprintKeys) {
        for (String key : blueprintKeys) {
            if (blueprintRepository.findByBlueprintKey(key).isPresent()) {
                continue;
            }
            Blueprint bp = new Blueprint();
            bp.setBlueprintKey(key);
            bp.setName(key);
            bp.setDescription("E2E test blueprint for " + key);
            bp.setVersion("1.0.0");
            bp.setParamsSchema(List.of());
            bp.setRuntimeRequirements(Map.of());
            bp.setPipelineConfig(false);
            bp.setDeferred(false);
            bp.setStatus("active");
            switch (key) {
                case "AdvanceTimeDimension" -> {
                    bp.setCategory(BlueprintCategory.ORCHESTRATION);
                    bp.setInputPorts(List.of(Map.of("name", "trigger")));
                    bp.setOutputPorts(List.of(Map.of("name", "status")));
                    bp.setValidLayers(List.of("control_plane"));
                    bp.setComputeBackend("airflow");
                    bp.setCompositionRole("orchestration_policy");
                }
                case "AggregateMaterialization" -> {
                    bp.setCategory(BlueprintCategory.MODELING);
                    bp.setInputPorts(List.of(Map.of("name", "detail_data")));
                    bp.setOutputPorts(List.of(Map.of("name", "aggregate_output")));
                    bp.setValidLayers(List.of("gold"));
                    bp.setComputeBackend("spark");
                }
                case "AnomalyDetection" -> {
                    bp.setCategory(BlueprintCategory.DATA_QUALITY);
                    bp.setInputPorts(List.of(Map.of("name", "data_to_monitor")));
                    bp.setOutputPorts(List.of(Map.of("name", "anomaly_report")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "BronzeToSilverCleaning" -> {
                    bp.setCategory(BlueprintCategory.TRANSFORM);
                    bp.setInputPorts(List.of(Map.of("name", "raw_input")));
                    bp.setOutputPorts(List.of(Map.of("name", "cleaned_output")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "BulkBackfill" -> {
                    bp.setCategory(BlueprintCategory.INGESTION);
                    bp.setInputPorts(List.of());
                    bp.setOutputPorts(List.of(Map.of("name", "backfill_output")));
                    bp.setValidLayers(List.of("bronze"));
                    bp.setComputeBackend("spark");
                }
                case "DatabaseWriter" -> {
                    bp.setCategory(BlueprintCategory.DESTINATION);
                    bp.setInputPorts(List.of(Map.of("name", "data_input")));
                    bp.setOutputPorts(List.of());
                    bp.setValidLayers(List.of("gold"));
                    bp.setComputeBackend("spark");
                }
                case "DedupeAndMerge" -> {
                    bp.setCategory(BlueprintCategory.TRANSFORM);
                    bp.setInputPorts(List.of(Map.of("name", "input_data")));
                    bp.setOutputPorts(List.of(Map.of("name", "deduped_output")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "DQValidator" -> {
                    bp.setCategory(BlueprintCategory.DATA_QUALITY);
                    bp.setInputPorts(List.of(Map.of("name", "data_to_validate")));
                    bp.setOutputPorts(List.of(Map.of("name", "validated_output"), Map.of("name", "quarantine_output")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "FactBuild" -> {
                    bp.setCategory(BlueprintCategory.MODELING);
                    bp.setInputPorts(List.of(Map.of("name", "transaction_data"), Map.of("name", "dimension_refs")));
                    bp.setOutputPorts(List.of(Map.of("name", "fact_output")));
                    bp.setValidLayers(List.of("gold"));
                    bp.setComputeBackend("spark");
                    bp.setSupportsReuse(true);
                }
                case "FeatureTablePublish" -> {
                    bp.setCategory(BlueprintCategory.MODELING);
                    bp.setInputPorts(List.of(Map.of("name", "source_data")));
                    bp.setOutputPorts(List.of(Map.of("name", "feature_output")));
                    bp.setValidLayers(List.of("gold"));
                    bp.setComputeBackend("spark");
                }
                case "FileArrivalSensor" -> {
                    bp.setCategory(BlueprintCategory.ORCHESTRATION);
                    bp.setInputPorts(List.of());
                    bp.setOutputPorts(List.of(Map.of("name", "ready_signal")));
                    bp.setValidLayers(List.of("control_plane"));
                    bp.setComputeBackend("airflow");
                    bp.setCompositionRole("orchestration_sensor");
                }
                case "FileIngestion" -> {
                    bp.setCategory(BlueprintCategory.INGESTION);
                    bp.setInputPorts(List.of());
                    bp.setOutputPorts(List.of(Map.of("name", "raw_output")));
                    bp.setValidLayers(List.of("bronze"));
                    bp.setComputeBackend("spark");
                }
                case "FreshnessChecks" -> {
                    bp.setCategory(BlueprintCategory.DATA_QUALITY);
                    bp.setInputPorts(List.of(Map.of("name", "monitored_dataset")));
                    bp.setOutputPorts(List.of(Map.of("name", "freshness_result")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "GenericAggregate" -> {
                    bp.setCategory(BlueprintCategory.TRANSFORM);
                    bp.setInputPorts(List.of(Map.of("name", "data_input")));
                    bp.setOutputPorts(List.of(Map.of("name", "aggregated_output")));
                    bp.setValidLayers(List.of("silver", "gold"));
                    bp.setComputeBackend("spark");
                }
                case "GenericFilter" -> {
                    bp.setCategory(BlueprintCategory.TRANSFORM);
                    bp.setInputPorts(List.of(Map.of("name", "data_input")));
                    bp.setOutputPorts(List.of(Map.of("name", "filtered_output")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "GenericJoin" -> {
                    bp.setCategory(BlueprintCategory.TRANSFORM);
                    bp.setInputPorts(List.of(Map.of("name", "left_input"), Map.of("name", "right_input")));
                    bp.setOutputPorts(List.of(Map.of("name", "joined_output")));
                    bp.setValidLayers(List.of("silver", "gold"));
                    bp.setComputeBackend("spark");
                }
                case "GenericRouter" -> {
                    bp.setCategory(BlueprintCategory.TRANSFORM);
                    bp.setInputPorts(List.of(Map.of("name", "data_input")));
                    bp.setOutputPorts(List.of(Map.of("name", "default_output")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "IncrementalMerge" -> {
                    bp.setCategory(BlueprintCategory.MODELING);
                    bp.setInputPorts(List.of(Map.of("name", "incremental_data")));
                    bp.setOutputPorts(List.of(Map.of("name", "merged_output")));
                    bp.setValidLayers(List.of("silver", "gold"));
                    bp.setComputeBackend("spark");
                }
                case "JsonFlatten" -> {
                    bp.setCategory(BlueprintCategory.TRANSFORM);
                    bp.setInputPorts(List.of(Map.of("name", "data_input")));
                    bp.setOutputPorts(List.of(Map.of("name", "flat_output")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "JsonStruct" -> {
                    bp.setCategory(BlueprintCategory.TRANSFORM);
                    bp.setInputPorts(List.of(Map.of("name", "data_input")));
                    bp.setOutputPorts(List.of(Map.of("name", "struct_output")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "LakeWriter" -> {
                    bp.setCategory(BlueprintCategory.DESTINATION);
                    bp.setInputPorts(List.of(Map.of("name", "data_input")));
                    bp.setOutputPorts(List.of());
                    bp.setValidLayers(List.of("silver", "gold"));
                    bp.setComputeBackend("spark");
                }
                case "PIIMasking" -> {
                    bp.setCategory(BlueprintCategory.TRANSFORM);
                    bp.setInputPorts(List.of(Map.of("name", "sensitive_data")));
                    bp.setOutputPorts(List.of(Map.of("name", "masked_output")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "ReferenceDataPublish" -> {
                    bp.setCategory(BlueprintCategory.MODELING);
                    bp.setInputPorts(List.of(Map.of("name", "reference_source")));
                    bp.setOutputPorts(List.of(Map.of("name", "published_reference")));
                    bp.setValidLayers(List.of("gold"));
                    bp.setComputeBackend("spark");
                }
                case "SCD2Dimension" -> {
                    bp.setCategory(BlueprintCategory.MODELING);
                    bp.setInputPorts(List.of(Map.of("name", "source_data")));
                    bp.setOutputPorts(List.of(Map.of("name", "scd2_output")));
                    bp.setValidLayers(List.of("gold"));
                    bp.setComputeBackend("spark");
                    bp.setSupportsReuse(true);
                }
                case "SchemaNormalization" -> {
                    bp.setCategory(BlueprintCategory.TRANSFORM);
                    bp.setInputPorts(List.of(Map.of("name", "source_data")));
                    bp.setOutputPorts(List.of(Map.of("name", "normalized_output")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "SchemaDriftDetection" -> {
                    bp.setCategory(BlueprintCategory.DATA_QUALITY);
                    bp.setInputPorts(List.of(Map.of("name", "incoming_data")));
                    bp.setOutputPorts(List.of(Map.of("name", "drift_report")));
                    bp.setValidLayers(List.of("silver"));
                    bp.setComputeBackend("spark");
                }
                case "SnapshotIngestion" -> {
                    bp.setCategory(BlueprintCategory.INGESTION);
                    bp.setInputPorts(List.of());
                    bp.setOutputPorts(List.of(Map.of("name", "snapshot_output")));
                    bp.setValidLayers(List.of("bronze"));
                    bp.setComputeBackend("spark");
                }
                case "SnapshotModel" -> {
                    bp.setCategory(BlueprintCategory.MODELING);
                    bp.setInputPorts(List.of(Map.of("name", "source_data")));
                    bp.setOutputPorts(List.of(Map.of("name", "snapshot_output")));
                    bp.setValidLayers(List.of("gold"));
                    bp.setComputeBackend("spark");
                }
                case "WideDenormalizedMart" -> {
                    bp.setCategory(BlueprintCategory.MODELING);
                    bp.setInputPorts(List.of(Map.of("name", "fact_data"), Map.of("name", "dimension_data")));
                    bp.setOutputPorts(List.of(Map.of("name", "mart_output")));
                    bp.setValidLayers(List.of("gold"));
                    bp.setComputeBackend("spark");
                    bp.setSupportsReuse(true);
                }
                default -> throw new IllegalArgumentException("Unsupported blueprint: " + key);
            }
            bp.setEmitStrategy("generate");
            blueprintRepository.save(bp);
        }
    }

    private ConnectorDefinition ensureConnectorDefinition(String name,
                                                         ConnectorType type,
                                                         String dockerRepository,
                                                         List<String> supportedModes) {
        return connectorDefinitionRepository.findAll().stream()
                .filter(existing -> existing.getName().equals(name) && existing.getConnectorType() == type)
                .findFirst()
                .orElseGet(() -> {
                    ConnectorDefinition definition = new ConnectorDefinition();
                    definition.setName(name);
                    definition.setConnectorType(type);
                    definition.setDockerRepository(dockerRepository);
                    definition.setDockerImageTag("1.0.0");
                    definition.setConnectionSpec(Map.of());
                    definition.setSupportedModes(supportedModes);
                    definition.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
                    return connectorDefinitionRepository.save(definition);
                });
    }

    private String defaultNameFor(String blueprintKey) {
        return switch (blueprintKey) {
            case "AdvanceTimeDimension" -> "Advance Loan As Of Date";
            case "AggregateMaterialization" -> "Materialize Loan Aggregates";
            case "AnomalyDetection" -> "Detect Loan Anomalies";
            case "BronzeToSilverCleaning" -> "Clean Loan Master";
            case "BulkBackfill" -> "Backfill Loan History";
            case "DatabaseWriter" -> "Write Loan Master To Postgres";
            case "DedupeAndMerge" -> "Dedupe Loan Records";
            case "DQValidator" -> "Validate Loan Quality";
            case "FactBuild" -> "Build Loan Fact";
            case "FeatureTablePublish" -> "Publish Loan Features";
            case "FileArrivalSensor" -> "Wait For Loan File";
            case "FileIngestion" -> "Ingest Loan Master";
            case "FreshnessChecks" -> "Check Loan Freshness";
            case "GenericAggregate" -> "Aggregate Loan Balances";
            case "GenericFilter" -> "Filter Current Loans";
            case "GenericJoin" -> "Join Loan Attributes";
            case "GenericRouter" -> "Route Loan Records";
            case "IncrementalMerge" -> "Merge Late Loan Updates";
            case "JsonFlatten" -> "Flatten Servicing Payload";
            case "JsonStruct" -> "Build Borrower Struct";
            case "LakeWriter" -> "Write Loan Master To Lake";
            case "PIIMasking" -> "Mask Loan PII";
            case "ReferenceDataPublish" -> "Publish State Reference Data";
            case "SCD2Dimension" -> "Track Loan History";
            case "SchemaNormalization" -> "Normalize Loan Schema";
            case "SchemaDriftDetection" -> "Detect Loan Schema Drift";
            case "SnapshotIngestion" -> "Snapshot Loan Source";
            case "SnapshotModel" -> "Snapshot Loan State";
            case "WideDenormalizedMart" -> "Build Wide Loan Mart";
            default -> blueprintKey;
        };
    }

    private String title(String slugLike) {
        return java.util.Arrays.stream(slugLike.split("[-_]+"))
                .filter(part -> !part.isBlank())
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
                .collect(Collectors.joining(" "));
    }

    public record ScenarioExecution(
            ScenarioDsl.ScenarioDefinition scenario,
            LoanMasterFixture fixture,
            Set<String> activeBlueprintKeys,
            Domain domain,
            Pipeline pipeline,
            PipelineVersion version,
            List<SubPipelineInstance> instances,
            PropagationSummary propagation,
            GenerationRun generationRun,
            Package builtPackage,
            List<GeneratedArtifact> artifacts
    ) {
    }
}
