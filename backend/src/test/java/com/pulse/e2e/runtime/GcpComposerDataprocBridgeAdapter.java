package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.contract.ScenarioDsl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin semantic-proof adapter that binds the existing Composer and Dataproc
 * harness planners into one GCP Composer -> Dataproc Serverless path.
 */
public class GcpComposerDataprocBridgeAdapter {

    public static final String RUNTIME_ADAPTER = ScenarioDsl.RuntimeAdapter.GCP_COMPOSER_DATAPROC_BRIDGE.name();
    public static final String SUBMIT_REQUEST_RELATIVE_PATH = "runtime/dataproc-submit-request.json";
    public static final String RENDERED_DAG_RELATIVE_DIR = "runtime/composer-dataproc";

    private final ObjectMapper objectMapper;
    private final GcpComposerRuntimeBridge composerBridge;
    private final GcpDataprocExecutionPlanner dataprocPlanner;

    public GcpComposerDataprocBridgeAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.composerBridge = new GcpComposerRuntimeBridge(objectMapper);
        this.dataprocPlanner = new GcpDataprocExecutionPlanner(objectMapper);
    }

    public AdapterPlan plan(AdapterRequest request) {
        GcpDataprocExecutionPlanner.ExecutionPlan dataprocPlan = dataprocPlanner.plan(dataprocRequest(request));
        GcpComposerRuntimeBridge.BridgePlan composerPlan = composerBridge.plan(composerRequest(request));
        Map<String, Object> submitRequest = dataprocSubmitRequest(request, dataprocPlan);
        String renderedDag = renderComposerDataprocDag(request, submitRequest);

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("generatedAt", Instant.now().toString());
        notes.put("runtimeAdapter", RUNTIME_ADAPTER);
        notes.put("targetMode", dataprocPlan.targetMode().name());
        notes.put("renderedDagRelativeDir", RENDERED_DAG_RELATIVE_DIR);
        notes.put("submitRequestRelativePath", SUBMIT_REQUEST_RELATIVE_PATH);
        notes.put("assumptions", List.of(
                "Composer imports the rendered DAG from its DAG GCS prefix.",
                "The DAG submits Dataproc Serverless through DataprocCreateBatchOperator.",
                "The harness stages generated PySpark artifacts to GCS before Composer triggers the DAG.",
                "Promotion still requires live Composer, Dataproc, GCS, log, oracle, cleanup, and critique evidence."
        ));

        return new AdapterPlan(
                request.scenarioId(),
                request.generationRunId(),
                RUNTIME_ADAPTER,
                composerPlan,
                dataprocPlan,
                submitRequest,
                renderedDag,
                notes
        );
    }

    public WrittenAdapterPlan writeEvidence(AdapterRequest request, Path evidenceRoot) throws IOException {
        Files.createDirectories(evidenceRoot);

        AdapterPlan plan = plan(request);
        materializeRuntimeInputs(request, plan);
        Path composerEvidence = composerBridge.writeEvidence(composerRequest(request), evidenceRoot);
        Path dataprocEvidence = dataprocPlanner.writeEvidence(dataprocRequest(request), evidenceRoot);

        Path submitRequestPath = evidenceRoot.resolve(SUBMIT_REQUEST_RELATIVE_PATH);
        Files.createDirectories(submitRequestPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(submitRequestPath.toFile(), plan.dataprocSubmitRequest());

        Path renderedDagPath = evidenceRoot.resolve(RENDERED_DAG_RELATIVE_DIR).resolve(request.dagFileName());
        Files.createDirectories(renderedDagPath.getParent());
        Files.writeString(renderedDagPath, plan.renderedDag());

        Path adapterPlanPath = evidenceRoot.resolve("gcp-composer-dataproc-bridge-adapter.json");
        Map<String, Object> adapterPacket = new LinkedHashMap<>();
        adapterPacket.put("scenarioId", plan.scenarioId());
        adapterPacket.put("generationRunId", plan.generationRunId());
        adapterPacket.put("runtimeAdapter", plan.runtimeAdapter());
        adapterPacket.put("composerBridgeEvidence", composerEvidence.getFileName().toString());
        adapterPacket.put("dataprocExecutionEvidence", dataprocEvidence.getFileName().toString());
        adapterPacket.put("dataprocSubmitRequest", SUBMIT_REQUEST_RELATIVE_PATH);
        adapterPacket.put("renderedDag", RENDERED_DAG_RELATIVE_DIR + "/" + request.dagFileName());
        adapterPacket.put("composerDagId", plan.composerPlan().dagId());
        adapterPacket.put("composerDagRunId", plan.composerPlan().dagRunId());
        adapterPacket.put("dataprocBatchId", plan.dataprocPlan().batchId());
        adapterPacket.put("notes", plan.notes());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(adapterPlanPath.toFile(), adapterPacket);

        return new WrittenAdapterPlan(adapterPlanPath, composerEvidence, dataprocEvidence, submitRequestPath, renderedDagPath);
    }

    private void materializeRuntimeInputs(AdapterRequest request, AdapterPlan plan) throws IOException {
        Path namespacedRoot = request.runtimeRepoRoot().resolve(request.compileNamespace()).normalize();
        Path runtimeDagPath = namespacedRoot.resolve("dags").resolve(request.dagFileName()).normalize();
        Files.createDirectories(runtimeDagPath.getParent());
        Files.writeString(runtimeDagPath, plan.renderedDag());

        Path runtimeSubmitRequestPath = namespacedRoot.resolve("runtime")
                .resolve("dataproc-submit-request.json")
                .normalize();
        Files.createDirectories(runtimeSubmitRequestPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(runtimeSubmitRequestPath.toFile(), plan.dataprocSubmitRequest());
    }

    private GcpComposerRuntimeBridge.BridgeRequest composerRequest(AdapterRequest request) {
        return new GcpComposerRuntimeBridge.BridgeRequest(
                request.scenarioId(),
                request.generationRunId(),
                request.projectId(),
                request.composerLocation(),
                request.composerEnvironment(),
                request.dagGcsPrefix(),
                request.compileNamespace(),
                request.dagId(),
                request.dagFileName(),
                request.dagRunId(),
                request.logicalDate(),
                request.runtimeRepoRoot()
        );
    }

    private GcpDataprocExecutionPlanner.ExecutionRequest dataprocRequest(AdapterRequest request) {
        return new GcpDataprocExecutionPlanner.ExecutionRequest(
                request.scenarioId(),
                request.generationRunId(),
                request.projectId(),
                request.dataprocRegion(),
                GcpDataprocExecutionPlanner.TargetMode.SERVERLESS_BATCH,
                null,
                request.batchId(),
                request.dataprocServiceAccount(),
                request.stagingBucket(),
                request.runtimeOutputPrefix(),
                request.compileNamespace(),
                request.mainPythonRelativePath(),
                request.additionalPyFilesRelativePaths(),
                request.jobArgs(),
                request.runtimeRepoRoot()
        );
    }

    private Map<String, Object> dataprocSubmitRequest(
            AdapterRequest request,
            GcpDataprocExecutionPlanner.ExecutionPlan dataprocPlan
    ) {
        Map<String, Object> pysparkBatch = new LinkedHashMap<>();
        pysparkBatch.put("main_python_file_uri", dataprocPlan.stagedMainPython());
        Object additionalPyFiles = dataprocPlan.notes().get("additionalPyFiles");
        if (additionalPyFiles instanceof List<?> files && !files.isEmpty()) {
            pysparkBatch.put("python_file_uris", files);
        }
        pysparkBatch.put("args", request.jobArgs());

        Map<String, Object> executionConfig = new LinkedHashMap<>();
        executionConfig.put("service_account", request.dataprocServiceAccount());
        executionConfig.put("staging_bucket", stripGsScheme(request.stagingBucket()));

        Map<String, Object> environmentConfig = new LinkedHashMap<>();
        environmentConfig.put("execution_config", executionConfig);

        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("scenario", label(request.scenarioId()));
        labels.put("generation-run", label(request.generationRunId()));

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("pyspark_batch", pysparkBatch);
        batch.put("environment_config", environmentConfig);
        batch.put("labels", labels);

        Map<String, Object> submitRequest = new LinkedHashMap<>();
        submitRequest.put("runtimeAdapter", RUNTIME_ADAPTER);
        submitRequest.put("scenarioId", request.scenarioId());
        submitRequest.put("generationRunId", request.generationRunId());
        submitRequest.put("projectId", request.projectId());
        submitRequest.put("region", request.dataprocRegion());
        submitRequest.put("batchId", request.batchId());
        submitRequest.put("batch", batch);
        submitRequest.put("sourcePlanCommand", dataprocPlan.submitCommand());
        submitRequest.put("authMode", request.authMode());
        submitRequest.put("controlPlaneMode", request.controlPlaneMode());
        submitRequest.put("callerPrincipal", request.callerPrincipal());
        return submitRequest;
    }

    private String renderComposerDataprocDag(AdapterRequest request, Map<String, Object> submitRequest) {
        String batchLiteral = pythonLiteral(submitRequest.get("batch"));
        return """
                from __future__ import annotations

                import pendulum
                from airflow import DAG
                from airflow.operators.empty import EmptyOperator
                from airflow.providers.google.cloud.operators.dataproc import DataprocCreateBatchOperator

                DAG_ID = "%s"
                BATCH_ID = "%s"
                PROJECT_ID = "%s"
                REGION = "%s"

                with DAG(
                    dag_id=DAG_ID,
                    start_date=pendulum.datetime(2026, 1, 1, tz="UTC"),
                    schedule=None,
                    catchup=False,
                    max_active_runs=1,
                    tags=["pulse", "semantic-proof", "gcp-composer-dataproc"],
                ) as dag:
                    submit_dataproc_batch = DataprocCreateBatchOperator(
                        task_id="submit_dataproc_batch",
                        project_id=PROJECT_ID,
                        region=REGION,
                        batch_id=BATCH_ID,
                        batch=%s,
                    )

                    done = EmptyOperator(task_id="done")

                    submit_dataproc_batch >> done
                """.formatted(
                escapePythonString(request.dagId()),
                escapePythonString(request.batchId()),
                escapePythonString(request.projectId()),
                escapePythonString(request.dataprocRegion()),
                batchLiteral
        );
    }

    private String pythonLiteral(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(value)
                    .replace("\n", "\n                        ");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render Python literal", e);
        }
    }

    private static String stripGsScheme(String bucket) {
        String trimmed = bucket == null ? "" : bucket.trim();
        if (trimmed.startsWith("gs://")) {
            trimmed = trimmed.substring("gs://".length());
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String label(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
    }

    private static String escapePythonString(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record AdapterRequest(
            String scenarioId,
            String generationRunId,
            String projectId,
            String composerLocation,
            String composerEnvironment,
            String dagGcsPrefix,
            String dataprocRegion,
            String dataprocServiceAccount,
            String stagingBucket,
            String runtimeOutputPrefix,
            String compileNamespace,
            String dagId,
            String dagFileName,
            String dagRunId,
            String logicalDate,
            String batchId,
            String mainPythonRelativePath,
            List<String> additionalPyFilesRelativePaths,
            List<String> jobArgs,
            String authMode,
            String controlPlaneMode,
            String callerPrincipal,
            Path runtimeRepoRoot
    ) {
        public AdapterRequest {
            additionalPyFilesRelativePaths = additionalPyFilesRelativePaths == null ? List.of() : List.copyOf(additionalPyFilesRelativePaths);
            jobArgs = jobArgs == null ? List.of() : List.copyOf(jobArgs);
            authMode = authMode == null || authMode.isBlank() ? "GCLOUD_SUBPROCESS" : authMode;
            controlPlaneMode = controlPlaneMode == null || controlPlaneMode.isBlank() ? "GCLOUD_COMPOSER_RUN" : controlPlaneMode;
            callerPrincipal = callerPrincipal == null || callerPrincipal.isBlank() ? "UNKNOWN" : callerPrincipal;
        }
    }

    public record AdapterPlan(
            String scenarioId,
            String generationRunId,
            String runtimeAdapter,
            GcpComposerRuntimeBridge.BridgePlan composerPlan,
            GcpDataprocExecutionPlanner.ExecutionPlan dataprocPlan,
            Map<String, Object> dataprocSubmitRequest,
            String renderedDag,
            Map<String, Object> notes
    ) {
    }

    public record WrittenAdapterPlan(
            Path adapterPlan,
            Path composerEvidence,
            Path dataprocEvidence,
            Path dataprocSubmitRequest,
            Path renderedDag
    ) {
    }
}
