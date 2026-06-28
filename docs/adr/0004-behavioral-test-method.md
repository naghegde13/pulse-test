# Behavioral tests, authored separately from the implementer, checked by an independent evaluator

Status: accepted

PULSE work is validated by **behavioral tests** that describe what PULSE must *do* — given input X, the running output must be Y — expressed independently of the codebase, not by tying tests to internal class structure. For code-generation work this is natural and strong: run the generated code against sample data and assert on the output table.

Three roles are kept separate to prevent an agent from writing code that merely passes its own tests:
1. **Spec author** (the operator + orchestrator, via grill-with-docs) writes a deep, unambiguous spec plus the behavioral tests.
2. **Blind implementer agent** receives the spec but **not** the behavioral tests or oracle, and writes the code.
3. **Independent evaluator agent** holds the behavioral tests, runs the implementer's code against sample data, and returns pass/fail with evidence.

No change is "done" until the evaluator passes it on the behavioral tests. Conventional unit/integration tests are still welcome, but the behavioral test is the contract.

Trade-off: this is more setup than "the implementer writes its own tests," and it requires runnable sample fixtures. Accepted because the operator has repeatedly been burned by code that looked right, claimed to work, and didn't — separating author/implementer/evaluator and judging by observable behavior is the defense against that.

## Refinement (2026-06-14): behavioral tests are SCENARIOS, not module tests

A behavioral test is a **scenario** — a whole pipeline journey, end-to-end
(input X → running output Y). **Scenarios map ACROSS modules, not to a single
module.** So module-level work (a Builder format change, a deploy client, an LLM
transform body) is **not** behaviorally tested in isolation — it is validated when
a **scenario that exercises it runs.**

Corollary: until enough integrated functionality exists for a real scenario to run
end-to-end, a given module may have **no behavioral test to apply yet** — and that
is the *correct* reason to defer it, **not** a license to skip verification
(unit-level checks + the ADR 0010 zero-fuzziness spec gate still apply). Example
(2026-06-13): G1/G2/S3 are module-level; their behavioral tests are the deferred
**GCP-run scenario** (G1/G2) and the **smart-Builder silver scenario** (S3).
