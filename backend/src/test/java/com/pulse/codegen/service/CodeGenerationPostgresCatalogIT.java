package com.pulse.codegen.service;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.deploy.controller.DeployController;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.LocalGitService;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineStage;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.opengine.OpList;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postgres/Flyway Builder proof for the V153 catalog cutover.
 *
 * <p>The fast codegen tests use H2 and hand-authored blueprint fixtures. This test boots the
 * real Flyway catalog, resolves the seeded {@code SqlModel} blueprint row, and calls the live
 * {@link CodeGenerationService#generate(String, String, String, String)} entrypoint. The
 * assertions prove the generated dbt artifact came from {@code CodegenOpEngine}, not the legacy
 * fallback branch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres-it")
@Tag("integration")
@Transactional
class CodeGenerationPostgresCatalogIT {

    @Autowired BlueprintRepository blueprintRepository;
    @Autowired PipelineRepository pipelineRepository;
    @Autowired PipelineVersionRepository pipelineVersionRepository;
    @Autowired SubPipelineInstanceRepository subPipelineInstanceRepository;
    @Autowired CodeGenerationService codeGenerationService;
    @Autowired GeneratedArtifactRepository generatedArtifactRepository;
    @Autowired DeployController deployController;
    @Autowired PackageRepository packageRepository;
    @Autowired GitRepoRepository gitRepoRepository;
    @Autowired LocalGitService localGitService;

    @Test
    void generateAndPackage_useFlywaySeededSqlModelBlueprint(@TempDir Path repoRoot) throws Exception {
        Blueprint sqlModel = blueprintRepository.findByBlueprintKey("SqlModel").orElseThrow();
        assertThat(sqlModel.getSchemaBehavior()).containsKey("ops");
        OpList opList = OpList.parse(sqlModel.getSchemaBehavior());
        assertThat(opList.emission().compute()).isEqualTo("dbt");
        assertThat(opList.ops()).extracting(OpList.OpEntry::op).containsExactly("sql-model");
        assertThat(codeGenerationService.codegenOpEngine().registry()
                .has("sql-model", EmissionEngine.DBT_SQL)).isTrue();

        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId("tenant-home-lending");
        pipeline.setDomainName("lending");
        pipeline.setName("Postgres Catalog SqlModel Proof");
        pipeline.setDescription("B5 proof that live generation reaches CodegenOpEngine from Flyway catalog metadata.");
        pipeline.setCreatedBy("stub-user-001");
        pipeline.setDefaultStorageBackend("DPC");
        pipeline = pipelineRepository.save(pipeline);

        PipelineVersion version = new PipelineVersion();
        version.setPipelineId(pipeline.getId());
        version.setRevision(1);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy("stub-user-001");
        version.setSlaConfig(Map.of());
        version.setMetadata(Map.of("proof", "b5-postgres-catalog-codegen-op-engine"));
        version.setChangeSummary("B5 seeded catalog codegen proof");
        version = pipelineVersionRepository.save(version);

        pipeline.setActiveVersionId(version.getId());
        pipelineRepository.save(pipeline);

        SubPipelineInstance inst = new SubPipelineInstance();
        inst.setPipelineId(pipeline.getId());
        inst.setVersionId(version.getId());
        inst.setBlueprintId(sqlModel.getId());
        inst.setBlueprintKey(sqlModel.getBlueprintKey());
        inst.setBlueprintVersion(sqlModel.getVersion());
        inst.setName("Loan Risk SQL Model");
        inst.setExecutionOrder(1);
        inst.setParams(Map.of(
                "steps", List.of(
                        Map.of(
                                "name", "base",
                                "sql", "select loan_id, current_upb from {{ ref('loan_master_clean') }}",
                                "materialize", "cte"),
                        Map.of(
                                "name", "final",
                                "sql", "select loan_id, current_upb from base where current_upb > 0",
                                "materialize", "table")),
                "declared_output_schema", List.of(
                        Map.of("name", "loan_id", "type", "string"),
                        Map.of("name", "current_upb", "type", "decimal"))));
        inst.setInputDatasets(List.of());
        inst.setOutputDatasets(List.of(Map.of(
                "ref", "home-lending.lending.silver.loan_risk_sql_model",
                "format", "delta",
                "role", "silver")));
        inst.setSchemaStatus("clean");
        inst.setStorageBackend("DPC");
        inst.setLakeLayer("silver");
        inst.setLakeFormat("delta");
        inst = subPipelineInstanceRepository.save(inst);

        GenerationRun run = codeGenerationService.generate(
                pipeline.getId(), version.getId(), pipeline.getTenantId(), "stub-user-001");

        List<GeneratedArtifact> artifacts = generatedArtifactRepository
                .findByGenerationRunIdOrderByFilePathAsc(run.getId());
        assertThat(run.getStatus()).as(run.getErrorMessage()).isEqualTo("COMPLETED");
        assertThat(run.getMetadata())
                .as("artifacts=%s", artifacts.stream()
                        .map(a -> a.getFileType() + ":" + a.getFilePath() + ":" + a.getMetadata())
                        .toList())
                .containsKey("codegenOpEngineInstances");
        @SuppressWarnings("unchecked")
        List<Object> opEngineInstances = (List<Object>) run.getMetadata().get("codegenOpEngineInstances");
        assertThat(opEngineInstances)
                .contains(inst.getId());

        GeneratedArtifact dbtModel = artifacts.stream()
                .filter(a -> "DBT_MODEL".equals(a.getFileType()))
                .filter(a -> a.getMetadata() != null
                        && "CodegenOpEngine".equals(a.getMetadata().get("codegenEngine")))
                .findFirst()
                .orElseThrow();

        assertThat(dbtModel.getMetadata())
                .containsEntry("instanceId", inst.getId())
                .containsEntry("blueprintKey", "SqlModel");
        assertThat(dbtModel.getContent())
                .contains("-- Codegen engine: CodegenOpEngine")
                .contains("WITH base AS (")
                .contains("select loan_id, current_upb from base where current_upb > 0")
                .doesNotContain("postgres_catalog_sqlmodel_proof_input");

        GitRepo repo = seedTenantGitRepo(pipeline.getTenantId(), repoRoot);

        ResponseEntity<Package> response = deployController.buildPackage(version.getId(),
                new DeployController.BuildRequest(
                        pipeline.getId(), pipeline.getTenantId(), "body-user-is-ignored", null));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Package pkg = response.getBody();
        assertThat(pkg).isNotNull();
        assertThat(packageRepository.findById(pkg.getId())).isPresent();
        assertThat(pkg.getBuildStatus()).isEqualTo("COMPLETED");
        assertThat(pkg.getMetadata())
                .containsEntry("generationRunId", run.getId())
                .containsEntry("artifactCount", artifacts.size())
                .containsEntry("workingTreeStatus", "clean")
                .containsEntry("gitRepoId", repo.getId());
        assertThat(pkg.getMetadata()).containsKeys(
                "packageManifest",
                "packageManifestHash",
                "staticRuntimeAssessment");
        assertThat(pkg.getArtifactHash()).isNotBlank();
    }

    @Test
    void generate_reachesCodegenOpEngineForEverySeededOpListedBlueprint() {
        List<Blueprint> opListed = blueprintRepository.findAll().stream()
                .filter(bp -> OpList.isOpList(bp.getSchemaBehavior()))
                .sorted((a, b) -> a.getBlueprintKey().compareTo(b.getBlueprintKey()))
                .toList();
        assertThat(opListed).as("Flyway V153 op-listed blueprints").isNotEmpty();

        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId("tenant-home-lending");
        pipeline.setDomainName("lending");
        pipeline.setName("Postgres Catalog All Op Listed Proof");
        pipeline.setDescription("Default-entrypoint proof that every op-listed blueprint reaches CodegenOpEngine.");
        pipeline.setCreatedBy("stub-user-001");
        pipeline.setDefaultStorageBackend("DPC");
        pipeline = pipelineRepository.save(pipeline);

        PipelineVersion version = new PipelineVersion();
        version.setPipelineId(pipeline.getId());
        version.setRevision(1);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy("stub-user-001");
        version.setSlaConfig(Map.of());
        version.setMetadata(Map.of("proof", "all-op-listed-codegen-op-engine"));
        version.setChangeSummary("All op-listed blueprint codegen proof");
        version = pipelineVersionRepository.save(version);

        pipeline.setActiveVersionId(version.getId());
        pipelineRepository.save(pipeline);

        List<SubPipelineInstance> instances = new ArrayList<>();
        int order = 1;
        for (Blueprint bp : opListed) {
            SubPipelineInstance inst = new SubPipelineInstance();
            inst.setPipelineId(pipeline.getId());
            inst.setVersionId(version.getId());
            inst.setBlueprintId(bp.getId());
            inst.setBlueprintKey(bp.getBlueprintKey());
            inst.setBlueprintVersion(bp.getVersion());
            inst.setName("Proof " + bp.getBlueprintKey());
            inst.setExecutionOrder(order++);
            inst.setParams(proofParams(bp.getBlueprintKey()));
            inst.setInputDatasets(List.of());
            inst.setOutputDatasets(List.of(Map.of(
                    "ref", "home-lending.lending.proof." + bp.getBlueprintKey().toLowerCase(),
                    "format", "delta",
                    "role", "proof")));
            inst.setSchemaStatus("clean");
            inst.setStorageBackend("DPC");
            inst.setLakeLayer(defaultLayer(bp));
            inst.setLakeFormat("delta");
            instances.add(subPipelineInstanceRepository.save(inst));
        }

        GenerationRun run = codeGenerationService.generate(
                pipeline.getId(), version.getId(), pipeline.getTenantId(), "stub-user-001");
        List<GeneratedArtifact> artifacts = generatedArtifactRepository
                .findByGenerationRunIdOrderByFilePathAsc(run.getId());

        assertThat(run.getStatus()).as(run.getErrorMessage()).isEqualTo("COMPLETED");
        assertThat(run.getMetadata()).containsKey("codegenOpEngineInstances");
        @SuppressWarnings("unchecked")
        List<String> opEngineInstanceIds = ((List<Object>) run.getMetadata().get("codegenOpEngineInstances"))
                .stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
        assertThat(opEngineInstanceIds)
                .containsAll(instances.stream().map(SubPipelineInstance::getId).toList());

        GeneratedArtifact merge = artifactForBlueprint(artifacts, "IncrementalMerge");
        assertThat(merge.getContent())
                .contains("-- Codegen engine: CodegenOpEngine")
                .contains("materialized='incremental'")
                .contains("incremental_strategy='merge'")
                .contains("unique_key");

        GeneratedArtifact scd2 = artifactForBlueprint(artifacts, "SCD2Dimension");
        assertThat(scd2.getFileType()).isEqualTo("DBT_SNAPSHOT");
        assertThat(scd2.getContent())
                .contains("{% snapshot")
                .contains("-- Codegen engine: CodegenOpEngine")
                .doesNotContain("current_timestamp() as effective_from")
                .doesNotContain("CAST(NULL AS TIMESTAMP) as effective_to");

        GeneratedArtifact snapshot = artifactForBlueprint(artifacts, "SnapshotModel");
        assertThat(snapshot.getContent())
                .contains("materialized='incremental'")
                .contains("-- Codegen engine: CodegenOpEngine");

        GeneratedArtifact sourceSql = artifactForBlueprint(artifacts, "SourceSQL");
        assertThat(sourceSql.getContent())
                .contains("# Codegen engine: CodegenOpEngine")
                .contains(".option('query',")
                .contains("pulse_resolve_mnemonic");

        assertThat(artifacts.stream()
                .filter(a -> a.getMetadata() != null)
                .filter(a -> "GenericRouter".equals(a.getMetadata().get("blueprintKey")))
                .filter(a -> "CodegenOpEngine".equals(a.getMetadata().get("codegenEngine")))
                .count())
                .isGreaterThanOrEqualTo(2);

        GeneratedArtifact dag = artifacts.stream()
                .filter(a -> "AIRFLOW_DAG".equals(a.getFileType()))
                .findFirst()
                .orElseThrow();
        assertThat(dag.getContent()).contains("# Codegen engine: CodegenOpEngine");
        assertThat(dag.getMetadata()).containsEntry("codegenEngine", "CodegenOpEngine");
    }

    private GitRepo seedTenantGitRepo(String tenantId, Path repoRoot) throws Exception {
        Path repoPath = repoRoot.resolve("repo-" + tenantId);
        Files.createDirectories(repoPath);
        localGitService.initRepo(repoPath.toString(), "main");
        Files.writeString(repoPath.resolve("README.md"), "seed for " + tenantId + "\n");
        localGitService.commitAll(repoPath.toString(), "seed " + tenantId);

        GitRepo repo = new GitRepo();
        repo.setTenantId(tenantId);
        repo.setScope("TENANT");
        repo.setRepoType("REMOTE");
        repo.setLocalPath(repoPath.toString());
        repo.setRepoUrl("file://" + repoPath);
        repo.setProvider("GITHUB");
        repo.setDefaultBranch("main");
        repo.setCurrentBranch("main");
        return gitRepoRepository.save(repo);
    }

    private GeneratedArtifact artifactForBlueprint(List<GeneratedArtifact> artifacts, String blueprintKey) {
        return artifacts.stream()
                .filter(a -> a.getMetadata() != null)
                .filter(a -> blueprintKey.equals(a.getMetadata().get("blueprintKey")))
                .filter(a -> "CodegenOpEngine".equals(a.getMetadata().get("codegenEngine")))
                .findFirst()
                .orElseThrow();
    }

    private String defaultLayer(Blueprint bp) {
        String category = bp.getCategory() != null ? bp.getCategory().name() : "";
        if ("INGESTION".equals(category)) return "bronze";
        if ("DESTINATION".equals(category)) return "gold";
        return "silver";
    }

    private Map<String, Object> proofParams(String blueprintKey) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("storage_backend", "DPC");
        p.put("lake_layer", "silver");
        p.put("lake_format", "delta");
        p.put("source_query", "select loan_id, customer_id, current_upb, status, updated_at from loan_master where as_of_date = [[ PBD-1 ]]");
        p.put("source_table", "loan_master");
        p.put("target_path", "/tmp/pulse/proof/" + blueprintKey.toLowerCase());
        p.put("write_mode", "overwrite");
        p.put("merge_key", "loan_id");
        p.put("merge_keys", List.of("loan_id"));
        p.put("business_key", "loan_id");
        p.put("unique_key", "loan_id");
        p.put("updated_at", "updated_at");
        p.put("effective_date_column", "updated_at");
        p.put("snapshot_name", "proof_" + blueprintKey.toLowerCase());
        p.put("routes", List.of(
                Map.of("name", "current", "condition", "status = 'CURRENT'"),
                Map.of("name", "closed", "condition", "status = 'CLOSED'")));
        p.put("include_default", true);
        p.put("steps", List.of(
                Map.of("name", "base", "sql", "select loan_id, customer_id, current_upb, status, updated_at from {{ ref('loan_master_clean') }}", "materialize", "cte"),
                Map.of("name", "final", "sql", "select loan_id, customer_id, current_upb from base", "materialize", "table")));
        p.put("declared_output_schema", List.of(
                Map.of("name", "loan_id", "type", "string"),
                Map.of("name", "customer_id", "type", "string"),
                Map.of("name", "current_upb", "type", "decimal")));
        p.put("columns", List.of("loan_id", "customer_id", "current_upb"));
        p.put("keep_columns", List.of("loan_id", "customer_id", "current_upb"));
        p.put("drop_columns", List.of("status"));
        p.put("rename_map", Map.of("loan_id", "loan_key"));
        p.put("type_mappings", Map.of("current_upb", "decimal"));
        p.put("join_keys", List.of("loan_id"));
        p.put("dimension_keys", List.of("loan_id"));
        p.put("entity_key", "customer_id");
        p.put("group_by", List.of("customer_id"));
        p.put("aggregations", List.of(Map.of("name", "total_upb", "expression", "sum(current_upb)")));
        p.put("features", List.of(Map.of("name", "avg_upb", "expression", "avg(current_upb)")));
        p.put("expectations", List.of(Map.of(
                "type", "ExpectColumnValuesToNotBeNull",
                "kwargs", Map.of("column", "loan_id"))));
        p.put("on_failure", "block");
        p.put("timestamp_column", "updated_at");
        p.put("expected_columns", List.of(Map.of("name", "loan_id", "type", "string")));
        p.put("monitored_columns", List.of("current_upb"));
        p.put("detection_method", "z_score");
        p.put("sensitivity_percent", 2.0);
        p.put("cron_expression", "0 6 * * *");
        p.put("schedule", "0 6 * * *");
        p.put("storage_kind", "s3");
        p.put("bucket", "pulse-proof");
        p.put("path_prefix", "landing/loan_master");
        p.put("filename_pattern", "loan_master_{{ ds_nodash }}.csv");
        p.put("sql", "select 1");
        p.put("connection_id", "pulse_sql_default");
        p.put("event_url", "https://example.com/ready");
        p.put("remote_dag_id", "remote_loan_master");
        p.put("remote_target_ref", "remote-proof");
        p.put("advance_to", "{{ ds }}");
        p.put("target_scope", "pipeline");
        p.put("rollback_trigger", "deploy_failure");
        return p;
    }
}
