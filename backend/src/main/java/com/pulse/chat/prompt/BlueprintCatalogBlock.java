package com.pulse.chat.prompt;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The DUMP-ALL cached Blueprint catalog block (fragment 02 §0.1 / SPEC-ui §6/§7.14,
 * IMPL Phase 8 task 2). Every active Blueprint is rendered as ONE TIGHT entry
 * (name · category · layer · in/out ports · key params) so the LLM reasons over the
 * full catalog in-context — there is NO catalog-search tool (Discovery / Composer
 * read these directly; `get_blueprint_detail` is the on-demand full-contract
 * fallback).
 *
 * <p><b>Prompt-caching marker (forward item).</b> The block is wrapped in a
 * {@link #CACHE_MARKER} sentinel so a future request-body builder can attach a
 * provider {@code cache_control} breakpoint (Anthropic) / equivalent at this
 * boundary. The marker is INERT today — the live request body
 * ({@code ChatService.streamStage}) does not yet emit a {@code cache_control}
 * field, so caching is NOT enforced; the structure is in place for when it is.</p>
 *
 * <p><b>Token-bloat fallback (hybrid).</b> If the dumped catalog ever bloats the
 * prompt, switch to HYBRID mode: emit only the one-line summaries (no per-param
 * detail) here and rely on the on-demand `get_blueprint_detail` tool for the full
 * contract of a Blueprint already identified by name. {@link #renderSummaries}
 * implements that hybrid form; {@link #render} is the full dump. The selection is
 * a single call-site choice in {@link StagePromptAssembler}.</p>
 */
@Component
public class BlueprintCatalogBlock {

    /** Sentinel boundary for a future provider prompt-cache breakpoint (inert today). */
    public static final String CACHE_MARKER = "<!-- pulse:cache_control:blueprint_catalog -->";

    /** Heading the assembled prompt carries (tests assert the catalog block is present). */
    public static final String HEADING = "=== BLUEPRINT CATALOG (all active blueprints — in-context, no search tool) ===";

    private final BlueprintRepository blueprintRepository;

    @Autowired
    public BlueprintCatalogBlock(BlueprintRepository blueprintRepository) {
        this.blueprintRepository = blueprintRepository;
    }

    /** Full dump: every active blueprint as a tight entry (name · category · layer · ports · key-params). */
    public String render() {
        return render(activeBlueprints());
    }

    /** Hybrid fallback: one-line summaries only (use get_blueprint_detail for the full contract). */
    public String renderSummaries() {
        return renderSummaries(activeBlueprints());
    }

    private List<Blueprint> activeBlueprints() {
        return blueprintRepository.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active");
    }

    static String render(List<Blueprint> blueprints) {
        StringBuilder sb = new StringBuilder();
        sb.append(CACHE_MARKER).append('\n');
        sb.append(HEADING).append('\n');
        sb.append("Each entry: blueprint_key · category · layer · in/out ports · key params. ");
        sb.append("Judge a Blueprint on intent + params + ports, not on emitted code. ");
        sb.append("Call get_blueprint_detail only for the FULL contract of a Blueprint you have named.\n");
        String currentCat = "";
        for (Blueprint bp : blueprints) {
            String cat = bp.getCategory() == null ? "?" : bp.getCategory().name();
            if (!cat.equals(currentCat)) {
                currentCat = cat;
                sb.append("\n[").append(cat).append("]\n");
            }
            sb.append("- ").append(bp.getBlueprintKey())
              .append(" · ").append(cat)
              .append(" · layer=").append(layers(bp))
              .append(" · in=").append(ports(bp.getInputPorts()))
              .append(" · out=").append(ports(bp.getOutputPorts()))
              .append(" · params=").append(keyParams(bp))
              .append('\n');
        }
        sb.append(CACHE_MARKER).append('\n');
        return sb.toString();
    }

    static String renderSummaries(List<Blueprint> blueprints) {
        StringBuilder sb = new StringBuilder();
        sb.append(CACHE_MARKER).append('\n');
        sb.append(HEADING).append(" [hybrid: summaries only — get_blueprint_detail for full params]\n");
        String currentCat = "";
        for (Blueprint bp : blueprints) {
            String cat = bp.getCategory() == null ? "?" : bp.getCategory().name();
            if (!cat.equals(currentCat)) {
                currentCat = cat;
                sb.append("\n[").append(cat).append("]\n");
            }
            String desc = bp.getDescription() == null ? "" : bp.getDescription();
            sb.append("- ").append(bp.getBlueprintKey()).append(" · ").append(cat)
              .append(" · ").append(desc, 0, Math.min(80, desc.length())).append('\n');
        }
        sb.append(CACHE_MARKER).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Tight-entry field renderers
    // ------------------------------------------------------------------

    private static String layers(Blueprint bp) {
        List<String> layers = bp.getValidLayers();
        if (layers == null || layers.isEmpty()) return "-";
        return String.join("/", layers);
    }

    private static String ports(List<Map<String, Object>> ports) {
        if (ports == null || ports.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ports.size(); i++) {
            Object name = ports.get(i).get("name");
            if (name == null) name = ports.get(i).get("port");
            if (name == null) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(name);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /**
     * The KEY params: prefer the required-params schema (the user-tier decisions),
     * fall back to the first few from the full params schema. Tight — names only.
     */
    @SuppressWarnings("unchecked")
    private static String keyParams(Blueprint bp) {
        // required_params_schema is {paramName: {...}} per the catalog metadata.
        Map<String, Object> required = bp.getRequiredParamsSchema();
        if (required != null && !required.isEmpty()) {
            return joinKeys(required.keySet(), 8);
        }
        List<Map<String, Object>> params = bp.getParamsSchema();
        if (params != null && !params.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Map<String, Object> p : params) {
                Object name = p.get("name");
                if (name == null) name = p.get("key");
                if (name == null) continue;
                if (count >= 8) { sb.append(",…"); break; }
                if (sb.length() > 0) sb.append(",");
                sb.append(name);
                count++;
            }
            return sb.length() == 0 ? "-" : sb.toString();
        }
        return "-";
    }

    private static String joinKeys(java.util.Collection<String> keys, int max) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String k : keys) {
            if (count >= max) { sb.append(",…"); break; }
            if (sb.length() > 0) sb.append(",");
            sb.append(k);
            count++;
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }
}
