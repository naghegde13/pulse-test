package com.pulse.tenant.service;

import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.repository.TenantGcpRuntimeTopologyRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PKT-0025: Generates least-privilege IAM manifests for tenant GCP runtime
 * topology, scoped to three split service accounts:
 *
 * <ol>
 *   <li><b>Control Plane SA</b> — PULSE platform identity. Needs Composer
 *       environment/storage/log access, Secret Manager read, logging write,
 *       evidence write.</li>
 *   <li><b>Dataproc Workload SA</b> — Dataproc Serverless runtime identity.
 *       Needs batch submit, worker, monitoring, GCS staging, actAs, and
 *       BigQuery job/data access.</li>
 *   <li><b>BigQuery Connection SA</b> — BigQuery connection identity for
 *       managed Iceberg. Needs GCS object read/write on Iceberg bucket.</li>
 * </ol>
 *
 * <p>All grants are resource-scoped (bucket-level, dataset-level, secret-level)
 * rather than project-level where possible. No live GCP API calls are made.
 */
@Service
public class GcpIamManifestService {

    private final TenantGcpRuntimeTopologyRepository topologyRepository;

    public GcpIamManifestService(TenantGcpRuntimeTopologyRepository topologyRepository) {
        this.topologyRepository = topologyRepository;
    }

    /**
     * Generate the full least-privilege IAM manifest for a tenant.
     */
    public Map<String, Object> generateManifest(String tenantId) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("tenantId", tenantId);
        manifest.put("packet", "PKT-0025");

        Optional<TenantGcpRuntimeTopology> opt = topologyRepository.findByTenantId(tenantId);
        if (opt.isEmpty()) {
            manifest.put("status", "not_configured");
            manifest.put("error", "No GCP runtime topology configured — cannot generate IAM manifest");
            return manifest;
        }

        TenantGcpRuntimeTopology t = opt.get();

        manifest.put("status", "generated");
        manifest.put("serviceAccounts", buildServiceAccountSummary(t));
        manifest.put("controlPlaneGrants", buildControlPlaneGrants(t));
        manifest.put("dataprocWorkloadGrants", buildDataprocWorkloadGrants(t));
        manifest.put("bigqueryConnectionGrants", buildBigQueryConnectionGrants(t));
        manifest.put("iamBindingExecution", "OPERATOR_BLOCKED");
        manifest.put("docReferences", buildDocReferences());

        return manifest;
    }

    private Map<String, Object> buildServiceAccountSummary(TenantGcpRuntimeTopology t) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("controlPlane", Map.of(
                "email", nullSafe(t.getControlPlaneSaEmail()),
                "purpose", "PULSE platform control plane — orchestrates Composer, reads secrets, writes logs/evidence"
        ));
        summary.put("dataprocWorkload", Map.of(
                "email", nullSafe(t.getDataprocWorkloadSaEmail()),
                "purpose", "Dataproc Serverless runtime workload identity — submits/monitors batches, accesses data"
        ));
        summary.put("bigqueryConnection", Map.of(
                "email", nullSafe(t.getBqConnectionSaEmail()),
                "purpose", "BigQuery connection service account — Iceberg table storage access via GCS"
        ));
        return summary;
    }

    // ---- Control Plane Grants ----

    List<Map<String, Object>> buildControlPlaneGrants(TenantGcpRuntimeTopology t) {
        List<Map<String, Object>> grants = new ArrayList<>();

        // Composer grants
        if (!isBlank(t.getComposerEnvironment())) {
            grants.add(grant("roles/composer.user",
                    "projects/" + nullSafe(t.getComposerProjectId()) + "/locations/"
                            + nullSafe(t.getComposerRegion()) + "/environments/"
                            + nullSafe(t.getComposerEnvironment()),
                    "Trigger DAG runs and read environment state",
                    "https://cloud.google.com/composer/docs/how-to/access-control"));
        }

        // Composer bucket (DAG/plugins/data upload)
        if (!isBlank(t.getComposerEnvironmentBucket())) {
            grants.add(grant("roles/storage.objectAdmin",
                    "buckets/" + t.getComposerEnvironmentBucket(),
                    "Upload DAGs, plugins, and data files to Composer environment bucket",
                    "https://cloud.google.com/storage/docs/access-control/iam-roles"));
        }

        // Composer logs
        if (!isBlank(t.getComposerLogPrefix())) {
            grants.add(grant("roles/logging.viewer",
                    "projects/" + nullSafe(t.getComposerProjectId()),
                    "Read Composer task logs for observability",
                    "https://cloud.google.com/logging/docs/access-control"));
        }

        // Secret Manager grants
        if (!isBlank(t.getSecretManagerProjectId())) {
            grants.add(grant("roles/secretmanager.secretAccessor",
                    "projects/" + t.getSecretManagerProjectId(),
                    "Read secret values for credential resolution (connection strings, API keys)",
                    "https://cloud.google.com/secret-manager/docs/access-control"));
        }

        // GCS lifecycle/object grants for evidence
        if (!isBlank(t.getEvidenceSinkBucket())) {
            grants.add(grant("roles/storage.objectCreator",
                    "buckets/" + t.getEvidenceSinkBucket(),
                    "Write evidence artifacts (test results, audit logs, proof snapshots)",
                    "https://cloud.google.com/storage/docs/access-control/iam-roles"));
        }

        // Evidence BQ dataset
        if (!isBlank(t.getEvidenceSinkDataset())) {
            grants.add(grant("roles/bigquery.dataEditor",
                    "projects/" + nullSafe(t.getBqProjectId()) + "/datasets/" + t.getEvidenceSinkDataset(),
                    "Write evidence records to BigQuery evidence dataset",
                    "https://cloud.google.com/bigquery/docs/access-control"));
        }

        // Logging write
        if (!isBlank(t.getLoggingProjectId())) {
            grants.add(grant("roles/logging.logWriter",
                    "projects/" + t.getLoggingProjectId(),
                    "Write structured logs from PULSE control plane",
                    "https://cloud.google.com/logging/docs/access-control"));
        }

        return grants;
    }

    // ---- Dataproc Workload Grants ----

    List<Map<String, Object>> buildDataprocWorkloadGrants(TenantGcpRuntimeTopology t) {
        List<Map<String, Object>> grants = new ArrayList<>();

        // Dataproc batch submit + monitor
        if (!isBlank(t.getDataprocProjectId())) {
            grants.add(grant("roles/dataproc.editor",
                    "projects/" + t.getDataprocProjectId(),
                    "Submit and monitor Dataproc Serverless batches",
                    "https://cloud.google.com/dataproc-serverless/docs/concepts/iam"));

            grants.add(grant("roles/dataproc.worker",
                    "projects/" + t.getDataprocProjectId(),
                    "Execute as Dataproc worker — read/write staging data, access cluster resources",
                    "https://cloud.google.com/dataproc/docs/concepts/configuring-clusters/service-accounts"));
        }

        // actAs self (workload SA needs iam.serviceAccounts.actAs on itself for Dataproc submission)
        if (!isBlank(t.getDataprocWorkloadSaEmail())) {
            grants.add(grant("roles/iam.serviceAccountUser",
                    "serviceAccounts/" + t.getDataprocWorkloadSaEmail(),
                    "actAs grant — Dataproc Serverless batch submission requires actAs on workload SA",
                    "https://cloud.google.com/dataproc-serverless/docs/concepts/iam"));
        }

        // Dataproc staging bucket
        if (!isBlank(t.getDataprocStagingBucket())) {
            grants.add(grant("roles/storage.objectAdmin",
                    "buckets/" + t.getDataprocStagingBucket(),
                    "Read/write Dataproc staging artifacts (JARs, deps, temp files)",
                    "https://cloud.google.com/storage/docs/access-control/iam-roles"));
        }

        // BigQuery job + data access for Dataproc workload
        if (!isBlank(t.getBqProjectId())) {
            grants.add(grant("roles/bigquery.jobUser",
                    "projects/" + t.getBqProjectId(),
                    "Run BigQuery jobs from Dataproc/Spark workloads",
                    "https://cloud.google.com/bigquery/docs/access-control"));

            grants.add(grant("roles/bigquery.dataEditor",
                    "projects/" + t.getBqProjectId(),
                    "Read/write data in BigQuery medallion datasets from Dataproc workloads",
                    "https://cloud.google.com/bigquery/docs/access-control"));
        }

        // Iceberg bucket access for Dataproc (reads/writes Iceberg data files)
        if (!isBlank(t.getIcebergStorageBucket())) {
            grants.add(grant("roles/storage.objectAdmin",
                    "buckets/" + t.getIcebergStorageBucket(),
                    "Read/write Iceberg data files and metadata from Dataproc/Spark",
                    "https://cloud.google.com/storage/docs/access-control/iam-roles"));
        }

        return grants;
    }

    // ---- BigQuery Connection Grants ----

    List<Map<String, Object>> buildBigQueryConnectionGrants(TenantGcpRuntimeTopology t) {
        List<Map<String, Object>> grants = new ArrayList<>();

        // BQ connection SA needs object access to Iceberg bucket
        if (!isBlank(t.getBqConnectionSaEmail()) && !isBlank(t.getIcebergStorageBucket())) {
            grants.add(grant("roles/storage.objectAdmin",
                    "buckets/" + t.getIcebergStorageBucket(),
                    "BigQuery connection SA reads/writes Iceberg data files in GCS for managed Iceberg tables",
                    "https://cloud.google.com/bigquery/docs/iceberg-tables"));
        }

        // BQ connection grant on BigQuery datasets
        if (!isBlank(t.getBqConnectionId()) && !isBlank(t.getBqProjectId())) {
            grants.add(grant("roles/bigquery.connectionUser",
                    "projects/" + t.getBqProjectId() + "/locations/"
                            + nullSafe(t.getBqConnectionRegion()) + "/connections/"
                            + t.getBqConnectionId(),
                    "Use BigQuery connection for external/Iceberg table access",
                    "https://cloud.google.com/bigquery/docs/connections-api-intro"));
        }

        return grants;
    }

    private Map<String, Object> grant(String role, String resource, String purpose, String docRef) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("role", role);
        g.put("resource", resource);
        g.put("purpose", purpose);
        g.put("docReference", docRef);
        return g;
    }

    private List<Map<String, String>> buildDocReferences() {
        return List.of(
                Map.of("topic", "Composer IAM",
                        "url", "https://cloud.google.com/composer/docs/how-to/access-control"),
                Map.of("topic", "Dataproc Serverless IAM",
                        "url", "https://cloud.google.com/dataproc-serverless/docs/concepts/iam"),
                Map.of("topic", "Dataproc Service Accounts",
                        "url", "https://cloud.google.com/dataproc/docs/concepts/configuring-clusters/service-accounts"),
                Map.of("topic", "BigQuery Access Control",
                        "url", "https://cloud.google.com/bigquery/docs/access-control"),
                Map.of("topic", "BigQuery Iceberg Tables",
                        "url", "https://cloud.google.com/bigquery/docs/iceberg-tables"),
                Map.of("topic", "GCS IAM Roles",
                        "url", "https://cloud.google.com/storage/docs/access-control/iam-roles"),
                Map.of("topic", "Secret Manager Access Control",
                        "url", "https://cloud.google.com/secret-manager/docs/access-control"),
                Map.of("topic", "Cloud Logging Access Control",
                        "url", "https://cloud.google.com/logging/docs/access-control")
        );
    }

    private static String nullSafe(String s) {
        return s != null ? s : "(not configured)";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
