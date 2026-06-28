package com.pulse.pipeline.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle stages that PULSE itself can directly observe.
 *
 * <p>PULSE deploys to dev only. All design work occurs in dev. Higher-environment
 * deployments (integration, UAT, prod) are owned by enterprise CI/CD after a
 * pipeline's artifact is published; PULSE has no legitimate visibility into
 * those downstream states and therefore does not model them as PULSE stages.
 *
 * <p>Per the 2026-05-25 architectural correction (PKT-FINAL-2 / BUG-2026-05-25-02),
 * the previous {@code INTEGRATION_QUALIFIED}, {@code UAT_DEPLOYED}, and
 * {@code PRODUCTION} values were removed. {@code PUBLISHED} is the terminal
 * PULSE-managed stage; everything past that is "handed off to enterprise CD".
 */
public enum PipelineStage {

    // PULSE-managed stages (dev only)
    ENGINEERING,            // Active development: configuring blueprints, params, datasets
    DEV_DEPLOYED,           // Deployed to dev environment
    DEV_VALIDATED,          // Passed dev validation run
    PUBLISHED;              // Artifact published; control handed off to enterprise CD

    private static final Map<PipelineStage, Set<PipelineStage>> VALID_TRANSITIONS = Map.of(
            ENGINEERING,   EnumSet.of(DEV_DEPLOYED),
            DEV_DEPLOYED,  EnumSet.of(DEV_VALIDATED, ENGINEERING),
            DEV_VALIDATED, EnumSet.of(PUBLISHED, ENGINEERING),
            PUBLISHED,     EnumSet.noneOf(PipelineStage.class)
    );

    public boolean canTransitionTo(PipelineStage target) {
        Set<PipelineStage> allowed = VALID_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }

    public Set<PipelineStage> allowedTransitions() {
        return VALID_TRANSITIONS.getOrDefault(this, EnumSet.noneOf(PipelineStage.class));
    }

    /**
     * Whether this stage is owned outside PULSE (e.g. enterprise CI/CD).
     *
     * <p>After the 2026-05-25 truncation no enum value is externally managed —
     * PULSE only tracks states it can directly observe. The method is kept for
     * call-site compatibility and always returns {@code false}.
     */
    public boolean isExternallyManaged() {
        return false;
    }
}
