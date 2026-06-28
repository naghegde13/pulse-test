package com.pulse.e2e.semantic;

import com.pulse.e2e.contract.ScenarioDsl;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public final class SemanticHardeningEvidenceContracts {

    public static final String LOCAL_RUNTIME_NAMESPACE = "local";
    public static final String GCP_RUNTIME_NAMESPACE = "gcp";

    private SemanticHardeningEvidenceContracts() {
    }

    public static final String GCP_ENVIRONMENT_ROOT = "backend/build/e2e-semantic-hardening/gcp-environment";
    public static final String PROGRESS_LEDGER_PATH = "backend/build/e2e-semantic-hardening/progress/semantic-hardening-lane-ledger.json";

    public static final Set<String> PHASE_0_GCP_ENVIRONMENT_ARTIFACTS = Set.of(
            "gcp-readiness-report.json",
            "gcp-iam-capability-report.json",
            "gcp-runtime-identity-map.json",
            "gcp-gcs-smoke.json",
            "gcp-dataproc-smoke.json",
            "gcp-composer-smoke.json",
            "composer-dag-import-sync.json",
            "gcp-composer-dataproc-smoke.json",
            "gcp-generated-adapter-prereq.json",
            "gcp-cost-control-verification.json",
            "gcp-log-capture-smoke.json",
            "gcp-smoke-cleanup.json",
            "cleanup/composer-idle-policy.json",
            "cleanup/staging-cleanup.json",
            "gcp-environment-smoke-verdict.json"
    );

    public static final Set<String> LOCAL_DOCKER_ARTIFACTS = Set.of(
            "dag-state.json",
            "task-state.json",
            "minio-output-probe.json",
            "data-oracle-comparison.json",
            "scenario-catalog.json",
            "scenario-coverage-plan.json",
            "coverage.json",
            "verdict.json",
            "evidence-index.json",
            "critique-packet.json",
            "critique-verdict.json",
            "rerun-idempotency-coverage.json",
            "promotion-receipt.json"
    );

    public static final Set<String> GCP_RUNTIME_ARTIFACTS = Set.of(
            "gcp-readiness-report.json",
            "gcp-environment-smoke-verdict.json",
            "gcp-runtime-bridge.json",
            "composer-upload-evidence.json",
            "composer-import-errors.json",
            "composer-dag-state.json",
            "composer-task-state.json",
            "gcs-input-listing.json",
            "gcs-output-probe.json",
            "cloud-data-oracle-comparison.json",
            "logs/composer/*.log",
            "evidence-index.json",
            "verdict.json",
            "critique-packet.json",
            "critique-verdict.json",
            "rerun-idempotency-coverage.json",
            "promotion-receipt.json"
    );

    public static final Set<String> GCP_SPARK_RUNTIME_ARTIFACTS = Set.of(
            "gcp-dataproc-execution-plan.json",
            "runtime/dataproc-submit-request.json",
            "dataproc-batch-state.json",
            "logs/dataproc/*.log"
    );

    public static final Set<String> PROOF_SHAPES = Set.of(
            "generator",
            "adapter",
            "evidence-contract",
            "semantic-oracle",
            "ledger-checks",
            "blocked-semantic-dev",
            "docker-runtime",
            "gcp-environment-smoke",
            "gcp-golden",
            "gcp-full"
    );

    public static String runtimeNamespaceFor(ScenarioDsl.RuntimeAdapter runtimeAdapter) {
        if (runtimeAdapter == null) {
            return LOCAL_RUNTIME_NAMESPACE;
        }
        return switch (runtimeAdapter) {
            case LOCAL_AIRFLOW_BRIDGE -> LOCAL_RUNTIME_NAMESPACE;
            case GCP_COMPOSER_DATAPROC_BRIDGE -> GCP_RUNTIME_NAMESPACE;
        };
    }

    public static String namespaceFromEvidenceRoot(Path evidenceRoot) {
        if (evidenceRoot == null) {
            return null;
        }
        for (int index = evidenceRoot.getNameCount() - 1; index >= 0; index--) {
            String segment = evidenceRoot.getName(index).toString().toLowerCase(Locale.ROOT);
            if (LOCAL_RUNTIME_NAMESPACE.equals(segment) || GCP_RUNTIME_NAMESPACE.equals(segment)) {
                return segment;
            }
        }
        return null;
    }

    public static boolean evidenceRootMatchesRuntimeNamespace(Path evidenceRoot, String runtimeNamespace) {
        String directoryNamespace = namespaceFromEvidenceRoot(evidenceRoot);
        return directoryNamespace == null || directoryNamespace.equals(runtimeNamespace);
    }
}
