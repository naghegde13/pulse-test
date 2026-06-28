package com.pulse.deploy.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 7 — deterministic plan envelope returned by every
 * {@link DeploymentTargetAdapter}'s {@code plan(...)} call. The
 * envelope is structurally identical across adapters so the
 * {@code DeploymentTargetAdapterPlanContractTest} can assert on a
 * stable shape; per-adapter detail lives under {@link #details}
 * keyed by the adapter's own schema version.
 *
 * <p>The {@link #planSha256} is computed by the adapter over the
 * canonical JSON of {@link #details}, mirroring the Phase 5
 * {@code manifestSha256} recipe so two identical packages produce
 * byte-equal plans.
 *
 * @param schemaVersion         per-adapter envelope id, e.g.
 *                              {@code gcp-composer-dataproc-plan.v1}
 * @param adapter               canonical target type key
 *                              ({@code LOCAL_MATERIALIZATION},
 *                              {@code GCP_COMPOSER_DATAPROC},
 *                              {@code DPC_AIRFLOW_OPENSHIFT_SPARK})
 * @param verb                  one of {@code MATERIALIZE},
 *                              {@code SUBMIT}, plus future verbs.
 * @param deploymentRunId       run the plan was generated for
 * @param packageId             package the plan would act on
 * @param tenantId              tenant scope
 * @param environment           canonical env scope
 * @param targetId              deployment target id
 * @param capability            outcome of the {@link CapabilityCheckResult}
 *                              consulted by this plan; never {@code null}
 *                              even when the adapter has no opinion
 *                              (use {@link CapabilityCheckResult#approved()}
 *                              for that case)
 * @param details               per-adapter typed plan body
 * @param planSha256            sha256 hex over the canonical JSON of
 *                              {@link #details}
 */
public record AdapterPlan(
        String schemaVersion,
        String adapter,
        String verb,
        String deploymentRunId,
        String packageId,
        String tenantId,
        String environment,
        String targetId,
        CapabilityCheckResult capability,
        Map<String, Object> details,
        String planSha256
) {
    public AdapterPlan {
        details = details == null
                ? Collections.unmodifiableMap(new LinkedHashMap<>())
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** Verb constants — adapters use these directly so the field is byte-stable. */
    public static final String VERB_MATERIALIZE = "MATERIALIZE";
    public static final String VERB_SUBMIT = "SUBMIT";

    /** Canonical-JSON-friendly map suitable for evidence storage / fixture asserts. */
    public Map<String, Object> toCanonicalJson() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", schemaVersion);
        doc.put("adapter", adapter);
        doc.put("verb", verb);
        doc.put("deploymentRunId", deploymentRunId);
        doc.put("packageId", packageId);
        doc.put("tenantId", tenantId);
        doc.put("environment", environment);
        doc.put("targetId", targetId);
        doc.put("capability", capability == null ? null : capability.toCanonicalJson());
        doc.put("details", details);
        doc.put("planSha256", planSha256);
        return doc;
    }
}
