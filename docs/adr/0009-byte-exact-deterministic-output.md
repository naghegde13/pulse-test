# Generated code may vary; its data output must be byte-exact deterministic

Status: accepted

## Context

PULSE's Builder generates pipeline code, increasingly LLM-assisted (ADR 0002).
LLM-written code is non-deterministic in **form** — two generations may produce
different but valid SQL / PySpark. But PULSE is a **data platform**: a given
pipeline definition must produce the **same data** every run, and two developers'
equivalent implementations must produce **identical results**. If the data output
varies, that is a **defect** (an ambiguous transform or broken codegen) — not an
acceptable form of LLM non-determinism.

This is independent of the (still-being-grilled) form/skeleton architecture — it
holds for **any** generated transform code, in any form.

## Decision

The behavioral test for generated transform code is a **byte-exact deterministic
data oracle**:

- A **deterministic reference output** (the exact expected table) is authored by
  the spec authors, **independent of the LLM**, with **deterministic tiebreakers**
  (e.g. explicit `ORDER BY` for any dedup/ranking) so it is reproducible.
- The evaluator **generates the code 2–3 times** (separate LLM calls → possibly
  different code) and **runs each**; every output must be **byte-identical to each
  other AND to the reference oracle**.
- Any divergence = **FAIL = a real bug** (non-deterministic SQL such as a dedup
  with no tiebreaker, an ambiguous spec, or wrong logic). **Never tolerated, never
  loosened** to a fuzzy "shape + rules" check.
- Inherently-runtime metadata (ingestion timestamps, run ids — the audit columns)
  is **excluded/normalized** from the diff; the byte-exact check is on the
  **business data**.

## Considered

- A fuzzy **"shape + cleaning-rules" check** (assert columns / dedup-applied /
  types-coerced without byte-exactness) — **rejected**: it tolerates exactly the
  output non-determinism that, for a data platform, *is* the defect. (The
  orchestrator initially recommended this; the operator corrected it, 2026-06-13.)

## Trade-off

Requires authoring deterministic reference oracles + multiple generations per
test. Accepted because reproducible data output is non-negotiable for a data
platform. The line is: **the code may vary; the data must not.**
