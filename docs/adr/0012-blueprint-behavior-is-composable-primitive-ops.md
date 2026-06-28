# Blueprint behavior is a composable list of self-describing primitive ops

Status: accepted — Branch 2 of the Builder-architecture grill; operator-signed-off 2026-06-15. Builds on ADR 0011 (deterministic schema = the enforced contract).

## Context

The Builder must know each blueprint's behavior to emit code and infer its schema (ADR 0011). Today that knowledge lives in a hardcoded **per-blueprint Java `switch`** (`SchemaPropagationService.deriveBaseOutputSchema:810-854` + `CodeGenerationService` per-bpKey branches), which: doesn't scale ("adding a blueprint" needs Java edits, breaking a PULSE goal); conflates column-shape, row-effects, and emission; and routes unknown blueprints to an LLM fallback — the exact thing ADR 0011 removes. The grill established that every blueprint's behavior decomposes into a small set of **primitive operations**.

## Decision

1. **A blueprint's data behavior = ONE ordered list of self-describing ops.** Each op declares its `schema-effect` (columns), `row-effect` (row population), and `side-output` (extra tables). Ops are NOT bucketed by rows-vs-columns — each self-describes whatever it does. **No op may equal a blueprint name** (an op==blueprint is a rename, not a decomposition — the operator's key catch).
2. **The op vocabulary is CLOSED and enumerable** (**32 ops** — exact set below; the atomic-blueprint ops `union-all`/`distinct-union`/`sort`/`sample-limit` are already in the set, not additions):
   - **Column:** add-column · transform-values · drop-columns · keep-columns · rename-columns · change-types · mask-columns · flatten-json · build-struct
   - **Combine/reshape:** join · group-and-aggregate · union-all · distinct-union · sort · sample-limit
   - **Row:** filter-rows · deduplicate · route-rows (N outputs) · merge-rows
   - **History:** track-history-scd2 · take-periodic-snapshot
   - **Quality (GX):** check-data · emit-report
   - **Movement:** read-source · add-audit-columns · write-sink
   - **Control (portless):** sense · schedule-and-triggers · rollback · advance-time · invoke-remote
   - **Power-user:** `sql-model` (a SQL-chaining blueprint's chain element — carries user-authored dbt SQL; schema-effect = the SQL's output columns, derived by **Calcite-validating the SQL against its input schema** or **declared**; row-effect/emission per the SQL; dbt-SQL emission). Closed op carrying user content, analogous to `add-column` carrying a user expression — NOT a free-form op. (ADR 0013.)

   New behavior = compose existing ops; a genuinely new op is added rarely, with its own handler + tests. **No free-form / LLM-interpreted ops** — that would reintroduce the fuzziness ADR 0011 removed. (`window-function` is NOT an op — it's `add-column` with a window expression.)
3. **Blueprints DECLARE their op-list as metadata** (the currently-dead `schema_behavior` field, redefined), replacing the hardcoded switch. This makes "adding a blueprint easy" and keeps schema 100% deterministic (ADR 0011) — inference applies the column-affecting ops in order.
4. **Intent-is-canonical.** Decompose a blueprint to what it is *meant* to do; the current pass-through / `SELECT *` / LLM-fallback / wrong-column behaviors are **bugs** (the fix-list), not the spec.
5. **Emission is blueprint-declared** (PySpark / dbt-SQL / dbt-snapshot / GX / DAG-only), never LLM-chosen. The Airflow DAG is the universal substrate (one per pipeline; every blueprint contributes a task).
6. **Atomic blueprints:** a primitive op may be exposed as a single-op blueprint. Several already are (GenericFilter=`filter-rows`, GenericAggregate=`group-and-aggregate`, GenericJoin=`join`); **new ones are added for `union-all`, `distinct-union`, `sample-limit`, and `sort`** (operator-confirmed 2026-06-15) so no op is unused.

## Lock-basis

All 39 surviving (non-deprecated) blueprints decompose cleanly into these ops — **no op==blueprint, no undeclared effect** — verified in `docs/blueprints/OP-VOCABULARY-AND-DECOMPOSITION.md`. The **12 fix-items** in that doc are the concrete "fix-S1 / fix-the-Builder" build worklist (biggest: BronzeToSilverCleaning does nothing today; SCD2/SnapshotModel schema rules are transposed; GenericRouter's N-outputs; 5 modeling blueprints still hit the LLM; aggregate output types; join same-type collision; DQ overwrite→append).

## Related adjacent decisions (this branch)

- **Quarantine** = a managed table, auto-generated, composed from `filter-rows` + auto-materialize (no new op; a `check-data` side-output — no path→table step forced on the developer).
- **GX + dbt-on-Spark run as Dataproc jobs on GCP** (scale; GCP track, parallel to G1's Dataproc-Serverless).
- **UI** is a committed *separate* lane (dedicated grill + running obligations list + capability audit once contracts stabilize) — task #10.
- **Param-tiering** (ADR 0023) still needs a metadata flag — open Builder branch.

## Considered

- **Fixed menu of ~9 blueprint-kinds (one kind per blueprint)** — rejected: behaviors compose (aggregate-then-rename) and the universal add/remove layer is on every blueprint, so one-kind-per-blueprint can't express reality.
- **Free-form recipe / mini-DSL** — rejected: an open op set the compiler interprets reintroduces fuzziness and isn't byte-exact-testable.
- **Op-per-blueprint (status-quo switch)** — rejected: a rename, not a decomposition; doesn't scale; needs Java per blueprint.

## Trade-off

Adding a blueprint now requires declaring its op-list (more than leaning on an LLM guess). Accepted — a predictable, testable, composable contract beats frictionless-but-unpredictable, and the op set is closed and small.
