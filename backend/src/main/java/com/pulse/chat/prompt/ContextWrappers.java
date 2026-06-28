package com.pulse.chat.prompt;

/**
 * The per-turn CONTEXT-INJECTION WRAPPERS (fragment 02 §2; IMPL Phase 8 task 4) —
 * the labelled context-tag blocks the prompt-builder concatenates into the turn:
 * {@code <current_composition>} / {@code <dataset_schemas>} / {@code <selected_step>}
 * / {@code <run_status>} + the plain-text conversation-summary block. These tags
 * are STRIPPED from the streamed text before it reaches the user (the
 * stream-processor strips them; see {@link #stripContextTags}).
 *
 * <p>NOTE: the live {@code ChatService.buildSystemPrompt} already injects the
 * dataset schemas (the {@code ### Available Datasets} / schema block) — this class
 * provides the TAGGED forms the per-stage assembly uses, and re-uses the live
 * schema injection rather than duplicating it. The empty-arg renderers exist so a
 * stage can carry the tag CONTRACT (the instruction text) even when the per-turn
 * payload is not yet populated by the live composition-view plumbing.</p>
 */
public final class ContextWrappers {

    private ContextWrappers() {}

    /** {@code <current_composition>} — the staging-or-canonical CompositionView. */
    public static String currentComposition(String compositionJson) {
        return "<current_composition>\n"
                + (compositionJson == null || compositionJson.isBlank()
                    ? "(no composition on this pipeline yet)"
                    : compositionJson)
                + "\nLarge param values may be trimmed. Use get_blueprint_detail or get_step_schema for full detail.\n"
                + "This is the ground-truth graph for THIS turn: while you have ops staged it ALREADY reflects them\n"
                + "(the staging graph), so reading it stays consistent with what you have built so far this turn.\n"
                + "</current_composition>";
    }

    /** {@code <dataset_schemas>} — per dataset: name, grain, time_grain, columns with type/nullable/PII. */
    public static String datasetSchemas(String schemasBlock) {
        return "<dataset_schemas>\n"
                + (schemasBlock == null || schemasBlock.isBlank() ? "(no datasets in play)" : schemasBlock)
                + "\nPII is per COLUMN — classify in a table, never call a whole dataset \"PII\".\n"
                + "</dataset_schemas>";
    }

    /** {@code <selected_step>} — the deictic anchor for "this"/"it"/"here". */
    public static String selectedStep(String selectedStepSummary) {
        if (selectedStepSummary == null || selectedStepSummary.isBlank()) return "";
        return "<selected_step>\n"
                + "The user has explicitly selected the following step in the inspector for you to focus on:\n"
                + selectedStepSummary + "\n"
                + "When the user says \"this step\", \"it\", \"this\", \"here\", or similar deictic references, they\n"
                + "mean THIS step. Resolve deixis against it. Look up full step detail by matching the name in\n"
                + "<current_composition>.\n"
                + "</selected_step>";
    }

    /** {@code <run_status>} — last-run status + per-step row counts. */
    public static String runStatus(String runStatusBlock) {
        if (runStatusBlock == null || runStatusBlock.isBlank()) return "";
        return "<run_status>\n" + runStatusBlock + "\n</run_status>";
    }

    /** The plain-text conversation-summary block (NOT an XML tag). */
    public static String conversationSummary(String previousSummary, String originalRequest,
                                             String priorActions, String currentRequest) {
        StringBuilder sb = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("Previous summary: ").append(previousSummary).append('\n');
        }
        if (originalRequest != null && !originalRequest.isBlank()) {
            sb.append("Original request: ").append(originalRequest).append('\n');
        }
        if (priorActions != null && !priorActions.isBlank()) {
            sb.append("Prior actions: ").append(priorActions).append('\n');
        }
        if (currentRequest != null && !currentRequest.isBlank()) {
            sb.append("Current request: ").append(currentRequest).append('\n');
        }
        return sb.toString();
    }

    /**
     * Strip the context-tag wrappers (and the plain-text summary block) from
     * streamed assistant text before it reaches the user — the tags are
     * model-only scaffolding. Conservatively removes the known tag pairs and the
     * inline cache markers; leaves the user-facing prose intact.
     */
    public static String stripContextTags(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] tags = {
                "current_composition", "dataset_schemas", "selected_step",
                "schema_visibility", "conflict_overlay", "run_status",
                "runtime_mode", "blueprint_config_guidance", "session_facts"
        };
        String out = text;
        for (String tag : tags) {
            out = out.replaceAll("(?s)<" + tag + ">.*?</" + tag + ">", "");
        }
        out = out.replace(BlueprintCatalogBlock.CACHE_MARKER, "");
        return out;
    }
}
