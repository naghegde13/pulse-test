# Constructs Lane — Evidence Report

**Lane:** Constructs (frontend UI controls, SPEC #4 `SPEC-construct-library.md`)
**Branch:** `build/constructs` (worktree `/Users/aameradam/projects/dev/pulse-wt/constructs`)
**Author:** Aamer Adam <aamer@aamer.net>
**Date:** 2026-06-16

Eleven deliverables (S1a, S1b, S2–S10) per `IMPL-construct-library.md` §1. Tests are vitest +
@testing-library/react only (no Playwright — operator ban honored). Each control ships a colocated
`*.test.tsx`.

## GATE RESULTS (real output, captured this run)

| Gate | Command | Result |
|---|---|---|
| Lint | `npm run lint` | **0 errors** (6 pre-existing warnings in files this lane did NOT touch: `readiness-category-panel-retirement.test.ts`, `sor/credential-dialog.tsx`, `test/setup.ts`) |
| Build | `npm run build` | **PASS** — `✓ Compiled successfully`, TypeScript clean, all 13 pages generated (Next.js 16.1.6 / Turbopack) |
| Unit | `npm run test:unit` | **202 passed / 1 failed** — the single failure is `src/test/contract/orphan-type.test.ts` (orphans `SubPipelineInstanceResponse`, `TenantGcpRuntimeTopologyForm`, `DeploymentTargetCreateRequest`), a **pre-existing, unrelated** contract guard (its own message: "a known orphan as of the SU-8 META-packet commit"). `git diff HEAD -- src/types/index.ts` shows this lane added only `ui_construct?` + `filter_types?` to `BlueprintParamDefinition` — it did NOT touch any of the three orphan types. |
| Unit (excl. that pre-existing failure) | `npm run test:unit -- --run --exclude '**/orphan-type.test.ts'` | **25 files / 202 tests — ALL PASS** |

Construct test totals: **80 tests across 10 construct test files** (all green).

## PER-CONSTRUCT EVIDENCE

| # | Construct | Disposition | File:line (key) | Test file | Tests |
|---|---|---|---|---|---|
| S1a | rich sql-chain-editor | BUILD (structural; backend-dep) | `frontend/src/components/pipeline/sql-chain-editor.tsx` (steps add/remove/reorder + per-step `materialize` :89-110; `[[ ]]` insert :119-138; S10 preview call :141-171; shared `SPARK_FUNCTIONS` import :37) | `sql-chain-editor.test.tsx` | 10 |
| S1b | simple-sql-builder | BUILD-NEW (structural; backend-dep) | `frontend/src/components/pipeline/simple-sql-builder.tsx` (api.post `/api/v1/sql/source/validate` :77; graceful-catch :95-103) | `simple-sql-builder.test.tsx` | 5 |
| S2 | expression-builder | POLISH | `frontend/src/components/pipeline/expression-input.tsx` (`insertToken` `[[ MNEMONIC ]]` ~:90-126) | `expression-input.test.tsx` | 5 |
| S3 | column-picker | EXTEND (committed wave 1) | `frontend/src/components/pipeline/column-picker.tsx` (`filterTypes` :78-82, :244-248; loading skeleton single :89-102 / multi :230-242) | `column-picker.test.tsx` | 6 |
| S4 | rename-mapper | BUILD-NEW | `frontend/src/components/pipeline/rename-mapper.tsx` (map↔rows sync :95-120; dup/empty validation :135-158; reuses `ColumnPicker` source cell :172-179; right-cell param :182-220) | `rename-mapper.test.tsx` | 9 |
| S5 | condition-builder | EXTEND | `frontend/src/components/pipeline/sql-filter-builder.tsx` (per-value date-mnemonic cell W-5/W-11; `setValueKind`; `rowError` W-6; unquoted-mnemonic preview) | `sql-filter-builder.test.tsx` | 9 |
| S6 | sensing-config / cron-builder | EXTEND + BUILD-NEW | `frontend/src/components/pipeline/cron-builder.tsx` (standalone, W-8); `orchestration-panel.tsx` (W-7 prune at :33-39 comment; CronBuilder hosted at the schedule slot) | `cron-builder.test.tsx` | 5 |
| S7 | dq-outcome-control | BUILD-NEW | `frontend/src/components/pipeline/dq-outcome-control.tsx` (port-aware filter W-9 :68-70) | `dq-outcome-control.test.tsx` | 5 |
| S8 | date-mnemonic-picker | EXTEND (committed wave 1) | `frontend/src/components/pipeline/mnemonic-date-input.tsx` (as-of prop W-10 :38,177; embedded variant W-11 :44,180-184; `dateTokenMode` W-12 :50,302-310; `DateMnemonicPicker` alias :320) | `mnemonic-date-input.test.tsx` | 14 |
| S9 | per-step data-preview | BUILD (structural; backend-dep) | embedded in `sql-chain-editor.tsx` (`runPreview` :141-171; per-step pane render block) | (covered by `sql-chain-editor.test.tsx`) | — |
| S10 | preview endpoint | **OUT of lane** (backend) | wired call only: `sql-chain-editor.tsx:148` `/api/v1/sql/sql-model/preview` | — | — |

### Backend-dependent / structurally-built (noted, not wired live)
- **S1a / S9** — Calcite **CALCITE-PHASE-2** (schema-deriving per-step validation) and the **S10 preview
  endpoint** (`/api/v1/sql/sql-model/preview`, dev-Spark-on-cached-sample) are OUT of this lane. The chain
  editor + per-step preview pane are built; the preview call is wired and **degrades gracefully** when the
  endpoint is absent (404 → non-fatal "unavailable" message; tested).
- **S1b** — the JDBC-prepare/`getMetaData` source-validate endpoint (`/api/v1/sql/source/validate`) is a
  backend dep. The control + Validate call are built; absent endpoint → graceful "not yet implemented"
  (tested).

## W-13 TYPE-SWITCH WIRING (single-writer, this lane)

- **Hint plumbing ADDED:** `BlueprintParamDefinition` (`frontend/src/types/index.ts:222-260`) gained
  `ui_construct?: string` (the #5 UI-construct HINT token) + `filter_types?: string[]` (column-picker type
  restriction). The data model previously had **no** hint field.
- **Wiring:** `configure-transform-dialog.tsx` `MetadataDrivenConfig` type-switch now **routes by the #5 hint
  first** (`resolveConstructHint`), then falls back to type-based arms. Two `object`-typed params route to
  different controls by hint (proven by the wiring test). Replaced fallbacks: raw-JSON `<Textarea>`
  (object/object[]) → rename-mapper / condition-builder / sql-chain-editor; generic `<Select>` (enum) →
  dq-outcome-control; newline `<Textarea>` (string[]) → column-picker (multi). Unhinted params keep their
  type-based fallback (a generic `string[]` like `tags` stays a Textarea).
- **W-9 port-awareness:** the dialog passes `blueprint.outputPorts` names to `DqOutcomeControl` so
  `quarantine` is offered only when a `quarantine_output` port is declared.
- **Test:** `configure-transform-dialog.test.tsx` (12 tests) — asserts each hint renders its purpose-built
  control (not the fallback), the two-object-different-controls case, dq port-awareness both ways, and that
  unhinted object/string[] params still get the JSON/newline fallbacks.

## SHARED SEAM (G-1)
- `SPARK_FUNCTIONS` lives in **exactly one** module — `frontend/src/components/pipeline/spark-functions.ts`.
  Both `sql-filter-builder.tsx` (S5) and `sql-chain-editor.tsx` (S1a) **import** it; no second copy was
  forked.

## COMMITS (this run, on `build/constructs`)
- `92db94b` — wave 2: S2, S7, S4, S1b (+ tests)
- `bf2bec4` — S5 condition-builder (W-5/W-11/W-6) (+ test)
- `30e62e8` — S6 cron-builder (W-8) + sensor prune (W-7) (+ test)
- `7cf0d98` — S1a + S9 sql-chain-editor + per-step preview (structural) (+ test)
- `fd38e8a` — W-13 hint plumbing + dialog type-switch wiring (+ test)

(Wave 1 — S3 column-picker, S8 date-mnemonic-picker, spark-functions registry — was committed earlier at
`0b7dadd`.)
