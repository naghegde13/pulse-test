package com.pulse.deploy.adapter.gcp;

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
 * Phase 7 — {@code GCP_COMPOSER_DATAPROC} adapter.
 *
 * <p>Plan-mode flow:
 * <ol>
 *   <li>{@link #materialization()} reuses {@link LocalMaterializationAdapter}
 *       to write the package to disk first; then computes the
 *       {@code gs://<bucket>/<prefix>} URI the production wiring would
 *       upload to.</li>
 *   <li>{@link #submitPoll()} plans the Composer DAG sync/activation. The plan
 *       envelope ({@code gcp-composer-dataproc-plan.v1}) includes resolved DAG
 *       file paths, runtime entrypoint references, the capability matrix result,
 *       and only secret REFERENCES — never values.</li>
 * </ol>
 *
 * <p>Execution-mode deploy flow publishes package bytes and syncs Airflow only.
 * Optional smoke validation triggers an Airflow DAG run explicitly through
 * Composer. Dataproc submission belongs to generated DAG tasks at pipeline run
 * time, not to this deploy adapter.
 */
@Component
public class GcpComposerDataprocAdapter implements DeploymentTargetAdapter {

    public static final String TARGET_TYPE = RuntimeCapabilityMatrix.GCP;
    public static final String SCHEMA_VERSION_PLAN = "gcp-composer-dataproc-plan.v1";

    private final LocalMaterializationAdapter local;
    private final DeploymentRunRepository runRepo;
    private final PackageRepository packageRepo;
    private final DeploymentTargetRepository targetRepo;
    private final RuntimeCapabilityMatrix capabilityMatrix;
    private final GcsPackageDeliveryClient gcsClient;
    private final ComposerDagSyncClient composerClient;
    private final ObjectMapper canonicalJson = new ObjectMapper();

    private final MaterializationAdapter materialization = new GcpMaterializationLeg();
    private final SubmitPollAdapter submitPoll = new GcpSubmitPollLeg();
    private final LogsAdapter logs = new GcpLogsLeg();
    private final CancelRollbackAdapter cancelRollback = new GcpCancelRollbackLeg();

    public GcpComposerDataprocAdapter(LocalMaterializationAdapter local,
                                      DeploymentRunRepository runRepo,
                                      PackageRepository packageRepo,
                                      DeploymentTargetRepository targetRepo,
                                      RuntimeCapabilityMatrix capabilityMatrix,
                                      GcsPackageDeliveryClient gcsClient,
                                      ComposerDagSyncClient composerClient) {
        this.local = local;
        this.runRepo = runRepo;
        this.packageRepo = packageRepo;
        this.targetRepo = targetRepo;
        this.capabilityMatrix = capabilityMatrix;
        this.gcsClient = gcsClient;
        this.composerClient = composerClient;
    }

    @Override public String targetType() { return TARGET_TYPE; }
    @Override public MaterializationAdapter materialization() { return materialization; }
    @Override public SubmitPollAdapter submitPoll() { return submitPoll; }
    @Override public LogsAdapter logs() { return logs; }
    @Override public CancelRollbackAdapter cancelRollback() { return cancelRollback; }

    // ------------------------------------------------------------------
    //  Materialization — reuse local materialization, then upload to GCS
    // ------------------------------------------------------------------

    private final class GcpMaterializationLeg implements MaterializationAdapter {

        @Override
        public AdapterPlan plan(String deploymentRunId) {
            return buildPlan(deploymentRunId, AdapterPlan.VERB_MATERIALIZE);
        }

        @Override
        public AdapterExecution materialize(String deploymentRunId) {
            try {
                LocalMaterializationAdapter.MaterializationResult local = GcpComposerDataprocAdapter.this.local
                        .materialize(deploymentRunId);
                GcpTargetConfig config = resolveTargetConfig(deploymentRunId);
                String prefix = "packages/" + deploymentRunId + "/";
                String gcsUri = gcsClient.uploadPackagePrefix(
                        new GcsPackageDeliveryClient.UploadRequest(
                                config.gcsBucket(),
                                prefix,
                                local.sortedPaths(),
                                config.tokenReference()));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("gcsUri", gcsUri);
                details.put("manifestPath", local.manifestPath().toString());
                details.put("packageContentSha256", local.packageContentSha256());
                details.put("manifestSha256", local.manifestSha256());
                details.put("fileCount", local.fileCount());
                return AdapterExecution.success(
                        AdapterPlan.VERB_MATERIALIZE,
                        DeploymentRunState.MATERIALIZED,
                        gcsUri,
                        details);
            } catch (RuntimeException e) {
                return AdapterExecution.failure(
                        AdapterPlan.VERB_MATERIALIZE,
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        Map.of());
            }
        }
    }

    // ------------------------------------------------------------------
    //  SubmitPoll — Composer DAG sync/activation only
    // ------------------------------------------------------------------

    private final class GcpSubmitPollLeg implements SubmitPollAdapter {

        @Override
        public AdapterPlan plan(String deploymentRunId) {
            return buildPlan(deploymentRunId, AdapterPlan.VERB_SUBMIT);
        }

        @Override
        public AdapterExecution submit(String deploymentRunId) {
            try {
                DeploymentRun run = loadRun(deploymentRunId);
                GcpTargetConfig config = resolveTargetConfig(deploymentRunId);
                ValidationRequest validation = readValidationRequest(run);
                String gcsUri = readMetaString(run, "gcsUri");
                if (gcsUri == null || gcsUri.isBlank()) {
                    gcsUri = "gs://" + config.gcsBucket() + "/packages/" + deploymentRunId + "/";
                }
                ComposerDagSyncClient.SyncResult syncResult = composerClient.syncDags(
                        new ComposerDagSyncClient.SyncRequest(
                                config.composerEnvironment(),
                                gcsUri,
                                config.dagFilePaths(),
                                validation.requested(),
                                config.tokenReference(),
                                validation.conf()));
                String syncId = "composer-sync-" + deploymentRunId;
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
                details.put("composerSyncId", syncId);
                details.put("composerEnvironment", config.composerEnvironment());
                details.put("composerDagNames", syncResult.syncedDagNames());
                details.put("gcsUri", gcsUri);
                details.put("dataprocProject", config.gcpProject());
                details.put("dataprocRegion", config.dataprocRegion());
                details.put("dataprocCluster", config.dataprocCluster());
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
                ValidationRequest validation = readValidationRequest(run);
                String validationDagRunId = readMetaString(run, "validationDagRunId");
                GcpTargetConfig config = resolveTargetConfig(deploymentRunId);
                if (validation.requested() && validationDagRunId != null && !validationDagRunId.isBlank()) {
                    ComposerDagSyncClient.DagRunStatus status = composerClient.pollDagRun(
                            config.composerEnvironment(),
                            firstDagName(config.dagFilePaths()),
                            validationDagRunId,
                            config.tokenReference());
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("activationProviderRunId", readActivationProviderRunId(run, deploymentRunId));
                    details.put("validationDagRunId", validationDagRunId);
                    details.put("validationRequested", true);
                    details.put("validationMode", validation.mode());
                    details.put("awaitValidation", validation.awaitValidation());
                    details.put("validationStatus", mapValidationStatus(status.effectiveState()));
                    details.put("composerEnvironment", config.composerEnvironment());
                    return new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION, TARGET_TYPE,
                            validationDagRunId, status.effectiveState(), status.providerStatus(),
                            null, Instant.now(), status.failureReason(), details);
                }
                String syncId = readActivationProviderRunId(run, deploymentRunId);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("activationProviderRunId", syncId);
                details.put("validationDagRunId", validationDagRunId);
                details.put("validationRequested", validation.requested());
                details.put("validationMode", validation.mode());
                details.put("awaitValidation", validation.awaitValidation());
                details.put("validationStatus", validation.requested() ? "TRIGGERED" : "NOT_REQUESTED");
                details.put("composerEnvironment", config.composerEnvironment());
                return new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION, TARGET_TYPE,
                        syncId, DeploymentRunState.SUCCEEDED, "composer_dag_synced",
                        null, Instant.now(), null, details);
            } catch (RuntimeException e) {
                return new RuntimeStatus(RuntimeStatus.SCHEMA_VERSION, TARGET_TYPE,
                        null, DeploymentRunState.FAILED, null, null, Instant.now(),
                        e.getClass().getSimpleName() + ": " + e.getMessage(), Map.of());
            }
        }
    }

    // ------------------------------------------------------------------
    //  Logs — empty for now (validation/runtime task logs stay in Airflow)
    // ------------------------------------------------------------------

    private final class GcpLogsLeg implements LogsAdapter {

        @Override
        public RuntimeLogs fetch(String deploymentRunId, int limit) {
            // Phase 7 ships the envelope only; runtime logs are pulled from
            // Airflow task evidence once validation/runtime observation lands.
            DeploymentRun run = runRepo.findById(deploymentRunId).orElse(null);
            String providerId = run == null ? deploymentRunId : currentProviderRunId(run, deploymentRunId);
            return RuntimeLogs.empty(TARGET_TYPE, providerId, Instant.now());
        }
    }

    // ------------------------------------------------------------------
    //  Cancel/rollback — request Composer cancellation when validation is active
    // ------------------------------------------------------------------

    private final class GcpCancelRollbackLeg implements CancelRollbackAdapter {

        @Override
        public AdapterExecution cancel(String deploymentRunId, String reason) {
            DeploymentRun run = runRepo.findById(deploymentRunId).orElse(null);
            String providerId = run == null ? null : currentProviderRunId(run, deploymentRunId);
            String dagRunId = run == null ? null : readMetaString(run, "validationDagRunId");
            if (run != null && dagRunId != null && !dagRunId.isBlank()) {
                GcpTargetConfig config = resolveTargetConfig(deploymentRunId);
                composerClient.cancelDagRun(
                        config.composerEnvironment(),
                        firstDagName(config.dagFilePaths()),
                        dagRunId,
                        config.tokenReference());
            }
            return AdapterExecution.success("CANCEL",
                    DeploymentRunState.CANCELLED,
                    providerId,
                    Map.of("reason", reason == null ? "" : reason,
                           "notes", dagRunId == null || dagRunId.isBlank()
                                   ? "Deployment activation publishes and syncs DAGs only; no Airflow validation run is active to cancel."
                                   : "Composer validation cancellation requested."));
        }

        @Override
        public AdapterExecution rollback(String deploymentId, String toPackageId, String reason) {
            return AdapterExecution.success("ROLLBACK",
                    DeploymentRunState.SUCCEEDED, toPackageId,
                    Map.of("deploymentId", deploymentId,
                           "toPackageId", toPackageId,
                           "reason", reason == null ? "" : reason,
                           "notes", "Phase 7 stub: rollback runs the previous package's DAG by "
                                   + "creating a new run; this hint lets the runtime reconcile."));
        }
    }

    // ------------------------------------------------------------------
    //  Plan envelope construction
    // ------------------------------------------------------------------

    private AdapterPlan buildPlan(String deploymentRunId, String verb) {
        DeploymentRun run = loadRun(deploymentRunId);
        String packageId = readMetaString(run, "packageId");
        String environment = readMetaString(run, "environment");
        String targetId = readMetaString(run, "targetId");
        Package pkg = packageId == null ? null : packageRepo.findById(packageId).orElse(null);
        GcpTargetConfig config = resolveTargetConfig(deploymentRunId);
        String requestedFormat = readRequestedTableFormat(pkg);
        CapabilityCheckResult capability = capabilityMatrix.evaluate(
                new RuntimeCapabilityMatrix.Request(TARGET_TYPE, requestedFormat, false));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("verb", verb);
        details.put("gcpProject", config.gcpProject());
        details.put("gcsBucket", config.gcsBucket());
        details.put("gcsPrefix", "packages/" + deploymentRunId + "/");
        details.put("composerEnvironment", config.composerEnvironment());
        details.put("dataprocRegion", config.dataprocRegion());
        details.put("dataprocCluster", config.dataprocCluster());
        details.put("mainPyFile", config.mainPyFile());
        details.put("dagFilePaths", config.dagFilePaths());
        details.put("pythonFiles", config.pythonFiles());
        details.put("jarFiles", config.jarFiles());
        // Secrets — REFERENCES only (gcp-sm:// URI), never values.
        details.put("tokenReference", config.tokenReference());
        details.put("requestedTableFormat", requestedFormat);
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

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Resolved + validated GCP target config. Pulled from
     * {@link DeploymentTarget#getConfig()} with deterministic defaults
     * so a target row that hasn't yet had every key populated still
     * produces a stable plan envelope (fixture-friendly).
     */
    record GcpTargetConfig(
            String gcpProject,
            String gcsBucket,
            String composerEnvironment,
            String dataprocRegion,
            String dataprocCluster,
            String mainPyFile,
            List<String> dagFilePaths,
            List<String> pythonFiles,
            List<String> jarFiles,
            String tokenReference
    ) {
        @SuppressWarnings("unchecked")
        static GcpTargetConfig from(DeploymentTarget target) {
            Map<String, Object> c = target == null || target.getConfig() == null
                    ? Map.of() : target.getConfig();
            String project = (String) c.getOrDefault("gcpProject", "pulse-dev");
            String bucket = (String) c.getOrDefault("gcsBucket", project + "-pulse-packages");
            String composerEnv = (String) c.getOrDefault("composerEnvironment",
                    "projects/" + project + "/locations/us-central1/environments/pulse-composer");
            String region = (String) c.getOrDefault("dataprocRegion", "us-central1");
            String cluster = (String) c.getOrDefault("dataprocCluster", "pulse-dataproc");
            String mainPy = (String) c.getOrDefault("mainPyFile", "package/main.py");
            List<String> dagFilePaths = (List<String>) c.getOrDefault("dagFilePaths",
                    List.of("package/dags/pipeline_dag.py"));
            List<String> pythonFiles = (List<String>) c.getOrDefault("pythonFiles", List.of());
            List<String> jarFiles = (List<String>) c.getOrDefault("jarFiles", List.of());
            String tokenRef = (String) c.getOrDefault("tokenReference",
                    "gcp-sm://projects/" + project + "/secrets/pulse-deploy-sa/versions/latest");
            return new GcpTargetConfig(project, bucket, composerEnv, region, cluster,
                    mainPy, dagFilePaths, pythonFiles, jarFiles, tokenRef);
        }
    }

    private GcpTargetConfig resolveTargetConfig(String deploymentRunId) {
        DeploymentRun run = loadRun(deploymentRunId);
        String targetId = readMetaString(run, "targetId");
        DeploymentTarget target = targetId == null
                ? null
                : targetRepo.findById(targetId).orElse(null);
        return GcpTargetConfig.from(target);
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
            activationId = readMetaString(run, "composerSyncId");
        }
        if (activationId == null || activationId.isBlank()) {
            activationId = readMetaString(run, "providerRunId");
        }
        return activationId == null || activationId.isBlank()
                ? "composer-sync-" + deploymentRunId
                : activationId;
    }

    private static String currentProviderRunId(DeploymentRun run, String deploymentRunId) {
        String validationDagRunId = readMetaString(run, "validationDagRunId");
        if (validationDagRunId != null && !validationDagRunId.isBlank()) {
            return validationDagRunId;
        }
        return readActivationProviderRunId(run, deploymentRunId);
    }

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
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
