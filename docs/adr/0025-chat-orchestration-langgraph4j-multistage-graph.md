# Chat orchestration — a LangGraph4j multi-stage StateGraph with per-stage model assignment

Status: accepted 2026-06-16 (operator + orchestrator). Resolves `SPEC-ui-composition.md` §7.16 #1 (staged-vs-single-loop) and §7.16 #2 (per-stage model matrix). Builds on ADR 0013 (the Builder is deterministic; the LLM is Chat-side only) and the #3 chat-prompt fragments (the 7-stage model, grounded in n8n's real LangGraph source). Supersedes the §7/fragment-07 **default** of Option B (phase-gated single loop).

## Context

PULSE Chat today is a single OpenRouter/Vertex `/chat/completions` **tool-loop** bounded by `MAX_TOOL_ROUNDS` (`ChatService.java`), with phase-gated prompt assembly (it already swaps prompt packets by `ConversationPhase`). The #3 design locks a **7-stage model** (Router → Discovery → Build/Composer → Configure → Provision → Planner → Responder, `D1-FEEDBACK-CHANGELIST.md` §F), authored against n8n's **actual** builder source — a LangGraph `StateGraph(ParentGraphState)` compiled `.compile({ checkpointer })`, with the plan gate as an `interrupt()` and deterministic re-routing from a coordination log (fragment `07-orchestration-revert-layout.md`).

The open question (§7.16 #1) was **how** to realize the 7 stages:
- **Option A** — build them as real graph nodes (orchestration layer + shared typed state + conditional edges + an interrupt-equivalent at the plan gate).
- **Option B** — keep the one tool-loop; the stages are prompt-assembly + tool-gating MODES over it (the smaller delta; the prior default).

The deciding facts, established 2026-06-16:
- PULSE's backend is **Java/Spring Boot**, so n8n's `@langchain/langgraph` (TypeScript) is not directly usable. **LangGraph4j** (`langgraph4j/langgraph4j`, Java, v1.8.x) is a mature port that provides `StateGraph` (nodes, conditional edges, **subgraphs**), a **checkpointer** with a ready `langgraph4j-postgres-saver`, `interruptBefore`/`interruptAfter` for human-in-the-loop, `getState`/`updateState`/resume/**time-travel**, **threads** for multi-session, and Mermaid/PlantUML visualization. It integrates with LangChain4j / Spring AI.
- PULSE already runs **Postgres**, so the LangGraph4j Postgres checkpointer is a drop-in for durable, resumable session state.
- PULSE must **scale to thousands of data engineers**. That is a *throughput* concern (solved by horizontal backend scaling — identical for a loop or a graph), but durable/resumable/inspectable **session state** at that scale is real ongoing value, and **per-stage model assignment** is a real ongoing **cost** lever.

## Decision

1. **Chat orchestration is a LangGraph4j `StateGraph` (Option A), not the single tool-loop.** The 7 stages are graph nodes (with a supervisor/router node and deterministic re-routing); the shared `ParentGraphState` analogue is a LangGraph4j `AgentState`; the Plan-Preview approval gate is an `interruptBefore` on the apply node; per-turn snapshot/restore is the **Postgres checkpointer** (`langgraph4j-postgres-saver`). This makes the #3 implementation map **~1:1** onto the n8n reference source we grounded the design on (`ParentGraphState`→`AgentState`, `interrupt`→`interruptBefore`, `MemorySaver`→`PostgresSaver`, the discovery-subgraph→a LangGraph4j subgraph), which **lowers** design risk rather than raising it.

2. **Per-stage model assignment (the cost lever; n8n `stageLLMs` analogue), resolving §7.16 #2.** Each node declares its own model:
   - **Cheap tier (a Flash-class chat model — e.g. `gemini-2.5-flash` — wired under a NEW dedicated chat-stage config key):** Router, Discovery, Configure, Provision, Responder.
   - **Reasoning tier (`pulse.llm.model` — the primary chat model, e.g. `gemini-3.1-pro-preview`):** Build/Composer, Planner.
   A node MAY escalate to the reasoning tier on a flagged hard case, but the defaults above are the cost-optimized baseline. At thousands of engineers, putting the cheap model on routing/structured-params/reporting and the reasoning model only on composition + plan synthesis is a large, recurring saving — and it is cleaner per-node than swapping models mid-loop.
   - **The cheap tier MUST NOT reuse `pulse.schema-inference.model` / `pulse.llm.vertex.schema-model`.** ADR 0011 retired **model-based schema inference** (schema inference is now 100% deterministic, zero-LLM), so those keys are **dead** and are NOT chat-stage models. The chat cheap tier is a separate, new config key (the existing `gemini-2.5-flash` value may be reused, but under a chat-stage key, not the schema-inference one).

3. **The checkpointer IS the snapshot mechanism.** The already-locked **undo = restore snapshot** (no inverse-plan commands) falls out of the LangGraph4j checkpointer's `getState`/restore/time-travel — the per-turn canonical snapshot and the staging clone are checkpoints, and "revert" is a checkpoint restore. No separate `chat_turn_snapshots` machinery is required beyond the checkpointer's Postgres tables (the §7.16 #4 snapshot-store GUESS resolves to the checkpointer's own schema).

4. **The LLM seam stays PULSE's.** LangGraph4j targets LangChain4j / Spring AI, but PULSE calls models through its own `LlmEndpointService`/Vertex seam (impersonation auth, thought-signature preservation, structured tool-call replay — ADR-tracked). A **thin adapter** lets graph nodes invoke PULSE's Vertex path; LangGraph4j is the orchestration layer ONLY, not the model client. OpenRouter switchability and the Vertex thought-signature/tool-replay guarantees are preserved.

5. **Milestone discipline protects the rescue.** The graph is built **incrementally** with "**first pipeline composed + built end-to-end**" as the gating milestone — not a big-bang orchestration rewrite before any pipeline works.

## Consequences

- §7.16 #1 resolves to **Option A**; §7.16 #2 resolves to the per-stage matrix above; §7.16 #4 (snapshot store) resolves to the checkpointer schema. `SPEC-ui-composition.md` §7 and `fragment 07` flip their **default from B to A** and name LangGraph4j as the realization; the shared-state-carrier / interrupt / checkpointer GUESSes are concretized to LangGraph4j types (`AgentState`, `interruptBefore`, `PostgresSaver`) rather than left abstract.
- A new backend dependency: `langgraph4j-core` + `langgraph4j-postgres-saver` (Gradle). A thin **node→`LlmEndpointService`** adapter is a build task.
- The IMPL plan for #3 gains an orchestration-layer phase (StateGraph wiring + the Vertex adapter + the Postgres checkpointer), kept behind the end-to-end milestone.

## Considered

- **Option B — phase-gated single loop (the prior default), keep the one tool-loop.** Rejected: the deciding objection (hand-rolling a Java state machine + checkpointer + interrupt) is moot now that LangGraph4j provides them; B forgoes durable/resumable session state and time-travel debugging, makes per-stage models messier (swap mid-loop), and would likely force a graph migration later anyway as PULSE grows. B remains the correct *fallback* if LangGraph4j is later deemed unfit.
- **Hand-rolled Java state machine** (graph semantics without the library). Rejected: re-implements `StateGraph`/checkpointer/interrupt that LangGraph4j already ships, for no gain.
- **Choosing the graph for throughput/scale.** Rejected as a *reason*: throughput at thousands of users is horizontal backend scaling, identical for loop or graph. The graph is chosen for durable session state, per-stage cost control, and 1:1 fidelity to the n8n reference — not raw throughput.

## Trade-off

We take a **community-maintained library** (`langgraph4j`, Apache-licensed, active — not an official LangChain product) as a core-orchestration dependency, in exchange for the graph machinery (StateGraph + Postgres checkpointer + interrupt + per-stage models + visualization) without hand-rolling it, and a near-1:1 match to the n8n source the #3 design is grounded in. The bet is bounded: the `StateGraph` concepts are standard and portable, so a worst-case future migration is a refactor of the orchestration layer (the stages, tools, and prompts are unaffected), not a rewrite — which is also exactly the Option-B fallback path.
