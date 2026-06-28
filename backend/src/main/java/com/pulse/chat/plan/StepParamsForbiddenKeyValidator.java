package com.pulse.chat.plan;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ARCH-018 guardrail for {@code plan_set_step_params}: rejects params payloads
 * that try to write canonical storage / lake / table-contract fields,
 * pipeline-level orchestration policy fields, or DQ-expectation fields
 * through the generic-params path.
 *
 * <p>The validator is intentionally name-only: it inspects the params map's
 * top-level keys and a small set of camel/snake aliases. It does not look
 * inside nested params; nested structures (e.g. {@code routes[].name}) are
 * blueprint-defined and pass through.</p>
 */
public final class StepParamsForbiddenKeyValidator {

    /**
     * Outcome of validating a params payload.
     *
     * @param sanitizedParams the params map with normalized lookup keys retained;
     *                        callers persist this only if {@link #allowed} is true.
     * @param allowed         {@code true} when no forbidden category was hit and
     *                        the post-normalization payload is non-empty.
     * @param errorCode       one of {@link ChatPlanErrorCodes}, or {@code null} when allowed.
     * @param offendingKeys   the specific keys that triggered the rejection
     *                        (for the human-readable message).
     * @param message         pre-formatted detail safe to surface in chat.
     */
    public record Result(
            Map<String, Object> sanitizedParams,
            boolean allowed,
            String errorCode,
            Set<String> offendingKeys,
            String message) {}

    // Lowercase-and-stripped keys ARE forbidden in the canonical-field category.
    // Mirrored / camelCase / snake_case spellings are normalized before lookup.
    private static final Set<String> CANONICAL_STORAGE_LAKE_KEYS = Set.of(
            "storagebackend", "lakelayer", "lakeformat",
            // table / storage / lake contract fields
            "tableformat", "catalogkind", "relativestoragepath",
            "partitionspec", "layoutspec", "logicaltableid", "tablerole",
            "landingcontract", "tablecontract"
            // physical-name fields (e.g. physical_table_name, physical_database, ...)
            // are matched by prefix below.
    );

    private static final Set<String> ORCHESTRATION_POLICY_KEYS = Set.of(
            "schedulecron", "catchupenabled", "maxactiveruns",
            "dependsonpast", "timezone", "retrycount",
            "scheduletype", "triggerdataset", "policyconfigs",
            "orchestrationpolicy", "orchestrationpolicyblueprints"
    );

    private static final Set<String> DQ_KEYS = Set.of(
            "dqexpectations", "expectations", "dqrules"
    );

    private StepParamsForbiddenKeyValidator() {}

    public static Result validate(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return new Result(
                    Map.of(), false,
                    ChatPlanErrorCodes.STEP_PARAMS_EMPTY_AFTER_NORMALIZATION,
                    Set.of(),
                    "plan_set_step_params requires at least one generic-params key. Got empty payload.");
        }

        Set<String> canonicalHits = new LinkedHashSet<>();
        Set<String> orchestrationHits = new LinkedHashSet<>();
        Set<String> dqHits = new LinkedHashSet<>();
        Map<String, Object> sanitized = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String original = entry.getKey();
            if (original == null) continue;
            String norm = normalize(original);

            if (DQ_KEYS.contains(norm)) {
                dqHits.add(original);
                continue;
            }
            if (ORCHESTRATION_POLICY_KEYS.contains(norm)) {
                orchestrationHits.add(original);
                continue;
            }
            if (CANONICAL_STORAGE_LAKE_KEYS.contains(norm)
                    || norm.startsWith("landingcontract")
                    || norm.startsWith("tablecontract")
                    || norm.startsWith("physicaltable")
                    || norm.startsWith("physicaldatabase")
                    || norm.startsWith("physicalschema")) {
                canonicalHits.add(original);
                continue;
            }
            sanitized.put(original, entry.getValue());
        }

        // DQ takes priority because it most strongly routes the LLM to a
        // specific alternative tool; orchestration follows; canonical-fields
        // is the catch-all.
        if (!dqHits.isEmpty()) {
            return new Result(
                    sanitized, false,
                    ChatPlanErrorCodes.STEP_PARAMS_REQUIRES_DQ_TOOL,
                    dqHits,
                    "plan_set_step_params cannot write DQ expectation keys "
                            + dqHits + ". Use apply_dq_expectations instead.");
        }
        if (!orchestrationHits.isEmpty()) {
            return new Result(
                    sanitized, false,
                    ChatPlanErrorCodes.STEP_PARAMS_REQUIRES_PIPELINE_ORCHESTRATION,
                    orchestrationHits,
                    "plan_set_step_params cannot write pipeline orchestration policy keys "
                            + orchestrationHits
                            + ". Use update_pipeline_orchestration (or plan_set_pipeline_orchestration) instead.");
        }
        if (!canonicalHits.isEmpty()) {
            return new Result(
                    sanitized, false,
                    ChatPlanErrorCodes.STEP_PARAMS_CANONICAL_FIELD_FORBIDDEN,
                    canonicalHits,
                    "plan_set_step_params cannot write canonical storage / lake / "
                            + "table-contract fields " + canonicalHits
                            + ". Update the instance via the canonical composition endpoint instead.");
        }
        if (sanitized.isEmpty()) {
            return new Result(
                    sanitized, false,
                    ChatPlanErrorCodes.STEP_PARAMS_EMPTY_AFTER_NORMALIZATION,
                    Set.of(),
                    "plan_set_step_params payload is empty after normalization. Nothing to plan.");
        }
        return new Result(sanitized, true, null, Set.of(), null);
    }

    /**
     * Normalize a params key for forbidden-key lookup: lowercase, strip
     * underscores. Catches storage_backend / storageBackend / Storage_Backend.
     */
    private static String normalize(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c != '_' && c != '-' && c != '.') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
