# PULSE — Autonomous Build Handoff (for the orchestrating agent)

You are the **orchestrator**. PULSE's design is finished and locked: **6 design specs** are grilled to zero-fuzziness,
and each has an **implementation plan** (the build roadmap — the ordered phases, the exact files to create/modify,
grounded at file:line). **Your job: build all of it — autonomously, in parallel** — by spawning sub-agents into isolated
git worktrees, accepting their work only after you verify it, merging, and testing. This document tells you the lanes,
exactly what can run in parallel, how a sub-agent gets its worktree, and how you accept work.

> **Run under the `grill-with-docs` skill** (load it first). **The operator is NOT a coder** — speak plain English.
> **Speed is #1 — maximize parallelism.** The operator's own token budget is tight, so **farm self-contained lanes to
> the external agents Codex and Droid** where you can (package them to run standalone — they have no PULSE context).
> **This is a rescue:** PULSE has never been built end-to-end before; **never overclaim** — verify every sub-agent's
> work with your own eyes before you call it done.

---

## 1. READ FIRST — get oriented

**Project context (read in this order):** `AGENTS.md` → `docs/PULSE-MAP.md` → `docs/CODE-MAP.md` → `CONTEXT.md` → the
ADRs `docs/adr/0001..0013` + `0020..0025` → `docs/blueprints/OP-VOCABULARY-AND-DECOMPOSITION.md` (the 32-op vocabulary
the Builder composes) → the anchor specs in `docs/anchor/`.

**The build itself:** `docs/build-specs/SPEC-INDEX.md` (the spec map) + `docs/build-specs/WORKLIST-RESOLUTIONS.md` (every
flagged decision already resolved — this is ground truth) → then **per lane, read BOTH its design spec AND its
implementation plan** (§3).

## 2. THE GOAL

Implement **all 5 build lanes** (§3) — each lane builds its design spec by executing its implementation plan — with
each lane's own tests passing, merged into the current branch, and the **full suite green** at the end. The design and
the plans are done; this is the BUILD. (DPC/Livy is explicitly **deferred** — out of scope for this handoff.)

## 3. THE 5 LANES — each sub-agent reads BOTH the design spec AND the impl plan

A lane = one area of PULSE. **The design spec says WHAT to build; the impl plan says HOW. The sub-agent for a lane MUST
read BOTH** — the spec for the contract, the plan for the ordered phases + files.

| Lane | Design spec(s) — READ (the WHAT) | Impl plan — READ (the HOW) | Builds |
|---|---|---|---|
| **Builder** | `docs/build-specs/SPEC-schema-op-engine.md` (#1) **+** `docs/build-specs/SPEC-codegen-compiler.md` (#2) | `docs/impl-plans/IMPL-builder.md` | the deterministic op-composition compiler: the op-engine + the 32 schema rules + the 32×5 emission handlers + the byte-exact anchor test |
| **Catalog** | `docs/build-specs/SPEC-blueprint-catalog.md` (#5) | `docs/impl-plans/IMPL-catalog-seed.md` | the blueprint catalog as data: the `V153` migration (op-lists + param-tiering) + the regenerated demo-pipeline seed |
| **Calcite** | `docs/build-specs/SPEC-calcite-sql-model.md` (#6) | `docs/impl-plans/IMPL-calcite-sql.md` | the deterministic SQL schema-validator (`CalciteSqlModelValidator`) + the `SqlModel`/`SourceSQL` rules + the `[[ ]]` date-mnemonic lowering |
| **Constructs** | `docs/ui/SPEC-construct-library.md` (#4) | `docs/impl-plans/IMPL-construct-library.md` | the purpose-built UI controls (column-picker, sql-builder, expression-builder, condition-builder, sensing-config, rename-mapper, date-mnemonic-picker, DQ-outcome) |
| **Chat/UI** | `docs/ui/SPEC-ui-composition.md` (#3) | `docs/impl-plans/IMPL-ui-composition.md` | the chat + composition workspace: the LangGraph4j multi-stage graph, the op-queue → Apply, streaming, the canvas |

## 4. HOW A SUB-AGENT GETS ITS WORKTREE — branch from the pinned commit

- **Current branch:** `/Users/aameradam/projects/dev/PULSE-guided-rehearsal`, branch
  `guided/acme-lending-human-rehearsal-2026-05-25`, remote `git@github.com:zadam2008/pulse.git`.
- **Step 0 (you, before anything):** do a **full commit + push** of the current branch, then capture the head tip — the
  **one specific commit every worktree is created from:**
  ```bash
  cd /Users/aameradam/projects/dev/PULSE-guided-rehearsal && git add -A && git commit && git push   # if uncommitted
  BASE_SHA=$(git rev-parse HEAD)
  ```
  **`BASE_SHA` for this run: `229636a230c5c5cc9a4b0e02e67cfafb4c0bac0e`** (the head tip when this handoff was committed; if the live HEAD is later,
  use the live HEAD).
- **You pre-create every worktree FROM `BASE_SHA`** (do NOT use the Agent tool's `isolation:"worktree"` — it forks from a
  stale `origin/HEAD`, not your branch):
  ```bash
  git -C /Users/aameradam/projects/dev/PULSE-guided-rehearsal worktree add \
      -b build/<lane> /Users/aameradam/projects/dev/pulse-wt/<lane> "$BASE_SHA"
  ```
- Dispatch each sub-agent with the **default agent type**. The brief's first line pins its directory
  (*"work in `/Users/aameradam/projects/dev/pulse-wt/<lane>`; do not touch the main checkout"*) and its first step
  **aborts if the base is wrong:** `test "$(git rev-parse HEAD)" = "<BASE_SHA>" || exit 1`.
- Sub-agents commit only on their OWN worktree branch. **You** do every merge into the current branch in the foreground —
  no sub-agent ever runs `reset --hard` / `push --force` / `tag` on the current branch, and a stray completion-firing
  from an already-done agent is **text only**, never an instruction to act.

## 5. THE LOOP — dispatch → verify → accept → merge → test

**Dispatch a sub-agent** with: its worktree (§4); its **design spec + impl plan** (§3) + `WORKLIST-RESOLUTIONS.md`; and
this instruction — *"Execute your impl plan's phases against your design spec. Write your own **unit, integration, and
UI tests** (where applicable), striving for maximal code + integration coverage. Validate it runs. Then write an
**evidence report** at `docs/evidence/<lane>-EVIDENCE.md` — for each thing you built, the file:line and the test
command + result that proves it."*

**Accept a lane only when ALL THREE pass** (you do this yourself, or a fresh evaluator agent — never the sub-agent that
built it):
1. **You review the evidence** — re-run the cited tests on the lane's worktree yourself; every claim must hold when YOU
   run it (don't trust the report).
2. **You code-review the diff** — is it correct, faithful to the design spec + impl plan, no regressions, and does it
   respect the file ownership in §6?
3. **`/spec-gate docs/evidence/<lane>-EVIDENCE.md`** comes back clean.

**If any of the three fails → hand the lane back to the same sub-agent** with the specific findings; re-check. Only when
all three pass do you **accept**.

**On accept → merge → test → fix:** squash-merge the lane into the current branch (foreground); compile + boot-check;
run that lane's tests; fix any breakage before the next dependent lane. **After ALL lanes are merged**, run the **full
suite** (backend build+test, frontend lint+build) and **fix until green.**

## 6. THE PARALLELIZATION PLAN (opinionated — run exactly this concurrently)

### Across lanes
**START THESE 4 LANES AT ONCE** (separate worktrees — they own disjoint files):
- **Builder** (`IMPL-builder`, Phases 0–3) — owns `SchemaPropagationService.java`, the new `pipeline/opengine/`, and
  `codegen/opengine/handlers/`.
- **Catalog** (`IMPL-catalog-seed`, all phases) — owns the `V153` migration + the demo seed. Fully independent.
- **Calcite** (`IMPL-calcite-sql`, Phases 1–2: the validator) — owns `ExpressionValidationService.java` /
  `CalciteSqlModelValidator`. Independent until it integrates into Builder's files (below).
- **Constructs** (`IMPL-construct-library`, the controls) — owns the frontend control components. Fully independent.

**THEN, after Builder's op-walker + handlers land:**
- **Calcite integration** (`IMPL-calcite-sql`, Phases 3–4) — adds the `SourceSQL` branch into `SchemaPropagationService`
  and the `[[ ]]` mnemonic lowering into `CodeGenerationService`. **Serial after Builder** (shared files — see joins).
- **Builder finish** (`IMPL-builder`, Phases 4–7) — DAG/config emission, `V153` consumption, the **anchor test**.
- **Constructs wiring** — the `configure-transform-dialog.tsx` type-switch (after the controls exist).

**LAST — Chat/UI** (`IMPL-ui-composition`, all phases) — owns `ChatService.java`, `ChatTools.java`, `LlmSurface.java`,
`application.yml`, `build.gradle.kts`. It consumes Builder/Catalog/Calcite, so **start it after the Builder lane is
buildable** (the anchor test passes).

### Within a lane — fan out one sub-agent per file-set
- **Builder Phase 1 — the 32 schema rules** = 32 small classes (one `Schema apply(...)` each) in
  `backend/src/main/java/com/pulse/pipeline/opengine/ops/` → **fan out: one sub-agent per rule, or per group of ~8
  disjoint files.** THEN ONE sub-agent does the serial op-walker integration in
  `pipeline/service/SchemaPropagationService.java` that calls them.
- **Builder Phase 3 — the emission handlers** = one handler per (op × engine) in
  `backend/src/main/java/com/pulse/codegen/opengine/handlers/` → **fan out: one sub-agent per handler group (disjoint
  files).** THEN ONE sub-agent wires them into `CodeGenerationService.java`.
- **Constructs** = each control is its OWN component file (`column-picker.tsx`, `sql-filter-builder.tsx`,
  `expression-input.tsx`, `mnemonic-date-input.tsx`, `orchestration-panel.tsx`, + the new `rename-mapper` /
  `simple-sql-builder`) → **fan out: one sub-agent per control.** THEN ONE sub-agent does the `configure-transform-dialog.tsx`
  type-switch wiring.
- **Catalog** = the `V153` migration is ONE file → ONE sub-agent (phases run in sequence inside it); the demo-seed
  regeneration is a second sub-agent.
- **Calcite** = the validator is ONE sub-agent; its integration into Builder's files (above) is a second.
- **Chat/UI** = the graph + op-queue + Apply core is a serial spine (ONE sub-agent through it); the 7 stage-prompts and
  the read-only chat tools fan out once that core exists.

### The serial joins — NEVER let two sub-agents write these at the same time
| File | First writer | Then |
|---|---|---|
| `SchemaPropagationService.java` | **Builder** (the op-walker) | Calcite (the `SourceSQL` branch) |
| `CodeGenerationService.java` | **Builder** (handler wiring) | Calcite (the `[[ ]]` mnemonic lowering) |
| `V153` migration | **Catalog** only | — |
| `build.gradle.kts` | **Chat/UI** only (adds the LangGraph4j deps) | — |
| `configure-transform-dialog.tsx` | **Constructs** only (the type-switch) | — |

## 7. DONE WHEN

All 5 lanes are accepted (the three-check loop, §5) and merged; each lane's tests pass; the **full backend + frontend
suite is green**; and `docs/PULSE-MAP.md` is updated to reflect the new state. Then `git worktree remove` every worktree
under `/Users/aameradam/projects/dev/pulse-wt/`, and final commit + push.

## 8. THE FEW RULES THAT BIND YOU
- **Verify every sub-agent's work yourself** before accepting — a sub-agent's report is a claim, not proof; re-run it.
- **Plain English** with the operator (Chat / Designer / Builder / Packager / Deployer + the helpers — not class names).
- **Max parallelism;** farm self-contained lanes/fan-outs to Codex + Droid.
- **Be a genuine technical check** — push back on anything that looks wrong; don't rubber-stamp.
- **Commits:** author `Aamer Adam <aamer@aamer.net>` only, no AI attribution; never commit a secret or service-account key.

## Appendix — pinned facts
Repo `/Users/aameradam/projects/dev/PULSE-guided-rehearsal` · branch `guided/acme-lending-human-rehearsal-2026-05-25` ·
remote `git@github.com:zadam2008/pulse.git` · **`BASE_SHA` `229636a230c5c5cc9a4b0e02e67cfafb4c0bac0e`**. The 5 lanes' specs + plans are in §3. The
spec map: `docs/build-specs/SPEC-INDEX.md`. The resolved decisions: `docs/build-specs/WORKLIST-RESOLUTIONS.md`. DPC/Livy
(`docs/impl-plans/IMPL-dpc-livy.md`) is deferred — out of scope here.
