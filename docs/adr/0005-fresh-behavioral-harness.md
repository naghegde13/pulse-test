# Rebuild the behavioral-test harness from scratch; salvage only the sample data and re-verified oracles

Status: accepted

## Context

A large pre-existing e2e "GCP golden runtime" suite (~30 per-blueprint
`*GcpGoldenRuntimeNamespaceMaterializationTest` classes, a Composer→Dataproc
bridge adapter, MinIO/Postgres oracle validators, a scenario/evidence DSL) had
given the operator **false confidence** — it read green while the customer
journey did not work. First-hand inspection (2026-06-13) confirmed why:

- The golden "materialization" tests **author their own PySpark/dbt job strings
  inline** (e.g. `FileIngestionGcpGoldenRuntimeNamespaceMaterializationTest.fileIngestionJob()`
  carries `"verdict":"draft-pass"`) and assert only that a **rendered** DAG/JSON
  *contains expected substrings*. They neither call the production Builder
  (`CodeGenerationService`) nor execute Spark or touch GCP.
- The genuinely-executing tests (`JsonBlueprintLiveRuntimeProofIT`,
  `AggregateBlueprintLiveRuntimeProofIT`) *do* feed the real Builder and run
  generated code on **local Docker Airflow + Spark** — but they are
  `@Tag("runtime")`, excluded from the default `./gradlew test`, and never
  witnessed by the operator.
- Committed "real GCP" evidence under `docs/verification/` (a hello-world
  Dataproc batch + a generic-join batch in throwaway project
  `pulse-proof-04261847`) is **self-reported JSON**, not a witnessed end-to-end
  run of the real anchor pipeline.

Net: a mostly-green suite whose green did not mean "a file becomes a correct
table." `[report]` items above came from a survey agent; the load-bearing one
(the hardcoded `fileIngestionJob()` render-only test) was read first-hand.

## Decision

Build a **fresh** behavioral-test harness that follows ADR 0004 literally — an
independent evaluator runs the **real Builder's generated code** against sample
data and diffs the produced table against an **oracle**.

> **Plain-English note on "oracle":** an *oracle* is just a pre-computed
> **answer key** — the exact rows, columns, and counts the output is supposed to
> have. For our data that answer key lives in `loan_master`'s
> `fixture-manifest.json` (e.g. "filter the 500 loans to the ones marked
> `Current` → there must be exactly 290 rows"). **"Re-verify the oracle"** means
> *we don't trust that file's claims — we open the real CSV and count for
> ourselves.* Done 2026-06-13: the file's answers (290 / 210 / 500 / 78) match
> the real data exactly, so the answer key is trustworthy.

**Quarantine** the existing e2e proof suite (exclude it from the build so its
green can no longer mislead) rather than extend it.

Salvage only:
- `data/loan_master.csv` — genuine 500×78 mortgage data;
- the `loan_master` oracle numbers, **after re-deriving them from the CSV
  ourselves** (done 2026-06-13: 500 rows / 78 cols / `loan_status=Current`→290 /
  `months_delinquent>0`→210, all exact);
- if proven useful as a reference (not as trusted tests): the serverless DAG
  renderer (`DataprocCreateBatchOperator`) and the local-Docker-proof pattern
  (real Builder → real run → oracle compare), which is the *good* pattern buried
  in the suite.

## Considered options

- **Salvage + repair the suite** — rejected: making ~30 render-theater tests
  trustworthy costs more than rebuilding, and drags the false-green philosophy
  forward.
- **Fresh including data** — rejected: throws away genuinely real data and a
  correct oracle for no trust gain; re-verifying the existing CSV is enough.

## Trade-off

We forfeit sunk effort and must rebuild execution plumbing. Accepted because
**trust is the binding constraint**: a green suite that never runs the real
Builder on real data is worse than no suite. The good pattern already present
(real Builder → real run → oracle compare) is adopted; the bad pattern (render +
assert-on-strings) is retired.

## Consequences — how quarantine MUST be marked (operator-mandated 2026-06-13)

Quarantined code must be **impossible for a future agent or human to mistake for
a source of truth.** When the quarantine is executed it MUST do all of the
following — nothing quarantined may be left looking live:

1. **Never runs / never reports green.** Excluded from every Gradle test task
   (`test`, `fastPrTest`, `backendIntegrationTest`, `runtimeNightlyTest`) so it
   can never produce a misleading "green."
2. **Banner on every quarantined file.** A header comment at the top of each
   file:
   `// QUARANTINED — ADR 0005 (2026-06-13). Render-only proof suite; its "green"`
   `// never meant the pipeline works. NOT a source of truth. Do NOT trust, run,`
   `// extend, or copy from this. See docs/adr/0005-fresh-behavioral-harness.md.`
3. **Marker file in the directory.** A `QUARANTINED.md` at the root of the
   quarantined tree restating the above and pointing here.
4. **Recorded in the living map.** `docs/PULSE-MAP.md` lists exactly what is
   quarantined and where the fresh harness lives, so the next session is not
   surprised.

Reminder for all future work (operator, 2026-06-13): the Builder ("compiler" /
`codegen`) is **not wired to the LLM today** (see ADR 0002). Any pre-existing
test that reported the Builder "working" was reporting on hand-written fixtures
or rendered strings, **not** real LLM-grounded generation. Treat all such green
as meaningless until *we* run the real Builder on real data and read the output.
