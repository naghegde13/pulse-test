# The Builder is a deterministic op-composition compiler; the LLM is a Chat-side assistant, not a code generator

Status: accepted 2026-06-15 (operator + orchestrator). **Supersedes ADR 0002 (LLM-grounded Builder).** Builds on ADR 0011 (deterministic schema contract) + ADR 0012 (blueprint behavior = composable primitive ops).

## Context

ADR 0002 had the Builder use an LLM to write per-blueprint code bodies, grounded in examples, because per-blueprint deterministic templating "doesn't scale" — a hand-written generator per ~50 blueprints. **ADR 0012 changed the premise:** a blueprint's behavior is an ordered list drawn from a CLOSED set of 32 primitive ops. That dissolves ADR 0002's core objection — there are now 32 reusable op handlers, not ~50 per-blueprint generators. **Per-op templating scales; per-blueprint did not.** Combined with ADR 0011 (schema is deterministic, no LLM) and ADR 0012 (op set is closed, no free-form ops), the LLM has been pushed out of schema and out of structure — and op handlers push it out of the body too.

## Decision

1. **The Builder is a DETERMINISTIC op-composition compiler.** Each primitive op has a tested codegen handler emitting its fragment per emission type (dbt-SQL / PySpark / GX). A blueprint's code = the deterministic composition of its op-list's handlers. **No LLM writes codegen.** Output is byte-exact (ADR 0009) by construction — no non-determinism to wrestle.
2. **The LLM's only role is Chat-side ASSISTANCE — never code generation.** Chat (the LLM) helps the user: **(a)** compose the pipeline — arrange and wire blueprints on the design surface (NL → composition); **(b)** author expressions (derived-column formulas, filter clauses); **(c)** author raw SQL for the SQL-chaining path (the user may write SQL by hand OR ask Chat to draft it). In every case the LLM's output is **deterministically validated/compiled downstream** (Calcite for expressions/SQL; the op handlers for codegen). The LLM proposes; the deterministic pipeline disposes.
3. **Two authoring paths, both fully deterministic, no LLM in codegen:**
   - **Structured (citizen):** compose primitive ops.
   - **Power-user (Data Engineer):** the **SQL-chaining blueprint** — a chain of `sql-model` ops carrying user-authored dbt SQL. Output schema derived deterministically by Calcite-validating each SQL against its input schema (or declared). **Build prerequisite:** the Calcite validator/planner (the Expression Builder's "Phase 2" gap) or the declare path.
4. **The codegen-examples corpus** is no longer runtime LLM-grounding (ADR 0002's purpose) — it becomes reference material for the humans/agents authoring the 32 op handlers.

## Consequences

- ADR 0002 is **superseded.** The LLM-body / schema-repair-loop / forbidden-token-scan-on-LLM-output machinery it implied is **not built.**
- The Builder build splits into deterministic, byte-exact-testable units: the metadata-driven op engine (ADR 0012) + the 32 op handlers (per emission type) + the SQL-chaining / `sql-model` path (needs Calcite Phase-2 or declare).
- The LLM remains only at **Chat** (composition + authoring assistance), all downstream-validated. (`CONTEXT.md`'s "Chat drives the Designer, does NOT write code" now holds literally for codegen.)
- **`sql-model`** is added to the op vocabulary (ADR 0012): a closed op carrying user dbt SQL (analogous to `add-column` carrying a user expression, at whole-model scope).

## Considered

- **Keep ADR 0002 (LLM writes bodies)** — rejected: with a closed op set, per-op handlers are deterministic, testable, scale, and give byte-exact output for free; an LLM in codegen would reintroduce the non-determinism ADR 0009 forbids, for no benefit the op-model doesn't already provide. The power-user who wants free-form logic writes SQL directly (deterministic), optionally Chat-assisted.

## Trade-off

32 op handlers (× emission types) must be hand-authored and tested. Accepted — bounded, reusable across every blueprint, and deterministic; far better than an LLM codegen path that is non-deterministic, hard to test byte-exact, and unnecessary once behavior is a closed op set.
