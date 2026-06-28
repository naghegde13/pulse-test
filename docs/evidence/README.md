# Agent-handoff evidence (ADR 0008)

Each farmed-out lane's **blind implementer** writes its CLAIM here as `<lane>-EVIDENCE.md`
(what it did + how it verified). An **independent evaluator** then re-runs the behavioral
test and records the verdict. **Merge only on evaluator PASS.**

The oracle / answer-key stays in a **separate evaluator-only file** — never in the spec
the implementer reads (ADR 0004 / ADR 0008; a prior agent saw the oracle and gamed it).

_(This directory is referenced by the operating rules; created 2026-06-14 so the path exists.)_
