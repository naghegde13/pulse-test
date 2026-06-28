package com.pulse.deploy.adapter.dpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.adapter.AdapterExecution;
import com.pulse.deploy.adapter.AdapterPlan;
import com.pulse.deploy.adapter.CancelRollbackAdapter;
import com.pulse.deploy.adapter.CapabilityCheckResult;
import com.pulse.deploy.adapter.DeploymentTargetAdapter;
import com.pulse.deploy.adapter.LocalMaterializationAdapter;
import com.pulse.deploy.adapter.LogsAdapter;
import com.pulse.deploy.adapter.MaterializationAdapter;
import com.pulse.deploy.adapter.RuntimeLogs;
import com.pulse.deploy.adapter.RuntimeStatus;
import com.pulse.deploy.adapter.SubmitPollAdapter;
import com.pulse.deploy.capability.RuntimeCapabilityMatrix;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.deploy.run.DeploymentRunState;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 7 — {@code DPC_AIRFLOW_OPENSHIFT_SPARK} adapter.
 *
 * <p>Mirrors {@link com.pulse.deploy.adapter.gcp.GcpComposerDataprocAdapter}
 * but speaks the DPC client surface (object store + DPC Airflow REST). Plan
 * envelope: {@code dpc-airflow-openshift-spark-plan.v1}.
 *
 * <p>Phase 7's DPC adapter ships only the structural seams: the client
 * interfaces are intentionally narrow so the platform team can wire production
 * implementations as Airflow/object-store specifics land. Optional smoke
 * validation triggers an Airflow DAG run explicitly through DPC Airflow. DPC
 * Spark submission belongs to generated DAG tasks at pipeline run time, not
 * deployment.
 */
@Component
public class DpcAirflowOpenShiftSparkAdapter implements DeploymentTargetAdapter {

    public static final String TARGET_TYPE = RuntimeCapabilityMatrix.DPC;
    public static final String SCHEMA_VERSION_PLAN = "dpc-airflow-openshift-spark-plan.v1";

    private final LocalMaterializationAdapter local;
    private final DeploymentRunRepository runRepo;
    private final PackageRepository packageRepo;
    private final DeploymentTargetRepository targetRepo;
    private final RuntimeCapabilityMatrix capabilityMatrix;
    private final DpcObjectStoreClient objectStoreClient;
    private final DpcAirflowClient airflowClient;
    private final ObjectMapper canonicalJson = new ObjectMapper();

    private final MaterializationAdapter materialization = new DpcMaterializationLeg();
    private final SubmitPollAdapter submitPoll = new DpcSubmitPollLeg();
    private final LogsAdapter logs = new DpcLogsLeg();
    private final CancelRollbackAdapter cancelRollback = new DpcCancelRollbackLeg();

    public DpcAirflowOpenShiftSparkAdapter(LocalMaterializationAdapter local,
                                           DeploymentRunRepository runRepo,
                                           PackageRepository packageRepo,
                                           DeploymentTargetRepository targetRepo,
                                           RuntimeCapabilityMatrix capabilityMatrix,
                                           DpcObjectStoreClient objectStoreClient,
                                           DpcAirflowClient airflowClient) {
        this.local = local;
        this.runRepo = runRepo;
        this.packageRepo = packageRepo;
        this.targetRepo = targetRepo;
        this.capabilityMatrix = capabilityMatrix;
        this.objectStoreClient = objectStoreClient;
        this.airflowClient = airflowClient;
    }

    @Override public String targetType() { return TARGET_TYPE; }
    @Override public MaterializationAdapter materialization() { return materialization; }
    @Override public SubmitPollAdapter submitPoll() { return submitPoll; }
    @Override public LogsAdapter logs() { return logs; }
    @Override public CancelRollbackAdapter cancelRollback() { return cancelRollback; }

    // ------------------------------------------------------------------

    private final class DpcMaterializationLeg implements MaterializationAdapter {

        @Override
        public AdapterPlan plan(String deploymentRunId) {
            return buildPlan(deploymentRunId, AdapterPlan.VERB_MATERIALIZE);
        }

        @Override
        public AdapterExecution materialize(String deploymentRunId) {
            try {
                LocalMaterializationAdapter.MaterializationResult localResult = local
                        .materialize(deploymentRunId);
                DpcTargetConfig config = resolveTargetConfig(deploymentRunId);
                String prefix = "packages/" + deploymentRunId + "/";
                String objectStoreUri = objectStoreClient.uploadPackagePrefix(
                        new DpcObjectStoreClient.UploadRequest(
                                config.objectStoreEndpoint(),
                                config.objectStoreBucket(),
                                prefix,
                                localResult.sortedPaths(),
                                config.tokenReference()));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("objectStoreUri", objectStoreUri);
                details.put("manifestPath", localResult.manifestPath().toString());
                details.put("packageContentSha256", localResult.packageContentSha256());
                details.put("manifestSha256", localResult.manifestSha256());
                details.put("fileCount", localResult.fileCount());
                return AdapterExecution.success(
                        AdapterPlan.VERB_MATERIALIZE,
                        DeploymentRunState.MATERIALIZED,
                        objectStoreUri,
                        details);
            } catch (RuntimeException e) {
                return AdapterExecution.failure(
                        AdapterPlan.VERB_MATERIALIZE,
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        Map.of());
            }
        }
    }

    private final class DpcSubmitPollLeg implements SubmitPollAdapter {

        @Override
        public AdapterPlan plan(String deploymentRunId) {
            return buildPlan(deploymentRunId, AdapterPlan.VERB_SUBMIT);
        }

        @Override
        public AdapterExecution submit(String deploymentRunId) {
            try {
                DeploymentRun run = loadRun(deploymentRunId);
                DpcTargetConfig config = resolveTargetConfig(deploymentRunId);
                ValidationRequest validation = readValidationRequest(run);
                String objectStoreUri = readMetaString(run, "objectStoreUri");
                if (objectStoreUri == null || objectStoreUri.isBlank()) {
                    objectStoreUri = "s3://" + config.objectStoreBucket()
                            + "/packages/" + deploymentRunId + "/";
                }
                DpcAirflowClient.SyncResult syncResult = airflowClient.syncDags(
                        new DpcAirflowClient.SyncRequest(
                                config.dpcAirflowEndpoint(),
                                objectStoreUri,
                                config.dagFilePaths(),
                                validation.requested(),
                                config.tokenReference(),
                                validation.conf()));
                String syncId = "dpc-airflow-sync-" + deploymentRunId;
                String validationStatus = validation.requested()
                        ? (validation.awaitValidation() ? "RUNNING" : "TRIGGERED")
                        : "NOT_REQUESTED";
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("activationProviderRunId", syncId);
                details.put("validationDagRunId", syncResult.triggeredDagRunId());
                details.put("validationRequested", validation.requested());
                details.put("validationMode", validation.mode());
                details.put("awaitValidation", validation.awaitValidation());
                details.put("validationStatus", validationStatus);
                details.put("dpcAirflowSyncId", syncId);
                details.put("dpcAirflowEndpoint", config.dpcAirflowEndpoint());
                details.put("dpcAirflowDagNames", syncResult.syncedDagNames());
                details.put("objectStoreUri", objectStoreUri);
                details.put("dpcSparkEndpoint", config.dpcSparkEndpoint());
                return AdapterExecution.success(
                        AdapterPlan.VERB_SUBMIT,
                        DeploymentRunState.RUNNING,
                        syncId,
                        details);
            } catch (RuntimeException e) {
                return AdapterExecution.failure(
                        AdapterPlan.VERB_SUBMIT,
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        Map.of());
            }
        }

        @Override
        public RuntimeStatus poll(String deploymentRunId) {
            try {
                DeploymentRun run = loadRun(deploymentRunId);
                DpcTargetConfig config = resolveTargetConfig(deploymentRunId);
                ValidationRequest validation = readValidationRequest(run);
                String dagRunId = readMetaString(run, "validationDagRunId");
                if (validation.requested() && dagRunId != null && !dagRunId.isBlank()) {
                    DpcAirflowClient.DagRunStatus status = airflowClient.pollDagRun(
                            config.dpcAirflowEndpoint(),
                            firstDagName(config.dagFilePaths()),
                            dagRunId,
                            config.tokenReference());
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("activationProviderRunId", readActivationProviderRunId(run, deploymentRunId));
                    details.put("validationDagRunId", dagRunId);
                    details.put("validationRequested", true);
                    details.put("validationMode", validation.mode());
                    details.put("awaitValidation", validation.awaitValidation());
                    details.put("validationStatus", mapValidationStatus(status.effectiveState()));
                    details.put("dpcAirflowEndpoint", config.dpcAirflowEndpoint());
                    return new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION, TARGET_TYPE,
                            dagRunId, status.effectiveState(), status.providerStatus(),
                            null, Instant.now(), status.failureReason(), details);
                }
                String syncId = readActivationProviderRunId(run, deploymentRunId);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("activationProviderRunId", syncId);
                details.put("validationDagRunId", dagRunId);
                details.put("validationRequested", validation.requested());
                details.put("validationMode", validation.mode());
                details.put("awaitValidation", validation.awaitValidation());
                details.put("validationStatus", validation.requested() ? "TRIGGERED" : "NOT_REQUESTED");
                details.put("dpcAirflowEndpoint", config.dpcAirflowEndpoint());
                return new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION, TARGET_TYPE,
                        syncId, DeploymentRunState.SUCCEEDED, "dpc_airflow_dag_synced",
                        null, Instant.now(), null, details);
            } catch (RuntimeException e) {
                return new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION, TARGET_TYPE,
                        null, DeploymentRunState.FAILED, null, null, Instant.now(),
                        e.getClass().getSimpleName() + ": " + e.getMessage(), Map.of());
            }
        }
    }

    private final class DpcLogsLeg implements LogsAdapter {

        @Override
        public RuntimeLogs fetch(String deploymentRunId, int limit) {
            DeploymentRun run = runRepo.findById(deploymentRunId).orElse(null);
            String providerId = run == null ? deploymentRunId : currentProviderRunId(run, deploymentRunId);
            return RuntimeLogs.empty(TARGET_TYPE, providerId, Instant.now());
        }
    }

    private final class DpcCancelRollbackLeg implements CancelRollbackAdapter {

        @Override
        public AdapterExecution cancel(String deploymentRunId, String reason) {
            DeploymentRun run = runRepo.findById(deploymentRunId).orElse(null);
            String providerId = run == null ? null : currentProviderRunId(run, deploymentRunId);
            String dagRunId = run == null ? null : readMetaString(run, "validationDagRunId");
            if (run != null && dagRunId != null && !dagRunId.isBlank()) {
                DpcTargetConfig config = resolveTargetConfig(deploymentRunId);
                airflowClient.cancelDagRun(
                        config.dpcAirflowEndpoint(),
                        firstDagName(config.dagFilePaths()),
                        dagRunId,
                        config.tokenReference());
            }
            return AdapterExecution.success("CANCEL",
                    DeploymentRunState.CANCELLED, providerId,
                    Map.of("reason", reason == null ? "" : reason,
                           "notes", dagRunId == null || dagRunId.isBlank()
                                   ? "Deployment activation publishes and syncs DAGs only; no Airflow validation run is active to cancel."
                                   : "DPC Airflow validation cancellation requested."));
        }

        @Override
        public AdapterExecution rollback(String deploymentId, String toPackageId, String reason) {
            return AdapterExecution.success("ROLLBACK",
                    DeploymentRunState.SUCCEEDED, toPackageId,
                    Map.of("deploymentId", deploymentId,
                           "toPackageId", toPackageId,
                           "reason", reason == null ? "" : reason));
        }
    }

    // ------------------------------------------------------------------

    private AdapterPlan buildPlan(String deploymentRunId, String verb) {
        DeploymentRun run = loadRun(deploymentRunId);
        String packageId = readMetaString(run, "packageId");
        String environment = readMetaString(run, "environment");
        String targetId = readMetaString(run, "targetId");
        Package pkg = packageId == null ? null : packageRepo.findById(packageId).orElse(null);
        DpcTargetConfig config = resolveTargetConfig(deploymentRunId);
        String requestedFormat = readRequestedTableFormat(pkg);
        boolean dpcIcebergSupported = readDpcIcebergSupported(targetId);
        CapabilityCheckResult capability = capabilityMatrix.evaluate(
                new RuntimeCapabilityMatrix.Request(TARGET_TYPE, requestedFormat, dpcIcebergSupported));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("verb", verb);
        details.put("objectStoreEndpoint", config.objectStoreEndpoint());
        details.put("objectStoreBucket", config.objectStoreBucket());
        details.put("objectStorePrefix", "packages/" + deploymentRunId + "/");
        details.put("dpcAirflowEndpoint", config.dpcAirflowEndpoint());
        details.put("dpcSparkEndpoint", config.dpcSparkEndpoint());
        details.put("sparkApp", config.sparkApp());
        details.put("mainPyFile", config.mainPyFile());
        details.put("dagFilePaths", config.dagFilePaths());
        details.put("pythonFiles", config.pythonFiles());
        details.put("jarFiles", config.jarFiles());
        details.put("tokenReference", config.tokenReference());
        details.put("requestedTableFormat", requestedFormat);
        details.put("dpcIcebergSupported", dpcIcebergSupported);
        ValidationRequest validation = readValidationRequest(run);
        details.put("validationRequested", validation.requested());
        details.put("validationMode", validation.mode());
        details.put("awaitValidation", validation.awaitValidation());
        details.put("validationConfHash", readMetaString(run, "validationConfHash"));
        details.put("validationUsesAirflowDagRun", validation.requested());
        details.put("activationTriggerImmediately", false);
        return new AdapterPlan(
                SCHEMA_VERSION_PLAN, TARGET_TYPE, verb,
                deploymentRunId, packageId, run.getTenantId(), environment, targetId,
                capability, details, sha256OfDetails(details));
    }

    record DpcTargetConfig(
            String objectStoreEndpoint,
            String objectStoreBucket,
            String dpcAirflowEndpoint,
            String dpcSparkEndpoint,
            String sparkApp,
            String mainPyFile,
            List<String> dagFilePaths,
            List<String> pythonFiles,
            List<String> jarFiles,
            String tokenReference
    ) {
        @SuppressWarnings("unchecked")
        static DpcTargetConfig from(DeploymentTarget target) {
            Map<String, Object> c = target == null || target.getConfig() == null
                    ? Map.of() : target.getConfig();
            String endpoint = (String) c.getOrDefault("objectStoreEndpoint",
                    "https://dpc-objectstore.example.com");
            String bucket = (String) c.getOrDefault("objectStoreBucket", "pulse-dpc-packages");
            String airflow = (String) c.getOrDefault("dpcAirflowEndpoint",
                    "https://dpc-airflow.example.com");
            String spark = (String) c.getOrDefault("dpcSparkEndpoint",
                    "https://dpc-spark.example.com");
            String sparkApp = (String) c.getOrDefault("sparkApp", "pulse-pipeline");
            String mainPy = (String) c.getOrDefault("mainPyFile", "package/main.py");
            List<String> dags = (List<String>) c.getOrDefault("dagFilePaths",
                    List.of("package/dags/pipeline_dag.py"));
            List<String> pyFiles = (List<String>) c.getOrDefault("pythonFiles", List.of());
            List<String> jars = (List<String>) c.getOrDefault("jarFiles", List.of());
            String token = (String) c.getOrDefault("tokenReference",
                    "gcp-sm://projects/pulse-dpc/secrets/pulse-deploy-sa/versions/latest");
            return new DpcTargetConfig(endpoint, bucket, airflow, spark, sparkApp,
                    mainPy, dags, pyFiles, jars, token);
        }
    }

    private DpcTargetConfig resolveTargetConfig(String deploymentRunId) {
        DeploymentRun run = loadRun(deploymentRunId);
        String targetId = readMetaString(run, "targetId");
        DeploymentTarget target = targetId == null
                ? null
                : targetRepo.findById(targetId).orElse(null);
        return DpcTargetConfig.from(target);
    }

    private boolean readDpcIcebergSupported(String targetId) {
        if (targetId == null) return false;
        DeploymentTarget target = targetRepo.findById(targetId).orElse(null);
        if (target == null || target.getConfig() == null) return false;
        return Boolean.TRUE.equals(target.getConfig().get("dpcIcebergSupported"));
    }

    private DeploymentRun loadRun(String deploymentRunId) {
        return runRepo.findById(deploymentRunId)
                .orElseThrow(() -> new ResourceNotFoundException("DeploymentRun", deploymentRunId));
    }

    private static String readMetaString(DeploymentRun run, String key) {
        Map<String, Object> meta = run.getMetadata();
        if (meta == null) return null;
        Object value = meta.get(key);
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> readMetaMap(DeploymentRun run, String key) {
        Map<String, Object> meta = run.getMetadata();
        if (meta == null) return Map.of();
        Object value = meta.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    copied.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return Collections.unmodifiableMap(copied);
        }
        return Map.of();
    }

    private static boolean readMetaBoolean(DeploymentRun run, String key) {
        Map<String, Object> meta = run.getMetadata();
        if (meta == null) return false;
        Object value = meta.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    private static ValidationRequest readValidationRequest(DeploymentRun run) {
        boolean requested = readMetaBoolean(run, "validationRequested");
        String mode = readMetaString(run, "validationMode");
        boolean awaitValidation = readMetaBoolean(run, "awaitValidation");
        Map<String, Object> conf = readMetaMap(run, "validationConf");
        return new ValidationRequest(requested, mode == null ? "NONE" : mode, awaitValidation, conf);
    }

    private static String readActivationProviderRunId(DeploymentRun run, String deploymentRunId) {
        String activationId = readMetaString(run, "activationProviderRunId");
        if (activationId == null || activationId.isBlank()) {
            activationId = readMetaString(run, "dpcAirflowSyncId");
        }
        if (activationId == null || activationId.isBlank()) {
            activationId = readMetaString(run, "providerRunId");
        }
        return activationId == null || activationId.isBlank()
                ? "dpc-airflow-sync-" + deploymentRunId
                : activationId;
    }

    private static String currentProviderRunId(DeploymentRun run, String deploymentRunId) {
        String validationDagRunId = readMetaString(run, "validationDagRunId");
        if (validationDagRunId != null && !validationDagRunId.isBlank()) {
            return validationDagRunId;
        }
        return readActivationProviderRunId(run, deploymentRunId);
    }

    private static String mapValidationStatus(DeploymentRunState state) {
        if (state == null) return "TRIGGERED";
        return switch (state) {
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case TIMED_OUT -> "TIMED_OUT";
            case RUNNING, SUBMITTING, MATERIALIZING, MATERIALIZED, PREFLIGHT_PASSED -> "RUNNING";
            default -> "TRIGGERED";
        };
    }

    private record ValidationRequest(
            boolean requested,
            String mode,
            boolean awaitValidation,
            Map<String, Object> conf
    ) {}

    private static String firstDagName(List<String> dagFilePaths) {
        if (dagFilePaths == null || dagFilePaths.isEmpty()) {
            return "unknown_dag";
        }
        String path = dagFilePaths.get(0);
        if (path == null || path.isBlank()) {
            return "unknown_dag";
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.endsWith(".py") ? name.substring(0, name.length() - 3) : name;
    }

    @SuppressWarnings("unchecked")
    private static String readRequestedTableFormat(Package pkg) {
        if (pkg == null || pkg.getMetadata() == null) {
            return RuntimeCapabilityMatrix.FORMAT_UNSPECIFIED;
        }
        Object manifest = pkg.getMetadata().get("packageManifest");
        if (manifest instanceof Map<?, ?> m) {
            Object capability = m.get("capabilityProfile");
            if (capability instanceof Map<?, ?> c) {
                Object format = c.get("tableFormat");
                if (format instanceof String s && !s.isBlank()) return s;
            }
        }
        Object flat = pkg.getMetadata().get("requestedTableFormat");
        if (flat instanceof String s && !s.isBlank()) return s;
        return RuntimeCapabilityMatrix.FORMAT_UNSPECIFIED;
    }

    private String sha256OfDetails(Map<String, Object> details) {
        try {
            return sha256Hex(canonicalJson.writeValueAsBytes(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("plan details serialization failed", e);
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
