package com.pulse.tenant.service;

import com.pulse.auth.service.TenantService;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PKT-0025: Manages per-tenant GCP runtime topology covering Composer,
 * Dataproc Serverless, BigQuery (native + managed Iceberg), GCS,
 * Secret Manager, logging, and evidence sinks.
 *
 * <p>Topology readback never includes secret material. Service-account emails
 * are surfaced for IAM manifest correlation but are not secrets themselves.
 */
@Service
public class GcpRuntimeTopologyService {

    private final TenantGcpRuntimeTopologyRepository repository;
    private final TenantService tenantService;

    public GcpRuntimeTopologyService(TenantGcpRuntimeTopologyRepository repository,
                                      TenantService tenantService) {
        this.repository = repository;
        this.tenantService = tenantService;
    }

    public Optional<TenantGcpRuntimeTopology> getTopology(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    /**
     * Upsert topology for a tenant. Validates tenant existence first.
     */
    public TenantGcpRuntimeTopology setTopology(String tenantId, TenantGcpRuntimeTopology incoming) {
        tenantService.getTenantEntity(tenantId); // throws if missing
        var existing = repository.findByTenantId(tenantId);
        TenantGcpRuntimeTopology target;
        if (existing.isPresent()) {
            target = existing.get();
        } else {
            target = new TenantGcpRuntimeTopology();
            target.setTenantId(tenantId);
        }
        copyFields(incoming, target);
        return repository.save(target);
    }

    /**
     * Build a full readback map for the topology. No secret material included.
     */
    public Map<String, Object> buildReadback(TenantGcpRuntimeTopology t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", t.getTenantId());

        // Composer
        Map<String, Object> composer = new LinkedHashMap<>();
        composer.put("projectId", t.getComposerProjectId());
        composer.put("environment", t.getComposerEnvironment());
        composer.put("region", t.getComposerRegion());
        composer.put("environmentBucket", t.getComposerEnvironmentBucket());
        composer.put("dagPrefix", t.getComposerDagPrefix());
        composer.put("pluginsPrefix", t.getComposerPluginsPrefix());
        composer.put("dataPrefix", t.getComposerDataPrefix());
        composer.put("logPrefix", t.getComposerLogPrefix());
        m.put("composer", composer);

        // Dataproc Serverless
        Map<String, Object> dataproc = new LinkedHashMap<>();
        dataproc.put("projectId", t.getDataprocProjectId());
        dataproc.put("region", t.getDataprocRegion());
        dataproc.put("workloadServiceAccount", t.getDataprocWorkloadSaEmail());
        dataproc.put("network", t.getDataprocNetwork());
        dataproc.put("subnet", t.getDataprocSubnet());
        dataproc.put("stagingBucket", t.getDataprocStagingBucket());
        m.put("dataproc", dataproc);

        // BigQuery native
        Map<String, Object> bq = new LinkedHashMap<>();
        bq.put("projectId", t.getBqProjectId());
        bq.put("location", t.getBqLocation());
        bq.put("datasetBronze", t.getBqDatasetBronze());
        bq.put("datasetSilver", t.getBqDatasetSilver());
        bq.put("datasetGold", t.getBqDatasetGold());
        m.put("bigquery", bq);

        // BigQuery connection
        Map<String, Object> bqConn = new LinkedHashMap<>();
        bqConn.put("connectionId", t.getBqConnectionId());
        bqConn.put("connectionRegion", t.getBqConnectionRegion());
        bqConn.put("connectionServiceAccount", t.getBqConnectionSaEmail());
        m.put("bigqueryConnection", bqConn);

        // Iceberg
        Map<String, Object> iceberg = new LinkedHashMap<>();
        iceberg.put("storageBucket", t.getIcebergStorageBucket());
        m.put("iceberg", iceberg);

        // Evidence
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sinkBucket", t.getEvidenceSinkBucket());
        evidence.put("sinkDataset", t.getEvidenceSinkDataset());
        m.put("evidence", evidence);

        // Secret Manager
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("projectId", t.getSecretManagerProjectId());
        // PKT-FINAL-5 / BUG-54: surface the per-tenant authority mode and
        // optional name prefix in the topology readback so the UI Settings
        // panel reflects what `PUT /secret-manager` wrote.
        sm.put("authorityMode", t.getSecretAuthorityMode());
        sm.put("secretNamePrefix", t.getSecretNamePrefix());
        m.put("secretManager", sm);

        // Logging
        Map<String, Object> logging = new LinkedHashMap<>();
        logging.put("projectId", t.getLoggingProjectId());
        logging.put("logBucket", t.getLoggingLogBucket());
        m.put("logging", logging);

        // Control plane SA
        m.put("controlPlaneServiceAccount", t.getControlPlaneSaEmail());

        return m;
    }

    /**
     * Build topology readiness category for the readiness API.
     * Reports status and blockers for each topology subsystem.
     */
    public Map<String, Object> buildReadinessCategory(String tenantId) {
        Map<String, Object> category = new LinkedHashMap<>();
        var opt = repository.findByTenantId(tenantId);

        if (opt.isEmpty()) {
            category.put("status", "not_configured");
            category.put("blockers", List.of("No GCP runtime topology configured for tenant"));
            return category;
        }

        TenantGcpRuntimeTopology t = opt.get();
        List<String> blockers = new ArrayList<>();

        // Composer checks
        if (isBlank(t.getComposerProjectId()) || isBlank(t.getComposerEnvironment())
                || isBlank(t.getComposerRegion())) {
            blockers.add("Composer topology incomplete: projectId, environment, and region are required");
        }

        // Dataproc checks
        if (isBlank(t.getDataprocProjectId()) || isBlank(t.getDataprocRegion())) {
            blockers.add("Dataproc topology incomplete: projectId and region are required");
        }
        if (isBlank(t.getDataprocWorkloadSaEmail())) {
            blockers.add("Dataproc workload service account not configured");
        }

        // BigQuery checks
        if (isBlank(t.getBqProjectId()) || isBlank(t.getBqLocation())) {
            blockers.add("BigQuery topology incomplete: projectId and location are required");
        }
        if (isBlank(t.getBqDatasetBronze()) || isBlank(t.getBqDatasetSilver())
                || isBlank(t.getBqDatasetGold())) {
            blockers.add("BigQuery medallion datasets incomplete: bronze, silver, and gold datasets required");
        }

        // BigQuery connection checks
        if (isBlank(t.getBqConnectionId())) {
            blockers.add("BigQuery connection not configured");
        }
        if (isBlank(t.getBqConnectionSaEmail())) {
            blockers.add("BigQuery connection service account not configured");
        }

        // Iceberg checks
        if (isBlank(t.getIcebergStorageBucket())) {
            blockers.add("Iceberg storage bucket not configured");
        }

        // Evidence checks
        if (isBlank(t.getEvidenceSinkBucket()) && isBlank(t.getEvidenceSinkDataset())) {
            blockers.add("Evidence sink not configured: at least one of bucket or dataset required");
        }

        // Location consistency checks
        checkLocationConsistency(t, blockers);

        // BigQuery connection SA bucket access check
        if (!isBlank(t.getBqConnectionSaEmail()) && !isBlank(t.getIcebergStorageBucket())) {
            // This is a static check — we flag if the connection SA is set but
            // cannot verify bucket access without live GCP. The IAM manifest
            // service generates the required grants.
        } else if (!isBlank(t.getBqConnectionId()) && isBlank(t.getBqConnectionSaEmail())) {
            blockers.add("BigQuery connection configured but connection service account missing — "
                    + "cannot verify Iceberg bucket access");
        }

        String status;
        if (blockers.isEmpty()) {
            status = "ready";
        } else {
            status = "incomplete";
        }

        category.put("status", status);
        if (!blockers.isEmpty()) {
            category.put("blockers", blockers);
        }
        category.put("hasComposer", !isBlank(t.getComposerProjectId()));
        category.put("hasDataproc", !isBlank(t.getDataprocProjectId()));
        category.put("hasBigQuery", !isBlank(t.getBqProjectId()));
        category.put("hasBigQueryConnection", !isBlank(t.getBqConnectionId()));
        category.put("hasIceberg", !isBlank(t.getIcebergStorageBucket()));
        category.put("hasEvidence", !isBlank(t.getEvidenceSinkBucket()) || !isBlank(t.getEvidenceSinkDataset()));

        return category;
    }

    /**
     * Checks location/region consistency across Composer, Dataproc, and BigQuery.
     * Mismatches are added as blockers because cross-region data movement incurs
     * cost and latency and can violate data residency requirements.
     */
    void checkLocationConsistency(TenantGcpRuntimeTopology t, List<String> blockers) {
        String composerRegion = t.getComposerRegion();
        String dataprocRegion = t.getDataprocRegion();
        String bqLocation = t.getBqLocation();

        if (!isBlank(composerRegion) && !isBlank(dataprocRegion)
                && !composerRegion.equalsIgnoreCase(dataprocRegion)) {
            blockers.add("Location mismatch: Composer region '" + composerRegion
                    + "' differs from Dataproc region '" + dataprocRegion + "'");
        }

        if (!isBlank(composerRegion) && !isBlank(bqLocation)
                && !composerRegion.equalsIgnoreCase(bqLocation)) {
            blockers.add("Location mismatch: Composer region '" + composerRegion
                    + "' differs from BigQuery location '" + bqLocation + "'");
        }

        if (!isBlank(dataprocRegion) && !isBlank(bqLocation)
                && !dataprocRegion.equalsIgnoreCase(bqLocation)) {
            blockers.add("Location mismatch: Dataproc region '" + dataprocRegion
                    + "' differs from BigQuery location '" + bqLocation + "'");
        }

        // BQ connection region should match BQ location
        if (!isBlank(t.getBqConnectionRegion()) && !isBlank(bqLocation)
                && !t.getBqConnectionRegion().equalsIgnoreCase(bqLocation)) {
            blockers.add("Location mismatch: BigQuery connection region '"
                    + t.getBqConnectionRegion() + "' differs from BigQuery location '"
                    + bqLocation + "'");
        }
    }

    private void copyFields(TenantGcpRuntimeTopology from, TenantGcpRuntimeTopology to) {
        to.setComposerProjectId(from.getComposerProjectId());
        to.setComposerEnvironment(from.getComposerEnvironment());
        to.setComposerRegion(from.getComposerRegion());
        to.setComposerEnvironmentBucket(from.getComposerEnvironmentBucket());
        to.setComposerDagPrefix(from.getComposerDagPrefix());
        to.setComposerPluginsPrefix(from.getComposerPluginsPrefix());
        to.setComposerDataPrefix(from.getComposerDataPrefix());
        to.setComposerLogPrefix(from.getComposerLogPrefix());

        to.setDataprocProjectId(from.getDataprocProjectId());
        to.setDataprocRegion(from.getDataprocRegion());
        to.setDataprocWorkloadSaEmail(from.getDataprocWorkloadSaEmail());
        to.setDataprocNetwork(from.getDataprocNetwork());
        to.setDataprocSubnet(from.getDataprocSubnet());
        to.setDataprocStagingBucket(from.getDataprocStagingBucket());

        to.setBqProjectId(from.getBqProjectId());
        to.setBqLocation(from.getBqLocation());
        to.setBqDatasetBronze(from.getBqDatasetBronze());
        to.setBqDatasetSilver(from.getBqDatasetSilver());
        to.setBqDatasetGold(from.getBqDatasetGold());

        to.setBqConnectionId(from.getBqConnectionId());
        to.setBqConnectionRegion(from.getBqConnectionRegion());
        to.setBqConnectionSaEmail(from.getBqConnectionSaEmail());

        to.setIcebergStorageBucket(from.getIcebergStorageBucket());

        to.setEvidenceSinkBucket(from.getEvidenceSinkBucket());
        to.setEvidenceSinkDataset(from.getEvidenceSinkDataset());

        to.setSecretManagerProjectId(from.getSecretManagerProjectId());

        to.setLoggingProjectId(from.getLoggingProjectId());
        to.setLoggingLogBucket(from.getLoggingLogBucket());

        to.setControlPlaneSaEmail(from.getControlPlaneSaEmail());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
