# IMPL #4 — Construct Library (build plan)

> Build plan (NOT code) for the reusable, purpose-built UI controls the bespoke
> Blueprint panels assemble from. Source of truth: `docs/ui/SPEC-construct-library.md`
> (each construct is marked EXTEND-existing / POLISH / BUILD-NEW there with
> `file:line` evidence). This plan turns that spec into an ordered, fan-outable
> build with prerequisites, phases, tests, milestones and risks.
>
> `[read]` = file verified at the cited path THIS session.
> Conventions match the spec: a "construct" = one reusable control; "wire into the
> type-switch" = replace a generic fallback in `configure-transform-dialog.tsx`'s
> per-param renderer.

---

## 0. Evidence base (files read this session)

- `[read]` `frontend/src/components/pipeline/column-picker.tsx` — exports `ColumnPicker`
  (single, `:47`), `MultiColumnPicker` (multi, `:193`), `getTypeColor` (`:26`). Both
  already schema-reading: `filterTypes` substring filter (`:71-75`, `:200-204`); text
  fallback on empty schema (single `:82-91`, multi `:206-222`). Props `:38-45` / `:185-191`.
- `[read]` `frontend/src/components/pipeline/sql-filter-builder.tsx` — `SqlFilterBuilder`
  (`:199`). Visual `FilterCondition[]` builder (`:283-387`); `OPERATORS` catalog
  (`:27-42`); `NULLARY_OPS` (`:44`); visual/SQL `mode` toggle (`:208`, `:236`); WHERE
  preview (`:381-386`); embedded `SPARK_FUNCTIONS` palette (`:47-184` — the duplicate to
  reconcile, spec G-1). Reuses `ColumnPicker` for the schema-bound column cell (`:304-310`).
  Props `:186-197`.
- `[read]` `frontend/src/components/pipeline/expression-input.tsx` — `ExpressionInput`
  (`:63`). Already wired to `POST /api/v1/expressions/validate` with 300ms debounce
  (`:85-118`); `kind: "value" | "predicate"` (`:38`); `inputSchemas` per-port (`:35`);
  `onValidationChange` callback (`:47`); status chip + diagnostics rows (`:149-203`).
- `[read]` `frontend/src/components/pipeline/mnemonic-date-input.tsx` — `MnemonicDateInput`
  (`:128`). Tri-mode (mnemonic combobox + `±N` offset / ISO / free-text, `:149-256`);
  `VOCABULARY` map (`:35-89`); `MNEMONIC_RE` mirrors `DateMnemonic.java` (`:93`). Props
  `:28-32` (`id`, `value`, `onChange` — NO schema, NO as-of).
- `[read]` `frontend/src/components/pipeline/orchestration-panel.tsx` — Gate-4 sensor key
  set `:33-39` (still lists the deprecating `ObjectStoreKeySensor` `:35` +
  `DatasetDependencySensor` `:37`); `ScheduleAndTriggers` key `:41`; `ScheduleMode` type
  `:55`; `buildScheduleSummary` `:178-191`; generic policy (de)serialize incl. `object` →
  JSON string `:67-96`. Does NOT yet render per-sensor field surfaces.
- `[read]` `frontend/src/components/pipeline/configure-transform-dialog.tsx` — the
  **type-switch** at `:727-808`: `accepts_mnemonic` → `MnemonicDateInput` (`:727-736`);
  predicate fields → `ExpressionInput` (`:737-750`); `enum` → generic `<Select>`
  (`:751-770`); `boolean` → Select (`:771-783`); `string[]` → newline `<Textarea>`
  (`:784-791`); **`object`/`object[]` → raw-JSON `<Textarea>`** (`:792-800`); else `<Input>`
  (`:801-808`). Schema source: `useUpstreamSchema(...)` → `{ columns: upstreamSchema,
  loading }` (`:224`).
- `[read]` `frontend/src/hooks/use-upstream-schema.ts` — returns `{ columns, loading,
  error, refetch }` (`:49`); fetches `GET /api/v1/versions/{versionId}/composition/
  instances/{instanceId}/upstream-schema` (`:31-33`); session cache (`:7`, `:35`).
- `[read]` backend present: `com/pulse/expression/controller/ExpressionController.java`,
  `com/pulse/expression/service/ExpressionValidationService.java`,
  `com/pulse/common/text/DateMnemonic.java`.
- `[read]` `docs/build-specs/SPEC-calcite-sql-model.md` exists (the validate backend +
  function registry + `[[ … ]]` spec — the #6 dependency); ADR 0024 at
  `docs/adr/0024-sql-authoring-sourcesql-sqlmodel-calcite-mnemonics.md`.
- `[read]` `frontend/src/components/pipeline/expectation-picker.tsx` exists (per-expectation
  `severity` — the DISTINCT axis from §7's Blueprint-level disposition; not this task).

---

## 1. SCOPE — the control set

Eleven deliverables (S1a, S1b, S2–S10). Per the spec each is EXTEND / POLISH / BUILD-NEW; the EXTEND/POLISH ones
build on the read files above, the BUILD-NEW ones replace a raw-JSON / generic-select
fallback in the type-switch.

| # | Construct | Disposition | Base file | #5 hint(s) it owns |
|---|-----------|-------------|-----------|--------------------|
| S1a | **sql-builder — rich (Spark dialect)** | EXTEND + new chain/validate/preview | `sql-filter-builder.tsx` `[read]` | `sql-chain-editor` → `SqlModel.steps` |
| S1b | **sql-builder — simple (source-DB)** | BUILD-NEW (thin: editor + Validate) | — (new) | `simple-sql-builder` → `SourceSQL.source_query`, `BulkBackfill.source_query` |
| S2 | **expression-builder** | POLISH | `expression-input.tsx` `[read]` | predicate / derived-column SQL exprs |
| S3 | **column-picker** | EXTEND | `column-picker.tsx` `[read]` | ~35 hints — `string` single + `string[]` multi |
| S4 | **rename-mapper** | BUILD-NEW | — (new) | `rename_map`, `mapping_rules` (`object`); folds `key-value-mapper`, `type-cast-mapper` |
| S5 | **condition-builder** | EXTEND | `sql-filter-builder.tsx` `[read]` | `conditions`, `routes[].condition` (`object[]`) |
| S6 | **sensing-config** | EXTEND | `orchestration-panel.tsx` `[read]` | FileArrival / DatabaseReadiness / ExternalEvent / ScheduleAndTriggers |
| S7 | **DQ-outcome-controls** | BUILD-NEW (thin, port-aware) | — (new) | `on_failure`, `drift_policy` (`enum`) |
| S8 | **date-mnemonic-picker** | EXTEND | `mnemonic-date-input.tsx` `[read]` | `accepts_mnemonic` `string` params |
| S9 | **per-step data-preview (control)** | BUILD-NEW | — (new) | embedded in S1a, per `SqlModel` step |
| S10 | **per-step data-preview (backend endpoint)** | BUILD-NEW | backend (new) | dev-Spark-on-cached-sample |

### Per-construct EXTEND deltas (the actual added work, vs "rebuild")

- **S1a rich sql-builder** — EXTENDS the existing visual/SQL builder. ADD: (i) the
  **multi-step `steps:[{name,sql,materialize}]` chain editor** with per-step `materialize`
  toggle; (ii) **Calcite-backed live validation per step** via the schema-deriving
  CALCITE-PHASE-2 of `/api/v1/expressions` (today returns `outputType="unknown"`,
  `ExpressionValidationService.java:99-113`); (iii) **`[[ … ]]` mnemonic insertion**;
  (iv) **per-step DATA preview** (S9/S10); (v) reconcile the in-file `SPARK_FUNCTIONS`
  palette (`sql-filter-builder.tsx:47-184`) to the ONE Calcite function registry
  (spec G-1) — do NOT fork a second copy. PULSE owns the one Spark dialect.
- **S1b simple sql-builder** — BUILD-NEW but thin: a plain SQL `<Textarea>` + a **Validate
  button ONLY** that round-trips to the bound JDBC source (prepare / `getMetaData`) — the
  **source DB validates its own dialect and returns the result schema**. NO rich palette,
  NO per-dialect parser. `[[ … ]]` supported (substituted to a dummy `DATE` before prepare).
  Plus connector/dataset picker. (Backend JDBC prepare/metadata endpoint is owned by the
  SourceSQL/ADR-0024 track, not built here — this task builds the control + wires the call.)
- **S2 expression-builder** — POLISH only: add `[[ … ]]` mnemonic insertion; minor polish.
  Validation already wired. No rebuild.
- **S3 column-picker** — ADD: `filterTypes` wiring from #5 per-param (e.g. struct-only for
  JsonFlatten `source_columns`, timestamp/date for `timestamp_column`); the
  **loading-vs-empty split** (W-2 — pass a `loading` flag so a fetch-in-flight shows a
  skeleton, not the text fallback); wire `string`/`string[]` column-role params into the
  type-switch (today `string[]` → newline `Textarea`, `:784-791`).
- **S4 rename-mapper** — BUILD-NEW two-column table (left = `ColumnPicker` source cell,
  right = target). **Reuse `ColumnPicker` for the source cell.** Build as the GENERAL
  two-column-map and **parameterize the right-hand cell** (free-text name / type `<Select>`
  / value input) so `key-value-mapper` (`fill_null_map`) + `type-cast-mapper`
  (`type_coercions`) reuse it (W-3). Props `{ columns, value: Record<string,string>,
  onChange }` (W-4).
- **S5 condition-builder** — EXTENDS `SqlFilterBuilder`'s `FilterCondition[]` builder.
  ADD: a per-value **`date-mnemonic-picker` cell** on `conditions[].value` (W-5 — the ONE
  net-new behavior); expose for GenericRouter's per-route `condition` (route-builder embeds
  one condition-builder per route). Round-trip visual rows → `raw_sql` WHERE for the
  `filter_mode` toggle. Reconcile palette to the one registry (same as S1a).
- **S6 sensing-config** — EXTENDS `orchestration-panel.tsx`. ADD the per-sensor field
  groups it does NOT yet render: FileArrival location+match+SLA
  (`bucket`/`path_prefix`/`filename_pattern`/`pattern_kind`/`expected_max_age_hours`/
  `multiple_files_mode`/`soft_fail`); DatabaseReadiness probe-SQL (delegate to S2) +
  `expected_count_min/max`; ExternalEvent `event_url`. Wire `date_value` → S8 (and feed the
  `{date}` substitution for `filename_pattern`, G-10). Host `cron-builder` + `dataset-picker`
  as sub-controls (W-8). **PRUNE** the deprecating `ObjectStoreKeySensor` /
  `DatasetDependencySensor` from `:35,37` to match #5 (W-7).
- **S7 DQ-outcome-controls** — BUILD-NEW small segmented-control over the enum
  (`quarantine`/`block`/`warn`) with a one-line consequence caption per option. The **#5 hint-routing token is
  `dq-outcome-control` (singular)** — the type-switch routes `on_failure`/`drift_policy` on that exact token
  (the plural is only the deliverable's display label). **Port-aware**: offer `quarantine` only when the host
  declares a `quarantine_output` port (DQValidator yes; SchemaDriftDetection's `drift_policy` no) — #3 passes
  the declared output roles (W-9).
- **S8 date-mnemonic-picker** — EXTENDS `MnemonicDateInput` (already vocabulary-complete,
  in lockstep with `DateMnemonic.java`). ADD: (i) optional **as-of business-date** prop for
  preview resolution (W-10); (ii) an **embedded-cell variant** for S5's value cells (W-11);
  (iii) confirm it feeds `{date}` substitution for `filename_pattern` (G-10, W-12). Optional
  cosmetic rename of the export to `DateMnemonicPicker`.
- **S9/S10 per-step data-preview** — control + backend. **Engine = dev Spark** (local or dev
  Dataproc), dev-builder-only, on a **cached top-N sample** of the chain input; run the chain
  **incrementally** (step N preview = sample through steps 1..N); `[[ … ]]` resolve against
  the dev business date or a user-picked as-of (via S8's new as-of prop). Design-time + dev
  only — **no bearing on the immutable package, no runtime phone-home.** Backend endpoint:
  `(input-dataset sample, chain steps 1..N) → top-N result rows + schema`.

---

## 2. PREREQUISITES

### 2a. #4's 13-item worklist (the `> GUESS:` items to ratify before build)

These are the open `> GUESS:` items from `SPEC-construct-library.md` §OPEN WORKLIST
(W-1…W-13). Each is an invention/assumption that MUST be operator-agreed (zero-fuzziness gate,
ADR 0010) before the touching phase starts. Listed with the phase that consumes each.

| W | Construct | What to ratify | Blocks |
|---|-----------|----------------|--------|
| W-1 | column-picker | stale-column-ref surfaces as a #3 panel warning (not silent) | P-A (S3) |
| W-2 | column-picker | split *loading* vs *empty*; #3 passes a `loading` flag | P-A (S3) |
| W-3 | rename-mapper | build as general two-column-map; param right cell so key-value/type-cast reuse | P-D (S4) |
| W-4 | rename-mapper | prop shape `{ columns, value: Record<string,string>, onChange }` | P-D (S4) |
| W-5 | condition-builder | per-value mnemonic = embedded `date-mnemonic-picker`, NOT `[[ … ]]` | P-B (S5) |
| W-6 | condition-builder | visual rows valid-by-construction; only error = empty value on non-nullary op | P-B (S5) |
| W-7 | sensing-config | prune `ObjectStoreKeySensor`/`DatasetDependencySensor` from the panel set | P-C (S6) |
| W-8 | sensing-config | `cron-builder` stays its own construct, hosted by sensing-config | P-C (S6) |
| W-9 | DQ-outcome-control | option set is port-aware; #3 passes declared output roles | P-E (S7) |
| W-10 | date-mnemonic-picker | add optional "as-of business date" prop for preview | P-A (S8) + P-F (S9) |
| W-11 | date-mnemonic-picker | add embedded-cell variant for condition value cells (couples W-5) | P-A (S8) + P-B (S5) |
| W-12 | date-mnemonic-picker | confirm it feeds `{date}` substitution for `filename_pattern` (G-10) | P-A (S8) + P-C (S6) |
| W-13 | cross-cutting | #3 owns wiring each construct into the type-switch | every phase |

`> CONFIRM` (spec §Preview): the preview engine choice = **dev-Spark-on-cached-sample**
(fidelity over interactivity). Operator-recommend; ratify before P-F.

### 2b. Cross-spec dependencies

- **#5 (`SPEC-blueprint-catalog.md`) — the per-param UI-construct hints.** #5 names, per
  param, WHICH construct renders it. The type-switch must route by the #5 hint (not just by
  `definition.type`), because today same-`type` params (e.g. `object` rename_map vs `object`
  fill_null_map) need DIFFERENT controls. **The hint token must be present on the param
  definition** for the construct to render. *Hard dependency for the wiring step of every
  phase; soft for building a construct in isolation (a construct can be built + unit-tested
  before its hint lands).* Hint→variant binding (spec Coverage basis): `sql-chain-editor`→S1a,
  `simple-sql-builder`→S1b.
- **#6 (`SPEC-calcite-sql-model.md`) — the Calcite validate backend.** S1a's live per-step
  validation needs the **schema-deriving CALCITE-PHASE-2** of `/api/v1/expressions` (today
  `outputType="unknown"`, `ExpressionValidationService.java:99-113`) so each step's output
  schema feeds the next step's column set + autocomplete. **Hard dependency for S1a's
  validation + autocomplete + the single function registry (G-1).** S2 already works against
  the parse-only validator; S1b uses JDBC-prepare (source-side, not Calcite) — neither blocks
  on #6. *S1a's chain editor UI + per-step preview (S9/S10) can be built before #6 lands; the
  live-schema validation/autocomplete wires when CALCITE-PHASE-2 ships.*
- **The type-switch wiring point — `configure-transform-dialog.tsx:727-808` `[read]`.** Every
  EXTEND/BUILD-NEW construct is wired here, replacing the matching generic fallback:
  `object`/`object[]` raw-JSON `<Textarea>` (`:792-800`) → rename-mapper / condition-builder;
  `enum` `<Select>` (`:751-770`) → DQ-outcome-control; `string`/`string[]` (`:784-791`) →
  column-picker. Schema is already in scope as `upstreamSchema` (+ `loading`) from
  `useUpstreamSchema` (`:224`). Per W-13, **#3 owns this wiring**; this task delivers the
  controls + the hint-routing contract #3 consumes.

**Prerequisite count: 16** = 13 worklist items (W-1…W-13) + 3 cross-spec deps (#5 hint
plumbing, #6 CALCITE-PHASE-2, the `:727-808` type-switch wiring point).

---

## 3. BUILD PHASES (ordered)

Ordering rule (per task): **EXTEND first** (cheapest — build on read files), **then the two
BUILD-NEW**, **then the per-step preview** (endpoint + control). Each phase ends by wiring its
construct(s) into the bespoke-panel type-switch (the W-13 / #3 hand-shake).

### Phase P-A — EXTEND the schema/date controls (S3, S8, S2)
Cheapest, highest fan-in (column-picker is ~35 hints; date-mnemonic-picker feeds S5/S6/S9).
- S3 column-picker: `filterTypes` from #5; loading-vs-empty split (W-2); wire `string`/
  `string[]` column-role params.
- S8 date-mnemonic-picker: as-of prop (W-10), embedded-cell variant (W-11), `{date}`
  confirmation (W-12), optional export rename.
- S2 expression-builder: POLISH — `[[ … ]]` insertion + polish.
- **Wire:** S3 into the `string`/`string[]` arms; S8 already wired via `accepts_mnemonic`
  (`:727-736`) — extend, don't rewire.
- Gate: W-1, W-2, W-10, W-11, W-12 ratified.

### Phase P-B — EXTEND condition-builder (S5)
Depends on S3 (column cell) + S8 (value-cell mnemonic, W-5/W-11) → after P-A.
- EXTEND `SqlFilterBuilder`: per-value `date-mnemonic-picker` cell (W-5, the net-new
  behavior); expose for GenericRouter per-route `condition`; round-trip visual → `raw_sql`.
- Reconcile the embedded `SPARK_FUNCTIONS` palette (`:47-184`) toward the one registry (G-1)
  — defer the live-schema half to the registry landing (P-G).
- **Wire:** `object[]` arm (`:792-800`) for `conditions` / `routes[].condition`.
- Gate: W-5, W-6 ratified.

### Phase P-C — EXTEND sensing-config (S6)
Depends on S8 (`date_value` → S8, `{date}`) + S2 (DatabaseReadiness probe SQL) → after P-A.
- EXTEND `orchestration-panel.tsx`: FileArrival location/match/SLA group; DatabaseReadiness
  probe-SQL (S2) + count bounds; ExternalEvent `event_url`; wire `date_value` → S8.
- Host `cron-builder` + `dataset-picker` sub-controls (W-8). PRUNE the two deprecating
  sensors from `:35,37` (W-7).
- **Wire:** sensor param arms (today JSON/Select fallback).
- Gate: W-7, W-8, W-12 ratified.

### Phase P-D — BUILD-NEW rename-mapper (S4)
First BUILD-NEW. Depends on S3 (source cell). After P-A.
- New two-column-map table; reuse `ColumnPicker` for source cell; parameterized right cell
  (W-3); props per W-4; dup-source / dup-target / empty-target validation.
- **Wire:** `object` arm (`:792-800`) for `rename_map` / `mapping_rules`.
- Gate: W-3, W-4 ratified.

### Phase P-E — BUILD-NEW DQ-outcome-controls (S7)
Second BUILD-NEW. Independent (static enum). After P-A (parallel with P-B/C/D).
- New port-aware segmented-control + consequence captions; hide `quarantine` when no
  `quarantine_output` port (W-9).
- **Wire:** `enum` arm (`:751-770`) for `on_failure` / `drift_policy`.
- Gate: W-9 ratified (#3 passes declared output roles).

### Phase P-F — per-step data-preview (S10 endpoint, then S9 control)
Endpoint first, then the control that calls it.
- S10 backend: design-time preview endpoint `(input-dataset sample, chain steps 1..N) →
  top-N rows + schema`; dev-Spark-on-cached-sample; session-cached sample; `[[ … ]]` resolve
  via dev business date / as-of (S8). Dev-only; no package/runtime coupling.
- S9 control: per-step preview pane embedded in S1a; calls S10 incrementally; shows rows +
  schema at each step.
- Gate: the `> CONFIRM` engine choice (dev-Spark) ratified.

### Phase P-G — EXTEND/assemble the rich sql-builder (S1a) + BUILD-NEW simple (S1b)
Last: S1a depends on S5's palette reconcile (P-B), S8's `[[ … ]]` (P-A), S9/S10 (P-F), and
#6's CALCITE-PHASE-2 for live per-step validation/autocomplete.
- S1a: chain editor (`steps:[{name,sql,materialize}]` + per-step `materialize`); `[[ … ]]`
  insertion; single Calcite function registry (G-1); per-step Calcite validation
  (CALCITE-PHASE-2); embed S9 preview.
- S1b: plain editor + Validate-only (JDBC prepare/`getMetaData`); `[[ … ]]` → dummy `DATE`;
  connector/dataset picker; optional single-query `SELECT … LIMIT N` preview.
- **Wire:** `sql-chain-editor` → S1a (`SqlModel.steps`); `simple-sql-builder` → S1b
  (`SourceSQL.source_query`, `BulkBackfill.source_query`).
- Gate: #6 CALCITE-PHASE-2 available for the live-validation half (chain UI + preview may
  land ahead of it).

---

## 4. TESTS

Three layers (no frontend runner is wired yet — new specs author against the current surface;
backend uses H2/JUnit; Spark-touching preview needs a dev-Spark integration target).

### 4a. Component behavior (per construct)
- **S3 column-picker:** single search/select + type badge; multi badge-toggle; `filterTypes`
  narrows the set (struct-only, date/timestamp-only); empty schema → text fallback; **loading
  flag → skeleton, NOT text fallback** (W-2).
- **S4 rename-mapper:** add/remove rows; source cell = `ColumnPicker`; reject dup source, dup
  target, empty target; round-trip to the `object` map; parameterized right cell renders
  free-text / type-Select / value per mode (W-3).
- **S5 condition-builder:** row add/remove; operator catalog incl. nullary value-hiding
  (`:44,352`); WHERE preview; visual→`raw_sql` round-trip; **per-value mnemonic cell** opens
  S8 (W-5); only visual error = empty value on non-nullary op (W-6).
- **S7 DQ-outcome-control:** renders 3 options w/ captions; **`quarantine` hidden when no
  quarantine port** (W-9); default applies when unset.
- **S8 date-mnemonic-picker:** tri-mode; offset shown only for offset-supporting heads; client
  regex matches `DateMnemonic.java`; as-of prop affects preview resolution (W-10); embedded
  variant renders in a cell (W-11).
- **S6 sensing-config:** FileArrival field group; `schedule_type` gates cron vs trigger
  required; `date_value`→S8 feeds `{date}` (W-12); pruned-sensor set excludes the two
  deprecated keys (W-7).
- **S2:** `[[ … ]]` insertion lands the token at the cursor; existing validation untouched.
- **S1a/S1b:** chain step add/remove/reorder + per-step `materialize` toggle; `[[ … ]]`
  insertion; S1b Validate button fires the JDBC-prepare call and renders the returned schema /
  error.
- **Type-switch wiring:** for each construct, a param carrying its #5 hint renders the
  construct (NOT the generic fallback) — assert against `configure-transform-dialog`'s render.

### 4b. Schema-aware controls vs the LIVE input schema
- Drive S3/S4/S5 from a representative `SchemaColumn[]` (the upstream port schema
  `useUpstreamSchema` supplies); assert picked columns exist in the schema, `filterTypes`
  honored, and a column that drops out of the schema surfaces as stale (W-1, at the #3 panel
  level).
- S1a autocomplete/validation: assert step N sees step N-1's derived output columns (requires
  CALCITE-PHASE-2 — schema-deriving).

### 4c. Preview vs dev-Spark (S9/S10)
- **Fidelity test:** the preview rows for a chain MUST equal what a real Spark run of the same
  steps on the same sample produces (the whole point of choosing dev-Spark over a divergent
  local engine).
- **Incrementality:** step N preview = cached sample through steps 1..N; sample fetched once
  per session (cache hit on re-preview).
- **Mnemonic resolution:** `[[ … ]]` resolves against the dev business date / picked as-of.
- **Isolation:** preview is dev-only — assert it leaves no trace in the immutable package and
  emits no runtime phone-home.

---

## 5. MILESTONES

- **M1 — Schema/date EXTENDs land (P-A).** column-picker (filterTypes + loading split),
  date-mnemonic-picker (as-of + embedded variant), expression-builder (`[[ … ]]`). Highest
  fan-in unblocked.
- **M2 — Visual builders EXTENDed (P-B, P-C).** condition-builder (mnemonic value cell) +
  sensing-config (per-sensor fields, pruned set). Most `object[]` / sensor fallbacks replaced.
- **M3 — BUILD-NEWs land (P-D, P-E).** rename-mapper + DQ-outcome-control. The raw-JSON
  `object` and generic-`enum` fallbacks for their hints are gone.
- **M4 — Preview works on dev-Spark (P-F).** Endpoint + control; fidelity + incrementality
  tests green.
- **M5 — Rich + simple sql-builder assembled (P-G).** S1a chain editor with single registry +
  CALCITE-PHASE-2 live validation + embedded preview; S1b validate-only. All eleven constructs
  wired into the type-switch; every #5 hint in this task's set resolves to a purpose-built
  control.

---

## 6. FAN-OUT (independently buildable)

The controls are **independently buildable** — each is a self-contained component with a
props contract, so they parallelize across builders once P-A's shared deps (S3, S8) exist.

- **Wave 1 (parallel, no inter-dep):** S3, S8, S2, S7. (S7 is fully independent — static
  enum; S2 is isolated polish.)
- **Wave 2 (parallel, depend only on Wave 1):** S5 (needs S3+S8), S6 (needs S2+S8), S4
  (needs S3). Three builders, no cross-talk.
- **Wave 3:** S10 then S9 (endpoint before control).
- **Wave 4:** S1a (needs S5 palette reconcile + S8 + S9/S10 + #6) and S1b (independent of #6)
  — S1b can actually run in Wave 2/3 in parallel since it only needs the JDBC-prepare endpoint.
- **Shared seam to coordinate (not parallelize blindly):** the ONE Calcite function registry
  (G-1) — S1a and S5 both consume it; assign one owner to land the registry, the rest import
  it. Don't fork a second `SPARK_FUNCTIONS` copy.
- **Hand-shake seam:** the type-switch wiring (`:727-808`) is #3-owned (W-13); each construct
  ships with its hint-routing contract so #3 wires without re-reading the control.

---

## 7. RISKS

1. **#6 CALCITE-PHASE-2 slip blocks S1a's value.** Without schema-deriving validation, S1a's
   per-step autocomplete + live validation degrade to parse-only. *Mitigation:* build S1a's
   chain UI + preview (S9/S10) ahead of #6; gate only the live-validation half on #6. The
   `outputType="unknown"` today (`ExpressionValidationService.java:99-113`) is the visible tell
   it isn't ready.
2. **Function-registry fork (G-1).** There is exactly **one** copy of the Spark-function list today —
   `sql-filter-builder.tsx:47-184` (verified: `SPARK_FUNCTIONS` is defined in that file only). The risk is not
   "reconcile three copies" but **forking a SECOND copy**: EXTENDing S5/S1a (both consume the list) could
   inline a duplicate palette. *Mitigation:* land the single registry first (one owner), import it into S5/S1a;
   treat any new inline palette as a defect.
3. **Preview fidelity vs interactivity (the `> CONFIRM`).** dev-Spark gives faithful previews
   but pays Spark startup latency. *Mitigation:* small cached sample + incremental re-run;
   ratify the engine choice before P-F (operator recommends fidelity). Risk if reversed to a
   divergent engine (DuckDB): previews diverge from the real run — explicitly rejected.
4. **#5 hint plumbing not landing per-param.** If a param lacks its UI-construct hint, the
   type-switch can't route to the purpose-built control and falls back to raw JSON / generic
   Select — silently reverting the value of this whole task. *Mitigation:* W-13 contract +
   a test asserting hint→construct routing; coordinate the hint set with #5.
5. **Deprecated-sensor prune drift (W-7).** Pruning `ObjectStoreKeySensor` /
   `DatasetDependencySensor` from `orchestration-panel.tsx:35,37` must stay in lockstep with
   #5 absorbing them into FileArrivalSensor / ScheduleAndTriggers; a mismatch leaves orphaned
   or missing sensor surfaces.
6. **Port-awareness contract (W-9).** DQ-outcome-control's `quarantine` option depends on #3
   passing declared output roles; if not passed, it either offers an invalid `quarantine` (no
   port) or hides a valid one. *Mitigation:* make the roles a required prop, default-safe.
7. **Mnemonic vocabulary triple-sync.** `mnemonic-date-input.tsx` must stay in lockstep with
   `DateMnemonic.java` + the runtime resolver (file header `:12-15`). Adding the as-of /
   embedded variant must not drift the `VOCABULARY` / `MNEMONIC_RE`. *Mitigation:* a
   client-vs-Java parity test on the head set + regex.
8. **No frontend test runner wired yet** (CLAUDE.md). Component + schema-aware tests need a
   runner stood up first, or they can't gate the milestones. *Mitigation:* stand up the
   runner as a P-A prerequisite task.

---

## 8. REPORT

- **Phase count: 7** (P-A … P-G).
- **Prerequisite count: 16** = 13 worklist items (W-1…W-13) + 3 cross-spec dependencies
  (#5 per-param hint plumbing, #6 CALCITE-PHASE-2 validate backend, the
  `configure-transform-dialog.tsx:727-808` type-switch wiring point).
