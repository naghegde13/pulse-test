package com.pulse.chat.plan;

/**
 * ARCH-018 scope model. Chat tools advertise which scope they apply to so
 * the LLM can route requests deterministically. Scope is a chat-side
 * description; it is not persisted on Plan / Command rows.
 *
 * <ul>
 *   <li>{@link #DOMAIN} - operations rooted at a business domain (data
 *       source onboarding, dataset registration, sink target creation,
 *       pipeline creation). Note: {@code plan_create_pipeline} from
 *       DOMAIN scope with provisional refs is INTENTIONALLY DEFERRED until
 *       {@code PlanService} grows draft-ref aliasing infrastructure.</li>
 *   <li>{@link #PIPELINE} - operations against an existing pipeline:
 *       instance add / wire / params / DQ / orchestration policy.</li>
 *   <li>{@link #WORKSPACE} - operations against a developer workspace:
 *       file edits, generation, commit / push / PR, dev package build,
 *       version acceptance handshake. Backed by Codex's workspace API
 *       (ARCH-016).</li>
 * </ul>
 */
public enum ChatScopeKind {
    DOMAIN,
    PIPELINE,
    WORKSPACE;

    public String wire() {
        return name();
    }

    public static ChatScopeKind fromWire(String raw) {
        if (raw == null) return null;
        return ChatScopeKind.valueOf(raw.toUpperCase());
    }
}
