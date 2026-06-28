# Spec Discipline — TRIAL (evaluate at the end of the next session)

> **Status: TRIAL for the next session.** This is PULSE's lightweight discipline for
> producing **zero-fuzziness specs** (ADR 0010) without heavy-framework ceremony.
> We run it as a trial next session, then **EVALUATE it at the end of that session
> and decide: make it permanent for the project, or not** (see "Evaluation" below).
>
> Chosen *over* adopting GitHub Spec Kit / BMAD / OpenSpec wholesale: the *tool*
> doesn't produce zero-fuzziness — the *discipline* does — and three frameworks is
> real ceremony risk (Spec Kit has ~90k stars yet teams still ship fuzzy specs).

## The bar (ADR 0010)

A spec is complete **only if** an autonomous agent can **(1) code it**, **(2) verify
it**, and **(3) behavioral-test it across features** (behavioral tests are
*scenarios* — cross-module, not module-level — ADR 0004). **If an agent has to
guess *anything* — the architecture, a contract, a boundary, a name, a data
shape — the spec is not complete.** Zero guessing.

## The discipline (5 steps)

1. **ADRs are the single "constitution."** Rules + decisions live in `docs/adr/`
   (+ the PULSE-MAP rules of the road). One source of truth — never a duplicate.

2. **One-page spec template.** Every spec has these sections, all concrete:
   - **Inputs** — exact data in, with shapes/types.
   - **Outputs** — exact data out / the **deterministic oracle** (expected result,
     byte-exact where applicable — ADR 0009).
   - **Boundaries** — the exact contract with *each* adjacent component (what this
     owns vs what it receives / hands off).
   - **Constraints** — Mode rules, determinism, config-externalization, forbidden
     things.
   - Keep it one page. If a section can't be made concrete, the spec isn't ready —
     grill it.

3. **grill-with-docs** — author + interrogate the spec against the domain model
   (operator + orchestrator). The domain depth; also keeps ADRs/CONTEXT current.

4. **EARS phrasing** — write requirement / acceptance sentences as
   **"WHEN \<trigger\> THE SYSTEM SHALL \<response\>"** so each contract reads
   exactly one way. A writing style, no tooling.

5. **THE GATE — the named SPEC-GATE** (`docs/spec-gate/SPEC-GATE.md`; run `/spec-gate <path>`).
   Before *any* dispatch (Codex/Droid/Claude) or any code: hand the finished spec to a
   **fresh, independent agent** (producer ≠ verifier, ADR 0008) that runs the SPEC-GATE
   prompt and returns **GUESSES / CONTRADICTIONS / DANGLING-REFERENCES.**
   - **All three empty → the spec is zero-fuzziness; dispatch.**
   - **Any finding → grill exactly those gaps, fix the spec, run the gate again.**
   - **Empty, or it doesn't ship.** This is the automated form of ADR 0010's
     "did an agent have to guess?"
   - **Provenance:** the gate's detection taxonomy is **adapted from GitHub Spec Kit's
     `/clarify` (10-category ambiguity scan) + `/analyze` (6 cross-artifact passes +
     severity)** — we cherry-picked the categories, not the framework or its CLI; "constitution"
     = our ADR series; we add a deterministic-oracle coverage check (ADR 0009). This is the
     formalization of the old hand-rolled "guess-detector."

## Trial scorecard — FILL IN DURING the session (one row per spec, as you go)

This is how the trial actually runs and how the Evaluation below gets data. Log every
spec authored this session. **An empty scorecard at exit means the discipline was NOT
trialed — a visible failure**, since running it on real specs is the whole point.

> **TRIAL OPENED — session 2026-06-14 (orchestrator).** The discipline is live this
> session. Every spec authored for dispatch gets a row below as it is authored; the
> end-of-session Evaluation reads these rows. (G1/G2 *evaluations* are not rows here —
> they are the ADR-0008 independent-evaluator step on already-written code, not specs
> dispatched to a blind implementer.) Rows are empty so far — no implementer spec has
> been authored yet this session.

| spec / lane | template used? | grilled? | EARS? | SPEC-GATE run? | what the gate caught (the key column) | dispatched? |
|---|---|---|---|---|---|---|
| Session handoff + locked decisions (PULSE-MAP resume entry + ADRs 0011/0012/0013 + 0003-refinement + decomposition doc + grill doc) | n/a (handoff) | yes (operator grill) | n/a | **YES → FAIL** | **Caught: (1)** `sql-model` claimed by ADR 0013 but absent from ADR 0012's vocabulary + decomposition (build-blocking) — **FIXED**; **(2)** op count "~28" vs enumerated 31 (+`sql-model`=**32**) — **FIXED**; **(3)** stale LLM-Builder framing still live across PULSE-MAP / grill-doc top half / CLAUDE.md → next-session sweep; **(4)** per-op build-spec details under-specified — correctly next-phase (each gated). Refs all resolved; resume point clear; no hidden rot. | n/a (resume spec) |
| Builder-compiler spec (`docs/build-specs/SPEC-builder-compiler.md` + `-CONTRACTS.md`: §A metadata model, §B 32 ops' schema rules, §C emission handlers, §D V153 migration, §E oracle) | yes | yes (grill log in SPEC) | yes (EARS in §A–E, clean) | **YES → FAIL** | **15 GUESSES + 3 CONTRADICTIONS + 0 DANGLING.** Fresh verifier confirmed all 14 of the draft's self-flagged GUESS/contradiction items are REAL, and found **3 it MISSED**. Blockers: **C-1 (CRITICAL)** bronze audit-column count 8-vs-7 — `IngestionAuditColumns.NAMES`=7 (no `created_as_timestamp`) breaks the 86-col anchor oracle; **G-1/G-4 (CRITICAL/HIGH)** `schema_behavior` JSON-key spelling + nested-type encoding invented (today's `schema_behavior` holds only `{effect_type,conflict_policy}`) — blocks V153 + every op-list answer key; **G-11/G-12 (HIGH/CRITICAL)** silver "87-col" ungrounded vs oracle's 78, and which op yields the 290-row silver is ambiguous (Cleaning has no `loan_status` filter) — blocks silver answer key; **G-7/G-13 (CRITICAL/HIGH)** Calcite whole-model Phase-2 validator and DPC/Livy Mode-emission branch are unbuilt prerequisites, not applies; **C-2 (HIGH)** DPC table format contradicts ACROSS the constitution (ADR 0001 Hive+Parquet vs ADR 0007 Delta=DPC; draft mis-attributes "Delta" to 0001); **C-3/G-14 (MEDIUM)** ADR 0011 still literally mandates a "bounded repair regeneration" MUST that the spec drops (only 0013-supersedes-0002 reconciles it, not 0011); **G-15 (MEDIUM)** fix-item #9 claimed folded-in at SPEC:131 but has zero treatment in CONTRACTS (OP-VOCAB:82 routes it to blueprint backlog). 32-op coverage complete; 11/12 fix-items addressed; **all references resolved (0 dangling)**, incl. V151 confirmed head (no V152/V153 collision). Output = resolution worklist; grill C-1/G-1/G-4/G-11/G-12 to concrete + reconcile C-2/C-3 then re-run. | **NO** (gate FAIL) |

> **EVALUATION (2026-06-15, CORRECTED): the discipline DOES apply now.** The handoff **is** the next session's spec, and the ADRs are spec-like (operator: "sticking to the discipline is non-negotiable"). An earlier draft here wrongly excused it as "design session, nothing to gate" — that was the exact **gate-your-own-handoff** failure mode; corrected. So the **guess-detector gate is RUN this session** on the spec-like artifacts: the PULSE-MAP resume handoff + the locked decisions (ADRs 0011/0012/0013 + 0003-refinement + `docs/blueprints/OP-VOCABULARY-AND-DECOMPOSITION.md` + the grill doc). Result logged in the scorecard below. The full per-spec discipline (template → EARS → guess-detector → scorecard) **also** applies to each build spec next phase.

The **"what the gate caught"** column is the evidence: if the guess-detector keeps
returning real gaps cheaply, the discipline earns "permanent"; if it's always empty or
costs hours, that's the case against it. Record honestly either way.

## Evaluation — DO THIS AT THE END OF THE NEXT SESSION

**Decide: make this discipline permanent for the project — Y / N?** Judge by:
- Did it produce **zero-fuzziness specs** (guess-detector returned empty before dispatch)?
- Did the guess-detector catch **real** gaps we'd otherwise have shipped?
- Was the **ceremony tolerable** — minutes, not hours; not process-for-its-own-sake?
- Did dispatched work come back **right** — fewer rework cycles than this session's
  fuzzy dispatches?

**If YES** → promote this doc from TRIAL to permanent (it becomes the standing method;
update the handoff to reference it as such). **If NO** → record what failed and what
to change, and decide what to keep.
