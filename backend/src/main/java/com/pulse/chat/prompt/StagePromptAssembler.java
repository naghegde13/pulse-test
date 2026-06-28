package com.pulse.chat.prompt;

import com.pulse.chat.orchestration.NodeLlmAdapter;
import com.pulse.chat.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Assembles the PER-STAGE system prompt (IMPL-ui-composition Phase 8 task 6). Each
 * of the seven LOCKED stages gets its OWN system prompt — the §8 shared preamble +
 * the stage body ({@link StagePrompts}) + the cross-cutting blocks the stage needs
 * (ACTIVE MODE, the dumped catalog, the category guides, the retained
 * PulseSystemPrompt coverage) + the live per-turn tenant/composition CONTEXT
 * (reused from {@link ChatService#buildTurnContextPrompt} so the dataset-schema /
 * domain / pipeline / session-fact injection is NOT duplicated).
 *
 * <p>This REPLACES the single {@code buildSystemPrompt} for the GRAPH path only —
 * the legacy single-loop {@code buildSystemPrompt} stays intact for the loop path
 * (the GraphDriver swaps in the per-stage prompt; {@code handleLLMMode} does not).</p>
 *
 * <p>Per-stage composition (fragment 01 §1-§8):</p>
 * <ul>
 *   <li><b>router</b> — preamble + Router body. (No catalog/Mode — pure classification.)</li>
 *   <li><b>discovery</b> — preamble + Discovery body + catalog + category guides.</li>
 *   <li><b>composer</b> — preamble + cross-cutting + ACTIVE MODE + Composer body + catalog + category guides.</li>
 *   <li><b>configure</b> — preamble + cross-cutting + ACTIVE MODE + Configure body.</li>
 *   <li><b>provision</b> — preamble + cross-cutting + ACTIVE MODE + Provision body + retained connector/punch-list.</li>
 *   <li><b>planner</b> — preamble + cross-cutting + ACTIVE MODE + Planner body + retained PLANNER_PACKET.</li>
 *   <li><b>responder</b> — preamble + Responder body.</li>
 * </ul>
 */
@Component
public class StagePromptAssembler {

    private final ChatService chatService;
    private final BlueprintCatalogBlock catalogBlock;
    private final ActiveModeBlock activeModeBlock;

    @Autowired
    public StagePromptAssembler(ChatService chatService,
                                BlueprintCatalogBlock catalogBlock,
                                ActiveModeBlock activeModeBlock) {
        this.chatService = chatService;
        this.catalogBlock = catalogBlock;
        this.activeModeBlock = activeModeBlock;
    }

    /**
     * Assemble the system prompt for {@code stage} on this turn. The live per-turn
     * CONTEXT (datasets/domains/pipeline/session facts) is appended via
     * {@link ChatService#buildTurnContextPrompt} so context injection is reused,
     * not duplicated.
     */
    public String assemble(NodeLlmAdapter.Stage stage, String tenantId, String pipelineId, String sessionId) {
        String liveContext = chatService.buildTurnContextPrompt(tenantId, pipelineId, sessionId);
        return assemble(stage, liveContext);
    }

    /** Assemble against an already-built live-context block (visible for tests). */
    public String assemble(NodeLlmAdapter.Stage stage, String liveContext) {
        StringBuilder sb = new StringBuilder();
        sb.append(SharedPreamble.identity()).append('\n');

        switch (stage) {
            case ROUTER -> {
                sb.append(StagePrompts.ROUTER);
            }
            case DISCOVERY -> {
                sb.append(StagePrompts.DISCOVERY).append('\n');
                sb.append(catalogBlock.render()).append('\n');
                sb.append(CategoryGuides.all());
            }
            case COMPOSER -> {
                sb.append(SharedPreamble.crossCutting()).append('\n');
                sb.append(activeModeBlock.render()).append('\n');
                sb.append(StagePrompts.COMPOSER).append('\n');
                sb.append(catalogBlock.render()).append('\n');
                sb.append(CategoryGuides.all());
            }
            case CONFIGURE -> {
                sb.append(SharedPreamble.crossCutting()).append('\n');
                sb.append(activeModeBlock.render()).append('\n');
                sb.append(StagePrompts.CONFIGURE);
            }
            case PROVISION -> {
                sb.append(SharedPreamble.crossCutting()).append('\n');
                sb.append(activeModeBlock.render()).append('\n');
                sb.append(StagePrompts.PROVISION).append('\n');
                sb.append(SharedPreamble.provisionRetained());
            }
            case PLANNER -> {
                sb.append(SharedPreamble.crossCutting()).append('\n');
                sb.append(activeModeBlock.render()).append('\n');
                sb.append(StagePrompts.PLANNER).append('\n');
                sb.append(SharedPreamble.plannerRetained());
            }
            case RESPONDER -> {
                sb.append(StagePrompts.RESPONDER);
            }
        }

        sb.append("\n\n=== CURRENT CONTEXT (tenant / pipeline / datasets / session) ===\n");
        sb.append(liveContext);
        return sb.toString();
    }
}
