package com.pulse.deploy;

import com.pulse.deploy.model.DeploymentTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 7 — boundary normalizer for {@code DeploymentTarget.target_type}.
 *
 * <p>The V106 migration rewrites every existing {@code KUBERNETES} row
 * to {@code LOCAL_MATERIALIZATION} and adds a check constraint pinning
 * the canonical adapter set. The Java boundary normalizer mirrors that
 * mapping so a controller call with the legacy key still lands a
 * canonical value on the row, regardless of whether the migration has
 * already run.
 */
class TargetTypeCanonicalizationTest {

    @Test
    @DisplayName("Canonical Phase 7 keys pass through unchanged")
    void canonicalKeysUnchanged() {
        assertEquals("LOCAL_MATERIALIZATION",
                DeploymentTarget.normalizeTargetType("LOCAL_MATERIALIZATION"));
        assertEquals("GCP_COMPOSER_DATAPROC",
                DeploymentTarget.normalizeTargetType("GCP_COMPOSER_DATAPROC"));
        assertEquals("DPC_AIRFLOW_OPENSHIFT_SPARK",
                DeploymentTarget.normalizeTargetType("DPC_AIRFLOW_OPENSHIFT_SPARK"));
    }

    @Test
    @DisplayName("Legacy KUBERNETES alias maps to LOCAL_MATERIALIZATION (mirrors V106)")
    void kubernetesAliasMapsToLocal() {
        assertEquals("LOCAL_MATERIALIZATION",
                DeploymentTarget.normalizeTargetType("KUBERNETES"));
        assertEquals("LOCAL_MATERIALIZATION",
                DeploymentTarget.normalizeTargetType("kubernetes"));
        assertEquals("LOCAL_MATERIALIZATION",
                DeploymentTarget.normalizeTargetType("  Kubernetes  "));
    }

    @Test
    @DisplayName("Null/blank input defaults to LOCAL_MATERIALIZATION")
    void nullDefaultsToLocal() {
        assertEquals("LOCAL_MATERIALIZATION", DeploymentTarget.normalizeTargetType(null));
        assertEquals("LOCAL_MATERIALIZATION", DeploymentTarget.normalizeTargetType(""));
        assertEquals("LOCAL_MATERIALIZATION", DeploymentTarget.normalizeTargetType("   "));
    }

    @Test
    @DisplayName("Unknown legacy values pass through (V106 check constraint surfaces them on insert)")
    void unknownLegacyValuesSurfaceToConstraint() {
        // Phase 7 deliberately does NOT auto-remap AIRFLOW/DATABRICKS/EMR/DATAPROC —
        // they predate the canonical adapter set and require operator review.
        // The normalizer returns the upper-cased value so the V106 check
        // constraint blocks it on insert/update.
        assertEquals("AIRFLOW", DeploymentTarget.normalizeTargetType("AIRFLOW"));
        assertEquals("DATABRICKS", DeploymentTarget.normalizeTargetType("databricks"));
        assertEquals("EMR", DeploymentTarget.normalizeTargetType("emr"));
        assertEquals("DATAPROC", DeploymentTarget.normalizeTargetType("dataproc"));
    }

    @Test
    @DisplayName("Default targetType on a fresh DeploymentTarget is LOCAL_MATERIALIZATION (Phase 7 changed default)")
    void defaultIsLocalMaterialization() {
        DeploymentTarget t = new DeploymentTarget();
        assertEquals("LOCAL_MATERIALIZATION", t.getTargetType());
    }
}
