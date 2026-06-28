# #3 Chat prompts — operator review feedback + change-list (2026-06-16)

Captured verbatim-intent from the operator's review of fragments 01/02/03. **Decision 2 = B** (full multi-agent
stage architecture). This change-list drives the revision of 01/02/03 + the #3 §7 stage model.

## A. Architecture decision
- **D2-arch = B.** Build the multi-agent **stage** architecture (separate specialist agents wired in a graph), NOT
  the single-loop / phase-gated default.

## B. Divergence-item dispositions (from 01 §0's D1–D10)
- **D1 (Discovery dual-mode):** PULSE Discovery is **DUAL-MODE** — both the ask-clarifying variant AND the
  `NEVER ask clarifying questions` variant (like real n8n). Revisit existing rules freely (full rearchitecture).
- **D2 (`ALWAYS call get_documentation first`):** **author the PULSE documentation** the rule injects (the
  best-practices/technique guides) so the pattern works.
- **D3 (n8n credential/placeholder rules):** **N/A to PULSE** (PULSE uses SecretRefs; drop the placeholder nuance).
- **D4 (use model name EXACTLY as provided):** **N/A — PULSE users do NOT make model choices** (model is
  system-config, Gemini/Vertex). Drop the anti-substitution rule.
- **D5 (progressive-batch):** **ADOPT** — the batch lifecycle (add-instance → set-params → wire-ports), good pattern.
- **D6 (`initialParametersReasoning` REQUIRED field):** **ADOPT** — the reasoning field on the mutation ops.
- **D7 (shared parameterized deictic helper):** **ADOPT** — one shared deictic helper, parameterized per stage.
- **D8 (six stages, parameterUpdater):** PULSE **DOES** have a param-update use case → **make it its OWN stage**.
  **REVISIT the 5-stage model** by design — map to n8n's stages, or use n8n's, or a mix. The param-update stage uses
  **STRUCTURED params (no sub-LLM)** — confirmed against the 02 feedback.
- **D9 (n8n `builder.prompt.ts` "may be gone" was wrong — it's live):** no PULSE action — it was only a correction
  to our *summary doc's* accuracy about an n8n file existing. (Explained to operator.)
- **D10 (Responder rules — report actual / never deploy-promote / no emojis + introspect + manual-setup honesty):**
  **ADOPT.**

## C. Section-level changes
- **Supervisor stages GAP:** the `build` stage is pipeline-composition ONLY. PULSE needs a stage (or stages) for
  building/configuring **SOR (Producer/ServiceInstance/Bindings), Domains, Connectors, Datasets** — the
  "helper/configuration objects." Likely a separate **entity-provisioning stage** (n8n's `configurator.prompt.ts` is
  the analogue). Pull the handling from the EXISTING PULSE system prompt.
- **Storage correction (was: `storage_backend` a pipeline-level default, ARCH-010 — WRONG):** storage is set by the
  **GLOBAL Mode variable (DPC vs GCP)**, not per-pipeline. Mapping: **GCP** → bronze + silver = BigQuery-managed
  Iceberg, gold = BigQuery **native**. **DPC** → bronze + silver + gold = **Hive + Parquet on S3**. (ADR 0001 Mode /
  ADR 0007 format.)
- **Planner = text + GRAPH:** keep the plain-language Plan Preview AND **render the pipeline graphically in the chat
  window** (see how the existing PULSE prompts/responses do this).
- **Composition RULES (Composer/Planner):** a **writer/sink at every medallion-layer boundary** (bronze→silver→gold),
  and a **DQ step after every write**.
- **set-params = structured (02):** confirmed — structured params object, no natural-language sub-LLM.
- **02 §4 row 5 (get_documentation techniques):** do **NOT** ignore — **author PULSE data-engineering technique
  guides** (the n8n "techniques" concept → PULSE techniques, in PULSE vocabulary), on top of the 5 category guides.
- **03-best-practices:** **approved as-is** ("looks good").

## D. Overall directive (applies to ALL of the above)
- Read the **existing PULSE system prompt** (`PulseSystemPrompt.java`) and **retain/incorporate** its coverage of:
  **SOR, Domain, Connector, Dataset, Sample Data / Schema Inference**, and other sensible pieces, into the rewritten
  multi-stage chat architecture.

## E. FINAL decisions (operator-confirmed 2026-06-16)
- **Discovery = SINGLE mode** (NOT dual). Ask a clarifying question ONLY on **material, plan-changing** ambiguity;
  else proceed to the Plan Preview (the human reviews there). No headless mode — PULSE is always human-in-the-loop;
  the never-ask mode's only hard justification (no human) doesn't apply. Optional: a user "be decisive / don't
  interrupt" preference knob (tunes eagerness — NOT a second agent).
- **Configure (and Build) stages INJECT per-blueprint configuration guidance** — param-by-param: what each param
  does, sensible defaults, gotchas, user-tier vs system-derived — sourced from #5's catalog metadata + the technique/
  best-practice guides. (PULSE analogue of n8n's `get_documentation` / `node_recommendations`.)
- **Mode injection** — the prompt-builder reads `RuntimeAuthorityService.getActivePersona()` (GCP vs DPC) and injects
  the active Mode + storage mapping into the system-prompt context (per-deployment CONSTANT, ADR 0001 — not a
  per-turn tool).

## F. LOCKED stage model (the revision target)
**Router → Discovery → Build/Composer → Configure → Provision → Planner → Responder** + cross-cutting shared helpers.
1. **Router/Supervisor** — route the engineer's intent → {discover, build, configure, provision, explain, plan-decision}.
2. **Discovery** — single judicious-ask: establish ground truth (composition, datasets/SORs, ports, upstream schema).
3. **Build/Composer** — compose/modify the PIPELINE (add instances, wire ports, set params); progressive-batch;
   `reasoning` field; one turn = one Command-Log transaction; per-blueprint config guidance.
4. **Configure** — change params/orchestration on an EXISTING step (no new structure); **STRUCTURED** set-params (no
   sub-LLM); per-blueprint config guidance.
5. **Provision** — build/configure the SOR entities (Producer/ServiceInstance/Bindings), Domains, Connectors,
   Datasets (incl. **sample-data / schema-inference**) — **RETAIN the existing `PulseSystemPrompt` coverage**; n8n
   `configurator` analogue.
6. **Planner** — Plan Preview = plain-language (summary/trigger/steps) **AND a GRAPHICAL pipeline rendering in chat**;
   no credentials; no blueprint-key names in user steps. Composition RULES: **sink at every medallion-layer boundary
   + DQ after every write**.
7. **Responder** — report ACTUAL composition; never deploy/promote (gated); no emojis; honesty rules.
Cross-cutting: shared parameterized **deictic helper** (D7); **Mode injection** (E); **structured set-params**;
**drop D3/D4**.

## Next step (proceeding now)
Revise 01 (the 7-stage prompts, pulling the existing-prompt SOR/schema-inference/sample-data pieces) + 02 (tools/
context: Mode tag, structured set-params, per-blueprint guidance, Provision tools) + 03 (add data-eng technique
guides) → assemble into #3 §7 → gate.
