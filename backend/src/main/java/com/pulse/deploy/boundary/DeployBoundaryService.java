package com.pulse.deploy.boundary;

import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.repository.TenantGcpConfigRepository;
import com.pulse.deploy.boundary.DeployBoundaryReadback.*;
import com.pulse.deploy.capability.RuntimeCapabilityMatrix;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PKT-0004 — assembles the deploy boundary readback for a tenant +
 * deployment target combination. Evaluates topology, IAM, credential,
 * cost, and approval blockers for Composer, Dataproc, BigQuery,
 * Secret Manager, GCS, and evidence/log destinations.
 *
 * <p>When PKT-0025 runtime topology is configured for the tenant,
 * boundary fields are derived from the topology record (which carries
 * per-resource project/region/SA/bucket detail). When topology is absent,
 * the service falls back to TenantGcpConfig + deployment target config.
 *
 * <p>The readback is boundary evidence only. It explicitly cannot
 * satisfy static package proof, preflight-only proof, local synthetic
 * proof, or runtime output proof. Live GCP deploy paths are shown as
 * {@link BoundaryStatus#OPERATOR_BLOCKED} unless all topology/IAM/
 * credential gates are satisfied.
 */
@Service
public class DeployBoundaryService {

    private final DeploymentTargetRepository targetRepo;
    private final TenantGcpConfigRepository gcpConfigRepo;
    private final TenantGcpRuntimeTopologyRepository topologyRepo;
    private final PackageRepository packageRepo;
    private final RuntimeAuthorityService runtimeAuthority;

    public DeployBoundaryService(DeploymentTargetRepository targetRepo,
                                 TenantGcpConfigRepository gcpConfigRepo,
                                 TenantGcpRuntimeTopologyRepository topologyRepo,
                                 PackageRepository packageRepo,
                                 RuntimeAuthorityService runtimeAuthority) {
        this.targetRepo = targetRepo;
        this.gcpConfigRepo = gcpConfigRepo;
        this.topologyRepo = topologyRepo;
        this.packageRepo = packageRepo;
        this.runtimeAuthority = runtimeAuthority;
    }

    /**
     * Assemble the deploy boundary readback for a specific target.
     */
    public DeployBoundaryReadback assembleForTarget(String tenantId, String targetId, String packageId) {
        DeploymentTarget target = targetRepo.findById(targetId).orElse(null);
        if (target == null) {
            return buildMissingTargetReadback(tenantId, targetId);
        }

        Optional<TenantGcpConfig> gcpConfigOpt = gcpConfigRepo.findByTenantId(tenantId);
        Optional<TenantGcpRuntimeTopology> topologyOpt = topologyRepo.findByTenantId(tenantId);
        String targetType = target.getTargetType();
        String environment = target.getEnvironment();
        Map<String, Object> config = target.getConfig() == null ? Map.of() : target.getConfig();

        List<String> allBlockers = new ArrayList<>();
        List<String> operatorActions = new ArrayList<>();

        // Check runtime authority for target type — persona restrictions are
        // hard blockers because an operator cannot change the active persona
        boolean targetTypeAllowed = runtimeAuthority.isTargetTypeAllowed(targetType);
        if (!targetTypeAllowed) {
            allBlockers.add("HARD_BLOCK: RUNTIME_AUTHORITY: target type '" + targetType
                    + "' is not allowed for persona " + runtimeAuthority.getActivePersona());
        }

        // Resolve base GCP project/region from config or topology
        String gcpProject;
        String gcpRegion;
        if (topologyOpt.isPresent()) {
            TenantGcpRuntimeTopology topo = topologyOpt.get();
            gcpProject = firstNonBlank(topo.getComposerProjectId(),
                    gcpConfigOpt.map(TenantGcpConfig::getControlPlaneProjectId).orElse(null));
            gcpRegion = firstNonBlank(topo.getComposerRegion(),
                    gcpConfigOpt.map(TenantGcpConfig::getGcpRegion).orElse("us-central1"));
        } else if (gcpConfigOpt.isPresent()) {
            TenantGcpConfig gcpConfig = gcpConfigOpt.get();
            gcpProject = gcpConfig.getControlPlaneProjectId();
            gcpRegion = gcpConfig.getGcpRegion();
        } else {
            gcpProject = configStr(config, "gcpProject", null);
            gcpRegion = configStr(config, "dataprocRegion", "us-central1");
            if (isGcpTarget(targetType)) {
                allBlockers.add("GCP_CONFIG: no tenant GCP configuration found; "
                        + "configure via PUT /api/v1/tenants/{tenantId}/gcp-config "
                        + "or PUT /api/v1/tenants/{tenantId}/gcp-runtime-topology");
                operatorActions.add("Configure tenant GCP project and region");
            }
        }

        // Build per-resource boundaries (topology-aware)
        TenantGcpRuntimeTopology topo = topologyOpt.orElse(null);
        ComposerBoundary composer = buildComposerBoundary(
                targetType, gcpProject, gcpRegion, config, topo, allBlockers, operatorActions);
        DataprocBoundary dataproc = buildDataprocBoundary(
                targetType, gcpProject, gcpRegion, config, topo, allBlockers, operatorActions);
        BigQueryBoundary bigquery = buildBigQueryBoundary(
                targetType, gcpProject, config, topo, allBlockers, operatorActions);
        SecretManagerBoundary secretManager = buildSecretManagerBoundary(
                targetType, gcpProject, config, topo, allBlockers, operatorActions);
        EvidenceLogBoundary evidenceLog = buildEvidenceLogBoundary(
                targetType, gcpProject, config, topo, allBlockers, operatorActions);
        GeneratedArtifactReadiness artifactReadiness = buildArtifactReadiness(
                packageId, allBlockers);

        // Determine overall boundary status
        BoundaryStatus status;
        if (allBlockers.isEmpty()) {
            status = BoundaryStatus.LIVE;
        } else if (allBlockers.stream().anyMatch(b -> b.startsWith("HARD_BLOCK:"))) {
            status = BoundaryStatus.BLOCKED;
        } else {
            status = BoundaryStatus.OPERATOR_BLOCKED;
        }

        return new DeployBoundaryReadback(
                DeployBoundaryReadback.SCHEMA_VERSION,
                tenantId,
                targetId,
                targetType,
                environment,
                status,
                Instant.now(),
                "DeployBoundaryService",
                composer,
                dataproc,
                bigquery,
                secretManager,
                evidenceLog,
                artifactReadiness,
                allBlockers,
                operatorActions,
                DeployBoundaryReadback.EVIDENCE_DISCLAIMER
        );
    }

    // ── Composer boundary ────────────────────────────────────

    private ComposerBoundary buildComposerBoundary(String targetType, String gcpProject,
                                                    String gcpRegion, Map<String, Object> config,
                                                    TenantGcpRuntimeTopology topo,
                                                    List<String> allBlockers, List<String> operatorActions) {
        if (!isGcpTarget(targetType)) {
            return null;
        }

        List<String> blockers = new ArrayList<>();

        // Prefer PKT-0025 topology fields, fall back to target config, then defaults
        String composerEnv = topo != null ? firstNonBlank(topo.getComposerEnvironment(), null) : null;
        if (composerEnv == null) {
            composerEnv = configStr(config, "composerEnvironment",
                    gcpProject == null ? null
                            : "projects/" + gcpProject + "/locations/" + gcpRegion + "/environments/pulse-composer");
        }

        String composerRegion = topo != null ? firstNonBlank(topo.getComposerRegion(), gcpRegion) : gcpRegion;

        String bucket = topo != null ? firstNonBlank(topo.getComposerEnvironmentBucket(), null) : null;
        if (bucket == null) {
            bucket = configStr(config, "gcsBucket",
                    gcpProject == null ? null : gcpProject + "-pulse-packages");
        }

        @SuppressWarnings("unchecked")
        List<String> dagPaths = config.get("dagFilePaths") instanceof List<?> list
                ? (List<String>) list
                : List.of("package/dags/pipeline_dag.py");

        String deployIdentity = topo != null ? firstNonBlank(topo.getControlPlaneSaEmail(), null) : null;
        if (deployIdentity == null) {
            deployIdentity = configStr(config, "deployServiceAccount",
                    gcpProject == null ? null : "pulse-deploy@" + gcpProject + ".iam.gserviceaccount.com");
        }

        if (gcpProject == null) {
            blockers.add("TOPOLOGY: Composer project not configured");
        }
        if (composerEnv == null) {
            blockers.add("TOPOLOGY: Composer environment not configured");
        }
        if (bucket == null) {
            blockers.add("TOPOLOGY: Composer package delivery bucket not configured");
        }
        if (deployIdentity == null) {
            blockers.add("IAM: control-plane service account not configured");
            operatorActions.add("Configure control-plane service account with Composer DAG sync permissions");
        }

        allBlockers.addAll(blockers);
        boolean ready = blockers.isEmpty();

        return new ComposerBoundary(
                gcpProject, composerRegion, composerEnv, dagPaths, bucket,
                deployIdentity, Responsibility.OPERATOR_PROVISIONS_PULSE_VALIDATES,
                blockers, ready);
    }

    // ── Dataproc boundary ────────────────────────────────────

    private DataprocBoundary buildDataprocBoundary(String targetType, String gcpProject,
                                                    String gcpRegion, Map<String, Object> config,
                                                    TenantGcpRuntimeTopology topo,
                                                    List<String> allBlockers, List<String> operatorActions) {
        if (!isGcpTarget(targetType)) {
            return null;
        }

        List<String> blockers = new ArrayList<>();

        String region = topo != null ? firstNonBlank(topo.getDataprocRegion(), null) : null;
        if (region == null) {
            region = configStr(config, "dataprocRegion", gcpRegion);
        }

        String runtimeSa = topo != null ? firstNonBlank(topo.getDataprocWorkloadSaEmail(), null) : null;
        if (runtimeSa == null) {
            runtimeSa = configStr(config, "dataprocServiceAccount",
                    gcpProject == null ? null : "pulse-dataproc@" + gcpProject + ".iam.gserviceaccount.com");
        }

        String stagingBucket = topo != null ? firstNonBlank(topo.getDataprocStagingBucket(), null) : null;
        if (stagingBucket == null) {
            stagingBucket = configStr(config, "dataprocStagingBucket",
                    gcpProject == null ? null : gcpProject + "-dataproc-staging");
        }

        String packageTarget = configStr(config, "dataprocPackageTarget",
                gcpProject == null ? null : "gs://" + gcpProject + "-pulse-packages/dataproc/");

        if (gcpProject == null) {
            blockers.add("TOPOLOGY: Dataproc project not configured");
        }
        if (runtimeSa == null) {
            blockers.add("IAM: Dataproc workload service account not configured");
            operatorActions.add("Configure Dataproc workload service account with batch submission permissions");
        }
        if (stagingBucket == null) {
            blockers.add("TOPOLOGY: Dataproc staging bucket not configured");
        }

        allBlockers.addAll(blockers);
        boolean ready = blockers.isEmpty();

        return new DataprocBoundary(
                region, runtimeSa, "SERVERLESS_BATCH",
                stagingBucket, packageTarget,
                Responsibility.OPERATOR_PROVISIONS_PULSE_VALIDATES,
                blockers, ready);
    }

    // ── BigQuery boundary ────────────────────────────────────

    private BigQueryBoundary buildBigQueryBoundary(String targetType, String gcpProject,
                                                    Map<String, Object> config,
                                                    TenantGcpRuntimeTopology topo,
                                                    List<String> allBlockers, List<String> operatorActions) {
        if (!isGcpTarget(targetType)) {
            return null;
        }

        List<String> blockers = new ArrayList<>();

        // Prefer topology medallion datasets if available
        List<String> datasets;
        if (topo != null && hasAnyBqDataset(topo)) {
            datasets = new ArrayList<>();
            if (!isBlank(topo.getBqDatasetBronze())) datasets.add(topo.getBqProjectId() + ":" + topo.getBqDatasetBronze());
            if (!isBlank(topo.getBqDatasetSilver())) datasets.add(topo.getBqProjectId() + ":" + topo.getBqDatasetSilver());
            if (!isBlank(topo.getBqDatasetGold())) datasets.add(topo.getBqProjectId() + ":" + topo.getBqDatasetGold());
        } else {
            @SuppressWarnings("unchecked")
            List<String> configDatasets = config.get("bqTargetDatasets") instanceof List<?> list
                    ? (List<String>) list
                    : (gcpProject == null ? List.of()
                            : List.of(gcpProject + ":pulse_bronze", gcpProject + ":pulse_silver", gcpProject + ":pulse_gold"));
            datasets = configDatasets;
        }

        @SuppressWarnings("unchecked")
        List<String> tables = config.get("bqTargetTables") instanceof List<?> list
                ? (List<String>) list : List.of();
        @SuppressWarnings("unchecked")
        List<String> ddlTargets = config.get("bqDdlTargets") instanceof List<?> list
                ? (List<String>) list : List.of("CREATE_TABLE", "CREATE_VIEW", "ALTER_TABLE");
        @SuppressWarnings("unchecked")
        List<String> jobTargets = config.get("bqJobTargets") instanceof List<?> list
                ? (List<String>) list : List.of("LOAD", "QUERY", "EXTRACT");

        // Prefer topology for Iceberg connection
        String icebergConnection = topo != null ? firstNonBlank(topo.getBqConnectionId(), null) : null;
        if (icebergConnection == null) {
            icebergConnection = configStr(config, "bqManagedIcebergConnection", null);
        }
        String icebergResourceRef = topo != null && !isBlank(topo.getIcebergStorageBucket())
                ? "gs://" + topo.getIcebergStorageBucket() : null;
        if (icebergResourceRef == null) {
            icebergResourceRef = configStr(config, "bqManagedIcebergResourceRef", null);
        }

        if (gcpProject == null && (topo == null || isBlank(topo.getBqProjectId()))) {
            blockers.add("TOPOLOGY: BigQuery project not configured");
        }
        if (datasets.isEmpty()) {
            blockers.add("TOPOLOGY: no BigQuery target datasets configured");
            operatorActions.add("Configure BigQuery target datasets (bronze/silver/gold)");
        }

        allBlockers.addAll(blockers);
        boolean ready = blockers.isEmpty();

        return new BigQueryBoundary(
                datasets, tables, ddlTargets, jobTargets,
                icebergConnection, icebergResourceRef,
                Responsibility.PULSE_CREATES,
                blockers, ready);
    }

    // ── Secret Manager boundary ──────────────────────────────

    private SecretManagerBoundary buildSecretManagerBoundary(String targetType, String gcpProject,
                                                             Map<String, Object> config,
                                                             TenantGcpRuntimeTopology topo,
                                                             List<String> allBlockers, List<String> operatorActions) {
        if (!isGcpTarget(targetType)) {
            return null;
        }

        List<String> blockers = new ArrayList<>();

        // Use topology's Secret Manager project if available
        String smProject = topo != null ? firstNonBlank(topo.getSecretManagerProjectId(), gcpProject) : gcpProject;

        String tokenRef = configStr(config, "tokenReference",
                smProject == null ? null
                        : "gcp-sm://projects/" + smProject + "/secrets/pulse-deploy-sa/versions/latest");
        String runtimeAccess = "Service account runtime access via Secret Manager accessor role";

        List<SecretRef> secretRefs = new ArrayList<>();
        if (tokenRef != null) {
            secretRefs.add(new SecretRef("pulse-deploy-sa", tokenRef, "Deploy service account credentials"));
        }
        // Add standard expected secret refs
        if (smProject != null) {
            secretRefs.add(new SecretRef("pulse-airflow-callback",
                    "gcp-sm://projects/" + smProject + "/secrets/pulse-airflow-callback/versions/latest",
                    "Airflow callback authentication"));
            secretRefs.add(new SecretRef("pulse-jdbc-credentials",
                    "gcp-sm://projects/" + smProject + "/secrets/pulse-jdbc-credentials/versions/latest",
                    "JDBC source credentials for connector bindings"));
        }

        if (smProject == null) {
            blockers.add("TOPOLOGY: Secret Manager project not configured");
        }
        if (tokenRef == null) {
            blockers.add("CREDENTIAL: deploy token reference not configured");
            operatorActions.add("Configure Secret Manager secret for deploy service account");
        }

        allBlockers.addAll(blockers);
        boolean ready = blockers.isEmpty();

        return new SecretManagerBoundary(
                runtimeAccess, secretRefs,
                Responsibility.OPERATOR_PROVISIONS_PULSE_VALIDATES,
                blockers, ready);
    }

    // ── Evidence/log boundary ────────────────────────────────

    private EvidenceLogBoundary buildEvidenceLogBoundary(String targetType, String gcpProject,
                                                          Map<String, Object> config,
                                                          TenantGcpRuntimeTopology topo,
                                                          List<String> allBlockers, List<String> operatorActions) {
        if (!isGcpTarget(targetType)) {
            return null;
        }

        List<String> blockers = new ArrayList<>();

        // Prefer topology evidence sink fields
        String evidenceBucket = topo != null ? firstNonBlank(topo.getEvidenceSinkBucket(), null) : null;
        if (evidenceBucket == null) {
            evidenceBucket = configStr(config, "evidenceBucket",
                    gcpProject == null ? null : gcpProject + "-pulse-evidence");
        }
        String evidencePrefix = configStr(config, "evidencePrefix", "deploy-evidence/");

        String logDataset = topo != null ? firstNonBlank(topo.getEvidenceSinkDataset(), null) : null;
        if (logDataset == null) {
            logDataset = configStr(config, "logDataset",
                    gcpProject == null ? null : gcpProject + ":pulse_deploy_logs");
        }
        String logPrefix = configStr(config, "logPrefix", "deploy-logs/");

        if (gcpProject == null && evidenceBucket == null) {
            blockers.add("TOPOLOGY: evidence/log destination not configured");
        }
        if (evidenceBucket == null) {
            blockers.add("TOPOLOGY: evidence bucket not configured");
            operatorActions.add("Configure GCS evidence bucket for deploy evidence storage");
        }

        allBlockers.addAll(blockers);
        boolean ready = blockers.isEmpty();

        return new EvidenceLogBoundary(
                evidenceBucket, evidencePrefix, logDataset, logPrefix,
                Responsibility.PULSE_CREATES,
                blockers, ready);
    }

    // ── Artifact readiness ───────────────────────────────────

    private GeneratedArtifactReadiness buildArtifactReadiness(String packageId,
                                                               List<String> allBlockers) {
        if (packageId == null) {
            allBlockers.add("ARTIFACT: no package specified for boundary readback");
            return new GeneratedArtifactReadiness(false, false, null, false,
                    List.of("No package specified"));
        }

        var pkg = packageRepo.findById(packageId).orElse(null);
        if (pkg == null) {
            allBlockers.add("ARTIFACT: package '" + packageId + "' not found");
            return new GeneratedArtifactReadiness(false, false, null, false,
                    List.of("Package not found"));
        }

        List<String> blockers = new ArrayList<>();
        String buildStatus = pkg.getBuildStatus();
        boolean completed = "COMPLETED".equals(buildStatus);
        if (!completed) {
            blockers.add("Package build status is '" + buildStatus + "', not COMPLETED");
        }

        allBlockers.addAll(blockers);
        return new GeneratedArtifactReadiness(true, true, buildStatus, completed, blockers);
    }

    // ── Helpers ──────────────────────────────────────────────

    private DeployBoundaryReadback buildMissingTargetReadback(String tenantId, String targetId) {
        return new DeployBoundaryReadback(
                DeployBoundaryReadback.SCHEMA_VERSION,
                tenantId, targetId, null, null,
                BoundaryStatus.BLOCKED,
                Instant.now(), "DeployBoundaryService",
                null, null, null, null, null, null,
                List.of("HARD_BLOCK: deployment target '" + targetId + "' not found"),
                List.of("Create a deployment target via POST /api/v1/tenants/{tenantId}/deployment-targets"),
                DeployBoundaryReadback.EVIDENCE_DISCLAIMER
        );
    }

    private static boolean isGcpTarget(String targetType) {
        return RuntimeCapabilityMatrix.GCP.equals(targetType);
    }

    private static String configStr(Map<String, Object> config, String key, String defaultValue) {
        Object val = config.get(key);
        if (val instanceof String s && !s.isBlank()) {
            return s;
        }
        return defaultValue;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean hasAnyBqDataset(TenantGcpRuntimeTopology topo) {
        return !isBlank(topo.getBqDatasetBronze())
                || !isBlank(topo.getBqDatasetSilver())
                || !isBlank(topo.getBqDatasetGold());
    }
}
