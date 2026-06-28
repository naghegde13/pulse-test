# SPEC-GATE — the named zero-fuzziness gate (run before ANY spec or handoff is dispatched)

> **What this is.** The single, named gate every build spec and every session handoff must pass before any agent writes code. It **replaces the ad-hoc "guess-detector"** — same intent, now a fixed artifact so **Claude, Codex, and Droid all run the *same* check.** Wherever older docs say "guess-detector," they mean THIS.
>
> **How it runs:** as a **FRESH, INDEPENDENT agent — never the spec's author** (producer ≠ verifier, ADR 0008). **Empty findings across all four lists = PASS → dispatch. Any finding = grill that gap to concrete, then re-run until empty.**
>
> **Provenance (not invented here).** The detection taxonomy is reproduced **VERBATIM from GitHub Spec Kit (MIT)** — its `/clarify` ambiguity taxonomy and its `/analyze` six detection passes + severity levels (the quoted blocks in the GATE PROMPT below carry the exact text, with source file + line numbers). We changed only the *delivery model*: Spec Kit's `/clarify` asks the **user** ≤5 interactive questions; our gate runs against a **finished** spec and returns a **machine-checkable findings list** (no human mid-loop) because our specs are dispatched to **blind autonomous implementers**. Two PULSE-specific adaptations: "constitution" = **our ADR series** (not a `constitution.md`); we add a **deterministic-oracle** coverage check (ADR 0009) that Spec Kit has no equivalent for. Sources (raw): `github/spec-kit` → `templates/commands/clarify.md` + `templates/commands/analyze.md`. We also use **EARS** (Mavin/Rolls-Royce, IEEE RE'09) for contract phrasing — a standard, not Spec-Kit-specific.

## How to run (the ritual)
1. The spec author hands the finished spec **+ the ADRs/files it cites** to a **different** agent (Agent tool / Codex / Droid).
2. That fresh agent runs the **GATE PROMPT** below against the spec, reading the cited ADRs/files/code **cold**.
3. It returns the four findings lists (the 4th = OMISSIONS, the completeness/coverage-vs-scope pass). **Empty = PASS → dispatch.** Otherwise grill the named gaps to concrete and re-run (repeat until empty).
4. **Log the result** to the trial scorecard in `docs/SPEC-DISCIPLINE.md` (what the gate caught, or "clean").

Claude convenience: `/spec-gate <spec path>` (it dispatches the fresh agent for you — see `.claude/commands/spec-gate.md`). The slash command is Claude-only; the GATE PROMPT below is the portable, agent-agnostic source.

---

## GATE PROMPT (paste to a fresh agent; set {SPEC} = the spec/handoff under test)

You are a fresh, skeptical **SPEC-GATE**. You did NOT write {SPEC}. Read it cold, plus every ADR / file / migration it cites. Your only job: prove whether an autonomous implementer could **build, verify, and behaviorally-test** it **without guessing**. Do NOT critique strategy, priorities, or taste — only fuzziness, contradiction, and unresolved references.

**A. Ambiguity / underspecification scan.** Scan the spec against the taxonomy below; for EACH category mark **Clear / Partial / Missing**, and for Partial/Missing state exactly what an implementer would have to GUESS. The taxonomy is reproduced **VERBATIM from GitHub Spec Kit `templates/commands/clarify.md` (lines 73–121):**

> Functional Scope & Behavior:
> - Core user goals & success criteria
> - Explicit out-of-scope declarations
> - User roles / personas differentiation
>
> Domain & Data Model:
> - Entities, attributes, relationships
> - Identity & uniqueness rules
> - Lifecycle/state transitions
> - Data volume / scale assumptions
>
> Interaction & UX Flow:
> - Critical user journeys / sequences
> - Error/empty/loading states
> - Accessibility or localization notes
>
> Non-Functional Quality Attributes:
> - Performance (latency, throughput targets)
> - Scalability (horizontal/vertical, limits)
> - Reliability & availability (uptime, recovery expectations)
> - Observability (logging, metrics, tracing signals)
> - Security & privacy (authN/Z, data protection, threat assumptions)
> - Compliance / regulatory constraints (if any)
>
> Integration & External Dependencies:
> - External services/APIs and failure modes
> - Data import/export formats
> - Protocol/versioning assumptions
>
> Edge Cases & Failure Handling:
> - Negative scenarios
> - Rate limiting / throttling
> - Conflict resolution (e.g., concurrent edits)
>
> Constraints & Tradeoffs:
> - Technical constraints (language, storage, hosting)
> - Explicit tradeoffs or rejected alternatives
>
> Terminology & Consistency:
> - Canonical glossary terms
> - Avoided synonyms / deprecated terms
>
> Completion Signals:
> - Acceptance criteria testability
> - Measurable Definition of Done style indicators
>
> Misc / Placeholders:
> - TODO markers / unresolved decisions
> - Ambiguous adjectives ("robust", "intuitive") lacking quantification

*PULSE mapping: "Canonical glossary terms" = `CONTEXT.md`. Treat each Partial/Missing category as a GUESS in list (1) below.*

**B. Cross-artifact analysis passes.** Run each pass and assign severity. Reproduced **VERBATIM from GitHub Spec Kit `templates/commands/analyze.md` (detection passes lines 117–149; severity lines 155–158):**

> #### A. Duplication Detection
> - Identify near-duplicate requirements
> - Mark lower-quality phrasing for consolidation
>
> #### B. Ambiguity Detection
> - Flag vague adjectives (fast, scalable, secure, intuitive, robust) lacking measurable criteria
> - Flag unresolved placeholders (TODO, TKTK, ???, `<placeholder>`, etc.)
>
> #### C. Underspecification
> - Requirements with verbs but missing object or measurable outcome
> - User stories missing acceptance criteria alignment
> - Tasks referencing files or components not defined in spec/plan
>
> #### D. Constitution Alignment
> - Any requirement or plan element conflicting with a MUST principle
> - Missing mandated sections or quality gates from constitution
>
> #### E. Coverage Gaps
> - Requirements with zero associated tasks
> - Tasks with no mapped requirement/story
> - Success Criteria requiring buildable work (performance, security, availability) not reflected in tasks
>
> #### F. Inconsistency
> - Terminology drift (same concept named differently across files)
> - Data entities referenced in plan but absent in spec (or vice versa)
> - Task ordering contradictions (e.g., integration tasks before foundational setup tasks without dependency note)
> - Conflicting requirements (e.g., one requires Next.js while other specifies Vue)
>
> **Severity:**
> - **CRITICAL**: Violates constitution MUST, missing core spec artifact, or requirement with zero coverage that blocks baseline functionality
> - **HIGH**: Duplicate or conflicting requirement, ambiguous security/performance attribute, untestable acceptance criterion
> - **MEDIUM**: Terminology drift, missing non-functional task coverage, underspecified edge case
> - **LOW**: Style/wording improvements, minor redundancy not affecting execution order

*PULSE mapping (our model = a one-page spec + ADRs + a deterministic oracle; no separate `plan.md`/`tasks.md`): "constitution" = our locked **ADRs** (pass **D** = conflict with any ADR; a MUST conflict = CRITICAL). Pass **E** "coverage" = every contract sentence has a **deterministic oracle / behavioral test** (ADR 0009 / ADR 0004) and no orphan oracle. Pass **C** "tasks referencing undefined files/components" = the spec referencing undefined files/components.*

**C. EARS check** — are the normative/contract sentences phrased in EARS (`WHEN <trigger> / WHILE <state>, the <system> SHALL <response>`, + ubiquitous / optional / unwanted-behavior variants)? Flag any normative requirement not testable as written.

**D. Reference resolution** — every cited file path, ADR number, migration V-number, branch, commit, and `file:line` must resolve. **Verify with Read/Grep/Bash.** List any that don't, with what you found instead.

**E. Completeness vs scope (the OMISSION pass — A–D only check what is WRITTEN; this checks what is MISSING).** Passes A–D find guesses, contradictions, and dangling refs *in the content that exists* — they are structurally blind to whole topics the spec OMITTED (an absent section has no ambiguity to flag and no orphan to detect). So:
> - Read the spec's declared **Scope / Coverage basis** and **every source / yardstick / constitution document it cites** (ADRs, referenced architecture / decomposition / research docs, the op vocabulary, etc.).
> - For each requirement, op, component, contract, behavior, failure mode, emission path, or section those sources establish as in-scope, verify the spec **actually specifies it**.
> - List everything **required-by-scope/source but ABSENT** from the spec — the missing topic, where the source mandates it, and what's not there.
>
> A spec that silently drops a required item (an emission path, a fix-item, a failure mode, a per-stage prompt, a Mode-flavor) PASSES A–D while being incomplete. **This pass is the only one that catches it** — run it with the source docs open, item by item.

*PULSE note: the yardstick is the spec's declared **"Coverage basis"** (the exact requirements it must fully cover — e.g. "all 32 ops × 5 emission types", "all 12 fix-items"). **Distinguish two kinds of cited source:** a **requirement/yardstick source** (the spec MUST cover it — the op vocabulary, the fix-items, the ADRs) vs a **pattern/inspiration source** (an external tool's architecture the spec ADAPTS, where PULSE differs BY DESIGN — e.g. n8n). For a pattern source, run it **AS the yardstick** and require the spec to **DISPOSITION every capability** the pattern has, one by one: **Adopted** (cite where in the spec) · **Deferred** (with a rationale + when) · **Ignored** (with a rationale why PULSE doesn't need it). An **UNDISPOSITIONED** capability — neither adopted nor consciously deferred/ignored — **IS a finding** (a conscious, reasoned decision is required; a silent non-coverage is exactly the omission this pass exists to catch). A deliberate, *rationalized* divergence is NOT an omission; an un-reasoned absence IS. The spec SHOULD carry this disposition table (e.g. an "n8n capability dispositions" section) so the accounting is explicit — every pattern capability is consciously adopted, deferred-with-reason, or ignored-with-reason, never silently dropped. If no Coverage basis is declared, infer the requirement yardstick from the Scope + the cited ADRs/requirement-docs.*

**RETURN EXACTLY FOUR LISTS, nothing else:**
- **(1) GUESSES** — what an implementer would have to guess (from A + B3 + C). Each: the gap · where · which category/pass · severity.
- **(2) CONTRADICTIONS** — from B1/B2/B4/B6. Each: `file:line` · quoted text · severity.
- **(3) DANGLING REFERENCES** — from D. Each: the citation · what you found.
- **(4) OMISSIONS / COVERAGE GAPS** — from E. Each: the required item · where the scope/source mandates it · what's absent · severity (a dropped MUST-cover item = CRITICAL/HIGH).

Write **"(none)"** under a heading if clean. Cite `file:line` and quote offending text for every finding. No padding, no style nitpicks, no suggestions, no fixes — report only. **EMPTY all four = the spec PASSES the gate.**
