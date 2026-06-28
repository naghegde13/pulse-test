package com.pulse.chat.plan;

/**
 * Stable, machine-readable error codes for ARCH-018 chat plan validation.
 *
 * <p>These codes are part of the chat plan/apply contract: tool results that
 * reject a plan attempt carry one of these codes in the {@code status} +
 * {@code message} fields of the {@link com.pulse.chat.model.ToolResult}
 * envelope so the LLM can route to the correct alternative tool without
 * having to parse English error messages.</p>
 */
public final class ChatPlanErrorCodes {

    private ChatPlanErrorCodes() {}

    /**
     * The caller tried to write a canonical storage / lake / table-contract
     * field through {@code plan_set_step_params}. Those fields are owned by
     * {@code BlueprintInstanceConfigurationService} via the canonical
     * composition instance endpoint, not by generic params.
     */
    public static final String STEP_PARAMS_CANONICAL_FIELD_FORBIDDEN =
            "STEP_PARAMS_CANONICAL_FIELD_FORBIDDEN";

    /**
     * The caller tried to write a pipeline-level orchestration policy field
     * (schedule_cron / catchup_enabled / max_active_runs / depends_on_past /
     * timezone / retry_count / schedule_type / trigger_dataset /
     * policy_configs / orchestration_policy / orchestration_policy_blueprints)
     * through generic step params. Those belong on the pipeline orchestration
     * tool, not on step params.
     */
    public static final String STEP_PARAMS_REQUIRES_PIPELINE_ORCHESTRATION =
            "STEP_PARAMS_REQUIRES_PIPELINE_ORCHESTRATION";

    /**
     * The caller tried to write DQ expectation fields through generic step
     * params. DQ expectations are owned by {@code DqExpectationService} and
     * must go through {@code apply_dq_expectations}.
     */
    public static final String STEP_PARAMS_REQUIRES_DQ_TOOL =
            "STEP_PARAMS_REQUIRES_DQ_TOOL";

    /**
     * After stripping canonical / orchestration / DQ keys, the params payload
     * is empty. The plan would be a no-op; reject so the caller picks the
     * right canonical tool instead.
     */
    public static final String STEP_PARAMS_EMPTY_AFTER_NORMALIZATION =
            "STEP_PARAMS_EMPTY_AFTER_NORMALIZATION";

    /**
     * The caller tried to add an orchestration policy blueprint as a
     * composition step. Routes the LLM to
     * {@code update_pipeline_orchestration} / {@code plan_set_pipeline_orchestration}.
     */
    public static final String STEP_REQUIRES_PIPELINE_ORCHESTRATION =
            "STEP_REQUIRES_PIPELINE_ORCHESTRATION";

    /**
     * Re-exported here so chat tools can attach the same stable code emitted
     * by {@code BlueprintCompatReadOnlyException} (ARCH-014).
     */
    public static final String BLUEPRINT_COMPAT_READ_ONLY =
            "BLUEPRINT_COMPAT_READ_ONLY";
}
