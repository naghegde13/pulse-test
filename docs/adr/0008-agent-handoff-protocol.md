# Every agent handoff: isolated worktree, implementer evidence doc, independent evaluator

Status: accepted

## Context

Work is farmed to many agents in parallel — external (Codex, Droid) and Claude.
The operator has repeatedly been burned by agents reporting "done" on work that
didn't actually work. ADR 0004 established behavioral tests with separated
author / implementer / evaluator roles; this ADR makes the **handoff mechanics**
concrete and **mandatory for every handoff**, external or Claude.

## Decision

Every time work is handed to another agent, it follows this protocol — no
exceptions:

1. **Isolation.** The implementer works in its **own git worktree on its own
   branch** (a peer folder, e.g. `../pulse-g1`), never the shared working tree.
   Parallel agents never share a tree.
2. **Spec, not oracle.** The implementer receives a self-contained spec
   (Task + Constraints) — enough to know what "correct" means — and is told **not**
   to read the behavioral-test/oracle section. The work is judged by observable
   behavior, not by its own report.
3. **Implementer evidence doc.** Before claiming done, the implementer writes
   `docs/evidence/<lane>-EVIDENCE.md` in its branch: every file changed + why, the
   exact build/compile command run + its result, any local checks run on the
   output, and how to reproduce. **This is the implementer's CLAIM.**
4. **Independent evaluator.** The orchestrator spawns a **fresh Claude evaluator
   agent** that checks out the implementer's branch, **re-runs the behavioral test
   against sample data with its own eyes**, and returns PASS/FAIL + evidence. The
   evaluator does **not** trust the evidence doc — it re-runs everything.
5. **Merge gate.** Nothing merges until the evaluator passes it. The orchestrator
   records the verdict.

This applies to **Codex, Droid, AND Claude** agents — every handoff, every time.

## Considered

- **Trust the implementer's evidence doc** — rejected: that is the exact failure
  mode that has burned the operator.
- **Orchestrator evaluates inline** — rejected: the orchestrator authored the
  spec; a separate fresh evaluator is more independent and runs in parallel.

## Trade-off

More agents + worktrees + an evaluation pass per handoff = more tokens and setup.
Accepted: it is the only defense against "looked done, wasn't" at multi-agent
scale, and external (Codex/Droid) tokens are the abundant resource right now.
