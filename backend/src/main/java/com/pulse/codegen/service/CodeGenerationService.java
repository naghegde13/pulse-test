package com.pulse.codegen.service;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.GxCodeGenerator;
import com.pulse.codegen.audit.IngestionAuditColumns;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.scan.ForbiddenTokenScanner;
import com.pulse.common.text.DateMnemonic;
import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.repository.GenerationRunRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.broker.mirror.RemoteTargetRuntimeMirror;
import com.pulse.broker.mirror.RemoteTargetRuntimeMirrorRepository;
import com.pulse.git.service.GitCommitService;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.opengine.OpList;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ParamResolver;
import com.pulse.pipeline.opengine.ParamSurface;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.secret.service.SecretReferenceService;
import com.pulse.secret.service.SecretReferenceService.RuntimeSecretBinding;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.storage.PathConventionService;
import com.pulse.storage.contract.service.TableContractService;
import com.pulse.storage.model.FileLifecycle;
import com.pulse.storage.model.LakeFormat;
import com.pulse.storage.model.LakeLayer;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.model.TableLocation;
import com.pulse.storage.repository.StorageBackendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CodeGenerationService {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationService.class);
    private final GenerationRunRepository runRepo;
    private final GeneratedArtifactRepository artifactRepo;
    private final PipelineRepository pipelineRepo;
    private final PipelineVersionRepository versionRepo;
    private final SubPipelineInstanceRepository instanceRepo;
    private final BlueprintRepository blueprintRepo;
    private final CredentialProfileRepository credentialProfileRepo;
    private final ConnectorInstanceRepository connectorInstanceRepo;
    private final PortWiringRepository wiringRepo;
    private final DatasetRepository datasetRepo;
    private final CompilePlanService compilePlanService;
    private final SecretReferenceService secretReferenceService;
    private final GxCodeGenerator gxCodeGenerator;
    private final GitCommitService gitCommitService;
    private final SystemOfRecordRepository sorRepo;
    private final DomainRepository domainRepo;
    private final StorageBackendRepository storageBackendRepo;
    private final PathConventionService pathConventionService;
    private final RuntimeAuthorityService runtimeAuthorityService;
    private final TableContractService tableContractService;
    private final RemoteTargetRuntimeMirrorRepository remoteTargetRuntimeMirrorRepository;
    /**
     * The deterministic codegen op-engine (SPEC #2 §C, ADR 0013). Injected as the
     * single seam that the post-V153 cutover + the Calcite lane plug into: it composes a
     * blueprint's compute artifact from its op-list handlers (no LLM). It is wired here now
     * (clean, churn-free) so downstream lanes have a stable call site; the per-blueprint
     * codegen branches below are NOT yet ripped out (they keep working until V153 seeds the
     * op-lists — the same producer-before-consumer cutover as the schema side, IMPL-builder §3).
     */
    private final com.pulse.codegen.opengine.CodegenOpEngine codegenOpEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CodeGenerationService(GenerationRunRepository runRepo,
                                  GeneratedArtifactRepository artifactRepo,
                                  PipelineRepository pipelineRepo,
                                  PipelineVersionRepository versionRepo,
                                  SubPipelineInstanceRepository instanceRepo,
                                  BlueprintRepository blueprintRepo,
                                  CredentialProfileRepository credentialProfileRepo,
                                  ConnectorInstanceRepository connectorInstanceRepo,
                                  PortWiringRepository wiringRepo,
                                  DatasetRepository datasetRepo,
                                  CompilePlanService compilePlanService,
                                  SecretReferenceService secretReferenceService,
                                  GxCodeGenerator gxCodeGenerator,
                                  GitCommitService gitCommitService,
                                  SystemOfRecordRepository sorRepo,
                                  DomainRepository domainRepo,
                                  StorageBackendRepository storageBackendRepo,
                                  PathConventionService pathConventionService,
                                  RuntimeAuthorityService runtimeAuthorityService,
                                  TableContractService tableContractService,
                                  RemoteTargetRuntimeMirrorRepository remoteTargetRuntimeMirrorRepository,
                                  com.pulse.codegen.opengine.CodegenOpEngine codegenOpEngine) {
        this.runRepo = runRepo;
        this.artifactRepo = artifactRepo;
        this.pipelineRepo = pipelineRepo;
        this.versionRepo = versionRepo;
        this.instanceRepo = instanceRepo;
        this.blueprintRepo = blueprintRepo;
        this.credentialProfileRepo = credentialProfileRepo;
        this.connectorInstanceRepo = connectorInstanceRepo;
        this.wiringRepo = wiringRepo;
        this.datasetRepo = datasetRepo;
        this.compilePlanService = compilePlanService;
        this.secretReferenceService = secretReferenceService;
        this.gxCodeGenerator = gxCodeGenerator;
        this.gitCommitService = gitCommitService;
        this.sorRepo = sorRepo;
        this.domainRepo = domainRepo;
        this.storageBackendRepo = storageBackendRepo;
        this.pathConventionService = pathConventionService;
        this.runtimeAuthorityService = runtimeAuthorityService;
        this.tableContractService = tableContractService;
        this.remoteTargetRuntimeMirrorRepository = remoteTargetRuntimeMirrorRepository;
        this.codegenOpEngine = codegenOpEngine;
    }

    /**
     * Accessor for the deterministic codegen op-engine seam (SPEC #2 §C). The post-V153
     * cutover and the Calcite lane (sql-model rule-27 emission, SourceSQL source-prepare,
     * {@code [[ ]]} mnemonic lowering) compose blueprint compute artifacts through this engine
     * instead of the per-blueprint branches below. Exposed so those lanes plug in without
     * churning this class's wiring.
     */
    com.pulse.codegen.opengine.CodegenOpEngine codegenOpEngine() {
        return codegenOpEngine;
    }

    /**
     * PKT-0027: Per-generation, per-thread tracker for secret-shaped fields
     * that were redacted during code generation. Uses ThreadLocal so concurrent
     * generation calls on different request threads cannot race or contaminate
     * each other's redaction evidence. Cleaned up in a finally block at the
     * end of each generate() call to prevent thread-pool residue.
     */
    private static final ThreadLocal<List<String>> REDACTED_SECRET_FIELDS = new ThreadLocal<>();

    private String resolveCodegenEnvironment() {
        return "dev";
    }

    private String resolveDefaultBackend() {
        var allowed = runtimeAuthorityService.getAuthority().allowedStorageBackends();
        return allowed.isEmpty() ? "DPC" : allowed.iterator().next();
    }

    public GenerationRun generate(String pipelineId, String versionId, String tenantId, String userId) {
        return generate(pipelineId, versionId, tenantId, userId, true);
    }

    public GenerationRun generateToWorkspace(String pipelineId, String versionId, String tenantId, String userId) {
        return generate(pipelineId, versionId, tenantId, userId, false);
    }

    private GenerationRun generate(String pipelineId, String versionId, String tenantId, String userId,
                                   boolean commitGeneratedCode) {
        REDACTED_SECRET_FIELDS.set(new ArrayList<>());  // PKT-0027: thread-local per-generation tracker
        try {
        Pipeline pipeline = pipelineRepo.findById(pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", pipelineId));
        PipelineVersion version = versionRepo.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineVersion", versionId));
        List<SubPipelineInstance> instances = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId);

        GenerationRun run = new GenerationRun();
        run.setPipelineId(pipelineId);
        run.setVersionId(versionId);
        run.setTenantId(tenantId);
        run.setTriggeredBy(userId);
        run.setStatus("GENERATING");
        run.setStartedAt(Instant.now());
        run = runRepo.save(run);

        try {
            List<GeneratedArtifact> artifacts = new ArrayList<>();
            String slug = slugify(pipeline.getName());
            List<PortWiring> wirings = wiringRepo.findByVersionIdOrderByCreatedAtAsc(versionId);
            var compilePlan = compilePlanService.build(pipeline, versionId, instances, wirings);
            prepareAdvanceTimeRuntimeContracts(compilePlan, tenantId);

            artifacts.add(generateAirflowDag(run, pipeline, version, instances, slug, compilePlan));
            artifacts.addAll(generatePySparkJobs(run, instances, slug));
            artifacts.addAll(generateSinkJobs(run, instances, slug, compilePlan));
            artifacts.addAll(generateGxCheckpoints(run, instances, slug, compilePlan));
            artifacts.addAll(generateDbtModels(run, instances, slug, compilePlan));
            artifacts.addAll(generateDbtSources(run, instances, slug, compilePlan));
            artifacts.add(generateConfig(run, pipeline, version, instances, slug));
            artifacts.add(generateRequirements(run, slug));
            artifacts.addAll(generateRuntimeSecretSupport(run, instances));
            artifacts.addAll(generateAdvanceTimeRuntimeSupport(run, compilePlan));
            // SPEC #6 §c.5: ensure the pulse_dates resolver is shipped when any instance
            // carries an inline [[ … ]] mnemonic (the advance-time path may have emitted
            // it already; emit-if-absent dedups by path).
            if (anyInstanceUsesMnemonics(instances)) {
                addArtifactIfAbsent(artifacts, createArtifact(run,
                        "pulse_dates/__init__.py", "RUNTIME_SUPPORT",
                        readRuntimeHelperResource("runtime-helpers/pulse_dates/__init__.py"),
                        "pulse_dates"));
            }
            artifacts.addAll(generateBrokerInvocationArtifacts(run, instances, slug));
            artifacts.add(generateCompilePlanArtifact(run, compilePlan));
            artifacts.addAll(generateSelectorArtifacts(run, slug, compilePlan));
            artifacts.add(generateGoldPublishContractArtifact(run, compilePlan));

            applyNamespace(artifacts, compilePlan.namespace());

            // SPEC #6 §c.6 (load-bearing): NO inline [[ ]] mnemonic survives into any
            // emitted artifact — dbt lowered to {{ var(...) }}, others substituted. Any
            // residue is a codegen bug; loud-fail rather than ship an unresolved token.
            assertNoMnemonicResidue(artifacts);

            preserveManualEdits(run, artifacts, versionId);
            artifactRepo.saveAll(artifacts);

            // ARCH-005: Generate table contracts for materializing instances
            try {
                String domainId = pipeline.getDomainId() != null ? pipeline.getDomainId() : "";
                String domainSlug = compilePlan.domainName() != null
                        ? compilePlan.domainName().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "")
                        : "default";
                tableContractService.generateContracts(
                        pipelineId, versionId, instances, List.of(), domainId, domainSlug);
            } catch (Exception e) {
                log.warn("Table contract generation failed for version {}: {}", versionId, e.getMessage());
            }

            // PKT-0027: Post-generation forbidden-token scan and runnability assessment
            Map<String, List<String>> forbiddenTokenViolations = ForbiddenTokenScanner.scan(artifacts);
            Map<String, List<RuntimeSecretBinding>> secretManifest = buildRuntimeSecretManifest(instances);
            List<String> allPlaceholders = new ArrayList<>();
            for (var artifact : artifacts) {
                // PKT-0027: Only scan executable code artifacts for credential/config
                // placeholders. DBT models, snapshots, and config files may legitimately
                // use Jinja template variables that look like placeholders.
                if (artifact.getContent() != null
                        && Set.of("PYSPARK_JOB", "AIRFLOW_DAG")
                        .contains(artifact.getFileType())) {
                    allPlaceholders.addAll(ForbiddenTokenScanner.scanForPlaceholders(artifact.getContent()));
                }
            }
            boolean hasUnresolvedPlaceholders = !allPlaceholders.isEmpty();
            boolean hasForbiddenTokens = !forbiddenTokenViolations.isEmpty();
            List<String> redactedSecretFields = REDACTED_SECRET_FIELDS.get();
            boolean hasRedactedSecrets = redactedSecretFields != null && !redactedSecretFields.isEmpty();
            boolean liveRunnable = !hasUnresolvedPlaceholders && !hasForbiddenTokens && !hasRedactedSecrets;

            run.setStatus("COMPLETED");
            run.setCompletedAt(Instant.now());
            Map<String, Object> runMetadata = new LinkedHashMap<>();
            runMetadata.put("artifact_count", artifacts.size());
            runMetadata.put("compile_namespace", compilePlan.namespace());
            runMetadata.put("compile_plan_snapshot", compilePlanAsMap(compilePlan));
            // PKT-0027: Secret manifest and runnability metadata
            runMetadata.put("secretManifestComplete", !secretManifest.isEmpty());
            runMetadata.put("secretManifestTaskCount", secretManifest.size());
            runMetadata.put("liveRunnable", liveRunnable);
            List<String> codegenOpEngineInstances = artifacts.stream()
                    .map(GeneratedArtifact::getMetadata)
                    .filter(Objects::nonNull)
                    .filter(metadata -> "CodegenOpEngine".equals(metadata.get("codegenEngine")))
                    .flatMap(metadata -> {
                        Object many = metadata.get("instanceIds");
                        if (many instanceof List<?> list) {
                            return list.stream().filter(Objects::nonNull).map(Object::toString);
                        }
                        Object one = metadata.get("instanceId");
                        return one == null ? java.util.stream.Stream.<String>empty()
                                : java.util.stream.Stream.of(one.toString());
                    })
                    .distinct()
                    .toList();
            if (!codegenOpEngineInstances.isEmpty()) {
                runMetadata.put("codegenOpEngineInstances", codegenOpEngineInstances);
            }
            if (hasUnresolvedPlaceholders) {
                runMetadata.put("unresolvedPlaceholders", allPlaceholders.stream().distinct().toList());
            }
            if (hasForbiddenTokens) {
                runMetadata.put("forbiddenTokenViolations", forbiddenTokenViolations);
            }
            if (hasRedactedSecrets) {
                runMetadata.put("redactedSecretFields", redactedSecretFields.stream().distinct().toList());
            }
            run.setMetadata(runMetadata);
        } catch (Exception e) {
            run.setStatus("FAILED");
            run.setErrorMessage(e.getMessage());
            run.setCompletedAt(Instant.now());
        }

        GenerationRun saved = runRepo.save(run);

        if (commitGeneratedCode && "COMPLETED".equals(saved.getStatus())) {
            try {
                gitCommitService.commitGeneratedCode(tenantId, saved.getId());
            } catch (Exception e) {
                log.warn("Git commit after code generation failed for run {}: {}",
                        saved.getId(), e.getMessage());
            }
        }

        return saved;
        } finally {
            REDACTED_SECRET_FIELDS.remove();  // PKT-0027: prevent thread-pool residue
        }
    }

    public List<GenerationRun> listRuns(String versionId) {
        return runRepo.findByVersionIdOrderByCreatedAtDesc(versionId);
    }

    public List<GeneratedArtifact> getArtifacts(String runId) {
        return artifactRepo.findByGenerationRunIdOrderByFilePathAsc(runId);
    }

    private GeneratedArtifact generateAirflowDag(GenerationRun run, Pipeline pipeline,
                                                   PipelineVersion version,
                                                   List<SubPipelineInstance> instances,
                                                   String slug,
                                                   CompilePlanService.CompilePlanSnapshot compilePlan) {
        String dagId = String.format("pulse_%s_v%d", slug, version.getRevision());
        String tenantSlug = pipeline.getTenantId() != null
                ? pipeline.getTenantId().replace("tenant-", "")
                : "unknown";
        String domain = pipeline.getDomainName() != null
                ? slugify(pipeline.getDomainName()) : "default";

        String schedule = version.getScheduleCron() != null
                ? version.getScheduleCron() : "@daily";
        boolean catchup = Boolean.TRUE.equals(version.getCatchupEnabled());
        int maxActiveRuns = version.getMaxActiveRuns() != null
                ? version.getMaxActiveRuns() : 1;
        boolean dependsOnPast = Boolean.TRUE.equals(version.getDependsOnPast());

        List<PortWiring> allWirings = wiringRepo.findByVersionIdOrderByCreatedAtAsc(
                version.getId());

        Map<String, SubPipelineInstance> instanceById = new LinkedHashMap<>();
        for (var inst : instances) instanceById.put(inst.getId(), inst);

        // Build adjacency for wiring-based dependencies
        Map<String, Set<String>> upstreamMap = new LinkedHashMap<>();
        for (var w : allWirings) {
            upstreamMap.computeIfAbsent(w.getTargetInstanceId(), k -> new LinkedHashSet<>())
                    .add(w.getSourceInstanceId());
        }

        // Determine which instances have DQ expectations
        Set<String> instancesWithDq = new HashSet<>();
        for (var inst : instances) {
            if (inst.getDqExpectations() != null && !inst.getDqExpectations().isEmpty()) {
                instancesWithDq.add(inst.getId());
            }
        }

        // Collect output datasets for cross-pipeline Dataset outlets
        List<String> outletDatasetUris = new ArrayList<>();
        for (var inst : instances) {
            if (inst.getOutputDatasets() != null) {
                for (var ds : inst.getOutputDatasets()) {
                    String ref = (String) ds.get("ref");
                    if (ref != null) outletDatasetUris.add(ref);
                }
            }
        }

        Map<String, List<RuntimeSecretBinding>> runtimeSecretBindingsByTask = new LinkedHashMap<>();
        for (var inst : instances) {
            var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
            List<RuntimeSecretBinding> bindings = collectRuntimeSecretBindings(resolveCredentialProfile(params));
            if (!bindings.isEmpty()) {
                runtimeSecretBindingsByTask.put(slugify(inst.getName()), bindings);
            }
        }
        boolean hasRuntimeSecretBindings = !runtimeSecretBindingsByTask.isEmpty();
        // SPEC #6 §c.5: emit the pulse_dates-backed mnemonic resolver macro only when
        // an instance carries an inline [[ … ]] mnemonic.
        boolean hasDateMnemonics = anyInstanceUsesMnemonics(instances);
        boolean hasRemotePipelineInvocation = instances.stream()
                .anyMatch(inst -> "RemotePipelineInvocation".equals(inst.getBlueprintKey()));
        boolean hasAdvanceTimeDimension = instances.stream()
                .anyMatch(inst -> "AdvanceTimeDimension".equals(inst.getBlueprintKey()));
        boolean hasDagOnlyOpEngineControls = instances.stream()
                .anyMatch(inst -> blueprintRepo.findByBlueprintKey(inst.getBlueprintKey())
                        .map(bp -> OpList.isOpList(bp.getSchemaBehavior())
                                && OpList.parse(bp.getSchemaBehavior()).emission().compute() == null)
                        .orElse(false));

        StringBuilder dag = new StringBuilder();

        // Imports
        dag.append("from airflow import DAG\n");
        dag.append("from airflow.datasets import Dataset\n");
        dag.append("from airflow.utils.task_group import TaskGroup\n");
        dag.append("from airflow.providers.apache.spark.operators.spark_submit import SparkSubmitOperator\n");
        dag.append("from airflow.operators.python import PythonOperator\n");
        dag.append("from airflow.operators.bash import BashOperator\n");
        dag.append("from airflow.operators.empty import EmptyOperator\n");
        if (hasRuntimeSecretBindings) {
            dag.append("from runtime.pulse_secret_resolver import cleanup_runtime_secret_files, resolve_runtime_secret_env\n");
        }
        if (hasDateMnemonics) {
            dag.append("import pulse_dates\n");
        }
        if (hasRemotePipelineInvocation) {
            dag.append("from pulse_remote_airflow_invoke import RemoteAirflowDagRunOperator\n");
        }
        if (hasAdvanceTimeDimension) {
            dag.append("from pulse_airflow_runtime.time_state import AdvanceTimeDimensionOperator\n");
        }
        if (hasDagOnlyOpEngineControls) {
            dag.append("from airflow.sensors.filesystem import FileSensor\n");
            dag.append("from airflow.providers.google.cloud.sensors.gcs import GCSObjectExistenceSensor\n");
            dag.append("from airflow.providers.common.sql.sensors.sql import SqlSensor\n");
            dag.append("from airflow.sensors.external_task import ExternalTaskSensor\n");
            dag.append("from airflow.operators.trigger_dagrun import TriggerDagRunOperator\n");
        }

        // Sensor imports based on what the instances need
        Set<String> sensorImports = new LinkedHashSet<>();
        for (var inst : instances) {
            String explicitSensorKey = inst.getBlueprintKey() != null ? inst.getBlueprintKey() : "";
            switch (explicitSensorKey) {
                case "FileArrivalSensor" -> {
                    String storageKind = stringParam(inst.getParams(), "storage_kind", "s3");
                    if ("sftp".equalsIgnoreCase(storageKind)) {
                        sensorImports.add("from airflow.providers.sftp.sensors.sftp import SFTPSensor\n");
                    } else {
                        sensorImports.add("from airflow.providers.amazon.aws.sensors.s3 import S3KeySensor\n");
                    }
                }
                case "ObjectStoreKeySensor" ->
                        sensorImports.add("from airflow.providers.amazon.aws.sensors.s3 import S3KeySensor\n");
                case "DatabaseReadinessSensor" ->
                        sensorImports.add("from airflow.providers.common.sql.sensors.sql import SqlSensor\n");
                case "DatasetDependencySensor" ->
                        sensorImports.add("from airflow.sensors.external_task import ExternalTaskSensor\n");
                case "ExternalEventSensor" ->
                        sensorImports.add("from airflow.sensors.python import PythonSensor\n");
            }
            Dataset sourceDataset = resolveSourceDataset(inst);
            if (sourceDataset != null && sourceDataset.getSensingStrategy() != null) {
                switch (sourceDataset.getSensingStrategy()) {
                    case "file" -> {
                        ConnectorInstance ci = resolveConnectorInstance(inst.getParams());
                        String connLabel = ci != null ? ci.getName().toLowerCase() : "";
                        if (connLabel.contains("sftp")) {
                            sensorImports.add("from airflow.providers.sftp.sensors.sftp import SFTPSensor\n");
                        } else {
                            sensorImports.add("from airflow.providers.amazon.aws.sensors.s3 import S3KeySensor\n");
                        }
                    }
                    case "sql_query" ->
                            sensorImports.add("from airflow.providers.common.sql.sensors.sql import SqlSensor\n");
                }
            }
        }
        for (String imp : sensorImports) dag.append(imp);

        dag.append("from datetime import datetime, timedelta\n\n");

        if (hasDateMnemonics) {
            // SPEC #6 §c.5: template-visible mnemonic resolver. Resolves a [[ ]] mnemonic
            // to an ISO date string at Airflow render time via pulse_dates (Airflow context
            // + pinned calendar bundle only — never the PULSE DB). Registered as a
            // user_defined_macro on the DAG so {{ pulse_resolve_mnemonic('PBD-1', ds) }}
            // renders inside dbt --vars and PySpark/SourceSQL SQL bodies.
            dag.append("def pulse_resolve_mnemonic(mnemonic, ds):\n");
            dag.append("    return pulse_dates.resolve_mnemonic(\n");
            dag.append("        mnemonic, as_of=datetime.strptime(ds, '%Y-%m-%d').date()\n");
            dag.append("    ).isoformat()\n\n");
        }

        if (hasRuntimeSecretBindings) {
            dag.append("def pulse_run_spark_job(application, secret_bindings=None, spark_conf=None, **context):\n");
            dag.append("    runtime_files = []\n");
            dag.append("    env_vars = {}\n");
            dag.append("    try:\n");
            dag.append("        if secret_bindings:\n");
            dag.append("            env_vars, runtime_files = resolve_runtime_secret_env(secret_bindings)\n");
            dag.append("        SparkSubmitOperator(\n");
            dag.append("            task_id=f\"{context['task'].task_id}__spark_submit\",\n");
            dag.append("            application=application,\n");
            dag.append("            conn_id='spark_default',\n");
            dag.append("            conf=spark_conf or {'spark.sql.adaptive.enabled': 'true'},\n");
            dag.append("            env_vars=env_vars or None,\n");
            dag.append("        ).execute(context)\n");
            dag.append("    finally:\n");
            dag.append("        cleanup_runtime_secret_files(runtime_files)\n\n");
        }

        // Cross-pipeline Dataset declarations
        if (!outletDatasetUris.isEmpty()) {
            dag.append("# Cross-pipeline Dataset declarations (Airflow 2.4+)\n");
            for (int i = 0; i < outletDatasetUris.size(); i++) {
                dag.append(String.format("outlet_dataset_%d = Dataset('%s')\n", i, outletDatasetUris.get(i)));
            }
            dag.append("\n");
        }

        // Default args
        dag.append("default_args = {\n");
        dag.append("    'owner': 'pulse',\n");
        dag.append(String.format("    'depends_on_past': %s,\n", dependsOnPast ? "True" : "False"));
        dag.append("    'email_on_failure': True,\n");
        dag.append("    'retries': 3,\n");
        dag.append("    'retry_delay': timedelta(minutes=5),\n");
        dag.append("}\n\n");

        // DAG definition
        boolean isPreset = schedule.startsWith("@");
        String scheduleStr = isPreset
                ? String.format("'%s'", schedule)
                : String.format("'%s'", schedule);

        dag.append(String.format("with DAG(\n"));
        dag.append(String.format("    dag_id='%s',\n", dagId));
        dag.append(String.format("    description='%s',\n",
                escapePython(pipeline.getDescription() != null ? pipeline.getDescription() : pipeline.getName())));
        dag.append("    default_args=default_args,\n");
        dag.append(String.format("    schedule=%s,\n", scheduleStr));
        dag.append("    start_date=datetime(2026, 1, 1),\n");
        dag.append(String.format("    catchup=%s,\n", catchup ? "True" : "False"));
        dag.append(String.format("    max_active_runs=%d,\n", maxActiveRuns));
        dag.append(String.format("    tags=['pulse', '%s', '%s'],\n", tenantSlug, domain));
        if (hasDateMnemonics) {
            dag.append("    user_defined_macros={'pulse_resolve_mnemonic': pulse_resolve_mnemonic},\n");
        }
        dag.append(") as dag:\n\n");

        // Generate TaskGroups for each sub-pipeline instance
        Map<String, String> instanceIdToTgVar = new LinkedHashMap<>();
        for (var inst : instances) {
            Blueprint bp = blueprintRepo.findByBlueprintKey(inst.getBlueprintKey()).orElse(null);
            String category = bp != null ? bp.getCategory().name() : "UNKNOWN";
            String taskSlug = slugify(inst.getName());
            String tgVar = "tg_" + taskSlug;
            instanceIdToTgVar.put(inst.getId(), tgVar);

            dag.append(String.format("    with TaskGroup('%s') as %s:\n", taskSlug, tgVar));

            // Sensor task (for INGESTION with sensing_strategy)
            boolean hasSensor = false;
            if ("INGESTION".equals(category)) {
                Dataset sourceDataset = resolveSourceDataset(inst);
                if (sourceDataset != null && sourceDataset.getSensingStrategy() != null
                        && !"none".equals(sourceDataset.getSensingStrategy())) {
                    hasSensor = true;
                    String sensorTaskId = "sense_" + taskSlug;
                    int pokeInterval = 300;
                    int timeout = 3600;
                    String mode = "reschedule";
                    if (sourceDataset.getSensorConfig() != null) {
                        var sc = sourceDataset.getSensorConfig();
                        if (sc.get("poke_interval") != null)
                            pokeInterval = ((Number) sc.get("poke_interval")).intValue();
                        if (sc.get("timeout") != null)
                            timeout = ((Number) sc.get("timeout")).intValue();
                        if (sc.get("mode") != null)
                            mode = sc.get("mode").toString();
                    }

                    switch (sourceDataset.getSensingStrategy()) {
                        case "file" -> {
                            ConnectorInstance ci = resolveConnectorInstance(inst.getParams());
                            String connLabel = ci != null ? ci.getName().toLowerCase() : "";
                            if (connLabel.contains("sftp")) {
                                String path = sourceDataset.getFileNamingPattern() != null
                                        ? sourceDataset.getFileNamingPattern()
                                        : "/incoming/{{ ds }}/*";
                                dag.append(String.format("        %s = SFTPSensor(\n", sensorTaskId));
                                dag.append(String.format("            task_id='%s',\n", sensorTaskId));
                                dag.append(String.format("            path='%s',\n", escapePython(path)));
                                dag.append("            sftp_conn_id='sftp_default',\n");
                                dag.append(String.format("            poke_interval=%d,\n", pokeInterval));
                                dag.append(String.format("            timeout=%d,\n", timeout));
                                dag.append(String.format("            mode='%s',\n", mode));
                                dag.append("        )\n\n");
                            } else {
                                // Per #80: object-storage paths resolve at codegen via
                                // PathConventionService against the pipeline + storage_backend
                                // context. The connector itself is identity-only.
                                String resolved = resolveObjectStoragePath(
                                        inst, slug, run.getTenantId(), FileLifecycle.SRC);
                                String bucket = "__PULSE_BUCKET_UNRESOLVED__";
                                String prefix = "__PULSE_PREFIX_UNRESOLVED__/";
                                if (resolved != null) {
                                    // resolved looks like 's3a://{bucket}/{domain}/{sor}/{pipeline}/SRC/'
                                    // The S3KeySensor takes bucket_name + bucket_key separately,
                                    // so split on the first slash after the scheme + bucket.
                                    String stripped = resolved.replaceFirst("^[a-z0-9]+a?://", "");
                                    int slash = stripped.indexOf('/');
                                    if (slash > 0) {
                                        bucket = stripped.substring(0, slash);
                                        prefix = stripped.substring(slash + 1) + "{{ ds }}/";
                                    }
                                } else {
                                    dag.append(String.format("        # WARNING: object-storage path unresolved for %s\n", sensorTaskId));
                                }
                                dag.append(String.format("        %s = S3KeySensor(\n", sensorTaskId));
                                dag.append(String.format("            task_id='%s',\n", sensorTaskId));
                                dag.append(String.format("            bucket_name='%s',\n", bucket));
                                dag.append(String.format("            bucket_key='%s*',\n", prefix));
                                dag.append("            wildcard_match=True,\n");
                                dag.append("            aws_conn_id='aws_default',\n");
                                dag.append(String.format("            poke_interval=%d,\n", pokeInterval));
                                dag.append(String.format("            timeout=%d,\n", timeout));
                                dag.append(String.format("            mode='%s',\n", mode));
                                dag.append("        )\n\n");
                            }
                        }
                        case "sql_query" -> {
                            String sql = sourceDataset.getReadinessQuery() != null
                                    ? sourceDataset.getReadinessQuery()
                                    : "SELECT COUNT(*) FROM data_table WHERE load_date = '{{ ds }}'";
                            dag.append(String.format("        %s = SqlSensor(\n", sensorTaskId));
                            dag.append(String.format("            task_id='%s',\n", sensorTaskId));
                            dag.append("            conn_id='pulse_sql_default',\n");
                            dag.append(String.format("            sql=\"%s\",\n", escapePython(sql)));
                            dag.append(String.format("            poke_interval=%d,\n", pokeInterval));
                            dag.append(String.format("            timeout=%d,\n", timeout));
                            dag.append(String.format("            mode='%s',\n", mode));
                            dag.append("        )\n\n");
                        }
                    }
                }
            }

            // Main task
            String mainTaskId = taskSlug;
            if (bp != null && OpList.isOpList(bp.getSchemaBehavior())) {
                OpList opList = OpList.parse(bp.getSchemaBehavior());
                if (opList.emission().compute() == null) {
                    dag.append("        # Codegen engine: CodegenOpEngine\n");
                    dag.append("        # DAG-only blueprint: ").append(bp.getBlueprintKey()).append("\n");
                    for (OpList.OpEntry op : opList.ops()) {
                        if (!codegenOpEngine.registry().has(op.op(), EmissionEngine.DAG_ONLY)) {
                            throw new IllegalStateException("Missing CodegenOpEngine DAG_ONLY handler for op "
                                    + op.op() + " on " + bp.getBlueprintKey());
                        }
                        Map<String, Object> resolved = resolveDagOnlyOpConfig(op, inst, mainTaskId);
                        String fragment = codegenOpEngine.dagOnly().emit(op, EmitContext.builder()
                                .mode(codegenOpEngine.modeResolver().activeMode())
                                .modeResolver(codegenOpEngine.modeResolver())
                                .config(new ResolvedConfig(resolved))
                                .lakeLayer(stringParam(inst.getParams(), "lake_layer", "control_plane"))
                                .build());
                        for (String line : fragment.split("\\R", -1)) {
                            if (!line.isBlank()) {
                                dag.append("        ").append(line).append("\n");
                            }
                        }
                    }
                    dag.append("\n");
                    continue;
                }
            }
            String jobSubdir = "INGESTION".equals(category) ? "ingestion" : "sink";
            String jobSuffix = "INGESTION".equals(category) ? "_ingest" : "_sink";
            if ("INGESTION".equals(category) || "DESTINATION".equals(category)) {
                List<RuntimeSecretBinding> secretBindings = runtimeSecretBindingsByTask.get(taskSlug);
                if (secretBindings != null && !secretBindings.isEmpty()) {
                    dag.append(String.format("        %s = PythonOperator(\n", mainTaskId));
                    dag.append(String.format("            task_id='%s',\n", mainTaskId));
                    dag.append("            python_callable=pulse_run_spark_job,\n");
                    dag.append(String.format("            op_kwargs={'application': 'jobs/%s/%s%s.py', ",
                            jobSubdir, taskSlug, jobSuffix));
                    dag.append(String.format("'secret_bindings': %s, ", pythonLiteral(secretBindings)));
                    dag.append("'spark_conf': {'spark.sql.adaptive.enabled': 'true'}},\n");
                    dag.append("        )\n\n");
                } else {
                    dag.append(String.format("        %s = SparkSubmitOperator(\n", mainTaskId));
                    dag.append(String.format("            task_id='%s',\n", mainTaskId));
                    dag.append(String.format("            application='jobs/%s/%s%s.py',\n",
                            jobSubdir, taskSlug, jobSuffix));
                    dag.append("            conn_id='spark_default',\n");
                    dag.append("            conf={'spark.sql.adaptive.enabled': 'true'},\n");
                    dag.append("            env_vars={'PULSE_BUSINESS_DATE': '{{ ds }}', 'PULSE_PROCESSING_TS': '{{ ts }}'},\n");
                    dag.append("        )\n\n");
                }
            } else if ("TRANSFORM".equals(category) || "MODELING".equals(category)) {
                // SPEC #6 §c.3: append --vars for this task's deduped [[ ]] mnemonics
                // (each pulse_<slug> resolves at runtime via the pulse_resolve_mnemonic
                // macro). No mnemonics -> emit the invocation byte-identical to today.
                String dbtVarsJson = buildDbtVarsJson(collectMnemonicSlugs(inst));
                dag.append(String.format("        %s = BashOperator(\n", mainTaskId));
                dag.append(String.format("            task_id='%s',\n", mainTaskId));
                if (dbtVarsJson == null) {
                    dag.append(String.format("            bash_command='cd /opt/dbt && dbt build --select tag:%s,tag:%s',\n",
                            slug, taskSlug));
                } else {
                    dag.append(String.format(
                            "            bash_command='cd /opt/dbt && dbt build --select tag:%s,tag:%s --vars '\"'\"'%s'\"'\"'',\n",
                            slug, taskSlug, dbtVarsJson));
                }
                dag.append("            env={'PULSE_BUSINESS_DATE': '{{ ds }}', 'PULSE_PROCESSING_TS': '{{ ts }}'},\n");
                dag.append("        )\n\n");
            } else if ("DATA_QUALITY".equals(category)) {
                dag.append(String.format("        %s = PythonOperator(\n", mainTaskId));
                dag.append(String.format("            task_id='%s',\n", mainTaskId));
                dag.append(String.format("            python_callable=lambda **ctx: __import__('subprocess').run(\n"));
                dag.append(String.format("                ['python', 'gx/checkpoints/%s_checkpoint.py'], check=True,\n",
                        taskSlug));
                dag.append("                env={**__import__('os').environ, 'PULSE_BUSINESS_DATE': ctx['ds'], 'PULSE_PROCESSING_TS': ctx['ts']}),\n");
                dag.append("            do_xcom_push=False,\n");
                dag.append("        )\n\n");
            } else if ("ORCHESTRATION".equals(category) && "AdvanceTimeDimension".equals(inst.getBlueprintKey())) {
                appendAdvanceTimeDimensionTask(dag, inst, mainTaskId, compilePlan);
            } else if ("ORCHESTRATION".equals(category) && "RemotePipelineInvocation".equals(inst.getBlueprintKey())) {
                appendRemotePipelineInvocationTask(dag, inst, taskSlug, mainTaskId);
            } else if ("ORCHESTRATION".equals(category) && isExplicitSensorBlueprint(inst.getBlueprintKey())) {
                appendExplicitSensorTask(dag, inst, taskSlug, mainTaskId);
            } else {
                dag.append(String.format("        %s = BashOperator(\n", mainTaskId));
                dag.append(String.format("            task_id='%s',\n", mainTaskId));
                dag.append(String.format("            bash_command='echo \"Running %s\"',\n", taskSlug));
                dag.append("        )\n\n");
            }

            // Per-instance GX expectations run inside the gx/checkpoints/ files; layer-level
            // gates below execute those checkpoint scripts between medallion layers.

            // Intra-TaskGroup wiring: sensor >> main
            if (hasSensor) {
                dag.append(String.format("        sense_%s >> %s\n\n", taskSlug, mainTaskId));
            }
        }

        // Layer-level GX gates: run between bronze/silver and silver/gold boundaries.
        Map<String, List<String>> tgVarsByLayer = new LinkedHashMap<>();
        tgVarsByLayer.put("bronze", new ArrayList<>());
        tgVarsByLayer.put("silver", new ArrayList<>());
        tgVarsByLayer.put("gold", new ArrayList<>());
        tgVarsByLayer.put("control_plane", new ArrayList<>());
        if (compilePlan != null) {
            for (Map<String, Object> node : compilePlan.nodes()) {
                String layer = node.get("resolvedLayer") != null ? node.get("resolvedLayer").toString() : "silver";
                String iid = node.get("instanceId").toString();
                String tgVar = instanceIdToTgVar.get(iid);
                if (tgVar != null) {
                    tgVarsByLayer.computeIfAbsent(layer, k -> new ArrayList<>()).add(tgVar);
                }
            }
        }
        boolean hasBronze = !tgVarsByLayer.getOrDefault("bronze", List.of()).isEmpty();
        boolean hasSilver = !tgVarsByLayer.getOrDefault("silver", List.of()).isEmpty();
        boolean hasGold = !tgVarsByLayer.getOrDefault("gold", List.of()).isEmpty();

        List<String> bronzeGateScripts = collectLayerCheckpointScripts(instances, compilePlan, "bronze");
        List<String> silverGateScripts = collectLayerCheckpointScripts(instances, compilePlan, "silver");

        if (hasBronze && hasSilver) {
            dag.append("    gx_bronze_silver_gate = PythonOperator(\n");
            dag.append("        task_id='gx_bronze_silver_gate',\n");
            dag.append("        python_callable=lambda **ctx: [__import__('subprocess').run(['python', p], check=True) for p in ");
            dag.append(pythonStringList(bronzeGateScripts));
            dag.append("],\n");
            dag.append("        do_xcom_push=False,\n");
            dag.append("    )\n");
        }
        if (hasSilver && hasGold) {
            dag.append("    gx_silver_gold_gate = PythonOperator(\n");
            dag.append("        task_id='gx_silver_gold_gate',\n");
            dag.append("        python_callable=lambda **ctx: [__import__('subprocess').run(['python', p], check=True) for p in ");
            dag.append(pythonStringList(silverGateScripts));
            dag.append("],\n");
            dag.append("        do_xcom_push=False,\n");
            dag.append("    )\n");
        }

        // Inter-TaskGroup wiring: intra-layer edges from port_wirings; cross-layer edges
        // are routed through the gx_bronze_silver_gate / gx_silver_gold_gate barriers.
        Map<String, String> instanceToLayer = new LinkedHashMap<>();
        if (compilePlan != null) {
            for (Map<String, Object> node : compilePlan.nodes()) {
                Object layerVal = node.get("resolvedLayer");
                instanceToLayer.put(node.get("instanceId").toString(),
                        layerVal != null ? layerVal.toString() : "silver");
            }
        }
        if (!allWirings.isEmpty()) {
            dag.append("    # Intra-layer task group dependencies (from port wirings)\n");
            Set<String> emittedEdges = new HashSet<>();
            for (var w : allWirings) {
                String srcTg = instanceIdToTgVar.get(w.getSourceInstanceId());
                String tgtTg = instanceIdToTgVar.get(w.getTargetInstanceId());
                String srcLayer = instanceToLayer.getOrDefault(w.getSourceInstanceId(), "silver");
                String tgtLayer = instanceToLayer.getOrDefault(w.getTargetInstanceId(), "silver");
                if (srcTg == null || tgtTg == null) continue;
                boolean gateHandlesEdge = ("bronze".equals(srcLayer) && "silver".equals(tgtLayer) && hasBronze && hasSilver)
                        || ("silver".equals(srcLayer) && "gold".equals(tgtLayer) && hasSilver && hasGold);
                if (gateHandlesEdge) {
                    continue;
                }
                String edge = srcTg + ">>" + tgtTg;
                if (emittedEdges.add(edge)) {
                    dag.append(String.format("    %s >> %s\n", srcTg, tgtTg));
                }
            }
        } else if (instances.size() > 1 && !hasBronze && !hasSilver && !hasGold) {
            dag.append("    # Sequential task group dependencies (no port wirings defined)\n");
            List<String> tgVars = new ArrayList<>(instanceIdToTgVar.values());
            for (int i = 0; i < tgVars.size() - 1; i++) {
                dag.append(String.format("    %s >> %s\n", tgVars.get(i), tgVars.get(i + 1)));
            }
        }

        if (hasBronze && hasSilver) {
            for (String bronzeTg : tgVarsByLayer.getOrDefault("bronze", List.of())) {
                dag.append(String.format("    %s >> gx_bronze_silver_gate\n", bronzeTg));
            }
            for (String silverTg : tgVarsByLayer.getOrDefault("silver", List.of())) {
                dag.append(String.format("    gx_bronze_silver_gate >> %s\n", silverTg));
            }
        }
        if (hasSilver && hasGold) {
            for (String silverTg : tgVarsByLayer.getOrDefault("silver", List.of())) {
                dag.append(String.format("    %s >> gx_silver_gold_gate\n", silverTg));
            }
            for (String goldTg : tgVarsByLayer.getOrDefault("gold", List.of())) {
                dag.append(String.format("    gx_silver_gold_gate >> %s\n", goldTg));
            }
        }

        // Publish Dataset outlets for cross-pipeline triggers
        if (!outletDatasetUris.isEmpty()) {
            dag.append("\n    # Publish cross-pipeline datasets\n");
            List<String> tgVars = new ArrayList<>(instanceIdToTgVar.values());
            String lastTg = tgVars.isEmpty() ? null : tgVars.get(tgVars.size() - 1);

            StringBuilder outletsList = new StringBuilder("[");
            for (int i = 0; i < outletDatasetUris.size(); i++) {
                if (i > 0) outletsList.append(", ");
                outletsList.append(String.format("outlet_dataset_%d", i));
            }
            outletsList.append("]");

            dag.append("    publish_datasets = EmptyOperator(\n");
            dag.append("        task_id='publish_datasets',\n");
            dag.append(String.format("        outlets=%s,\n", outletsList));
            dag.append("    )\n");
            if (lastTg != null) {
                dag.append(String.format("    %s >> publish_datasets\n", lastTg));
            }
        }

        GeneratedArtifact artifact = createArtifact(run, String.format("dags/%s_dag.py", slug),
                "AIRFLOW_DAG", dag.toString(), "airflow_dag");
        if (hasDagOnlyOpEngineControls) {
            List<String> controlInstanceIds = instances.stream()
                    .filter(inst -> blueprintRepo.findByBlueprintKey(inst.getBlueprintKey())
                            .map(bp -> OpList.isOpList(bp.getSchemaBehavior())
                                    && OpList.parse(bp.getSchemaBehavior()).emission().compute() == null)
                            .orElse(false))
                    .map(SubPipelineInstance::getId)
                    .toList();
            Map<String, Object> metadata = new LinkedHashMap<>(artifact.getMetadata());
            metadata.put("codegenEngine", "CodegenOpEngine");
            metadata.put("instanceIds", controlInstanceIds);
            metadata.put("blueprintSurface", "dag-only");
            artifact.setMetadata(metadata);
        }
        return artifact;
    }

    /**
     * Maps an ingestion blueprint key + connector label to the audit source
     * context. Used by IngestionAuditColumns.emitPyspark to decide how to
     * source _pulse_source_uri (input_file_name() for files, env var for
     * everything else).
     */
    private static IngestionAuditColumns.SourceContext auditSourceContextFor(
            String bpKey, String connLower) {
        if (bpKey == null) return IngestionAuditColumns.SourceContext.GENERIC;
        if ("FileIngestion".equals(bpKey)) return IngestionAuditColumns.SourceContext.FILE;
        if ("StreamIngestion".equals(bpKey)) return IngestionAuditColumns.SourceContext.STREAM;
        if ("ApiIngestion".equals(bpKey)) return IngestionAuditColumns.SourceContext.API;
        if ("SnapshotIngestion".equals(bpKey) || "BulkBackfill".equals(bpKey)
                || "CDCIngestion".equals(bpKey)) {
            return IngestionAuditColumns.SourceContext.JDBC;
        }
        return IngestionAuditColumns.SourceContext.GENERIC;
    }

    private Dataset resolveSourceDataset(SubPipelineInstance inst) {
        if (inst.getInputDatasets() != null) {
            for (var dsMap : inst.getInputDatasets()) {
                String ref = (String) dsMap.get("ref");
                if (ref != null) {
                    var datasets = datasetRepo.findAll();
                    for (var ds : datasets) {
                        if (ref.equals(ds.getQualifiedName())) return ds;
                    }
                }
            }
        }
        if (inst.getParams() != null) {
            Object dsId = inst.getParams().get("dataset_id");
            if (dsId instanceof String) {
                return datasetRepo.findById((String) dsId).orElse(null);
            }
            Object datasetIds = inst.getParams().get("dataset_ids");
            if (datasetIds instanceof List<?> ids && !ids.isEmpty()) {
                Object firstId = ids.get(0);
                if (firstId != null) {
                    return datasetRepo.findById(firstId.toString()).orElse(null);
                }
            }
        }
        return null;
    }

    private List<GeneratedArtifact> generatePySparkJobs(GenerationRun run,
                                                         List<SubPipelineInstance> instances,
                                                         String slug) {
        List<GeneratedArtifact> artifacts = new ArrayList<>();
        for (var inst : instances) {
            Blueprint bp = blueprintRepo.findByBlueprintKey(inst.getBlueprintKey()).orElse(null);
            if (bp == null || !"INGESTION".equals(bp.getCategory().name())) continue;

            String taskSlug = slugify(inst.getName());
            var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
            String connName = (String) params.getOrDefault("connector_name", "source");
            String bpKey = inst.getBlueprintKey() != null ? inst.getBlueprintKey() : "";

            if (OpList.isOpList(bp.getSchemaBehavior())) {
                GeneratedArtifact opEngineArtifact = generatePySparkArtifactWithOpEngine(
                        run, inst, bp, slug, "ingestion");
                artifacts.add(opEngineArtifact);
                continue;
            }

            // Build merged config: connector config_template + credential profile + instance params.
            // After V62 param deduplication, connection params (api_url, bootstrap_servers, etc.)
            // are no longer in blueprint params_schema -- they come from the connector instance.
            CredentialProfile credentialProfile = resolveCredentialProfile(params);
            Map<String, Object> mergedConfig = buildMergedConfig(params);
            Map<String, Object> connConfig = resolveConnectionConfig(credentialProfile);
            ConnectorInstance connectorInst = resolveConnectorInstance(params);
            String connLabel = connectorInst != null ? connectorInst.getName() : connName;
            boolean hasCreds = connConfig != null && !connConfig.isEmpty();

            // Collect secret-manager refs for environment variable documentation
            List<RuntimeSecretBinding> secretBindings = collectRuntimeSecretBindings(credentialProfile);
            boolean hasSecretRefs = !secretBindings.isEmpty();

            StringBuilder py = new StringBuilder();
            py.append("from pyspark.sql import SparkSession\n");
            py.append("from pyspark.sql.functions import *\n");
            py.append("from datetime import datetime\n");
            py.append("import os\n");
            // Additional imports for specific connector types
            if (bpKey.contains("rest") || bpKey.contains("api")) {
                py.append("import requests\n");
                py.append("import json\n");
            }
            if (bpKey.contains("sftp")) {
                py.append("import paramiko\n");
                py.append("from io import StringIO\n");
            }
            py.append("\n");
            py.append("PULSE_BUSINESS_DATE = os.environ.get('PULSE_BUSINESS_DATE', '{{ ds }}')\n");
            py.append("PULSE_PROCESSING_TS = os.environ.get('PULSE_PROCESSING_TS', '{{ ts }}')\n\n");

            // Document required environment variables sourced from Vault
            if (hasSecretRefs) {
                py.append("# ").append("=".repeat(70)).append("\n");
                py.append("# Runtime secret bindings (resolved by the orchestrator at task start):\n");
                for (var binding : secretBindings) {
                    py.append(String.format("#   %-40s <- %s [%s]\n",
                            binding.envVarName(),
                            binding.runtimeSecretRef(),
                            binding.deliveryMode().name()));
                }
                py.append("# ").append("=".repeat(70)).append("\n\n");
            }

            py.append("spark = SparkSession.builder \\\n");
            py.append(String.format("    .appName('%s_%s') \\\n", slug, taskSlug));
            py.append("    .config('spark.sql.adaptive.enabled', 'true') \\\n");
            py.append("    .getOrCreate()\n\n");

            // --- Connector-type-specific ingestion code ---
            String connLower = connLabel.toLowerCase();

            if ("SnapshotIngestion".equals(bpKey)
                    || "BulkBackfill".equals(bpKey)
                    || "CDCIngestion".equals(bpKey)
                    || "SourceSQL".equals(bpKey)) {
                // JDBC-based ingestion -- determine dialect from connector name
                if (hasCreds) {
                    String host = strVal(connConfig, "host", "localhost");
                    String port = strVal(connConfig, "port", "5432");
                    String jdbcUrl;
                    String driver;
                    if (connLower.contains("oracle")) {
                        String sid = strVal(connConfig, "sid",
                                strVal(connConfig, "service_name", "ORCL"));
                        jdbcUrl = String.format("jdbc:oracle:thin:@%s:%s:%s", host, port, sid);
                        driver = "oracle.jdbc.OracleDriver";
                    } else if (connLower.contains("postgres")) {
                        String db = strVal(connConfig, "database", "postgres");
                        jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, db);
                        driver = "org.postgresql.Driver";
                    } else if (connLower.contains("mysql")) {
                        String db = strVal(connConfig, "database", "mysql");
                        jdbcUrl = String.format("jdbc:mysql://%s:%s/%s", host, port, db);
                        driver = "com.mysql.cj.jdbc.Driver";
                    } else if (connLower.contains("mssql") || connLower.contains("sql server")) {
                        String db = strVal(connConfig, "database", "master");
                        jdbcUrl = String.format("jdbc:sqlserver://%s:%s;databaseName=%s", host, port, db);
                        driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
                    } else if (connLower.contains("snowflake")) {
                        String account = strVal(connConfig, "account", "${SNOWFLAKE_ACCOUNT}");
                        String db = strVal(connConfig, "database", "");
                        jdbcUrl = String.format("jdbc:snowflake://%s.snowflakecomputing.com/?db=%s", account, db);
                        driver = "net.snowflake.client.jdbc.SnowflakeDriver";
                    } else {
                        jdbcUrl = String.format("jdbc:generic://%s:%s", host, port);
                        driver = "${JDBC_DRIVER}";
                    }

                    py.append(String.format("# JDBC Ingestion -- %s (DEV)\n", escapePython(connLabel)));
                    py.append(String.format("jdbc_url = '%s'\n", jdbcUrl));
                    py.append(String.format("jdbc_driver = '%s'\n", driver));
                    py.append(String.format("jdbc_user = %s\n", pyValue("username", connConfig.get("username"))));
                    py.append(String.format("jdbc_password = %s\n\n", pyValue("password", connConfig.get("password"))));

                    py.append("df = spark.read \\\n");
                    py.append("    .format('jdbc') \\\n");
                    py.append("    .option('url', jdbc_url) \\\n");
                    py.append("    .option('driver', jdbc_driver) \\\n");
                    py.append("    .option('user', jdbc_user) \\\n");
                    py.append("    .option('password', jdbc_password) \\\n");
                    if ("BulkBackfill".equals(bpKey)) {
                        String fetchSize = strVal(params, "fetch_size", "10000");
                        String partitionColumn = firstNonBlankString(params, "partition_column", "partition_col");
                        String lowerBound = strVal(params, "lower_bound", "0");
                        String upperBound = firstNonBlankString(params, "upper_bound", "max_partition_value");
                        String numPartitions = strVal(params, "num_partitions", "8");
                        py.append(String.format("    .option('fetchsize', '%s') \\\n", escapePython(fetchSize)));
                        if (partitionColumn != null && !partitionColumn.isBlank()
                                && upperBound != null && !upperBound.isBlank()) {
                            py.append(String.format("    .option('partitionColumn', '%s') \\\n", escapePython(partitionColumn)));
                            py.append(String.format("    .option('lowerBound', '%s') \\\n", escapePython(lowerBound)));
                            py.append(String.format("    .option('upperBound', '%s') \\\n", escapePython(upperBound)));
                            py.append(String.format("    .option('numPartitions', '%s') \\\n", escapePython(numPartitions)));
                        }
                    }
                } else {
                    py.append("# JDBC Ingestion (credentials not configured -- using placeholders)\n");
                    py.append("df = spark.read \\\n");
                    py.append("    .format('jdbc') \\\n");
                    py.append(String.format("    .option('url', '${%s_JDBC_URL}') \\\n",
                            connName.toUpperCase().replace(" ", "_")));
                    py.append("    .option('driver', '${JDBC_DRIVER}') \\\n");
                    py.append("    .option('user', '${JDBC_USER}') \\\n");
                    py.append("    .option('password', '${JDBC_PASSWORD}') \\\n");
                }

                if ("SourceSQL".equals(bpKey)) {
                    String sourceQuery = substituteMnemonicsForRuntime(
                            strVal(params, "source_query", "SELECT * FROM ${SOURCE_TABLE}"));
                    py.append("    .option('query', ")
                            .append(pyValue("source_query", sourceQuery))
                            .append(") \\\n");
                } else if (params.containsKey("qualified_names")) {
                    @SuppressWarnings("unchecked")
                    var qns = (List<String>) params.get("qualified_names");
                    if (!qns.isEmpty()) {
                        py.append(String.format("    .option('dbtable', '%s') \\\n", qns.get(0)));
                    }
                } else {
                    py.append("    .option('dbtable', '${SOURCE_TABLE}') \\\n");
                }
                py.append("    .load()\n\n");

            } else if ("StreamIngestion".equals(bpKey)) {
                if (hasCreds) {
                    String secProtocol = strVal(connConfig, "security_protocol", "PLAINTEXT");

                    py.append(String.format("# Kafka Ingestion -- %s (DEV)\n", escapePython(connLabel)));
                            py.append(String.format("bootstrap_servers = %s\n",
                            pyValueWithComment("bootstrap_servers", connConfig.get("bootstrap_servers"))));
                    py.append(String.format("security_protocol = %s\n",
                            pyValueWithComment("security_protocol", connConfig.get("security_protocol"))));

                    Object saslUser = connConfig.get("sasl_username");
                    Object saslPass = connConfig.get("sasl_password");
                    if (saslUser != null) {
                        py.append(String.format("sasl_user = %s\n", pyValue("sasl_username", saslUser)));
                    }
                    if (saslPass != null) {
                        py.append(String.format("sasl_pass = %s\n", pyValue("sasl_password", saslPass)));
                    }
                    py.append("\n");

                    py.append("df = spark.readStream \\\n");
                    py.append("    .format('kafka') \\\n");
                    py.append("    .option('kafka.bootstrap.servers', bootstrap_servers) \\\n");
                    py.append("    .option('kafka.security.protocol', security_protocol) \\\n");

                    if ("SASL_SSL".equals(secProtocol) || "SASL_PLAINTEXT".equals(secProtocol)) {
                        py.append("    .option('kafka.sasl.mechanism', 'PLAIN') \\\n");
                        py.append("    .option('kafka.sasl.jaas.config',\n");
                        py.append("            f'org.apache.kafka.common.security.plain.PlainLoginModule required '\n");
                        py.append("            f'username=\"{sasl_user}\" password=\"{sasl_pass}\";') \\\n");
                    }

                    py.append("    .option('subscribe', '${KAFKA_TOPIC}') \\\n");
                    py.append("    .option('startingOffsets', 'earliest') \\\n");
                    py.append("    .load()\n\n");
                } else {
                    py.append("# Kafka Ingestion (credentials not configured -- using placeholders)\n");
                    py.append("df = spark.readStream \\\n");
                    py.append("    .format('kafka') \\\n");
                    py.append("    .option('kafka.bootstrap.servers', '${KAFKA_BROKERS}') \\\n");
                    py.append("    .option('subscribe', '${KAFKA_TOPIC}') \\\n");
                    py.append("    .option('startingOffsets', 'earliest') \\\n");
                    py.append("    .load()\n\n");
                }

            } else if ("FileIngestion".equals(bpKey)) {
                if (connLower.contains("sftp")) {
                    // SFTP file ingestion
                    if (hasCreds) {
                        py.append(String.format("# SFTP Ingestion -- %s (DEV)\n", escapePython(connLabel)));
                        py.append(String.format("sftp_host = %s\n", pyValueWithComment("host", connConfig.get("host"))));
                        py.append(String.format("sftp_user = %s\n", pyValue("username", connConfig.get("username"))));
                        if (secretReferenceService.isSecretReference(connConfig.get("private_key"))) {
                            py.append(String.format("sftp_key_path = %s\n\n", pyValue("private_key", connConfig.get("private_key"))));
                        } else {
                            py.append(String.format("sftp_key = %s\n\n", pyValue("private_key", connConfig.get("private_key"))));
                        }
                        py.append("ssh = paramiko.SSHClient()\n");
                        py.append("ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())\n");
                        if (secretReferenceService.isSecretReference(connConfig.get("private_key"))) {
                            py.append("pkey = paramiko.RSAKey.from_private_key_file(sftp_key_path)\n");
                        } else {
                            py.append("pkey = paramiko.RSAKey.from_private_key(StringIO(sftp_key))\n");
                        }
                        py.append("ssh.connect(sftp_host, username=sftp_user, pkey=pkey)\n");
                        py.append("sftp_client = ssh.open_sftp()\n\n");
                        py.append("local_path = '/tmp/pulse_sftp_staging/${FILE_NAME}'\n");
                        py.append("sftp_client.get('${REMOTE_PATH}', local_path)\n");
                        py.append("sftp_client.close()\n");
                        py.append("ssh.close()\n\n");
                        py.append("df = spark.read.format('csv').option('header', 'true').load(local_path)\n\n");
                    } else {
                        py.append("# SFTP Ingestion (credentials not configured)\n");
                        py.append("df = spark.read.format('csv').option('header', 'true').load('${SOURCE_PATH}')\n\n");
                    }
                } else {
                    // S3-compatible Object Storage Ingestion (covers S3 + GCS + Cloudera —
                    // post-V99 the connector_definition is unified as "S3-compatible Object Storage";
                    // the runtime cluster's Hadoop config decides the endpoint).
                    //
                    // Per #80: the connector_instance.config_template is empty for object-storage
                    // connectors. Path resolves at codegen time via PathConventionService using
                    // pipeline + storage_backend context. PULSE-emitted code does NOT set S3
                    // endpoint or credentials — those live in the cluster's spark-defaults.conf
                    // (workload identity for GCP, Kerberos for DPC, MinIO creds for local).
                    String objectStoragePath = resolveObjectStoragePath(
                            inst, slug, run.getTenantId(), FileLifecycle.SRC);
                    if (objectStoragePath == null) {
                        py.append("# WARNING: storage_backend not resolvable at codegen time. ")
                          .append("Verify the pipeline's storage_backend choice and that the ")
                          .append("storage_backends row exists for (tenant, dev, backend).\n");
                        py.append(String.format("# Connector: %s\n", escapePython(connLabel)));
                        objectStoragePath = "s3a://__PULSE_PATH_UNRESOLVED__/";
                    } else {
                        py.append(String.format("# Object Storage Ingestion — %s\n", escapePython(connLabel)));
                        py.append("# Path resolved by PULSE PathConventionService at codegen time.\n");
                    }
                    String sourceFormat = normalizeSparkSourceFormat(strVal(mergedConfig, "file_format",
                            strVal(mergedConfig, "source_format", "parquet")));
                    if ("csv".equals(sourceFormat)) {
                        appendCsvRead(py, objectStoragePath,
                                strVal(mergedConfig, "header", "true"),
                                strVal(mergedConfig, "infer_schema", "true"));
                    } else {
                        py.append("df = spark.read \\\n");
                        py.append(String.format("    .format('%s') \\\n", sourceFormat));
                        if ("parquet".equals(sourceFormat)) {
                            py.append("    .option('mergeSchema', 'true') \\\n");
                        }
                        py.append(String.format("    .load('%s')\n\n", objectStoragePath));
                    }
                }

            } else if ("ApiIngestion".equals(bpKey)) {
                if (hasCreds) {
                    // After V62 dedup: api_url, auth_type, pagination_type are inherited
                    // from the connector instance. Use mergedConfig which combines
                    // connector config_template + credential profile + instance params.
                    String urlBase = strVal(mergedConfig, "url_base",
                            strVal(mergedConfig, "base_url",
                                    strVal(mergedConfig, "api_url", "${API_BASE_URL}")));

                    py.append(String.format("# REST API Ingestion -- %s (DEV)\n", escapePython(connLabel)));
                    py.append(String.format("api_key = %s\n", pyValue("api_key", connConfig.get("api_key"))));
                    py.append(String.format("api_base_url = '%s'\n\n", urlBase));

                    py.append("response = requests.get(\n");
                    py.append("    f'{api_base_url}/${API_ENDPOINT}',\n");
                    py.append("    headers={'Authorization': f'Bearer {api_key}'},\n");
                    py.append("    timeout=300\n");
                    py.append(")\n");
                    py.append("response.raise_for_status()\n\n");

                    py.append("data = response.json()\n");
                    py.append("df = spark.createDataFrame(data if isinstance(data, list) else [data])\n\n");
                } else {
                    py.append("# REST API Ingestion (credentials not configured -- using placeholders)\n");
                    py.append("response = requests.get(\n");
                    py.append("    '${API_BASE_URL}/${API_ENDPOINT}',\n");
                    py.append("    headers={'Authorization': f'Bearer ${{API_KEY}}'},\n");
                    py.append("    timeout=300\n");
                    py.append(")\n");
                    py.append("response.raise_for_status()\n");
                    py.append("data = response.json()\n");
                    py.append("df = spark.createDataFrame(data if isinstance(data, list) else [data])\n\n");
                }

            } else {
                py.append("# Generic Ingestion\n");
                py.append("df = spark.read.format('${SOURCE_FORMAT}').load('${SOURCE_PATH}')\n\n");
            }

            // PULSE audit columns are emitted via IngestionAuditColumns (single source
            // of truth shared with SchemaPropagationService). The processing_ts column
            // is branched on dataset.processing_datetime_source per V101 (file_arrival_time
            // | filename_segment | airflow_run_time).
            Dataset sourceDs = resolveSourceDataset(inst);
            String pdtSource = sourceDs != null && sourceDs.getProcessingDatetimeSource() != null
                    ? sourceDs.getProcessingDatetimeSource()
                    : "airflow_run_time";
            IngestionAuditColumns.SourceContext auditCtx = auditSourceContextFor(bpKey, connLower);
            IngestionAuditColumns.emitPyspark(py, slug, taskSlug, pdtSource, auditCtx);

            // PKT-0027: Add created_as_timestamp for bronze layer provenance tracking.
            // This immutable timestamp records when the row was first created in the bronze layer,
            // distinct from _pulse_ingested_at (wall-clock) and _pulse_processing_ts (business time).
            py.append("# PKT-0027: Immutable creation timestamp for bronze-layer provenance\n");
            py.append("df = df.withColumn('created_as_timestamp', current_timestamp())\n\n");

            // Bronze table registration in metastore so dbt-spark can resolve
            // {{ source('bronze_<src>', '<task>') }} at runtime.  Source slug derives
            // from the connector instance (matches generateDbtSources grouping).
            String bronzeSrcSlug = (connectorInst != null && connectorInst.getName() != null)
                    ? slugify(connectorInst.getName())
                    : "default";
            String bronzeSchema = "bronze_" + bronzeSrcSlug;

            String bronzeTablePath = resolveLakeTablePath(
                    inst, slug, run.getTenantId(), LakeLayer.BRONZE, LakeFormat.DELTA, taskSlug);

            py.append("# Write to bronze layer — register in metastore so dbt sources can resolve.\n");
            if (bronzeTablePath == null) {
                py.append("# WARNING: bronze lake path not resolvable at codegen time. ")
                  .append("Verify storage_backend and source SOR/domain configuration.\n");
                py.append(String.format("output_path = '${OUTPUT_BASE}/%s/%s'\n", slug, taskSlug));
            } else {
                py.append(String.format("output_path = '%s'\n", bronzeTablePath));
            }
            py.append(String.format("bronze_table = '%s.%s'\n\n", bronzeSchema, taskSlug));
            py.append(String.format("spark.sql(f\"CREATE SCHEMA IF NOT EXISTS %s\")\n", bronzeSchema));
            py.append("df.write \\\n");
            py.append("    .format('delta') \\\n");
            py.append("    .mode('overwrite') \\\n");
            py.append("    .option('overwriteSchema', 'true') \\\n");
            py.append("    .save(output_path)\n");
            py.append("spark.sql(f\"DROP TABLE IF EXISTS {bronze_table}\")\n");
            py.append("spark.sql(f\"CREATE TABLE {bronze_table} USING DELTA LOCATION '{output_path}'\")\n\n");

            py.append(String.format("print(f'%s: Written {df.count()} rows to {bronze_table} ({output_path})')\n", taskSlug));
            py.append("spark.stop()\n");

            artifacts.add(createArtifact(run,
                    String.format("jobs/ingestion/%s_ingest.py", taskSlug),
                    "PYSPARK_JOB", py.toString(), "pyspark_ingestion"));
        }
        return artifacts;
    }

    private GeneratedArtifact generatePySparkArtifactWithOpEngine(GenerationRun run,
                                                                   SubPipelineInstance inst,
                                                                   Blueprint bp,
                                                                   String slug,
                                                                   String artifactRole) {
        OpList opList = OpList.parse(bp.getSchemaBehavior());
        String compute = opList.emission().compute();
        if (!"pyspark".equalsIgnoreCase(String.valueOf(compute))) {
            throw new IllegalStateException("Op-listed " + bp.getBlueprintKey()
                    + " is not a PySpark blueprint (compute=" + compute + ")");
        }
        for (OpList.OpEntry op : opList.ops()) {
            if (!codegenOpEngine.registry().has(op.op(), EmissionEngine.PYSPARK)) {
                throw new IllegalStateException("No PYSPARK op-engine handler for op '" + op.op()
                        + "' in blueprint " + bp.getBlueprintKey());
            }
        }

        ParamSurface surface = bp.getParamsSchema() == null
                ? null
                : ParamSurface.derive(opList, bp.getParamsSchema());
        Map<String, Object> params = inst.getParams() == null ? Map.of() : inst.getParams();
        String taskSlug = slugify(inst.getName());
        Mode mode = codegenOpEngine.modeResolver().activeMode();

        StringBuilder py = new StringBuilder();
        py.append("# Generated by PULSE for ").append(inst.getName()).append("\n");
        py.append("# Blueprint: ").append(bp.getName()).append(" (").append(bp.getBlueprintKey()).append(")\n");
        py.append("# Codegen engine: CodegenOpEngine\n\n");
        py.append("from pyspark.sql import SparkSession\n");
        py.append("from pyspark.sql.functions import *\n");
        py.append("import os\n\n");
        py.append("PULSE_BUSINESS_DATE = os.environ.get('PULSE_BUSINESS_DATE', '{{ ds }}')\n");
        py.append("PULSE_PROCESSING_TS = os.environ.get('PULSE_PROCESSING_TS', '{{ ts }}')\n\n");
        py.append("spark = SparkSession.builder \\\n");
        py.append(String.format("    .appName('%s_%s') \\\n", slug, taskSlug));
        py.append("    .config('spark.sql.adaptive.enabled', 'true') \\\n");
        py.append("    .getOrCreate()\n\n");

        py.append(codegenOpEngine.pySpark().emit(opList.ops(), op -> {
            Map<String, Object> resolved = resolvePySparkOpConfig(op, params, surface, inst, bp, slug, taskSlug, run);
            return EmitContext.builder()
                    .mode(mode)
                    .modeResolver(codegenOpEngine.modeResolver())
                    .config(new ResolvedConfig(resolved))
                    .inputSchema(Schema.empty())
                    .dfVar("df")
                    .lakeLayer(stringParam(params, "lake_layer",
                            "sink".equals(artifactRole) ? "gold" : "bronze"))
                    .build();
        }));
        py.append("\nspark.stop()\n");

        String subdir = "sink".equals(artifactRole) ? "sink" : "ingestion";
        String suffix = "sink".equals(artifactRole) ? "_sink" : "_ingest";
        GeneratedArtifact artifact = createArtifact(run,
                String.format("jobs/%s/%s%s.py", subdir, taskSlug, suffix),
                "PYSPARK_JOB", py.toString(), "pyspark_" + artifactRole);
        return markOpEngineArtifact(artifact, inst, bp);
    }

    private Map<String, Object> resolvePySparkOpConfig(OpList.OpEntry op,
                                                       Map<String, Object> params,
                                                       ParamSurface surface,
                                                       SubPipelineInstance inst,
                                                       Blueprint bp,
                                                       String slug,
                                                       String taskSlug,
                                                       GenerationRun run) {
        Map<String, Object> resolved = new LinkedHashMap<>(
                ParamResolver.resolve(op.op(), op.config(), params, surface));
        if (OpVocabulary.READ_SOURCE.equals(op.op())) {
            String sourceQuery = stringValue(resolved.get("source_query"));
            if (sourceQuery != null && !sourceQuery.isBlank()) {
                resolved.put("source_query", substituteMnemonicsForRuntime(sourceQuery));
            }
            CredentialProfile credentialProfile = resolveCredentialProfile(params);
            Map<String, Object> connConfig = resolveConnectionConfig(credentialProfile);
            ConnectorInstance connectorInst = resolveConnectorInstance(params);
            String connLabel = connectorInst != null ? connectorInst.getName() : stringParam(params, "connector_name", "");
            addJdbcConfig(resolved, connConfig, connLabel);
            resolved.putIfAbsent("source_uri", resolveObjectStoragePath(inst, slug, run.getTenantId(), FileLifecycle.SRC));
            resolved.putIfAbsent("source_format", normalizeSparkSourceFormat(stringParam(params, "file_format",
                    stringParam(params, "source_format", "parquet"))));
            if ("StreamIngestion".equals(bp.getBlueprintKey())) {
                resolved.putIfAbsent("transport", "kafka");
            }
        }
        if (OpVocabulary.ADD_AUDIT_COLUMNS.equals(op.op())) {
            Dataset sourceDs = resolveSourceDataset(inst);
            String pdtSource = sourceDs != null && sourceDs.getProcessingDatetimeSource() != null
                    ? sourceDs.getProcessingDatetimeSource()
                    : "airflow_run_time";
            ConnectorInstance connectorInst = resolveConnectorInstance(params);
            String connLower = connectorInst != null && connectorInst.getName() != null
                    ? connectorInst.getName().toLowerCase(Locale.ROOT)
                    : "";
            resolved.putIfAbsent("pipeline_slug", slug);
            resolved.putIfAbsent("task_slug", taskSlug);
            resolved.putIfAbsent("processing_datetime_source", pdtSource);
            resolved.putIfAbsent("source_context", auditSourceContextFor(bp.getBlueprintKey(), connLower).name());
        }
        if (OpVocabulary.WRITE_SINK.equals(op.op())) {
            String layer = stringParam(params, "lake_layer",
                    "DESTINATION".equals(bp.getCategory().name()) ? "gold" : "bronze");
            LakeLayer lakeLayer = parseLakeLayerOrDefault(layer,
                    "gold".equals(layer) ? LakeLayer.GOLD : LakeLayer.BRONZE);
            LakeFormat lakeFormat = LakeFormat.DELTA;
            String targetPath = resolveLakeTablePath(inst, slug, run.getTenantId(), lakeLayer, lakeFormat, taskSlug);
            resolved.putIfAbsent("target_path", targetPath);
            resolved.putIfAbsent("write_mode", stringParam(params, "write_mode",
                    stringParam(params, "mode", "overwrite")));
        }
        return resolved;
    }

    private void addJdbcConfig(Map<String, Object> resolved,
                               Map<String, Object> connConfig,
                               String connLabel) {
        if (connConfig == null || connConfig.isEmpty()) {
            return;
        }
        String explicit = strVal(connConfig, "jdbc_url", "");
        String host = strVal(connConfig, "host", "localhost");
        String port = strVal(connConfig, "port", "5432");
        String connLower = connLabel == null ? "" : connLabel.toLowerCase(Locale.ROOT);
        String jdbcUrl = explicit;
        String driver = strVal(connConfig, "driver", "");
        if (jdbcUrl.isBlank()) {
            if (connLower.contains("mysql")) {
                jdbcUrl = String.format("jdbc:mysql://%s:%s/%s", host, port, strVal(connConfig, "database", "mysql"));
                driver = firstNonBlank(driver, "com.mysql.cj.jdbc.Driver");
            } else if (connLower.contains("mssql") || connLower.contains("sql server")) {
                jdbcUrl = String.format("jdbc:sqlserver://%s:%s;databaseName=%s", host, port,
                        strVal(connConfig, "database", "master"));
                driver = firstNonBlank(driver, "com.microsoft.sqlserver.jdbc.SQLServerDriver");
            } else {
                jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port,
                        strVal(connConfig, "database", "postgres"));
                driver = firstNonBlank(driver, "org.postgresql.Driver");
            }
        }
        resolved.putIfAbsent("jdbc_url", jdbcUrl);
        resolved.putIfAbsent("jdbc_driver", firstNonBlank(driver, "org.postgresql.Driver"));
        resolved.putIfAbsent("jdbc_user", strVal(connConfig, "username", ""));
        resolved.putIfAbsent("jdbc_password", strVal(connConfig, "password", ""));
    }

    private LakeLayer parseLakeLayerOrDefault(String raw, LakeLayer fallback) {
        try {
            LakeLayer parsed = LakeLayer.from(raw);
            return parsed == null ? fallback : parsed;
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private List<GeneratedArtifact> generateSinkJobs(GenerationRun run,
                                                     List<SubPipelineInstance> instances,
                                                     String slug,
                                                     CompilePlanService.CompilePlanSnapshot compilePlan) {
        List<GeneratedArtifact> artifacts = new ArrayList<>();
        for (var inst : instances) {
            Blueprint bp = blueprintRepo.findByBlueprintKey(inst.getBlueprintKey()).orElse(null);
            if (bp == null || !"DESTINATION".equals(bp.getCategory().name())) continue;

            String taskSlug = slugify(inst.getName());
            var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
            String bpKey = inst.getBlueprintKey() != null ? inst.getBlueprintKey() : "";

            if (OpList.isOpList(bp.getSchemaBehavior())) {
                GeneratedArtifact opEngineArtifact = generatePySparkArtifactWithOpEngine(
                        run, inst, bp, slug, "sink");
                artifacts.add(opEngineArtifact);
                continue;
            }

            // Build merged config: connector config_template + credential profile + instance params.
            // After V62 param deduplication, connection params are inherited from the connector.
            CredentialProfile credentialProfile = resolveCredentialProfile(params);
            Map<String, Object> mergedConfig = buildMergedConfig(params);
            Map<String, Object> connConfig = resolveConnectionConfig(credentialProfile);
            ConnectorInstance connectorInst = resolveConnectorInstance(params);
            String connName = (String) params.getOrDefault("connector_name", inst.getName());
            String connLabel = connectorInst != null ? connectorInst.getName() : connName;
            boolean hasCreds = connConfig != null && !connConfig.isEmpty();
            List<RuntimeSecretBinding> secretBindings = collectRuntimeSecretBindings(credentialProfile);

            String targetTable = strVal(mergedConfig, "target_table", "${TARGET_TABLE}");
            String outputPath = strVal(mergedConfig, "output_path",
                    strVal(mergedConfig, "target_path",
                            strVal(mergedConfig, "storage_path",
                                    buildObjectStorePath(mergedConfig, "${TARGET_PATH}"))));
            String topicName = strVal(mergedConfig, "topic",
                    strVal(mergedConfig, "target_topic", "${TOPIC}"));
            String writeMode = strVal(mergedConfig, "write_mode", "append");
            String connLower = connLabel.toLowerCase();

            StringBuilder py = new StringBuilder();
            py.append("from pyspark.sql import SparkSession\n");
            py.append("from pyspark.sql.functions import *\n");
            py.append("from datetime import datetime\n");
            py.append("import os\n");
            py.append("\n");
            py.append("PULSE_BUSINESS_DATE = os.environ.get('PULSE_BUSINESS_DATE', '{{ ds }}')\n");
            py.append("PULSE_PROCESSING_TS = os.environ.get('PULSE_PROCESSING_TS', '{{ ts }}')\n\n");

            if (!secretBindings.isEmpty()) {
                py.append("# ").append("=".repeat(70)).append("\n");
                py.append("# Runtime secret bindings (resolved by the orchestrator at task start):\n");
                for (var binding : secretBindings) {
                    py.append(String.format("#   %-40s <- %s [%s]\n",
                            binding.envVarName(),
                            binding.runtimeSecretRef(),
                            binding.deliveryMode().name()));
                }
                py.append("# ").append("=".repeat(70)).append("\n\n");
            }

            py.append("spark = SparkSession.builder \\\n");
            py.append(String.format("    .appName('%s_%s') \\\n", slug, taskSlug));
            py.append("    .config('spark.sql.adaptive.enabled', 'true') \\\n");
            py.append("    .getOrCreate()\n\n");

            String inputPath = resolveUpstreamDeltaPath(inst, instances, slug, run.getTenantId(), compilePlan);

            // Read from the upstream staging layer
            py.append(String.format("# Read from upstream staging layer\n"));
            if (inputPath == null) {
                py.append("# WARNING: upstream Delta path not resolvable at codegen time. ")
                  .append("Verify wiring and storage_backend configuration.\n");
                py.append(String.format("input_path = '${OUTPUT_BASE}/%s/${UPSTREAM_TASK}'\n", slug));
            } else {
                py.append(String.format("input_path = '%s'\n", inputPath));
            }
            py.append("df = spark.read.format('delta').load(input_path)\n\n");

            // Destination-specific write code
            if ("WarehouseWriter".equals(bpKey)) {
                if (connLower.contains("snowflake")) {
                    py.append("# Write to Snowflake\n");
                    if (hasCreds) {
                        py.append(String.format("sf_url = %s\n", pyValue("host", mergedConfig.get("host"))));
                        py.append(String.format("sf_user = %s\n", pyValue("username", mergedConfig.get("username"))));
                        py.append(String.format("sf_password = %s\n", pyValue("password", mergedConfig.get("password"))));
                        py.append(String.format("sf_database = %s\n", pyValue("database", mergedConfig.get("database"))));
                        py.append(String.format("sf_schema = %s\n", pyValue("schema", mergedConfig.get("schema"))));
                        py.append(String.format("sf_warehouse = %s\n\n", pyValue("warehouse", mergedConfig.get("warehouse"))));
                    } else {
                        py.append("sf_url = '${SNOWFLAKE_URL}'\nsf_user = '${SF_USER}'\nsf_password = '${SF_PASSWORD}'\n");
                        py.append("sf_database = '${SF_DATABASE}'\nsf_schema = '${SF_SCHEMA}'\nsf_warehouse = '${SF_WAREHOUSE}'\n\n");
                    }
                    py.append("df.write \\\n");
                    py.append("    .format('snowflake') \\\n");
                    py.append("    .option('sfURL', sf_url) \\\n");
                    py.append("    .option('sfUser', sf_user) \\\n");
                    py.append("    .option('sfPassword', sf_password) \\\n");
                    py.append("    .option('sfDatabase', sf_database) \\\n");
                    py.append("    .option('sfSchema', sf_schema) \\\n");
                    py.append("    .option('sfWarehouse', sf_warehouse) \\\n");
                    py.append(String.format("    .option('dbtable', '%s') \\\n", targetTable));
                    py.append(String.format("    .mode('%s') \\\n", writeMode));
                    py.append("    .save()\n\n");
                } else if (connLower.contains("bigquery")) {
                    // PKT-0027: Native BigQuery write with full configuration.
                    // Uses the spark-bigquery connector (com.google.cloud.spark:spark-bigquery-with-dependencies)
                    // which supports direct writes via the BigQuery Storage Write API.
                    py.append("# Write to BigQuery (native BigQuery connector)\n");
                    String tempBucket = strVal(mergedConfig, "temporaryGcsBucket",
                            strVal(mergedConfig, "temp_gcs_bucket", "${BQ_TEMP_BUCKET}"));
                    py.append(String.format("bq_temp_bucket = %s\n", pyValueWithComment("temporaryGcsBucket", mergedConfig.get("temporaryGcsBucket"))));
                    py.append("df.write \\\n");
                    py.append("    .format('bigquery') \\\n");
                    py.append(String.format("    .option('table', '%s') \\\n", targetTable));
                    py.append(String.format("    .option('temporaryGcsBucket', bq_temp_bucket) \\\n"));
                    py.append("    .option('createDisposition', 'CREATE_IF_NEEDED') \\\n");
                    py.append("    .option('writeDisposition', 'WRITE_TRUNCATE') \\\n");
                    py.append(String.format("    .mode('%s') \\\n", writeMode));
                    py.append("    .save()\n\n");
                } else if (connLower.contains("redshift")) {
                    py.append("# Write to Redshift\n");
                    py.append("df.write \\\n");
                    py.append("    .format('io.github.spark_redshift_community.spark.redshift') \\\n");
                    py.append("    .option('url', '${REDSHIFT_JDBC_URL}') \\\n");
                    py.append(String.format("    .option('dbtable', '%s') \\\n", targetTable));
                    py.append("    .option('tempdir', '${REDSHIFT_TEMPDIR}') \\\n");
                    py.append(String.format("    .mode('%s') \\\n", writeMode));
                    py.append("    .save()\n\n");
                } else {
                    // Generic warehouse
                    py.append("# Write to warehouse\n");
                    py.append("df.write \\\n");
                    py.append("    .format('jdbc') \\\n");
                    py.append(String.format("    .option('dbtable', '%s') \\\n", targetTable));
                    py.append(String.format("    .mode('%s') \\\n", writeMode));
                    py.append("    .save()\n\n");
                }
            } else if ("LakeWriter".equals(bpKey)) {
                String format = "delta";
                if (connLower.contains("iceberg")) format = "iceberg";
                else if (connLower.contains("parquet") || connLower.contains("s3")) format = "parquet";
                if (outputPath == null || outputPath.isBlank() || outputPath.contains("${TARGET_PATH}")) {
                    LakeFormat lakeFormat = "iceberg".equals(format)
                            ? LakeFormat.ICEBERG_EXTERNAL
                            : ("parquet".equals(format) ? LakeFormat.PARQUET : LakeFormat.DELTA);
                    String resolvedOutputPath = resolveLakeTablePath(
                            inst, slug, run.getTenantId(), LakeLayer.GOLD, lakeFormat, taskSlug);
                    if (resolvedOutputPath != null) {
                        outputPath = resolvedOutputPath;
                    }
                }

                py.append(String.format("# Write to data lake (%s)\n", format));
                py.append("df.write \\\n");
                py.append(String.format("    .format('%s') \\\n", format));
                py.append(String.format("    .mode('%s') \\\n", writeMode));
                if ("merge".equals(writeMode)) {
                    py.append("    .option('mergeSchema', 'true') \\\n");
                }
                py.append(String.format("    .save('%s')\n\n", outputPath));
            } else if ("StreamWriter".equals(bpKey)) {
                py.append("# Write to Kafka\n");
                if (hasCreds) {
                    py.append(String.format("bootstrap_servers = %s\n",
                            pyValueWithComment("bootstrap_servers", mergedConfig.get("bootstrap_servers"))));
                } else {
                    py.append("bootstrap_servers = '${KAFKA_BROKERS}'\n");
                }
                py.append("\ndf.selectExpr('to_json(struct(*)) AS value') \\\n");
                py.append("    .write \\\n");
                py.append("    .format('kafka') \\\n");
                py.append("    .option('kafka.bootstrap.servers', bootstrap_servers) \\\n");
                py.append(String.format("    .option('topic', '%s') \\\n", topicName));
                py.append("    .save()\n\n");
            } else if ("DatabaseWriter".equals(bpKey)) {
                py.append("# Write to database (JDBC)\n");
                String jdbcDriver = "org.postgresql.Driver";
                if (hasCreds || !mergedConfig.isEmpty()) {
                    String jdbcUrl = strVal(mergedConfig, "jdbc_url", "");
                    if (jdbcUrl.isBlank()) {
                        if (connLower.contains("databricks")) {
                            String host = strVal(mergedConfig, "server_hostname", "${DATABRICKS_HOST}");
                            String httpPath = strVal(mergedConfig, "http_path", "${DATABRICKS_HTTP_PATH}");
                            jdbcUrl = String.format(
                                    "jdbc:databricks://%s:443/default;transportMode=http;ssl=1;httpPath=%s",
                                    host, httpPath);
                            jdbcDriver = "com.databricks.client.jdbc.Driver";
                        } else {
                            String host = strVal(mergedConfig, "host", "localhost");
                            String port = strVal(mergedConfig, "port", "5432");
                            String db = strVal(mergedConfig, "database", "");
                            jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, db);
                        }
                    }
                    jdbcDriver = strVal(mergedConfig, "driver", jdbcDriver);
                    py.append(String.format("jdbc_url = '%s'\n", escapePython(jdbcUrl)));
                    py.append(String.format("jdbc_driver = '%s'\n", escapePython(jdbcDriver)));
                    if (connLower.contains("databricks")) {
                        py.append("jdbc_user = 'token'\n");
                        py.append(String.format("jdbc_password = %s\n\n", pyValue("access_token", mergedConfig.get("access_token"))));
                    } else {
                        py.append(String.format("jdbc_user = %s\n", pyValue("username", mergedConfig.get("username"))));
                        py.append(String.format("jdbc_password = %s\n\n", pyValue("password", mergedConfig.get("password"))));
                    }
                } else {
                    py.append("jdbc_url = '${DEST_JDBC_URL}'\njdbc_driver = '${DEST_JDBC_DRIVER}'\njdbc_user = '${DEST_USER}'\njdbc_password = '${DEST_PASSWORD}'\n\n");
                }
                py.append("df.write \\\n");
                py.append("    .format('jdbc') \\\n");
                py.append("    .option('url', jdbc_url) \\\n");
                py.append("    .option('driver', jdbc_driver) \\\n");
                py.append("    .option('user', jdbc_user) \\\n");
                py.append("    .option('password', jdbc_password) \\\n");
                py.append(String.format("    .option('dbtable', '%s') \\\n", targetTable));
                py.append(String.format("    .mode('%s') \\\n", writeMode));
                py.append("    .save()\n\n");
            } else {
                // Generic write
                py.append("df.write \\\n");
                py.append(String.format("    .mode('%s') \\\n", writeMode));
                py.append(String.format("    .save('%s')\n\n", targetTable));
            }

            // Audit
            py.append("print(f'" + taskSlug + ": Written {df.count()} rows')\n");
            py.append("spark.stop()\n");

            artifacts.add(createArtifact(run,
                    String.format("jobs/sink/%s_sink.py", taskSlug),
                    "PYSPARK_JOB", py.toString(), "pyspark_sink"));
        }
        return artifacts;
    }

    // --- Credential resolution helpers ---

    /**
     * Looks up the dev CredentialProfile for a connector instance referenced in the
     * sub-pipeline params and returns the canonical credential profile.
     *
     * <p>Codegen is dev-only today (chat-first builder per PROJECT_CONTEXT.MD
     * locked decision #5), so the lookup uses the canonical lowercase 'dev'
     * key — V103 backfilled all credential_profiles.environment rows from
     * legacy uppercase 'DEV' to canonical 'dev', and Phase 1 normalization
     * keeps every new write canonical.
     */
    private CredentialProfile resolveCredentialProfile(Map<String, Object> params) {
        Object cidObj = params != null ? params.get("connector_instance_id") : null;
        if (!(cidObj instanceof String)) return null;
        String connectorInstanceId = (String) cidObj;
        return credentialProfileRepo
                .findByConnectorInstanceIdAndEnvironment(connectorInstanceId, "dev")
                .orElse(null);
    }

    private Map<String, Object> resolveConnectionConfig(CredentialProfile credentialProfile) {
        if (credentialProfile == null) {
            return null;
        }
        Map<String, Object> combined = new LinkedHashMap<>(credentialProfile.getConnectionMetadata());
        combined.putAll(credentialProfile.getSecretReferences());
        return combined;
    }

    /** Resolves the ConnectorInstance entity from the sub-pipeline params. */
    private ConnectorInstance resolveConnectorInstance(Map<String, Object> params) {
        Object cidObj = params != null ? params.get("connector_instance_id") : null;
        if (!(cidObj instanceof String)) return null;
        return connectorInstanceRepo.findById((String) cidObj).orElse(null);
    }

    /**
     * Builds a merged configuration by combining connector-level config with
     * instance-level params. This supports the param deduplication introduced in
     * V62 where connection parameters (api_url, bootstrap_servers, jdbc_url, etc.)
     * were removed from blueprint params_schema because they duplicate information
     * already stored in the connector instance's config_template and credential
     * profiles.
     *
     * Merge order (later wins):
     *   1. Connector instance config_template (base connection settings)
     *   2. Credential profile metadata + secret refs (env-specific overrides + secrets)
     *   3. Sub-pipeline instance params (pipeline-specific overrides)
     *
     * This ensures connector config provides base values while instance params
     * can still override when explicitly set.
     */
    private Map<String, Object> buildMergedConfig(Map<String, Object> instanceParams) {
        Map<String, Object> merged = new HashMap<>();

        // 1. Start with connector instance config_template (base connection config)
        ConnectorInstance ci = resolveConnectorInstance(instanceParams);
        if (ci != null && ci.getConfigTemplate() != null) {
            merged.putAll(ci.getConfigTemplate());
        }

        // 2. Overlay with credential profile config (env-specific, includes secrets)
        CredentialProfile credentialProfile = resolveCredentialProfile(instanceParams);
        if (credentialProfile != null) {
            merged.putAll(credentialProfile.getConnectionMetadata());
            merged.putAll(credentialProfile.getSecretReferences());
        }

        // 3. Overlay with instance params (pipeline-specific overrides take priority)
        if (instanceParams != null) {
            for (var entry : instanceParams.entrySet()) {
                if (entry.getValue() != null) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return merged;
    }

    private List<RuntimeSecretBinding> collectRuntimeSecretBindings(CredentialProfile credentialProfile) {
        return secretReferenceService.collectBindings(credentialProfile);
    }

    /**
     * Returns a Python expression for a config value.
     * <ul>
     *   <li>Secret refs (gcp-sm://, vault://) become {@code os.environ['VAR']} lookups.</li>
     *   <li>PKT-0027: Secret-shaped fields with raw (non-ref) values are emitted as
     *       env-var placeholders ({@code os.environ['${FIELD_UPPER}']}) so the raw
     *       value never appears in the generated artifact.</li>
     *   <li>Non-secret fields are emitted as string literals.</li>
     * </ul>
     */
    private String pyValue(String fieldName, Object value) {
        if (value == null) return "'unknown'";
        if (secretReferenceService.isSecretReference(value)) {
            return "os.environ['"
                    + secretReferenceService.bindingFor(fieldName, (String) value).envVarName()
                    + "']";
        }
        // PKT-0027: Never emit raw values for secret-shaped fields.
        if (ForbiddenTokenScanner.isSecretShapedKey(fieldName)) {
            String envVar = fieldName.toUpperCase(Locale.ROOT).replace(" ", "_");
            recordRedactedField(fieldName);
            return "os.environ['" + envVar + "']";
        }
        return "'" + value.toString().replace("'", "\\'") + "'";
    }

    private String pyValue(Object value) {
        return pyValue(null, value);
    }

    /** Like pyValue but appends a comment for non-secret inline values. */
    private String pyValueWithComment(String fieldName, Object value) {
        if (value == null) return "'unknown'";
        if (secretReferenceService.isSecretReference(value)) {
            return "os.environ['"
                    + secretReferenceService.bindingFor(fieldName, (String) value).envVarName()
                    + "']";
        }
        // PKT-0027: Never emit raw values for secret-shaped fields.
        if (ForbiddenTokenScanner.isSecretShapedKey(fieldName)) {
            String envVar = fieldName.toUpperCase(Locale.ROOT).replace(" ", "_");
            recordRedactedField(fieldName);
            return "os.environ['" + envVar + "']";
        }
        return "'" + value.toString().replace("'", "\\'") + "'  # production: use secret reference";
    }

    private String pyValueWithComment(Object value) {
        return pyValueWithComment(null, value);
    }

    /** PKT-0027: Thread-safe recording of redacted secret fields. */
    private static void recordRedactedField(String fieldName) {
        List<String> fields = REDACTED_SECRET_FIELDS.get();
        if (fields != null) {
            fields.add(fieldName);
        }
    }

    /** Safely reads a string value from a config map with a fallback default. */
    private String strVal(Map<String, Object> map, String key, String defaultVal) {
        Object v = map.get(key);
        return v != null ? v.toString() : defaultVal;
    }

    private String buildObjectStorePath(Map<String, Object> map, String defaultVal) {
        String bucket = strVal(map, "bucket", "").trim();
        if (bucket.isEmpty()) {
            return defaultVal;
        }
        String prefix = strVal(map, "path_prefix", "").trim();
        String normalizedPrefix = prefix.replaceAll("^/+|/+$", "");
        return normalizedPrefix.isEmpty()
                ? String.format("s3://%s", bucket)
                : String.format("s3://%s/%s", bucket, normalizedPrefix);
    }

    private void appendReuseDecisionComments(StringBuilder sql, Object reuseDecisionValue) {
        if (!(reuseDecisionValue instanceof Map<?, ?> reuseDecision)) {
            sql.append("-- Reuse rationale: registry-backed wrapper selected.\n\n");
            return;
        }

        Object score = reuseDecision.get("score");
        if (score != null) {
            sql.append("-- Match score: ").append(score).append("\n");
        }
        appendCommentList(sql, "-- Reuse rationale: ", reuseDecision.get("reasons"));
        appendCommentList(sql, "-- Reuse warning: ", reuseDecision.get("warnings"));
        if (reuseDecision.get("compatibility") instanceof Map<?, ?> compatibility
                && compatibility.get("referenceSafe") != null) {
            sql.append("-- Reference-safe: ")
                    .append(Boolean.TRUE.equals(compatibility.get("referenceSafe")) ? "yes" : "no")
                    .append("\n");
        }
        sql.append("\n");
    }

    private void appendCommentList(StringBuilder sql, String prefix, Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return;
        }
        for (Object entry : collection) {
            if (entry != null && !entry.toString().isBlank()) {
                sql.append(prefix).append(entry).append("\n");
            }
        }
    }

    // --- End credential helpers ---

    private List<GeneratedArtifact> generateDbtModels(GenerationRun run,
                                                       List<SubPipelineInstance> instances,
                                                       String slug,
                                                       CompilePlanService.CompilePlanSnapshot compilePlan) {
        List<GeneratedArtifact> artifacts = new ArrayList<>();
        Map<String, Map<String, Object>> compileNodes = new LinkedHashMap<>();
        for (Map<String, Object> node : compilePlan.nodes()) {
            compileNodes.put(node.get("instanceId").toString(), node);
        }
        String domainSlug = deriveDomainSlug(compilePlan);

        for (var inst : instances) {
            Blueprint bp = blueprintRepo.findByBlueprintKey(inst.getBlueprintKey()).orElse(null);
            if (bp == null) continue;
            String category = bp.getCategory().name();
            // Data-quality blueprints are emitted as GX checkpoints, never dbt models.
            if (!"TRANSFORM".equals(category) && !"MODELING".equals(category)) continue;

            String entitySlug = slugify(inst.getName());
            String bpKey = bp.getBlueprintKey();
            Map<String, Object> compileNode = compileNodes.get(inst.getId());
            String emitStrategy = compileNode != null && compileNode.get("emitStrategy") != null
                    ? compileNode.get("emitStrategy").toString()
                    : "generate";
            String resolvedLayer = compileNode != null && compileNode.get("resolvedLayer") != null
                    ? compileNode.get("resolvedLayer").toString()
                    : "silver";
            String fileFormat = resolveFileFormatFromHints(compileNode);
            DbtLayout layout = resolveDbtLayout(bpKey, resolvedLayer, domainSlug,
                    resolveSourceSystemSlug(inst, instances, domainSlug), entitySlug);

            if ("reference_only".equals(emitStrategy)) {
                continue;
            }

            if ("reuse_wrapper".equals(emitStrategy) && compileNode != null
                    && compileNode.get("reuseAsset") instanceof Map<?, ?> reuseAsset) {
                String assetName = reuseAsset.get("assetName").toString();
                StringBuilder sql = new StringBuilder();
                sql.append("-- Generated by PULSE for ").append(inst.getName()).append("\n");
                sql.append("-- Blueprint: ").append(bp.getName()).append(" (").append(bpKey).append(")\n\n");
                sql.append("{{ config(materialized='ephemeral', tags=['pulse', '")
                        .append(slug).append("', '").append(entitySlug).append("', 'reuse_wrapper']) }}\n\n");
                sql.append("-- Reuse plan: wrap existing dbt asset ").append(assetName).append("\n");
                appendReuseDecisionComments(sql, compileNode.get("reuseDecision"));
                sql.append("WITH reused_asset AS (\n");
                sql.append("    SELECT *\n");
                sql.append("    FROM {{ ref('").append(assetName).append("') }}\n");
                sql.append(")\n");
                sql.append("SELECT\n");
                sql.append("    reused_asset.*,\n");
                sql.append("    current_timestamp() as _pulse_processed_at\n");
                sql.append("FROM reused_asset\n");
                artifacts.add(createArtifact(run, layout.path(),
                        "DBT_MODEL", lowerDbtMnemonics(sql.toString()), "dbt_model"));
                continue;
            }

            List<GeneratedArtifact> opEngineArtifacts = generateDbtArtifactsWithOpEngine(
                    run, inst, bp, instances, slug, compilePlan, layout, resolvedLayer, fileFormat, compileNode);
            if (!opEngineArtifacts.isEmpty()) {
                artifacts.addAll(opEngineArtifacts);
                continue;
            }
            if (OpList.isOpList(bp.getSchemaBehavior())) {
                throw new IllegalStateException("Op-listed dbt blueprint " + bpKey
                        + " was not emitted by CodegenOpEngine");
            }

            if ("SCD2Dimension".equals(bpKey)) {
                String snapshotSql = generateSnapshotSql(inst, bp, slug, domainSlug, entitySlug,
                        fileFormat, run.getTenantId());
                artifacts.add(createArtifact(run, layout.path(),
                        "DBT_SNAPSHOT", lowerDbtMnemonics(snapshotSql), "dbt_snapshot"));
                continue;
            }

            if ("SnapshotModel".equals(bpKey)) {
                String snapshotModelSql = generateSnapshotModelSql(inst, bp, slug, entitySlug, fileFormat, run.getTenantId());
                artifacts.add(createArtifact(run, layout.path(),
                        "DBT_MODEL", lowerDbtMnemonics(snapshotModelSql), "dbt_model"));
                continue;
            }

            if ("GenericRouter".equals(bpKey)) {
                var routerModels = generateRouterSql(inst, slug, fileFormat, resolvedLayer, run.getTenantId());
                for (var entry : routerModels.entrySet()) {
                    String routePath = layout.path().replace(layout.fileName(),
                            "int__" + domainSlug + "__" + entry.getKey() + ".sql");
                    artifacts.add(createArtifact(run, routePath,
                            "DBT_MODEL", lowerDbtMnemonics(entry.getValue()), "dbt_model"));
                }
                continue;
            }

            StringBuilder body = new StringBuilder();
            if ("GenericJoin".equals(bpKey)) {
                body.append(generateJoinSqlBody(inst, slug));
            } else if ("GenericAggregate".equals(bpKey) || "AggregateMaterialization".equals(bpKey)) {
                body.append(generateAggregateSqlBody(inst, slug));
            } else if ("BronzeToSilverCleaning".equals(bpKey)) {
                body.append(generateBronzeToSilverCleaningSqlBody(inst, slug));
            } else if ("PIIMasking".equals(bpKey)) {
                body.append(generatePiiMaskingSqlBody(inst, slug));
            } else if ("GenericFilter".equals(bpKey)) {
                body.append(generateFilterSqlBody(inst, slug));
            } else if ("DedupeAndMerge".equals(bpKey)) {
                body.append(generateDedupeAndMergeSqlBody(inst, slug));
            } else if ("JsonFlatten".equals(bpKey)) {
                body.append(generateJsonFlattenSqlBody(inst, slug));
            } else if ("JsonStruct".equals(bpKey)) {
                body.append(generateJsonStructSqlBody(inst, slug));
            } else if ("SchemaNormalization".equals(bpKey)) {
                body.append(generateSchemaNormalizationSqlBody(inst, slug));
            } else if ("FeatureTablePublish".equals(bpKey)) {
                body.append(generateFeatureTablePublishSqlBody(inst, slug));
            } else if ("ReferenceDataPublish".equals(bpKey)) {
                body.append(generateReferenceDataPublishSqlBody(inst, slug));
            } else if ("FactBuild".equals(bpKey)) {
                body.append(generateFactBuildSqlBody(inst, slug));
            } else if ("WideDenormalizedMart".equals(bpKey)) {
                body.append(generateWideDenormalizedMartSqlBody(inst, slug));
            } else if ("IncrementalMerge".equals(bpKey)) {
                body.append(generateIncrementalMergeSqlBody(inst, slug));
            } else {
                // Default fallback body — no specific generator available for this blueprint.
                // Use a same-pipeline bronze source ref so dbt parse fails with a clear missing-source
                // error if the upstream wiring isn't established.
                body.append("SELECT\n    *,\n    current_timestamp() as _pulse_processed_at\nFROM ")
                        .append(String.format("{{ source('bronze_%s', '%s_input') }}", slug, slug))
                        .append("\n");
            }

            boolean isIncremental = "IncrementalMerge".equals(bpKey);
            boolean includeFileFormat = isIncremental || !"gold".equals(resolvedLayer);
            List<BronzeSourceRegistration> bronzeSourceRegistrations =
                    collectBronzeSourceRegistrations(inst, slug, run.getTenantId());
            String locationRoot = resolveDbtLocationRoot(inst, slug, run.getTenantId(), resolvedLayer);
            boolean usePulseDeltaTable = !isIncremental
                    && "delta".equalsIgnoreCase(fileFormat)
                    && locationRoot != null;
            boolean usePulseDeltaIncrementalMerge = isIncremental
                    && "delta".equalsIgnoreCase(fileFormat)
                    && locationRoot != null;
            String materialized = usePulseDeltaIncrementalMerge
                    ? "pulse_delta_incremental_merge"
                    : (isIncremental ? "incremental" : (usePulseDeltaTable ? "pulse_delta_table" : "table"));
            List<String> preHooks = new ArrayList<>();
            if (!isIncremental && !usePulseDeltaTable && "delta".equalsIgnoreCase(fileFormat)) {
                preHooks.add("DROP TABLE IF EXISTS {{ this }}");
            }
            preHooks.addAll(bronzeSourcePreHooks(bronzeSourceRegistrations));
            @SuppressWarnings("unchecked")
            List<String> partitionBy = compileNode != null && compileNode.get("partitionBy") instanceof List<?>
                    ? ((List<?>) compileNode.get("partitionBy")).stream().map(Object::toString).toList()
                    : List.of();

            // Incremental params — required for dbt-spark merge strategy to actually
            // be idempotent.  Without unique_key + incremental_strategy='merge', dbt
            // does an INSERT every run and silently produces duplicate rows.
            Map<String, Object> instParams = inst.getParams() != null ? inst.getParams() : Map.of();
            List<String> uniqueKeyList = isIncremental
                    ? firstNonEmptyStringList(instParams,
                    "unique_key",
                    "merge_keys",
                    "merge_key")
                    : List.of();
            String watermarkColumn = isIncremental
                    ? stringParam(instParams, "watermark_column", "_pulse_processed_at")
                    : null;

            StringBuilder sql = new StringBuilder();
            sql.append("-- Generated by PULSE for ").append(inst.getName()).append("\n");
            sql.append("-- Blueprint: ").append(bp.getName()).append(" (").append(bpKey).append(")\n\n");
            sql.append("{{ config(\n");
            sql.append("    materialized='").append(materialized).append("'");
            sql.append(",\n    alias='").append(dbtRelationAlias(slug, entitySlug)).append("'");
            if (isIncremental) {
                sql.append(",\n    incremental_strategy='merge'");
                sql.append(",\n    on_schema_change='append_new_columns'");
                if (!uniqueKeyList.isEmpty()) {
                    sql.append(",\n    unique_key=[");
                    for (int i = 0; i < uniqueKeyList.size(); i++) {
                        if (i > 0) sql.append(", ");
                        sql.append("'").append(uniqueKeyList.get(i)).append("'");
                    }
                    sql.append("]");
                } else {
                    sql.append(",\n    -- WARNING: no unique_key configured on instance.params; dbt merge will fail at run time.");
                    sql.append("\n    -- Set params.unique_key=['col'], params.merge_keys=['col'], or params.merge_key='col' on this instance.");
                }
            }
            if (includeFileFormat) {
                sql.append(",\n    file_format='").append(fileFormat).append("'");
            }
            if (locationRoot != null) {
                sql.append(",\n    location_root='").append(locationRoot).append("'");
            }
            sql.append(",\n    tags=['pulse', '").append(slug).append("', '")
                    .append(entitySlug).append("', '").append(layout.dbtLayer()).append("']");
            if (!partitionBy.isEmpty()) {
                sql.append(",\n    partition_by=[");
                for (int i = 0; i < partitionBy.size(); i++) {
                    if (i > 0) sql.append(", ");
                    sql.append("'").append(partitionBy.get(i)).append("'");
                }
                sql.append("]");
            }
            appendDbtPreHooks(sql, preHooks);
            sql.append("\n) }}\n\n");
            String bodyWithProcessedAt = addProcessedAtColumn(body.toString());
            if (isIncremental && !uniqueKeyList.isEmpty()) {
                // Wrap the body in {% if is_incremental() %} so subsequent runs only
                // pull rows newer than the watermark.  First run pulls everything.
                bodyWithProcessedAt = wrapIncrementalGuard(bodyWithProcessedAt, watermarkColumn);
            }
            sql.append(bodyWithProcessedAt);
            // SPEC #6 §c.3: lower inline [[ m ]] -> {{ var('pulse_<slug>') }}; the matching
            // --vars are appended to this task's dbt invocation in generateAirflowDag.
            String modelSql = lowerDbtMnemonics(sql.toString());
            artifacts.add(createArtifact(run, layout.path(),
                    "DBT_MODEL", modelSql, "dbt_model"));
        }
        if (requiresDateDimArtifact(artifacts, compilePlan)) {
            artifacts.add(createArtifact(run,
                    "dbt_project/models/shared/date_dim.sql",
                    "DBT_MODEL",
                    generateDateDimSql(run.getTenantId(), deriveDomainSlug(compilePlan)),
                    "dbt_date_dim"));
        }
        return artifacts;
    }

    private List<GeneratedArtifact> generateDbtArtifactsWithOpEngine(GenerationRun run,
                                                                      SubPipelineInstance inst,
                                                                      Blueprint bp,
                                                                      List<SubPipelineInstance> instances,
                                                                      String slug,
                                                                      CompilePlanService.CompilePlanSnapshot compilePlan,
                                                                      DbtLayout layout,
                                                                      String resolvedLayer,
                                                                      String fileFormat,
                                                                      Map<String, Object> compileNode) {
        if (!OpList.isOpList(bp.getSchemaBehavior())) {
            return List.of();
        }

        OpList opList = OpList.parse(bp.getSchemaBehavior());
        String compute = opList.emission().compute();
        if (!"dbt".equalsIgnoreCase(String.valueOf(compute))
                && !"dbt_sql".equalsIgnoreCase(String.valueOf(compute))
                && !"dbt-sql".equalsIgnoreCase(String.valueOf(compute))) {
            return List.of();
        }

        ParamSurface surface = bp.getParamsSchema() == null
                ? null
                : ParamSurface.derive(opList, bp.getParamsSchema());
        Map<String, Object> params = inst.getParams() == null ? Map.of() : inst.getParams();
        Mode mode = codegenOpEngine.modeResolver().activeMode();
        String baseRef = resolveDbtBaseRefForOpEngine(inst, instances, slug, compilePlan);

        if (opList.ops().size() == 1 && OpVocabulary.TRACK_HISTORY_SCD2.equals(opList.ops().getFirst().op())) {
            OpList.OpEntry op = opList.ops().getFirst();
            Map<String, Object> resolved = resolveCodegenOpConfig(op, params, surface,
                    inst, bp, slug, layout, resolvedLayer, fileFormat, compileNode);
            String body = codegenOpEngine.dbtSnapshot().emit(op, EmitContext.builder()
                    .mode(mode)
                    .modeResolver(codegenOpEngine.modeResolver())
                    .config(new ResolvedConfig(resolved))
                    .inputSchema(Schema.empty())
                    .upstreamRef(baseRef)
                    .lakeLayer(resolvedLayer)
                    .build());
            String content = opEngineHeader(inst, bp) + body + "\n";
            return List.of(markOpEngineArtifact(createArtifact(run, layout.path(),
                    "DBT_SNAPSHOT", lowerDbtMnemonics(content), "dbt_snapshot"), inst, bp));
        }

        if (opList.ops().size() == 1 && OpVocabulary.ROUTE_ROWS.equals(opList.ops().getFirst().op())) {
            return generateRouteRowsWithOpEngine(run, inst, bp, opList.ops().getFirst(), params, surface,
                    slug, layout, resolvedLayer, fileFormat, compileNode, mode, baseRef);
        }

        for (OpList.OpEntry op : opList.ops()) {
            if (!codegenOpEngine.registry().has(op.op(), EmissionEngine.DBT_SQL)) {
                throw new IllegalStateException("No DBT_SQL op-engine handler for op '" + op.op()
                        + "' in blueprint " + bp.getBlueprintKey());
            }
        }

        boolean sqlModelBody = opList.ops().size() == 1
                && OpVocabulary.SQL_MODEL.equals(opList.ops().getFirst().op());
        boolean wholeModelHandler = opList.ops().size() == 1
                && (OpVocabulary.MERGE_ROWS.equals(opList.ops().getFirst().op())
                || OpVocabulary.TAKE_PERIODIC_SNAPSHOT.equals(opList.ops().getFirst().op()));

        String body;
        if (sqlModelBody || wholeModelHandler) {
            OpList.OpEntry op = opList.ops().getFirst();
            Map<String, Object> resolved = resolveCodegenOpConfig(op, params, surface,
                    inst, bp, slug, layout, resolvedLayer, fileFormat, compileNode);
            body = codegenOpEngine.registry().get(op.op(), EmissionEngine.DBT_SQL).emit(EmitContext.builder()
                    .mode(mode)
                    .modeResolver(codegenOpEngine.modeResolver())
                    .config(new ResolvedConfig(resolved))
                    .inputSchema(Schema.empty())
                    .upstreamRef(baseRef)
                    .lakeLayer(resolvedLayer)
                    .build());
        } else {
            body = codegenOpEngine.dbtSql().emit(opList.ops(), baseRef, (op, upstreamRef) -> {
            Map<String, Object> resolved = resolveCodegenOpConfig(op, params, surface,
                    inst, bp, slug, layout, resolvedLayer, fileFormat, compileNode);
            return EmitContext.builder()
                    .mode(mode)
                    .modeResolver(codegenOpEngine.modeResolver())
                    .config(new ResolvedConfig(resolved))
                    .inputSchema(Schema.empty())
                    .upstreamRef(upstreamRef)
                    .lakeLayer(resolvedLayer)
                    .build();
            });
        }

        String entitySlug = slugify(inst.getName());
        StringBuilder sql = new StringBuilder();
        sql.append(opEngineHeader(inst, bp));
        if (!wholeModelHandler) {
            appendDbtModelConfig(sql, slug, entitySlug, layout, resolvedLayer, fileFormat, compileNode);
        }
        sql.append(sqlModelBody || wholeModelHandler ? body : addProcessedAtColumn(body));

        return List.of(markOpEngineArtifact(createArtifact(run, layout.path(),
                "DBT_MODEL", lowerDbtMnemonics(sql.toString()), "dbt_model"), inst, bp));
    }

    private List<GeneratedArtifact> generateRouteRowsWithOpEngine(GenerationRun run,
                                                                   SubPipelineInstance inst,
                                                                   Blueprint bp,
                                                                   OpList.OpEntry op,
                                                                   Map<String, Object> params,
                                                                   ParamSurface surface,
                                                                   String slug,
                                                                   DbtLayout layout,
                                                                   String resolvedLayer,
                                                                   String fileFormat,
                                                                   Map<String, Object> compileNode,
                                                                   Mode mode,
                                                                   String baseRef) {
        Map<String, Object> resolved = resolveCodegenOpConfig(op, params, surface,
                inst, bp, slug, layout, resolvedLayer, fileFormat, compileNode);
        List<Map<String, Object>> routes = new ResolvedConfig(resolved).getMapList("routes");
        boolean includeDefault = !Boolean.FALSE.equals(resolved.get("include_default"));
        List<GeneratedArtifact> out = new ArrayList<>();
        List<String> routeConditions = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> route : routes) {
            String label = firstNonBlank(
                    stringValue(route.get("name")),
                    stringValue(route.get("label")),
                    "route_" + (index + 1));
            String condition = firstNonBlank(
                    stringValue(route.get("condition")),
                    stringValue(route.get("predicate")),
                    "1 = 1");
            routeConditions.add("(" + condition + ")");
            out.add(createRouteArtifact(run, inst, bp, op, resolved, slug, layout, resolvedLayer,
                    fileFormat, compileNode, mode, baseRef, label, condition));
            index++;
        }
        if (includeDefault) {
            String defaultCondition = routeConditions.isEmpty()
                    ? "1 = 1"
                    : "NOT (" + String.join(" OR ", routeConditions) + ")";
            out.add(createRouteArtifact(run, inst, bp, op, resolved, slug, layout, resolvedLayer,
                    fileFormat, compileNode, mode, baseRef, "default", defaultCondition));
        }
        if (out.isEmpty()) {
            out.add(createRouteArtifact(run, inst, bp, op, resolved, slug, layout, resolvedLayer,
                    fileFormat, compileNode, mode, baseRef, "pass_through", "1 = 1"));
        }
        return out;
    }

    private GeneratedArtifact createRouteArtifact(GenerationRun run,
                                                  SubPipelineInstance inst,
                                                  Blueprint bp,
                                                  OpList.OpEntry op,
                                                  Map<String, Object> resolved,
                                                  String slug,
                                                  DbtLayout layout,
                                                  String resolvedLayer,
                                                  String fileFormat,
                                                  Map<String, Object> compileNode,
                                                  Mode mode,
                                                  String baseRef,
                                                  String routeLabel,
                                                  String condition) {
        String routeSlug = slugify(routeLabel);
        Map<String, Object> branchConfig = new LinkedHashMap<>(resolved);
        branchConfig.put("branch_condition", condition);
        String body = codegenOpEngine.registry().get(op.op(), EmissionEngine.DBT_SQL).emit(EmitContext.builder()
                .mode(mode)
                .modeResolver(codegenOpEngine.modeResolver())
                .config(new ResolvedConfig(branchConfig))
                .inputSchema(Schema.empty())
                .upstreamRef(baseRef)
                .lakeLayer(resolvedLayer)
                .build());
        String entitySlug = slugify(inst.getName()) + "__" + routeSlug;
        String routeFile = layout.fileName().replace(".sql", "__" + routeSlug + ".sql");
        String routePath = layout.path().replace(layout.fileName(), routeFile);
        StringBuilder sql = new StringBuilder();
        sql.append(opEngineHeader(inst, bp));
        sql.append("-- Route output: ").append(routeLabel).append("\n");
        appendDbtModelConfig(sql, slug, entitySlug, layout, resolvedLayer, fileFormat, compileNode);
        sql.append(addProcessedAtColumn(body));
        GeneratedArtifact artifact = createArtifact(run, routePath,
                "DBT_MODEL", lowerDbtMnemonics(sql.toString()), "dbt_model");
        return markOpEngineArtifact(artifact, inst, bp);
    }

    private Map<String, Object> resolveCodegenOpConfig(OpList.OpEntry op,
                                                       Map<String, Object> params,
                                                       ParamSurface surface,
                                                       SubPipelineInstance inst,
                                                       Blueprint bp,
                                                       String slug,
                                                       DbtLayout layout,
                                                       String resolvedLayer,
                                                       String fileFormat,
                                                       Map<String, Object> compileNode) {
        Map<String, Object> resolved = new LinkedHashMap<>(
                ParamResolver.resolve(op.op(), op.config(), params, surface));
        String entitySlug = slugify(inst.getName());
        resolved.putIfAbsent("alias", dbtRelationAlias(slug, entitySlug));
        resolved.putIfAbsent("file_format", fileFormat);
        resolved.putIfAbsent("tags", List.of("pulse", slug, entitySlug, layout.dbtLayer(), "op_engine"));
        List<String> partitionBy = compileNodeList(compileNode, "partitionBy");
        if (partitionBy.isEmpty()) {
            partitionBy = stringListValue(params.get("partition_by"));
        }
        if (!partitionBy.isEmpty()) {
            resolved.putIfAbsent("partition_by", partitionBy);
        }
        List<String> clusterBy = stringListValue(params.get("cluster_by"));
        if (!clusterBy.isEmpty()) {
            resolved.putIfAbsent("cluster_by", clusterBy);
        }
        if (OpVocabulary.MERGE_ROWS.equals(op.op()) && !resolved.containsKey("merge_keys")
                && resolved.containsKey("merge_key")) {
            resolved.put("merge_keys", resolved.get("merge_key"));
        }
        if (OpVocabulary.TRACK_HISTORY_SCD2.equals(op.op())) {
            resolved.putIfAbsent("snapshot_name", "snp__" + entitySlug);
            resolved.putIfAbsent("target_schema", "snapshots");
            Object updatedAt = resolved.remove("effective_date_column");
            if (updatedAt != null) {
                resolved.putIfAbsent("updated_at", updatedAt);
            }
        }
        return resolved;
    }

    private String opEngineHeader(SubPipelineInstance inst, Blueprint bp) {
        return "-- Generated by PULSE for " + inst.getName() + "\n"
                + "-- Blueprint: " + bp.getName() + " (" + bp.getBlueprintKey() + ")\n"
                + "-- Codegen engine: CodegenOpEngine\n\n";
    }

    private void appendDbtModelConfig(StringBuilder sql,
                                      String slug,
                                      String entitySlug,
                                      DbtLayout layout,
                                      String resolvedLayer,
                                      String fileFormat,
                                      Map<String, Object> compileNode) {
        sql.append("{{ config(\n");
        sql.append("    materialized='table'");
        sql.append(",\n    alias='").append(dbtRelationAlias(slug, entitySlug)).append("'");
        if (!"gold".equals(resolvedLayer)) {
            sql.append(",\n    file_format='").append(fileFormat).append("'");
        }
        sql.append(",\n    tags=['pulse', '").append(slug).append("', '")
                .append(entitySlug).append("', '").append(layout.dbtLayer()).append("', 'op_engine']");
        List<String> partitionBy = compileNodeList(compileNode, "partitionBy");
        if (!partitionBy.isEmpty()) {
            sql.append(",\n    partition_by=[");
            for (int i = 0; i < partitionBy.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("'").append(partitionBy.get(i)).append("'");
            }
            sql.append("]");
        }
        sql.append("\n) }}\n\n");
    }

    private GeneratedArtifact markOpEngineArtifact(GeneratedArtifact artifact,
                                                   SubPipelineInstance inst,
                                                   Blueprint bp) {
        Map<String, Object> metadata = new LinkedHashMap<>(artifact.getMetadata() == null
                ? Map.of()
                : artifact.getMetadata());
        metadata.put("codegenEngine", "CodegenOpEngine");
        metadata.put("instanceId", inst.getId());
        metadata.put("blueprintKey", bp.getBlueprintKey());
        artifact.setMetadata(metadata);
        return artifact;
    }

    private List<String> compileNodeList(Map<String, Object> compileNode, String key) {
        return compileNode != null ? stringListValue(compileNode.get(key)) : List.of();
    }

    private List<String> stringListValue(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        if (raw instanceof String string && !string.isBlank()) {
            return List.of(string);
        }
        if (raw instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            iterable.forEach(item -> {
                if (item != null) out.add(item.toString());
            });
            return out;
        }
        return List.of();
    }

    private String resolveDbtBaseRefForOpEngine(SubPipelineInstance inst,
                                                List<SubPipelineInstance> instances,
                                                String slug,
                                                CompilePlanService.CompilePlanSnapshot compilePlan) {
        if (inst.getVersionId() != null) {
            List<PortWiring> incoming = wiringRepo.findByVersionIdAndTargetInstanceId(
                    inst.getVersionId(), inst.getId());
            for (PortWiring wiring : incoming) {
                SubPipelineInstance upstream = findInstance(wiring.getSourceInstanceId(), instances);
                if (upstream == null) {
                    continue;
                }
                Blueprint upstreamBlueprint = blueprintRepo.findByBlueprintKey(upstream.getBlueprintKey()).orElse(null);
                if (upstreamBlueprint != null && "INGESTION".equals(upstreamBlueprint.getCategory().name())) {
                    return String.format("{{ source('bronze_%s', '%s') }}",
                            resolveSourceSystemSlug(upstream, instances, deriveDomainSlug(compilePlan)),
                            slugify(upstream.getName()));
                }
                return "{{ ref('" + dbtModelIdentifier(upstream, instances, compilePlan) + "') }}";
            }
        }
        return String.format("{{ source('bronze_%s', '%s_input') }}", slug, slug);
    }

    private boolean requiresDateDimArtifact(List<GeneratedArtifact> artifacts,
                                            CompilePlanService.CompilePlanSnapshot compilePlan) {
        if (compilePlan.advanceTimeContracts() != null && !compilePlan.advanceTimeContracts().isEmpty()) {
            return true;
        }
        return artifacts.stream()
                .filter(artifact -> "DBT_MODEL".equals(artifact.getFileType())
                        || "DBT_SNAPSHOT".equals(artifact.getFileType()))
                .map(GeneratedArtifact::getContent)
                .filter(Objects::nonNull)
                .anyMatch(content -> content.contains("ref('date_dim')")
                        || content.contains("ref(\"date_dim\")"));
    }

    private String generateDateDimSql(String tenantId, String domainSlug) {
        String tenantSlug = slugify(firstNonBlank(tenantId, "default"));
        String resolvedDomainSlug = slugify(firstNonBlank(domainSlug, "default"));
        return """
                -- Generated by PULSE as a tenant-scoped data-plane date dimension.
                -- Runtime business-day mutation uses the packaged calendar bundle; this
                -- relation exists for downstream dbt/Spark SQL joins and reporting.

                {{ config(
                    materialized='table',
                    alias='date_dim',
                    tags=['pulse', 'calendar', 'date_dim', 'tenant_%s', 'domain_%s']
                ) }}

                WITH date_spine AS (
                    SELECT explode(sequence(to_date('1900-01-01'), to_date('2100-12-31'), interval 1 day)) AS calendar_date
                )
                SELECT
                    CAST(date_format(calendar_date, 'yyyyMMdd') AS INT) AS date_key,
                    calendar_date,
                    date_format(calendar_date, 'yyyy-MM-dd') AS iso_date,
                    year(calendar_date) AS year,
                    quarter(calendar_date) AS quarter,
                    month(calendar_date) AS month,
                    day(calendar_date) AS day_of_month,
                    dayofweek(calendar_date) AS day_of_week,
                    date_format(calendar_date, 'E') AS day_name_short,
                    date_format(calendar_date, 'EEEE') AS day_name,
                    weekofyear(calendar_date) AS iso_week,
                    date_trunc('month', calendar_date) AS month_start_date,
                    last_day(calendar_date) AS month_end_date,
                    date_trunc('quarter', calendar_date) AS quarter_start_date,
                    add_months(date_trunc('quarter', calendar_date), 3) - interval 1 day AS quarter_end_date,
                    date_trunc('year', calendar_date) AS year_start_date,
                    add_months(date_trunc('year', calendar_date), 12) - interval 1 day AS year_end_date,
                    CASE WHEN dayofweek(calendar_date) IN (1, 7) THEN true ELSE false END AS is_weekend,
                    '%s' AS tenant_id,
                    '%s' AS pulse_domain_slug
                FROM date_spine
                """.formatted(tenantSlug, resolvedDomainSlug, tenantId, resolvedDomainSlug);
    }

    /**
     * Wraps a generated SELECT body with a dbt `{% if is_incremental() %}` guard so
     * subsequent runs of an incremental model only pull rows newer than what's already
     * in {{ this }}.  The first run (no {{ this }} yet) pulls everything.
     *
     * <p>Inserts the guard right before any trailing WHERE clause, or appends a new
     * WHERE if none exists.  Preserves SELECT/FROM/JOIN/GROUP BY untouched.
     */
    private String wrapIncrementalGuard(String sqlBody, String watermarkColumn) {
        String sourceWatermarkExpression = "_pulse_processed_at".equals(watermarkColumn)
                ? "current_timestamp()"
                : watermarkColumn;
        String guard = "\n{% if is_incremental() %}\n"
                + "  WHERE " + sourceWatermarkExpression + " > (SELECT COALESCE(MAX(" + watermarkColumn + "), '1970-01-01') FROM {{ this }})\n"
                + "{% endif %}\n";
        // If the generated body already has a WHERE clause, append the incremental
        // predicate as an AND to preserve filter semantics.
        int wherePos = findTopLevelWhere(sqlBody);
        if (wherePos >= 0) {
            String head = sqlBody.substring(0, wherePos);
            String tail = sqlBody.substring(wherePos);
            // Replace `WHERE x` with `WHERE x AND {% if is_incremental() %} ... {% endif %}`.
            // Find end of body to inject the AND...endif before final newline.
            String trimmedTail = tail.stripTrailing();
            String incrAnd = "\n  {% if is_incremental() %}AND " + sourceWatermarkExpression
                    + " > (SELECT COALESCE(MAX(" + watermarkColumn + "), '1970-01-01') FROM {{ this }}){% endif %}\n";
            return head + trimmedTail + incrAnd;
        }
        return sqlBody.stripTrailing() + guard;
    }

    private int findTopLevelWhere(String sql) {
        // Naive top-level WHERE detection — finds the first WHERE not inside a sub-query.
        // Sufficient for codegen output; user-edited models would need a proper parser.
        int depth = 0;
        for (int i = 0; i < sql.length() - 5; i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (depth == 0 && (c == 'W' || c == 'w')
                    && i + 5 < sql.length()
                    && sql.regionMatches(true, i, "WHERE", 0, 5)
                    && (i == 0 || Character.isWhitespace(sql.charAt(i - 1)))
                    && Character.isWhitespace(sql.charAt(i + 5))) {
                return i;
            }
        }
        return -1;
    }

    private List<String> firstNonEmptyStringList(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value instanceof List<?> list) {
                List<String> normalized = list.stream()
                        .map(Object::toString)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                if (!normalized.isEmpty()) {
                    return normalized;
                }
            }
            if (value instanceof String s) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    return List.of(trimmed);
                }
            }
        }
        return List.of();
    }

    private String firstNonBlankString(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value instanceof String s) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return "";
    }

    /** Injects `current_timestamp() as _pulse_processed_at` into a generated SELECT if absent. */
    private String addProcessedAtColumn(String sqlBody) {
        if (sqlBody.contains("_pulse_processed_at")) {
            return sqlBody;
        }
        int selectIdx = sqlBody.indexOf("SELECT");
        if (selectIdx < 0) {
            return sqlBody + "\n-- NB: generator could not locate SELECT to inject _pulse_processed_at\n";
        }
        int fromIdx = sqlBody.indexOf("\nFROM ", selectIdx);
        if (fromIdx < 0) {
            return sqlBody;
        }
        String head = sqlBody.substring(0, fromIdx).trim();
        String tail = sqlBody.substring(fromIdx);
        if (!head.endsWith(",")) head = head + ",";
        return head + "\n    current_timestamp() as _pulse_processed_at" + tail;
    }

    private String deriveDomainSlug(CompilePlanService.CompilePlanSnapshot compilePlan) {
        String ns = compilePlan.namespace();
        if (ns == null || ns.isBlank()) return "default";
        String[] parts = ns.split("/");
        if (parts.length == 0) return "default";
        return parts[0];
    }

    private String resolveFileFormatFromHints(Map<String, Object> node) {
        if (node == null) return "delta";
        Object hints = node.get("artifactHints");
        if (hints instanceof Map<?, ?> hintMap) {
            Object ff = hintMap.get("fileFormat");
            if (ff != null && !ff.toString().isBlank()) return ff.toString();
        }
        return "delta";
    }

    private String resolveSourceSystemSlug(SubPipelineInstance target,
                                           List<SubPipelineInstance> allInstances,
                                           String fallbackDomainSlug) {
        List<PortWiring> wirings = target.getVersionId() != null
                ? wiringRepo.findByVersionIdAndTargetInstanceId(target.getVersionId(), target.getId())
                : List.of();
        for (PortWiring w : wirings) {
            for (SubPipelineInstance upstream : allInstances) {
                if (!upstream.getId().equals(w.getSourceInstanceId())) continue;
                Blueprint upBp = blueprintRepo.findByBlueprintKey(upstream.getBlueprintKey()).orElse(null);
                if (upBp != null && "INGESTION".equals(upBp.getCategory().name())) {
                    ConnectorInstance ci = resolveConnectorInstance(upstream.getParams());
                    if (ci != null && ci.getName() != null) {
                        return slugify(ci.getName());
                    }
                    return slugify(upstream.getName());
                }
                String recurse = resolveSourceSystemSlug(upstream, allInstances, fallbackDomainSlug);
                if (recurse != null && !recurse.isBlank()) return recurse;
            }
        }
        if ("INGESTION".equals(
                blueprintRepo.findByBlueprintKey(target.getBlueprintKey())
                        .map(bp -> bp.getCategory().name()).orElse(""))) {
            ConnectorInstance ci = resolveConnectorInstance(target.getParams());
            if (ci != null && ci.getName() != null) return slugify(ci.getName());
        }
        return fallbackDomainSlug;
    }

    private DbtLayout resolveDbtLayout(String bpKey, String layer, String domainSlug,
                                       String sourceSlug, String entitySlug) {
        String dbtLayer;
        String fileName;
        String path;
        switch (bpKey) {
            case "BronzeToSilverCleaning", "SchemaNormalization", "PIIMasking",
                 "DedupeAndMerge", "JsonFlatten" -> {
                dbtLayer = "staging";
                String suffix = switch (bpKey) {
                    case "SchemaNormalization" -> "_normalized";
                    case "PIIMasking" -> "_masked";
                    case "DedupeAndMerge" -> "_deduped";
                    case "JsonFlatten" -> "_flat";
                    default -> "";
                };
                fileName = String.format("stg__%s__%s%s.sql", sourceSlug, entitySlug, suffix);
                path = String.format("dbt_project/models/staging/%s/%s", sourceSlug, fileName);
            }
            case "IncrementalMerge" -> {
                if ("silver".equals(layer)) {
                    dbtLayer = "intermediate";
                    fileName = String.format("int__%s__%s_incr.sql", domainSlug, entitySlug);
                    path = String.format("dbt_project/models/intermediate/%s/%s", domainSlug, fileName);
                } else {
                    dbtLayer = "marts";
                    fileName = String.format("incr__%s.sql", entitySlug);
                    path = String.format("dbt_project/models/marts/%s/%s", domainSlug, fileName);
                }
            }
            case "FactBuild" -> {
                dbtLayer = "marts";
                fileName = String.format("fct__%s.sql", entitySlug);
                path = String.format("dbt_project/models/marts/%s/%s", domainSlug, fileName);
            }
            case "WideDenormalizedMart" -> {
                dbtLayer = "marts";
                fileName = String.format("mart__%s.sql", entitySlug);
                path = String.format("dbt_project/models/marts/%s/%s", domainSlug, fileName);
            }
            case "AggregateMaterialization" -> {
                dbtLayer = "marts";
                fileName = String.format("agg__%s.sql", entitySlug);
                path = String.format("dbt_project/models/marts/%s/%s", domainSlug, fileName);
            }
            case "ReferenceDataPublish" -> {
                dbtLayer = "marts";
                fileName = String.format("ref__%s.sql", entitySlug);
                path = String.format("dbt_project/models/marts/%s/%s", domainSlug, fileName);
            }
            case "FeatureTablePublish" -> {
                dbtLayer = "marts";
                fileName = String.format("feat__%s.sql", entitySlug);
                path = String.format("dbt_project/models/marts/%s/%s", domainSlug, fileName);
            }
            case "SCD2Dimension" -> {
                dbtLayer = "snapshots";
                fileName = String.format("snp__%s.sql", entitySlug);
                path = String.format("dbt_project/snapshots/%s/%s", domainSlug, fileName);
            }
            case "SnapshotModel" -> {
                dbtLayer = "marts";
                fileName = String.format("snp__%s.sql", entitySlug);
                path = String.format("dbt_project/models/marts/%s/%s", domainSlug, fileName);
            }
            case "GenericFilter", "GenericAggregate", "GenericJoin", "JsonStruct" -> {
                if ("gold".equals(layer)) {
                    dbtLayer = "marts";
                    fileName = String.format("mart__%s.sql", entitySlug);
                    path = String.format("dbt_project/models/marts/%s/%s", domainSlug, fileName);
                } else {
                    dbtLayer = "intermediate";
                    fileName = String.format("int__%s__%s.sql", domainSlug, entitySlug);
                    path = String.format("dbt_project/models/intermediate/%s/%s", domainSlug, fileName);
                }
            }
            case "GenericRouter" -> {
                dbtLayer = "intermediate";
                fileName = String.format("int__%s__%s.sql", domainSlug, entitySlug);
                path = String.format("dbt_project/models/intermediate/%s/%s", domainSlug, fileName);
            }
            default -> {
                dbtLayer = "intermediate";
                fileName = String.format("int__%s__%s.sql", domainSlug, entitySlug);
                path = String.format("dbt_project/models/intermediate/%s/%s", domainSlug, fileName);
            }
        }
        return new DbtLayout(path, fileName, dbtLayer);
    }

    private record DbtLayout(String path, String fileName, String dbtLayer) {}

    private String generateSnapshotSql(SubPipelineInstance inst, Blueprint bp, String slug,
                                       String domainSlug, String entitySlug,
                                       String compileFileFormat, String tenantId) {
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
        String strategy = stringParam(params, "strategy", "timestamp");
        String fileFormat = snapshotFileFormat(params, compileFileFormat);
        List<BronzeSourceRegistration> bronzeSourceRegistrations =
                collectBronzeSourceRegistrations(inst, slug, tenantId);
        List<String> preHooks = bronzeSourcePreHooks(bronzeSourceRegistrations);
        List<String> uniqueKey = firstNonEmptyStringList(params,
                "unique_key",
                "unique_key_columns",
                "business_key_columns",
                "business_key",
                "merge_key");
        List<String> checkColumns = firstNonEmptyStringList(params,
                "check_cols",
                "change_detection_columns",
                "tracked_columns");
        String upstreamPort = "SnapshotModel".equals(bp.getBlueprintKey()) ? "source_data" : "data_input";
        String upstreamRef = resolveUpstreamFromExpr(inst, upstreamPort, slug);

        StringBuilder sql = new StringBuilder();
        sql.append("-- Generated by PULSE for ").append(inst.getName()).append("\n");
        sql.append("-- Blueprint: ").append(bp.getName()).append(" (").append(bp.getBlueprintKey()).append(")\n\n");
        sql.append("{% snapshot snp__").append(entitySlug).append(" %}\n\n");
        sql.append("{{ config(\n");
        sql.append("    target_schema='snapshots',\n");
        sql.append("    unique_key='").append(uniqueKey.isEmpty() ? "id" : uniqueKey.get(0)).append("',\n");
        sql.append("    strategy='").append(strategy).append("',\n");
        sql.append("    file_format='").append(fileFormat).append("',\n");
        String locationRoot = resolveDbtLocationRoot(inst, slug, tenantId,
                firstNonBlankString(params, "lake_layer", "gold"));
        if (locationRoot != null) {
            sql.append("    location_root='").append(locationRoot).append("',\n");
        }
        if ("timestamp".equals(strategy)) {
            sql.append("    updated_at='")
                    .append(firstNonBlankString(params, "updated_at_column", "effective_date_column", "updated_at"))
                    .append("',\n");
        } else if (checkColumns.isEmpty()) {
            sql.append("    check_cols='all',\n");
        } else {
            sql.append("    check_cols=[");
            for (int i = 0; i < checkColumns.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("'").append(checkColumns.get(i)).append("'");
            }
            sql.append("],\n");
        }
        sql.append("    tags=['pulse', '").append(slug).append("', '").append(entitySlug).append("', 'snapshots']");
        appendDbtPreHooks(sql, preHooks);
        sql.append("\n");
        sql.append(") }}\n\n");
        // PKT-0027: SCD2 automatic effective dates — dbt_valid_from / dbt_valid_to
        // are managed by the dbt snapshot strategy, but we add explicit effective_from/to
        // columns for downstream consumption when the unique_key is a business key (e.g., loan_id).
        sql.append("SELECT\n    *,\n");
        sql.append("    current_timestamp() as _pulse_processed_at,\n");
        sql.append("    current_timestamp() as effective_from,\n");
        sql.append("    CAST(NULL AS TIMESTAMP) as effective_to\n");
        sql.append("FROM ").append(upstreamRef).append("\n\n");
        sql.append("{% endsnapshot %}\n");
        return sql.toString();
    }

    private String generateSnapshotModelSql(SubPipelineInstance inst, Blueprint bp, String slug,
                                            String entitySlug, String compileFileFormat, String tenantId) {
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
        String fileFormat = snapshotFileFormat(params, compileFileFormat);
        String businessDateVariable = firstNonBlankString(params, "business_date_var", "snapshot_date_var");
        if (businessDateVariable.isBlank()) {
            businessDateVariable = "pulse_business_date";
        }
        String runIdVariable = firstNonBlankString(params, "run_id_var", "snapshot_run_id_var");
        if (runIdVariable.isBlank()) {
            runIdVariable = "pulse_run_id";
        }
        String partitionColumn = firstNonBlankString(params, "snapshot_partition_column", "partition_column", "as_of_column");
        if (partitionColumn.isBlank()) {
            partitionColumn = "ds";
        }
        List<String> businessKeys = firstNonEmptyStringList(params,
                "unique_key",
                "unique_key_columns",
                "business_key_columns",
                "business_key",
                "natural_key",
                "merge_key");
        if (businessKeys.isEmpty()) {
            businessKeys = List.of("id");
        }
        String sourceDateColumn = firstNonBlankString(params, "source_date_column", "source_ds_column", "effective_date_column");
        String upstreamRef = resolveUpstreamFromExpr(inst, "source_data", slug);
        List<BronzeSourceRegistration> bronzeSourceRegistrations =
                collectBronzeSourceRegistrations(inst, slug, tenantId);
        List<String> preHooks = bronzeSourcePreHooks(bronzeSourceRegistrations);

        StringBuilder sql = new StringBuilder();
        sql.append("-- Generated by PULSE for ").append(inst.getName()).append("\n");
        sql.append("-- Blueprint: ").append(bp.getName()).append(" (").append(bp.getBlueprintKey()).append(")\n");
        sql.append("-- SnapshotModel is a point-in-time incremental model, not a dbt snapshot block.\n\n");
        sql.append("{{ config(\n");
        sql.append("    materialized='incremental',\n");
        sql.append("    file_format='").append(fileFormat).append("',\n");
        sql.append("    partition_by=['").append(partitionColumn).append("'],\n");
        sql.append("    unique_key=[");
        for (int i = 0; i < businessKeys.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("'").append(businessKeys.get(i)).append("'");
        }
        sql.append(", '").append(partitionColumn).append("'],\n");
        sql.append("    incremental_strategy='merge',\n");
        sql.append("    on_schema_change='fail',\n");
        sql.append("    tags=['pulse', '").append(slug).append("', '").append(entitySlug).append("', 'point_in_time_snapshot']");
        appendDbtPreHooks(sql, preHooks);
        sql.append("\n");
        sql.append(") }}\n\n");
        sql.append("WITH source_data AS (\n");
        sql.append("    SELECT *\n");
        sql.append("    FROM ").append(upstreamRef).append("\n");
        if (!sourceDateColumn.isBlank()) {
            sql.append("    WHERE CAST(").append(quoteSqlIdentifier(sourceDateColumn)).append(" AS DATE) <= DATE('{{ var(\"")
                    .append(businessDateVariable).append("\") }}')\n");
        }
        sql.append(")\n");
        sql.append("SELECT\n");
        sql.append("    source_data.*,\n");
        sql.append("    DATE('{{ var(\"").append(businessDateVariable).append("\") }}') AS ")
                .append(quoteSqlIdentifier(partitionColumn)).append(",\n");
        sql.append("    current_timestamp() AS _pulse_processing_ts,\n");
        sql.append("    '{{ var(\"").append(runIdVariable).append("\") }}' AS _pulse_run_id,\n");
        sql.append("    '{{ this.identifier }}' AS _pulse_snapshot_model\n");
        sql.append("FROM source_data\n");
        return sql.toString();
    }

    private String snapshotFileFormat(Map<String, Object> params, String compileFileFormat) {
        String requested = firstNonBlankString(params, "snapshot_file_format", "lake_format", "file_format");
        if (requested.isBlank()) {
            requested = compileFileFormat != null && !compileFileFormat.isBlank() ? compileFileFormat : "delta";
        }
        String normalized = requested.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "iceberg", "hudi" -> normalized;
            default -> "delta";
        };
    }

    @SuppressWarnings("unchecked")
    private String generateJoinSqlBody(SubPipelineInstance inst, String slug) {
        var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
        String joinType = ((String) params.getOrDefault("join_type", "inner")).toUpperCase().replace("_", " ");
        String aliasLeft = (String) params.getOrDefault("alias_left", "l");
        String aliasRight = (String) params.getOrDefault("alias_right", "r");

        String leftRef = resolveUpstreamFromExpr(inst, "left_input", slug);
        String rightRef = resolveUpstreamFromExpr(inst, "right_input", slug);

        String selectExpr = aliasLeft + ".*";
        Object selectCols = params.get("select_columns");
        if (selectCols instanceof List<?> cols && !cols.isEmpty()) {
            selectExpr = String.join(",\n    ", cols.stream().map(Object::toString).toList());
        }

        StringBuilder onClause = new StringBuilder();
        Object joinKeys = params.get("join_keys");
        if (joinKeys instanceof List<?> keys && !keys.isEmpty()) {
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) onClause.append("\n    AND ");
                var pair = (Map<String, Object>) keys.get(i);
                String leftCol = (String) pair.getOrDefault("left_column", "id");
                String rightCol = (String) pair.getOrDefault("right_column", "id");
                onClause.append(aliasLeft).append(".").append(leftCol)
                        .append(" = ").append(aliasRight).append(".").append(rightCol);
            }
        } else {
            onClause.append(aliasLeft).append(".id = ").append(aliasRight).append(".id");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT\n    ").append(selectExpr).append("\n");
        sql.append("FROM ").append(leftRef).append(" AS ").append(aliasLeft).append("\n");
        sql.append(joinType).append(" JOIN ").append(rightRef).append(" AS ").append(aliasRight).append("\n");
        sql.append("    ON ").append(onClause).append("\n");
        return sql.toString();
    }

    @SuppressWarnings("unchecked")
    private String generateAggregateSqlBody(SubPipelineInstance inst, String slug) {
        var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
        String inputPort = "AggregateMaterialization".equals(inst.getBlueprintKey()) ? "detail_data" : "data_input";
        String upstreamRef = resolveUpstreamFromExpr(inst, inputPort, slug);

        List<String> groupByCols = new ArrayList<>();
        Object groupBy = params.get("group_by_columns");
        if (!(groupBy instanceof List<?>)) {
            groupBy = params.get("group_by");
        }
        if (groupBy instanceof List<?> cols) {
            cols.forEach(c -> groupByCols.add(c.toString()));
        }

        List<String> aggExprs = new ArrayList<>();
        Object aggs = params.get("aggregations");
        if (aggs instanceof List<?> aggList) {
            for (var aggObj : aggList) {
                var agg = (Map<String, Object>) aggObj;
                String col = (String) agg.getOrDefault("column", "*");
                String func = ((String) agg.getOrDefault("function", "count")).toUpperCase();
                String alias = (String) agg.getOrDefault("alias", func.toLowerCase() + "_" + col);
                if ("COUNT_DISTINCT".equals(func)) {
                    aggExprs.add("COUNT(DISTINCT " + col + ") AS " + alias);
                } else {
                    aggExprs.add(func + "(" + col + ") AS " + alias);
                }
            }
        }

        String havingClause = (String) params.get("having_clause");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT\n");

        List<String> selectParts = new ArrayList<>(groupByCols);
        selectParts.addAll(aggExprs);
        if (selectParts.isEmpty()) {
            sql.append("    *\n");
        } else {
            sql.append("    ").append(String.join(",\n    ", selectParts)).append("\n");
        }
        sql.append("FROM ").append(upstreamRef).append("\n");
        if (!groupByCols.isEmpty()) {
            sql.append("GROUP BY ").append(String.join(", ", groupByCols)).append("\n");
        }
        if (havingClause != null && !havingClause.isBlank()) {
            sql.append("HAVING ").append(havingClause).append("\n");
        }
        return sql.toString();
    }

    private String generateBronzeToSilverCleaningSqlBody(SubPipelineInstance inst, String slug) {
        String upstreamRef = resolveUpstreamFromExpr(inst, "raw_input", slug);
        return "SELECT *\nFROM " + upstreamRef + "\n";
    }

    @SuppressWarnings("unchecked")
    private String generatePiiMaskingSqlBody(SubPipelineInstance inst, String slug) {
        var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
        String upstreamRef = resolveUpstreamFromExpr(inst, "data_input", slug);
        String relationExpr = dbtRelationExpression(upstreamRef);
        List<String> columnsToMask = params.get("columns_to_mask") instanceof List<?> columns
                ? columns.stream().map(Object::toString).filter(column -> !column.isBlank()).toList()
                : List.of("borrower_ssn_masked");
        if (columnsToMask.isEmpty()) {
            columnsToMask = List.of("borrower_ssn_masked");
        }
        if (params.get("source_columns") instanceof List<?> sourceColumns && !sourceColumns.isEmpty()) {
            Set<String> maskSet = new LinkedHashSet<>(columnsToMask);
            String selectList = sourceColumns.stream()
                    .map(Object::toString)
                    .filter(column -> !column.isBlank())
                    .map(column -> maskSet.contains(column)
                            ? ssnMaskSqlExpression(column) + " AS " + quoteSqlIdentifier(column)
                            : quoteSqlIdentifier(column))
                    .collect(Collectors.joining(",\n    "));
            return "SELECT\n    " + selectList + ",\n    current_timestamp() as _pulse_processed_at\nFROM "
                    + upstreamRef + "\n";
        }
        String maskList = columnsToMask.stream()
                .map(column -> "'" + column.replace("'", "\\'") + "'")
                .collect(Collectors.joining(", "));

        return """
                {%% set pulse_pii_source = %s %%}
                {%% set pulse_pii_columns = adapter.get_columns_in_relation(pulse_pii_source) %%}
                {%% set pulse_pii_mask_columns = [%s] %%}
                SELECT
                {%% for column in pulse_pii_columns %%}
                    {%% if column.name in pulse_pii_mask_columns %%}
                    CASE
                        WHEN `{{ column.name }}` IS NULL THEN NULL
                        WHEN regexp_extract(CAST(`{{ column.name }}` AS STRING), '(\\\\d{4})$', 1) = '' THEN CAST(`{{ column.name }}` AS STRING)
                        ELSE concat('XXX-XX-', regexp_extract(CAST(`{{ column.name }}` AS STRING), '(\\\\d{4})$', 1))
                    END AS `{{ column.name }}`
                    {%% else %%}
                    `{{ column.name }}`
                    {%% endif %%}{%% if not loop.last %%},{%% endif %%}
                {%% endfor %%},
                    current_timestamp() as _pulse_processed_at
                FROM {{ pulse_pii_source }}
                """.formatted(relationExpr, maskList);
    }

    private String ssnMaskSqlExpression(String column) {
        String quoted = quoteSqlIdentifier(column);
        return """
                CASE
                        WHEN %1$s IS NULL THEN NULL
                        WHEN regexp_extract(CAST(%1$s AS STRING), '(\\\\d{4})$', 1) = '' THEN CAST(%1$s AS STRING)
                        ELSE concat('XXX-XX-', regexp_extract(CAST(%1$s AS STRING), '(\\\\d{4})$', 1))
                    END""".formatted(quoted);
    }

    private String quoteSqlIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private String dbtRelationExpression(String dbtExpression) {
        String trimmed = dbtExpression == null ? "" : dbtExpression.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            return trimmed.substring(2, trimmed.length() - 2).trim();
        }
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private String generateFilterSqlBody(SubPipelineInstance inst, String slug) {
        var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
        String upstreamRef = resolveUpstreamFromExpr(inst, "data_input", slug);
        String filterMode = (String) params.getOrDefault("filter_mode", "visual");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT *\n");
        sql.append("FROM ").append(upstreamRef).append("\n");

        if ("sql".equals(filterMode)) {
            String rawSql = (String) params.getOrDefault("raw_sql", "1 = 1");
            sql.append("WHERE ").append(rawSql).append("\n");
        } else {
            Object conditions = params.get("conditions");
            if (conditions instanceof List<?> condList && !condList.isEmpty()) {
                sql.append("WHERE\n");
                for (int i = 0; i < condList.size(); i++) {
                    var cond = (Map<String, Object>) condList.get(i);
                    String column = (String) cond.getOrDefault("column", "id");
                    String operator = (String) cond.getOrDefault("operator", "eq");
                    String value = cond.get("value") != null ? cond.get("value").toString() : "";
                    String logic = (String) cond.getOrDefault("logic", "AND");

                    if (i > 0) {
                        sql.append("    ").append(logic).append(" ");
                    } else {
                        sql.append("    ");
                    }
                    sql.append(mapFilterOperator(column, operator, value)).append("\n");
                }
            } else {
                sql.append("WHERE 1 = 1\n");
            }
        }
        return sql.toString();
    }

    @SuppressWarnings("unchecked")
    private String generateDedupeAndMergeSqlBody(SubPipelineInstance inst, String slug) {
        var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
        String upstreamRef = resolveUpstreamFromExpr(inst, "input_data", slug);

        List<String> matchKeys = new ArrayList<>();
        Object keys = params.get("match_keys");
        if (keys instanceof List<?> keyList) {
            for (Object key : keyList) {
                matchKeys.add(key.toString());
            }
        }
        if (matchKeys.isEmpty()) {
            matchKeys = List.of("loan_id");
        }

        List<String> orderByClauses = new ArrayList<>();
        Object orderBy = params.get("order_by_columns");
        if (orderBy instanceof List<?> orderList) {
            for (Object entry : orderList) {
                if (entry instanceof Map<?, ?> map) {
                    String column = map.get("column") != null ? map.get("column").toString() : "";
                    if (column.isBlank()) {
                        continue;
                    }
                    String direction = map.get("direction") != null ? map.get("direction").toString().toUpperCase() : "ASC";
                    if (!"DESC".equals(direction)) {
                        direction = "ASC";
                    }
                    orderByClauses.add(column + " " + direction);
                }
            }
        }
        if (orderByClauses.isEmpty()) {
            orderByClauses = List.of("last_payment_date DESC", "loan_number ASC");
        }

        String dedupMethod = params.get("dedup_method") != null
                ? params.get("dedup_method").toString().trim().toUpperCase()
                : "ROW_NUMBER";
        if (!Set.of("ROW_NUMBER", "RANK", "DENSE_RANK").contains(dedupMethod)) {
            dedupMethod = "ROW_NUMBER";
        }

        StringBuilder sql = new StringBuilder();
        sql.append("-- Dedupe & Merge: keep one canonical row per business key\n");
        sql.append("WITH ranked AS (\n");
        sql.append("    SELECT\n");
        sql.append("        *,\n");
        sql.append("        ").append(dedupMethod).append("() OVER (\n");
        sql.append("            PARTITION BY ").append(String.join(", ", matchKeys)).append("\n");
        sql.append("            ORDER BY ").append(String.join(", ", orderByClauses)).append("\n");
        sql.append("        ) AS _pulse_dedup_rank\n");
        sql.append("    FROM ").append(upstreamRef).append("\n");
        sql.append(")\n");
        sql.append("SELECT\n");
        sql.append("    *\n");
        sql.append("FROM ranked\n");
        sql.append("WHERE _pulse_dedup_rank = 1\n");
        return sql.toString();
    }

    @SuppressWarnings("unchecked")
    private String generateJsonFlattenSqlBody(SubPipelineInstance inst, String slug) {
        var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
        String upstreamRef = resolveUpstreamFromExpr(inst, "data_input", slug);
        String starSource = upstreamRef;
        if (upstreamRef.startsWith("{{") && upstreamRef.endsWith("}}")) {
            starSource = upstreamRef.substring(2, upstreamRef.length() - 2).trim();
        } else {
            starSource = "ref('" + upstreamRef + "')";
        }
        String separator = (String) params.getOrDefault("separator", "_");
        boolean explodeArrays = booleanParam(params, "explode_arrays", false);
        boolean keepOriginal = booleanParam(params, "keep_original", false);
        String prefix = (String) params.getOrDefault("prefix", "");
        int maxDepth = params.containsKey("max_depth") ? ((Number) params.get("max_depth")).intValue() : 3;

        List<String> sourceCols = new ArrayList<>();
        Object src = params.get("source_columns");
        if (src instanceof List<?> cols) cols.forEach(c -> sourceCols.add(c.toString()));

        StringBuilder sql = new StringBuilder();
        sql.append("-- JSON Flatten: expands nested struct/JSON columns into flat columns\n");
        sql.append("-- Separator: '").append(separator).append("', Max depth: ").append(maxDepth).append("\n");

        if (sourceCols.isEmpty() || (sourceCols.size() == 1 && "*".equals(sourceCols.get(0)))) {
            sql.append("SELECT\n");
            sql.append("    *\n");
        } else {
            sql.append("SELECT\n");
            if (keepOriginal) {
                sql.append("    *,\n");
            } else {
                sql.append("    {{ dbt_utils.star(from=").append(starSource);
                sql.append(", except=[");
                sql.append(sourceCols.stream().map(c -> "\"" + c + "\"").reduce((a, b) -> a + ", " + b).orElse(""));
                sql.append("]");
                sql.append(") }},\n");
            }
            for (String col : sourceCols) {
                String p = prefix.isEmpty() ? col + separator : prefix + col + separator;
                sql.append("    -- Flatten ").append(col).append(" (prefix: ").append(p).append(")\n");
                sql.append("    ").append(col).append(".*\n");
            }
        }

        if (explodeArrays) {
            sql.append("FROM ").append(upstreamRef).append("\n");
            for (String col : sourceCols) {
                sql.append("LATERAL VIEW OUTER EXPLODE(").append(col).append(") ").append(col).append("_exploded AS ").append(col).append("_element\n");
            }
        } else {
            sql.append("FROM ").append(upstreamRef).append("\n");
        }

        return sql.toString();
    }

    @SuppressWarnings("unchecked")
    private String generateJsonStructSqlBody(SubPipelineInstance inst, String slug) {
        var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
        String upstreamRef = resolveUpstreamFromExpr(inst, "data_input", slug);
        String outputFormat = (String) params.getOrDefault("output_format", "struct");
        boolean dropSource = Boolean.TRUE.equals(params.get("drop_source_columns"));

        List<Map<String, Object>> mappings = parseJsonStructMappings(params.get("mappings"));

        Set<String> mappedColumns = new LinkedHashSet<>();
        for (var mapping : mappings) {
            Object fields = mapping.get("fields");
            if (fields instanceof List<?> fieldList) {
                for (var f : fieldList) {
                    if (f instanceof Map<?, ?> fm) {
                        String srcCol = fm.get("source_column") != null ? fm.get("source_column").toString() : "";
                        if (!srcCol.isEmpty()) mappedColumns.add(srcCol);
                    }
                }
            }
        }

        StringBuilder sql = new StringBuilder();
        sql.append("-- Build JSON/Struct: combines flat columns into nested structures\n");
        sql.append("SELECT\n");

        // Passthrough columns
        Object passthrough = params.get("passthrough_columns");
        if (passthrough instanceof List<?> ptCols && !ptCols.isEmpty()) {
            if (ptCols.size() == 1 && "*".equals(ptCols.get(0))) {
                if (dropSource && !mappedColumns.isEmpty()) {
                    sql.append("    {{ dbt_utils.star(from=").append(relationMacroArg(upstreamRef)).append(", except=[");
                    sql.append(mappedColumns.stream().map(c -> "\"" + c + "\"").reduce((a, b) -> a + ", " + b).orElse(""));
                    sql.append("]) }},\n");
                } else {
                    sql.append("    *,\n");
                }
            } else {
                for (var col : ptCols) {
                    sql.append("    ").append(col).append(",\n");
                }
            }
        } else if (!dropSource) {
            sql.append("    *,\n");
        }

        // Build struct/json columns
        for (var mapping : mappings) {
            String structName = (String) mapping.getOrDefault("struct_name", "nested");
            Object fields = mapping.get("fields");
            List<String> fieldExprs = new ArrayList<>();

            if (fields instanceof List<?> fieldList) {
                for (var f : fieldList) {
                    if (f instanceof Map<?, ?> fm) {
                        String srcCol = fm.get("source_column") != null ? fm.get("source_column").toString() : "";
                        String asName = fm.get("as") != null ? fm.get("as").toString() : srcCol;
                        if (!srcCol.isEmpty()) {
                            fieldExprs.add(srcCol + " AS " + asName);
                        }
                    }
                }
            }

            if ("json_string".equals(outputFormat)) {
                sql.append("    TO_JSON(NAMED_STRUCT(\n");
                for (int i = 0; i < fieldExprs.size(); i++) {
                    String[] parts = fieldExprs.get(i).split(" AS ");
                    String src = parts[0].trim();
                    String name = parts.length > 1 ? parts[1].trim() : src;
                    sql.append("        '").append(name).append("', ").append(src);
                    if (i < fieldExprs.size() - 1) sql.append(",");
                    sql.append("\n");
                }
                sql.append("    )) AS ").append(structName).append(",\n");
            } else {
                sql.append("    NAMED_STRUCT(\n");
                for (int i = 0; i < fieldExprs.size(); i++) {
                    String[] parts = fieldExprs.get(i).split(" AS ");
                    String src = parts[0].trim();
                    String name = parts.length > 1 ? parts[1].trim() : src;
                    sql.append("        '").append(name).append("', ").append(src);
                    if (i < fieldExprs.size() - 1) sql.append(",");
                    sql.append("\n");
                }
                sql.append("    ) AS ").append(structName).append(",\n");
            }
        }

        // Remove trailing comma+newline and add FROM
        String sqlStr = sql.toString();
        if (sqlStr.endsWith(",\n")) {
            sqlStr = sqlStr.substring(0, sqlStr.length() - 2) + "\n";
        }

        return sqlStr + "FROM " + upstreamRef + "\n";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonStructMappings(Object mappingsObj) {
        List<Map<String, Object>> mappings = new ArrayList<>();
        if (mappingsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    mappings.add((Map<String, Object>) map);
                }
            }
            return mappings;
        }
        if (mappingsObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || !(entry.getValue() instanceof List<?> cols)) continue;
                List<Map<String, Object>> fields = new ArrayList<>();
                for (Object col : cols) {
                    if (col == null) continue;
                    String sourceColumn = col.toString();
                    fields.add(Map.of(
                            "source_column", sourceColumn,
                            "as", inferJsonStructFieldAlias(entry.getKey().toString(), sourceColumn)
                    ));
                }
                mappings.add(Map.of(
                        "struct_name", entry.getKey().toString(),
                        "fields", fields
                ));
            }
        }
        return mappings;
    }

    private String inferJsonStructFieldAlias(String structName, String sourceColumn) {
        String prefix = slugify(structName).replace('-', '_') + "_";
        if (sourceColumn.startsWith(prefix) && sourceColumn.length() > prefix.length()) {
            return sourceColumn.substring(prefix.length());
        }
        return sourceColumn;
    }

    @SuppressWarnings("unchecked")
    private String generateSchemaNormalizationSqlBody(SubPipelineInstance inst, String slug) {
        var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
        String upstreamRef = resolveUpstreamFromExpr(inst, "source_data", slug);
        boolean strictMode = Boolean.TRUE.equals(params.get("strict_mode"));

        Object targetSchema = params.get("target_schema");
        if (targetSchema instanceof List<?> contractList && !contractList.isEmpty()) {
            StringBuilder sql = new StringBuilder();
            sql.append("-- Schema normalization: ordered contract projection with casts\n");
            sql.append("SELECT\n");
            for (Object item : contractList) {
                if (!(item instanceof Map<?, ?> entry)) continue;
                String contractName = firstNonBlank(
                        valueAsString(entry.get("contract_name")),
                        valueAsString(entry.get("name")),
                        valueAsString(entry.get("target_column")));
                String matchedName = firstNonBlank(
                        valueAsString(entry.get("matched_name")),
                        valueAsString(entry.get("source_column")),
                        contractName);
                String declaredType = firstNonBlank(valueAsString(entry.get("type")), "string");
                if (contractName == null) continue;
                sql.append("    CAST(").append(matchedName).append(" AS ").append(declaredType)
                        .append(") AS ").append(contractName).append(",\n");
            }
            sql.append("    current_timestamp() as _pulse_processed_at\n");
            sql.append("FROM ").append(upstreamRef).append("\n");
            return sql.toString();
        }

        Map<String, String> mappingRules = parseStringMap(params.get("mapping_rules"));
        List<Map.Entry<String, String>> renames = mappingRules.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .filter(entry -> !entry.getKey().equals(entry.getValue()))
                .toList();

        StringBuilder sql = new StringBuilder();
        sql.append("-- Schema normalization: lightweight rename/conformance projection\n");
        sql.append("SELECT\n");
        if (strictMode && !mappingRules.isEmpty()) {
            for (Map.Entry<String, String> entry : mappingRules.entrySet()) {
                sql.append("    ").append(entry.getValue()).append(" AS ").append(entry.getKey()).append(",\n");
            }
        } else if (renames.isEmpty()) {
            sql.append("    *,\n");
        } else {
            sql.append("    {{ dbt_utils.star(from=").append(relationMacroArg(upstreamRef)).append(", except=[");
            sql.append(renames.stream()
                    .map(Map.Entry::getValue)
                    .distinct()
                    .map(col -> "\"" + col + "\"")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""));
            sql.append("]) }},\n");
            for (Map.Entry<String, String> entry : renames) {
                sql.append("    ").append(entry.getValue()).append(" AS ").append(entry.getKey()).append(",\n");
            }
        }
        sql.append("    current_timestamp() as _pulse_processed_at\n");
        sql.append("FROM ").append(upstreamRef).append("\n");
        return sql.toString();
    }

    private String generateFeatureTablePublishSqlBody(SubPipelineInstance inst, String slug) {
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
        String upstreamRef = resolveUpstreamFromExpr(inst, "source_data", slug);
        String entityKey = stringParam(params, "entity_key", "loan_id");
        String pointInTimeColumn = firstNonBlankString(params, "point_in_time_column", "as_of_column", "effective_at_column");
        List<String> featureColumns = firstNonEmptyStringList(params, "features", "feature_columns");

        LinkedHashSet<String> projection = new LinkedHashSet<>();
        if (!entityKey.isBlank()) {
            projection.add(entityKey);
        }
        projection.addAll(featureColumns);
        if (!pointInTimeColumn.isBlank()) {
            projection.add(pointInTimeColumn);
        }
        if (projection.isEmpty()) {
            projection.add("*");
        }

        String selectList = projection.stream()
                .map(column -> "*".equals(column) ? "*" : quoteSqlIdentifier(column))
                .collect(Collectors.joining(",\n    "));
        return "SELECT\n    " + selectList + "\nFROM " + upstreamRef + "\n";
    }

    private String generateReferenceDataPublishSqlBody(SubPipelineInstance inst, String slug) {
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
        String upstreamRef = resolveUpstreamFromExpr(inst, "reference_source", slug);
        String referenceColumn = firstNonBlankString(params, "reference_column", "source_column", "reference_type");
        if (referenceColumn.isBlank()) {
            referenceColumn = "reference_value";
        }
        String quotedColumn = quoteSqlIdentifier(referenceColumn);
        return "SELECT DISTINCT\n    " + quotedColumn + " AS " + quotedColumn + "\n"
                + "FROM " + upstreamRef + "\n"
                + "WHERE " + quotedColumn + " IS NOT NULL\n";
    }

    private String generateFactBuildSqlBody(SubPipelineInstance inst, String slug) {
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
        String upstreamRef = resolveUpstreamFromExpr(inst, "transaction_data", slug);
        LinkedHashSet<String> projection = new LinkedHashSet<>();
        projection.addAll(firstNonEmptyStringList(params, "grain", "grain_columns", "business_keys"));
        projection.addAll(firstNonEmptyStringList(params, "dimension_keys", "dimensions", "dimension_columns"));
        projection.addAll(firstNonEmptyStringList(params, "measures", "measure_columns"));
        if (projection.isEmpty()) {
            projection.add("*");
        }

        String selectList = projection.stream()
                .map(column -> "*".equals(column) ? "*" : quoteSqlIdentifier(column))
                .collect(Collectors.joining(",\n    "));
        return "SELECT\n    " + selectList + "\nFROM " + upstreamRef + "\n";
    }

    @SuppressWarnings("unchecked")
    private String generateWideDenormalizedMartSqlBody(SubPipelineInstance inst, String slug) {
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
        String factRef = resolveUpstreamFromExpr(inst, "fact_data", slug);
        String dimensionRef = resolveUpstreamFromExpr(inst, "dimension_data", slug);
        String joinKey = "loan_id";
        Object dimensionJoins = params.get("dimension_joins");
        if (dimensionJoins instanceof List<?> joins && !joins.isEmpty() && joins.get(0) instanceof Map<?, ?> join) {
            Object configuredJoinKey = join.get("join_key");
            if (configuredJoinKey != null && !configuredJoinKey.toString().isBlank()) {
                joinKey = configuredJoinKey.toString();
            }
        }
        List<String> factColumns = firstNonEmptyStringList(params, "fact_columns");
        if (factColumns.isEmpty()) {
            factColumns = List.of("loan_id", "current_upb", "interest_rate");
        }
        List<String> dimensionColumns = firstNonEmptyStringList(params, "dimension_columns");
        if (dimensionColumns.isEmpty()) {
            dimensionColumns = List.of("loan_status", "property_state", "borrower_credit_score");
        }

        List<String> selectParts = new ArrayList<>();
        for (String column : factColumns) {
            selectParts.add("f." + quoteSqlIdentifier(column) + " AS " + quoteSqlIdentifier(column));
        }
        for (String column : dimensionColumns) {
            if (factColumns.contains(column)) {
                continue;
            }
            selectParts.add("d." + quoteSqlIdentifier(column) + " AS " + quoteSqlIdentifier(column));
        }
        return "SELECT\n    " + String.join(",\n    ", selectParts) + "\n"
                + "FROM " + factRef + " AS f\n"
                + "LEFT JOIN " + dimensionRef + " AS d\n"
                + "    ON f." + quoteSqlIdentifier(joinKey) + " = d." + quoteSqlIdentifier(joinKey) + "\n";
    }

    private String generateIncrementalMergeSqlBody(SubPipelineInstance inst, String slug) {
        String upstreamRef = resolveUpstreamFromExpr(inst, "incremental_data", slug);
        return "SELECT\n    *\nFROM " + upstreamRef + "\n";
    }

    private Map<String, String> parseStringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            out.put(entry.getKey().toString(), entry.getValue().toString());
        }
        return out;
    }

    private String relationMacroArg(String relationExpr) {
        if (relationExpr != null && relationExpr.startsWith("{{") && relationExpr.endsWith("}}")) {
            return relationExpr.substring(2, relationExpr.length() - 2).trim();
        }
        return "ref('" + relationExpr + "')";
    }

    private String valueAsString(Object value) {
        return value == null ? null : value.toString();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> generateRouterSql(SubPipelineInstance inst, String slug,
                                                  String fileFormat, String resolvedLayer,
                                                  String tenantId) {
        var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
        String upstreamRef = resolveUpstreamFromExpr(inst, "data_input", slug);
        boolean includeDefault = Boolean.TRUE.equals(params.getOrDefault("include_default", true));

        Map<String, String> models = new LinkedHashMap<>();
        String taskSlug = slugify(inst.getName());
        boolean includeFileFormat = !"gold".equals(resolvedLayer);
        String locationRoot = resolveDbtLocationRoot(inst, slug, tenantId, resolvedLayer);
        boolean usePulseDeltaTable = includeFileFormat
                && "delta".equalsIgnoreCase(fileFormat)
                && locationRoot != null;
        String materialized = usePulseDeltaTable ? "pulse_delta_table" : "table";
        List<BronzeSourceRegistration> bronzeSourceRegistrations =
                collectBronzeSourceRegistrations(inst, slug, tenantId);
        List<String> preHooks = new ArrayList<>();
        if (!usePulseDeltaTable && "delta".equalsIgnoreCase(fileFormat)) {
            preHooks.add("DROP TABLE IF EXISTS {{ this }}");
        }
        preHooks.addAll(bronzeSourcePreHooks(bronzeSourceRegistrations));
        List<String> partitionBy = new ArrayList<>();
        if (params.get("partition_by") instanceof List<?> partitionList) {
            for (Object value : partitionList) {
                if (value != null) {
                    partitionBy.add(value.toString());
                }
            }
        }

        Object routesObj = params.get("routes");
        List<Map<String, Object>> routes = new ArrayList<>();
        if (routesObj instanceof List<?> routeList) {
            for (var r : routeList) {
                if (r instanceof Map<?, ?> m) {
                    routes.add((Map<String, Object>) m);
                }
            }
        }

        List<String> routeConditions = new ArrayList<>();
        for (var route : routes) {
            String routeName = (String) route.getOrDefault("name", "route");
            String condition = (String) route.getOrDefault("condition", "1 = 1");
            String desc = (String) route.getOrDefault("description", "");
            String modelName = taskSlug + "_" + slugify(routeName);

            StringBuilder sql = new StringBuilder();
            sql.append("-- Router output: ").append(routeName);
            if (!desc.isEmpty()) sql.append(" -- ").append(desc);
            sql.append("\n");
            sql.append("{{ config(\n");
            sql.append("    materialized='").append(materialized).append("'");
            if (includeFileFormat) sql.append(",\n    file_format='").append(fileFormat).append("'");
            if (locationRoot != null) sql.append(",\n    location_root='").append(locationRoot).append("'");
            sql.append(",\n    tags=['pulse', '").append(slug).append("', '").append(taskSlug).append("', 'router']");
            if (!partitionBy.isEmpty()) {
                sql.append(",\n    partition_by=[");
                for (int i = 0; i < partitionBy.size(); i++) {
                    if (i > 0) sql.append(", ");
                    sql.append("'").append(partitionBy.get(i)).append("'");
                }
                sql.append("]");
            }
            appendDbtPreHooks(sql, preHooks);
            sql.append("\n) }}\n\n");
            sql.append("SELECT *\n");
            sql.append("FROM ").append(upstreamRef).append("\n");
            sql.append("WHERE ").append(condition).append("\n");

            models.put(modelName, sql.toString());
            routeConditions.add(condition);
        }

        if (includeDefault) {
            String defaultModelName = taskSlug + "_default";
            StringBuilder sql = new StringBuilder();
            sql.append("-- Router output: default (unmatched rows)\n");
            sql.append("{{ config(\n");
            sql.append("    materialized='").append(materialized).append("'");
            if (includeFileFormat) sql.append(",\n    file_format='").append(fileFormat).append("'");
            if (locationRoot != null) sql.append(",\n    location_root='").append(locationRoot).append("'");
            sql.append(",\n    tags=['pulse', '").append(slug).append("', '").append(taskSlug).append("', 'router']");
            if (!partitionBy.isEmpty()) {
                sql.append(",\n    partition_by=[");
                for (int i = 0; i < partitionBy.size(); i++) {
                    if (i > 0) sql.append(", ");
                    sql.append("'").append(partitionBy.get(i)).append("'");
                }
                sql.append("]");
            }
            appendDbtPreHooks(sql, preHooks);
            sql.append("\n) }}\n\n");
            sql.append("SELECT *\n");
            sql.append("FROM ").append(upstreamRef).append("\n");
            if (!routeConditions.isEmpty()) {
                sql.append("WHERE NOT (\n");
                for (int i = 0; i < routeConditions.size(); i++) {
                    if (i > 0) sql.append("    OR ");
                    else sql.append("    ");
                    sql.append("(").append(routeConditions.get(i)).append(")\n");
                }
                sql.append(")\n");
            }
            models.put(defaultModelName, sql.toString());
        }

        return models;
    }

    private String mapFilterOperator(String column, String operator, String value) {
        return switch (operator) {
            case "eq" -> column + " = " + quoteSqlValue(value);
            case "neq" -> column + " != " + quoteSqlValue(value);
            case "gt" -> column + " > " + quoteSqlValue(value);
            case "gte" -> column + " >= " + quoteSqlValue(value);
            case "lt" -> column + " < " + quoteSqlValue(value);
            case "lte" -> column + " <= " + quoteSqlValue(value);
            case "like" -> column + " LIKE " + quoteSqlValue(value);
            case "not_like" -> column + " NOT LIKE " + quoteSqlValue(value);
            case "in" -> column + " IN (" + value + ")";
            case "not_in" -> column + " NOT IN (" + value + ")";
            case "is_null" -> column + " IS NULL";
            case "is_not_null" -> column + " IS NOT NULL";
            default -> column + " = " + quoteSqlValue(value);
        };
    }

    private String quoteSqlValue(String value) {
        if (value == null || value.isEmpty()) return "''";
        // If the value looks numeric, don't quote it
        try {
            Double.parseDouble(value);
            return value;
        } catch (NumberFormatException e) {
            return "'" + value.replace("'", "''") + "'";
        }
    }

    /**
     * Resolves the FROM-clause Jinja expression for an upstream instance connected
     * to the given port of the target instance.
     *
     * <p>If the upstream is an INGESTION blueprint, the upstream is bronze (PySpark-
     * emitted, registered in the metastore as {@code bronze_<src_slug>.<table>}).
     * Returns {@code {{ source('bronze_<src_slug>', '<table>') }}}.
     *
     * <p>Otherwise the upstream is itself a dbt model — returns
     * {@code {{ ref('<upstream_slug>') }}}.
     *
     * <p>Falls back to a same-pipeline source ref if no wiring exists; this should
     * be rare and {@code dbt parse} will surface a clean missing-source error if
     * the table genuinely doesn't exist.
     */
    private String resolveUpstreamFromExpr(SubPipelineInstance inst, String portName, String slug) {
        if (inst.getVersionId() != null) {
            List<PortWiring> wirings = wiringRepo.findByVersionIdAndTargetInstanceId(
                    inst.getVersionId(), inst.getId());
            for (PortWiring w : wirings) {
                if (portName.equals(w.getTargetPortName())) {
                    SubPipelineInstance upstream = instanceRepo.findById(w.getSourceInstanceId()).orElse(null);
                    if (upstream != null) {
                        Blueprint upBp = blueprintRepo.findByBlueprintKey(upstream.getBlueprintKey()).orElse(null);
                        if (upBp != null && "INGESTION".equals(upBp.getCategory().name())) {
                            ConnectorInstance ci = resolveConnectorInstance(upstream.getParams());
                            String srcSlug = (ci != null && ci.getName() != null)
                                    ? slugify(ci.getName())
                                    : slug;
                            String tableName = slugify(upstream.getName());
                            return String.format("{{ source('bronze_%s', '%s') }}", srcSlug, tableName);
                        }
                        return String.format("{{ ref('%s') }}", dbtModelRefName(upstream, upBp, w));
                    }
                }
            }
        }
        // No wiring resolved — surface the unresolved-source case as a same-pipeline
        // bronze source so dbt parse fails with a clear "source not found" error
        // rather than a confusing missing-ref error.
        return String.format("{{ source('bronze_%s', '%s_input') }}", slug, slug);
    }

    private String dbtModelRefName(SubPipelineInstance upstream, Blueprint upstreamBlueprint, PortWiring wiring) {
        String bpKey = upstreamBlueprint.getBlueprintKey();
        String entitySlug = slugify(upstream.getName());
        String domainSlug = pipelineRepo.findById(upstream.getPipelineId())
                .map(Pipeline::getDomainName)
                .filter(name -> !name.isBlank())
                .map(this::slugify)
                .orElse("default");
        Map<String, Object> params = upstream.getParams() == null ? Map.of() : upstream.getParams();
        String resolvedLayer = Optional.ofNullable(upstream.getLakeLayer())
                .filter(layer -> !layer.isBlank())
                .or(() -> Optional.ofNullable(stringParam(params, "lake_layer", null)))
                .filter(layer -> !layer.isBlank())
                .orElse("silver");

        if ("GenericRouter".equals(bpKey)) {
            String routeSlug = "default_output".equals(wiring.getSourcePortName())
                    ? entitySlug + "_default"
                    : entitySlug + "_" + slugify(wiring.getSourcePortName().replaceAll("_output$", ""));
            return "int__" + domainSlug + "__" + routeSlug;
        }

        List<SubPipelineInstance> allInstances = upstream.getVersionId() == null
                ? List.of(upstream)
                : instanceRepo.findByVersionIdOrderByExecutionOrderAsc(upstream.getVersionId());
        String sourceSlug = resolveSourceSystemSlug(upstream, allInstances, domainSlug);
        DbtLayout layout = resolveDbtLayout(bpKey, resolvedLayer, domainSlug, sourceSlug, entitySlug);
        return layout.fileName().replaceAll("\\.sql$", "");
    }

    private List<BronzeSourceRegistration> collectBronzeSourceRegistrations(SubPipelineInstance target,
                                                                            String pipelineSlug,
                                                                            String tenantId) {
        if (target.getVersionId() == null) {
            return List.of();
        }

        List<BronzeSourceRegistration> registrations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<PortWiring> wirings = wiringRepo.findByVersionIdAndTargetInstanceId(
                target.getVersionId(), target.getId());
        for (PortWiring wiring : wirings) {
            SubPipelineInstance upstream = instanceRepo.findById(wiring.getSourceInstanceId()).orElse(null);
            if (upstream == null) continue;
            Blueprint upstreamBlueprint = blueprintRepo.findByBlueprintKey(upstream.getBlueprintKey()).orElse(null);
            if (upstreamBlueprint == null || !"INGESTION".equals(upstreamBlueprint.getCategory().name())) {
                continue;
            }

            BronzeSourceRegistration registration = resolveBronzeSourceRegistration(upstream, pipelineSlug, tenantId);
            if (registration != null && seen.add(registration.schemaName() + "." + registration.tableName())) {
                registrations.add(registration);
            }
        }
        return registrations;
    }

    private BronzeSourceRegistration resolveBronzeSourceRegistration(SubPipelineInstance ingestion,
                                                                     String pipelineSlug,
                                                                     String tenantId) {
        ConnectorInstance ci = resolveConnectorInstance(ingestion.getParams());
        String sourceSlug = ci != null && ci.getName() != null
                ? slugify(ci.getName())
                : "default";
        String tableName = slugify(ingestion.getName());
        String tableLocation = resolveLakeTablePath(
                ingestion, pipelineSlug, tenantId, LakeLayer.BRONZE, LakeFormat.DELTA, tableName);
        if (tableLocation == null) {
            return null;
        }
        return new BronzeSourceRegistration("bronze_" + sourceSlug, tableName, tableLocation);
    }

    private List<String> bronzeSourcePreHooks(List<BronzeSourceRegistration> registrations) {
        if (registrations == null || registrations.isEmpty()) {
            return List.of();
        }
        List<String> hooks = new ArrayList<>();
        for (BronzeSourceRegistration registration : registrations) {
            hooks.add("CREATE SCHEMA IF NOT EXISTS " + registration.schemaName());
            hooks.add("CREATE TABLE IF NOT EXISTS "
                    + registration.schemaName() + "." + registration.tableName()
                    + " USING DELTA LOCATION '" + registration.location() + "'");
            hooks.add("REFRESH TABLE " + registration.schemaName() + "." + registration.tableName());
        }
        return hooks;
    }

    private void appendDbtPreHooks(StringBuilder sql, List<String> hooks) {
        if (hooks == null || hooks.isEmpty()) {
            return;
        }

        sql.append(",\n    pre_hook=[\n");
        for (int i = 0; i < hooks.size(); i++) {
            if (i > 0) {
                sql.append(",\n");
            }
            sql.append("        \"").append(escapeDbtConfigString(hooks.get(i))).append("\"");
        }
        sql.append("\n    ]");
    }

    private String escapeDbtConfigString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record BronzeSourceRegistration(String schemaName, String tableName, String location) {}

    private String resolveDbtLocationRoot(SubPipelineInstance model,
                                          String pipelineSlug,
                                          String tenantId,
                                          String resolvedLayer) {
        SubPipelineInstance storageContext = findIngestionAncestor(model, new LinkedHashSet<>());
        if (storageContext == null) {
            return null;
        }
        return resolveLakeLayerRoot(storageContext, pipelineSlug, tenantId, lakeLayerFrom(resolvedLayer));
    }

    private String resolveUpstreamDeltaPath(SubPipelineInstance sink,
                                            List<SubPipelineInstance> allInstances,
                                            String pipelineSlug,
                                            String tenantId,
                                            CompilePlanService.CompilePlanSnapshot compilePlan) {
        if (sink.getVersionId() == null) {
            return null;
        }
        List<PortWiring> wirings = wiringRepo.findByVersionIdAndTargetInstanceId(sink.getVersionId(), sink.getId());
        for (PortWiring wiring : wirings) {
            SubPipelineInstance upstream = findInstance(wiring.getSourceInstanceId(), allInstances);
            if (upstream == null) continue;
            Blueprint upstreamBlueprint = blueprintRepo.findByBlueprintKey(upstream.getBlueprintKey()).orElse(null);
            if (upstreamBlueprint == null) continue;

            String upstreamCategory = upstreamBlueprint.getCategory().name();
            if ("INGESTION".equals(upstreamCategory)) {
                return resolveLakeTablePath(upstream, pipelineSlug, tenantId,
                        LakeLayer.BRONZE, LakeFormat.DELTA, slugify(upstream.getName()));
            }
            if ("TRANSFORM".equals(upstreamCategory) || "MODELING".equals(upstreamCategory)) {
                String layer = resolvedLayerFor(upstream, compilePlan, "silver");
                String locationRoot = resolveDbtLocationRoot(upstream, pipelineSlug, tenantId, layer);
                if (locationRoot == null) {
                    return null;
                }
                return locationRoot + "/" + dbtModelIdentifier(
                        upstream,
                        allInstances,
                        compilePlan,
                        wiring.getSourcePortName()
                );
            }
            if ("DATA_QUALITY".equals(upstreamCategory)) {
                return resolveDataQualityOutputPath(upstream, pipelineSlug, tenantId, compilePlan);
            }
        }
        return null;
    }

    private String resolveDataQualityOutputPath(SubPipelineInstance quality,
                                                String pipelineSlug,
                                                String tenantId,
                                                CompilePlanService.CompilePlanSnapshot compilePlan) {
        String layer = resolvedLayerFor(quality, compilePlan, "silver");
        SubPipelineInstance storageContext = findIngestionAncestor(quality, new LinkedHashSet<>());
        if (storageContext == null) {
            return null;
        }
        String locationRoot = resolveLakeLayerRoot(storageContext, pipelineSlug, tenantId, lakeLayerFrom(layer));
        if (locationRoot == null) {
            return null;
        }
        return locationRoot + "/" + slugify(quality.getName());
    }

    private SubPipelineInstance findIngestionAncestor(SubPipelineInstance instance, Set<String> visited) {
        if (instance == null || instance.getId() == null || !visited.add(instance.getId())) {
            return null;
        }
        Blueprint bp = blueprintRepo.findByBlueprintKey(instance.getBlueprintKey()).orElse(null);
        if (bp != null && "INGESTION".equals(bp.getCategory().name())) {
            return instance;
        }
        if (instance.getVersionId() == null) {
            return null;
        }
        List<PortWiring> wirings = wiringRepo.findByVersionIdAndTargetInstanceId(
                instance.getVersionId(), instance.getId());
        for (PortWiring wiring : wirings) {
            SubPipelineInstance upstream = instanceRepo.findById(wiring.getSourceInstanceId()).orElse(null);
            SubPipelineInstance ancestor = findIngestionAncestor(upstream, visited);
            if (ancestor != null) {
                return ancestor;
            }
        }
        return null;
    }

    private SubPipelineInstance findInstance(String id, List<SubPipelineInstance> instances) {
        if (id == null) {
            return null;
        }
        for (SubPipelineInstance instance : instances) {
            if (id.equals(instance.getId())) {
                return instance;
            }
        }
        return instanceRepo.findById(id).orElse(null);
    }

    private String dbtModelIdentifier(SubPipelineInstance instance,
                                      List<SubPipelineInstance> allInstances,
                                      CompilePlanService.CompilePlanSnapshot compilePlan) {
        return dbtModelIdentifier(instance, allInstances, compilePlan, null);
    }

    private String dbtModelIdentifier(SubPipelineInstance instance,
                                      List<SubPipelineInstance> allInstances,
                                      CompilePlanService.CompilePlanSnapshot compilePlan,
                                      String sourcePortName) {
        Blueprint bp = blueprintRepo.findByBlueprintKey(instance.getBlueprintKey()).orElse(null);
        if (bp == null) {
            return slugify(instance.getName());
        }
        String entitySlug = slugify(instance.getName());
        if ("GenericRouter".equals(bp.getBlueprintKey())) {
            String port = sourcePortName == null || sourcePortName.isBlank() ? "default_output" : sourcePortName;
            String routeSlug = "default_output".equals(port)
                    ? entitySlug + "_default"
                    : entitySlug + "_" + slugify(port.replaceAll("_output$", ""));
            return "int__" + deriveDomainSlug(compilePlan) + "__" + routeSlug;
        }
        if ("SCD2Dimension".equals(bp.getBlueprintKey()) || "SnapshotModel".equals(bp.getBlueprintKey())) {
            return "snp__" + entitySlug;
        }
        return dbtRelationAlias(pipelineSlugFromCompilePlan(compilePlan), entitySlug);
    }

    private String pipelineSlugFromCompilePlan(CompilePlanService.CompilePlanSnapshot compilePlan) {
        if (compilePlan == null || compilePlan.namespace() == null || compilePlan.namespace().isBlank()) {
            return "pipeline";
        }
        String[] parts = compilePlan.namespace().split("/");
        return slugify(parts.length == 0 ? compilePlan.namespace() : parts[parts.length - 1]);
    }

    private String dbtRelationAlias(String pipelineSlug, String entitySlug) {
        return slugify(pipelineSlug) + "__" + slugify(entitySlug);
    }

    private String resolvedLayerFor(SubPipelineInstance instance,
                                    CompilePlanService.CompilePlanSnapshot compilePlan,
                                    String fallback) {
        if (compilePlan != null) {
            for (Map<String, Object> node : compilePlan.nodes()) {
                if (instance.getId().equals(node.get("instanceId")) && node.get("resolvedLayer") != null) {
                    return node.get("resolvedLayer").toString();
                }
            }
        }
        return fallback;
    }

    private LakeLayer lakeLayerFrom(String rawLayer) {
        try {
            LakeLayer layer = LakeLayer.from(rawLayer);
            return layer != null ? layer : LakeLayer.SILVER;
        } catch (Exception ignored) {
            return LakeLayer.SILVER;
        }
    }

    private List<GeneratedArtifact> generateDbtSources(GenerationRun run,
                                                       List<SubPipelineInstance> instances,
                                                       String slug,
                                                       CompilePlanService.CompilePlanSnapshot compilePlan) {
        String domainSlug = deriveDomainSlug(compilePlan);
        Map<String, List<SubPipelineInstance>> bySource = new LinkedHashMap<>();
        for (var inst : instances) {
            Blueprint bp = blueprintRepo.findByBlueprintKey(inst.getBlueprintKey()).orElse(null);
            if (bp == null || !"INGESTION".equals(bp.getCategory().name())) continue;
            ConnectorInstance ci = resolveConnectorInstance(inst.getParams());
            String sourceSlug = ci != null && ci.getName() != null
                    ? slugify(ci.getName())
                    : domainSlug;
            bySource.computeIfAbsent(sourceSlug, k -> new ArrayList<>()).add(inst);
        }

        List<GeneratedArtifact> out = new ArrayList<>();
        for (var entry : bySource.entrySet()) {
            String sourceSlug = entry.getKey();
            // Source name MUST match what resolveUpstreamFromExpr emits when an upstream
            // INGESTION blueprint is detected: bronze_<sourceSlug>. dbt-spark looks up
            // the metastore schema with this same name and finds external Delta tables
            // registered by generated ingestion jobs and model pre-hooks.
            String sourceName = "bronze_" + sourceSlug;
            StringBuilder yml = new StringBuilder();
            yml.append("# PULSE-generated source declaration for raw bronze ingest tables.\n");
            yml.append("# Tables are written as Delta by PySpark ingestion jobs (jobs/ingestion/*_ingest.py)\n");
            yml.append("# and registered as external Spark tables before dbt source() reads.\n\n");
            yml.append("version: 2\n\nsources:\n");
            yml.append("  - name: ").append(sourceName).append("\n");
            yml.append("    schema: ").append(sourceName).append("\n");
            yml.append("    description: Raw ingest tables sourced from ").append(sourceSlug).append("\n");
            yml.append("    tables:\n");
            for (SubPipelineInstance inst : entry.getValue()) {
                yml.append("      - name: ").append(slugify(inst.getName())).append("\n");
                yml.append("        description: Ingested by ").append(inst.getName()).append("\n");
            }
            // dbt-labs convention: leading single underscore + double underscore between
            // source and "sources.yml". Was double-leading; aligning to canonical.
            String path = String.format("dbt_project/models/staging/%s/_%s__sources.yml", sourceSlug, sourceSlug);
            out.add(createArtifact(run, path, "DBT_SOURCE", yml.toString(), "dbt_sources"));
        }
        return out;
    }

    private GeneratedArtifact generateConfig(GenerationRun run, Pipeline pipeline,
                                               PipelineVersion version,
                                               List<SubPipelineInstance> instances,
                                               String slug) {
        StringBuilder yml = new StringBuilder();
        yml.append("# PULSE Pipeline Configuration\n");
        yml.append("# Auto-generated - do not edit manually unless you know what you're doing\n\n");
        yml.append("pipeline:\n");
        yml.append("  name: ").append(pipeline.getName()).append("\n");
        yml.append("  slug: ").append(slug).append("\n");
        yml.append("  version: ").append(version.getRevision()).append("\n");
        yml.append("  domain: ").append(pipeline.getDomainName()).append("\n\n");
        yml.append("steps:\n");

        for (var inst : instances) {
            Blueprint bp = blueprintRepo.findByBlueprintKey(inst.getBlueprintKey()).orElse(null);
            yml.append("  - name: ").append(inst.getName()).append("\n");
            yml.append("    blueprint: ").append(inst.getBlueprintKey()).append("\n");
            if (bp != null) {
                yml.append("    category: ").append(bp.getCategory().name()).append("\n");
            }
            if (inst.getParams() != null && !inst.getParams().isEmpty()) {
                yml.append("    params:\n");
                for (var entry : inst.getParams().entrySet()) {
                    yml.append("      ").append(entry.getKey()).append(": ")
                            .append(sanitizeConfigValueForArtifact(entry.getValue()))
                            .append("\n");
                }
            }
        }

        return createArtifact(run, "config/pipeline.yaml",
                "CONFIG_YAML", yml.toString(), "pipeline_config");
    }

    private Object sanitizeConfigValueForArtifact(Object value) {
        if (value instanceof String s) {
            return substituteMnemonicsForRuntime(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey().toString(), sanitizeConfigValueForArtifact(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            for (Object entry : collection) {
                out.add(sanitizeConfigValueForArtifact(entry));
            }
            return out;
        }
        return value;
    }

    private GeneratedArtifact generateRequirements(GenerationRun run, String slug) {
        String content = """
                # PULSE Pipeline Dependencies
                # Auto-generated
                pyspark>=3.5.0
                delta-spark>=3.1.0
                apache-airflow>=2.8.0
                apache-airflow-providers-apache-spark>=4.7.0
                apache-airflow-providers-amazon>=8.0.0
                apache-airflow-providers-sftp>=5.0.0
                apache-airflow-providers-common-sql>=1.14.0
                apache-airflow-providers-cncf-kubernetes>=8.0.0
                google-cloud-secret-manager>=2.20.2
                dbt-core>=1.7.0
                dbt-spark>=1.7.0
                """;
        return createArtifact(run, "requirements.txt", "REQUIREMENTS_TXT", content, "requirements");
    }

    private List<GeneratedArtifact> generateBrokerInvocationArtifacts(GenerationRun run,
                                                                      List<SubPipelineInstance> instances,
                                                                      String slug) {
        List<Map<String, Object>> invocations = new ArrayList<>();
        for (SubPipelineInstance inst : instances) {
            if (!"RemotePipelineInvocation".equals(inst.getBlueprintKey())) {
                continue;
            }
            Map<String, Object> params = inst.getParams() == null ? Map.of() : inst.getParams();
            Map<String, Object> invocation = new LinkedHashMap<>();
            invocation.put("instanceId", inst.getId());
            invocation.put("name", inst.getName());
            invocation.put("federatedTenantKey", stringParam(params, "federated_tenant_key"));
            invocation.put("remoteTargetRef", stringParam(params, "remote_target_ref"));
            invocation.put("environment", stringParam(params, "environment"));
            RemoteAirflowRuntime runtime = resolveRemoteAirflowRuntime(params);
            invocation.put("remoteDagId", runtime.remoteDagId());
            invocation.put("airflowConnectionId", runtime.airflowConnectionId());
            invocation.put("pollIntervalSeconds", runtime.pollIntervalSeconds());
            invocation.put("timeoutSeconds", runtime.timeoutSeconds());
            invocation.put("payloadTemplate", mapParam(params, "payload_template"));
            invocation.put("operatorModulePath", "airflow/plugins/pulse_remote_airflow_invoke.py");
            invocations.add(invocation);
        }
        if (invocations.isEmpty()) {
            return List.of();
        }

        List<GeneratedArtifact> artifacts = new ArrayList<>();
        String manifestJson;
        try {
            manifestJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of(
                            "schemaVersion", "pulse.broker.invocations.v1",
                            "pipelineSlug", slug,
                            "invocations", invocations));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize broker invocation manifest", e);
        }
        GeneratedArtifact manifest = createArtifact(run, "broker/broker_remote_invocations.json",
                "BROKER_INVOCATION_MANIFEST", manifestJson + "\n", "broker_invocation_manifest");
        manifest.setMetadata(Map.of("brokerInvocations", invocations));
        artifacts.add(manifest);

        GeneratedArtifact operator = createArtifact(run, "airflow/plugins/pulse_remote_airflow_invoke.py",
                "BROKER_OPERATOR", remoteAirflowOperatorSource(), "remote_airflow_invocation_operator");
        operator.setMetadata(Map.of("brokerInvocations", invocations));
        artifacts.add(operator);
        return artifacts;
    }

    private String remoteAirflowOperatorSource() {
        return """
                \"\"\"Remote Airflow invocation helper for Pulse generated DAGs.

                This module is packaged only when a RemotePipelineInvocation
                blueprint is present. It does not call the Pulse API at runtime;
                it uses an Airflow Connection available in the executing
                environment to trigger and poll a peer Airflow DAG run.
                \"\"\"

                import time

                from airflow.exceptions import AirflowException
                from airflow.hooks.base import BaseHook
                from airflow.models import BaseOperator
                import requests


                class RemoteAirflowDagRunOperator(BaseOperator):
                    template_fields = ("remote_run_id", "payload")

                    def __init__(
                        self,
                        *,
                        airflow_conn_id,
                        remote_dag_id,
                        remote_run_id,
                        payload=None,
                        poll_interval_seconds=30,
                        timeout_seconds=7200,
                        **kwargs,
                    ):
                        super().__init__(**kwargs)
                        self.airflow_conn_id = airflow_conn_id
                        self.remote_dag_id = remote_dag_id
                        self.remote_run_id = remote_run_id
                        self.payload = payload or {}
                        self.poll_interval_seconds = int(poll_interval_seconds)
                        self.timeout_seconds = int(timeout_seconds)
                        self._session = None
                        self._base_url = None

                    def execute(self, context):
                        if not self.airflow_conn_id:
                            raise AirflowException("RemotePipelineInvocation requires airflow_connection_id")
                        if not self.remote_dag_id:
                            raise AirflowException("RemotePipelineInvocation requires remote_dag_id")
                        session, base_url = self._client()
                        body = {"dag_run_id": self.remote_run_id, "conf": self.payload}
                        trigger = session.post(
                            f"{base_url}/api/v1/dags/{self.remote_dag_id}/dagRuns",
                            json=body,
                            timeout=30,
                        )
                        if trigger.status_code not in (200, 201, 409):
                            raise AirflowException(f"Remote Airflow trigger failed: {trigger.status_code} {trigger.text}")

                        deadline = time.monotonic() + self.timeout_seconds
                        facts_url = f"{base_url}/api/v1/dags/{self.remote_dag_id}/dagRuns/{self.remote_run_id}"
                        while True:
                            facts = session.get(facts_url, timeout=30)
                            facts.raise_for_status()
                            payload = facts.json()
                            state = (payload.get("state") or "").lower()
                            self.log.info("Remote Airflow DAG %s run %s state=%s",
                                          self.remote_dag_id, self.remote_run_id, state)
                            if state == "success":
                                return {
                                    "remoteDagId": self.remote_dag_id,
                                    "remoteRunId": self.remote_run_id,
                                    "state": state,
                                }
                            if state in {"failed", "upstream_failed"}:
                                raise AirflowException(
                                    f"Remote Airflow DAG {self.remote_dag_id} run {self.remote_run_id} ended in {state}"
                                )
                            if time.monotonic() > deadline:
                                raise AirflowException(
                                    f"Remote Airflow DAG {self.remote_dag_id} run {self.remote_run_id} timed out"
                                )
                            time.sleep(self.poll_interval_seconds)

                    def on_kill(self):
                        if not self._session or not self._base_url or not self.remote_run_id:
                            return
                        try:
                            self._session.patch(
                                f"{self._base_url}/api/v1/dags/{self.remote_dag_id}/dagRuns/{self.remote_run_id}",
                                json={"state": "failed"},
                                timeout=10,
                            )
                        except Exception as exc:
                            self.log.warning("Remote Airflow cancellation request failed: %s", exc)

                    def _client(self):
                        conn = BaseHook.get_connection(self.airflow_conn_id)
                        base_url = (conn.host or "").rstrip("/")
                        if not base_url:
                            raise AirflowException(f"Airflow connection {self.airflow_conn_id} must define host")
                        session = requests.Session()
                        headers = {"Content-Type": "application/json"}
                        extra = conn.extra_dejson or {}
                        bearer = extra.get("bearer_token") or extra.get("bearerToken")
                        if bearer:
                            headers["Authorization"] = f"Bearer {bearer}"
                        session.headers.update(headers)
                        if conn.login or conn.password:
                            session.auth = (conn.login or "", conn.password or "")
                        self._session = session
                        self._base_url = base_url
                        return session, base_url
                """;
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private List<GeneratedArtifact> generateRuntimeSecretSupport(GenerationRun run,
                                                                 List<SubPipelineInstance> instances) {
        Map<String, List<RuntimeSecretBinding>> manifest = buildRuntimeSecretManifest(instances);
        if (manifest.isEmpty()) {
            return List.of();
        }

        List<GeneratedArtifact> artifacts = new ArrayList<>();
        artifacts.add(createArtifact(
                run,
                "runtime/__init__.py",
                "RUNTIME_SUPPORT",
                "# Generated runtime helpers for PULSE pipeline packaging\n",
                "runtime_support"
        ));
        artifacts.add(createArtifact(
                run,
                "runtime/pulse_secret_resolver.py",
                "RUNTIME_SUPPORT",
                generatedSecretResolverModule(),
                "pulse_secret_resolver"
        ));
        artifacts.add(createArtifact(
                run,
                "config/secret-manifest.json",
                "RUNTIME_SECRET_MANIFEST",
                pythonLiteral(manifest),
                "runtime_secret_manifest"
        ));
        return artifacts;
    }

    /**
     * PKT-0027: Builds the complete runtime secret manifest including both
     * source (ingestion) and target (destination/sink) connector credential refs.
     * Every connector_instance_id referenced by any sub-pipeline instance is
     * resolved and its credential bindings are included.
     */
    private Map<String, List<RuntimeSecretBinding>> buildRuntimeSecretManifest(List<SubPipelineInstance> instances) {
        Map<String, List<RuntimeSecretBinding>> manifest = new LinkedHashMap<>();
        for (var inst : instances) {
            var params = inst.getParams() != null ? inst.getParams() : Map.<String, Object>of();
            List<RuntimeSecretBinding> bindings = collectRuntimeSecretBindings(resolveCredentialProfile(params));
            if (!bindings.isEmpty()) {
                manifest.put(slugify(inst.getName()), bindings);
            }
        }
        return manifest;
    }

    private List<GeneratedArtifact> generateAdvanceTimeRuntimeSupport(GenerationRun run,
                                                                      CompilePlanService.CompilePlanSnapshot compilePlan) {
        List<Map<String, Object>> contracts = compilePlan.advanceTimeContracts();
        if (contracts == null || contracts.isEmpty()) {
            return List.of();
        }

        List<GeneratedArtifact> artifacts = new ArrayList<>();
        artifacts.add(createArtifact(
                run,
                "pulse_airflow_runtime/__init__.py",
                "RUNTIME_SUPPORT",
                readRuntimeHelperResource("runtime-helpers/pulse_airflow_runtime/__init__.py"),
                "pulse_airflow_runtime_init"
        ));
        artifacts.add(createArtifact(
                run,
                "pulse_airflow_runtime/time_state.py",
                "RUNTIME_SUPPORT",
                readRuntimeHelperResource("runtime-helpers/pulse_airflow_runtime/time_state.py"),
                "pulse_airflow_runtime_time_state"
        ));
        artifacts.add(createArtifact(
                run,
                "pulse_dates/__init__.py",
                "RUNTIME_SUPPORT",
                readRuntimeHelperResource("runtime-helpers/pulse_dates/__init__.py"),
                "pulse_dates"
        ));
        Map<String, String> packagedCalendarBundles = packagedCalendarBundles(contracts, run.getTenantId());
        for (Map.Entry<String, String> entry : packagedCalendarBundles.entrySet()) {
            artifacts.add(createArtifact(
                    run,
                    entry.getKey(),
                    "CALENDAR_BUNDLE",
                    entry.getValue(),
                    "calendar_bundle"
            ));
        }
        GeneratedArtifact manifest = createArtifact(
                run,
                "config/advance-time-runtime.json",
                "ADVANCE_TIME_RUNTIME_CONTRACT",
                toJson(Map.of(
                        "schemaVersion", "pulse.advance_time.runtime_contract.v1",
                        "contracts", contracts,
                        "calendarBundles", packagedCalendarBundles.keySet())),
                "advance_time_runtime_contract"
        );
        manifest.setMetadata(Map.of("timeStateBindings", contracts));
        artifacts.add(manifest);
        return artifacts;
    }

    private void prepareAdvanceTimeRuntimeContracts(CompilePlanService.CompilePlanSnapshot compilePlan,
                                                    String tenantId) {
        List<Map<String, Object>> contracts = compilePlan.advanceTimeContracts();
        if (contracts == null || contracts.isEmpty()) {
            return;
        }
        for (Map<String, Object> contract : contracts) {
            String bundleUri = stringValue(contract.get("calendarBundleUri"));
            if (bundleUri == null || bundleUri.isBlank()) {
                bundleUri = "runtime/calendar/%s-calendar-bundle.json"
                        .formatted(slugify(firstNonBlank(tenantId, "default")));
                contract.put("calendarBundleUri", bundleUri);
            }
            if (!isPackagedCalendarBundleUri(bundleUri)) {
                continue;
            }
            String bundle = calendarBundleJson(contracts, tenantId);
            contract.put("calendarBundleHash", "sha256:" + sha256(bundle));
            contract.put("calendarBundlePackaged", true);
            contract.put("calendarBundleCoverageStart", "1900-01-01");
            contract.put("calendarBundleCoverageEnd", "2100-12-31");
        }
    }

    private Map<String, String> packagedCalendarBundles(List<Map<String, Object>> contracts, String tenantId) {
        Map<String, String> bundles = new LinkedHashMap<>();
        String payload = calendarBundleJson(contracts, tenantId);
        for (Map<String, Object> contract : contracts) {
            String bundleUri = stringValue(contract.get("calendarBundleUri"));
            if (!isPackagedCalendarBundleUri(bundleUri)) {
                continue;
            }
            bundles.putIfAbsent(normalizePackagedCalendarBundlePath(bundleUri), payload);
        }
        return bundles;
    }

    private boolean isPackagedCalendarBundleUri(String bundleUri) {
        if (bundleUri == null || bundleUri.isBlank()) {
            return true;
        }
        String normalized = bundleUri.trim().toLowerCase(Locale.ROOT);
        return !normalized.startsWith("gs://")
                && !normalized.startsWith("s3://")
                && !normalized.startsWith("abfs://")
                && !normalized.startsWith("abfss://")
                && !normalized.startsWith("http://")
                && !normalized.startsWith("https://");
    }

    private String normalizePackagedCalendarBundlePath(String bundleUri) {
        String path = bundleUri == null || bundleUri.isBlank()
                ? "runtime/calendar/default-calendar-bundle.json"
                : bundleUri.trim();
        if (path.startsWith("file://")) {
            path = path.substring("file://".length());
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private String calendarBundleJson(List<Map<String, Object>> contracts, String tenantId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "pulse.calendar_bundle.v1");
        payload.put("tenantId", tenantId);
        payload.put("coverageStart", "1900-01-01");
        payload.put("coverageEnd", "2100-12-31");
        payload.put("generatedBy", "PULSE CodeGenerationService");
        payload.put("calendarBindingRefs", contracts.stream()
                .map(contract -> stringValue(contract.get("calendarBindingRef")))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList());
        payload.put("supportedMnemonics", List.of(
                "PBD", "NBD", "FBOM", "LBOM", "NBDOM(n)",
                "TODAY", "RUN_DATE", "BOM", "EOM", "BOQ", "EOQ", "BOY", "EOY"));
        Map<String, Object> calendars = new LinkedHashMap<>();
        calendars.put("US-FED", Map.of(
                "businessDays", businessDays("US-FED"),
                "description", "US federal holidays with observed-date rules"));
        calendars.put("US-NYSE", Map.of(
                "businessDays", businessDays("US-NYSE"),
                "description", "NYSE trading holidays; early-close days remain business days"));
        payload.put("calendars", calendars);
        return toJson(payload);
    }

    private List<String> businessDays(String calendarId) {
        Set<LocalDate> holidays = holidays(calendarId);
        List<String> days = new ArrayList<>();
        LocalDate cursor = LocalDate.of(1900, 1, 1);
        LocalDate end = LocalDate.of(2100, 12, 31);
        while (!cursor.isAfter(end)) {
            if (!isWeekend(cursor) && !holidays.contains(cursor)) {
                days.add(cursor.format(DateTimeFormatter.ISO_LOCAL_DATE));
            }
            cursor = cursor.plusDays(1);
        }
        return days;
    }

    private Set<LocalDate> holidays(String calendarId) {
        Set<LocalDate> holidays = new LinkedHashSet<>();
        for (int year = 1900; year <= 2100; year++) {
            holidays.add(observed(LocalDate.of(year, Month.JANUARY, 1)));
            holidays.add(nthWeekday(year, Month.JANUARY, DayOfWeek.MONDAY, 3));
            holidays.add(nthWeekday(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3));
            holidays.add(lastWeekday(year, Month.MAY, DayOfWeek.MONDAY));
            if (year >= 2021) {
                holidays.add(observed(LocalDate.of(year, Month.JUNE, 19)));
            }
            holidays.add(observed(LocalDate.of(year, Month.JULY, 4)));
            holidays.add(nthWeekday(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1));
            if ("US-FED".equals(calendarId)) {
                holidays.add(nthWeekday(year, Month.OCTOBER, DayOfWeek.MONDAY, 2));
                holidays.add(observed(LocalDate.of(year, Month.NOVEMBER, 11)));
            }
            holidays.add(nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4));
            holidays.add(observed(LocalDate.of(year, Month.DECEMBER, 25)));
            if ("US-NYSE".equals(calendarId)) {
                holidays.add(easter(year).minusDays(2));
            }
        }
        return holidays;
    }

    private boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private LocalDate observed(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return date.minusDays(1);
        }
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return date.plusDays(1);
        }
        return date;
    }

    private LocalDate nthWeekday(int year, Month month, DayOfWeek dayOfWeek, int n) {
        LocalDate cursor = LocalDate.of(year, month, 1);
        while (cursor.getDayOfWeek() != dayOfWeek) {
            cursor = cursor.plusDays(1);
        }
        return cursor.plusWeeks(n - 1L);
    }

    private LocalDate lastWeekday(int year, Month month, DayOfWeek dayOfWeek) {
        LocalDate cursor = LocalDate.of(year, month, month.length(LocalDate.of(year, month, 1).isLeapYear()));
        while (cursor.getDayOfWeek() != dayOfWeek) {
            cursor = cursor.minusDays(1);
        }
        return cursor;
    }

    private LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    private String generatedSecretResolverModule() {
        return """
                from __future__ import annotations

                import os
                import stat
                import tempfile
                from typing import Iterable


                def _read_secret(secret_ref: str, env_var_name: str) -> str:
                    existing = os.environ.get(env_var_name)
                    if existing:
                        return existing
                    if secret_ref.startswith("gcp-sm://"):
                        try:
                            from google.cloud import secretmanager
                        except Exception as exc:  # pragma: no cover - runtime dependency
                            raise RuntimeError(
                                "google-cloud-secret-manager is required for gcp-sm:// runtime hydration"
                            ) from exc
                        client = secretmanager.SecretManagerServiceClient()
                        secret_name = secret_ref.replace("gcp-sm://", "", 1)
                        response = client.access_secret_version(request={"name": secret_name})
                        return response.payload.data.decode("utf-8")
                    if secret_ref.startswith("vault://"):
                        raise RuntimeError(
                            f"Legacy vault:// ref {secret_ref} requires an orchestrator-provided "
                            f"fallback env var ({env_var_name}) until backend vault resolution is migrated."
                        )
                    raise RuntimeError(f"Unsupported secret ref scheme: {secret_ref}")


                def resolve_runtime_secret_env(secret_bindings: Iterable[dict]) -> tuple[dict, list[str]]:
                    env_vars: dict[str, str] = {}
                    runtime_files: list[str] = []

                    for binding in secret_bindings or []:
                        env_var_name = binding["envVarName"]
                        secret_ref = binding["runtimeSecretRef"]
                        delivery_mode = binding["deliveryMode"]
                        resolved = _read_secret(secret_ref, env_var_name)
                        if delivery_mode == "FILE":
                            fd, path = tempfile.mkstemp(prefix="pulse-secret-", suffix=".secret")
                            os.close(fd)
                            with open(path, "w", encoding="utf-8") as handle:
                                handle.write(resolved)
                            os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
                            env_vars[env_var_name] = path
                            runtime_files.append(path)
                        else:
                            env_vars[env_var_name] = resolved

                    return env_vars, runtime_files


                def cleanup_runtime_secret_files(runtime_files: Iterable[str]) -> None:
                    for path in runtime_files or []:
                        try:
                            os.remove(path)
                        except FileNotFoundError:
                            continue
                """;
    }

    private String pythonLiteral(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize generated runtime payload", e);
        }
    }

    private boolean isExplicitSensorBlueprint(String blueprintKey) {
        return Set.of(
                "FileArrivalSensor",
                "ObjectStoreKeySensor",
                "DatabaseReadinessSensor",
                "DatasetDependencySensor",
                "ExternalEventSensor"
        ).contains(blueprintKey);
    }

    private void appendAdvanceTimeDimensionTask(StringBuilder dag,
                                                SubPipelineInstance inst,
                                                String mainTaskId,
                                                CompilePlanService.CompilePlanSnapshot compilePlan) {
        Map<String, Object> contract = advanceTimeContract(inst, compilePlan);
        Map<String, Object> opKwargs = new LinkedHashMap<>();
        opKwargs.put("state_binding_ref", contract.get("stateBindingRef"));
        opKwargs.put("variable_key", contract.get("variableKey"));
        opKwargs.put("calendar_binding_ref", contract.get("calendarBindingRef"));
        opKwargs.put("calendar_bundle_uri", contract.get("calendarBundleUri"));
        opKwargs.put("calendar_bundle_hash", contract.get("calendarBundleHash"));
        opKwargs.put("calendar_id", contract.get("calendarId"));
        opKwargs.put("advance_mode", contract.get("advanceMode"));
        opKwargs.put("requested_asof_expr", contract.get("requestedAsofExpr"));
        opKwargs.put("replay_policy", contract.get("replayPolicy"));
        opKwargs.put("evidence_prefix", contract.get("evidencePrefix"));
        opKwargs.put("initial_value", contract.get("initialValue"));
        opKwargs.put("initialization_policy", contract.get("initializationPolicy"));
        opKwargs.put("concurrency_policy", contract.get("concurrencyPolicy"));
        opKwargs.put("target_scope", contract.get("targetScope"));
        opKwargs.put("grain", contract.get("grain"));
        opKwargs.put("timezone", contract.get("timezone"));
        opKwargs.put("evidence_required", contract.get("evidenceRequired"));
        opKwargs.put("notes_template", contract.get("notesTemplate"));
        opKwargs.put("source", contract.get("source"));
        opKwargs.put("advanced_by", contract.get("advancedBy"));
        Object advanceConfig = contract.get("advanceConfig");
        if (advanceConfig instanceof Map<?, ?> map && !map.isEmpty()) {
            opKwargs.put("advance_config", map);
        }

        dag.append(String.format("        %s = AdvanceTimeDimensionOperator(\n", mainTaskId));
        dag.append(String.format("            task_id='%s',\n", mainTaskId));
        dag.append(String.format("            **%s,\n", pythonLiteral(opKwargs)));
        if ("serialized_airflow".equals(String.valueOf(contract.get("concurrencyPolicy")))) {
            dag.append(String.format("            pool='%s',\n", escapePython(String.valueOf(contract.get("poolName")))));
            dag.append("            pool_slots=1,\n");
        }
        dag.append("        )\n\n");
    }

    private void appendRemotePipelineInvocationTask(StringBuilder dag,
                                                    SubPipelineInstance inst,
                                                    String taskSlug,
                                                    String mainTaskId) {
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
        Map<String, Object> payloadTemplate = mapParam(params, "payload_template");
        RemoteAirflowRuntime runtime = resolveRemoteAirflowRuntime(params);
        int pollInterval = intParam(params, "poll_interval_seconds", runtime.pollIntervalSeconds());
        int timeout = intParam(params, "timeout_seconds", runtime.timeoutSeconds());

        dag.append(String.format("        %s = RemoteAirflowDagRunOperator(\n", mainTaskId));
        dag.append(String.format("            task_id='%s',\n", mainTaskId));
        dag.append(String.format("            airflow_conn_id='%s',\n", escapePython(runtime.airflowConnectionId())));
        dag.append(String.format("            remote_dag_id='%s',\n", escapePython(runtime.remoteDagId())));
        dag.append(String.format("            remote_run_id='pulse__{{{{ dag.dag_id }}}}__{{{{ run_id }}}}__%s',\n",
                escapePython(taskSlug)));
        dag.append(String.format("            payload=%s,\n", pythonLiteral(payloadTemplate)));
        dag.append(String.format("            poll_interval_seconds=%d,\n", pollInterval));
        dag.append(String.format("            timeout_seconds=%d,\n", timeout));
        dag.append("        )\n\n");
    }

    @SuppressWarnings("unchecked")
    private RemoteAirflowRuntime resolveRemoteAirflowRuntime(Map<String, Object> params) {
        String federatedTenantKey = stringParam(params, "federated_tenant_key");
        String remoteTargetRef = stringParam(params, "remote_target_ref");
        String environment = stringParam(params, "environment");
        Optional<RemoteTargetRuntimeMirror> mirror = remoteTargetRuntimeMirrorRepository
                .findFirstByFederatedTenantKeyAndRemoteTargetRefAndEnvironment(
                        federatedTenantKey, remoteTargetRef, environment);
        Map<String, Object> payload = mirror.map(RemoteTargetRuntimeMirror::getPayload).orElse(Map.of());
        Map<String, Object> runtimeMetadata = payload.get("runtimeMetadata") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : Map.of();

        String remoteDagId = firstNonBlank(
                stringParam(params, "remote_dag_id"),
                mirror.map(RemoteTargetRuntimeMirror::getPeerLogicalDagId).orElse(""),
                string(payload.get("peerLogicalDagId")),
                "");
        String airflowConnectionId = firstNonBlank(
                stringParam(params, "airflow_connection_id"),
                string(runtimeMetadata.get("airflowConnectionId")),
                string(payload.get("airflowConnectionId")),
                "");
        int pollInterval = firstPositive(
                intParam(params, "poll_interval_seconds", 0),
                intValue(runtimeMetadata.get("pollIntervalSeconds"), 0),
                intValue(payload.get("pollIntervalSeconds"), 0),
                30);
        int timeout = firstPositive(
                intParam(params, "timeout_seconds", 0),
                intValue(runtimeMetadata.get("timeoutSeconds"), 0),
                intValue(payload.get("timeoutSeconds"), 0),
                7200);
        return new RemoteAirflowRuntime(remoteDagId, airflowConnectionId, pollInterval, timeout);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) return value;
        }
        return 0;
    }

    private record RemoteAirflowRuntime(
            String remoteDagId,
            String airflowConnectionId,
            int pollIntervalSeconds,
            int timeoutSeconds
    ) {}

    private void appendExplicitSensorTask(StringBuilder dag,
                                          SubPipelineInstance inst,
                                          String taskSlug,
                                          String mainTaskId) {
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
        int pokeInterval = intParam(params, "poke_interval_seconds", 300);
        int timeout = intParam(params, "timeout_seconds", 3600);
        String mode = stringParam(params, "mode", "reschedule");

        switch (inst.getBlueprintKey()) {
            case "FileArrivalSensor" -> {
                String storageKind = stringParam(params, "storage_kind", "s3");
                String path = stringParam(params, "path", "{{ ds }}");
                if ("sftp".equalsIgnoreCase(storageKind)) {
                    dag.append(String.format("        %s = SFTPSensor(\n", mainTaskId));
                    dag.append(String.format("            task_id='%s',\n", mainTaskId));
                    dag.append(String.format("            path='%s',\n", escapePython(path)));
                    dag.append("            sftp_conn_id='sftp_default',\n");
                    dag.append(String.format("            poke_interval=%d,\n", pokeInterval));
                    dag.append(String.format("            timeout=%d,\n", timeout));
                    dag.append(String.format("            mode='%s',\n", mode));
                    dag.append("        )\n\n");
                } else {
                    String[] bucketAndKey = splitBucketAndKey(path);
                    dag.append(String.format("        %s = S3KeySensor(\n", mainTaskId));
                    dag.append(String.format("            task_id='%s',\n", mainTaskId));
                    dag.append(String.format("            bucket_name='%s',\n", bucketAndKey[0]));
                    dag.append(String.format("            bucket_key='%s',\n", escapePython(bucketAndKey[1])));
                    dag.append("            wildcard_match=True,\n");
                    dag.append("            aws_conn_id='aws_default',\n");
                    dag.append(String.format("            poke_interval=%d,\n", pokeInterval));
                    dag.append(String.format("            timeout=%d,\n", timeout));
                    dag.append(String.format("            mode='%s',\n", mode));
                    dag.append("        )\n\n");
                }
            }
            case "ObjectStoreKeySensor" -> {
                String bucket = stringParam(params, "bucket", "${S3_BUCKET}");
                String objectKey = stringParam(params, "object_key", "${S3_PREFIX}/{{ ds }}/");
                boolean wildcardMatch = booleanParam(params, "wildcard_match", true);
                dag.append(String.format("        %s = S3KeySensor(\n", mainTaskId));
                dag.append(String.format("            task_id='%s',\n", mainTaskId));
                dag.append(String.format("            bucket_name='%s',\n", escapePython(bucket)));
                dag.append(String.format("            bucket_key='%s',\n", escapePython(objectKey)));
                dag.append(String.format("            wildcard_match=%s,\n", wildcardMatch ? "True" : "False"));
                dag.append("            aws_conn_id='aws_default',\n");
                dag.append(String.format("            poke_interval=%d,\n", pokeInterval));
                dag.append(String.format("            timeout=%d,\n", timeout));
                dag.append(String.format("            mode='%s',\n", mode));
                dag.append("        )\n\n");
            }
            case "DatabaseReadinessSensor" -> {
                String sql = stringParam(params, "sql", "SELECT 1");
                String connectionId = stringParam(params, "connection_id", "pulse_sql_default");
                dag.append(String.format("        %s = SqlSensor(\n", mainTaskId));
                dag.append(String.format("            task_id='%s',\n", mainTaskId));
                dag.append(String.format("            conn_id='%s',\n", escapePython(connectionId)));
                dag.append(String.format("            sql=\"%s\",\n", escapePython(sql)));
                dag.append(String.format("            poke_interval=%d,\n", pokeInterval));
                dag.append(String.format("            timeout=%d,\n", timeout));
                dag.append(String.format("            mode='%s',\n", mode));
                dag.append("        )\n\n");
            }
            case "DatasetDependencySensor" -> {
                String upstreamDagId = stringParam(params, "upstream_dag_id", "upstream_dag");
                String upstreamTaskId = stringParam(params, "upstream_task_id", "");
                List<String> allowedStates = listParam(params, "allowed_states", List.of("success"));
                dag.append(String.format("        %s = ExternalTaskSensor(\n", mainTaskId));
                dag.append(String.format("            task_id='%s',\n", mainTaskId));
                dag.append(String.format("            external_dag_id='%s',\n", escapePython(upstreamDagId)));
                if (!upstreamTaskId.isBlank()) {
                    dag.append(String.format("            external_task_id='%s',\n", escapePython(upstreamTaskId)));
                }
                dag.append(String.format("            allowed_states=%s,\n", pythonStringList(allowedStates)));
                dag.append(String.format("            poke_interval=%d,\n", pokeInterval));
                dag.append(String.format("            timeout=%d,\n", timeout));
                dag.append("        )\n\n");
            }
            case "ExternalEventSensor" -> {
                String eventUrl = stringParam(params, "event_url", "https://example.com/ready");
                int successStatusCode = intParam(params, "success_status_code", 200);
                dag.append(String.format("        %s = PythonSensor(\n", mainTaskId));
                dag.append(String.format("            task_id='%s',\n", mainTaskId));
                dag.append("            python_callable=lambda: requests.get(\n");
                dag.append(String.format("                '%s', timeout=10\n", escapePython(eventUrl)));
                dag.append(String.format("            ).status_code == %d,\n", successStatusCode));
                dag.append(String.format("            poke_interval=%d,\n", pokeInterval));
                dag.append(String.format("            timeout=%d,\n", timeout));
                dag.append(String.format("            mode='%s',\n", mode));
                dag.append("        )\n\n");
            }
        }
    }

    private List<GeneratedArtifact> generateGxCheckpoints(GenerationRun run,
                                                          List<SubPipelineInstance> instances,
                                                          String slug,
                                                          CompilePlanService.CompilePlanSnapshot compilePlan) {
        List<GeneratedArtifact> out = new ArrayList<>();
        for (var inst : instances) {
            Blueprint bp = blueprintRepo.findById(inst.getBlueprintId()).orElse(null);
            String category = bp != null && bp.getCategory() != null ? bp.getCategory().name() : "";
            if (!"DATA_QUALITY".equals(category)) {
                continue;
            }
            List<Map<String, Object>> expectations = gxExpectations(inst);
            String blueprintKey = inst.getBlueprintKey();
            boolean emitsSemanticReport = Set.of("FreshnessChecks", "SchemaDriftDetection", "AnomalyDetection")
                    .contains(blueprintKey);
            if (!emitsSemanticReport && (expectations == null || expectations.isEmpty())) continue;

            String instanceSlug = slugify(inst.getName());
            Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
            if (bp != null && OpList.isOpList(bp.getSchemaBehavior())) {
                GeneratedArtifact opEngineArtifact = generateGxCheckpointWithOpEngine(
                        run, inst, bp, instances, slug, compilePlan);
                if (opEngineArtifact != null) {
                    out.add(opEngineArtifact);
                    continue;
                }
                throw new IllegalStateException("Op-listed GX blueprint " + inst.getBlueprintKey()
                        + " was not emitted by CodegenOpEngine");
            }

            String onFailure = stringParam(params, "on_failure", "fail");
            String inputPath = resolveUpstreamDeltaPath(inst, instances, slug, run.getTenantId(), compilePlan);
            String outputPath = resolveDataQualityOutputPath(inst, slug, run.getTenantId(), compilePlan);

            StringBuilder py = new StringBuilder();
            py.append("# Generated GX checkpoint for ").append(inst.getName()).append("\n");
            py.append("# Blueprint: ").append(inst.getBlueprintKey()).append("\n\n");
            py.append("import os\n");
            py.append("from pyspark.sql import SparkSession\n\n");
            py.append("PULSE_BUSINESS_DATE = os.environ.get('PULSE_BUSINESS_DATE', '{{ ds }}')\n");
            py.append("spark = SparkSession.builder.appName('gx_").append(instanceSlug).append("').getOrCreate()\n");
            if (inputPath == null) {
                py.append("input_path = os.environ.get('OUTPUT_BASE', '/tmp') + '/silver/")
                        .append(instanceSlug).append("'\n");
            } else {
                py.append("input_path = '").append(inputPath).append("'\n");
            }
            py.append("df = spark.read.format('delta').load(input_path)\n");
            if ("FreshnessChecks".equals(blueprintKey)) {
                appendFreshnessReportCheckpoint(py, params);
            } else if ("SchemaDriftDetection".equals(blueprintKey)) {
                appendSchemaDriftReportCheckpoint(py, params);
            } else if ("AnomalyDetection".equals(blueprintKey)) {
                appendAnomalyReportCheckpoint(py, params);
            } else {
                py.append(gxCodeGenerator.generateGxValidation("df", expectations, onFailure));
            }
            if (outputPath == null) {
                py.append("output_path = os.environ.get('OUTPUT_BASE', '/tmp') + '/silver/")
                        .append(instanceSlug).append("'\n");
            } else {
                py.append("output_path = '").append(outputPath).append("'\n");
            }
            if (emitsSemanticReport) {
                py.append("output_df = report_df\n");
                py.append("output_df.write.mode('overwrite').format('delta').option('overwriteSchema', 'true').save(output_path)\n");
                py.append("print(f\"DQ REPORT OUTPUT: {output_df.count()} rows written to {output_path}\")\n");
            } else {
                py.append("df.write.mode('overwrite').format('delta').option('overwriteSchema', 'true').save(output_path)\n");
                py.append("print(f\"DQ VALIDATED OUTPUT: {df.count()} rows written to {output_path}\")\n");
            }
            py.append("spark.stop()\n");

            String path = String.format("gx/checkpoints/%s_checkpoint.py", instanceSlug);
            out.add(createArtifact(run, path, "GX_CHECKPOINT", py.toString(), "gx_checkpoint"));
        }
        return out;
    }

    private GeneratedArtifact generateGxCheckpointWithOpEngine(GenerationRun run,
                                                               SubPipelineInstance inst,
                                                               Blueprint bp,
                                                               List<SubPipelineInstance> instances,
                                                               String slug,
                                                               CompilePlanService.CompilePlanSnapshot compilePlan) {
        OpList opList = OpList.parse(bp.getSchemaBehavior());
        if (!"gx".equalsIgnoreCase(opList.emission().compute())) {
            return null;
        }
        for (OpList.OpEntry op : opList.ops()) {
            if (!codegenOpEngine().registry().has(op.op(), EmissionEngine.GX)) {
                throw new IllegalStateException("Missing CodegenOpEngine GX handler for op "
                        + op.op() + " on " + bp.getBlueprintKey());
            }
        }
        String instanceSlug = slugify(inst.getName());
        String inputPath = resolveUpstreamDeltaPath(inst, instances, slug, run.getTenantId(), compilePlan);
        String outputPath = resolveDataQualityOutputPath(inst, slug, run.getTenantId(), compilePlan);
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();

        StringBuilder py = new StringBuilder();
        py.append("# Generated GX checkpoint for ").append(inst.getName()).append("\n");
        py.append("# Blueprint: ").append(inst.getBlueprintKey()).append("\n");
        py.append("# Codegen engine: CodegenOpEngine\n\n");
        py.append("import os\n");
        py.append("from pyspark.sql import SparkSession\n\n");
        py.append("PULSE_BUSINESS_DATE = os.environ.get('PULSE_BUSINESS_DATE', '{{ ds }}')\n");
        py.append("spark = SparkSession.builder.appName('gx_").append(instanceSlug).append("').getOrCreate()\n");
        if (inputPath == null) {
            py.append("input_path = os.environ.get('OUTPUT_BASE', '/tmp') + '/silver/")
                    .append(instanceSlug).append("'\n");
        } else {
            py.append("input_path = '").append(escapePython(inputPath)).append("'\n");
        }
        py.append("df = spark.read.format('delta').load(input_path)\n");
        py.append("report_df = df\n");
        Mode mode = codegenOpEngine.modeResolver().activeMode();
        py.append(codegenOpEngine().gx().emit(opList.ops(), op -> EmitContext.builder()
                .mode(mode)
                .modeResolver(codegenOpEngine.modeResolver())
                .config(new ResolvedConfig(resolveGxOpConfig(op, inst, slug, run.getTenantId(), compilePlan)))
                .dfVar("df")
                .lakeLayer(stringParam(params, "lake_layer", stringParam(params, "lakeLayer", "silver")))
                .build()));
        if (outputPath == null) {
            py.append("output_path = os.environ.get('OUTPUT_BASE', '/tmp') + '/silver/")
                    .append(instanceSlug).append("'\n");
        } else {
            py.append("output_path = '").append(escapePython(outputPath)).append("'\n");
        }
        py.append("df.write.mode('overwrite').format('delta').option('overwriteSchema', 'true').save(output_path)\n");
        py.append("print(f\"DQ VALIDATED OUTPUT: {df.count()} rows written to {output_path}\")\n");
        py.append("spark.stop()\n");

        GeneratedArtifact artifact = createArtifact(run,
                String.format("gx/checkpoints/%s_checkpoint.py", instanceSlug),
                "GX_CHECKPOINT",
                py.toString(),
                "gx_checkpoint");
        return markOpEngineArtifact(artifact, inst, bp);
    }

    private Map<String, Object> resolveGxOpConfig(OpList.OpEntry op,
                                                  SubPipelineInstance inst,
                                                  String slug,
                                                  String tenantId,
                                                  CompilePlanService.CompilePlanSnapshot compilePlan) {
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
        Map<String, Object> cfg = ParamResolver.resolve(op.op(), op.config(), params, null);
        if ("check-data".equals(op.op())) {
            cfg.putIfAbsent("on_failure", params.getOrDefault("on_failure",
                    params.getOrDefault("drift_policy", "block")));
            cfg.putIfAbsent("expectations", gxExpectations(inst));
        }
        if ("emit-report".equals(op.op())) {
            Object mode = cfg.remove("mode");
            cfg.putIfAbsent("report_mode", mode != null ? mode : "append");
            cfg.putIfAbsent("report_path", resolveDataQualityOutputPath(inst, slug, tenantId, compilePlan));
        }
        return cfg;
    }

    private Map<String, Object> resolveDagOnlyOpConfig(OpList.OpEntry op,
                                                       SubPipelineInstance inst,
                                                       String taskId) {
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
        Map<String, Object> cfg = ParamResolver.resolve(op.op(), op.config(), params, null);
        cfg.putIfAbsent("task_id", taskId);
        switch (op.op()) {
            case "sense" -> {
                String blueprintKey = inst.getBlueprintKey();
                if ("DatabaseReadinessSensor".equals(blueprintKey)) {
                    cfg.put("sense_type", "sql_query");
                    cfg.putIfAbsent("conn_id", params.getOrDefault("connection_id", "pulse_sql_default"));
                } else if ("DatasetDependencySensor".equals(blueprintKey)) {
                    cfg.put("sense_type", "trigger");
                    cfg.putIfAbsent("external_dag_id", params.getOrDefault("external_dag_id",
                            params.getOrDefault("upstream_dag_id", "")));
                    cfg.putIfAbsent("external_task_id", params.getOrDefault("external_task_id", ""));
                } else {
                    cfg.putIfAbsent("sense_type", "file");
                    Object pathPrefix = cfg.remove("path_prefix");
                    Object pattern = cfg.remove("filename_pattern");
                    String object = firstNonBlank(
                            pathPrefix != null ? pathPrefix.toString() : null,
                            "");
                    if (pattern != null && !pattern.toString().isBlank()) {
                        object = object + (object.endsWith("/") || object.isBlank() ? "" : "/")
                                + pattern;
                    }
                    cfg.putIfAbsent("object", object);
                    cfg.putIfAbsent("filepath", object);
                }
            }
            case "schedule-and-triggers" -> {
                cfg.putIfAbsent("schedule", params.getOrDefault("cron_expression",
                        params.getOrDefault("schedule", "@daily")));
                Object triggerDataset = cfg.remove("trigger_dataset");
                if (triggerDataset != null && !triggerDataset.toString().isBlank()) {
                    cfg.putIfAbsent("trigger_dags", List.of(triggerDataset.toString()));
                }
            }
            case "advance-time" -> {
                cfg.putIfAbsent("target_scope", params.getOrDefault("target_scope", "pipeline"));
                cfg.putIfAbsent("state_binding_ref", params.getOrDefault("state_binding_ref", ""));
                cfg.putIfAbsent("variable_key", params.getOrDefault("variable_key", ""));
                cfg.putIfAbsent("calendar_binding_ref", params.getOrDefault("calendar_binding_ref", ""));
                cfg.putIfAbsent("calendar_bundle_uri", params.getOrDefault("calendar_bundle_uri", ""));
                cfg.putIfAbsent("calendar_bundle_hash", params.getOrDefault("calendar_bundle_hash", ""));
                cfg.putIfAbsent("calendar_id", params.getOrDefault("calendar_id", "default"));
                cfg.putIfAbsent("replay_policy", params.getOrDefault("replay_policy", "fail_if_regresses"));
                cfg.putIfAbsent("evidence_prefix", params.getOrDefault("evidence_prefix", "pulse/time-state"));
                cfg.putIfAbsent("initial_value", params.getOrDefault("initial_value", ""));
                cfg.putIfAbsent("initialization_policy", params.getOrDefault("initialization_policy", "require_existing"));
                cfg.putIfAbsent("concurrency_policy", params.getOrDefault("concurrency_policy", "serialized_airflow"));
                cfg.putIfAbsent("grain", params.getOrDefault("grain", "day"));
                cfg.putIfAbsent("timezone", params.getOrDefault("timezone", "UTC"));
                cfg.putIfAbsent("evidence_required", params.getOrDefault("evidence_required", true));
                cfg.putIfAbsent("notes_template", params.getOrDefault("notes_template", ""));
                cfg.putIfAbsent("source", params.getOrDefault("source", "pulse"));
                cfg.putIfAbsent("advanced_by", params.getOrDefault("advanced_by", "airflow"));
                cfg.putIfAbsent("pool_name", "pulse_time_state_" + taskId);
            }
            case "invoke-remote" -> {
                cfg.putIfAbsent("remote_dag_id", params.getOrDefault("remote_dag_id", ""));
                cfg.putIfAbsent("target_ref", params.getOrDefault("remote_target_ref", ""));
            }
            default -> {
                // Handler defaults are sufficient.
            }
        }
        return cfg;
    }

    private void appendFreshnessReportCheckpoint(StringBuilder py, Map<String, Object> params) {
        String timestampColumn = stringParam(params, "timestamp_column", "as_of_date");
        long maxAgeMinutes = longParam(params, "max_age_minutes", longParam(params, "max_delay_minutes",
                longParam(params, "max_age_hours", 24L) * 60L));
        py.append("from pyspark.sql import functions as F\n");
        py.append("timestamp_column = '").append(escapePython(timestampColumn)).append("'\n");
        py.append("max_age_minutes = ").append(maxAgeMinutes).append("\n");
        py.append("business_date = os.environ.get('PULSE_BUSINESS_DATE', '1970-01-01')\n");
        py.append("stats = df.select(\n");
        py.append("    F.count(F.lit(1)).cast('long').alias('row_count'),\n");
        py.append("    F.date_format(F.max(F.to_date(F.col(timestamp_column))), 'yyyy-MM-dd').alias('max_observed_date')\n");
        py.append(").collect()[0]\n");
        py.append("max_observed_date = stats['max_observed_date']\n");
        py.append("if max_observed_date is None:\n");
        py.append("    actual_age_minutes = None\n");
        py.append("    status = 'FAIL'\n");
        py.append("else:\n");
        py.append("    actual_age_minutes = spark.sql(\n");
        py.append("        f\"SELECT CAST((unix_timestamp(to_date('{business_date}')) - unix_timestamp(to_date('{max_observed_date}'))) / 60 AS BIGINT) AS age_minutes\"\n");
        py.append("    ).collect()[0]['age_minutes']\n");
        py.append("    status = 'PASS' if actual_age_minutes <= max_age_minutes else 'FAIL'\n");
        py.append("report_df = spark.createDataFrame([{\n");
        py.append("    'check_name': 'freshness',\n");
        py.append("    'timestamp_column': timestamp_column,\n");
        py.append("    'business_date': business_date,\n");
        py.append("    'max_observed_date': max_observed_date,\n");
        py.append("    'max_age_minutes': int(max_age_minutes),\n");
        py.append("    'actual_age_minutes': int(actual_age_minutes) if actual_age_minutes is not None else None,\n");
        py.append("    'row_count': int(stats['row_count']),\n");
        py.append("    'status': status,\n");
        py.append("}])\n");
    }

    @SuppressWarnings("unchecked")
    private void appendSchemaDriftReportCheckpoint(StringBuilder py, Map<String, Object> params) {
        List<String> expectedColumns = params.get("expected_columns") instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();
        boolean allowExtraColumns = booleanParam(params, "allow_extra_columns", true);
        py.append("expected_columns = [")
                .append(expectedColumns.stream().map(value -> "'" + escapePython(value) + "'").collect(Collectors.joining(", ")))
                .append("]\n");
        py.append("allow_extra_columns = ").append(allowExtraColumns ? "True" : "False").append("\n");
        py.append("actual_columns = [c for c in df.columns if not c.startswith('_pulse_')]\n");
        py.append("missing_columns = sorted([c for c in expected_columns if c not in actual_columns])\n");
        py.append("added_columns = sorted([c for c in actual_columns if c not in expected_columns])\n");
        py.append("status = 'PASS' if not missing_columns and (allow_extra_columns or not added_columns) else 'FAIL'\n");
        py.append("report_df = spark.createDataFrame([{\n");
        py.append("    'check_name': 'schema_drift',\n");
        py.append("    'expected_columns': '|'.join(expected_columns),\n");
        py.append("    'actual_columns': '|'.join(actual_columns),\n");
        py.append("    'missing_columns': '|'.join(missing_columns),\n");
        py.append("    'added_columns': '|'.join(added_columns),\n");
        py.append("    'allow_extra_columns': 'true' if allow_extra_columns else 'false',\n");
        py.append("    'expected_column_count': len(expected_columns),\n");
        py.append("    'actual_column_count': len(actual_columns),\n");
        py.append("    'row_count': df.count(),\n");
        py.append("    'status': status,\n");
        py.append("}])\n");
    }

    private void appendAnomalyReportCheckpoint(StringBuilder py, Map<String, Object> params) {
        List<String> monitoredColumns = params.get("monitored_columns") instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of("current_upb");
        py.append("from pyspark.sql import functions as F\n");
        py.append("monitored_columns = [")
                .append(monitoredColumns.stream().map(value -> "'" + escapePython(value) + "'").collect(Collectors.joining(", ")))
                .append("]\n");
        py.append("z_threshold = 3.0\n");
        py.append("rows = []\n");
        py.append("for column_name in monitored_columns:\n");
        py.append("    stats = df.select(\n");
        py.append("        F.count(F.lit(1)).cast('long').alias('row_count'),\n");
        py.append("        F.avg(F.col(column_name).cast('double')).alias('mean_value'),\n");
        py.append("        F.stddev_pop(F.col(column_name).cast('double')).alias('stddev_value')\n");
        py.append("    ).collect()[0]\n");
        py.append("    mean_value = float(stats['mean_value'] or 0.0)\n");
        py.append("    stddev_value = float(stats['stddev_value'] or 0.0)\n");
        py.append("    if stddev_value == 0.0:\n");
        py.append("        anomaly_count = 0\n");
        py.append("    else:\n");
        py.append("        anomaly_count = df.where(F.abs((F.col(column_name).cast('double') - F.lit(mean_value)) / F.lit(stddev_value)) > F.lit(z_threshold)).count()\n");
        py.append("    rows.append({\n");
        py.append("        'check_name': 'anomaly_detection',\n");
        py.append("        'monitored_column': column_name,\n");
        py.append("        'row_count': int(stats['row_count']),\n");
        py.append("        'mean_value': f'{mean_value:.4f}',\n");
        py.append("        'stddev_value': f'{stddev_value:.4f}',\n");
        py.append("        'z_threshold': f'{z_threshold:.1f}',\n");
        py.append("        'anomaly_count': int(anomaly_count),\n");
        py.append("        'status': 'PASS',\n");
        py.append("    })\n");
        py.append("report_df = spark.createDataFrame(rows)\n");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> gxExpectations(SubPipelineInstance inst) {
        if (inst.getDqExpectations() != null && !inst.getDqExpectations().isEmpty()) {
            return inst.getDqExpectations();
        }
        Map<String, Object> params = inst.getParams() != null ? inst.getParams() : Map.of();
        Object expectations = params.get("expectations");
        if (expectations instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private List<String> collectLayerCheckpointScripts(List<SubPipelineInstance> instances,
                                                       CompilePlanService.CompilePlanSnapshot compilePlan,
                                                       String layer) {
        Set<String> instanceLayers = new LinkedHashSet<>();
        if (compilePlan != null) {
            for (Map<String, Object> node : compilePlan.nodes()) {
                if (layer.equals(node.get("resolvedLayer"))) {
                    instanceLayers.add(node.get("instanceId").toString());
                }
            }
        }
        List<String> scripts = new ArrayList<>();
        for (SubPipelineInstance inst : instances) {
            if (!instanceLayers.contains(inst.getId())) continue;
            if (inst.getDqExpectations() == null || inst.getDqExpectations().isEmpty()) continue;
            scripts.add(String.format("gx/checkpoints/%s_checkpoint.py", slugify(inst.getName())));
        }
        return scripts;
    }

    private GeneratedArtifact generateCompilePlanArtifact(GenerationRun run,
                                                          CompilePlanService.CompilePlanSnapshot compilePlan) {
        return createArtifact(
                run,
                "config/compile-plan.json",
                "COMPILE_PLAN",
                toJson(compilePlanAsMap(compilePlan)),
                "compile_plan"
        );
    }

    private List<GeneratedArtifact> generateSelectorArtifacts(GenerationRun run,
                                                              String slug,
                                                              CompilePlanService.CompilePlanSnapshot compilePlan) {
        String yml = """
                selectors:
                  - name: %s
                    description: All dbt models generated for pipeline %s
                    definition:
                      union:
                        - method: tag
                          value: %s
                """.formatted(slug, slug, slug);

        String path = String.format("dbt_project/selectors/%s.yml", slug);
        return List.of(createArtifact(run, path, "DBT_SELECTOR", yml, "dbt_selector"));
    }

    private GeneratedArtifact generateGoldPublishContractArtifact(GenerationRun run,
                                                                  CompilePlanService.CompilePlanSnapshot compilePlan) {
        return createArtifact(
                run,
                "config/gold-publish-contract.json",
                "GOLD_PUBLISH_CONTRACT",
                toJson(compilePlan.goldPublishBoundary()),
                "gold_publish_contract"
        );
    }

    private void preserveManualEdits(GenerationRun run, List<GeneratedArtifact> newArtifacts, String versionId) {
        var prevRun = runRepo.findTopByVersionIdOrderByCreatedAtDesc(versionId);
        if (prevRun.isEmpty() || prevRun.get().getId().equals(run.getId())) return;

        var manualEdits = artifactRepo.findByGenerationRunIdAndManuallyModifiedTrue(prevRun.get().getId());
        Map<String, GeneratedArtifact> editsByPath = new HashMap<>();
        for (var edit : manualEdits) editsByPath.put(edit.getFilePath(), edit);

        for (int i = 0; i < newArtifacts.size(); i++) {
            var artifact = newArtifacts.get(i);
            var prev = editsByPath.get(artifact.getFilePath());
            if (prev != null) {
                artifact.setContent(prev.getContent());
                artifact.setContentHash(prev.getContentHash());
                artifact.setManuallyModified(true);
            }
        }
    }

    private GeneratedArtifact createArtifact(GenerationRun run, String path, String type,
                                              String content, String templateName) {
        GeneratedArtifact a = new GeneratedArtifact();
        a.setGenerationRunId(run.getId());
        a.setFilePath(path);
        a.setFileType(type);
        a.setContent(content);
        a.setContentHash(sha256(content));
        a.setTemplateName(templateName);
        a.setMetadata(Map.of());
        return a;
    }

    private void applyNamespace(List<GeneratedArtifact> artifacts, String namespace) {
        for (GeneratedArtifact artifact : artifacts) {
            String path = artifact.getFilePath();
            // dbt_project/** lives at the tenant repo root (shared across pipelines),
            // so it is not prefixed with the per-pipeline namespace.
            if (!path.startsWith("dbt_project/")) {
                artifact.setFilePath(namespace + "/" + path);
            }
            Map<String, Object> metadata = new LinkedHashMap<>(
                    artifact.getMetadata() == null ? Map.of() : artifact.getMetadata());
            metadata.put("namespace", namespace);
            artifact.setMetadata(metadata);
        }
    }

    private Map<String, Object> compilePlanAsMap(CompilePlanService.CompilePlanSnapshot compilePlan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipelineId", compilePlan.pipelineId());
        payload.put("versionId", compilePlan.versionId());
        payload.put("domainId", compilePlan.domainId());
        payload.put("domainName", compilePlan.domainName());
        payload.put("namespace", compilePlan.namespace());
        payload.put("nodes", sanitizeCompilePlanValue(compilePlan.nodes()));
        payload.put("goldPublishBoundary", sanitizeCompilePlanValue(compilePlan.goldPublishBoundary()));
        payload.put("advanceTimeContracts", sanitizeCompilePlanValue(compilePlan.advanceTimeContracts()));
        return payload;
    }

    private Object sanitizeCompilePlanValue(Object value) {
        if (value instanceof String s) {
            return substituteMnemonicsForRuntime(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey().toString(), sanitizeCompilePlanValue(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(sanitizeCompilePlanValue(item));
            }
            return out;
        }
        return value;
    }

    private Map<String, Object> advanceTimeContract(SubPipelineInstance inst,
                                                    CompilePlanService.CompilePlanSnapshot compilePlan) {
        return compilePlan.advanceTimeContracts().stream()
                .filter(contract -> Objects.equals(contract.get("instanceId"), inst.getId()))
                .findFirst()
                .map(LinkedHashMap::new)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing AdvanceTimeDimension runtime contract for instance " + inst.getId()));
    }

    private String readRuntimeHelperResource(String resourcePath) {
        try {
            return new String(new ClassPathResource(resourcePath).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load runtime helper resource " + resourcePath, e);
        }
    }

    private String selectorEntries(List<String> nodes) {
        if (nodes.isEmpty()) {
            return "        - method: tag\n          value: pulse_empty";
        }
        StringBuilder builder = new StringBuilder();
        for (String node : nodes) {
            builder.append("        - method: fqn\n")
                    .append("          value: ").append(node).append("\n");
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> listParam(Map<String, Object> params, String key, List<String> fallback) {
        Object value = params.get(key);
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(Object::toString).toList();
        }
        return fallback;
    }

    private int intParam(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private long longParam(Map<String, Object> params, String key, long fallback) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanParam(Map<String, Object> params, String key, boolean fallback) {
        Object value = params.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            String raw = value.toString().trim();
            if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw = raw.substring(1, raw.length() - 1).trim();
            }
            return Boolean.parseBoolean(raw);
        }
        return fallback;
    }

    private String stringParam(Map<String, Object> params, String key, String fallback) {
        Object value = params.get(key);
        if (value == null) {
            return fallback;
        }
        String stringValue = value.toString().trim();
        return stringValue.isEmpty() ? fallback : stringValue;
    }

    private String[] splitBucketAndKey(String path) {
        String normalized = path;
        if (normalized.startsWith("s3://")) {
            normalized = normalized.substring(5);
        }
        int slashIndex = normalized.indexOf('/');
        if (slashIndex < 0) {
            return new String[]{normalized, "*"};
        }
        return new String[]{normalized.substring(0, slashIndex), normalized.substring(slashIndex + 1)};
    }

    private String pythonStringList(List<String> values) {
        return values.stream()
                .map(value -> "'" + escapePython(value) + "'")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize generated artifact payload", e);
        }
    }

    private String slugify(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private String normalizeSparkSourceFormat(String rawFormat) {
        String normalized = rawFormat == null ? "parquet" : rawFormat.trim().toLowerCase();
        return switch (normalized) {
            case "csv", "json", "parquet", "orc", "avro", "text" -> normalized;
            default -> "parquet";
        };
    }

    private void appendCsvRead(StringBuilder py,
                               String objectStoragePath,
                               String header,
                               String inferSchema) {
        if (!Boolean.parseBoolean(inferSchema)) {
            py.append("df = spark.read \\\n");
            py.append("    .format('csv') \\\n");
            py.append(String.format("    .option('header', '%s') \\\n", header));
            py.append("    .option('inferSchema', 'false') \\\n");
            py.append(String.format("    .load('%s')\n\n", objectStoragePath));
            return;
        }

        py.append("""
                # PULSE CSV inference reads as strings first, infers a guarded schema,
                # then rereads with explicit types. This preserves identifier-like
                # values such as ZIP/postal codes that Spark's CSV inferSchema would
                # otherwise coerce from "06101" to 6101.
                from pyspark.sql import functions as F
                from pyspark.sql.types import StructType, StructField, StringType, LongType, DoubleType, BooleanType, DateType

                def _pulse_quote_column(column_name):
                    return "`" + column_name.replace("`", "``") + "`"

                def _pulse_infer_csv_schema(spark, source_path, has_header):
                    raw_df = spark.read \\
                        .format('csv') \\
                        .option('header', has_header) \\
                        .option('inferSchema', 'false') \\
                        .load(source_path)
                    aggregations = []
                    aliases_by_column = []
                    for idx, column_name in enumerate(raw_df.columns):
                        value = F.trim(F.col(_pulse_quote_column(column_name)))
                        present = value.isNotNull() & (value != '')
                        aliases = {
                            'present': f'c{idx}_present',
                            'leading_zero_integer': f'c{idx}_leading_zero_integer',
                            'boolean': f'c{idx}_boolean',
                            'integer': f'c{idx}_integer',
                            'decimal': f'c{idx}_decimal',
                            'date': f'c{idx}_date',
                        }
                        aggregations.extend([
                            F.sum(F.when(present, 1).otherwise(0)).alias(aliases['present']),
                            F.sum(F.when(present & value.rlike(r'^0[0-9]+$'), 1).otherwise(0)).alias(aliases['leading_zero_integer']),
                            F.sum(F.when(present & F.lower(value).isin('true', 'false'), 1).otherwise(0)).alias(aliases['boolean']),
                            F.sum(F.when(present & value.rlike(r'^-?[0-9]+$'), 1).otherwise(0)).alias(aliases['integer']),
                            F.sum(F.when(present & value.rlike(r'^-?([0-9]+\\.[0-9]+|[0-9]+)$'), 1).otherwise(0)).alias(aliases['decimal']),
                            F.sum(F.when(present & value.rlike(r'^[0-9]{4}-[0-9]{2}-[0-9]{2}$'), 1).otherwise(0)).alias(aliases['date']),
                        ])
                        aliases_by_column.append((column_name, aliases))

                    metrics = raw_df.agg(*aggregations).collect()[0].asDict() if aggregations else {}
                    fields = []
                    for column_name, aliases in aliases_by_column:
                        present_count = int(metrics.get(aliases['present']) or 0)
                        leading_zero_integer_count = int(metrics.get(aliases['leading_zero_integer']) or 0)
                        boolean_count = int(metrics.get(aliases['boolean']) or 0)
                        integer_count = int(metrics.get(aliases['integer']) or 0)
                        decimal_count = int(metrics.get(aliases['decimal']) or 0)
                        date_count = int(metrics.get(aliases['date']) or 0)

                        if present_count == 0 or leading_zero_integer_count > 0:
                            data_type = StringType()
                        elif boolean_count == present_count:
                            data_type = BooleanType()
                        elif integer_count == present_count:
                            data_type = LongType()
                        elif decimal_count == present_count:
                            data_type = DoubleType()
                        elif date_count == present_count:
                            data_type = DateType()
                        else:
                            data_type = StringType()
                        fields.append(StructField(column_name, data_type, True))
                    return StructType(fields)

                """);
        py.append(String.format("source_path = '%s'\n", objectStoragePath));
        py.append(String.format("has_header = '%s'\n", header));
        py.append("schema = _pulse_infer_csv_schema(spark, source_path, has_header)\n");
        py.append("df = spark.read \\\n");
        py.append("    .format('csv') \\\n");
        py.append("    .option('header', has_header) \\\n");
        py.append("    .schema(schema) \\\n");
        py.append("    .load(source_path)\n\n");
    }

    /**
     * Resolves the full s3a:// (or gs://) URI for a FileIngestion instance's
     * source-side read at codegen time, using the PULSE path convention:
     *
     * <pre>{scheme}://{storage_root_files}/{domain}/{sor}/{pipeline}/{lifecycle}/</pre>
     *
     * Object-storage connector_instances carry NO path-related config_template
     * data — connectors are identity-only. Paths are pipeline-property and
     * resolve here from (storage_backend, domain, sor, pipeline_slug, lifecycle).
     *
     * <p>Returns null if any required piece (connector_instance, sor, domain,
     * storage_backend row) is missing — caller falls back to a placeholder
     * with an explicit error comment so the failure is visible at compile time.
     *
     * @param inst the SubPipelineInstance whose params reference the connector
     * @param pipelineSlug the pipeline-level codegen slug (snake_case identifier form from generate())
     * @param tenantId the tenant scope for storage_backend lookup
     * @param lifecycle which lifecycle folder (SRC for ingestion, OUTGOING_EXTRACTS for sink, etc.)
     */
    private String resolveObjectStoragePath(SubPipelineInstance inst,
                                             String pipelineSlug,
                                             String tenantId,
                                             FileLifecycle lifecycle) {
        ConnectorInstance ci = resolveConnectorInstance(inst.getParams());
        if (ci == null) return null;
        SystemOfRecord sor = sorRepo.findById(ci.getSorId()).orElse(null);
        if (sor == null) return null;

        String domainSlug = "default";
        if (sor.getDomainId() != null && !sor.getDomainId().isBlank()) {
            Domain d = domainRepo.findById(sor.getDomainId()).orElse(null);
            if (d != null && d.getSlug() != null && !d.getSlug().isBlank()) {
                domainSlug = d.getSlug();
            }
        }
        String sorSlug = sor.getName() == null ? "unknown"
                : com.pulse.common.text.Slugify.slugify(sor.getName());

        String backend = inst.getStorageBackend();
        if (backend == null || backend.isBlank()) {
            backend = resolveDefaultBackend();
        }
        String env = resolveCodegenEnvironment();
        StorageBackend sb = storageBackendRepo
                .findByTenantIdAndEnvironmentAndBackend(tenantId, env, backend)
                .orElse(null);
        if (sb == null) return null;

        String pathPipelineSlug = com.pulse.common.text.Slugify.slugify(pipelineSlug);
        return pathConventionService.filesPath(sb, domainSlug, sorSlug, pathPipelineSlug, lifecycle);
    }

    /**
     * Resolves a lake-resident table path for generated runtime code.
     *
     * <p>The catalog identifier (for example {@code bronze_loan_drops.ingest_loan_files})
     * remains snake_case because Spark/dbt relation names are identifiers. The physical
     * storage location uses PULSE path slugs per {@link PathConventionService}.
     */
    private String resolveLakeTablePath(SubPipelineInstance inst,
                                        String pipelineSlug,
                                        String tenantId,
                                        LakeLayer layer,
                                        LakeFormat format,
                                        String tableSlug) {
        ConnectorInstance ci = resolveConnectorInstance(inst.getParams());
        if (ci == null) return null;
        SystemOfRecord sor = sorRepo.findById(ci.getSorId()).orElse(null);
        if (sor == null) return null;

        String domainSlug = "default";
        if (sor.getDomainId() != null && !sor.getDomainId().isBlank()) {
            Domain d = domainRepo.findById(sor.getDomainId()).orElse(null);
            if (d != null && d.getSlug() != null && !d.getSlug().isBlank()) {
                domainSlug = d.getSlug();
            }
        }
        String sorSlug = sor.getName() == null ? "unknown"
                : com.pulse.common.text.Slugify.slugify(sor.getName());

        String backend = inst.getStorageBackend();
        if (backend == null || backend.isBlank()) {
            backend = resolveDefaultBackend();
        }
        String env = resolveCodegenEnvironment();
        StorageBackend sb = storageBackendRepo
                .findByTenantIdAndEnvironmentAndBackend(tenantId, env, backend)
                .orElse(null);
        if (sb == null) return null;

        String pathPipelineSlug = com.pulse.common.text.Slugify.slugify(pipelineSlug);
        String pathTableSlug = com.pulse.common.text.Slugify.slugify(tableSlug);
        TableLocation location = pathConventionService.tableLocation(
                sb, layer, format, domainSlug, sorSlug, pathPipelineSlug, pathTableSlug);
        if (location instanceof TableLocation.ObjectStorePath objectStorePath) {
            return objectStorePath.uri();
        }
        return null;
    }

    private String resolveLakeLayerRoot(SubPipelineInstance inst,
                                        String pipelineSlug,
                                        String tenantId,
                                        LakeLayer layer) {
        ConnectorInstance ci = resolveConnectorInstance(inst.getParams());
        if (ci == null) return null;
        SystemOfRecord sor = sorRepo.findById(ci.getSorId()).orElse(null);
        if (sor == null) return null;

        String domainSlug = "default";
        if (sor.getDomainId() != null && !sor.getDomainId().isBlank()) {
            Domain d = domainRepo.findById(sor.getDomainId()).orElse(null);
            if (d != null && d.getSlug() != null && !d.getSlug().isBlank()) {
                domainSlug = d.getSlug();
            }
        }
        String sorSlug = sor.getName() == null ? "unknown"
                : com.pulse.common.text.Slugify.slugify(sor.getName());

        String backend = inst.getStorageBackend();
        if (backend == null || backend.isBlank()) {
            backend = resolveDefaultBackend();
        }
        String env = resolveCodegenEnvironment();
        StorageBackend sb = storageBackendRepo
                .findByTenantIdAndEnvironmentAndBackend(tenantId, env, backend)
                .orElse(null);
        if (sb == null) return null;

        String pathPipelineSlug = com.pulse.common.text.Slugify.slugify(pipelineSlug);
        return pathConventionService.lakeLayerRoot(sb, layer, domainSlug, sorSlug, pathPipelineSlug);
    }

    private String escapePython(String s) {
        return s.replace("'", "\\'").replace("\n", " ");
    }

    private String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "error";
        }
    }

    // ==========================================================================
    // SPEC #6 §(c): inline [[ ]] date-mnemonic lowering (ADR 0024).
    //
    // Every SQL surface may carry inline [[ mnemonic ]] tokens. At codegen we:
    //   (1) validate each mnemonic at config time (loud-fail on a typo, §c.1);
    //   (2) lower per engine — dbt: [[m]] -> {{ var('pulse_<slug>') }} + append
    //       --vars to the dbt invocation (§c.3); PySpark/SourceSQL: substitute the
    //       runtime-resolved date directly (§c.4);
    // so that NO [[ ]] survives any emitted artifact (§c.6 — ForbiddenTokenScanner
    // stays green). The mnemonic value is NEVER resolved at design time; resolution
    // is the Airflow-runtime pulse_dates.resolve_mnemonic helper (§c.5), reused
    // unchanged. Slug rule is 6-G07 (§c.2).
    // ==========================================================================

    /** Matches an inline {@code [[ mnemonic ]]} token; group(1) is the inner text. */
    static final Pattern MNEMONIC_TOKEN = Pattern.compile("\\[\\[\\s*(.*?)\\s*\\]\\]");

    /**
     * §c.1 — config-time validation: extract every {@code [[ … ]]} mnemonic from a
     * SQL surface and loud-fail (IllegalArgumentException) on an unknown/typo token.
     */
    void validateMnemonics(String sqlSurface) {
        if (sqlSurface == null) {
            return;
        }
        Matcher m = MNEMONIC_TOKEN.matcher(sqlSurface);
        while (m.find()) {
            DateMnemonic.validateOrThrow(m.group(1).trim());
        }
    }

    /**
     * §c.2 — deterministic slug (6-G07): lowercase, every non-alphanumeric char to
     * {@code '_'}, prefixed {@code 'pulse_'}. {@code NBDOM(2)} -> {@code pulse_nbdom_2_}.
     */
    static String mnemonicSlug(String mnemonic) {
        String norm = mnemonic.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        return "pulse_" + norm;
    }

    /**
     * Collect the distinct mnemonics on a sub-pipeline instance, keyed by slug
     * (§c.2 dedup: one var per distinct slug). Scans every string value in the
     * instance's params recursively (covers sql-model steps, source_query, and
     * SQL-expression params alike), validating each as it goes (§c.1).
     *
     * @return ordered map {@code slug -> raw mnemonic text}; empty if none.
     */
    private Map<String, String> collectMnemonicSlugs(SubPipelineInstance inst) {
        Map<String, String> slugToMnemonic = new LinkedHashMap<>();
        if (inst == null || inst.getParams() == null) {
            return slugToMnemonic;
        }
        collectMnemonicsFromValue(inst.getParams(), slugToMnemonic);
        return slugToMnemonic;
    }

    @SuppressWarnings("unchecked")
    private void collectMnemonicsFromValue(Object value, Map<String, String> out) {
        if (value instanceof String s) {
            Matcher m = MNEMONIC_TOKEN.matcher(s);
            while (m.find()) {
                String mnemonic = m.group(1).trim();
                DateMnemonic.validateOrThrow(mnemonic); // §c.1 loud-fail on typo
                out.putIfAbsent(mnemonicSlug(mnemonic), mnemonic);
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                collectMnemonicsFromValue(v, out);
            }
        } else if (value instanceof Collection<?> coll) {
            for (Object v : coll) {
                collectMnemonicsFromValue(v, out);
            }
        }
    }

    /**
     * §c.3 step 1 — dbt model rewrite: {@code [[ m ]]} -> {@code {{ var('pulse_<slug>') }}}.
     * Validates each mnemonic as it lowers (§c.1). Returns the SQL unchanged when it
     * carries no mnemonics (ADR 0009 byte-exactness for no-mnemonic models).
     */
    String lowerDbtMnemonics(String modelSql) {
        if (modelSql == null || modelSql.indexOf("[[") < 0) {
            return modelSql;
        }
        Matcher m = MNEMONIC_TOKEN.matcher(modelSql);
        return m.replaceAll(mr -> {
            String mnemonic = mr.group(1).trim();
            DateMnemonic.validateOrThrow(mnemonic);
            return Matcher.quoteReplacement("{{ var('" + mnemonicSlug(mnemonic) + "') }}");
        });
    }

    /**
     * §c.4 — PySpark / SourceSQL string-substitution: {@code [[ m ]]} -> a runtime
     * template that resolves the mnemonic to a date string via the pulse_dates
     * helper (§c.5). The substituted text contains no {@code [[ ]]}. Validates each
     * mnemonic (§c.1).
     */
    String substituteMnemonicsForRuntime(String sql) {
        if (sql == null || sql.indexOf("[[") < 0) {
            return sql;
        }
        Matcher m = MNEMONIC_TOKEN.matcher(sql);
        return m.replaceAll(mr -> {
            String mnemonic = mr.group(1).trim();
            DateMnemonic.validateOrThrow(mnemonic);
            // Airflow-templated runtime resolution; no design-time date value.
            return Matcher.quoteReplacement("{{ pulse_resolve_mnemonic('" + mnemonic + "', ds) }}");
        });
    }

    /**
     * §c.3 step 2 — build the dbt {@code --vars} JSON for a task's deduped mnemonic
     * set. Each {@code pulse_<slug>} maps to a runtime template that resolves at
     * Airflow render time via the {@code pulse_resolve_mnemonic} user-defined macro
     * (§c.5). Returns {@code null} when the task has no mnemonics (so the dbt
     * invocation is emitted unchanged — byte-identical to today, ADR 0009).
     */
    private String buildDbtVarsJson(Map<String, String> slugToMnemonic) {
        if (slugToMnemonic == null || slugToMnemonic.isEmpty()) {
            return null;
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : slugToMnemonic.entrySet()) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            json.append('"').append(e.getKey()).append("\": ")
                    .append("\"{{ pulse_resolve_mnemonic('").append(e.getValue()).append("', ds) }}\"");
        }
        json.append('}');
        return json.toString();
    }

    /**
     * §c.6 — assert no inline {@code [[ ]]} mnemonic token survives into any emitted
     * artifact. After lowering, every mnemonic is either a dbt {@code {{ var(...) }}}
     * or a runtime-substituted value; a residual {@code [[ }}/{@code ]]} is a codegen
     * bug. Loud-fail with the offending artifact path(s) (ADR 0011 — never ship it).
     */
    private void assertNoMnemonicResidue(List<GeneratedArtifact> artifacts) {
        List<String> offenders = new ArrayList<>();
        for (GeneratedArtifact a : artifacts) {
            String content = a.getContent();
            if (content != null && MNEMONIC_TOKEN.matcher(content).find()) {
                offenders.add(a.getFilePath());
            }
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException(
                    "SPEC #6 §c.6 violation: unresolved [[ ]] date-mnemonic token(s) survived "
                    + "into emitted artifact(s): " + offenders
                    + " — every mnemonic must be lowered (dbt {{ var(...) }}) or substituted.");
        }
    }

    /** Add an artifact only if no artifact with the same file path is already present. */
    private void addArtifactIfAbsent(List<GeneratedArtifact> artifacts, GeneratedArtifact candidate) {
        for (GeneratedArtifact a : artifacts) {
            if (a.getFilePath() != null && a.getFilePath().equals(candidate.getFilePath())) {
                return;
            }
        }
        artifacts.add(candidate);
    }

    /** Whether any instance carries an inline {@code [[ … ]]} mnemonic (drives the macro emit). */
    private boolean anyInstanceUsesMnemonics(List<SubPipelineInstance> instances) {
        for (var inst : instances) {
            if (!collectMnemonicSlugs(inst).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
